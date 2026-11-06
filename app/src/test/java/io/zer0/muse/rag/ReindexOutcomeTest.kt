package io.zer0.muse.rag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Determinación del estado final de la reindexación global —— La línea de defensa de retorno para los artículos de revisión.
 *
 * fondo:anteriormente [ReindexAllWorker] Independientemente del éxito o el fracaso, simplemente escriba el registro y regrese Result.success(),
 * Después de que el usuario hace clic en "Reindexar todo" y la notificación de progreso desaparece, no puede ver ningún resultado.,Todos los documentos fallan también con
 * Todos los éxitos son indistinguibles. El estado final ahora determina el texto y la prioridad de la notificación del resultado.,Las reglas de decisión están clavadas aquí.
 */
class ReindexOutcomeTest {

    @Test
    fun `all documents succeeded is success`() {
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 5, total = 5))
    }

    @Test
    fun `partial failure is partial not success`() {
        assertEquals(ReindexOutcome.PARTIAL, reindexOutcomeOf(successCount = 4, total = 5))
        assertEquals(ReindexOutcome.PARTIAL, reindexOutcomeOf(successCount = 1, total = 9))
    }

    @Test
    fun `every document failed is failed not success`() {
        assertEquals(ReindexOutcome.FAILED, reindexOutcomeOf(successCount = 0, total = 3))
    }

    @Test
    fun `nothing to do counts as success`() {
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 0, total = 0))
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 0, total = -1))
    }

    @Test
    fun `unexpected extra successes do not downgrade to partial`() {
        assertEquals(ReindexOutcome.SUCCESS, reindexOutcomeOf(successCount = 6, total = 5))
    }

    @Test
    fun `no count combination collapses failed into success`() {
        // Agotando un pequeño abanico de combinaciones:falló(0 Exitoso y documentado)No se debe juzgar igual que "todo éxito"
        for (total in 1..12) {
            for (success in 0..total) {
                val outcome = reindexOutcomeOf(success, total)
                if (success == 0) {
                    assertEquals("total=$total success=0 debe fallar", ReindexOutcome.FAILED, outcome)
                } else if (success < total) {
                    assertEquals("total=$total success=$success Debe ser juzgado como fracaso parcial.", ReindexOutcome.PARTIAL, outcome)
                } else {
                    assertEquals(ReindexOutcome.SUCCESS, outcome)
                }
            }
        }
    }
}
