package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.db.MpActorType
import io.zer0.muse.miniphone.data.db.MpMessageType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reglas de visibilidad para el menú de pulsación larga:¿Quién puede copiar y redistribuir?
 *
 * Las reglas son funciones puras.,Clavarlo aquí;Con una sola pulsación larga realmente puede aparecer, y estos son los elementos que aparecen.,Responsable de las pruebas de UI.
 */
class ChatMenuActionsTest {

    @Test
    fun ownTextOffersCopyDeleteAndResend() {
        assertEquals(
            listOf(ChatMenuAction.COPY, ChatMenuAction.DELETE, ChatMenuAction.RESEND),
            chatMenuActionsFor(message(MpActorType.USER, MpMessageType.TEXT)),
        )
    }

    @Test
    fun characterTextOffersNoResend() {
        assertEquals(
            listOf(ChatMenuAction.COPY, ChatMenuAction.DELETE),
            chatMenuActionsFor(message(MpActorType.CHARACTER, MpMessageType.TEXT)),
        )
    }

    @Test
    fun imageAndVoiceOfferNoCopy() {
        assertEquals(
            listOf(ChatMenuAction.DELETE),
            chatMenuActionsFor(message(MpActorType.USER, MpMessageType.IMAGE)),
        )
        assertEquals(
            listOf(ChatMenuAction.DELETE),
            chatMenuActionsFor(message(MpActorType.CHARACTER, MpMessageType.VOICE)),
        )
    }

    @Test
    fun systemNoticeHasNoMenuAtAll() {
        assertEquals(emptyList<ChatMenuAction>(), chatMenuActionsFor(message(MpActorType.SYSTEM, MpMessageType.SYSTEM)))
    }

    private fun message(sender: MpActorType, type: MpMessageType): ChatMessageUi = ChatMessageUi(
        id = "m1",
        sender = sender,
        type = type,
        content = "contenido",
        createdAt = 1_000L,
    )
}
