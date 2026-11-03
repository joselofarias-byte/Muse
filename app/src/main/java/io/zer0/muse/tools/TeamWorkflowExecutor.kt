package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * v1.200: Ejecutor del flujo de trabajo del equipo.
 *
 * Admite tres modos de orquestación:
 * - de serie(SEQUENTIAL):Ejecutar en orden de nodos,Resultados de pedidos anticipados como contexto de pedidos posteriores
 * - paralelo(PARALLEL):Ningún nodo dependiente se ejecuta simultáneamente,Finalmente fusionar según la estrategia de agregación.
 * - Condiciones(CONDITIONAL):Determinar si ejecutar en función de resultados anteriores(Actualmente implementado como un simple interruptor.)
 *
 * Si el equipo no ha configurado el flujo de trabajo,Luego se degrada a la ejecución en serie en el orden de los miembros.
 *
 * v1.201: Nuevo [llmAggregator] parámetros,LLM_REVIEW La estrategia de agregación consiste en [LlmAggregator.review]
 * Revisión integral completa;llmAggregator Cuando el LLM_REVIEW nulo se degrada a EXPERT_REVIEW.
 * v1.201: Nuevo [pauseManager] + [pausePolicy] parámetros,Flujo de trabajo del equipo de soporte antes de la ejecución/antes de que cada miembro ejecute
 * Punto de pausa en la colaboración entre humanos y máquinas;pauseManager Cuando es nulo, se omiten todos los puntos de pausa.
 *
 * v1.202:
 *  - Nuevo [delegationChainTracker] parámetros,Sincronice el estado del nodo secundario con el rastreador de enlaces al ejecutar cada nodo miembro,
 *    Habilite las tarjetas de enlace de la interfaz de usuario para mostrar la estructura de árbol de los flujos de trabajo del equipo(parentRequestId El mecanismo inserta automáticamente nodos secundarios en los nodos principales. subNodes).
 *  - Nuevo [chatService] parámetros,CONDITIONAL Utilice LLM para juzgar antes de la ejecución del nodo"Si este nodo debe ejecutarse en función de los resultados anteriores",
 *    Responda NO para omitir(marcado como skipped);chatService CONDICIONAL vuelve a la vieja lógica cuando es nulo(Ejecución directa).
 *  - Una vez completado el flujo de trabajo, el árbol de resultados se construye como [DelegationContract.DelegationResult.subResultTree].
 */
