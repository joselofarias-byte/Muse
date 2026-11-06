package io.zer0.muse.ui.taskcard

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.formatToolDuration
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.statusColors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/**
 * Phase 10.1: Programación completa de tarjetas de tareas.
 *
 * Mejorado basado en la Fase 8.8:
 *  - etapa de planificación independiente(PLANNING / EXECUTING / DONE)
 *  - línea de tiempo(cada paso startedAt / finishedAt,Mostrar tiempo de ejecución)
 *  - Ampliar/contraer interacción(Haga clic en el título para cambiar)
 *  - Reintentar pasos fallidos(onRetry devolución de llamada)
 *  - estilo visual(primary Barra de título degradada verde laurel,adaptarse"lámpara de mesa nocturna"Tonalidad)
 *
 * @param id tarjeta de tarea id(Vincular al mensaje del asistente id)
 * @param title Título de la tarea
 * @param steps Lista de pasos
 * @param phase etapa actual
 * @param isExpanded Ya sea para expandirse(Predeterminado true)
 * @param createdAt Marca de tiempo de creación de la tarjeta de tareas
 */
data class TaskCardData(
    val id: String,
    val title: String,
    val steps: List<TaskStep>,
    val phase: TaskCardPhase = TaskCardPhase.PLANNING,
    val isExpanded: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** progreso general(0..1),Sólo se cuenta el ÉXITO. */
    val progress: Float
        get() = if (steps.isEmpty()) {
            0f
        } else {
            steps.count { it.status == TaskStepStatus.SUCCESS }.toFloat() / steps.size
        }

    /** Si todos han alcanzado el estado final.(SUCCESS / FAILED / CANCELLED / TIMED_OUT). */
    val isAllDone: Boolean
        get() = steps.isNotEmpty() && steps.all { it.status.isTerminal }

    /** ¿Existe un paso final que se pueda volver a intentar?(FAILED / TIMED_OUT). */
    val hasFailedSteps: Boolean
        get() = steps.any { it.status.isRetryable }

    /** Si hay pasos pendientes que el usuario aún puede pausar. */
    val hasPausableSteps: Boolean
        get() = steps.any { it.status == TaskStepStatus.PENDING }

    /** Si todos los pasos a ejecutar tienen intenciones de pausa establecidas. */
    val allPausableStepsPaused: Boolean
        get() = steps.filter { it.status == TaskStepStatus.PENDING }.let { pending ->
            pending.isNotEmpty() && pending.all { it.pauseRequested }
        }

    /** Número de pasos de tiempo de espera(Para resumen plegado). */
    val timedOutSteps: Int
        get() = steps.count { it.status == TaskStepStatus.TIMED_OUT }

    /** Número de pasos cancelados(Para resumen plegado). */
    val cancelledSteps: Int
        get() = steps.count { it.status == TaskStepStatus.CANCELLED }

    /** Tiempo total invertido(milisegundos),La suma de FINALIZADO EN - INICIADO EN para todos los pasos completados. */
    val totalDurationMs: Long
        get() = steps.mapNotNull { s ->
            if (s.startedAt != null && s.finishedAt != null) s.finishedAt - s.startedAt else null
        }.sum()

    companion object {
        /**
         * Cree tarjetas de tareas a partir de la lista de llamadas de herramientas(PLANNING escenario,Todos los pasos PENDING).
         *
         * @param context Se utiliza para obtener recursos de cadena.(Título)
         */
        fun fromToolCalls(
            context: Context,
            assistantId: Uuid,
            toolCalls: List<Pair<String, String>>,
            toolCallIds: List<String> = emptyList(),
        ): TaskCardData {
            val steps = toolCalls.mapIndexed { idx, (name, args) ->
                val delegateArgs = if (name == "delegate_agent") {
                    parseDelegateAgentArgs(args)
                } else {
                    DelegateAgentArgs("", "")
                }
                TaskStep(
                    id = "${assistantId}_$idx",
                    title = name,
                    detail = stepDetailFor(name, args, delegateArgs),
                    status = TaskStepStatus.PENDING,
                    toolCallIndex = idx,
                    toolName = name,
                    source = delegateArgs.assistantId,
                    // Reparación de auditoría (2.6): Guarde los parámetros completos para volver a intentarlo,detail puede ser truncado
                    rawArgs = args,
                    toolCallId = toolCallIds.getOrNull(idx).orEmpty(),
                )
            }
            return TaskCardData(
                id = assistantId.toString(),
                title = context.getString(R.string.task_card_plan_title, steps.size),
                steps = steps,
                phase = TaskCardPhase.PLANNING,
            )
        }

        /**
         * v1.25: Analizar parámetros de la herramienta delegado_agent,Extraiga el ID del asistente y la tarea.
         */
        fun parseDelegateAgentArgs(argumentsJson: String): DelegateAgentArgs {
            return runCatching {
                val obj = io.zer0.common.AppJson.decodeFromString(JsonObject.serializer(), argumentsJson)
                val assistantId = (obj["assistantId"] as? JsonPrimitive)?.content?.trim().orEmpty()
                val task = (obj["task"] as? JsonPrimitive)?.content?.trim().orEmpty()
                DelegateAgentArgs(assistantId, task)
            }.getOrDefault(DelegateAgentArgs("", ""))
        }

        /**
         * Texto de resumen de pasos.
         *
         * Todo lo anterior `args.take(200)`,Entonces herramientas como show_card "el parámetro es el HTML completo"
         * Se colocará un JSON truncado encima de la tarjeta.——Lo que ve el usuario es `{"title":...,"code":"<style>...`,
         * Es difícil de leer y ocupa espacio vertical.,Y la carta real está debajo. Los parámetros de dichas herramientas no son resúmenes para la visualización humana.,
         * El resumen debe indicar claramente "qué tarjeta se muestra".
         */
        internal fun stepDetailFor(name: String, args: String, delegateArgs: DelegateAgentArgs): String = when {
            name == "delegate_agent" -> delegateArgs.task.take(60)
            name in CARD_TOOL_NAMES -> cardTitleOf(args).take(60)
            else -> args.take(200)
        }

        /** Tomado de los parámetros de show_card/render_data title;Si no se puede recuperar, se devolverá una cadena vacía.(No mostrar línea de resumen). */
        private fun cardTitleOf(args: String): String = runCatching {
            val obj = io.zer0.common.AppJson.decodeFromString(JsonObject.serializer(), args)
            (obj["title"] as? JsonPrimitive)?.content?.trim().orEmpty()
        }.getOrDefault("")

        /** El parámetro es una herramienta para \"todo el contenido de la tarjeta\" en lugar de un resumen legible por humanos. */
        private val CARD_TOOL_NAMES = setOf("show_card", "render_data")
    }

    /** v1.25: delegate_agent Resultados del análisis de parámetros. */
    data class DelegateAgentArgs(
        val assistantId: String,
        val task: String,
    )
}

