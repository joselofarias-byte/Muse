package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessagePartEntity

/** UI Muestra el uso de piezas estructuradas. */
data class MessageDisplayPart(
    val kind: String,
    val text: String,
    val metadataJson: String = "{}",
)

data class ProjectedMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val parts: List<MessageDisplayPart>,
    val content: String,
    val reasoning: String?,
    val seq: Long,
    val commitSeq: Long,
    val parentMessageId: String?,
    val variantGroupId: String?,
    val isLegacyProjection: Boolean,
)

/**
 * Proyector de mensajes reproducible.
 * Los mensajes nuevos leen primero message_parts; Los mensajes antiguos sin partes se derivan del campo heredado.
 */
object MessageProjector {
    fun project(message: MessageEntity, storedParts: List<MessagePartEntity>): ProjectedMessage {
        val parts = if (storedParts.isNotEmpty()) {
            storedParts.sortedBy { it.partIndex }.map { MessageDisplayPart(it.kind, it.text, it.metadataJson) }
        } else {
            buildLegacyParts(message)
        }
        // Algunas versiones antiguas escribirán primero la parte de razonamiento/herramienta y luego complementarán de forma asincrónica la parte de texto; en este momento
        // legacy content Sigue siendo el único cuerpo completo, y no se puede proyectar que la parte estructurada esté vacía simplemente porque no lo está.
        val visibleContent = parts.filter { it.kind == "text" }.joinToString("") { it.text }
            .ifBlank { message.content }
        val reasoning = parts.filter { it.kind == "reasoning" }.joinToString("") { it.text }.ifBlank { message.reasoning }
        return ProjectedMessage(
            id = message.id,
            sessionId = message.sessionId,
            role = message.role,
            parts = parts,
            content = visibleContent,
            reasoning = reasoning,
            seq = message.seq,
            commitSeq = message.commitSeq,
            parentMessageId = message.parentMessageId,
            variantGroupId = message.variantGroupId,
            isLegacyProjection = storedParts.isEmpty(),
        )
    }

    fun order(messages: List<MessageEntity>, useCommitSeq: Boolean): List<MessageEntity> = messages
        .asSequence()
        .filter { it.deletedAt == null }
        .sortedWith(
            compareBy<MessageEntity> { if (useCommitSeq && it.commitSeq > 0) it.commitSeq else it.seq }
                .thenBy { it.createdAt }
                .thenBy { it.id },
        )
        .toList()

    private fun buildLegacyParts(message: MessageEntity): List<MessageDisplayPart> = buildList {
        if (!message.reasoning.isNullOrEmpty()) add(MessageDisplayPart("reasoning", message.reasoning))
        if (message.content.isNotEmpty()) add(MessageDisplayPart("text", message.content))
        if (!message.toolCallInfoJson.isNullOrEmpty()) {
            add(MessageDisplayPart("tool", "", message.toolCallInfoJson))
        }
    }
}

data class ConversationProjection(
    val sessionId: String,
    val projectionVersion: Long,
    val messages: List<ProjectedMessage>,
    val contentHash: String,
)

/** Proyección de funciones puras a nivel de sesión para la conciliación entre la IU antigua y la nueva. */
object ConversationProjector {
    fun project(
        messages: List<MessageEntity>,
        parts: List<MessagePartEntity>,
        useCommitSeq: Boolean,
        projectionVersion: Long = 0,
    ): ConversationProjection {
        val ordered = MessageProjector.order(messages, useCommitSeq)
        val partsByMessage = parts.groupBy { it.messageId }
        val projected = ordered.map { message ->
            MessageProjector.project(message, partsByMessage[message.id].orEmpty())
        }
        val hashInput = projected.joinToString("|") { "${it.id}:${it.role}:${it.content}" }
        return ConversationProjection(
            sessionId = messages.firstOrNull()?.sessionId.orEmpty(),
            projectionVersion = projectionVersion,
            messages = projected,
            contentHash = sha256(hashInput),
        )
    }
}
