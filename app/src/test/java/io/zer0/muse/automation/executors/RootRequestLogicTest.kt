package io.zer0.muse.automation.executors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root Solicitud de autorización prueba de lógica pura —— No se inicia ningún proceso hijo y no se ejecuta su.
 *
 * Portada:estructura de mando,`uid=0` Sentencia, código de salida/Salida al mapeo de motivos de falla, tiempo de espera y mapeo de excepciones.
 * root La ventana emergente del administrador no se puede reproducir en el entorno de prueba.,La orquestación del proceso es proporcionada por [RootExecutor.requestRootAccess] responsable;
 * Lo que está bloqueado aquí es la semántica resultante de las dependencias de la interfaz de usuario.
 */
class RootRequestLogicTest {

    // ── Construcción de comando ─────────────────────────────────────────────────────────

    @Test
    fun `probe args reuse su prefix with fixed id command`() {
        assertEquals(listOf("su", "-c", "id"), rootProbeArgs(listOf("su")))
    }

    @Test
    fun `probe args keep whatever prefix the executor declares`() {
        assertEquals(listOf("su", "--mount-master", "-c", "id"), rootProbeArgs(listOf("su", "--mount-master")))
    }

    // ── Juicio de salida ─────────────────────────────────────────────────────────

    @Test
    fun `uid 0 output counts as granted`() {
        assertTrue(isRootGranted("uid=0(root) gid=0(root) groups=0(root)"))
        assertTrue(isRootGranted("uid=0(root) gid=0(root) context=u:r:su:s0"))
    }

    @Test
    fun `non root uid does not count as granted`() {
        assertFalse(isRootGranted("uid=2000(shell) gid=2000(shell)"))
        assertFalse(isRootGranted(""))
        assertFalse(isRootGranted(null))
    }

    // ── Sentencia estatal final ─────────────────────────────────────────────────────────

    @Test
    fun `exit code 0 with uid 0 is granted`() {
        val result = evaluateRootProbe(0, "uid=0(root) gid=0(root)")

        assertTrue(result.granted)
        assertNull(result.failure)
    }

    @Test
    fun `exit code 0 with shell uid is denied`() {
        val result = evaluateRootProbe(0, "uid=2000(shell) gid=2000(shell)")

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.DENIED, result.failure)
    }

    @Test
    fun `nonzero exit code is denied even when output mentions uid 0`() {
        val result = evaluateRootProbe(1, "uid=0(root) gid=0(root)")

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.DENIED, result.failure)
    }

    @Test
    fun `missing su binary output maps to no su failure`() {
        val result = evaluateRootProbe(127, "/system/bin/sh: su: not found")

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.NO_SU_BINARY, result.failure)
    }

    // ── Asignación de tiempo de espera/excepción ──────────────────────────────────────────────────

    @Test
    fun `timeout maps to timeout failure`() {
        val result = rootProbeTimeout()

        assertFalse(result.granted)
        assertEquals(RootRequestFailure.TIMEOUT, result.failure)
    }

    @Test
    fun `security exception maps to denied`() {
        assertEquals(
            RootRequestFailure.DENIED,
            classifyRootProbeException(SecurityException("permission denied")),
        )
    }

    @Test
    fun `other exceptions map to error`() {
        assertEquals(RootRequestFailure.ERROR, classifyRootProbeException(java.io.IOException("broken pipe")))
        assertEquals(RootRequestFailure.ERROR, classifyRootProbeException(IllegalStateException("no process")))
    }

    @Test
    fun `failed result always carries a readable reason`() {
        RootRequestFailure.entries.forEach { failure ->
            val result = RootRequestResult.failed(failure)

            assertFalse(result.granted)
            assertEquals(failure, result.failure)
        }
    }
}
