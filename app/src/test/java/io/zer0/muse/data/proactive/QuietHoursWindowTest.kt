package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de regresión basadas en comentarios de los usuarios de que "después de obtener la capacidad de controlar activamente el ritmo, no se enviaron mensajes activos".
 *
 * Cubriendo cuatro áreas:
 *  1. No molestar periodo ↔ Permitir el mapeo de ventanas de ida y vuelta (guardar y luego abrir, el inicio y el final no se intercambian)
 *  2. Diagnósticos que permiten que la ventanilla esté vacía/coincidiendo con el periodo de silencio del motor
 *  3. ScoreEngine El motivo de la interceptación es reproducible (factor + umbral + veredicto)
 *  4. shouldSend Distinción de análisis faltante/fallida (consulte ProactiveDecisionParserTest)
 */
class QuietHoursWindowTest {

    // ── 1. Ida y vuelta: el mapeo semántico debe ser estrictamente recíproco ────────────────────────────────

    /** Configuración predeterminada: Permitir 08-22 → No molestar 22-08. */
    @Test
    fun `default allowed window maps to quiet hours 22 to 8`() {
        val quiet = QuietHoursWindow.AllowedWindow(8, 22).toQuietHours()
        assertEquals(22, quiet.startHour)
        assertEquals(8, quiet.endHour)
    }

    /** no molestar 22-08 → Permitido el 22-08. */
    @Test
    fun `quiet hours 22 to 8 maps back to allowed 8 to 22`() {
        val allowed = QuietHoursWindow.QuietHours(22, 8).toAllowed()
        assertEquals(8, allowed.startHour)
        assertEquals(22, allowed.endHour)
    }

    /**
     * Regresión clave: el usuario guarda una vez en la ventana emergente y el inicio y el final no se pueden intercambiar.
     *
     * La implementación anterior dispersó las dos direcciones de "No molestar" y "Permitir" en la interfaz de usuario (el valor inicial es permitidoHoraEnd,
     * Escribir al guardar allowedHourEnd = s), un guardado requiere dos asignaciones inversas.
     * Esta prueba bloquea "leer en voz alta → Escribe como está → Léelo de nuevo. Este anillo es idéntico.
     */
    @Test
    fun `saving the dialog unchanged does not swap start and end`() {
        val stored = QuietHoursWindow.AllowedWindow(startHour = 8, endHour = 22)

        // Abrir ventana emergente: Lea en voz alta el período de no molestar
        val dialog = stored.toQuietHours()
        assertEquals(22, dialog.startHour)
        assertEquals(8, dialog.endHour)

        // El usuario no movió el control deslizante y guardó directamente: escribiendo el período de no molestar en la ventana permitida
        val rewritten = dialog.toAllowed()

        assertEquals("Permitir el inicio después de guardar y no debe cambiarse", 8, rewritten.startHour)
        assertEquals("El final permitido después de guardar no debe cambiarse", 22, rewritten.endHour)
        assertFalse("La configuración predeterminada no debería convertirse en una ventana vacía.", rewritten.isEmpty)
    }

    /** todos 24×24 Las asignaciones bajo la combinación son estrictamente recíprocas, no solo el grupo de valores predeterminado. */
    @Test
    fun `mapping is involutive for every hour pair`() {
        for (start in 0..23) {
            for (end in 0..23) {
                val allowed = QuietHoursWindow.AllowedWindow(start, end)
                val roundTripped = allowed.toQuietHours().toAllowed()
                assertEquals("start=$start end=$end comienzo cambiado después del viaje de ida y vuelta", start, roundTripped.startHour)
                assertEquals("start=$start end=$end final cambiado después del viaje de ida y vuelta", end, roundTripped.endHour)
            }
        }
    }

    /** El usuario ha ajustado No molestar al 23-07 y la configuración guardada debería permitir 07-23 (No molestar durante la noche). */
    @Test
    fun `editing quiet hours writes the complementary allowed window`() {
        val edited = QuietHoursWindow.QuietHours(startHour = 23, endHour = 7)
        val allowed = edited.toAllowed()

        assertEquals(7, allowed.startHour)
        assertEquals(23, allowed.endHour)
        // Permitir del 23 al 7 debe cubrir 17 horas, incluido el día y excluyendo la noche
        assertEquals(16, allowed.hours().size)
        assertTrue(7 in allowed.hours())
        assertTrue(22 in allowed.hours())
        assertFalse(23 in allowed.hours())
        assertFalse(3 in allowed.hours())
    }

    // ── 2. Diagnóstico de ventana vacía ──────────────────────────────────────────────

    /** Mismo comienzo y final = ventana vacia = Nunca enviado. */
    @Test
    fun `equal start and end is an empty window`() {
        assertTrue(QuietHoursWindow.AllowedWindow(9, 9).isEmpty)
        assertTrue(QuietHoursWindow.AllowedWindow(9, 9).hours().isEmpty())
        assertTrue(QuietHoursWindow.QuietHours(9, 9).isEmptyAllowed)
    }

    /** Sólo las ventanas no vacías cubren el horario. */
    @Test
    fun `non empty windows cover hours correctly`() {
        assertEquals(14, QuietHoursWindow.AllowedWindow(8, 22).hours().size)
        assertEquals(8, QuietHoursWindow.AllowedWindow(22, 6).hours().size) // a través de la noche 22-06
    }

    /** Permita que todo el segmento 23-06 esté en el período de silencio del motor (22-08) dentro → Estructuralmente nunca se envió. */
    @Test
    fun `allowed window fully inside engine quiet hours is detected`() {
        assertTrue(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(23, 6),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    /** Permita que 22-08 coincida exactamente con el período de silencio del motor. → Lo mismo estructuralmente nunca se envía. */
    @Test
    fun `allowed window equal to engine quiet hours is detected`() {
        assertTrue(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(22, 8),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    /** No se debe juzgar mal la ventana diurna normal. */
    @Test
    fun `daytime allowed window is not flagged as shadowed`() {
        assertFalse(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(8, 22),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    /** Las ventanas vacías no participan en la determinación de la \"oclusión\" (isEmpty lo ha expresado por separado para evitar alarmas repetidas). */
    @Test
    fun `empty allowed window is not reported as shadowed`() {
        assertFalse(
            QuietHoursWindow.fullyShadowedByEngineQuietHours(
                QuietHoursWindow.AllowedWindow(9, 9),
                engineQuietStart = ENGINE_QUIET_START,
                engineQuietEnd = ENGINE_QUIET_END,
            ),
        )
    }

    private companion object {
        /** El período de silencio del motor. De acuerdo con el valor predeterminado del producto, las pruebas no se basan en constantes de clases de implementación específicas. */
        const val ENGINE_QUIET_START = 22
        const val ENGINE_QUIET_END = 8
    }
}
