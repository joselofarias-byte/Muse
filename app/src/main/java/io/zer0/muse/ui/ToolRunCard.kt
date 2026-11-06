package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R
import io.zer0.muse.ui.chat.processIconFor
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * Tarjeta de grupo de operación —— poner**Llamadas continuas a herramientas**agregados en una sola tarjeta.
 *
 * - estado plegado(Predeterminado):resumen de una línea(N operaciones realizadas + cadena de nombres de herramientas);
 * - estado expandido:Reutilizar uno por uno [ToolCallCard],Sea coherente con la visualización detallada de una única llamada de herramienta.
 *
 * Agrupado por [io.zer0.muse.ui.chat.ChatDisplayGrouper] decidir;Acceso a renderizado ver ChatScreen ——
 * La primera representación de esta tarjeta en el grupo.,Las barras restantes del grupo no se renderizan.(El índice de mensajes permanece sin cambios,desplazarse/No es necesario ajustar los aspectos más destacados).
 */
@Composable
internal fun ToolRunCard(
    msgs: List<UIMessage>,
    modifier: Modifier = Modifier,
    /**
     * v2.6.7 fix(Auditoría de tarjeta): Procesador de devolución de tarjetas.
     *
     * La implementación anterior no pasa este parámetro, lo que da como resultado show_card en la tarjeta de operación del grupo sin un puente interactivo:
     * Si la misma carta aparece sola, se podrá puntuar consecutivamente. ≥2 Una vez que se ha agrupado una llamada de herramienta, no se puede hacer clic en ella.
     * Los parámetros son (messageId, action), implementado uniformemente por la persona que llama.
     */
    onCardAction: ((messageId: String, action: io.zer0.muse.ui.markdown.CardAction) -> Unit)? = null,
    /** El estado en tiempo real de este grupo (si se está ejecutando/qué se está ejecutando/en qué paso está pensando). */
    live: ToolRunLiveState = ToolRunLiveState(),
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val toolInfos = msgs.mapNotNull { it.toolCallInfo }
    // v2.0.1: También se representan grupos de pensamiento puro (plegado de proceso) y solo se omiten los grupos completamente vacíos.
    if (msgs.isEmpty()) return

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Solo queda una línea en el estado plegado para su revisión: el estado de ejecución en tiempo real se ha movido a la barra plana sobre el campo de entrada.
                    // Admitir aquí un relleno superior e inferior de 12 dp es un desperdicio de pantalla.
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                ToolRunHeaderRow(msgs = msgs, toolInfos = toolInfos, expanded = expanded)
                ToolRunLiveRow(
                    msgs = msgs,
                    isActive = live.isActive,
                    progressMessage = live.progressMessage,
                )
            }
            ToolRunExpandedRows(
                msgs = msgs,
                expanded = expanded,
                live = live,
                onCardAction = onCardAction,
            )
        }
    }
}

/**
 * El "estado en tiempo real" del grupo de procesos.
 *
 * Empaquetado en un objeto en lugar de tres parámetros: los parámetros de construcción de la tarjeta ya están adjuntos al umbral de verificación estática,
 * Estos tres elementos son semánticamente lo mismo (si este grupo está vivo y qué están haciendo ahora).
 */
internal data class ToolRunLiveState(
    /** Si este grupo todavía se está ejecutando (solo el grupo de transmisión actual es verdadero). */
    val isActive: Boolean = false,
    /** Copia del progreso de la herramienta actual (como \"Ejecutando browser_navigate…”). */
    val progressMessage: String? = null,
    /** El ID del mensaje que se está transmitiendo actualmente se utiliza para determinar qué paso está \"bajo consideración\". */
    val activeMessageId: String? = null,
)

/**
 * Segunda fila: ¿Qué está haciendo ahora?
 *
 * Esta fila es un "estado de vida", no una "lista terminada". Entonces:
 *  - Redacción de progreso de la herramienta → Mostrar tal cual ("Ejecutando browser_navigate…")
 *  - No hay avances pero estoy pensando en algo. → Muestra el último párrafo del texto pensado (una sola línea, toma la cola)
 *  - tampoco → "Pensando…"
 *  - Todo el grupo ha terminado de correr. → Esta fila no aparece en absoluto.
 *
 * ¿Por qué no dejar una línea después de ejecutarla? El número de herramienta ya está escrito arriba./Piensa en los números/tiempo total invertido,
 * Cuelga otra cuerda `a -> b -> c` El nombre de la herramienta es solo el registro de ejecución, ni estado ni legible.
 */
