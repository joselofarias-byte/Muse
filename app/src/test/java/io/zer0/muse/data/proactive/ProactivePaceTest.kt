package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ProactivePace] Prueba unitaria para empuje de marcha atrás.
 *
 * Verificación de clave: La configuración predeterminada debe ser ESTÁNDAR y el (interval, probability)
 * Se puede devolver a sí mismo tal como está (para garantizar que la interfaz de usuario no se muestre como "personalizada" después de la selección del archivo).
 *
 * El número de artículos diarios no está incluido en esta enumeración, debido a [ProactiveIntensity.cap] Decisión, por lo que el empuje de marcha atrás solo mira
 * Dimensiones de intervalo y probabilidad.
 */
class ProactivePaceTest {

    @Test
    fun `default config maps to standard`() {
        // ProactiveMessageConfig Valor predeterminado:240 minutos / 100%
        assertEquals(ProactivePace.STANDARD, ProactivePace.from(240, 100))
    }

    @Test
    fun `every pace round trips`() {
        ProactivePace.values().forEach { pace ->
            assertEquals(
                pace,
                ProactivePace.from(pace.intervalMinutes, pace.sendProbability),
            )
        }
    }

    @Test
    fun `custom values return null`() {
        assertNull(ProactivePace.from(200, 77))
        assertNull(ProactivePace.from(240, 50))
    }
}