/** Un solo paso. */
data class TaskStep(
    val id: String,
    val title: String,
    val detail: String = "",
    val status: TaskStepStatus,
    val result: String = "",
    /** Iniciar marca de tiempo de ejecución(Configurar al entrar en RUNNING). */
    val startedAt: Long? = null,
    /** marca de tiempo final(Establecer al ingresar ÉXITO / FALLADO). */
    val finishedAt: Long? = null,
    /** Herramienta correspondienteÍndice de llamadas(Se utiliza para posicionar al reintentar). */
    val toolCallIndex: Int = -1,
    /** v0.49: Texto de progreso de ejecución de herramienta(Como por ejemplo"Buscando..."),null O no se muestra cuando está vacío.RUNNING El estado se actualiza mediante la devolución de llamada SkillExecutor.onProgress. */
    val progressText: String? = null,
    /** v1.200: fuente de paso(Agent/Nombre del equipo),Se utiliza para agrupar carriles. */
    val source: String = "",
    /** v1.200: Lista de subpasos,para equipos/Desglose de los resultados del flujo de trabajo. */
    val subSteps: List<TaskStep> = emptyList(),
    /** Reparación de auditoría (2.6): Parámetros originales completos de la herramienta.(JSON),para reintentos.detail Se utiliza para la visualización de truncamiento. */
    val rawArgs: String = "",
    /**
     * v2.6.7: Nombre de la herramienta.
     *
     * El paso originalmente solo tenía título/detalle/resultado, por lo que era imposible determinar qué tipo de producto era este resultado.
     * (show_card de cartas,generate_image fotos…), por lo que los productos de la tarjeta del plan sólo pueden ser
     * Se muestra como texto truncado. Solo con el nombre de la herramienta se puede reutilizar resolveDirectOutput para la representación directa.
     */
    val toolName: String = "",
    /** Estable durante la ronda actual tool_call id,Se utiliza para asociar pausas de una sola herramienta con el estado de ejecución. */
    val toolCallId: String = "",
    /**
     * El usuario ha solicitado que se detenga este paso.,Pero la verdadera herramienta aún no se ha implementado.
     *
     * Solo se pueden alternar los pasos PENDIENTES;La herramienta que se ejecuta no realiza una matanza forzada.,Evite efectos secundarios desconocidos.
     */
    val pauseRequested: Boolean = false,
) {
    /** Un solo paso lleva tiempo(milisegundos),null Indica incompleto. */
    val durationMs: Long? get() = if (startedAt != null && finishedAt != null) finishedAt - startedAt else null
}

