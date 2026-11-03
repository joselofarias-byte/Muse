package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lectura de caché/División de escritura: semántica de campos, múltiplos de precios, compatibilidad de datos antiguos.
 */
class CacheUsageSplitTest {

    @Test
    fun `cache read and write are separate fields`() {
        val usage = UsageTokens(promptTokens = 100, completionTokens = 50, cachedTokens = 1_000, cacheWriteTokens = 200)

        assertEquals(1_000, usage.cacheReadTokens)
        assertEquals(200, usage.cacheWriteTokens)
        // Vista de compatibilidad: cachedTokens se cargó con lectura + escritura antes de dividirse
        assertEquals(1_200, usage.cachedTokensTotal)
        // El calibre existente permanece sin cambios:total Todavía sólo mirando prompt + completion
        assertEquals(150, usage.total)
    }

    @Test
    fun `legacy usage with only cachedTokens is treated as cache read`() {
        // Solo el valor combinado (lectura + escritura) del registro anterior recae en cachedTokens. Al leer se interpreta como "leer" y al escribir se considera "leer". 0
        val legacy = UsageTokens(promptTokens = 1_000_000, cachedTokens = 400_000)

        assertEquals(400_000, legacy.cacheReadTokens)
        assertEquals(0, legacy.cacheWriteTokens)
        assertEquals(400_000, legacy.cachedTokensTotal)

        val info =
            KnownModels.KnownModelInfo(
                pricingPromptPer1M = 3.0,
                pricingCompletionPer1M = 15.0,
                pricingCachedInputPer1M = 0.3,
            )
        val cost = TokenCostCalculator.cost(TokenCostCalculator.fromUsageTokens(legacy), info)
        // 600k Entrada normal * 3.0 + 400k lectura de caché * 0.3 = 1.8 + 0.12
        assertTrue(cost.known)
        assertEquals(1.92, cost.usd, 1e-9)
    }

    @Test
    fun `cache write is priced at input multiplier for anthropic models`() {
        val usage = UsageTokens(cacheWriteTokens = 1_000_000)
        val cost = TokenCostCalculator.costFor("anthropic", "claude-sonnet-4", usage)

        assertTrue(cost.known)
        // claude-sonnet-4 Precio de entrada 3.0, escritura en caché 1.25x = 3.75
        assertEquals(3.75, cost.usd, 1e-9)
    }

    @Test
    fun `cache write multiplier applies after stripping aggregator prefix`() {
        val usage = UsageTokens(cacheWriteTokens = 1_000_000)
        val direct = TokenCostCalculator.costFor("anthropic", "claude-sonnet-4", usage)
        val prefixed = TokenCostCalculator.costFor("openrouter", "anthropic/claude-sonnet-4", usage)

        assertTrue(prefixed.known)
        assertEquals(direct.usd, prefixed.usd, 1e-9)
        assertEquals(3.75, prefixed.usd, 1e-9)
    }

    @Test
    fun `cache write price is not invented for non anthropic models`() {
        val info = KnownModels.lookupProvider("openrouter", "gpt-4o")

        assertNotNull(info)
        assertNotNull(info!!.pricingPromptPer1M)
        val priced = TokenCostCalculator.withEstimatedCacheWritePrice("gpt-4o", info)
        assertEquals(null, priced.pricingCacheWritePer1M)
    }

    @Test
    fun `anthropic prompt tokens are not clamped when cache is large`() {
        // Anthropic Los input_tokens son entradas no almacenadas en caché, lecturas almacenadas en caché/Escribe una sola columna; una vez que presionas "prompt incluyendo caché" se restan,
        // 5000+2000 La cantidad de caché limitará 1000 entradas normales a 0 y la tarifa de entrada desaparecerá directamente.
        val anthropic =
            UsageTokens(
                promptTokens = 1_000,
                cachedTokens = 5_000,
                cacheWriteTokens = 2_000,
                promptTokensIncludeCache = false,
            )
        val cost = TokenCostCalculator.costFor("anthropic", "claude-sonnet-4", anthropic)

        assertTrue(cost.known)
        // 1000 entrada * 3.0 + 5000 lectura de caché * 3.0(Al leer el precio unitario sin caché, se utilizará el precio de entrada)+ 2000 escribir * 3.75
        val expected = 6_000 / 1_000_000.0 * 3.0 + 2_000 / 1_000_000.0 * 3.75
        assertEquals(expected, cost.usd, 1e-12)

        // Para el mismo número, si presiona "prompt Incluyendo el cálculo de caché" (OpenAI/Gemini calibre), la entrada ordinaria se fija en 0 y la cantidad es menor
        val clamped = TokenCostCalculator.costFor(
            "anthropic",
            "claude-sonnet-4",
            anthropic.copy(promptTokensIncludeCache = true),
        )
        assertTrue("El calibre de resta perderá la tarifa de entrada y la cantidad debería ser menor", clamped.usd < cost.usd)
    }

    @Test
    fun `openai style prompt still subtracts cache from prompt`() {
        val usage = UsageTokens(promptTokens = 1_000_000, cachedTokens = 1_000_000)
        val info =
            KnownModels.KnownModelInfo(
                pricingPromptPer1M = 3.0,
                pricingCompletionPer1M = 15.0,
                pricingCachedInputPer1M = 0.3,
            )
        // Predeterminado promptTokensIncludeCache = true: Cuando todo llega al caché, la entrada normal es 0
        val cost = TokenCostCalculator.cost(TokenCostCalculator.fromUsageTokens(usage), info)

        assertEquals(0.3, cost.usd, 1e-9)
    }

    @Test
    fun `explicit cache write price in table wins over multiplier`() {
        val info =
            KnownModels.KnownModelInfo(
                pricingPromptPer1M = 3.0,
                pricingCompletionPer1M = 15.0,
                pricingCacheWritePer1M = 9.0,
            )
        val priced = TokenCostCalculator.withEstimatedCacheWritePrice("claude-sonnet-4", info)

        assertEquals(9.0, priced.pricingCacheWritePer1M!!, 1e-9)
    }

    @Test
    fun `missing input price leaves cache write unpriced`() {
        val info = KnownModels.KnownModelInfo(pricingPromptPer1M = null, pricingCompletionPer1M = 15.0)
        val priced = TokenCostCalculator.withEstimatedCacheWritePrice("claude-sonnet-4", info)

        assertEquals(null, priced.pricingCacheWritePer1M)
    }
}
