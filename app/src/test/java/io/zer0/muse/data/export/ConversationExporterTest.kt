package io.zer0.muse.data.export

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * [ConversationExporter] La parte de función pura de(Markdown / HTML)Prueba única.
 *
 * Anule solo las rutas de exportación que no dependen de Android;PDF depender de [android.graphics.pdf.PdfDocument],
 * No se puede crear una instancia en la prueba unitaria JVM,Por lo tanto, no está dentro del alcance de esta prueba.(necesitar instrumented / Robolectric).
 */
class ConversationExporterTest {

    private fun msg(role: MessageRole, content: String, reasoning: String? = null, createdAt: Long = 1_700_000_000_000L) =
        UIMessage(role = role, content = content, reasoning = reasoning, createdAt = createdAt)

    @Test
    fun `markdown starts with title and uses english role labels for non-zh`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.USER, "hello"), msg(MessageRole.ASSISTANT, "hi there")),
            chatTitle = "My Chat",
            locale = Locale.ENGLISH,
        )
        assertTrue("Debería comenzar con # título", out.startsWith("# My Chat"))
        assertTrue("Debe contener etiquetas de caracteres en inglés.", out.contains("**User**: hello"))
        assertTrue("Se debe incluir una etiqueta de ayuda en inglés.", out.contains("**Assistant**: hi there"))
    }

    @Test
    fun `markdown uses chinese role labels for zh locale`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.USER, "Hola"), msg(MessageRole.ASSISTANT, "en")),
            chatTitle = "sesión",
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertTrue(out.contains("**Usuario**: Hola"))
        assertTrue(out.contains("**asistente**: en"))
    }

    @Test
    fun `markdown marks empty assistant reply instead of leaving a blank`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.ASSISTANT, "")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue("Las respuestas vacías deben marcarse (Empty reply)", out.contains("(Empty reply)"))
    }

    @Test
    fun `markdown keeps reasoning as its own section`() {
        val out = ConversationExporter.exportToMarkdown(
            listOf(msg(MessageRole.ASSISTANT, "answer", reasoning = "because 1+1=2")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue(out.contains("### Thinking"))
        assertTrue(out.contains("because 1+1=2"))
    }

    @Test
    fun `json export preserves session identity and complete message fields`() {
        val out = ConversationExporter.exportToJson(
            sessionId = "session-1",
            messages = listOf(msg(MessageRole.USER, "hello")),
            chatTitle = "My Chat",
        )
        val root = Json.parseToJsonElement(out).jsonObject
        assertTrue(root["sessionId"]?.toString() == "\"session-1\"")
        assertTrue(root["messages"]?.toString()?.contains("hello") == true)
    }

    @Test
    fun `html is a standalone document with lang and escaped title`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.USER, "hi")),
            chatTitle = "A & B <x>",
            locale = Locale.ENGLISH,
        )
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        assertTrue("La configuración regional en inglés debería aparecer lang=en", out.contains("lang=\"en\""))
        assertTrue("<title> se debe escapar", out.contains("<title>A &amp; B &lt;x&gt;</title>"))
    }

    @Test
    fun `html escapes message content so raw tags cannot leak`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.USER, "<script>alert(1)</script>")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue("El texto debe tener escape.", out.contains("&lt;script&gt;"))
        assertTrue("Las etiquetas de script sin formato ejecutables no deben estar presentes", !out.contains("<script>alert"))
    }

    @Test
    fun `html renders fenced code block as pre code with language class`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.ASSISTANT, "```kotlin\nval x = 1\n```")),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )
        assertTrue(out.contains("<pre><code class=\"language-kotlin\">"))
        assertTrue(out.contains("val x = 1"))
    }

    @Test
    fun `html uses zh lang for chinese locale`() {
        val out = ConversationExporter.exportToHtml(
            listOf(msg(MessageRole.USER, "Hola")),
            chatTitle = "sesión",
            locale = Locale.SIMPLIFIED_CHINESE,
        )
        assertTrue(out.contains("lang=\"zh-CN\""))
    }

    @Test
    fun `html inlines base64 image and downgrades remote url to a safe link`() {
        val m = UIMessage(
            role = MessageRole.USER,
            content = "pic",
            imageBase64List = listOf("AAAA"),
            imageUrls = listOf("https://example.com/a.png"),
            createdAt = 1L,
        )
        val out = ConversationExporter.exportToHtml(listOf(m), "t", Locale.ENGLISH)
        assertTrue("base64 La figura debe estar alineada como data URI", out.contains("src=\"data:image/jpeg;base64,AAAA\""))
        assertTrue("El gráfico remoto debe degradarse a un enlace con rel", out.contains("rel=\"noopener noreferrer\""))
    }

    // ── v2.6.8: card_state La cola no se puede importar ni exportar. ──────────────────────────────

    /** El campo de la máquina devuelto por la tarjeta (cardId / JSON valor-clave) no puede aparecer en ningún formato de exportación. */
    private fun cardStateMessage(): UIMessage = msg(
        MessageRole.USER,
        io.zer0.muse.ui.markdown.appendCardStateToMessage(
            text = "Configurar el sueño en 6 horas",
            cardId = "msg-123",
            data = """{"hours":6}""",
        ),
    )

    @Test
    fun `markdown export drops the card state tail`() {
        val out = ConversationExporter.exportToMarkdown(listOf(cardStateMessage()), chatTitle = "t", locale = Locale.ENGLISH)

        assertTrue("mantener el texto", out.contains("Configurar el sueño en 6 horas"))
        assertFalse("La etiqueta de estado de la tarjeta no se puede exportar", out.contains("card_state"))
        assertFalse("La identidad de la tarjeta no se puede exportar", out.contains("cardId="))
        assertFalse("No se pueden exportar valores-clave JSON", out.contains("hours"))
    }

    @Test
    fun `json export drops the card state tail`() {
        val out = ConversationExporter.exportToJson(
            sessionId = "s1",
            messages = listOf(cardStateMessage()),
            chatTitle = "t",
            locale = Locale.ENGLISH,
        )

        assertTrue(out.contains("Configurar el sueño en 6 horas"))
        assertFalse(out.contains("card_state"))
        assertFalse(out.contains("cardId="))
        assertFalse(out.contains("hours"))
    }

    @Test
    fun `html export drops the card state tail`() {
        val out = ConversationExporter.exportToHtml(listOf(cardStateMessage()), chatTitle = "t", locale = Locale.ENGLISH)

        assertTrue(out.contains("Configurar el sueño en 6 horas"))
        assertFalse(out.contains("card_state"))
        assertFalse(out.contains("cardId="))
        assertFalse(out.contains("hours"))
    }
}
