package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure Kotlin: pruebas deterministas de extractos históricos fuera de la ventana de contexto. */
class ContextHistoryDigestTest {
    @Test
    fun `preserves user and assistant natural language`() {
        val dropped =
            listOf(
                UIMessage(role = MessageRole.USER, content = "Ayúdame a probar los canales de notificación"),
                UIMessage(role = MessageRole.ASSISTANT, content = "Bien, déjame comprobar la configuración primero."),
            )

        val digest = ContextHistoryDigest.build(dropped)

        assertTrue(digest != null)
        assertEquals(MessageRole.SYSTEM, digest!!.role)
        assertTrue(digest.content.contains(ContextHistoryDigest.DIGEST_MARKER))
        assertTrue(digest.content.contains("Ayúdame a probar los canales de notificación"))
        assertTrue(digest.content.contains("Bien, déjame comprobar la configuración primero."))
    }

    @Test
    fun `compacts tool rounds into deterministic entries`() {
        val call = ToolCall("c1", "search_memory", "{}")
        val dropped =
            listOf(
                UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(call)),
                UIMessage(role = MessageRole.TOOL, content = "Encontré 3 recuerdos", toolCallId = "c1"),
            )

        val digest = ContextHistoryDigest.build(dropped)

        assertTrue(digest != null)
        assertTrue(digest!!.content.contains("search_memory"))
        assertTrue(digest.content.contains("Resultados de la herramienta: Encontré 3 recuerdos"))
    }

    @Test
    fun `respects budget and reports omitted count`() {
        val dropped =
            (1..40).map { index ->
                UIMessage(role = MessageRole.USER, content = "noticias$index " + "x".repeat(200))
            }

        val digest = ContextHistoryDigest.build(dropped, maxChars = 1000)

        assertTrue(digest != null)
        // Las últimas reservas,Doblado anteriormente para omitir descripción.
        assertTrue(digest!!.content.contains("noticias40"))
        assertFalse(digest.content.contains("noticias1 "))
        assertTrue(digest.content.contains("No ampliado"))
    }

    @Test
    fun `returns null when nothing digestable`() {
        assertEquals(null, ContextHistoryDigest.build(emptyList()))
        val systemOnly = listOf(UIMessage(role = MessageRole.SYSTEM, content = "system prompt"))
        assertEquals(null, ContextHistoryDigest.build(systemOnly))
    }

    @Test
    fun `keeps newest entries first when budget is tight`() {
        val dropped =
            listOf(
                UIMessage(role = MessageRole.USER, content = "noticias muy viejas"),
                UIMessage(role = MessageRole.USER, content = "noticias más recientes"),
            )

        val digest = ContextHistoryDigest.build(dropped, maxChars = 10)

        assertTrue(digest != null)
        assertTrue(digest!!.content.contains("noticias más recientes"))
        assertFalse(digest.content.contains("noticias muy viejas"))
    }
}
