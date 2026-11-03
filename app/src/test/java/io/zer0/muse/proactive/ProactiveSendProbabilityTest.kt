package io.zer0.muse.proactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enviar puerta de probabilidad.
 *
 * La probabilidad es un número que los usuarios pueden ver en la página de configuración, por lo que se deben conservar ambos extremos:0 nunca enviar,100 Con o sin esta puerta
 * Totalmente consistente. El valor intermedio valida ambas ramas con el resultado del dado inyectado. —— Para afirmar que "si corres cien veces, te despedirán aproximadamente cincuenta veces".
 * Sólo obtendrás una prueba fallida ocasionalmente, y lo que realmente quieres asegurar es "cómo se asigna el resultado de la tirada de dados a si se dispara o no".
 */
class ProactiveSendProbabilityTest {

    @Test
    fun `Nunca envíe cuando la probabilidad es 0`() {
        (0 until ProactiveSendProbability.ROLL_BOUND).forEach { roll ->
            assertFalse("roll=$roll", ProactiveSendProbability.allows(0, roll))
        }
    }

    @Test
    fun `Todos se emiten cuando la probabilidad es 100`() {
        (0 until ProactiveSendProbability.ROLL_BOUND).forEach { roll ->
            assertTrue("roll=$roll", ProactiveSendProbability.allows(100, roll))
        }
    }

    @Test
    fun `Cuando la probabilidad es 50, el resultado del dado se divide en dos puntos`() {
        assertTrue(ProactiveSendProbability.allows(50, 0))
        assertTrue(ProactiveSendProbability.allows(50, 49))
        assertFalse(ProactiveSendProbability.allows(50, 50))
        assertFalse(ProactiveSendProbability.allows(50, 99))
    }

    @Test
    fun `Los valores sucios se manejan sobre una base límite`() {
        assertTrue(ProactiveSendProbability.allows(150, 99))
        assertFalse(ProactiveSendProbability.allows(-10, 0))
        assertTrue(ProactiveSendProbability.allows(1, 0))
        assertFalse(ProactiveSendProbability.allows(1, 1))
    }

    @Test
    fun `La inspección realmente lee la probabilidad en la configuración antes de generar`() {
        // Después de probar las funciones puras, todavía hay que estar atento al cableado: la probabilidad solía ser uno de los campos de que "la interfaz escribe, pero el planificador no lee".
        // Haga afirmaciones a nivel de código fuente aquí para evitar que esta puerta quede en la función pero no se inspeccione en el futuro.
        val source = source("ProactiveCycleRunner.kt")
        assertTrue(
            "La inspección debe utilizar sendProbability en la configuración para rodar el dado.",
            source.contains("ProactiveSendProbability.allows(ready.config.sendProbability"),
        )
        assertTrue("La fuente de aleatoriedad debe ser inyectable.", source.contains("rollPercent"))
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/proactive/$name"),
            File("app/src/main/java/io/zer0/muse/proactive/$name"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("no encontrado $name")
        return file.readText()
    }
}
