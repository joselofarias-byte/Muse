package io.zer0.muse.channel

import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap

/**
 * Ensamblador acotado de conexión larga Feishu para marcos fragmentados.
 *
 * Cuando hay demasiados mensajes sin terminar al mismo tiempo, solo se eliminará el más antiguo y los demás mensajes que aún se puedan ensamblar no se podrán borrar.
 * WebSocket Las devoluciones de llamada se pueden ingresar simultáneamente, por lo que las transiciones de estado se completan dentro del mismo bloqueo.
 */
internal class FeishuFrameAssembler(
    private val maxPendingMessages: Int = DEFAULT_MAX_PENDING_MESSAGES,
) {
    private data class PendingMessage(
        val expectedParts: Int,
        val parts: MutableMap<Int, ByteArray>,
    )

    private val pending = LinkedHashMap<String, PendingMessage>()

    init {
        require(maxPendingMessages > 0) { "maxPendingMessages must be positive" }
    }

    @Synchronized
    fun add(messageId: String, sum: Int, seq: Int, payload: ByteArray): String? {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(sum > 0) { "sum must be positive" }
        require(seq in 0 until sum) { "seq must be within the frame range" }

        val pendingMessage = pending[messageId] ?: run {
            while (pending.size >= maxPendingMessages) {
                val oldestId = pending.entries.firstOrNull()?.key ?: break
                pending.remove(oldestId)
            }
            PendingMessage(sum, LinkedHashMap<Int, ByteArray>()).also {
                pending[messageId] = it
            }
        }
        if (pendingMessage.expectedParts != sum) {
            pending.remove(messageId)
            return null
        }
        pendingMessage.parts[seq] = payload.copyOf()
        if (pendingMessage.parts.size < sum) return null

        val merged = ByteArrayOutputStream()
        for (index in 0 until sum) {
            val part = pendingMessage.parts[index] ?: return null
            merged.write(part)
        }
        pending.remove(messageId)
        return String(merged.toByteArray(), Charsets.UTF_8)
    }

    private companion object {
        const val DEFAULT_MAX_PENDING_MESSAGES = 64
    }
}
