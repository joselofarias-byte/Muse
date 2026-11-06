package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    @Test
    fun `window unknown falls back to the default budget`() {
        val result = ContextBudget.compute(contextWindow = 0)
        assertEquals(ContextBudget.DEFAULT_FALLBACK_BUDGET, result.historyBudget)
        assertEquals(0, result.outputReserve)
    }

    @Test
    fun `drains system tools output and safety from the window`() {
        // 200K Ventana, sistema 10K, herramienta 30K, límite superior de salida 8K
        // margen de seguridad = max(1024, 200000 * 5%) = 10000
        // presupuesto histórico = 200000 - 10000 - 30000 - 8000 - 10000 = 142000
        val result = ContextBudget.compute(
            contextWindow = 200_000,
            systemTokens = 10_000,
            toolTokens = 30_000,
            maxOutputTokens = 8_000,
        )
        assertEquals(8_000, result.outputReserve)
        assertEquals(10_000, result.safetyTokens)
        assertEquals(142_000, result.historyBudget)
    }

    @Test
    fun `output reserve is clamped within a usable range`() {
        assertEquals(4_096, ContextBudget.outputReserveFor(0))
        assertEquals(4_096, ContextBudget.outputReserveFor(1_000))
        assertEquals(16_384, ContextBudget.outputReserveFor(200_000))
        assertEquals(8_000, ContextBudget.outputReserveFor(8_000))
    }

    @Test
    fun `safety margin never drops below one thousand tokens`() {
        val result = ContextBudget.compute(contextWindow = 4_000, maxOutputTokens = 0)
        assertEquals(1_024, result.safetyTokens)
    }

    @Test
    fun `tool heavy sessions keep a floor instead of collapsing to zero`() {
        // Cuando la definición de la herramienta ocupa la mayor parte de la ventana, la deducción estricta llevará el historial a un número negativo. → La compresión se activa en cada ronda, pero no se puede presionar nada.
        val result = ContextBudget.compute(
            contextWindow = 100_000,
            systemTokens = 20_000,
            toolTokens = 70_000,
            maxOutputTokens = 16_384,
        )
        assertEquals(20_000, result.historyBudget)
        assertTrue("El presupuesto histórico no debe ser negativo.", result.historyBudget > 0)
    }

    @Test
    fun `larger window yields larger history budget for the same payload`() {
        val small = ContextBudget.compute(contextWindow = 32_000, systemTokens = 4_000, maxOutputTokens = 8_000)
        val big = ContextBudget.compute(contextWindow = 200_000, systemTokens = 4_000, maxOutputTokens = 8_000)
        assertTrue(big.historyBudget > small.historyBudget)
    }
}
