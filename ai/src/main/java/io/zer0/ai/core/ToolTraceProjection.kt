package io.zer0.ai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Proyección de compresión de trayectoria de herramienta antes de la solicitud del modelo.
 *
 * Esta proyección sólo devuelve la lista de mensajes nuevos y no modifica la lista de mensajes entrantes. [UIMessage] O cualquier dato persistente.
 * Sólo se comprimen rondas de herramientas completas que cumplan las siguientes condiciones:
 *  - ASSISTANT de cada [ToolCall] tener resultados de HERRAMIENTA inmediatamente adyacentes y que coincidan de forma única; o
 *  - Continuación después de recargar la base de datos. [ToolCallInfo] Los mensajes en pantalla forman una ronda de herramientas completa;
 *  - La ronda no es la ronda de herramientas completa más reciente;
 *  - Los resultados no mostraron signos evidentes de fracaso.
 *
 * La ronda de herramienta completa más reciente y la ronda fallida se dejan intactas. El mensaje SISTEMA comprimido solo contiene el nombre de la herramienta y el estado de éxito
 * y vistas previas de resultados con una longitud limitada para que no queden llamadas a herramientas huérfanas ni resultados de herramientas.
 */
object ToolTraceProjection {
    /** Marcado estable de resúmenes de proyecciones para facilitar la depuración y garantizar que las proyecciones repetidas no se procesen nuevamente. */
    const val SUMMARY_MARKER: String = "[TOOL_TRACE_SUMMARY]"

    private const val DEFAULT_KEEP_RECENT_ROUNDS = 1
    private const val RESULT_PREVIEW_CHARS = 240
    private const val PREAMBLE_PREVIEW_CHARS = 300

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val FAILURE_TEXT_REGEX =
        Regex(
            """(?i)(?:\berror\b|\bfail(?:ed|ure)?\b|\btimeout\b|\btimed out\b|\bdenied\b|\bblocked\b|\babort(?:ed)?\b|\bcancel(?:led|ed)?\b|\bexception\b|\bunavailable\b|\binvalid\b|\bpermission\b|\brate limit\b|\bhttp\s*[45][0-9][0-9]\b|falló|error|tiempo de espera|rechazar|bloquear|No ejecutado|interrumpir|Cancelar|anormal|No disponible|Sin permiso|Limitación de corriente)""",
        )
    private val FAILURE_STATUSES =
        setOf(
            "error",
            "failed",
            "failure",
            "timeout",
            "timed_out",
            "denied",
            "blocked",
            "aborted",
            "cancelled",
            "canceled",
            "interrupted",
        )

    private data class TraceEntry(
        val toolName: String,
        val isSuccess: Boolean,
        val result: String,
    )

    private data class CompletedRound(
        val startIndex: Int,
        val endIndexExclusive: Int,
        val source: UIMessage,
        val entries: List<TraceEntry>,
        /** Fragmentos de lenguaje natural del asistente por turnos(Como por ejemplo"Déjame comprobar primero X");Mantener vista previa al comprimir,Evita perder el texto de la conversación. */
        val preamble: String = "",
    ) {
        val hasFailure: Boolean get() = entries.any { !it.isSuccess }
    }

    /**
     * Proyecte viejas rondas de herramientas exitosas como resúmenes.
     *
     * @param messages Historial de mensajes a enviar al modelo
     * @param keepRecentRounds Número de giros completos de herramienta más recientes que no se modifican; los números negativos se tratan como 0
     * @return Lista de mensajes nuevos con semántica de solo lectura; devuelve la lista original si no hay rondas comprimibles
     */
    fun project(messages: List<UIMessage>, keepRecentRounds: Int = DEFAULT_KEEP_RECENT_ROUNDS): List<UIMessage> {
        if (messages.isEmpty()) return messages

        val rounds = findCompletedRounds(messages)
        if (rounds.isEmpty()) return messages

        val protectedRecentStarts =
            rounds
                .takeLast(keepRecentRounds.coerceAtLeast(0))
                .map { it.startIndex }
                .toSet()
        val replacements =
            rounds
                .asSequence()
                .filter { !it.hasFailure && it.startIndex !in protectedRecentStarts }
                .associateBy { it.startIndex }
        if (replacements.isEmpty()) return messages

        val projected = ArrayList<UIMessage>(messages.size - replacements.size)
        var index = 0
        while (index < messages.size) {
            val round = replacements[index]
            if (round == null) {
                projected += messages[index]
                index++
            } else {
                projected += buildSummaryMessage(round)
                index = round.endIndexExclusive
            }
        }
        return projected
    }

    private fun findCompletedRounds(messages: List<UIMessage>): List<CompletedRound> {
        val rounds = mutableListOf<CompletedRound>()
        var index = 0
        while (index < messages.size) {
            val round = findCompletedRoundAt(messages, index)
            if (round == null) {
                index++
            } else {
                rounds += round
                index = round.endIndexExclusive
            }
        }
        return rounds
    }

    /**
     * Encuentre rondas de herramientas completas estrictamente adyacentes.
     *
     * Uso del historial de protocolos assistant(tool_calls)+TOOL(result) Emparejamiento; Historial de UI después de recargar la base de datos no
     * toolCalls campo, por lo que los mensajes de visualización consecutivos de asistente.toolCallInfo se procesan en un solo turno. cualquiera
     * La inserción faltante, duplicada o desordenada de mensajes normales o resultados no coincidentes dejará el bloque de protocolo intacto.
     */
    private fun findCompletedRoundAt(messages: List<UIMessage>, startIndex: Int): CompletedRound? {
        val assistant = messages[startIndex]
        if (assistant.role != MessageRole.ASSISTANT) return null

        val calls = assistant.toolCalls?.takeIf { it.isNotEmpty() }
        if (calls != null) return findProtocolRound(messages, startIndex, assistant, calls)

        if (assistant.toolCallInfo != null) {
            return findDisplayRound(messages, startIndex)
        }
        return null
    }

