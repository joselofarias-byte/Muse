package io.zer0.muse.ui.chat

import io.zer0.common.resultOf
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.134 P1-5: tarjeta de tarea Coordinator — Estado de la tarjeta de tareas y lógica de reintento extraída de ChatViewModel.
 *
 * Responsabilidades:
 *  - [updateTaskCardPhase]: Actualizar etapa de tarjeta de tarea(PLANNING / EXECUTING / DONE)
 *  - [updateTaskCardStep]: Actualizar con precisión un solo TaskStep,Evite la reconstrucción completa de la tabla
 *  - [toggleTaskCardExpand]: Alternar expansión/contraer tarjeta de tareas
 *  - [toggleTaskStepPause]: Pausar/reanudar un paso de herramienta que aún no se ha iniciado
 *  - [toggleTaskCardPause]: Pausar/reanudar todos los pasos de la herramienta en la tarjeta de tareas que aún no se han iniciado
 *  - [retryFailedStep]: Reintentar pasos fallidos en una tarjeta de tarea(El usuario activa activamente)
 *  - [isToolResultSuccess]: Determinación del éxito/fracaso del resultado de la ejecución de la herramienta
 *
 * descripción del diseño:
 *  - Mismo origen que ChatStreamCoordinator / ChatMiscCoordinator,Todo pasó [ChatStateAccessor] leer y escribir state,
 *    No hay dependencia inversa de ChatViewModel.
 *  - launchStream El tema permanece en ChatViewModel(Debido a la captura de una gran cantidad de variables de cierre.,El riesgo de retirada total y regresión es alto.,
 *    prensa project_memory Lessons Learned),Sus llamadas internas a updateTaskCardPhase/updateTaskCardStep
 *    Reenviado a este Coordinador a través de ChatViewModel.
 */
