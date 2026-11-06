package io.zer0.muse.transformer

import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Solo cuando se excede el presupuesto del token y la cantidad de mensajes no caduca, se debe activar la compresión.
 *
 * Este artículo una vez fue roto por una "optimización": cambiar el juicio presupuestario a `overCount && isOver(...)` forma de cortocircuito,
 * Entonces "el número de artículos no superó → Sin contar el presupuesto → Se considera que está dentro del presupuesto → Regrese directamente al historial original", la compresión nunca se activará.
 * y `compress_char_budget` Esta clave ha sido interpretada según el presupuesto del token en v2.3.2, precisamente para
 * Cubre sesiones con sesiones "no muchas pero muy largas" (documentos largos, código fuente de tarjeta, pegado de registros).
 *
 * Aquí, se utiliza un umbral mínimo para construir una entrada que tenga "una pequeña cantidad de entradas pero debe exceder el presupuesto" y bloquear esa rama.
 */
class ContextCompressTokenBudgetTriggerTest {

    @Test
    fun `token budget overrun triggers compression even when message count is under threshold`() = runTest {
        val compressor = mockk<ConversationCompressor>()
        coEvery { compressor.compress(any(), any()) } returns null
        val transformer = ContextCompressTransformer(
            chatService = mockk(relaxed = true),
            compressor = compressor,
        )
        // cada uno es muy largo → token Muy grande; pero el número de entradas es sólo 4, muy por debajo del umbral de 999.
        val messages = List(4) { index ->
            UIMessage(
                role = if (index == 0) MessageRole.USER else MessageRole.ASSISTANT,
                content = "Se utiliza contenido histórico muy extenso para estirar el presupuesto simbólico. ".repeat(200),
            )
        }
        val totalTokens = TokenEstimator.estimate(messages)
        assertTrue("Requisito previo: los tokens para este lote de mensajes deben exceder con creces el presupuesto", totalTokens > 100)

        val result = transformer.transform(
            messages,
            TransformContext(
                sessionId = "session-budget",
                extras = mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to 999,
                    "compress_keep_recent" to 1,
                    "compress_char_budget" to 100,
                    // Cuando el modelo de resumen falla, aún se debe generar una marca de truncamiento visible para evitar casos de uso que dependan del modelo real.
                    "compress_force_fallback" to true,
                ),
            ),
        )

        assertTrue(
            "No se excede el número de artículos pero el token excede el presupuesto y debe comprimirse. El número real de resultados es=${result.size}",
            result.size != messages.size,
        )
    }

    @Test
    fun `neither over count nor over budget keeps history untouched`() = runTest {
        val transformer = ContextCompressTransformer(
            chatService = mockk(relaxed = true),
            compressor = mockk(relaxed = true),
        )
        val messages = List(3) { UIMessage(role = MessageRole.USER, content = "mensaje corto $it") }

        val result = transformer.transform(
            messages,
            TransformContext(
                sessionId = "session-idle",
                extras = mapOf(
                    "compress_enabled" to true,
                    "compress_threshold" to 999,
                    "compress_keep_recent" to 1,
                    "compress_char_budget" to 100_000,
                ),
            ),
        )

        assertTrue("Si ambas condiciones no expiran, el historial no se puede cambiar.", result.size == messages.size)
    }
}