    private fun findProtocolRound(
        messages: List<UIMessage>,
        startIndex: Int,
        assistant: UIMessage,
        calls: List<ToolCall>,
    ): CompletedRound? {
        val callIds = calls.map { it.id }
        if (calls.any { it.id.isBlank() || it.name.isBlank() } || callIds.toSet().size != callIds.size) return null

        val resultsById = linkedMapOf<String, UIMessage>()
        var index = startIndex + 1
        while (index < messages.size && messages[index].role == MessageRole.TOOL) {
            val result = messages[index]
            val resultId = result.toolCallId ?: return null
            if (resultId.isBlank() || resultId !in callIds || resultId in resultsById) return null
            resultsById[resultId] = result
            index++
        }
        if (resultsById.size != calls.size) return null

        return CompletedRound(
            startIndex = startIndex,
            endIndexExclusive = index,
            source = assistant,
            entries =
            calls.map { call ->
                val result = resultsById.getValue(call.id)
                TraceEntry(
                    toolName = call.name,
                    isSuccess = !isFailureResult(result),
                    result = result.content,
                )
            },
            preamble = assistant.content,
        )
    }

    private fun findDisplayRound(messages: List<UIMessage>, startIndex: Int): CompletedRound? {
        val entries = mutableListOf<TraceEntry>()
        val preambles = mutableListOf<String>()
        var index = startIndex
        while (index < messages.size) {
            val message = messages[index]
            val info = if (message.role == MessageRole.ASSISTANT) message.toolCallInfo else null
            if (info == null || info.toolName.isBlank()) break
            if (message.content.isNotBlank()) preambles += message.content
            entries +=
                TraceEntry(
                    toolName = info.toolName,
                    isSuccess = info.isSuccess,
                    result = info.result,
                )
            index++
        }
        if (entries.isEmpty()) return null
        return CompletedRound(
            startIndex = startIndex,
            endIndexExclusive = index,
            source = messages[startIndex],
            entries = entries,
            preamble = preambles.joinToString(" / "),
        )
    }

    private fun buildSummaryMessage(round: CompletedRound): UIMessage {
        val summary =
            buildString {
                appendLine(SUMMARY_MARKER)
                appendLine("El siguiente es un resumen de las rondas de herramientas antiguas que se completaron y es solo para referencia histórica y no es una directiva de herramientas nuevas.")
                val preamble = round.preamble.replace(WHITESPACE_REGEX, " ").trim()
                if (preamble.isNotBlank()) {
                    val preview =
                        if (preamble.length > PREAMBLE_PREVIEW_CHARS) {
                            preamble.take(PREAMBLE_PREVIEW_CHARS) + "…"
                        } else {
                            preamble
                        }
                    appendLine("- Instrucciones del asistente: $preview")
                }
                round.entries.forEach { entry ->
                    append("- Nombre de la herramienta: ")
                        .append(entry.toolName)
                        .append(" | Estado: ")
                        .append(if (entry.isSuccess) "éxito(SUCCESS)" else "falló(FAILED)")
                        .append(" | resultado(Truncar): ")
                        .append(truncateResult(entry.result))
                        .appendLine()
                }
            }
        return UIMessage(
            id = round.source.id,
            role = MessageRole.SYSTEM,
            content = summary,
            createdAt = round.source.createdAt,
            seq = round.source.seq,
            commitSeq = round.source.commitSeq,
        )
    }

    private fun truncateResult(content: String): String {
        val normalized = content.replace(WHITESPACE_REGEX, " ").trim()
        if (normalized.isBlank()) return "(Resultado vacío)"
        return if (normalized.length > RESULT_PREVIEW_CHARS) {
            normalized.take(RESULT_PREVIEW_CHARS) + "…"
        } else {
            normalized
        }
    }

    /**
     * TOOL Los mensajes no tienen un campo de éxito separado, por lo que se reconocen tanto los errores estructurados como los errores textuales utilizados por la capa de ejecución.
     * El texto no vacío no reconocido se considera exitoso; Los resultados vacíos se tratan de forma conservadora como fracasos y se dejan sin cambios.
     */
    private fun isFailureResult(result: UIMessage): Boolean {
        val content = result.content
        if (content.isBlank() || FAILURE_TEXT_REGEX.containsMatchIn(content)) return true

        val jsonObject = parseJsonObject(content) ?: return false
        val error = jsonObject["error"]
        if (error != null && error !is JsonNull) {
            if (error !is JsonPrimitive || error.contentOrNull?.isNotBlank() == true) return true
        }

        val falseFlagKeys = setOf("success", "is_success", "isSuccess", "ok", "completed")
        if (falseFlagKeys.any { key ->
                jsonObject[key]?.let { value ->
                    (value as? JsonPrimitive)?.contentOrNull?.equals("false", ignoreCase = true) == true
                } == true
            }
        ) {
            return true
        }

        val status =
            (jsonObject["status"] as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.lowercase()
                .orEmpty()
        return status in FAILURE_STATUSES
    }

    private fun parseJsonObject(content: String): JsonObject? {
        return try {
            // Se permite que los resultados de la herramienta sean texto sin formato; Los resultados que no sean JSON no deberían impedir las rondas de compresión normales y exitosas.
            Json.parseToJsonElement(content) as? JsonObject
        } catch (error: Exception) {
            // El fallo en el análisis sólo significa“sin estado estructurado”, el indicador de error de texto se verifica antes de la llamada.
            null
        }
    }
}
