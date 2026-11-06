package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F3: Actualizar sesión desde la base de datos en el frontend(refreshDisplayedSessionFromDatabase)
 * Debe utilizar SessionSwitchGuard para verificar si esta carga ha sido reemplazada por un conmutador actualizado antes de escribir mensajes.,
 * y escrito a través del descriptor de acceso CAS.
 *
 * fondo(REPORT_UI.md F3):Esta función sólo comprueba una vez antes de escribir. displayedSessionId,hay una sesion
 * ventana de carrera —— Los resultados de carga de la sesión anterior pueden sobrescribirse en la interfaz de la nueva sesión.
 */
class ChatSessionRefreshGuardWiringTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/ChatViewModel.kt").readText()

    @Test
    fun `refresh path begins a switch token before async load`() {
        val src = source()
        val start = src.indexOf("private fun refreshDisplayedSessionFromDatabase()")
        assertTrue("no encontrado refreshDisplayedSessionFromDatabase", start >= 0)
        val body = src.substring(start, start + 2600)
        assertTrue(
            "Se debe obtener el cambio antes de actualizar token",
            body.contains("switchGuard.begin()"),
        )
    }

    @Test
    fun `refresh path drops stale results before writing messages`() {
        val src = source()
        val start = src.indexOf("private fun refreshDisplayedSessionFromDatabase()")
        assertTrue("no encontrado refreshDisplayedSessionFromDatabase", start >= 0)
        val body = src.substring(start, start + 2600)
        val staleChecks = body.split("switchGuard.isStale(").size - 1
        assertTrue(
            "Debe haber una verificación de vencimiento antes de escribir mensajes y antes de enviarlos.(Medición real $staleChecks en)",
            staleChecks >= 2,
        )
    }

    @Test
    fun `refresh path writes messages through CAS accessor`() {
        val src = source()
        val start = src.indexOf("private fun refreshDisplayedSessionFromDatabase()")
        assertTrue("no encontrado refreshDisplayedSessionFromDatabase", start >= 0)
        val body = src.substring(start, start + 2600)
        // ChatViewModel se dio cuenta de sí mismo ChatStateAccessor,Se puede llamar directamente internamente updateMessages
        assertTrue(
            "Las escrituras de actualización deben ir updateMessages(CAS transformar),No se puede asignar en su totalidad",
            body.contains("updateMessages { messages }"),
        )
        assertTrue(
            "_messages.value ya no debería asignarse en su totalidad",
            !body.contains("_messages.value = messages"),
        )
    }
}
