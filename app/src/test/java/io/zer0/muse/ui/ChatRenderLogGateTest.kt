package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deduplicación de registros de diagnóstico de "mensajes no renderizados".
 *
 * Durante la salida de streaming, la lista visible se recalcula varias veces por segundo.,El registro repetido con el mismo valor convertirá 6 horas de registros de depuración en ruido.;
 * Pero este registro en sí es la única entrada para comprobar "por qué no se muestra un determinado mensaje".,Por lo tanto, sólo se eliminan los duplicados, no se eliminan.
 */
class ChatRenderLogGateTest {

    @Test
    fun `same hidden and total only logs once`() {
        val gate = ChatRenderLogGate()
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
        assertFalse(gate.shouldLog(hidden = 11, total = 87))
        assertFalse(gate.shouldLog(hidden = 11, total = 87))
    }

    @Test
    fun `changed hidden or total logs again`() {
        val gate = ChatRenderLogGate()
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
        assertTrue(gate.shouldLog(hidden = 12, total = 88))
        assertTrue(gate.shouldLog(hidden = 12, total = 89))
        assertFalse(gate.shouldLog(hidden = 12, total = 89))
    }

    @Test
    fun `no hidden messages never logs and forgets the last value`() {
        val gate = ChatRenderLogGate()
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
        assertFalse(gate.shouldLog(hidden = 0, total = 87))
        // Volver a cero en el medio,El mismo par de números reaparece en un nuevo evento de cálculo.
        assertTrue(gate.shouldLog(hidden = 11, total = 87))
    }
}
