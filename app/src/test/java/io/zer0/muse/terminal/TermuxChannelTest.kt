package io.zer0.muse.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.1 Termux canal:Análisis de resultados y prueba única defensiva de redacción orientativa.(puro JVM).
 */
class TermuxChannelTest {

    @Test
    fun `Análisis de paquetes de resultados estándar`() {
        val result = TermuxChannel.parseResultEntries(
            mapOf("stdout" to "hello", "stderr" to "", "exitCode" to 0, "err" to 0),
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertEquals("", result.stderr)
        assertNull(result.error)
    }

    @Test
    fun `Campos faltantes seguros y protegidos`() {
        val result = TermuxChannel.parseResultEntries(emptyMap())
        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertNull(result.error)
    }

    @Test
    fun `errmsg Priorizar como error`() {
        val result = TermuxChannel.parseResultEntries(
            mapOf("stdout" to "", "stderr" to "", "exitCode" to 1, "err" to 5, "errmsg" to "allow-external-apps not configured"),
        )
        assertEquals(1, result.exitCode)
        assertEquals("allow-external-apps not configured", result.error)
    }

    @Test
    fun `Termux El código de éxito -1 no es un error`() {
        // Termux Errno.ERRNO_SUCCESS es -1, la implementación anterior lo trataba como un error,
        // Como resultado, el comando informó "Termux Código de error de servicio -1".
        val result = TermuxChannel.parseResultEntries(
            mapOf("stdout" to "hello", "stderr" to "", "exitCode" to 0, "err" to -1, "errmsg" to null),
        )
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.stdout)
        assertNull(result.error)
    }

    @Test
    fun `errCode Código de error de informe distinto de cero y sin error`() {
        val result = TermuxChannel.parseResultEntries(mapOf("err" to 7))
        assertTrue(result.error!!.contains("7"))
    }

    @Test
    fun `Compatible con valores de cadena`() {
        val result = TermuxChannel.parseResultEntries(
            mapOf("exit_code" to "9", "errCode" to "0", "stdout" to "ok"),
        )
        assertEquals(9, result.exitCode)
        assertEquals("ok", result.stdout)
        assertNull(result.error)
    }

    @Test
    fun `Elementos esenciales de arranque no instalados`() {
        val text = TermuxChannel.describe(TermuxChannel.Availability.NotInstalled)
        assertTrue(text.contains("Termux"))
        assertTrue(text.contains("no está instalado"))
    }

    @Test
    fun `Puntos clave de la guía de permisos`() {
        val text = TermuxChannel.describe(TermuxChannel.Availability.PermissionMissing)
        assertTrue(text.contains("Permisos adicionales"))
    }

    @Test
    fun `No se incluye configuración de arranque allow-external-apps`() {
        val text = TermuxChannel.describe(TermuxChannel.Availability.NotConfigured("boom"))
        assertTrue(text.contains("allow-external-apps"))
        assertTrue(text.contains("boom"))
    }
}
