package io.zer0.muse.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.ToolCallRecord
import io.zer0.muse.ui.common.animation.MuseInkGlyph
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.common.form.MuseBottomSheet
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer

/**
 * La barra plana del proceso encima del campo de entrada.
 *
 * Por qué la necesitamos: Durante una misión, cuando la herramienta se atasca en el flujo de mensajes, ocupará la mitad de la pantalla, pero cuando está plegada, solo responde preguntas.
 * una pregunta——"¿Qué estás haciendo ahora?" Esto se puede explicar en una barra plana sin desplazar lo que estás leyendo.
 *
 * División del trabajo:
 *  - Bian Tiao: Simplemente di "en este momento" y desaparece cuando finalice la tarea.
 *  - Tarjetas de proceso en el flujo de mensajes: reservadas para revisión posterior
 *
 * Haga clic en la barra plana para que aparezcan los detalles de la ronda actual de pasos en lugar de expandirlos en el acto. —— Al expandirse en su lugar, el campo de entrada se empujará hacia arriba.
 */
@Composable
internal fun ActiveProcessBar(
    records: List<ToolCallRecord>,
    progressMessage: String?,
    subagent: SubagentActivity = SubagentActivity(),
    onExpand: () -> Unit,
) {
    if (records.isEmpty() && progressMessage.isNullOrBlank() && subagent.isEmpty) return
    val scheme = MaterialTheme.colorScheme
    val latest = records.lastOrNull()
    // La subtarea ocupa el primer lugar: es el único estado de ejecución que persistirá durante las rondas, el resto son "esta ronda".
    // A continuación, hable sobre "lo que se está ejecutando en este momento" y finalmente devuelva la descripción de la cantidad de todo el lote.
    val running = records.lastOrNull { it.finishedAt == null } ?: latest
    val statusText = when {
        subagent.count > 0 -> stringResource(R.string.chat_process_subagent_running, subagent.count)
        !progressMessage.isNullOrBlank() -> progressMessage
        running != null -> describeProcess(running)
        else -> stringResource(R.string.chat_process_thinking_now)
    }
    val failed = records.count { !it.isSuccess }

    Surface(
        color = scheme.surfaceVariant.copy(alpha = 0.72f),
        shape = MuseShapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            // Deje solo 2 dp arriba y abajo: cuando aparecen varias barras planas al mismo tiempo, el espaciado lo proporciona la lista de manera uniforme.
            // Separar cada tira dejará un espacio claro entre las dos tiras.
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp)
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MuseInkGlyph(
                modifier = Modifier.size(MuseIconSizes.iconSmall),
                animate = true,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (failed > 0) {
                Icon(
                    imageVector = MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = scheme.error,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
            if (records.isNotEmpty() && subagent.isEmpty) {
                Text(
                    text = stringResource(R.string.chat_process_tool_count, records.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    maxLines = 1,
                )
            }
            Icon(
                imageVector = MuseIcons.chevronUp,
                contentDescription = null,
                tint = scheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
        }
    }
}

/**
 * Detalles de los pasos para esta ronda.
 *
 * Utilice una hoja en lugar de una expansión in situ: la expansión in situ empujará la barra de entrada hacia arriba y cuando el usuario haga clic en esta barra
 * A menudo escribiendo.
 */
@Composable
internal fun ActiveProcessSheet(
    records: List<ToolCallRecord>,
    subagent: SubagentActivity = SubagentActivity(),
    onCancelSubagent: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            Text(
                text = stringResource(R.string.chat_process_sheet_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(8.dp))
            if (!subagent.isEmpty) {
                Text(
                    text = stringResource(R.string.chat_process_subagent_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                subagent.threads.forEach { thread ->
                    SubagentRow(label = thread.assistantId, onCancel = { onCancelSubagent(thread.threadId) })
                }
                subagent.pending
                    .filter { task -> subagent.threads.none { it.threadId == task.taskId } }
                    .forEach { task -> SubagentRow(label = task.taskId, onCancel = { onCancelSubagent(task.taskId) }) }
                Spacer(Modifier.size(8.dp))
            }
            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_process_sheet_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(records) { index, record ->
                        ProcessRecordRow(record = record, index = index + 1)
                    }
                }
            }
        }
    }
}

/** Registro único de herramienta: número de serie + nombre + tiempo empleado + resumen de resultados. */
@Composable
private fun ProcessRecordRow(record: ToolCallRecord, index: Int) {
    val scheme = MaterialTheme.colorScheme
    val duration = record.finishedAt?.let { finish ->
        record.startedAt?.let { start -> finish - start }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                // El mismo registro: el ícono del tipo de acción se usa para el éxito y el triángulo de advertencia se usa para el fracaso (el estado supera al tipo).
                imageVector = if (record.isSuccess) processIconFor(record.toolName) else MuseIcons.alertTriangle,
                contentDescription = null,
                tint = if (record.isSuccess) scheme.primary else scheme.error,
                modifier = Modifier.size(MuseIconSizes.iconTiny),
            )
            Text(
                text = "$index. ${record.toolName}",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (duration != null && duration > 0) {
                Text(
                    text = "${duration}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    maxLines = 1,
                )
            }
        }
        val detail = record.result.trim().ifBlank { record.arguments.trim() }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, top = 1.dp),
            )
        }
    }
}