/** Estado del paso. */
enum class TaskStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,

    /** Ejecución cancelada(Usuario detenido activamente/transmisión interrumpida),Los pasos no se han completado. */
    CANCELLED,

    /** Tiempo de espera de ejecución de herramienta,Terminación forzosa. */
    TIMED_OUT,

    ;

    /** estado final:El estado no volverá a cambiar. */
    val isTerminal: Boolean
        get() = this == SUCCESS || this == FAILED || this == CANCELLED || this == TIMED_OUT

    /** Se puede volver a intentar:falló o se agotó el tiempo de espera(La cancelación significa que el usuario se detiene activamente,No solicite proactivamente que vuelva a intentarlo). */
    val isRetryable: Boolean
        get() = this == FAILED || this == TIMED_OUT
}

/** Etapa de la tarjeta de tareas. */
enum class TaskCardPhase(val labelRes: Int) {
    PLANNING(R.string.task_card_phase_planning),
    EXECUTING(R.string.task_card_phase_executing),
    DONE(R.string.task_card_phase_done),
}

/**
 * Phase 10.1: tarjeta de tarea Composable(Versión de programación completa).
 *
 * Visión:primary Barra de título con degradado verde laurel + parte inferior de la tarjeta + fila de pasos de la línea de tiempo.
 * interacción:Haga clic en el título para cambiar y expandir/doblar;Visualización de paso fallido"Inténtalo de nuevo"botón.
 *
 * @param data Datos de la tarjeta de tareas
 * @param onToggleExpand Alternar para expandir/doblar devolución de llamada
 * @param onRetryStep Reintentar la devolución de llamada del paso fallido(parámetros:stepId)
 * @param delegationChain v1.201: Lista de nodos raíz de enlace delegado(Opcional).
 *        Insertar debajo de la lista de pasos si no está vacío [DelegationChainCard],asistente visual maestro → Enlace de subasistente.
 *        Pasar nulo o una lista vacía no se procesará.
 * @param onCardAction v2.6.8: procesador de devolución de tarjetas (messageId, action).
 *        con tarjeta única en línea/Las tarjetas de grupo comparten el mismo procesador(ChatScreen de handleCardAction).
 *        La implementación anterior no tenía este parámetro en absoluto, por lo que la tarjeta de producto en el paso nació sin puente: se podía ver pero no hacer clic en ella.
 */
