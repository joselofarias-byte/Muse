package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallSanitizerTest {
    @Test
    fun `valid tool call is accepted`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = """{"query":"hello"}""")
        assertTrue(ToolCallSanitizer.isValid(tc))
        assertEquals(listOf(tc), ToolCallSanitizer.sanitize(listOf(tc)))
    }

    @Test
    fun `blank name is rejected`() {
        val tc = ToolCall(id = "1", name = "  ", arguments = """{"query":"hello"}""")
        assertFalse(ToolCallSanitizer.isValid(tc))
        assertTrue(ToolCallSanitizer.sanitize(listOf(tc)).isEmpty())
    }

    @Test
    fun `blank arguments are preserved as empty object`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = " ")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc))
        assertEquals(1, sanitized.size)
        assertEquals("{}", sanitized.single().arguments)
    }

    @Test
    fun `truncated json is balanced and preserved`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = """{"query":"hello""")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc)).single()
        assertEquals("""{"query":"hello"}""", sanitized.arguments)
    }

    @Test
    fun `concatenated objects are merged`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = """{"query":"x"}{"max_results":5}""")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc)).single()
        assertTrue(sanitized.arguments.contains("\"query\""))
        assertTrue(sanitized.arguments.contains("\"max_results\""))
        assertTrue(ToolCallSanitizer.isValidJson(sanitized.arguments))
    }

    @Test
    fun `unrecoverable arguments remain callable with empty object`() {
        val tc = ToolCall(id = "1", name = "web_search", arguments = "not json")
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc)).single()
        assertEquals("{}", sanitized.arguments)
    }

    @Test
    fun `valid json arguments are untouched`() {
        val ok = ToolCall(id = "1", name = "speak_text", arguments = """{"text":"hello"}""")
        assertEquals(ok, ToolCallSanitizer.sanitize(listOf(ok))[0])
    }

    @Test
    fun `json array arguments are accepted`() {
        val arr = ToolCall(id = "1", name = "x", arguments = """[1,2,3]""")
        assertTrue(ToolCallSanitizer.isValidJson(arr.arguments))
        assertEquals(arr, ToolCallSanitizer.sanitize(listOf(arr))[0])
    }

    @Test
    fun `diagnostic argument preview does not expose sensitive input`() {
        val preview = ToolCallSanitizer.safeArgumentsForLog(
            toolName = "browser_type",
            arguments = """{"selector":"#password","text":"super-secret-password"}""",
        )

        assertFalse(preview.contains("super-secret-password"))
        assertTrue(preview.contains("[REDACTED]"))
        assertTrue(preview.contains("browser_type"))
    }

    // ── v2.6.7: El modelo escribe el texto en la protección de function.name ──────────────────

    /**
     * Registro de mediciones reales:GLM-4-9B Regresar name="¡Está bien, Ziqi! Crearé un gráfico interactivo.…show_card".
     * La implementación anterior solo verifica isNotBlank(), esta convocatoria pasará por la limpieza, aprobación y ejecución como herramienta.
     * Finalmente con“herramienta desconocida”Fallo, la percepción del usuario es“La tarjeta no se puede generar en absoluto.”.
     */
    @Test
    fun `chinese prose in tool name is rejected when no tool list is available`() {
        val tc = ToolCall(
            id = "1",
            name = "¡Está bien, Ziqi! Crearé un gráfico interactivo simple que muestre las cantidades de diferentes frutas. Espere un momento, lo generaré para usted de inmediato.show_card",
            arguments = """{"title":"x"}""",
        )
        assertFalse(ToolCallSanitizer.isValidName(tc.name))
        assertFalse(ToolCallSanitizer.isValid(tc))
        // No adivine cuando no hay una lista de herramientas y evite ejecutar palabras en inglés en el texto como nombres de herramientas.
        assertTrue(ToolCallSanitizer.sanitize(listOf(tc)).isEmpty())
    }

    // ── v2.6.10: Los nombres de herramientas que causan contaminación del texto deben poder restaurarse ──────────────────────────

    /**
     * Registro de medición real (agnes Puerta de enlace): el modelo concatena la línea completa y el nombre de la función en una cadena y la envía.
     * name = "Sure, I can help with that. ... Please hold on a moment.show_card"(len=135).
     * Todo el comportamiento anterior se descarta y el usuario ve“El formato del parámetro es anormal; descríbalo nuevamente.”y vuelve a intentarlo con el mismo resultado.
     */
    @Test
    fun `prose plus known tool name is salvaged`() {
        val tc = ToolCall(
            id = "1",
            name = "Sure, I can help with that. I'll create a card. Please hold on a moment.show_card",
            arguments = """{"title":"t","code":"<div>x</div>"}""",
        )
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc), setOf("show_card", "web_search"))
        assertEquals(1, sanitized.size)
        assertEquals("show_card", sanitized.single().name)
        assertTrue(ToolCallSanitizer.isValid(sanitized.single()))
    }

    @Test
    fun `prose without a known tool name is still dropped`() {
        val tc = ToolCall(
            id = "1",
            name = "I will look that up for you right now",
            arguments = """{"query":"x"}""",
        )
        assertTrue(ToolCallSanitizer.sanitize(listOf(tc), setOf("show_card")).isEmpty())
    }

    @Test
    fun `trailing word that is not a registered tool is not accepted`() {
        val tc = ToolCall(
            id = "1",
            name = "Please hold on a moment",
            arguments = "{}",
        )
        // fin "moment" El formulario es legal, pero no está en la lista y no puede usarse como herramienta.
        assertTrue(ToolCallSanitizer.sanitize(listOf(tc), setOf("show_card")).isEmpty())
    }

    @Test
    fun `salvaged call also gets its arguments repaired`() {
        val tc = ToolCall(
            id = "1",
            name = "Let me draw it. show_card",
            arguments = """{"title":"t""",
        )
        val sanitized = ToolCallSanitizer.sanitize(listOf(tc), setOf("show_card")).single()
        assertEquals("show_card", sanitized.name)
        assertTrue(ToolCallSanitizer.isValidJson(sanitized.arguments))
    }

    @Test
    fun `tool name with spaces or punctuation is rejected`() {
        assertFalse(ToolCallSanitizer.isValidName("web search"))
        assertFalse(ToolCallSanitizer.isValidName("web.search"))
        assertFalse(ToolCallSanitizer.isValidName("webBuscar"))
    }

    @Test
    fun `legitimate tool names are accepted`() {
        assertTrue(ToolCallSanitizer.isValidName("web_search"))
        assertTrue(ToolCallSanitizer.isValidName("show_card"))
        assertTrue(ToolCallSanitizer.isValidName("mcp-server-tool"))
        assertTrue(ToolCallSanitizer.isValidName("tool2"))
    }

    @Test
    fun `overlong tool name is rejected`() {
        assertFalse(ToolCallSanitizer.isValidName("a".repeat(65)))
    }
}
