package io.zer0.muse.session

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.x Importar calentamiento:Prueba única de recortes presupuestarios y sesiones informativas.
 */
class WarmupHistoryTest {
    private fun msg(i: Int) = UIMessage(role = MessageRole.USER, content = "m$i")

    @Test
    fun `Mantenga todo dentro del presupuesto`() {
        val history = (1..5).map { msg(it) }
        val result = WarmupHistory.trimToBudget(history, budgetTokens = 1000) { 100 }
        assertFalse(result.truncated)
        assertEquals(5, result.history.size)
        assertEquals("m1", result.history.first().content)
    }

    @Test
    fun `Por encima del presupuesto, truncar desde el más antiguo y mantener el más nuevo`() {
        val history = (1..10).map { msg(it) }
        val result = WarmupHistory.trimToBudget(history, budgetTokens = 350) { 100 }
        assertTrue(result.truncated)
        assertEquals(3, result.history.size)
        assertEquals("m8", result.history.first().content)
        assertEquals("m10", result.history.last().content)
    }

    @Test
    fun `Si una sola partida excede el presupuesto, se conservará al menos la última partida.`() {
        val history = (1..3).map { msg(it) }
        val result = WarmupHistory.trimToBudget(history, budgetTokens = 50) { 100 }
        assertTrue(result.truncated)
        assertEquals(1, result.history.size)
        assertEquals("m3", result.history.first().content)
    }

    @Test
    fun `La historia vacía no se trunca`() {
        val result = WarmupHistory.trimToBudget(emptyList(), budgetTokens = 100) { 100 }
        assertFalse(result.truncated)
        assertTrue(result.history.isEmpty())
    }

    @Test
    fun `Cálculo de presupuesto y mochilero.`() {
        assertEquals(4800, WarmupHistory.budgetTokensFor(8000))
        assertEquals(WarmupHistory.FALLBACK_BUDGET_TOKENS, WarmupHistory.budgetTokensFor(0))
        assertEquals(WarmupHistory.FALLBACK_BUDGET_TOKENS, WarmupHistory.budgetTokensFor(-1))
    }

    @Test
    fun `La sesión informativa es un mensaje del sistema y no se puede reclamar.`() {
        val briefing = WarmupHistory.briefingMessage(truncated = true)
        assertEquals(MessageRole.SYSTEM, briefing.role)
        assertTrue(briefing.content.contains("importar"))
        assertTrue(briefing.content.contains("Lo último"))
        assertTrue(briefing.content.contains("mantener parte reciente"))

        val full = WarmupHistory.briefingMessage(truncated = false)
        assertFalse(full.content.contains("mantener parte reciente"))
    }

    @Test
    fun `El presupuesto de caracteres de compresión es el mismo que el presupuesto de token de calentamiento.`() {
        // El presupuesto de compresión y el presupuesto de calentamiento ya no tienen el mismo valor: la compresión también deduce la reserva de salida y el margen de seguridad.
        // De lo contrario, el modelo llenará la ventana después de escribir una respuesta larga. El presupuesto de calentamiento sólo es responsable de "cuánto historial cargar".
        assertTrue(
            "El presupuesto de compresión debe ser más ajustado que el presupuesto de calentamiento.",
            WarmupHistory.compressCharBudgetFor(8_000) < WarmupHistory.budgetTokensFor(8_000),
        )
        // 8000 - 4096(Límite inferior de reserva de salida) - 1024(Límite inferior del margen de seguridad) = 2880
        assertEquals(2_880, WarmupHistory.compressCharBudgetFor(8_000))
        assertEquals(WarmupHistory.FALLBACK_BUDGET_TOKENS, WarmupHistory.compressCharBudgetFor(0))
        assertEquals(WarmupHistory.FALLBACK_BUDGET_TOKENS, WarmupHistory.compressCharBudgetFor(-5))
    }
}
