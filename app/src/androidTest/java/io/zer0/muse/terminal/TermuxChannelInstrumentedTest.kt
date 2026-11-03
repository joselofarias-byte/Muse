package io.zer0.muse.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v2.2.1 Termux canal:Verificación de extremo a extremo del lado del dispositivo(El dispositivo requiere que Termux esté instalado y que se haya completado la configuración de permitir aplicaciones externas).
 *
 * Portada:Detección(Instalación/Permisos) → RUN_COMMAND ejecutar → Devolución de resultados(stdout/exitCode)Enlace completo.
 */
@RunWith(AndroidJUnit4::class)
class TermuxChannelInstrumentedTest {

    @Test
    fun termuxEchoRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val channel = TermuxChannel.get(context)

        assertTrue("Termux No instalado(Falta el prefijo de prueba)", channel.isInstalled())
        assertTrue("RUN_COMMAND Permiso no concedido(Falta el prefijo de prueba)", channel.hasPermission())

        val result = runBlocking {
            channel.exec("echo MUSE_TERMUX_OK_$((6 * 7))", timeoutMs = 30_000L)
        }
        // instrumentation El proceso siempre se considera en segundo plano.,Las restricciones de inicio del servicio en segundo plano bloquearán este caso de uso;defectos no canalizados,Omita en lugar de informar un error.
        org.junit.Assume.assumeTrue(
            "El dispositivo está en segundo plano e inicia un entorno restringido.,saltar: ${result.error}",
            result.error == null || !result.error.contains("Not allowed to start service"),
        )
        assertNull("error de nivel de canal: ${result.error}", result.error)
        assertTrue("stdout debe contener salida etiquetada: [${result.stdout}]", result.stdout.contains("MUSE_TERMUX_OK_42"))
        assertEquals("El código de salida debe ser 0", 0, result.exitCode)
    }

    @Test
    fun termuxStderrAndExitCodePropagated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val channel = TermuxChannel.get(context)
        if (!channel.isInstalled() || !channel.hasPermission()) return

        val result = runBlocking {
            channel.exec("echo MUSE_ERR_PROBE 1>&2; exit 3", timeoutMs = 30_000L)
        }
        org.junit.Assume.assumeTrue(
            "El dispositivo está en segundo plano e inicia un entorno restringido.,saltar: ${result.error}",
            result.error == null || !result.error.contains("Not allowed to start service"),
        )
        assertNull("error de nivel de canal: ${result.error}", result.error)
        assertEquals("El código de salida debe ser 3", 3, result.exitCode)
        assertTrue("stderr Se debe incluir el texto de la sonda.: [${result.stderr}]", result.stderr.contains("MUSE_ERR_PROBE"))
    }
}
