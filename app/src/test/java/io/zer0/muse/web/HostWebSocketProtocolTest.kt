package io.zer0.muse.web

import io.zer0.common.AppJson
import io.zer0.muse.ui.SsrfGuard
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host Mode WebSocket Pruebas de regresión de serialización de protocolos de comando e instantáneas. */
class HostWebSocketProtocolTest {

    @Test
    fun `command round trip preserves chat intent`() {
        val command = HostCommand(
            type = "chat.send",
            requestId = "req-1",
            sessionId = "session-1",
            text = "hola,Muse",
        )

        val decoded = AppJson.decodeFromString<HostCommand>(AppJson.encodeToString(command))

        assertEquals(command, decoded)
    }

    @Test
    fun `chat send SSRF policy rejects private and metadata URLs`() {
        assertTrue(SsrfGuard.hasBlockedUrlInText("Por favor visita http://127.0.0.1:8080/admin"))
        assertTrue(SsrfGuard.hasBlockedUrlInText("http://169.254.169.254/latest/meta-data/"))
        assertTrue(SsrfGuard.hasBlockedUrlInText("http://[fd00::1]/secret"))
        assertTrue(SsrfGuard.hasBlockedUrlInText("http://2130706433/"))
    }

    @Test
    fun `chat send SSRF policy permits public URL`() {
        assertFalse(SsrfGuard.hasBlockedUrlInText("Por favor consulte https://8.8.8.8/docs"))
    }

    @Test
    fun `snapshot round trip preserves messages and host state`() {
        val event = HostEvent(
            type = "state.snapshot",
            sessionId = "session-1",
            isStreaming = true,
            sessions = listOf(
                HostSession("session-1", "sesión de prueba", "default", 123L, archived = false, pinned = true),
            ),
            messages = listOf(
                HostMessage("message-1", "assistant", "Hola", reasoning = "pensar", createdAt = 124L),
            ),
        )

        val decoded = AppJson.decodeFromString<HostEvent>(AppJson.encodeToString(event))

        assertEquals(event, decoded)
        assertTrue(decoded.messages.single().reasoning == "pensar")
        assertTrue(decoded.sessions.single().pinned)
    }
}
