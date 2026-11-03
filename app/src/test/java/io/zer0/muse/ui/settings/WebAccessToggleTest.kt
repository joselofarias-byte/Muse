package io.zer0.muse.ui.settings

import io.zer0.muse.web.WebServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secuencia de efectos secundarios del interruptor "Habilitar versión web".
 *
 * El cambio de página sólo se puede ajustar [applyWebAccessEnabled], entonces lo que está bloqueado aquí es lo que sucede cuando el usuario presiona el interruptor:
 * Se debe escribir la configuración y el servicio se puede iniciar o detener según demanda, y cada cosa ocurre solo una vez.
 */
class WebAccessToggleTest {

    /** Registre la doble llamada del servicio. */
    private class RecordingHost(private val startResult: Boolean = true) : WebAccessHost {
        val saved = mutableListOf<WebServerConfig>()
        var startCalls = 0
        var stopCalls = 0
        var startAttemptsAfterSave = 0

        override suspend fun saveConfig(config: WebServerConfig) {
            saved += config
        }

        override suspend fun startServer(): Boolean {
            startCalls++
            startAttemptsAfterSave = saved.size
            return startResult
        }

        override suspend fun stopServer() {
            stopCalls++
        }
    }

    private val disabled = WebServerConfig(enabled = false, port = 8765, pin = "123456")

    @Test
    fun `enabling writes config then starts the server once`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled, enabled = true, running = false)

        assertTrue(running)
        assertEquals(1, host.saved.size)
        assertTrue("habilitado en la configuración debe estar instalado", host.saved.single().enabled)
        assertEquals("El puerto y el PIN no se pueden cambiar mediante el conmutador", "123456", host.saved.single().pin)
        assertEquals(1, host.startCalls)
        assertEquals(0, host.stopCalls)
        assertEquals("El inicio debe ocurrir después de escribir la configuración.", 1, host.startAttemptsAfterSave)
    }

    /** Encienda el interruptor nuevamente cuando el servicio ya se esté ejecutando y no lo inicie nuevamente. */
    @Test
    fun `enabling while running does not start again`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled.copy(enabled = true), enabled = true, running = true)

        assertTrue(running)
        assertEquals("No se debe iniciar repetidamente cuando ya está funcionando.", 0, host.startCalls)
        assertEquals(0, host.stopCalls)
    }

    @Test
    fun `disabling stops the server once and clears enabled`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled.copy(enabled = true), enabled = false, running = true)

        assertFalse(running)
        assertFalse("habilitado en la configuración debe colocarse como false", host.saved.single().enabled)
        assertEquals(1, host.stopCalls)
        assertEquals(0, host.startCalls)
    }

    /** El interruptor no se apaga durante la ejecución: la configuración es tal como está escrita, pero no se realiza ninguna parada sin sentido. */
    @Test
    fun `disabling while stopped skips the stop call`() = runTest {
        val host = RecordingHost()

        val running = applyWebAccessEnabled(host, disabled, enabled = false, running = false)

        assertFalse(running)
        assertEquals(1, host.saved.size)
        assertEquals(0, host.stopCalls)
    }

    /** Cuando el inicio falla, devuelva falso a la página y en la página aparecerá \"Error de inicio\" en consecuencia. */
    @Test
    fun `start failure is reported back to the caller`() = runTest {
        val host = RecordingHost(startResult = false)

        val running = applyWebAccessEnabled(host, disabled, enabled = true, running = false)

        assertFalse(running)
        assertEquals(1, host.startCalls)
    }
}
