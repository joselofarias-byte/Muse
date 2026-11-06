package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F9: La respuesta automática debe enviarse correctamente antes de quedar registrada en el historial de conversaciones.
 *
 * original primero appendTurn(assistant, reply) Enviar de nuevo:Envío fallido→Retry→Regenerar la siguiente ronda
 * nuevo texto reply',El segundo appendTurn se omite debido a la deduplicación de despachoId. → El inventario de sesiones es la primera vez.
 * texto,Y el usuario realmente recibe el segundo,Deriva del contexto.
 */
class ChannelReplyPersistAfterSendTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt").readText()

    @Test
    fun `assistant turn is appended only after successful send`() {
        val src = source()
        val fnStart = src.indexOf("private suspend fun sendPreparedReply(")
        assertTrue("no encontrado sendPreparedReply", fnStart >= 0)
        val fnEnd = src.indexOf("private sealed interface HandleResult", fnStart)
        val body = src.substring(fnStart, if (fnEnd > fnStart) fnEnd else src.length)

        val appendIdx = body.indexOf("ChannelConversationStore.appendTurn")
        val sendIdx = body.indexOf("channelManager.sendTextWithOptions")
        assertTrue("appendTurn debe existir", appendIdx >= 0)
        assertTrue("sendTextWithOptions debe existir", sendIdx >= 0)
        assertTrue(
            "appendTurn Debe enviarse después de enviar una rama exitosa(No se puede escribir el historial primero y luego enviarlo.)",
            appendIdx > sendIdx,
        )
        assertTrue(
            "appendTurn Debe estar ubicado dentro de la rama de éxito de result.ok",
            body.indexOf("if (!result.ok)") < appendIdx,
        )
    }
}
