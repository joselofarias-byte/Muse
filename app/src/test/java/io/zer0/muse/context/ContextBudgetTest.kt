package io.zer0.muse.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4.3: Pruebas de presupuesto de contexto unificado — Los límites de partición, el truncamiento que preserva el encabezado y las anotaciones de truncamiento son diagnósticos.
 */
class ContextBudgetTest {

    @Test
    fun `text within limit passes through unchanged`() {
        val budget = ContextBudget()
        val result = budget.clamp(ContextSection.LONG_TERM_MEMORY, "texto corto")
        assertFalse(result.truncated)
        assertEquals("texto corto", result.text)
        assertEquals(3, result.originalChars)
    }

    @Test
    fun `oversized memory section is head-truncated with diagnostic notice`() {
        val budget = ContextBudget()
        val big = "m".repeat(30_000)
        val result = budget.clamp(ContextSection.LONG_TERM_MEMORY, big)
        assertTrue(result.truncated)
        assertEquals(30_000, result.originalChars)
        // mantener el encabezado
        assertTrue(result.text.startsWith("m".repeat(1_000)))
        // La anotación incluye partición, longitud original y límite superior.,Diagnosticable
        assertTrue(result.text.contains("Partición=LONG_TERM_MEMORY"))
        assertTrue(result.text.contains("originales 30000"))
        assertTrue(result.text.contains("límite superior 24000"))
    }

    @Test
    fun `attachment clamping keeps user text intact in buildSendText`() {
        // La entrada del usuario está al final de la secuencia de empalme.,No debe ser truncado ni absorbido por archivos adjuntos.
        val budget = ContextBudget(
            mapOf(ContextSection.ATTACHMENT_TEXT to 100),
        )
        val docs = listOf("d".repeat(200))
        val raw = "Preguntas de usuario"
        // Simular el comportamiento de buildSendText(La función real está en el módulo de la aplicación.,Verifique la semántica del presupuesto aquí)
        val clampedDoc = budget.clampText(ContextSection.ATTACHMENT_TEXT, docs.joinToString("\n\n---\n\n"))
        val send = "$clampedDoc\n\n---\n\n$raw"
        assertTrue(send.endsWith(raw))
        assertTrue(send.contains("El contenido ha sido truncado por el presupuesto contextual."))
    }

    @Test
    fun `unlimited sections without configured limit pass through`() {
        val budget = ContextBudget(emptyMap())
        val big = "x".repeat(500_000)
        val result = budget.clamp(ContextSection.TOOL_SCHEMA, big)
        assertFalse(result.truncated)
        assertEquals(big, result.text)
    }

    @Test
    fun `default limits cover every section`() {
        ContextSection.entries.forEach { section ->
            assertTrue("Partición $section Debería haber un límite superior predeterminado", ContextBudget.DEFAULT_LIMITS.containsKey(section))
        }
    }

    @Test
    fun `vision description clamp bounds injected descriptions`() {
        val budget = ContextBudget()
        val big = "Tuli es".repeat(10_000) // 30_000 personaje
        val result = budget.clamp(ContextSection.VISION_DESCRIPTION, big)
        assertTrue(result.truncated)
        // Revisión de corrección(P2)Regresar:Nota incluida en el presupuesto,La longitud total de los resultados no debe exceder el límite de configuración.
        assertTrue(result.text.length <= 12_000)
    }

    @Test
    fun `truncated result never exceeds the configured limit`() {
        val budget = ContextBudget(mapOf(ContextSection.RELEVANT_MEMORY to 500))
        val result = budget.clamp(ContextSection.RELEVANT_MEMORY, "x".repeat(10_000))
        assertTrue(result.truncated)
        assertTrue("longitud real ${result.text.length}", result.text.length <= 500)
        assertTrue(result.text.contains("El contenido ha sido truncado por el presupuesto contextual."))
    }
}
