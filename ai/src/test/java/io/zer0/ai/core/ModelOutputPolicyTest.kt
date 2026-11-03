package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prueba normalizada del límite superior de capacidad del modelo y el presupuesto de esta solicitud. */
class ModelOutputPolicyTest {

    private fun model(maxOutputTokens: Int? = null) = Model(
        id = "test-model",
        providerId = "test-provider",
        maxOutputTokens = maxOutputTokens,
    )

    @Test
    fun `explicit request budget is clamped to model capability`() {
        val target = model(maxOutputTokens = 8_192)

        assertEquals(8_192, ModelOutputPolicy.resolve(32_000, target))
        assertTrue(ModelOutputPolicy.wasClamped(32_000, target))
    }

    @Test
    fun `explicit smaller budget is preserved`() {
        val target = model(maxOutputTokens = 8_192)

        assertEquals(2_048, ModelOutputPolicy.resolve(2_048, target))
        assertFalse(ModelOutputPolicy.wasClamped(2_048, target))
    }

    @Test
    fun `known model limit is used when request budget is omitted`() {
        assertEquals(8_192, ModelOutputPolicy.resolve(null, model(maxOutputTokens = 8_192)))
    }

    @Test
    fun `context budget leaves reserve and never exceeds model limit`() {
        val target = model(maxOutputTokens = 8_192).copy(contextWindow = 16_384)

        assertEquals(
            8_192,
            ModelOutputPolicy.resolveForContext(
                null,
                target,
                inputTokens = 4_000,
                reserveTokens = 1_024,
            ),
        )
        assertEquals(
            4_360,
            ModelOutputPolicy.resolveForContext(
                10_000,
                target,
                inputTokens = 11_000,
                reserveTokens = 1_024,
            ),
        )
    }

    @Test
    fun `context window caps an inconsistent model output declaration`() {
        val target = model(maxOutputTokens = 100_000).copy(contextWindow = 16_384)

        assertEquals(16_384, ModelOutputPolicy.resolve(null, target))
        assertEquals(16_384, ModelOutputPolicy.resolve(50_000, target))
    }

    @Test
    fun `unknown model context keeps regular output policy`() {
        assertEquals(4_096, ModelOutputPolicy.resolveForContext(4_096, model(), inputTokens = 100_000))
    }

    @Test
    fun `unknown model limit preserves provider default`() {
        assertEquals(null, ModelOutputPolicy.resolve(null, model()))
        assertEquals(4_096, ModelOutputPolicy.resolve(4_096, model()))
    }

    @Test
    fun `no declared limit and no request budget never inflates to the whole context window`() {
        // Regresar:agnes-2.5-flash El modelo de clase solo declara contextWindow y no declara maxOutputTokens.
        // La antigua implementación available(≈512000)Emitido como presupuesto de resultados,max_tokens 400 rechazaron exceder el límite ascendente.
        val target = model().copy(contextWindow = 512_000)

        assertEquals(null, ModelOutputPolicy.resolveForContext(null, target, inputTokens = 143))
    }

    @Test
    fun `explicit budget with unknown model limit is still capped by remaining context`() {
        val target = model().copy(contextWindow = 512_000)

        assertEquals(100_000, ModelOutputPolicy.resolveForContext(100_000, target, inputTokens = 143))
        assertEquals(
            4_360,
            ModelOutputPolicy.resolveForContext(100_000, target, inputTokens = 506_616),
        )
    }

    @Test
    fun `non-positive request budget is treated as omitted`() {
        assertEquals(8_192, ModelOutputPolicy.resolve(0, model(maxOutputTokens = 8_192)))
        assertEquals(8_192, ModelOutputPolicy.resolve(-1, model(maxOutputTokens = 8_192)))
    }

    @Test
    fun `input token estimate includes reasoning tool calls and images`() {
        val message = UIMessage(
            role = MessageRole.USER,
            content = "tu".repeat(10) + "a".repeat(40),
            reasoning = "r".repeat(20),
            toolCalls = listOf(ToolCall("call-1", "search", "{\"q\":\"Muse\"}")),
            imageBase64List = listOf("image"),
        )
        val tool = ToolDefinition(
            name = "search",
            description = "search the web",
            parametersJsonSchema = """{"type":"object","properties":{"q":{"type":"string"}}}""",
        )

        val estimate = ModelOutputPolicy.estimateInputTokens(listOf(message), listOf(tool))

        assertTrue("image and auxiliary payloads must affect the estimate", estimate >= 1_025)
    }
}
