package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F12: El estado de pausa del tiempo de espera de aprobación debe restablecerse al salir de la combinación.
 *
 * La implementación original solo usa LaunchedEffect(approvals.size, approvalsExpanded) conducir:
 * Al salir de una sesión pause=true,Al regresar, si la clave se restaura a la misma combinación,effect No repetir carrera,
 * VM El tiempo de espera de aprobación en pausa permanece true —— La aprobación de una nueva sesión se suspendió permanentemente de forma incorrecta,
 * Ya no activa el rechazo automático de 30 segundos.
 */
class ApprovalTimeoutPauseResetTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/ChatScreen.kt").readText()

    @Test
    fun `disposable effect resets pause on leaving composition`() {
        val src = source()
        val start = src.indexOf("AUD-UI-F12")
        assertTrue("Bloque de protección AUD-UI-F12 no encontrado", start >= 0)
        val body = src.substring(start, (start + 900).coerceAtMost(src.length))
        assertTrue(
            "Debe usar DesechableEffect para restablecer al salir",
            body.contains("DisposableEffect(Unit)"),
        )
        assertTrue(
            "onDispose El estado de pausa debe restablecerse",
            body.contains("onDispose { viewModel.setApprovalTimeoutPaused(false) }"),
        )
    }
}
