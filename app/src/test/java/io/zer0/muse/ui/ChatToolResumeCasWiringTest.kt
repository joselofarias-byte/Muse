package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F2: La herramienta de recuperación del punto de interrupción llama al mensaje anexado y debe ir CAS accessor,no puede estar en lo cierto
 * `messages.value` asignación global —— De lo contrario, escriba con limitación de transmisión(updateAssistant)Cuando está intercalado
 * se sobrescribirá con la instantánea anterior,El problema es que los mensajes de HERRAMIENTA desaparecen ocasionalmente después de la recuperación.
 *
 * Isomórfico a las pruebas de cableado existentes en el proyecto.:Afirmar el método de cableado en el código fuente.(La competición no se puede reproducir de forma estable aquí.,
 * Pero una vez devuelto el cableado, se asigna directamente.,Esta prueba falla).
 */
class ChatToolResumeCasWiringTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/chat/ChatToolResumeCoordinator.kt").readText()

    @Test
    fun `resume appends tool result message through CAS accessor`() {
        val src = source()
        assertTrue(
            "TOOL Los mensajes resultantes se deben adjuntar a través de stateAccessor.updateMessages",
            src.contains("host.stateAccessor.updateMessages { messages -> messages + toolMsg }"),
        )
    }

    @Test
    fun `resume appends assistant placeholder through CAS accessor`() {
        val src = source()
        assertTrue(
            "ASSISTANT Los mensajes de marcador de posición se deben agregar a través de stateAccessor.updateMessages",
            src.contains("host.stateAccessor.updateMessages { messages -> messages + assistantMsg }"),
        )
    }

    @Test
    fun `resume path no longer overwrites messages state directly`() {
        val src = source()
        assertTrue(
            "La ruta de recuperación ya no debería aparecer messages.value = cobertura general",
            !src.contains("host.stateStore.messages.value = host.stateStore.messages.value + toolMsg") &&
                !src.contains("host.stateStore.messages.value = host.stateStore.messages.value + assistantMsg"),
        )
    }
}