class ChatTaskCardCoordinator(
    private val accessor: ChatStateAccessor,
    private val toolRegistry: ToolRegistry,
    /** Phase 3: Tiempo de espera de ejecución de reintento de un solo paso(Predeterminado 2 minutos,Alinear con el tiempo de espera de ejecución de la herramienta);Las pruebas pueden inyectar valores más cortos. */
    private val retryTimeoutMs: Long = DEFAULT_RETRY_TIMEOUT_MS,
) {
    private val routeGuard = io.zer0.muse.tools.ToolRouteExecutionGuard(toolRegistry)

    /** Actualizar etapa de tarjeta de tarea(PLANNING / EXECUTING / DONE). */
    fun updateTaskCardPhase(taskCardId: String?, phase: TaskCardPhase) {
        // v1.0.53: send_sticker-only La escena no crea una tarjeta.,TaskCardId se puede dejar vacío y omitir directamente.
        if (taskCardId == null) return
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(phase = phase)))
        }
    }

    /**
     * v1.98 (P-TOOL): Actualizar con precisión un solo TaskStep,Evite la reconstrucción completa de la tabla mapValues ​​+ mapIndexed.
     *
     * La implementación anterior recorre todo el mapa de taskCards + la lista de pasos de cada tarjeta cada vez que cambia el estado de los pasos de la herramienta.,
     * Las devoluciones de llamadas de alta frecuencia de onProgress provocan tormentas de reorganización de Compose. La nueva implementación localiza directamente por clave + actualizaciones por índice.
     */
    fun updateTaskCardStep(taskCardId: String?, stepIndex: Int, transform: (TaskStep) -> TaskStep) {
        // v1.0.53: send_sticker-only La escena no crea una tarjeta.,TaskCardId se puede dejar vacío y omitir directamente.
        if (taskCardId == null) return
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            if (stepIndex !in card.steps.indices) return@update state
            val newSteps = card.steps.toMutableList()
            newSteps[stepIndex] = transform(newSteps[stepIndex])
            // v1.0.47 P8-3: Expandir automáticamente cuando falla la herramienta TaskCard,Permitir que los usuarios vean los detalles del error inmediatamente
            val shouldAutoExpand = newSteps[stepIndex].status == TaskStepStatus.FAILED
            state.copy(
                taskCards = state.taskCards + (
                    taskCardId to card.copy(
                        steps = newSteps,
                        isExpanded = if (shouldAutoExpand) true else card.isExpanded,
                    )
                    ),
            )
        }
    }

    /** Pasos de actualización por ID de llamada_herramienta estable,Evite que el índice original se desplace después del filtrado silencioso de la herramienta. */
    fun updateTaskCardStepByToolCallId(taskCardId: String?, toolCallId: String, transform: (TaskStep) -> TaskStep) {
        if (taskCardId == null || toolCallId.isBlank()) return
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            val index = card.steps.indexOfFirst { it.toolCallId == toolCallId }
            if (index < 0) return@update state
            val newSteps = card.steps.toMutableList()
            newSteps[index] = transform(newSteps[index])
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(steps = newSteps)))
        }
    }

    /** Leer la intención de pausa del paso actual,Para uso en puertas de seguridad dinámicas antes de la ejecución de la herramienta. */
    fun isTaskStepPauseRequested(taskCardId: String?, toolCallId: String, fallbackIndex: Int = -1): Boolean {
        val steps = taskCardId?.let { accessor.snapshot.taskCards[it]?.steps }
        val step =
            steps?.let {
                if (toolCallId.isNotBlank()) {
                    it.firstOrNull { s -> s.toolCallId == toolCallId }
                } else {
                    it.getOrNull(fallbackIndex)
                }
            }
        return step?.pauseRequested == true
    }

    /**
     * Reclame atómicamente un paso pendiente y escriba EN EJECUCIÓN.
     *
     * El botón de pausa puede llegar al mismo tiempo que el hilo de ejecución.;No puedes simplemente leer la pausa solicitada primero y luego escribirla por separado. RUNNING,
     * De lo contrario existe“El usuario acaba de hacer clic en pausa pero la herramienta aún está liberada.”ventana. Devolver falso indica que el paso se ha pausado.
     */
    fun markTaskStepRunningIfNotPaused(
        taskCardId: String?,
        toolCallId: String,
        fallbackIndex: Int,
        transform: (TaskStep) -> TaskStep,
    ): Boolean {
        if (taskCardId == null) return true
        var marked = false
        accessor.update { state ->
            val card = state.taskCards[taskCardId]
            if (card == null) {
                marked = true
                return@update state
            }
            val index =
                if (toolCallId.isNotBlank()) {
                    card.steps.indexOfFirst { it.toolCallId == toolCallId }
                } else {
                    fallbackIndex
                }
            if (index !in card.steps.indices) {
                marked = true
                return@update state
            }
            val step = card.steps[index]
            if (step.pauseRequested) return@update state
            val newSteps = card.steps.toMutableList()
            newSteps[index] = transform(step)
            marked = true
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(steps = newSteps)))
        }
        return marked
    }

    /** Alternar el estado expandido/contraído de la tarjeta de tareas. */
    fun toggleTaskCardExpand(taskCardId: String) {
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(isExpanded = !card.isExpanded)))
        }
    }

    /**
     * Cambie un paso de herramienta que aún no se haya iniciado.
     *
     * Las herramientas en funcionamiento no admiten el aborto forzado:Una herramienta de efectos secundarios desconocida puede haber cruzado el límite de la irreversibilidad,
     * Por lo tanto, sólo se permite el paso PENDIENTE para establecer la intención de pausa.,por [ToolOrchestrator] Lea nuevamente antes de ejecutar.
     */
    fun toggleTaskStepPause(taskCardId: String, stepId: String) {
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            val target = card.steps.firstOrNull { it.id == stepId } ?: return@update state
            if (target.status != TaskStepStatus.PENDING) return@update state
            val newSteps = card.steps.map { step ->
                if (step.id == stepId) {
                    step.copy(pauseRequested = !step.pauseRequested)
                } else {
                    step
                }
            }
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(steps = newSteps)))
        }
    }

    /**
     * Alterna la intención de pausa de todos los pasos de la tarjeta de tareas que aún no se han iniciado.
     *
     * EN EJECUCIÓN / Los pasos completados permanecen en su estado original,prevenir"Toda la tarjeta está suspendida."Malinterpretado como un efecto secundario externo de la matanza que se realiza.
     */
    fun toggleTaskCardPause(taskCardId: String) {
        accessor.update { state ->
            val card = state.taskCards[taskCardId] ?: return@update state
            val pending = card.steps.filter { it.status == TaskStepStatus.PENDING }
            if (pending.isEmpty()) return@update state
            val pause = pending.any { !it.pauseRequested }
            val newSteps = card.steps.map { step ->
                if (step.status == TaskStepStatus.PENDING) {
                    step.copy(pauseRequested = pause)
                } else {
                    step
                }
            }
            state.copy(taskCards = state.taskCards + (taskCardId to card.copy(steps = newSteps)))
        }
    }

    /**
     * Error al reintentar la tarjeta de tarea/Paso de tiempo de espera.
     * - stepId = "ALL_FAILED":Reintentar todos los pasos reintentables(FAILED / TIMED_OUT)
     * - stepId = ID de paso específico: reintentar un único paso reintentable
     *
     * Phase 3 Mejora de la confiabilidad:
     *  - Reintento de un solo paso [retryTimeoutMs] restricción,Tiempo de espera para escribir de nuevo [TaskStepStatus.TIMED_OUT];
     *  - Cancelar(CancellationException)no ser tragado — Primero escribe los pasos como [TaskStepStatus.CANCELLED]
     *    Tíralo hacia arriba otra vez,Evite que la interfaz de usuario se quede atascada en EJECUCIÓN para siempre.
     *
     * Nota:Reintentar solo actualiza el estado de la interfaz de usuario(RUNNING → SUCCESS / FAILED / TIMED_OUT / CANCELLED),
     * No volver a solicitar LLM(Los parámetros de la herramienta se conservan en pasos.).
     * Si necesita dejar que LLM continúe en función de nuevos resultados,Los usuarios deben regenerar manualmente.
     */
    fun retryFailedStep(taskCardId: String, stepId: String) {
        val taskCard = accessor.snapshot.taskCards[taskCardId] ?: return
        accessor.coroutineScope.launch {
            val stepsToUpdate = if (stepId == "ALL_FAILED") {
                taskCard.steps.filter { it.status.isRetryable }
            } else {
                taskCard.steps.filter { it.id == stepId && it.status.isRetryable }
            }
            if (stepsToUpdate.isEmpty()) return@launch

            // marcado como RUNNING
            accessor.update {
                it.copy(
                    taskCards = it.taskCards.mapValues { (k, v) ->
                        if (k == taskCardId) {
                            v.copy(
                                phase = TaskCardPhase.EXECUTING,
                                steps = v.steps.map { s ->
                                    if (stepsToUpdate.any { it.id == s.id }) {
                                        s.copy(
                                            status = TaskStepStatus.RUNNING,
                                            startedAt = System.currentTimeMillis(),
                                            finishedAt = null,
                                            result = "",
                                            pauseRequested = false,
                                        )
                                    } else {
                                        s
                                    }
                                },
                            )
                        } else {
                            v
                        }
                    },
                )
            }

            // Vuelva a ejecutar uno por uno — Reparación de auditoría (2.6): Priorizar lo completo rawArgs(La implementación original sólo trunca 200 caracteres.
            // detail,Se producirá un error al volver a intentarlo después de que se trunquen los parámetros complejos de la herramienta.);rawArgs Volver al detalle sólo cuando esté vacío.
            stepsToUpdate.forEach { step ->
                val startedAt = System.currentTimeMillis()
                // v2.6.2(Auditoría de herramientas): Los reintentos automáticos omiten la aprobación/Riesgos de los permisos —— Ajuste la ruta de reintento original directamente
                // routeGuard.executeFromJson, sin pasar ToolPermissionResolver / ALWAYS_DENY /
                // Tarjeta/gancho de aprobación. Para las herramientas de efectos secundarios que han sido rechazadas por el usuario durante la aprobación, haga clic en“Inténtalo de nuevo”se disparará de nuevo.
                // Agregue una puerta conservadora aquí: reintente automáticamente solo herramientas que no sean ALTAS;HIGH Herramientas (send_/delete_/browser_/ui_ etc)
                // Ya no se reenviará automáticamente, solicitando al usuario que reinicie (siguiendo el enlace de aprobación normal).
                val retryRisk = io.zer0.muse.tools.ToolPermissionResolver.riskLevelFor(step.title)
                if (retryRisk == io.zer0.muse.tools.ToolRiskLevel.HIGH) {
                    writeRetriedStep(
                        taskCardId = taskCardId,
                        stepId = step.id,
                        status = TaskStepStatus.FAILED,
                        result = "[Requiere intervención] ${step.title} es una herramienta de alto riesgo y no se reintenta sola. Vuelve a intentarlo para confirmar.",
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        autoExpand = true,
                    )
                    return@forEach
                }
                val retryArgs = step.rawArgs.ifBlank { step.detail }
                // Phase 3: reintentar establecer withTimeoutOrNull;resultOf Garantizado para no tragar. CancellationException,
                // Aquí nuevamente captura explícita para distinguir."tiempo de espera(null)"con"Cancelación externa(tirar)".
                val retryResult: String? = try {
                    withTimeoutOrNull(retryTimeoutMs) {
                        when (
                            val r = resultOf {
                                routeGuard.executeFromJson(step.title, retryArgs)
                            }
                        ) {
                            is io.zer0.common.Result.Success -> r.data
                            is io.zer0.common.Result.Error -> "Reintentar excepción de ejecución: ${r.message}"
                        }
                    }
                } catch (ce: CancellationException) {
                    // Las cancelaciones deben propagarse hacia arriba.(La persona que llama es responsable de cerrar),Pero primero escribe los pasos en el estado final antes de tirarlos.
                    withContext(NonCancellable) {
                        writeRetriedStep(
                            taskCardId = taskCardId,
                            stepId = step.id,
                            status = TaskStepStatus.CANCELLED,
                            result = "[Cancelado] Se canceló el reintento de ${step.title}",
                            startedAt = startedAt,
                            finishedAt = System.currentTimeMillis(),
                            autoExpand = false,
                        )
                        updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)
                    }
                    throw ce
                }

                val timedOut = retryResult == null
                val toolResult = retryResult
                    ?: "[Tiempo agotado] El reintento de ${step.title} superó ${retryTimeoutMs / 1000} s sin respuesta y se detuvo"
                val isSuccess = !timedOut && isToolResultSuccess(toolResult)
                writeRetriedStep(
                    taskCardId = taskCardId,
                    stepId = step.id,
                    status = when {
                        timedOut -> TaskStepStatus.TIMED_OUT
                        isSuccess -> TaskStepStatus.SUCCESS
                        else -> TaskStepStatus.FAILED
                    },
                    result = toolResult,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    autoExpand = !isSuccess,
                )
            }

            // Reintento completado,volver atrás DONE
            updateTaskCardPhase(taskCardId, TaskCardPhase.DONE)
        }
    }

    /**
     * Phase 3: Escriba el estado final del reintento en el paso especificado según el estado(Tarjeta de expansión automática opcional para mostrar detalles de fallas).
     */
    private fun writeRetriedStep(
        taskCardId: String,
        stepId: String,
        status: TaskStepStatus,
        result: String,
        startedAt: Long,
        finishedAt: Long,
        autoExpand: Boolean,
    ) {
        accessor.update {
            it.copy(
                taskCards = it.taskCards.mapValues { (k, v) ->
                    if (k == taskCardId) {
                        // v1.0.47 P8-3: Expandir automáticamente cuando falla la herramienta TaskCard,Permitir que los usuarios vean los detalles del error inmediatamente
                        v.copy(
                            isExpanded = if (autoExpand) true else v.isExpanded,
                            steps = v.steps.map { s ->
                                if (s.id == stepId) {
                                    s.copy(
                                        status = status,
                                        result = result,
                                        startedAt = startedAt,
                                        finishedAt = finishedAt,
                                    )
                                } else {
                                    s
                                }
                            },
                        )
                    } else {
                        v
                    }
                },
            )
        }
    }

    /**
     * Determinar si el resultado de la ejecución de la herramienta es exitoso(P2-22:Delegado árbitro compartido [io.zer0.muse.tools.ToolResultJudge],
     * con subagente/tareas programadas/Las pruebas comparten la misma implementación.,Eliminar la deriva del juicio de subcadenas chinas).
     */
    fun isToolResultSuccess(result: String): Boolean = io.zer0.muse.tools.ToolResultJudge.isSuccess(result)

    private companion object {
        /** Phase 3: Tiempo de espera de ejecución de reintento de un solo paso(2 minutos,Alineación con el tiempo de espera de la herramienta de ToolOrchestrator). */
        const val DEFAULT_RETRY_TIMEOUT_MS = 120_000L
    }
}
