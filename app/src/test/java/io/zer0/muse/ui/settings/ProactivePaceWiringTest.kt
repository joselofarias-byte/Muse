package io.zer0.muse.ui.settings

import io.zer0.muse.data.proactive.ProactivePace
import io.zer0.muse.data.proactive.ProactiveTimingEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cableado para el engranaje "Nivel Activo".
 *
 * El equipo fue escrito antes. `aiPaced` Todo el campo es ilegible, lo que equivale a un interruptor que solo está activo en la interfaz;
 * Ahora el archivo solo escribe los dos campos "intervalo + probabilidad" que realmente lee la capa de juicio.
 * Aquí hacemos afirmaciones a nivel de código fuente en lugar de realizar pruebas: a lo que debemos prestar atención es a "si la interfaz está escrita por el programador y se puede leer".
 * El aspecto que tenga cuando se renderice no afecta esta conclusión.
 */
class ProactivePaceWiringTest {

    @Test
    fun `El aiPaced obsoleto ya no aparece en la página de configuración`() {
        assertFalse(
            "La página de configuración ya no debería leer ni escribir aiPaced (este campo está obsoleto y no tiene implementación)",
            source().contains("aiPaced"),
        )
    }

    @Test
    fun `El intervalo y la probabilidad escritos por la selección de archivos son los campos realmente leídos por la capa de decisión.`() {
        val text = source()
        assertTrue(text.contains("intervalMinutes = pace.intervalMinutes"))
        assertTrue(text.contains("sendProbability = pace.sendProbability"))
    }

    @Test
    fun `Los intervalos entre las cuatro marchas están todos dentro del rango permitido.`() {
        ProactivePace.values().forEach { pace ->
            assertTrue(
                "${pace.name}=${pace.intervalMinutes} Los minutos exceden el intervalo de juicio.",
                pace.intervalMinutes in
                    ProactiveTimingEngine.MIN_INTERVAL_MINUTES..ProactiveTimingEngine.MAX_INTERVAL_MINUTES,
            )
            assertTrue("${pace.name} La probabilidad debe ser 0..100", pace.sendProbability in 0..100)
        }
    }

    private fun source(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/ui/settings/AgentSettingsPage.kt"),
            File("app/src/main/java/io/zer0/muse/ui/settings/AgentSettingsPage.kt"),
        )
        val file = candidates.firstOrNull { it.exists() } ?: error("No puedo encontrar el código fuente de la página de configuración")
        return file.readText()
    }
}
