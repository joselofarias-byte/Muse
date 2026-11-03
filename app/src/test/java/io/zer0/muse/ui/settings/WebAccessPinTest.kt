package io.zer0.muse.ui.settings

import io.zer0.muse.web.WebServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * La forma y el comportamiento de "regeneración" de los PIN basados ​​en la web.
 *
 * PIN Es la credencial para el primer acceso al lado Web. La página de configuración lo muestra al usuario en texto plano, por lo que debe fijarse en 6 números puros;
 * "Regenerar" realmente cambia el valor en el sentido de que el antiguo PIN deja de ser válido inmediatamente. —— Aquí se utiliza una semilla fija para inyectar la fuente aleatoria,
 * Evite colisiones de probabilidad extremadamente baja que se basan en una verdadera aleatoriedad.
 */
class WebAccessPinTest {

    private val pinRegex = Regex("^[1-9]\\d{5}$")

    @Test
    fun `pin is six digits without leading zero`() {
        val random = Random(20261030L)
        repeat(50) {
            val pin = WebServerConfig.generateRandomPin(random)
            assertEquals(WebServerConfig.PIN_LENGTH, pin.length)
            assertTrue("PIN La forma no coincide: $pin", pinRegex.matches(pin))
        }
    }

    @Test
    fun `regenerating twice with the same source yields a different pin`() {
        val random = Random(42L)
        val first = WebServerConfig.generateRandomPin(random)
        val second = WebServerConfig.generateRandomPin(random)
        assertNotEquals("Dos compilaciones consecutivas con una semilla fija no deberían ser iguales", first, second)
        assertTrue(second, pinRegex.matches(second))
    }

    /** De forma predeterminada, SecureRandom se llama sin parámetros y su forma es consistente con la versión de inyección de semillas. */
    @Test
    fun `default source also produces six digits`() {
        val pin = WebServerConfig.generateRandomPin()
        assertTrue("PIN La forma no coincide: $pin", pinRegex.matches(pin))
    }
}
