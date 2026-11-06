package io.zer0.muse.ui

import android.content.res.Resources
import io.zer0.ai.core.UIMessage
import io.zer0.muse.R

/**
 * Resumen de trayectorias de herramientas a nivel de tarea agregadas por nombre de herramienta.
 *
 * [records] Se conservan todos los registros originales del grupo; La agregación solo cambia el modo de visualización del panel de historial y no elimina ni reescribe mensajes.
 */
internal data class ToolTraceSummary(
    val toolName: String,
    val records: List<ToolCallRecord>,
    val successCount: Int,
    val failureCount: Int,
    val runningCount: Int,
    val latestRecord: ToolCallRecord,
) {
    val totalCount: Int
        get() = records.size

    /** Los grupos de herramientas que han fallado o que se están ejecutando se expanden de forma predeterminada, lo que permite a los usuarios encontrar elementos que necesitan atención de manera oportuna. */
    val shouldExpandByDefault: Boolean
        get() = failureCount > 0 || runningCount > 0

    /** Un resumen silencioso del resultado más reciente para una vista previa directa del estado plegado.res para null(Vista previa/Escenario de función pura)Devuelve una cadena vacía. */
    val latestSummary: String
        get() = toolTraceRecordSummary(latestRecord)
}

/** Registros de herramientas y [ToolCallCard] Sea coherente: los resultados vacíos y los resultados fallidos significan que la ejecución está en curso. */
internal fun ToolCallRecord.isRunningToolTrace(): Boolean = !isSuccess && result.isBlank()

/** Hay un resultado pero ningún éxito indica fracaso; Los registros fallidos con resultados vacíos no se contarán repetidamente como fallidos. */
internal fun ToolCallRecord.isFailedToolTrace(): Boolean = !isSuccess && result.isNotBlank()

/**
 * Agregue el historial de herramientas por nombre de herramienta conservando el orden de primera aparición de la herramienta y todos los registros originales de cada grupo.
 *
 * El registro más reciente se selecciona mediante marca de tiempo; si la marca de tiempo es la misma, se conserva el registro más atrás en la lista de entrada.
 */
internal fun summarizeToolTrace(records: List<ToolCallRecord>): List<ToolTraceSummary> {
    if (records.isEmpty()) return emptyList()

    val grouped = linkedMapOf<String, MutableList<Pair<Int, ToolCallRecord>>>()
    records.forEachIndexed { index, record ->
        grouped.getOrPut(record.toolName) { mutableListOf() } += index to record
    }

    return grouped.map { (toolName, indexedRecords) ->
        val groupRecords = indexedRecords.map { it.second }
        val latestRecord = indexedRecords
            .maxWithOrNull(compareBy<Pair<Int, ToolCallRecord>> { it.second.timestamp }.thenBy { it.first })
            ?.second
            ?: groupRecords.last()
        ToolTraceSummary(
            toolName = toolName,
            records = groupRecords,
            successCount = groupRecords.count { it.isSuccess },
            failureCount = groupRecords.count { it.isFailedToolTrace() },
            runningCount = groupRecords.count { it.isRunningToolTrace() },
            latestRecord = latestRecord,
        )
    }
}

/**
 * Restore tool history from persisted assistant tool cards when the in-memory trace was cleared
 * by a new generation or a session reload. Runtime records win on duplicate executions.
 */
internal fun mergeToolCallRecords(records: List<ToolCallRecord>, messages: List<UIMessage>, sessionId: String): List<ToolCallRecord> {
    val merged = LinkedHashMap<String, ToolCallRecord>()
    fun key(record: ToolCallRecord): String = listOf(record.toolName, record.timestamp, record.result).joinToString("\u001f")

    records.forEach { merged[key(it)] = it }
    messages.forEach { message ->
        val info = message.toolCallInfo ?: return@forEach
        val restored =
            ToolCallRecord(
                toolName = info.toolName,
                arguments = info.arguments,
                result = info.result,
                isSuccess = info.isSuccess,
                timestamp = message.createdAt,
                sessionId = sessionId,
                toolCallId = message.id.toString(),
                status = if (info.isSuccess) "SUCCESS" else "FAILED",
                startedAt = message.createdAt,
                finishedAt = message.createdAt,
            )
        merged.putIfAbsent(key(restored), restored)
    }
    return merged.values.sortedBy { it.timestamp }
}

private const val TOOL_TRACE_PREVIEW_LIMIT = 120

/** Copia abstracta plegada:El estado es lo primero en recursos.(res),null(Vista previa/función pura)Devuelve una cadena vacía. */
internal fun toolTraceRecordSummary(record: ToolCallRecord, res: Resources? = null): String {
    val normalized = record.result.replace(Regex("\\s+"), " ").trim()
    return when {
        record.isRunningToolTrace() -> res?.getString(R.string.tool_trace_running) ?: ""
        normalized.isBlank() -> res?.getString(R.string.tool_trace_empty) ?: ""
        else -> {
            if (normalized.length > TOOL_TRACE_PREVIEW_LIMIT) {
                normalized.take(TOOL_TRACE_PREVIEW_LIMIT) + "…"
            } else {
                normalized
            }
        }
    }
}