/** Traduzca los nombres de las herramientas a palabras humanas: delegar/ejecutar comando/herramienta común. */
@Composable
private fun describeProcess(record: ToolCallRecord): String {
    val name = record.toolName
    val target = record.arguments.trim()
        .takeIf { it.isNotBlank() }
        ?.let { extractDelegateTarget(it) }
    return when {
        isDelegateTool(name) && target != null -> stringResource(R.string.chat_process_delegating, target)
        isDelegateTool(name) -> stringResource(R.string.chat_process_delegating, name)
        isCommandTool(name) -> stringResource(R.string.chat_process_running_command)
        else -> stringResource(R.string.tool_running, name)
    }
}

/** Herramientas de delegación de subagentes: se cuentan todos aquellos que tienen estos fragmentos en sus nombres. */
private fun isDelegateTool(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("subagent") || n.contains("delegate") || n.contains("agent_discuss") || n.contains("ask_agent")
}

/** Herramientas de ejecución de comandos. */
private fun isCommandTool(name: String): Boolean {
    val n = name.lowercase()
    return n.contains("exec_command") || n.contains("run_command") || n.contains("termux") || n == "shell"
}

/**
 * Extraiga el nombre del asistente de los parámetros de delegación.
 *
 * Realice únicamente una extracción superficial: el parámetro puede ser JSON y el orden lexicográfico es inestable. Si no se puede obtener, se devolverá nulo para permitir que la persona que llama devuelva el nombre de la herramienta.
 */
private fun extractDelegateTarget(arguments: String): String? =
    listOf("assistant_name", "assistant", "agent", "name", "target").firstNotNullOfOrNull { key ->
        val marker = "\"$key\""
        val at = arguments.indexOf(marker)
        val colon = if (at < 0) -1 else arguments.indexOf(':', at + marker.length)
        val raw = if (colon < 0) "" else arguments.substring(colon + 1).trimStart()
        val end = if (raw.startsWith('"')) raw.indexOf('"', 1) else -1
        if (end <= 1) {
            null
        } else {
            raw.substring(1, end).trim().takeIf { it.isNotBlank() }
        }
    }

/** Una subtarea en segundo plano: quién se ejecuta + cancelada. */
@Composable
private fun SubagentRow(label: String, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MuseInkGlyph(modifier = Modifier.size(MuseIconSizes.iconTiny), animate = true)
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.subagent_task_cancel),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.error,
            modifier = Modifier.clickable(onClick = onCancel),
        )
    }
}
