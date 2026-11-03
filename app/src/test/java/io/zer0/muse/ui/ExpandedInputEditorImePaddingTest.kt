package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F14: La página de edición de entrada de pantalla completa debe tener imePadding,De lo contrario, el teclado cubre el cuadro de entrada./Botón enviar.
 *
 * Esta página está compuesta directamente dentro de la barra de entrada.(Ventana independiente sin diálogo),MainActivity encender edge-to-edge
 * La inserción del teclado post-soft no subirá automáticamente el contenido —— systemBarsPadding por sí solo no es suficiente.
 */
class ExpandedInputEditorImePaddingTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/MuseExpandedInputEditor.kt").readText()

    @Test
    fun `expanded editor root applies ime padding`() {
        val src = source()
        assertTrue(
            "Se debe aplicar el diseño de raíz. imePadding",
            src.contains(".imePadding()"),
        )
        assertTrue(
            "imePadding Necesidad de importar",
            src.contains("import androidx.compose.foundation.layout.imePadding"),
        )
    }
}
