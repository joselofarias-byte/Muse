package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.ConversationTurnEntity
import io.zer0.muse.data.session.SessionRepository

/**
 * Capa de adaptación de la reconstrucción del diálogo.
 *
 * Actualmente solo es responsable de los eventos de sombra y el estado de la ronda, y el antiguo ChatViewModel sigue siendo el principal punto de entrada para el envío;
 * Después de abrir la escala de grises posterior, el envío y la proyección se pueden conectar gradualmente a este servicio.
 */
class ConversationService(
    private val sessionRepository: SessionRepository,
) {
    suspend fun startTurn(turn: ConversationTurnEntity) {
        sessionRepository.upsertConversationTurn(turn)
    }

    suspend fun record(event: ConversationEventDraft): Boolean = sessionRepository.recordConversationEvent(event)

    suspend fun finishTurn(turnId: String, phase: String = "COMPLETED") {
        sessionRepository.finishConversationTurn(turnId, phase)
    }
}