class TeamWorkflowExecutor(
    /** Devolución de llamada para ejecutar una única solicitud de delegado,Generalmente se pasa en SkillExecutor::delegateAgent. */
    private val delegate: suspend (DelegationContract.DelegationRequest) -> DelegationContract.DelegationResult,
    /** v1.201: LLM Agregador de reseñas completo,LLM_REVIEW Utilizado en estrategia;Se degrada a EXPERT_REVIEW cuando es nulo. */
    private val llmAggregator: LlmAggregator? = null,
    /**
     * Cambio de revisión integral de Global LLM([io.zer0.muse.data.MultiAgentConfig.llmReviewEnabled]).
     * Al cerrar, LLM_REVIEW baja a EXPERT_REVIEW —— Anteriormente, el interruptor era ajustable en la interfaz de usuario pero no tenía un punto de lectura de producción.,
     * Apagarlo tampoco funciona. El valor predeterminado es verdadero para mantener el comportamiento anterior de las personas que llaman sin conexión.
     */
    private val llmReviewEnabled: Boolean = true,
    /** v1.201: Gestor de pausas delegado,null para omitir todos los puntos de pausa. */
    private val pauseManager: DelegationPauseManager? = null,
    /** v1.201: política de pausa,Solo tiene efecto cuando pausaManager no es nulo. */
    private val pausePolicy: DelegationPauseManager.PausePolicy = DelegationPauseManager.PausePolicy(),
    /** v1.202: Rastreador de enlaces delegados,Sincronizar el estado de ejecución de los miembros del equipo con la tarjeta de enlace de la interfaz de usuario;Cuando es nulo, el vínculo no se registra. */
    private val delegationChainTracker: DelegationChainTracker? = null,
    /** v1.202: ChatService,Juicio condicional de LLM para nodos CONDICIONALES;Cuando es nulo, el nodo CONDICIONAL se degrada a ejecución directa. */
    private val chatService: ChatService? = null,
    /** v1.0.53: Limitador de corriente concurrente global(Requerido,Inyectado por SkillExecutor). Ajuste del tiempo de ejecución del nodo paralelo limiter.run,Evite que el paralelismo del equipo + la delegación del agente maestro superpongan la concurrencia. */
    private val concurrencyLimiter: AgentConcurrencyLimiter,
    /** v1.0.53 Phase 2: Registro de recuperación de puntos de interrupción del flujo de trabajo;Saltar lectura y escritura del diario cuando sea nulo(Sin posibilidad de currículum). */
    private val journal: WorkflowJournal? = null,
) {

    /**
     * v1.202: CONDITIONAL El conjunto de ID de solicitud de omisión se registra cuando el nodo determina NO.
     * Apoyado por ConcurrentHashMap Set,async Seguridad de escritura concurrente(Alineación ejecutada en modo H-TWE1).
     * TeamWorkflowExecutor Cree una nueva instancia para cada ejecución en SkillExecutor,Entonces no es necesario aclarar.
     */
    private val skippedRequestIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private sealed interface ConditionalDecision {
        data object Execute : ConditionalDecision
        data object Skip : ConditionalDecision
        data class Failed(val reason: String) : ConditionalDecision
    }

    /**
     * Ejecutar el flujo de trabajo del equipo.
     *
     * @param workflow Definición de flujo de trabajo
     * @param teamTask Descripción general de la misión del equipo.
     * @param parentRequestId solicitud de los padres id,Se utiliza para generar subsolicitudes. id
     * @param teamMembers Lista de ID de asistente de miembro del equipo,Como orden degenerado cuando flujo de trabajo.nodes está vacío
     * @param baseContext Compartir mensajes contextuales(Opcional)
     * @return Resultados de delegación agregados,subResults Contiene resultados para cada nodo.
     */
    suspend fun execute(
        workflow: DelegationContract.TeamWorkflow,
        teamTask: String,
        parentRequestId: String,
        teamMembers: List<String>,
        baseContext: List<UIMessage> = emptyList(),
        /** v1.0.53 Phase 2: journal run id;Cuando es nulo, los metadatos del resultado se generan y escriben automáticamente para un currículum posterior. */
        runId: String? = null,
        /** v1.0.53 Phase 2: Ya sea para restaurar desde el diario(resume=true El nodo que llega al caché se devolverá en segundos.,Se reinicia el primer nodo sin caché.). */
        resume: Boolean = false,
    ): DelegationContract.DelegationResult {
        val startedAt = System.currentTimeMillis()
        val nodes = workflow.nodes.takeIf { it.isNotEmpty() }
            ?: teamMembers.mapIndexed { idx, assistantId ->
                DelegationContract.TeamWorkflowNode(
                    id = "step-$idx",
                    assistantId = assistantId,
                    name = "pasos del equipo $idx",
                    taskTemplate = teamTask,
                )
            }

        if (nodes.isEmpty()) {
            return errorResult(parentRequestId, "El equipo no tiene miembros.,No se puede ejecutar el flujo de trabajo", startedAt)
        }

        // verificar assistantId
        val invalidNodes = nodes.filter { it.assistantId.isBlank() }
        if (invalidNodes.isNotEmpty()) {
            return errorResult(parentRequestId, "Falta el nodo de flujo de trabajo assistantId: ${invalidNodes.map { it.id }}", startedAt)
        }

        // v1.0.53 Phase 2: generar/Reutilizar runId,Crear mapeo nodeSeq + cálculo expectedKeys
        val effectiveRunId = runId ?: "workflow-$startedAt-${(100..999).random()}"
        val nodeSeqMap: Map<String, Int> = nodes.mapIndexed { idx, node -> node.id to idx }.toMap()
        val expectedKeys: Map<Int, String> = if (journal != null && resume) {
            nodes.associate { node ->
                val seq = nodeSeqMap[node.id]!!
                val prompt = node.taskTemplate.ifBlank { teamTask }
                val identity = "${node.id}|${node.assistantId}|${node.taskTemplate}"
                seq to journal.computeKey(prompt, identity)
            }
        } else {
            emptyMap()
        }

        // H-TWE1: Utilice ConcurrentHashMap en su lugar mutableMapOf,async Seguridad de escritura concurrente
        val executed = java.util.concurrent.ConcurrentHashMap<String, DelegationContract.DelegationResult>()
        val errors = mutableListOf<String>()

        // v1.0.53 Phase 2: resume caché de precarga,Los nodos de impacto se completan directamente en ejecutados y se eliminan de pendientes
        val cachedNodeIds = mutableSetOf<String>()
        if (journal != null && resume) {
            val resumeResult = journal.resume(effectiveRunId, expectedKeys)
            resumeResult.cached.forEach { (seq, entry) ->
                val node = nodes.getOrNull(seq) ?: return@forEach
                // delegation nodo de clase:Acceso y uso de caché(result tal cual);llm_generative:vacio,Todavía necesito correr de nuevo
                if (entry.nodeKind != WorkflowJournal.NODE_KIND_LLM_GENERATIVE && entry.result.isNotEmpty()) {
                    executed[node.id] = DelegationContract.DelegationResult(
                        requestId = "$parentRequestId/${node.id}",
                        success = true,
                        resultText = entry.result,
                        metadata = DelegationContract.DelegationResult.ResultMetadata(
                            startedAt = entry.ts,
                            finishedAt = entry.ts,
                            durationMs = 0,
                            assistantId = node.assistantId,
                            assistantName = node.name.ifBlank { node.id },
                        ),
                    )
                    cachedNodeIds.add(node.id)
                }
            }
            Logger.d("TeamWorkflowExecutor", "journal resume runId=$effectiveRunId: ${cachedNodeIds.size}/${nodes.size} Caché de aciertos de nodo")
        }

        try {
            // v1.201: Pausa antes de la ejecución del equipo(pauseBeforeTeam)
            if (pauseManager != null && pausePolicy.pauseBeforeTeam) {
                if (pauseManager.isCancelled(parentRequestId)) {
                    return errorResult(parentRequestId, "Flujo de trabajo del equipo cancelado por el usuario", startedAt)
                }
                val pauseReq = DelegationPauseManager.PauseRequest(
                    requestId = "pause-$parentRequestId-before-team",
                    taskId = parentRequestId,
                    taskTitle = "Confirmación antes de la ejecución del flujo de trabajo del equipo",
                    taskDescription = teamTask,
                    targetType = "team",
                    targetName = parentRequestId,
                    reason = "Confirmación antes de la ejecución del flujo de trabajo del equipo",
                    options = listOf(
                        DelegationPauseManager.PauseOption.APPROVE,
                        DelegationPauseManager.PauseOption.REJECT,
                        DelegationPauseManager.PauseOption.CANCEL,
                    ),
                )
                val resp = pauseManager.awaitPauseDecision(pauseReq, pausePolicy)
                when (resp.decision) {
                    DelegationPauseManager.PauseDecision.CANCEL,
                    DelegationPauseManager.PauseDecision.REJECT,
                    ->
                        return errorResult(parentRequestId, "El usuario cancela el flujo de trabajo del equipo", startedAt)
                    DelegationPauseManager.PauseDecision.APPROVE,
                    DelegationPauseManager.PauseDecision.MODIFY,
                    -> { /* continuar */ }
                }
            }

            // En capas por topología de dependencia:Ejecutar sin dependencias primero,Una vez completado, desbloquea los nodos que dependen de él.
            // v1.0.53 Phase 2: Los nodos que han llegado a la caché del diario se eliminan de pendientes(No es necesario volver a ejecutar)
            val pending = nodes.filter { it.id !in cachedNodeIds }.toMutableList()
            while (pending.isNotEmpty()) {
                val ready = pending.filter { node ->
                    node.dependsOn.all { executed.containsKey(it) }
                }
                if (ready.isEmpty()) {
                    return errorResult(
                        parentRequestId,
                        "El flujo de trabajo tiene dependencias circulares o dependencias no resueltas: ${pending.map { it.id }}",
                        startedAt,
                    )
                }

                // Los nodos predecesores que fallan o son omitidos por una condición no pueden desbloquear los nodos sucesores.
                // De lo contrario, un guardia devuelve NO después,Los nodos de efectos secundarios que dependen de él seguirán ejecutándose.
                val blocked = ready.filter { node ->
                    node.dependsOn.any { dependencyId ->
                        val dependency = executed[dependencyId] ?: return@any false
                        !dependency.success ||
                            skippedRequestIds.contains("$parentRequestId/$dependencyId")
                    }
                }
                blocked.forEach { node ->
                    val childRequestId = "$parentRequestId/${node.id}"
                    val inheritedSkip = node.dependsOn.any { dependencyId ->
                        skippedRequestIds.contains("$parentRequestId/$dependencyId")
                    }
                    val blockedResult =
                        if (inheritedSkip) {
                            skippedRequestIds.add(childRequestId)
                            skippedResult(childRequestId)
                        } else {
                            errorResult(
                                childRequestId,
                                "Error en el paso previo,El nodo ha sido bloqueado para su ejecución.",
                                startedAt,
                            )
                        }
                    executed[node.id] = blockedResult
                    delegationChainTracker?.onDelegationStarted(
                        requestId = childRequestId,
                        parentRequestId = parentRequestId,
                        task = node.taskTemplate.ifBlank { teamTask },
                        targetType = "assistant",
                        targetId = node.assistantId,
                        targetName = node.name.ifBlank { node.id },
                    )
                    delegationChainTracker?.onDelegationFinished(
                        requestId = childRequestId,
                        success = blockedResult.success,
                        resultText = if (inheritedSkip) "(saltado: No se cumplen las condiciones previas)" else "",
                        error = blockedResult.error,
                    )
                    recordToJournal(
                        effectiveRunId,
                        nodeSeqMap[node.id] ?: -1,
                        node.taskTemplate.ifBlank { teamTask },
                        node,
                        if (inheritedSkip) "(skipped)" else blockedResult.error.orEmpty(),
                        if (inheritedSkip) "done" else "failed",
                        WorkflowJournal.NODE_KIND_LLM_DETERMINISTIC,
                    )
                    errors.addAll(blockedResult.collectErrors())
                    pending.remove(node)
                }

                val executableReady = ready.filterNot { blocked.contains(it) }
                if (executableReady.isEmpty()) continue

                // v1.201: Pausa antes de que cada miembro ejecute(pauseBeforeEachMember)
                if (pauseManager != null && pausePolicy.pauseBeforeEachMember) {
                    if (pauseManager.isCancelled(parentRequestId)) {
                        return errorResult(parentRequestId, "Flujo de trabajo del equipo cancelado por el usuario", startedAt)
                    }
                    executableReady.forEach { node ->
                        val pauseReq = DelegationPauseManager.PauseRequest(
                            requestId = "pause-$parentRequestId-${node.id}",
                            taskId = "$parentRequestId/${node.id}",
                            taskTitle = node.name.ifBlank { node.id },
                            taskDescription = node.taskTemplate.ifBlank { teamTask },
                            targetType = "assistant",
                            targetName = node.assistantId,
                            reason = "miembro del equipo ${node.name.ifBlank { node.id }} Confirmar antes de la ejecución",
                            options = listOf(
                                DelegationPauseManager.PauseOption.APPROVE,
                                DelegationPauseManager.PauseOption.REJECT,
                                DelegationPauseManager.PauseOption.CANCEL,
                            ),
                        )
                        val resp = pauseManager.awaitPauseDecision(pauseReq, pausePolicy)
                        when (resp.decision) {
                            DelegationPauseManager.PauseDecision.CANCEL ->
                                return errorResult(parentRequestId, "El usuario cancela el flujo de trabajo del equipo", startedAt)
                            DelegationPauseManager.PauseDecision.REJECT -> {
                                // saltar este nodo,Marcar como fallido
                                executed[node.id] = errorResult(
                                    "$parentRequestId/${node.id}",
                                    "El usuario se niega a ejecutar el nodo.",
                                    System.currentTimeMillis(),
                                )
                                pending.remove(node)
                            }
                            DelegationPauseManager.PauseDecision.APPROVE,
                            DelegationPauseManager.PauseDecision.MODIFY,
                            -> { /* Continuar la ejecución */ }
                        }
                    }
                    // Si se rechazan todos los nodos listos,Continuar a la siguiente ronda.
                    if (executableReady.all { executed.containsKey(it.id) }) {
                        pending.removeAll(executableReady)
                        continue
                    }
                }

                val runnable = executableReady.filterNot { executed.containsKey(it.id) }
                if (runnable.isEmpty()) continue

                // Los nodos en la misma capa determinan serie o paralelo según el modo.
                val sequential = runnable.first().mode == DelegationContract.TeamWorkflowNode.Mode.SEQUENTIAL
                val layerResults: List<DelegationContract.DelegationResult> = if (sequential) {
                    runnable.map { node ->
                        executed[node.id] ?: executeNode(node, teamTask, parentRequestId, baseContext, executed, effectiveRunId, nodeSeqMap[node.id] ?: -1).also {
                            executed[node.id] = it
                        }
                    }
                } else {
                    executeParallel(runnable, teamTask, parentRequestId, baseContext, executed, effectiveRunId, nodeSeqMap).also {
                        executed.putAll(it)
                    }.values.toList()
                }

                errors.addAll(layerResults.flatMap { it.collectErrors() })
                pending.removeAll(runnable)
            }

            val orderedResults = nodes.mapNotNull { executed[it.id] }
            val resultText = aggregateResults(workflow.aggregationStrategy, orderedResults, teamTask)
            val finishedAt = System.currentTimeMillis()

            // v1.202: Construir árbol de subresultados,Proporciona un árbol de interfaz de usuario para mostrar el estado de ejecución de los miembros del flujo de trabajo del equipo.
            val subResultTree = buildSubResultTree(nodes, orderedResults, teamTask)

            return DelegationContract.DelegationResult(
                requestId = parentRequestId,
                success = errors.isEmpty(),
                resultText = resultText,
                error = errors.firstOrNull(),
                metadata = DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    durationMs = finishedAt - startedAt,
                    journalRunId = effectiveRunId,
                ),
                subResults = orderedResults,
                subResultTree = subResultTree,
            )
        } catch (e: Exception) {
            return errorResult(parentRequestId, "Excepción de ejecución del flujo de trabajo: ${e.message}", startedAt)
        }
    }

    private suspend fun executeNode(
        node: DelegationContract.TeamWorkflowNode,
        teamTask: String,
        parentRequestId: String,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
        /** v1.0.53 Phase 2: journal run id(null Saltar registros del diario cuando). */
        runId: String? = null,
        /** v1.0.53 Phase 2: Número de serie del nodo(Utilizado para registros de diario.;-1 salto de tiempo). */
        nodeSeq: Int = -1,
    ): DelegationContract.DelegationResult {
        val childRequestId = "$parentRequestId/${node.id}"
        val task = buildNodeTask(node, teamTask, executed)

        // v1.202 Transformación 2: juicio de condición verdadera del nodo CONDICIONAL.
        // Requerido cuando falta el servicio condicional, la llamada falla o devuelve algo distinto de SÍ/NO fail-closed,no puedo poner
        // “incapaz de juzgar”Como“permitir la ejecución”,De lo contrario, las delegaciones posteriores podrían tener efectos secundarios externos.
        if (node.mode == DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL &&
            node.dependsOn.isNotEmpty()
        ) {
            when (val decision = evaluateConditional(node, executed)) {
                ConditionalDecision.Execute -> Unit
                is ConditionalDecision.Failed -> {
                    val reason = decision.reason
                    delegationChainTracker?.onDelegationStarted(
                        requestId = childRequestId,
                        parentRequestId = parentRequestId,
                        task = task,
                        targetType = "assistant",
                        targetId = node.assistantId,
                        targetName = node.name.ifBlank { node.id },
                    )
                    delegationChainTracker?.onDelegationFinished(
                        requestId = childRequestId,
                        success = false,
                        resultText = "",
                        error = reason,
                    )
                    recordToJournal(
                        runId,
                        nodeSeq,
                        task,
                        node,
                        reason,
                        "failed",
                        WorkflowJournal.NODE_KIND_LLM_DETERMINISTIC,
                    )
                    return errorResult(childRequestId, reason, System.currentTimeMillis())
                }
                ConditionalDecision.Skip -> {
                    // Marcado como omitido:resultTextDejar en blanco,Resultados agregados naturalmente isNotBlank() filtrar
                    skippedRequestIds.add(childRequestId)
                    // Sincronizar con el rastreador de enlaces:Iniciar + finalizar inmediatamente(skipped Estado)
                    // usar "(saltado)" como vista previa del resultado,UI Las tarjetas pueden mostrar directamente el motivo de la omisión
                    delegationChainTracker?.onDelegationStarted(
                        requestId = childRequestId,
                        parentRequestId = parentRequestId,
                        task = task,
                        targetType = "assistant",
                        targetId = node.assistantId,
                        targetName = node.name.ifBlank { node.id },
                    )
                    delegationChainTracker?.onDelegationFinished(
                        requestId = childRequestId,
                        success = true,
                        resultText = "(saltado: Condición no cumplida)",
                    )
                    // v1.0.53 Phase 2: CONDITIONAL Una determinación de NO es un resultado determinista.(temperatura=0),registrado como llm_deterministic
                    recordToJournal(runId, nodeSeq, task, node, "(skipped)", "done", WorkflowJournal.NODE_KIND_LLM_DETERMINISTIC)
                    return skippedResult(childRequestId)
                }
            }
        }

        val contextMessages = buildNodeContext(node, baseContext, executed)

        // v1.202 Modificación 1: Notificar al rastreador de enlaces que comienza la delegación
        // parentRequestId El mecanismo insertará automáticamente este nodo secundario en el nodo principal. subNodes
        delegationChainTracker?.onDelegationStarted(
            requestId = childRequestId,
            parentRequestId = parentRequestId,
            task = task,
            targetType = "assistant",
            targetId = node.assistantId,
            targetName = node.name.ifBlank { node.id },
        )

        val request = DelegationContract.DelegationRequest(
            requestId = childRequestId,
            task = task,
            targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
            targetId = node.assistantId,
            parentSessionId = parentRequestId,
            contextMessages = contextMessages,
            timeoutSec = 120,
        )
        val result = delegate(request)

        // v1.202 Modificación 1: Notificar al rastreador de enlaces que la delegación está completa,Actualizar el estado del nodo secundario
        delegationChainTracker?.onDelegationFinished(
            requestId = childRequestId,
            success = result.success,
            resultText = result.resultText,
            error = result.error,
        )

        // v1.0.53 Phase 2: Registre los resultados del nodo de delegación a journal(delegation clase,Los resultados de la herramienta se pueden almacenar en caché)
        recordToJournal(
            runId,
            nodeSeq,
            task,
            node,
            result.resultText,
            if (result.success) "done" else "failed",
            WorkflowJournal.NODE_KIND_DELEGATION,
        )
        return result
    }

    /**
     * v1.0.53 Phase 2: Registre los resultados del nodo en journal(ignorar null runId / -1 nodeSeq).
     */
    private suspend fun recordToJournal(
        runId: String?,
        nodeSeq: Int,
        task: String,
        node: DelegationContract.TeamWorkflowNode,
        result: String,
        status: String,
        nodeKind: String,
    ) {
        if (journal == null || runId == null || nodeSeq < 0) return
        val identity = "${node.id}|${node.assistantId}|${node.taskTemplate}"
        val key = journal.computeKey(task, identity)
        journal.record(runId, nodeSeq, key, result, status, nodeKind)
    }

    private suspend fun executeParallel(
        nodes: List<DelegationContract.TeamWorkflowNode>,
        teamTask: String,
        parentRequestId: String,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
        /** v1.0.53 Phase 2: journal run id. */
        runId: String? = null,
        /** v1.0.53 Phase 2: nodo id → seq cartografía. */
        nodeSeqMap: Map<String, Int> = emptyMap(),
    ): Map<String, DelegationContract.DelegationResult> = coroutineScope {
        nodes.associate { node ->
            node.id to async {
                // v1.0.53: Los nodos paralelos también adoptan la vista global. limiter,Evite que el paralelismo del equipo + la delegación del agente maestro superpongan la concurrencia.
                concurrencyLimiter.run {
                    executeNode(node, teamTask, parentRequestId, baseContext, executed, runId, nodeSeqMap[node.id] ?: -1)
                }
            }
        }.mapValues { it.value.await() }
    }

    private fun buildNodeTask(
        node: DelegationContract.TeamWorkflowNode,
        teamTask: String,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): String {
        val base = node.taskTemplate.ifBlank { teamTask }
        if (node.dependsOn.isEmpty()) return base
        val dependencySummary = node.dependsOn.joinToString("\n\n") { depId ->
            val dep = executed[depId]
            val header = "## Pasos preliminares $depId resultado"
            when {
                dep == null -> "$header: (No ejecutado)"
                dep.success -> "$header:\n${dep.resultText.take(2000)}"
                else -> "$header:\nerror: ${dep.error ?: "error desconocido"}"
            }
        }
        return """$base

$dependencySummary
        """.trimIndent()
    }

    private fun buildNodeContext(
        node: DelegationContract.TeamWorkflowNode,
        baseContext: List<UIMessage>,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): List<UIMessage> {
        val messages = baseContext.toMutableList()
        if (node.mode == DelegationContract.TeamWorkflowNode.Mode.CONDITIONAL && node.dependsOn.isNotEmpty()) {
            // nodo de condición:Inyecte resultados previos al éxito cuando el sistema lo solicite,Juicio auxiliar sobre si ejecutar/como ejecutar
            val conditionContext = node.dependsOn.mapNotNull { executed[it] }
                .filter { it.success }
                .joinToString("\n") { "[${it.requestId}]: ${it.resultText.take(500)}" }
            if (conditionContext.isNotBlank()) {
                messages.add(
                    UIMessage(
                        role = MessageRole.SYSTEM,
                        content = "Continuar la ejecución cuando se cumplan las siguientes condiciones::\n$conditionContext",
                    ),
                )
            }
        }
        return messages
    }

    /** B3-08: Resultado experto similitud de 3 gramos(0~1),para la agrupación por consenso. */
    private fun expertTextSimilarity(a: String, b: String): Double {
        fun grams(s: String): Set<String> {
            val clean = s.lowercase().filter { it.isLetterOrDigit() }
            return if (clean.length >= 3) clean.windowed(3, 1).toSet() else setOf(clean)
        }
        val ga = grams(a)
        val gb = grams(b)
        val union = ga.union(gb)
        return if (union.isEmpty()) 0.0 else ga.intersect(gb).size.toDouble() / union.size
    }

    private suspend fun aggregateResults(
        strategy: DelegationContract.TeamWorkflow.AggregationStrategy,
        results: List<DelegationContract.DelegationResult>,
        teamTask: String,
    ): String {
        val successful = results.filter { it.success && it.resultText.isNotBlank() }
        if (successful.isEmpty()) return "(No hay resultados disponibles)"

        return when (strategy) {
            DelegationContract.TeamWorkflow.AggregationStrategy.MERGE -> {
                buildString {
                    appendLine("Resumen de los resultados de la ejecución del equipo:")
                    successful.forEachIndexed { idx, r ->
                        appendLine()
                        appendLine("--- subresultados ${idx + 1} [${r.metadata.assistantName ?: r.metadata.assistantId ?: r.requestId}] ---")
                        appendLine(r.resultText)
                    }
                }.trimEnd()
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.VOTE -> {
                // voto simple:Cuente la frecuencia después de deduplicar los resultados.,Devolver los resultados más frecuentes
                val votes = successful.groupingBy { it.resultText }.eachCount()
                val best = votes.maxByOrNull { it.value }
                "Resultados de la votación(total ${successful.size} billete): Los resultados más frecuentes aparecen ${best?.value} veces\n\n${best?.key ?: ""}"
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.EXPERT_REVIEW -> {
                // B3-08: El consenso primero — Haga primero el agrupamiento por similitud,La mayoría produce directamente;Revisión integral de LLM sin consenso
                val clusters = mutableListOf<MutableList<DelegationContract.DelegationResult>>()
                for (r in successful) {
                    val target = clusters.firstOrNull { cluster ->
                        cluster.any { expertTextSimilarity(it.resultText, r.resultText) >= 0.5 }
                    }
                    if (target != null) target.add(r) else clusters.add(mutableListOf(r))
                }
                val majority = clusters.maxByOrNull { it.size }
                if (majority != null && majority.size >= 2) {
                    val consensus = majority.maxByOrNull { it.resultText.length }
                    "Consenso de expertos(${majority.size}/${successful.size}):\n\n${consensus?.resultText ?: ""}"
                } else {
                    val candidates = successful.map { r ->
                        AgentResultAggregator.Candidate(
                            source = r.metadata.assistantName
                                ?: r.metadata.assistantId
                                ?: r.requestId,
                            content = r.resultText,
                            confidence = null,
                        )
                    }
                    if (llmAggregator != null && llmReviewEnabled) {
                        val aggregation = AgentResultAggregator.aggregate(
                            candidates = candidates,
                            strategy = AgentResultAggregator.Strategy.LLM_REVIEW,
                            question = teamTask,
                            llmReviewer = llmAggregator?.let { agg -> { c, q -> agg.review(c, q) } },
                        )
                        aggregation.output
                    } else {
                        val expert = successful.maxByOrNull { it.resultText.length }
                        "Resultados de la selección de la revisión de expertos:\n\n${expert?.resultText ?: ""}"
                    }
                }
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.FIRST_SUCCESS -> {
                successful.first().resultText
            }
            DelegationContract.TeamWorkflow.AggregationStrategy.LLM_REVIEW -> {
                // v1.201: LLM Revisión completa — Convertir resultado de delegación a Candidate,
                // Llame a AgentResultAggregator.aggregate y páselo llmAggregator?.review como llmReviewer.
                // llmAggregator es nulo o cuando falla la llamada LLM,AgentResultAggregator Rebaja de categoría interna a EXPERT_REVIEW.
                val candidates = successful.map { r ->
                    AgentResultAggregator.Candidate(
                        source = r.metadata.assistantName
                            ?: r.metadata.assistantId
                            ?: r.requestId,
                        content = r.resultText,
                        confidence = null,
                    )
                }
                val aggregation = AgentResultAggregator.aggregate(
                    candidates = candidates,
                    strategy = AgentResultAggregator.Strategy.LLM_REVIEW,
                    question = teamTask,
                    // No hay transmisión cuando el interruptor está cerrado. reviewer,AgentResultAggregator Se bajará automáticamente a EXPERT_REVIEW.
                    llmReviewer = if (llmReviewEnabled) {
                        llmAggregator?.let { agg -> { c, q -> agg.review(c, q) } }
                    } else {
                        null
                    },
                )
                aggregation.output
            }
        }
    }

    private fun errorResult(requestId: String, error: String, startedAt: Long): DelegationContract.DelegationResult {
        val finishedAt = System.currentTimeMillis()
        return DelegationContract.DelegationResult(
            requestId = requestId,
            success = false,
            error = error,
            metadata = DelegationContract.DelegationResult.ResultMetadata(
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = finishedAt - startedAt,
            ),
        )
    }

    /**
     * v1.202 Modificación 2: omitir resultados para nodos CONDICIONALES.
     *
     * - success=true(Saltarse no cuenta como fracaso,No afecta la agregación de errores.)
     * - resultText dejar en blanco,Naturalmente [aggregateResults] de `it.resultText.isNotBlank()` filtrar
     * - requestId Unirse [skippedRequestIds],suministro [buildSubResultTree] marcar el estado como "skipped"
     */
    private fun skippedResult(requestId: String): DelegationContract.DelegationResult {
        val now = System.currentTimeMillis()
        return DelegationContract.DelegationResult(
            requestId = requestId,
            success = true,
            resultText = "",
            metadata = DelegationContract.DelegationResult.ResultMetadata(
                startedAt = now,
                finishedAt = now,
                durationMs = 0,
            ),
        )
    }

    /**
     * v1.202 Modificación 2: utilice LLM para determinar si se debe ejecutar el nodo CONDICIONAL.
     *
     * Puntos de implementación:
     *  - construcción corta prompt,Proporcione el resumen del resultado anterior + la tarea actual a LLM,Solicitar solo respuesta YES/NO
     *  - temperature=0 Certeza garantizada,maxTokens=10 Evite que LLM sea detallado y derrochador token
     *  - coincidencia de prioridad NO(Evite errores de juicio al contener SÍ/NO)
     *  - LLM llamada fallida/tiempo de espera/Excepción fail-closed,Evitar que el nodo se ejecute
     *  - Impide la ejecución incluso si todos los resultados anteriores están vacíos.,Evite la provocación infundada de efectos secundarios.
     */
    private suspend fun evaluateConditional(
        node: DelegationContract.TeamWorkflowNode,
        executed: Map<String, DelegationContract.DelegationResult>,
    ): ConditionalDecision {
        val previousResults = node.dependsOn.mapNotNull { depId ->
            executed[depId]?.let { dep ->
                val status = if (dep.success) "éxito" else "falló"
                "[$depId]($status): ${dep.resultText.take(500)}"
            }
        }.joinToString("\n")
        // No hay resultados previos para hacer referencia.,impedir la ejecución
        if (previousResults.isBlank()) {
            return ConditionalDecision.Failed("El juicio condicional carece de resultados previos,El nodo ha sido bloqueado para su ejecución.")
        }

        val nodeTask = node.taskTemplate.ifBlank { node.name.ifBlank { node.id } }
        val prompt = """Según los siguientes pre-resultados,Determinar si se debe realizar una tarea."$nodeTask". Responda SÍ o NO.

preresultado:
$previousResults

Simplemente responda SÍ o NO,No expliques.
        """.trimIndent()

        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                content = "Eres un juez estricto de las condiciones del flujo de trabajo.,Simplemente responda SÍ o NO,No genere nada más.",
            ),
            UIMessage(role = MessageRole.USER, content = prompt),
        )

        // resultOf{} Relanzamiento automático CancellationException,No rompe la semántica de cancelación de rutinas
        val service = chatService
            ?: return ConditionalDecision.Failed("El servicio de juicio condicional no está disponible,El nodo ha sido bloqueado para su ejecución.")
        val completion = resultOf {
            service.completeText(
                messages = messages,
                temperature = 0f,
                maxTokens = 10,
            )
        }.onError { msg, t ->
            Logger.w("TeamWorkflowExecutor", "CONDITIONAL Falló la convocatoria de LLM de juicio condicional,Ejecución de nodo bloqueada: $msg", t)
        }.getOrNull() ?: return ConditionalDecision.Failed("La decisión de juicio condicional falló,El nodo ha sido bloqueado para su ejecución.")

        val text = completion.text.trim().uppercase()
        return when {
            text.startsWith("NO") -> ConditionalDecision.Skip
            text.startsWith("YES") -> ConditionalDecision.Execute
            else -> ConditionalDecision.Failed("El juicio condicional arroja resultados irreconocibles,El nodo ha sido bloqueado para su ejecución.")
        }
    }

    /**
     * v1.202 Transformación 1: construir el resultado de la ejecución del flujo de trabajo en [DelegationContract.SubResultNode] árbol.
     *
     * Los flujos de trabajo son listas planas de miembros,Por lo tanto, los niños están vacíos.;Los niños se procesarán de forma recursiva cuando se admitan flujos de trabajo anidados en el futuro.
     * status valor:success / failed / skipped(skipped de [skippedRequestIds]).
     */
    private fun buildSubResultTree(
        nodes: List<DelegationContract.TeamWorkflowNode>,
        orderedResults: List<DelegationContract.DelegationResult>,
        teamTask: String,
    ): List<DelegationContract.SubResultNode> {
        if (orderedResults.isEmpty()) return emptyList()
        return orderedResults.mapIndexed { idx, result ->
            // Alinear con resultados ordenados:nodes También mapeado en orden ejecutado.;Si hay escasez de nodos, utilice nombres vacíos para cubrirlo.
            val node = nodes.getOrNull(idx)
            val assistantId = result.metadata.assistantId ?: node?.assistantId ?: ""
            val assistantName = result.metadata.assistantName
                ?: node?.name?.ifBlank { node.id }
                ?: result.requestId
            val task = node?.taskTemplate?.ifBlank { teamTask } ?: teamTask
            val status = when {
                skippedRequestIds.contains(result.requestId) -> "skipped"
                result.success -> "success"
                else -> "failed"
            }
            DelegationContract.SubResultNode(
                requestId = result.requestId,
                assistantId = assistantId,
                assistantName = assistantName,
                task = task,
                status = status,
                resultText = result.resultText.ifBlank { null },
                error = result.error,
                durationMs = result.metadata.durationMs,
                children = emptyList(),
            )
        }
    }
}