@Composable
private fun ToolRunLiveRow(
    msgs: List<UIMessage>,
    isActive: Boolean,
    progressMessage: String?,
) {
    if (!isActive) return
    val activity = resolveLiveActivity(
        msgs = msgs,
        progressMessage = progressMessage,
        thinkingPlaceholder = stringResource(R.string.chat_process_thinking_now),
    )
    Spacer(Modifier.size(4.dp))
    Text(
        text = activity,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** El número máximo de palabras mostradas en estado de tiempo real. Una línea puede contener aproximadamente esta cantidad, si se excede alguna, tome la cola. */
private const val LIVE_STATUS_CHARS = 90

/** poner salto de línea/Una fórmula coincidente que comprime espacios en blanco consecutivos en un solo espacio: la línea de estado se cuenta en cada fotograma y no se edita sobre la marcha. */
private val whitespaceRun = Regex("\\s+")

/**
 * Vista previa de una línea de lo que estoy pensando.
 *
 * tomar**cola**: Esta es la salida de transmisión. La última frase es en lo que está pensando en este momento.
 * Y sólo cuando la etiqueta se mueve parece que todavía está ejecutándose.
 */
private fun thinkingPreview(raw: String?): String? {
    val flat = raw.orEmpty().replace(whitespaceRun, " ").trim()
    if (flat.isEmpty()) return null
    return if (flat.length <= LIVE_STATUS_CHARS) flat else "…" + flat.takeLast(LIVE_STATUS_CHARS)
}

/**
 * Descubra el texto activo que debería mostrarse actualmente.
 *
 * Piensa en tomar**cola**: Esta es la salida de transmisión. La última frase es en lo que está pensando ahora.
 * Y solo puedes verlo cuando la etiqueta se está moviendo.“Todavía corriendo”.
 * Cuando el texto pensado no cae en el mensaje (la solicitud acaba de enviarse y el primer token no ha llegado), se proporciona un marcador de posición.
 * De lo contrario, la fila parpadeará hacia adentro y hacia afuera, como si estuviera atascada.
 */
private fun resolveLiveActivity(
    msgs: List<UIMessage>,
    progressMessage: String?,
    thinkingPlaceholder: String,
): String {
    if (!progressMessage.isNullOrBlank()) return progressMessage
    val raw = msgs.lastOrNull()?.reasoning?.takeIf { it.isNotBlank() }
        ?: msgs.lastOrNull { !it.reasoning.isNullOrBlank() }?.reasoning
    val flat = raw.orEmpty().replace(whitespaceRun, " ").trim()
    return when {
        flat.isEmpty() -> thinkingPlaceholder
        flat.length <= LIVE_STATUS_CHARS -> flat
        else -> "…" + flat.takeLast(LIVE_STATUS_CHARS)
    }
}

/**
 * v2.6.7: El área de expansión de la tarjeta de operación grupal.
 *
 * de [ToolRunCard] sacar —— La función original ha superado el umbral de detekt LongMethod,
 * Y esta es la única parte que continúa creciendo con el tiempo (cada vez que agrega parámetros interactivos a la tarjeta, crece un poco más).
 */
@Composable
private fun ToolRunExpandedRows(
    msgs: List<UIMessage>,
    expanded: Boolean,
    live: ToolRunLiveState,
    onCardAction: ((messageId: String, action: io.zer0.muse.ui.markdown.CardAction) -> Unit)?,
) {
    AnimatedVisibility(
        visible = expanded,
        enter = MuseMotion.expandFadeEnter(),
        exit = MuseMotion.expandFadeExit(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            msgs.forEach { m ->
                val info = m.toolCallInfo
                val reasoningText = m.reasoning
                if (info != null) {
                    // Cada herramienta ocupa una fila plegable, que está contraída de forma predeterminada. —— con plataforma lateral
                    // thinkingBlock / toolIndicator Mantén el mismo ritmo:
                    // Los grupos se pliegan y cada elemento del grupo también se pliega individualmente para evitar que se extiendan docenas de líneas a la vez.
                    ProcessStepItem(
                        title = info.toolName,
                        state = ProcessStepState(
                            subtitle = if (info.isSuccess) null else info.result.take(80).ifBlank { null },
                            isFailed = !info.isSuccess && info.result.isNotBlank(),
                            trailing = info.durationMs?.takeIf { it > 0 }?.let { formatToolDuration(it) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ToolCallCard(
                            toolName = info.toolName,
                            arguments = info.arguments,
                            result = info.result,
                            isSuccess = info.isSuccess,
                            durationMs = info.durationMs,
                            cardId = m.id.toString(),
                            onCardAction = onCardAction?.let { handler ->
                                { action -> handler(m.id.toString(), action) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (reasoningText?.isNotBlank() == true) {
                    // Piensa en el mismo conjunto de filas plegables, pero haz dos cosas más:
                    //  1. El título habla por estado —— Si todavía estás pensando en ello, simplemente escribe "Pensando en ello"…”, no uses el tiempo perfecto
                    //  2. Debajo de la línea de resumen, proporcione una línea de vista previa del texto; de lo contrario, una ronda de cuatro reflexiones serán cuatro líneas de "Pensamiento completado".
                    //     Si quieres ver claramente lo que está pensando, tienes que hacer clic en ellos uno por uno.
                    val thinkingNow = live.isActive && m.id.toString() == live.activeMessageId
                    ProcessStepItem(
                        title = stringResource(
                            if (thinkingNow) {
                                R.string.chat_process_thinking_now
                            } else {
                                R.string.chat_process_thinking_done
                            },
                        ),
                        state = ProcessStepState(
                            // Sólo dé una vista previa al "pensar": cada pensamiento histórico se dividirá en una línea, que extenderá la lista.
                            // Además, lo que se le ocurrió ya se ha incorporado al texto principal, y otra línea es sólo ruido.
                            detailLine = if (thinkingNow) thinkingPreview(reasoningText) else null,
                            // El título cambiará entre "Pensamiento" y "Pensamiento completado", y la clave plegable debe usar un valor estable.
                            itemKey = "think_${m.id}",
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            shape = MuseShapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = reasoningText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(MusePaddings.iconPadding),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * El calibre estadístico de un conjunto de mensajes de proceso (alineados con los tres recuentos de ProcessFold del lado de la plataforma).
 *
 * - toolCount: El número real de llamadas a herramientas.
 * - thinkingCount: El número de veces con contenido pensado pero párrafos independientes (el mismo párrafo solo se cuenta una vez)
 * - unsuccessfulCount: Número de veces que la herramienta se completó pero falló
 *
 * El resumen de primer nivel solo se basa en estos tres números y solo puede ver los detalles en el área ampliada.
 */
internal data class ProcessTally(
    val toolCount: Int,
    val thinkingCount: Int,
    val unsuccessfulCount: Int,
) {
    val isRunning: Boolean get() = toolCount > 0 && unsuccessfulCount < 0

    companion object {
        fun of(msgs: List<UIMessage>): ProcessTally {
            var tools = 0
            var thinking = 0
            var failed = 0
            msgs.forEach { msg ->
                val info = msg.toolCallInfo
                if (info != null) {
                    tools += 1
                    if (!info.isSuccess && info.result.isNotBlank()) failed += 1
                }
                if (!msg.reasoning.isNullOrBlank()) thinking += 1
            }
            return ProcessTally(tools, thinking, failed)
        }
    }
}

/**
 * [ProcessStepItem] estatus secundario.
 *
 * Se sintetiza un objeto para mantener los parámetros de entrada de la línea de plegado de un solo paso dentro del umbral de verificación estática;
 * La persona que llama completa los campos según sea necesario y los deja en blanco si no es necesario.
 */
internal data class ProcessStepState(
    val subtitle: String? = null,
    val isFailed: Boolean = false,
    val trailing: String? = null,
    /**
     * Una línea separada de vista previa de texto debajo de la línea de resumen (truncamiento de una sola línea).
     *
     * con [subtitle] La diferencia:subtitle Comparte el mismo ancho que el título y no cabe en texto largo;
     * Tenga en cuenta que la vista previa debe estar en su propia línea; de lo contrario, el título quedará desplazado.
     */
    val detailLine: String? = null,
    /** Clave estable para estado colapsado. Si no se aprueba, volverá al título, pero se debe aprobar cuando el título cambie. */
    val itemKey: String? = null,
)

/**
 * Filas plegables de un solo paso dentro de grupos de procesos.
 *
 * El enfoque en el lado de la plataforma es: el grupo en sí se pliega, y cada elemento del grupo (un pensamiento, una llamada de herramienta) también se pliega por separado.
 * La línea abstracta siempre se muestra y el texto principal se muestra solo cuando se hace clic. De esta manera, cuando se ejecutan una docena de herramientas en una ronda, el grupo expandido no se convertirá en docenas de filas de paredes.
 *
 * @param title Redacción principal de línea abstracta (nombre de la herramienta / "Pensamiento"…")
 * @param subtitle Descripción secundaria opcional (fragmento del motivo del fallo, etc.)
 * @param isFailed Si se trata de un estado de falla (la línea de resumen está en color de error)
 * @param trailing Información de cola opcional (consumo de tiempo, etc.)
 * @param content Texto ampliado
 */
@Composable
private fun ProcessStepItem(
    title: String,
    modifier: Modifier = Modifier,
    state: ProcessStepState = ProcessStepState(),
    content: @Composable () -> Unit,
) {
    val subtitle = state.subtitle
    val isFailed = state.isFailed
    val trailing = state.trailing
    val detailLine = state.detailLine
    // key Se da prioridad al valor estable dado por la persona que llama: el título del elemento de pensamiento cambiará entre "Pensamiento" y "Pensamiento completado".
    // El uso del título como clave hará que el elemento que el usuario acaba de expandir se pliegue hacia atrás en el momento en que finalice.
    var expanded by rememberSaveable(state.itemKey ?: title) { mutableStateOf(false) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                // El fallo sigue siendo un triángulo de advertencia (el estado tiene prioridad sobre el tipo). Normalmente presione el ícono de tipo de acción para este paso.
                imageVector = if (isFailed) MuseIcons.alertTriangle else processIconFor(title),
                contentDescription = null,
                tint = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!trailing.isNullOrBlank()) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp),
            )
        }
        // Vista previa del texto: puede ver lo que hace este paso sin expandirlo.
        if (!detailLine.isNullOrBlank()) {
            Text(
                text = detailLine,
                style = MaterialTheme.typography.labelSmall,
                color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 2.dp, bottom = 2.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = MuseMotion.expandFadeEnter(),
            exit = MuseMotion.expandFadeExit(),
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
                content()
            }
        }
    }
}

/**
 * v2.6.7: Encabezado de resumen de tarjetas de operaciones agrupadas (icono + cantidad + marca de falla + tiempo total empleado + flecha de colapso).
 *
 * de [ToolRunCard] Extracción: el encabezado en sí es una pieza de semántica independiente,
 * También devuelva el LongMethod de la función principal dentro del umbral.
 */
@Composable
private fun ToolRunHeaderRow(msgs: List<UIMessage>, toolInfos: List<io.zer0.ai.core.ToolCallInfo>, expanded: Boolean) {
    // Resumen de primer nivel: Puedes ver de un vistazo cuántas cosas has hecho, cuántas veces lo has pensado y cuántas veces has fallado.
    // Vea cada paso en detalle después de desplegarlo. —— Consistente con la estructura de dos pisos del lado de la plataforma.
    val tally = ProcessTally.of(msgs)
    val counts = buildList {
        if (tally.toolCount > 0) {
            add(stringResource(R.string.chat_process_tool_count, tally.toolCount))
        }
        if (tally.thinkingCount > 0) {
            add(stringResource(R.string.chat_process_thinking_count, tally.thinkingCount))
        }
        if (tally.unsuccessfulCount > 0) {
            add(stringResource(R.string.chat_process_failed_count, tally.unsuccessfulCount))
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = MuseIcons.bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = counts.ifEmpty { listOf(stringResource(R.string.chat_tool_run_summary, msgs.size)) }
                .joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Dar una señal de advertencia cuando hay un paso fallido,También puedes verlo plegado de un vistazo.
        if (tally.unsuccessfulCount > 0) {
            Icon(
                imageVector = MuseIcons.alertTriangle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
        // El estado plegado muestra el tiempo total de ejecución de este grupo de herramientas: los usuarios pueden juzgar directamente
        // “¿Es la lentitud de la herramienta o la lentitud del modelo?”, puedes verlo sin expandirlo.
        val totalDurationMs = toolInfos.mapNotNull { it.durationMs }.sum()
        if (totalDurationMs > 0) {
            Text(
                text = formatToolDuration(totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * La herramienta realiza un formateo legible que requiere mucho tiempo.
 *
 * Muestra milisegundos de menos de 1 segundo (p. ej. `320ms`), de lo contrario muestra segundos (un decimal, como `1.8s`),
 * Muestra minutos y segundos durante un minuto (p. ej. `1m20s`). Sea breve y no quite el enfoque visual del nombre de la herramienta y del resumen.
 */
internal fun formatToolDuration(durationMs: Long): String = when {
    durationMs < 1000L -> "${durationMs}ms"
    durationMs < 60_000L -> String.format(java.util.Locale.US, "%.1fs", durationMs / 1000.0)
    else -> {
        val totalSec = durationMs / 1000L
        "${totalSec / 60}m${totalSec % 60}s"
    }
}
