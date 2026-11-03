package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F4: switchAgentAssistant Un interruptor de recuento de referencias de sesión no debe liberar accidentalmente una sesión que todavía esté en uso.
 *
 * La implementación original ahora está dentro de la corrutina. release(`_state.value.agentSessionId`)adquirir de nuevo. rapido A→B
 * Al cambiar continuamente,B Es posible que el valor anterior leído por la ruta ya sea la nueva sesión A recién adquirida,release Reducirá más el recuento de referencias.,
 * 5s idle reaper Luego borre los recursos de la sesión que todavía están en uso.
 *
 * Reparación:Capture primero el valor anterior → Primero adquiera una nueva sesión → Luego libere el valor anterior de la instantánea.(Y excluir lanzamientos repetidos con el mismo ID.).
 */
class SwitchAgentAssistantRefcountTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/ChatViewModel.kt").readText()

    @Test
    fun `refcount switch snapshots previous session before release`() {
        val src = source()
        val start = src.indexOf("AUD-UI-F4")
        assertTrue("Bloque de protección AUD-UI-F4 no encontrado", start >= 0)
        val body = src.substring(start, (start + 800).coerceAtMost(src.length))
        assertTrue(
            "Primero se deben tomar instantáneas de las sesiones antiguas. id",
            body.contains("val previousAgentSessionId = _state.value.agentSessionId"),
        )
        assertTrue(
            "Primero se debe adquirir una nueva sesión y luego liberar la sesión anterior.(Aumentar primero y luego disminuir.,no será negativo)",
            body.indexOf("sessionManager.acquire(sessionId)") <
                body.indexOf("sessionManager.release(previousAgentSessionId)"),
        )
    }

    @Test
    fun `release skips identical session id`() {
        val src = source()
        val start = src.indexOf("AUD-UI-F4")
        assertTrue("Bloque de protección AUD-UI-F4 no encontrado", start >= 0)
        val body = src.substring(start, (start + 800).coerceAtMost(src.length))
        assertTrue(
            "La misma identificación no puede adquirir y al mismo tiempo release",
            body.contains("previousAgentSessionId != sessionId"),
        )
    }
}
