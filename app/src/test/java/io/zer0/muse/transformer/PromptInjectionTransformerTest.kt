package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PromptInjectionTransformer Pruebas unitarias.
 *
 * Leer entradas de inyección de context.extra,Pruebas de función pura.
 */
class PromptInjectionTransformerTest {

    private val transformer = PromptInjectionTransformer()

    private fun makeInjection(
        name: String = "test-mode",
        content: String = "Eres un asistente de prueba.",
        priority: Int = 0,
        insertionPosition: String = "after_system",
        enabled: Boolean = true,
        mode: String = "default",
    ) = PromptInjectionEntity(
        mode = mode,
        id = name,
        name = name,
        content = content,
        priority = priority,
        insertionPosition = insertionPosition,
        enabled = enabled,
    )

    @Test
    fun `injection inserted after system message`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "eres un asistente"),
            UIMessage(role = MessageRole.USER, content = "Hola"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(makeInjection())),
        )
        val result = transformer.transform(messages, context)

        assertEquals(3, result.size)
        assertEquals("eres un asistente", result[0].content)
        assertTrue(result[1].content.contains("[ModeInjection: test-mode]"))
        assertTrue(result[1].content.contains("Eres un asistente de prueba."))
        assertEquals("Hola", result[2].content)
    }

    @Test
    fun `injection inserted before system message`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "eres un asistente"),
            UIMessage(role = MessageRole.USER, content = "Hola"),
        )
        val context = TransformContext(
            extras = mapOf(
                "prompt_injections" to listOf(
                    makeInjection(insertionPosition = "before_system"),
                ),
            ),
        )
        val result = transformer.transform(messages, context)

        assertEquals(3, result.size)
        assertTrue(result[0].content.contains("[ModeInjection:"))
        assertEquals("eres un asistente", result[1].content)
        assertEquals("Hola", result[2].content)
    }

    @Test
    fun `no injection when prompt_injections is null`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "eres un asistente"),
        )
        val context = TransformContext(extras = emptyMap())
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `disabled injection is skipped`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "eres un asistente"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(makeInjection(enabled = false))),
        )
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `multiple injections ordered by priority`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "asistente"),
            UIMessage(role = MessageRole.USER, content = "Hola"),
        )
        val context = TransformContext(
            extras = mapOf(
                "prompt_injections" to listOf(
                    makeInjection(name = "low", priority = 1, content = "baja prioridad"),
                    makeInjection(name = "high", priority = 10, content = "alta prioridad"),
                ),
            ),
        )
        val result = transformer.transform(messages, context)

        assertEquals(4, result.size)
        // Alta prioridad primero
        assertTrue(result[1].content.contains("alta prioridad"))
        assertTrue(result[2].content.contains("baja prioridad"))
    }

    @Test
    fun `no system messages inserts at head`() = runTest {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "Sólo mensajes de usuario"),
        )
        val context = TransformContext(
            extras = mapOf("prompt_injections" to listOf(makeInjection())),
        )
        val result = transformer.transform(messages, context)

        assertEquals(2, result.size)
        assertTrue(result[0].content.contains("[ModeInjection:"))
        assertEquals("Sólo mensajes de usuario", result[1].content)
    }
}
