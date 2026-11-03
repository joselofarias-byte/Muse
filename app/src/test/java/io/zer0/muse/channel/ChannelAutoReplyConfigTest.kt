package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelAutoReplyConfigTest {

    @Test
    fun `webhook reply uses the channel whose credentials authenticated the event`() {
        val first = ChannelConfig("feishu-a", ChannelPlatform.FEISHU, autoReply = true)
        val second = ChannelConfig("feishu-b", ChannelPlatform.FEISHU, autoReply = true)
        val inbound = ChannelInbox.Inbound(
            platform = ChannelPlatform.FEISHU.name,
            sourceChannelId = second.id,
        )

        assertEquals(second, selectAutoReplyConfig(listOf(first, second), inbound))
    }

    @Test
    fun `missing authenticated channel id does not fall through to another channel`() {
        val channel = ChannelConfig("qq-a", ChannelPlatform.QQ, autoReply = true)
        val inbound = ChannelInbox.Inbound(
            platform = ChannelPlatform.QQ.name,
            sourceChannelId = "qq-removed",
        )

        assertNull(selectAutoReplyConfig(listOf(channel), inbound))
    }

    @Test
    fun `legacy inbound without a source channel does not pick an arbitrary account`() {
        val channel = ChannelConfig("qq-a", ChannelPlatform.QQ, autoReply = true)
        val inbound = ChannelInbox.Inbound(platform = ChannelPlatform.QQ.name)

        assertNull(selectAutoReplyConfig(listOf(channel), inbound))
    }

    @Test
    fun `qq passive reply binding expires with the platform reply window`() {
        val now = 1_800_000_000_000L

        assertEquals("message-1", qqReplyEventIdOverride("QQ", "group:test", "message-1", now - 1_000L, now))
        // La caducidad debe devolver un valor nulo en lugar de una cadena vacía: una cadena vacía llegará a la rama de eventos de AnswerContext,
        // Se establece en nulo en isNotBlank, omitiendo por completo el msg_id disponible en el caché.
        assertNull(
            "group window must expire to null, not empty string",
            qqReplyEventIdOverride("QQ", "group:test", "expired", now - QqMsgIdCache.GROUP_WINDOW_MS - 1L, now),
        )
        assertNull(qqReplyEventIdOverride("QQ", "group:test", "", now, now))
        assertNull(qqReplyEventIdOverride("FEISHU", "event-1", "x", now, now))
    }

    @Test
    fun `c2c keeps the one hour window while group stops at five minutes`() {
        val now = 1_800_000_000_000L
        // Seis minutos: los chats individuales todavía están dentro de la ventana (oficialmente 60 minutos), los chats grupales han expirado (oficialmente 5 minutos).
        val sixMinutes = 6 * 60 * 1000L
        assertEquals("m", qqReplyEventIdOverride("QQ", "user-openid", "m", now - sixMinutes, now))
        assertNull(qqReplyEventIdOverride("QQ", "group:g", "m", now - sixMinutes, now))
    }
}