@Composable
fun TaskCard(
    data: TaskCardData,
    onToggleExpand: () -> Unit = {},
    onRetryStep: (String) -> Unit = {},
    onCancel: (() -> Unit)? = null,
    onTogglePause: () -> Unit = {},
    onToggleStepPause: (String) -> Unit = {},
    // M-TC2 Reparación: Agregar parámetro modificador,Permitir que las personas que llaman personalicen las modificaciones del diseño
    modifier: Modifier = Modifier,
    // v1.201: Enlace delegado(Opcional,Representado debajo de la lista de pasos si no está vacía)
    delegationChain: List<DelegationChainTracker.ChainNode>? = null,
    // v2.6.8: Procesador de devolución de tarjetas para tarjetas de productos —— Los parámetros son (messageId, action)
    onCardAction: ((String, io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
) {
    val accent = when (data.phase) {
        TaskCardPhase.DONE -> if (data.isAllDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        TaskCardPhase.EXECUTING -> MaterialTheme.colorScheme.primary
        TaskCardPhase.PLANNING -> MaterialTheme.colorScheme.tertiary
    }

    Surface(
        modifier = modifier
            .padding(vertical = 4.dp),
        shape = MuseShapes.medium,
        // CHAT-09: Estándares unificados para tarjetas dentro de mensajes. — Color base + trazo 1dp + esquinas redondeadas uniformes(Antes transparente e ilimitado)
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(0.dp)) {
            // ── barra de título(Haga clic para cambiar y expandir/doblar)── Unificar para delinear el estilo,No más rellenos degradados.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                // insignia de icono:Ícono de escenario + fondo redondo claro
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            when (data.phase) {
                                TaskCardPhase.DONE -> if (data.isAllDone) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                }
                                TaskCardPhase.EXECUTING ->
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                TaskCardPhase.PLANNING ->
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when (data.phase) {
                        TaskCardPhase.PLANNING -> Icon(
                            MuseIcons.edit,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                        TaskCardPhase.EXECUTING -> MuseSpinner(
                            size = 16.dp,
                            color = accent,
                        )
                        TaskCardPhase.DONE -> Icon(
                            if (data.isAllDone) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                // etiqueta de escenario + título
                Column(modifier = Modifier.weight(1f)) {
                    // v2.0.1: Información suplementaria sobre fallos cuando el estado final contiene fallos. — evitar"Completado"con"0/N"Percepciones contradictorias sobre el progreso al mismo tiempo.
                    val failedCount = data.steps.count {
                        it.status == TaskStepStatus.FAILED || it.status == TaskStepStatus.TIMED_OUT
                    }
                    val phaseLabel = if (data.phase == TaskCardPhase.DONE && failedCount > 0) {
                        stringResource(data.phase.labelRes) + " · " +
                            stringResource(R.string.task_card_summary_failed, failedCount)
                    } else {
                        stringResource(data.phase.labelRes)
                    }
                    Text(
                        text = phaseLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = data.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // números de progreso
                val successCount = data.steps.count { it.status == TaskStepStatus.SUCCESS }
                Text(
                    text = "$successCount/${data.steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                if (data.hasPausableSteps) {
                    MuseTactileButton(
                        icon = if (data.allPausableStepsPaused) MuseIcons.play else MuseIcons.pause,
                        onClick = onTogglePause,
                        contentDescription = stringResource(
                            if (data.allPausableStepsPaused) R.string.task_card_resume else R.string.task_card_pause,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                }
                Icon(
                    imageVector = if (data.isExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    contentDescription = stringResource(
                        if (data.isExpanded) R.string.action_collapse else R.string.action_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            // ── Fila de escalones compactos plegados(v1.0.52) ──
            // numero de pasos ≥ 2 Mostrar una barra de pasos de una sola línea cuando,Los usuarios pueden conocer el progreso sin ampliarlo.
            // todo hecho → "N pasos completados";En progreso → nombre del paso+Los iconos de estado están dispuestos horizontalmente.
            AnimatedVisibility(
                visible = !data.isExpanded && data.steps.size >= 2,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                CompactStepRow(
                    data = data,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // ── Contenido de la tarjeta(Mostrar cuando esté expandido)──
            AnimatedVisibility(
                visible = data.isExpanded,
                enter = MuseMotion.expandFadeEnter(),
                exit = MuseMotion.expandFadeExit(),
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // v1.200: descripción del carril(Se muestra cuando hay múltiples fuentes)
                    val sources = remember(data.steps) {
                        data.steps.map { it.source }.filter { it.isNotBlank() }.distinct()
                    }
                    if (sources.size > 1) {
                        Text(
                            text = stringResource(
                                R.string.task_card_group_by_agent,
                                sources.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // El título no se repetirá una vez: el mismo texto ya ha sido escrito en la línea doblada de arriba, y se volverá a escribir después de ampliarla.
                    // se convertirá en "Execution plan (2 steps)"Hay uno arriba y otro abajo, que parecen dos cartas.
                    // barra de progreso(Mostrado durante la ejecución)
                    if (data.phase == TaskCardPhase.EXECUTING || (data.phase == TaskCardPhase.DONE && !data.isAllDone)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(MuseShapes.pill)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(data.progress)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary, MuseShapes.pill),
                            )
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    // Lista de pasos
                    // R-UI-09: Proporcionar botón cancelar durante la ejecución,Acceso detenido por la persona que llama.
                    if (data.phase == TaskCardPhase.EXECUTING && onCancel != null) {
                        MuseTactileButton(
                            icon = MuseIcons.x,
                            onClick = onCancel,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // L-TC6 Reparación: forEach agregar key,Cuando cambia el estado del paso, Compose puede reorganizar con precisión las filas correspondientes.
                    data.steps.forEach { step ->
                        key(step.id) {
                            TaskStepRow(
                                step = step,
                                onRetry = { onRetryStep(step.id) },
                                onTogglePause = { onToggleStepPause(step.id) },
                                // v2.6.8: La tarjeta de identidad de la tarjeta de producto. = noticias id(data.id Es el mensaje del asistente el que cuelga esta tarjeta de tareas. id),
                                // Exactamente igual que una sola tarjeta en línea;El uso de toolCallId obtendrá otro espacio de identificación,
                                // La tarjeta no puede leer sus propios datos y no se puede devolver el cardId correcto al devolverla.
                                cardId = data.id,
                                onCardAction = onCardAction,
                            )
                        }
                    }
                    // v1.201: Diagrama de enlace de delegación(Opcional,Representado debajo de la lista de pasos si no está vacía)
                    if (!delegationChain.isNullOrEmpty()) {
                        DelegationChainCard(roots = delegationChain)
                    }
                    // Tiempo total invertido(DONE exhibición en el escenario)
                    if (data.phase == TaskCardPhase.DONE && data.totalDurationMs > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(
                            text = stringResource(
                                R.string.task_card_total_duration,
                                formatToolDuration(data.totalDurationMs),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Botón Reintentar todos los pasos fallidos
                    if (data.hasFailedSteps && data.phase == TaskCardPhase.DONE) {
                        MuseTactileButton(
                            icon = MuseIcons.refresh,
                            onClick = { onRetryStep("ALL_FAILED") },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** insignia del escenario(Lado izquierdo de la barra de título). */
@Composable
private fun PhaseBadge(phase: TaskCardPhase, progress: Float) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    // M-TC5 Reparación: DONE Rama: las dos ramas originales devuelven el mismo valor.(progress>=1f y <1f Todos 0.3f).
    // En lugar de ello, distingue en función del progreso:Todo exitoso(1.0)Usar alfa inferior"Completo"Color,
    // Parcialmente exitoso(<1.0,Hay pasos fallidos)Utilice alfa superior"advertencia"color.
    val (text, bg) = when (phase) {
        TaskCardPhase.PLANNING -> stringResource(phase.labelRes) to onPrimary.copy(alpha = 0.25f)
        TaskCardPhase.EXECUTING -> stringResource(phase.labelRes) to onPrimary.copy(alpha = 0.35f)
        TaskCardPhase.DONE -> if (progress >= 1f) {
            // Todo exitoso:Color de acabado suave
            stringResource(phase.labelRes) to onPrimary.copy(alpha = 0.3f)
        } else {
            // Parcialmente exitoso/hay fracaso:Énfasis de fondo ligeramente más oscuro"Necesito atención"
            stringResource(phase.labelRes) to onPrimary.copy(alpha = 0.5f)
        }
    }
    Surface(
        color = bg,
        shape = MuseShapes.medium,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = onPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// CompactStepRow — Barra de paso plegada(v1.0.52)
// numero de pasos ≥2 reemplazar el título simple,Organice los íconos de estado + nombres abreviados de cada paso horizontalmente.
// ═══════════════════════════════════════════════════════════════════════

/** Número máximo de caracteres mostrados en un solo paso(Truncamiento extralargo más puntos suspensivos). */
private const val STEP_NAME_MAX_CHARS = 8

/**
 * El espacio horizontal ocupado por la columna del icono de estado en el lado izquierdo de la fila de pasos(Icono 20dp + interlineado 8dp).
 *
 * La tarjeta de producto debe atravesar esta muesca horizontalmente y alinearse con el borde izquierdo de la tarjeta de tarea exterior.,De lo contrario, una tarjeta de ancho completo quedará presionada en aproximadamente 28dp,
 * Cuando se apila con el espacio en blanco interior, parece"La tarjeta está comprimida".
 */
internal val STEP_ICON_COLUMN_WIDTH = 28.dp

/**
 * fila compacta de escalones — pantalla plegada,Organizar los pasos horizontalmente."nombre del icono".
 *
 * - todo hecho → "N pasos completados"
 * - En progreso → cada paso "✓/⏳/✗ Nombre" Organizar horizontalmente(flecha separada)
 * - Hay pasos fallidos → Marca roja de pasos fallidos
 */
@Composable
private fun CompactStepRow(data: TaskCardData, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    if (data.steps.size < 2) return

    // Todos llegan al estado final.:Mostrar resumen
    if (data.isAllDone) {
        val failedCount = data.steps.count { it.status == TaskStepStatus.FAILED }
        val timedOutCount = data.timedOutSteps
        val cancelledCount = data.cancelledSteps
        val hasIssues = failedCount + timedOutCount + cancelledCount > 0
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (hasIssues) MuseIcons.alertTriangle else MuseIcons.circleCheck,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (hasIssues) {
                    MaterialTheme.statusColors.error
                } else {
                    MaterialTheme.statusColors.success
                },
            )
            Spacer(Modifier.size(6.dp))
            val summaryText = if (!hasIssues) {
                stringResource(R.string.task_card_all_completed, data.steps.size)
            } else {
                val parts = mutableListOf<String>()
                if (failedCount > 0) {
                    parts += stringResource(R.string.task_card_summary_failed, failedCount)
                }
                if (timedOutCount > 0) {
                    parts += stringResource(R.string.task_card_summary_timed_out, timedOutCount)
                }
                if (cancelledCount > 0) {
                    parts += stringResource(R.string.task_card_summary_cancelled, cancelledCount)
                }
                stringResource(
                    R.string.task_card_steps_summary,
                    data.steps.size,
                    parts.joinToString(" · "),
                )
            }
            Text(
                text = summaryText,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // En progreso:barra de paso
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        data.steps.forEachIndexed { index, step ->
            // icono de paso(éxito/En progreso/Por determinar/falló/tiempo de espera/Cancelar)
            val (icon, tint) = when (step.status) {
                TaskStepStatus.SUCCESS -> MuseIcons.circleCheck to MaterialTheme.statusColors.success
                TaskStepStatus.RUNNING -> MuseIcons.circle to colorScheme.primary
                TaskStepStatus.FAILED -> MuseIcons.x to MaterialTheme.statusColors.error
                TaskStepStatus.TIMED_OUT -> MuseIcons.timer to MaterialTheme.statusColors.error
                TaskStepStatus.CANCELLED -> MuseIcons.x to colorScheme.outline
                TaskStepStatus.PENDING -> MuseIcons.circle to colorScheme.outline
            }
            Icon(
                imageVector = icon,
                contentDescription = step.title,
                modifier = Modifier.size(12.dp),
                tint = tint,
            )
            Spacer(Modifier.size(2.dp))
            // Nombre abreviado
            Text(
                text = if (step.title.length > STEP_NAME_MAX_CHARS) {
                    step.title.take(STEP_NAME_MAX_CHARS) + "…"
                } else {
                    step.title
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (step.status == TaskStepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                color = if (step.status == TaskStepStatus.RUNNING) {
                    colorScheme.onSurface
                } else {
                    colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Las flechas separan los pasos(No agregar el último paso)
            if (index < data.steps.size - 1) {
                Icon(
                    imageVector = MuseIcons.arrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = colorScheme.outline,
                )
            }
        }
    }
}
