package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType

/** El separador entre el nombre del hablante y el texto en el historial del chat grupal. */
internal const val SPEAKER_SEPARATOR: String = ":"

/**
 * Historial de sesiones entregado al anfitrión:Recupere solo mensajes de texto que se hayan colocado en la biblioteca,En orden cronológico.
 *
 * Avisos e imágenes del sistema./La línea de voz no participa. —— Lo primero no es algo que dijo el personaje.,Este último no tiene texto para enviar.
 *
 * Los mensajes de rol en los chats grupales siempre deben escribirse como "Nombre del portavoz: Texto". Sólo hay dos niveles de rondas de anfitriones: "Usuario/No usuario",
 * Varias personas hablando en el mismo grupo tendrán la misma identidad de asistente:Sin prefijo,La última persona que habló no supo decir nada.
 * ¿Quién dijo la frase anterior?,Es fácil repetir la frase anterior o responder a la persona equivocada. Completa el prefijo,Quien responda puede seguir el contexto.
 * Los mensajes de usuario permanecen como están.(Lo mismo ocurre con los mensajes privados.),El host no necesita cambiar ninguna interfaz.
 */
internal fun chatHistoryOf(state: ChatUiState): List<MpChatTurn> = state.messages
    .filter { it.type == MpMessageType.TEXT && it.content.isNotBlank() }
    .map { message ->
        val speaker = if (state.isGroup && message.sender == MpActorType.CHARACTER) {
            state.memberNames[message.characterId].orEmpty()
        } else {
            ""
        }
        MpChatTurn(
            sender = message.sender,
            content = if (speaker.isEmpty()) message.content else "$speaker$SPEAKER_SEPARATOR${message.content}",
        )
    }
