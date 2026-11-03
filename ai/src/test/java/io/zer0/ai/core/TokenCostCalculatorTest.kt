package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.3 (P4-3): Token Prueba de costeo.
 */
class TokenCostCalculatorTest {

    private val info =
        KnownModels.KnownModelInfo(
            pricingPromptPer1M = 3.0,
            pricingCompletionPer1M = 15.0,
            pricingCachedInputPer1M = 0.3,
            pricingCacheWritePer1M = 3.75,
        )

    @Test
    fun `plain usage prices input and output`() {
        val usage = TokenCostCalculator.Usage(promptTokens = 1_000_000, completionTokens = 1_000_000)
        val cost = TokenCostCalculator.cost(usage, info)
        assertTrue(cost.known)
        // entrada 1M * 3 + salida 1M * 15 = 18
        assertEquals(18.0, cost.usd, 0.0001)
    }

    @Test
    fun `cached input uses cheaper rate`() {
        val usage =
            TokenCostCalculator.Usage(
                promptTokens = 1_000_000,
                cachedTokens = 1_000_000,
                completionTokens = 0,
            )
        val cost = TokenCostCalculator.cost(usage, info)
        // Todos los aciertos de caché:1M * 0.3
        assertEquals(0.3, cost.usd, 0.0001)
    }

    @Test
    fun `cache write charged when priced`() {
        val usage = TokenCostCalculator.Usage(promptTokens = 1_000_000, cacheWriteTokens = 1_000_000)
        val cost = TokenCostCalculator.cost(usage, info)
        // Todas las escrituras en caché:1M * 3.75
        assertEquals(3.75, cost.usd, 0.0001)
    }

    @Test
    fun `cache write free when provider does not charge`() {
        val infoNoWrite = info.copy(pricingCacheWritePer1M = null)
        val usage = TokenCostCalculator.Usage(promptTokens = 1_000_000, cacheWriteTokens = 1_000_000)
        val cost = TokenCostCalculator.cost(usage, infoNoWrite)
        // Las escrituras en caché no se facturan
        assertEquals(0.0, cost.usd, 0.0001)
    }

    @Test
    fun `cached tokens clamped to prompt tokens`() {
        val usage =
            TokenCostCalculator.Usage(
                promptTokens = 100,
                cachedTokens = 10_000,
                completionTokens = 0,
            )
        val cost = TokenCostCalculator.cost(usage, info)
        // billedCached Sujete a 100, la entrada normal es 0
        assertEquals(100 / 1_000_000.0 * 0.3, cost.usd, 1e-9)
    }

    @Test
    fun `unknown price returns known false`() {
        val cost = TokenCostCalculator.cost(TokenCostCalculator.Usage(promptTokens = 100), null)
        assertFalse(cost.known)
        assertEquals(0.0, cost.usd, 1e-9)
    }

    @Test
    fun `costFor looks up pricing by provider and model`() {
        val usage = TokenCostCalculator.Usage(promptTokens = 1_000_000, completionTokens = 1_000_000)
        val cost = TokenCostCalculator.costFor("openrouter", "gpt-4o", usage)
        assertTrue("gpt-4o El precio debe ser calculable.", cost.known)
        assertTrue("el costo debe ser positivo", cost.usd > 0)
    }

    @Test
    fun `usage saturating add does not overflow`() {
        val a = TokenCostCalculator.Usage(promptTokens = Long.MAX_VALUE)
        val b = TokenCostCalculator.Usage(promptTokens = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, a.plus(b).promptTokens)
    }
}
