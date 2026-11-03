package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * v1.0.72: Prueba de cálculo de términos solares.(La fórmula de longevidad se calcula por año.).
 *
 * fondo:Antigua implementación de tabla de fechas fijas(Principios de otoño=8/8),Pero el comienzo del otoño de 2026 está en realidad en 8/7,
 * conducir a comentarios de los usuarios"El comienzo del otoño fue ayer.". Las nuevas realizaciones se calculan año tras año utilizando la fórmula de longevidad.
 *
 * Verificar las verdaderas fechas de términos solares de años conocidos.:
 *  - 2026 Principios de otoño = 8/7(Escenarios de errores reportados por los usuarios)
 *  - 2026 Fin del calor del verano = 8/23
 *  - 2026 equinoccio = 3/20
 *  - 2026 Principio de primavera = 2/4
 *  - regreso en fecha ordinaria null
 *  - Consejos para el día previo al término solar"mañana esX"
 */
class SolarTermTest {

    @Test
    fun `2026 autumn start is aug 7 not aug 8`() {
        // Regresar: Comentarios de los usuarios"El comienzo del otoño fue ayer."(Escritura de mesa antigua 8/8)
        assertEquals("Hoy es el comienzo del otoño.", GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 7)))
        // 8/8 Ya no es el comienzo del otoño
        assertNull(GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 8)))
    }

    @Test
    fun `2026 other autumn terms`() {
        assertEquals("Hoy es el calor del verano.", GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 23)))
    }

    @Test
    fun `2026 spring terms`() {
        assertEquals("Hoy es el comienzo de la primavera.", GreetingHelper.getSolarTerm(LocalDate.of(2026, 2, 4)))
        assertEquals("Hoy es el equinoccio de primavera.", GreetingHelper.getSolarTerm(LocalDate.of(2026, 3, 20)))
    }

    @Test
    fun `day before term announces tomorrow`() {
        assertEquals("Mañana es el comienzo del otoño.", GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 6)))
    }

    @Test
    fun `ordinary day returns null`() {
        assertNull(GreetingHelper.getSolarTerm(LocalDate.of(2026, 8, 10)))
        assertNull(GreetingHelper.getSolarTerm(LocalDate.of(2026, 12, 25)))
    }

    @Test
    fun `2027 autumn start varies by year`() {
        // 2027 Principios de otoño = 8/8(Diferentes fechas en diferentes años.,Validar tablas no fijas)
        assertEquals("Hoy es el comienzo del otoño.", GreetingHelper.getSolarTerm(LocalDate.of(2027, 8, 8)))
    }
}
