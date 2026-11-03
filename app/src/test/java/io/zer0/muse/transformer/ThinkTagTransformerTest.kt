package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThinkTagTransformer + stripThinkTags Pruebas unitarias.
 *
 * ThinkTagTransformer Se utiliza para poner el mensaje ASISTENTE en `<think>...</think>` Las etiquetas se extraen en el campo de razonamiento.
 * stripThinkTags es una función de herramienta independiente,Piense en la limpieza de etiquetas para las rutas de traducción.
 */
class ThinkTagTransformerTest {

    private val transformer = ThinkTagTransformer()

    // ======================== ThinkTagTransformer ========================

    @Test
    fun `think tag extracted to reasoning field`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>Este es el proceso de pensamiento.</think>Esta es la respuesta final",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals(1, result.size)
        assertEquals("Este es el proceso de pensamiento.", result[0].reasoning)
        assertEquals("Esta es la respuesta final", result[0].content)
    }

    @Test
    fun `think tag removed from content`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>análisis paso a paso</think>entonces la respuesta es42.",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("entonces la respuesta es42.", result[0].content)
        assertEquals("análisis paso a paso", result[0].reasoning)
    }

    @Test
    fun `no think tag returns original message`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "Esta es una respuesta normal.",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals(messages[0], result[0])
        assertNull(result[0].reasoning)
    }

    @Test
    fun `only think tag with no content after`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>proceso de pensamiento</think>",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("", result[0].content)
        assertEquals("proceso de pensamiento", result[0].reasoning)
    }

    @Test
    fun `empty think tag stripped`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think></think>Responder contenido",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("Responder contenido", result[0].content)
        assertNull(result[0].reasoning) // etiqueta vacía,extracted=null
    }

    @Test
    fun `unclosed think tag left intact`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>Etiqueta no cerrada",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals(messages[0].content, result[0].content)
        assertNull(result[0].reasoning)
    }

    @Test
    fun `only ASSISTANT messages are processed`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "tu eres el asistente"),
            UIMessage(role = MessageRole.USER, content = "<think>Pensamiento del usuario</think>Hola"),
            UIMessage(role = MessageRole.ASSISTANT, content = "<think>asistente de pensamiento</think>Responder"),
        )
        val result = transformer.transform(messages, TransformContext())

        // SYSTEM El mensaje sigue siendo el mismo.
        assertEquals(messages[0], result[0])
        // USER El mensaje sigue siendo el mismo.(Incluso si la etiqueta think está incluida)
        assertEquals(messages[1], result[1])
        // ASSISTANT El mensaje ha sido procesado.
        assertEquals("Responder", result[2].content)
        assertEquals("asistente de pensamiento", result[2].reasoning)
    }

    @Test
    fun `existing reasoning field skips processing`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>pensar</think>Responder",
                reasoning = "ya inferido",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("ya inferido", result[0].reasoning)
        // content permanecer sin cambios,Porque ya existe un campo de razonamiento. -> Saltar procesamiento
        assertEquals("<think>pensar</think>Responder", result[0].content)
    }

    @Test
    fun `multiple think tags in one message`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>primer paso</think><think>Paso 2</think>respuesta final",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("respuesta final", result[0].content)
        assertEquals("primer paso\nPaso 2", result[0].reasoning)
    }

    @Test
    fun `think tag case insensitive`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<THINK>etiqueta mayúscula</THINK>contenido",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertEquals("contenido", result[0].content)
        assertEquals("etiqueta mayúscula", result[0].reasoning)
    }

    @Test
    fun `think tag cross line`() = runTest {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "<think>\nmúltiples líneas\npensar\n</think>\nResponder contenido",
            ),
        )
        val result = transformer.transform(messages, TransformContext())

        assertNotNull(result[0].reasoning)
        assertTrue(result[0].reasoning!!.contains("múltiples líneas"))
        assertTrue(result[0].reasoning!!.contains("pensar"))
        assertEquals("Responder contenido", result[0].content.trim())
    }

    // ======================== stripThinkTags utility ========================

    @Test
    fun `stripThinkTags removes think blocks`() {
        val result = stripThinkTags("<think>pensar</think>Responder")
        assertEquals("Responder", result)
    }

    @Test
    fun `stripThinkTags returns original when no tags`() {
        val result = stripThinkTags("Texto normal sin etiquetas")
        assertEquals("Texto normal sin etiquetas", result)
    }

    @Test
    fun `stripThinkTags handles empty content with only tags`() {
        val result = stripThinkTags("<think>pensar</think>")
        assertEquals("", result)
    }

    @Test
    fun `stripThinkTags handles text before and after tags`() {
        val result = stripThinkTags("comienzo<think>medio</think>finalizando")
        assertEquals("comienzofinalizando", result)
    }
}
