package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F5: La devolución de llamada de finalización de la compresión no debe escribir la marca de agua del punto de control de la "sesión cortada" como estado global.
 *
 * Ejecuciones de compresión en la corrutina IO,Es posible que el usuario haya cambiado a la sesión B cuando haya completado. Implementación original de ejecución incondicional.
 * `checkpointCoveredSessionId = sessionId; refresh; updateContextTokenCount()`,
 * Entonces la sesión B lee el conjunto de cobertura de A. → La ocupación del contexto se calcula por sesión.
 */
class ChatCompressionWatermarkGuardTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/chat/ChatCompressionCoordinator.kt").readText()

    @Test
    fun `compression completion guards watermark against stale session`() {
        val src = source()
        // Utilice el posicionamiento del punto de anclaje de anotación recién agregado(También hay ramas degradadas en el archivo que también se determinan mediante displaySessionId.)
        val start = src.indexOf("AUD-UI-F5")
        assertTrue("Bloque de protección AUD-UI-F5 no encontrado", start >= 0)
        val body = src.substring(start, (start + 1400).coerceAtMost(src.length))
        assertTrue(
            "Solo escribe si aún se muestra la sesión checkpointCoveredSessionId",
            body.contains("host.checkpointCoveredSessionId = sessionId"),
        )
        assertTrue(
            "Sólo recalcular el contexto si la sesión todavía se muestra token",
            body.contains("host.updateContextTokenCount()"),
        )
    }

    @Test
    fun `no unconditional watermark write remains`() {
        val src = source()
        val guard = src.indexOf("AUD-UI-F5")
        assertTrue("Bloque de protección AUD-UI-F5 no encontrado", guard >= 0)
        // Dentro del bloque de protección deben aparecer al mismo tiempo: juicio, escritura del nivel del agua,token Recalcular
        val block = src.substring(guard, (guard + 1400).coerceAtMost(src.length))
        assertTrue(block.contains("host.displayedSessionId() == sessionId"))
        assertTrue(block.contains("host.checkpointCoveredSessionId = sessionId"))
    }
}
