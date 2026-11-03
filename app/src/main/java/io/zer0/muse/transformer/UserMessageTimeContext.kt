package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Provides stable message-time anchors so tool latency is not mistaken for user-message elapsed time. */
internal object UserMessageTimeContext {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT)

    fun build(messages: List<UIMessage>, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val userMessages = messages.filter { it.role == MessageRole.USER && it.createdAt > 0L }
        val current = userMessages.lastOrNull() ?: return ""
        val currentTimestamp = formatTimestamp(current.createdAt, zoneId)
        val previous = userMessages.getOrNull(userMessages.lastIndex - 1)
        return if (previous == null) {
            "Ancla horaria del mensaje del usuario (zona horaria del dispositivo) ${zoneId.id}): El mensaje del usuario actual se envía a $currentTimestamp;" +
                "No hay ningún mensaje de usuario anterior con el que comparar, por lo tanto, no extrapole los intervalos de mensajes según el pensamiento del asistente, la ejecución de la herramienta o el tiempo de llamada a la API."
        } else {
            val previousTimestamp = formatTimestamp(previous.createdAt, zoneId)
            val elapsedMs = if (current.createdAt >= previous.createdAt) {
                current.createdAt - previous.createdAt
            } else {
                previous.createdAt - current.createdAt
            }
            val elapsed = formatElapsed(elapsedMs)
            "Ancla horaria del mensaje del usuario (zona horaria del dispositivo) ${zoneId.id}): El último mensaje de usuario se envió el $previousTimestamp;" +
                "El mensaje del usuario actual se envía a $currentTimestamp;El intervalo entre dos mensajes de usuario:$elapsed." +
                "Este intervalo se debe utilizar al responder la pregunta sobre el intervalo del mensaje y no se puede sustituir por el pensamiento del asistente, la ejecución de la herramienta o el tiempo transcurrido de la solicitud API."
        }
    }

    private fun formatTimestamp(timestamp: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(timestamp).atZone(zoneId).format(timestampFormatter)

    private fun formatElapsed(elapsedMs: Long): String {
        if (elapsedMs < 1_000L) return "$elapsedMs milisegundos"
        val totalSeconds = elapsedMs / 1_000L
        val days = totalSeconds / 86_400L
        val hours = totalSeconds % 86_400L / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return buildList {
            if (days > 0) add("$days dia")
            if (hours > 0) add("$hours horas")
            if (minutes > 0) add("$minutes puntos")
            if (seconds > 0) add("$seconds segundos")
        }.joinToString(" ").ifBlank { "0 segundos" }
    }
}
