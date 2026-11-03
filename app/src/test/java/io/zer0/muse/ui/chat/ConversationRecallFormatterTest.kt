package io.zer0.muse.ui.chat

import io.zer0.muse.data.session.SearchResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecallFormatterTest {

    @Test
    fun `builds a bounded historical reference section from current-session matches`() {
        val results = listOf(
            SearchResult(
                messageId = "m1",
                sessionId = "s1",
                sessionTitle = "sesión actual",
                contentSnippet = "El usuario mencionó antes que quería conservar el texto original.",
                role = "USER",
                createdAt = 1L,
                content = "Los usuarios han mencionado antes que quieren conservar el texto original, especialmente después de comprimirlo, y aún quieren poder rastrear los detalles.",
            ),
            SearchResult(
                messageId = "m2",
                sessionId = "s1",
                sessionTitle = "sesión actual",
                contentSnippet = "La confirmación del asistente agregará seguimiento",
                role = "ASSISTANT",
                createdAt = 2L,
                content = "Mantendré esta historia como referencia para recuperarla cuando sea necesario, en lugar de meterla permanentemente en cada ronda de contexto.",
            ),
        )

        val section = ConversationRecallFormatter.build("Mantener el texto original.", results, maxTokens = 200)

        assertTrue(section.contains("Rastreo del texto de la conversación"))
        assertTrue(section.contains("Usuario"))
        assertTrue(section.contains("asistente"))
        assertTrue(section.contains("Especialmente después de la compresión, todavía queremos poder rastrear los detalles."))
        assertTrue(section.contains("Sólo como referencia histórica"))
    }

    @Test
    fun `returns blank for empty query or no usable user assistant results`() {
        val toolResult = SearchResult(
            messageId = "tool",
            sessionId = "s1",
            sessionTitle = "sesión actual",
            contentSnippet = "internal tool output",
            role = "TOOL",
            createdAt = 1L,
            content = "internal tool output",
        )

        assertTrue(ConversationRecallFormatter.build("", listOf(toolResult), maxTokens = 200).isBlank())
        assertTrue(ConversationRecallFormatter.build("x", listOf(toolResult), maxTokens = 200).isBlank())
    }

    @Test
    fun `does not expose system or tool roles even when search results contain them`() {
        val results = listOf(
            SearchResult("s", "s1", "sesión", "system", "SYSTEM", 1L, "system secret"),
            SearchResult("t", "s1", "sesión", "tool", "TOOL", 2L, "tool secret"),
            SearchResult("u", "s1", "sesión", "user", "USER", 3L, "Contenido público del usuario"),
        )

        val section = ConversationRecallFormatter.build("contenido", results, maxTokens = 200)

        assertTrue(section.contains("Contenido público del usuario"))
        assertFalse(section.contains("system secret"))
        assertFalse(section.contains("tool secret"))
    }
}
