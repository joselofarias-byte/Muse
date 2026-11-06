package io.zer0.muse.data.chat.rewrite

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.session.MessagePartEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.session.ToolRoundEntity

/** Entrada enviada en una ronda.parts/toolRounds Se puede dejar vacío, los campos antiguos permanecerán intactos. */
data class MessageCommitRequest(
    val turnId: String,
    val sessionId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val message: UIMessage,
    val parts: List<MessagePartEntity> = emptyList(),
    val toolRounds: List<ToolRoundEntity> = emptyList(),
)

sealed interface MessageCommitResult {
    data object Committed : MessageCommitResult
    data object AlreadyCommitted : MessageCommitResult
    data object Rejected : MessageCommitResult
}

/**
 * Nuevo adaptador de envío de enlaces.
 *
 * El verdadero límite idempotente es ConversationTurnDao.finishIfOpen de Room; aún se puede seguir llamando al enlace antiguo
 * SessionRepository.upsertMessage, cambie solo si useNewConversationService está activado explícitamente.
 */
class MessageCommit(
    private val sessionRepository: SessionRepository,
) {
    suspend fun commit(request: MessageCommitRequest): MessageCommitResult = sessionRepository.commitConversationMessage(request)
}

/** Genere las partes base de una confirmación a partir de campos UIMessage compatibles; Los mensajes antiguos no están obligados a reponerse. */
fun buildCommitParts(message: UIMessage, createdAt: Long): List<MessagePartEntity> = buildList {
    val reasoning = message.reasoning
    if (!reasoning.isNullOrEmpty()) {
        add(
            MessagePartEntity(
                messageId = message.id.toString(),
                partIndex = size,
                kind = "reasoning",
                text = reasoning,
                createdAt = createdAt,
            ),
        )
    }
    if (message.content.isNotEmpty()) {
        add(
            MessagePartEntity(
                messageId = message.id.toString(),
                partIndex = size,
                kind = "text",
                text = message.content,
                createdAt = createdAt,
            ),
        )
    }
    message.toolCallInfo?.let { info ->
        add(
            MessagePartEntity(
                messageId = message.id.toString(),
                partIndex = size,
                kind = "tool",
                text = "",
                metadataJson = io.zer0.common.AppJson.encodeToString(
                    io.zer0.ai.core.ToolCallInfo.serializer(),
                    info,
                ),
                createdAt = createdAt,
            ),
        )
    }
}
