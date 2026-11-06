package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UserMessageTimeContextTest {
    @Test
    fun `prompt uses the two user message timestamps instead of tool round duration`() {
        val messages = listOf(
            userMessage("previous", "2026-10-01T09:00:00Z"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "I will check.",
                createdAt = Instant.parse("2026-10-01T09:01:00Z").toEpochMilli(),
            ),
            UIMessage(
                role = MessageRole.TOOL,
                content = "current time: 09:01",
                createdAt = Instant.parse("2026-10-01T09:01:03Z").toEpochMilli(),
            ),
            userMessage("current", "2026-10-01T09:01:05Z"),
        )

        val context = UserMessageTimeContext.build(messages, ZoneId.of("UTC"))

        assertTrue(context.contains("2026-10-01 09:00:00 UTC"))
        assertTrue(context.contains("2026-10-01 09:01:05 UTC"))
        assertTrue(context.contains("1 puntos 5 segundos"))
        assertTrue(context.contains("no se puede sustituir por el pensamiento del asistente, la ejecución de la herramienta o el tiempo transcurrido de la solicitud API."))
    }

    @Test
    fun `one user message does not invent a previous message interval`() {
        val context = UserMessageTimeContext.build(
            listOf(userMessage("only", "2026-10-01T09:00:00Z")),
            ZoneId.of("UTC"),
        )

        assertTrue(context.contains("No hay ningún mensaje de usuario anterior con el que comparar"))
        assertFalse(context.contains("intervalo de mensaje:"))
    }

    private fun userMessage(id: String, timestamp: String) = UIMessage(
        role = MessageRole.USER,
        content = id,
        createdAt = Instant.parse(timestamp).toEpochMilli(),
    )
}
