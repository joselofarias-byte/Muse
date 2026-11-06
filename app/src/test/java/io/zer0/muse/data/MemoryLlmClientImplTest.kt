package io.zer0.muse.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryLlmClientImplTest {

    @Test
    fun `visible text always wins over reasoning`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "Generar la lista fusionada",
            text = "El nombre del usuario es Ziqi.",
            reasoningContent = "necesitamos answer in Chinese",
        )
        assertEquals("El nombre del usuario es Ziqi.", raw)
    }

    @Test
    fun `reasoning only rejected when prompt does not require JSON`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "Generar la lista fusionada,No utilice encabezados de Markdown",
            text = "",
            reasoningContent = "necesitamos answer in Chinese. Need merge facts.",
        )
        assertNull(raw)
    }

    @Test
    fun `reasoning only rejected when JSON prompt has no JSON payload`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "Solo genera una matriz JSON",
            text = "",
            reasoningContent = "necesitamos answer in Chinese. Need merge facts.",
        )
        assertNull(raw)
    }

    @Test
    fun `reasoning accepted when JSON prompt contains JSON payload`() {
        val reasoning = """[
          {"fact": "El nombre del usuario es Ziqi.", "tags": ["Ziqi"], "importance": 2}
        ]"""
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "Solo genera una matriz JSON",
            text = "",
            reasoningContent = reasoning,
        )
        assertEquals(reasoning.trim(), raw)
    }

    @Test
    fun `both text and reasoning blank rejected`() {
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "Solo genera una matriz JSON",
            text = "",
            reasoningContent = null,
        )
        assertNull(raw)
    }

    @Test
    fun `english structured prompt also recognizes JSON reasoning`() {
        val reasoning = """[{"fact": "name is ZiQi"}]"""
        val raw = resolveMemoryLlmRawText(
            systemPrompt = "Output Format\nOutput a strict JSON array only",
            text = "",
            reasoningContent = reasoning,
        )
        assertEquals(reasoning, raw)
    }
}
