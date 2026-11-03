package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verificación previa de la cola fuera de la ventana: después de que el mensaje entrante se coloca en la cola y se cierra la ventana de respuesta pasiva, no debería ejecutar otra ronda del modelo.
 *
 * La evaluación de la ventana en la implementación original solo se realizó dentro de runAgent, por lo que el mensaje "en cola para la superventana" aún se transmitió durante toda la conversación.
 * Después de quemar la ficha, descubrí que no se podía enviar; retroceder y volver a intentar devolver el mensaje repetidamente y quemarlo nuevamente.
 */
class ChannelReplyWindowPrecheckTest {

    private val receivedAt = 1_800_000_000_000L

    /** Ventana oficial de 5 minutos, margen de envío de 15 segundos. */
    private val windowMillis = 5 * 60 * 1000L
    private val sendMarginMillis = 15_000L

    private fun qqInbound(receivedAtMillis: Long = receivedAt): ChannelInbox.Inbound = ChannelInbox.Inbound(
        platform = ChannelPlatform.QQ.name,
        from = "group:test-group",
        timestamp = receivedAtMillis,
    )

    @Test
    fun `QQ La fecha límite es igual al tiempo de entrada más la ventana menos el margen de envío`() {
        assertEquals(
            receivedAt + windowMillis - sendMarginMillis,
            ChannelAutoReply.replyWindowDeadlineMillis(ChannelPlatform.QQ.name, receivedAt),
        )
        // Otras plataformas no tienen una ventana de respuesta pasiva y nunca excederán la ventana.
        assertEquals(
            Long.MAX_VALUE,
            ChannelAutoReply.replyWindowDeadlineMillis(ChannelPlatform.FEISHU.name, receivedAt),
        )
    }

    @Test
    fun `QQ Si la cola entrante excede la ventana pasiva, se considerará que excede la ventana`() {
        assertTrue(queuedReplyWindowExpired(qqInbound(), receivedAt + 6 * 60 * 1000L))
    }

    @Test
    fun `QQ Pase entrante mientras aún está dentro de la ventana`() {
        assertFalse(queuedReplyWindowExpired(qqInbound(), receivedAt + 60 * 1000L))
    }

    @Test
    fun `QQ Si la inscripción se atasca en la fecha límite, se considerará que excedió la ventana`() {
        assertTrue(
            queuedReplyWindowExpired(qqInbound(), receivedAt + windowMillis - sendMarginMillis),
        )
    }

    @Test
    fun `Las plataformas que no son QQ no se ven afectadas por las ventanas pasivas`() {
        val inbound = ChannelInbox.Inbound(
            platform = ChannelPlatform.FEISHU.name,
            from = "chat-1",
            timestamp = receivedAt,
        )
        assertFalse(queuedReplyWindowExpired(inbound, receivedAt + 60 * 60 * 1000L))
    }

    @Test
    fun `El código de error final de interceptación indica que se agotó el tiempo de espera de la cola`() {
        assertEquals("La ventana de respuesta pasiva ha expirado(Tiempo de espera de cola)", REPLY_WINDOW_EXPIRED_BY_QUEUE)
    }

    /**
     * La posición de verificación previa está bloqueada por el orden del código fuente: la interceptación debe ser anterior a appendTurn (de lo contrario, se dejará un mensaje de usuario sin respuesta en la sesión).
     * También debe preceder a prepareReply(runAgent sólo se llama allí).
     */
    @Test
    fun `La interceptación previa a la verificación precede a la escritura de bibliotecas de sesiones y a los modelos de llamada`() {
        val source = java.io.File("src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt").readText()
        val start = source.indexOf("private suspend fun handleMessage(")
        assertTrue("no encontrado handleMessage", start >= 0)
        val end = source.indexOf("private suspend fun prepareReply(", start)
        val body = source.substring(start, if (end > start) end else source.length)

        val blockIdx = body.indexOf("return HandleResult.Blocked(REPLY_WINDOW_EXPIRED_BY_QUEUE)")
        val appendIdx = body.indexOf("ChannelConversationStore.appendTurn")
        val prepareIdx = body.indexOf("prepareReply(inbound, config)")

        assertTrue("handleMessage Debe haber una intercepción de ventana de cola.", blockIdx >= 0)
        assertTrue("la verificación previa debe preceder a appendTurn", blockIdx < appendIdx)
        assertTrue("La verificación previa debe realizarse prepareReply(Eso es runAgent) antes", blockIdx < prepareIdx)
        assertEquals("handleMessage Los modelos no deben llamarse directamente", -1, body.indexOf("runAgent("))
    }
}
