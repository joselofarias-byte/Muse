package io.zer0.memory.ticker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resumen de desplazamiento de memoria con"Compilado hoy"Prueba de intervalo de disparo.
 *
 * Antecedentes: los usuarios informaron que actualizar el resumen de desplazamiento cada 10 rondas era demasiado tarde (es fácil pasar por alto lo que acaba de suceder), por lo que se cambió a cada 5 rondas;
 * pero"Compilación de la memoria de hoy + asamblea."Las escrituras son significativamente más intensas con llamadas de modelos grandes, con 10 rondas a la vez.
 *
 * Aquí hay dos cosas bloqueadas:① Los dos intervalos surten efecto por separado;② **El intervalo de resumen divide el intervalo de compilación** ——
 * De lo contrario, la compilación se ejecutará antes del resumen y el resumen anterior de la ronda anterior se utilizará para compilar la memoria de hoy.
 */
class MemorySummaryIntervalTest {

    @Test
    fun `rolling summary fires every five turns`() {
        val expected = setOf(5, 10, 15, 20, 25)
        (1..25).forEach { turn ->
            val should = turn in expected
            assertTrue("No. $turn El juicio sumario de la ronda debe ser $should", shouldRollSummary(turn) == should)
        }
    }

    @Test
    fun `compile today stays at every ten turns`() {
        val expected = setOf(10, 20, 30)
        (1..30).forEach { turn ->
            val should = turn in expected
            assertTrue("No. $turn La decisión de compilación de la ronda debe ser $should", shouldCompileToday(turn) == should)
        }
    }

    @Test
    fun `compile never runs without a fresh summary in the same turn`() {
        (1..120).forEach { turn ->
            if (shouldCompileToday(turn)) {
                assertTrue(
                    "La compilación debe ocurrir simultáneamente con la digestión (sección $turn rueda), de lo contrario se utilizará el resumen anterior para compilar la memoria de hoy",
                    shouldRollSummary(turn),
                )
            }
        }
    }

    @Test
    fun `zero and negative turn counts never trigger`() {
        listOf(0, -1, -5).forEach { turn ->
            assertFalse("No. $turn la ronda no debería activar el resumen", shouldRollSummary(turn))
            assertFalse("No. $turn la ronda no debería activar la compilación", shouldCompileToday(turn))
        }
    }

    @Test
    fun `summary interval divides the compile interval`() {
        val summary = MemoryTicker.TURNS_PER_SUMMARY
        val compile = MemoryTicker.TURNS_PER_COMPILE
        assertTrue("intervalo de resumen($summary)Debe dividir el intervalo de compilación($compile)", compile % summary == 0)
        assertTrue("El resumen debería ser más frecuente que la compilación.", summary < compile)
    }
}
