package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B10: Función pura y liviana para la eliminación en tiempo real de la transmisión ThinkTag [splitThinkTagsForStreaming] Prueba única.
 *
 * Portada: cerrado / no cerrado / multibloque / sin etiqueta cortocircuito / razonamiento existente omitir / caso,
 * y [ThinkTagTransformer.visualTransform] Delegar coherencia para esta función.
 */
class StreamingThinkSplitTest {

    // ── función pura splitThinkTagsForStreaming ─────────────────────────────

    @Test
    fun `no think marker returns original content unchanged`() {
        val (reasoning, content) = splitThinkTagsForStreaming("Texto en streaming normal,sin etiqueta")
        assertNull(reasoning)
        assertEquals("Texto en streaming normal,sin etiqueta", content)
    }

    @Test
    fun `closed think block split into reasoning and content`() {
        val (reasoning, content) = splitThinkTagsForStreaming("<think>estoy pensando</think>La respuesta es42")
        assertEquals("estoy pensando", reasoning)
        assertEquals("La respuesta es42", content)
    }

    @Test
    fun `closed block only leaves empty content`() {
        val (reasoning, content) = splitThinkTagsForStreaming("<think>solo pensando</think>")
        assertEquals("solo pensando", reasoning)
        assertEquals("", content)
    }

    @Test
    fun `unclosed think during streaming is surfaced as reasoning`() {
        // Común en streaming: solo llega <think> comienzo,Aún no estoy esperando </think>
        val (reasoning, content) = splitThinkTagsForStreaming("<think>razonamiento")
        assertEquals("razonamiento", reasoning)
        assertEquals("", content)
    }

    @Test
    fun `closed block then unclosed remainder`() {
        val (reasoning, content) = splitThinkTagsForStreaming("<think>A</think>Texto<think>B")
        assertEquals("A\nB", reasoning)
        assertEquals("Texto", content)
    }

    @Test
    fun `multiple closed blocks joined by newline`() {
        val (reasoning, content) = splitThinkTagsForStreaming("<think>uno</think><think>dos</think>Conclusión")
        assertEquals("uno\ndos", reasoning)
        assertEquals("Conclusión", content)
    }

    @Test
    fun `empty think tag stripped while reasoning stays null`() {
        val (reasoning, content) = splitThinkTagsForStreaming("<think></think>Texto")
        assertNull(reasoning)
        assertEquals("Texto", content)
    }

    @Test
    fun `case insensitive think tag`() {
        val (reasoning, content) = splitThinkTagsForStreaming("<THINK>mayúsculas</THINK>Texto")
        assertEquals("mayúsculas", reasoning)
        assertEquals("Texto", content)
    }

    @Test
    fun `existing reasoning skips split`() {
        val (reasoning, content) = splitThinkTagsForStreaming("<think>nuevo pensamiento</think>Texto", "ya inferido")
        assertEquals("ya inferido", reasoning)
        assertEquals("<think>nuevo pensamiento</think>Texto", content)
    }

    @Test
    fun `thinking variant not triggered by streaming guard`() {
        // Consistente con visualTransform guard: solo <think> Activa esta ruta rápida de transmisión;
        // <thinking> La ruta de limpieza completa pasada por la variante para actualizarAssistant,Déjalo como está aquí.
        val (reasoning, content) = splitThinkTagsForStreaming("<thinking>razonamiento</thinking>Texto")
        assertNull(reasoning)
        assertEquals("<thinking>razonamiento</thinking>Texto", content)
    }

    // ── visualTransform Delegar coherencia ────────────────────────────────────

    @Test
    fun `visualTransform delegates closed block`() = runTest {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "<think>pensar</think>Responder")
        val result = ThinkTagTransformer().visualTransform(listOf(msg), TransformContext())[0]
        assertEquals("pensar", result.reasoning)
        assertEquals("Responder", result.content)
    }

    @Test
    fun `visualTransform surfaces unclosed think`() = runTest {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "<think>Piensa en tiempo real")
        val result = ThinkTagTransformer().visualTransform(listOf(msg), TransformContext())[0]
        assertEquals("Piensa en tiempo real", result.reasoning)
        assertEquals("", result.content)
    }

    @Test
    fun `visualTransform leaves plain message untouched`() = runTest {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "Texto puro")
        val result = ThinkTagTransformer().visualTransform(listOf(msg), TransformContext())[0]
        assertEquals(msg, result)
    }

    @Test
    fun `visualTransform skips message with existing reasoning`() = runTest {
        val msg = UIMessage(role = MessageRole.ASSISTANT, content = "<think>x</think>y", reasoning = "Ya existe")
        val result = ThinkTagTransformer().visualTransform(listOf(msg), TransformContext())[0]
        assertEquals(msg, result)
    }
}
