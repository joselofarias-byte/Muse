package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Registra la reducción de ruido cuando no hay ningún canal.
 *
 * El bucle de recepción debe reintentar cada 30 segundos (de lo contrario el usuario que desactive y vuelva a habilitar el canal nunca volverá a recibir mensajes),
 * Por lo tanto, en el estado estable a largo plazo de "no tener siempre canales", lo único que puede hacer es no tomar notas cada vez.
 * Estos tres tipos de transiciones de estado son los que tienen más probabilidades de escribirse incorrectamente: entrada inicial, continuación del mismo estado y recuperación.
 */
class WeClawChannelAvailabilityLogTest {

    @Test
    fun `three consecutive idle rounds produce a single log line`() {
        var previous: Boolean? = true
        val lines = (1..3).map {
            val line = channelAvailabilityLog(hasChannel = false, previousHasChannel = previous)
            previous = false
            line
        }

        assertEquals(1, lines.count { it != null })
        assertEquals(2, lines.count { it == null })
        assertNotNull(lines.first())
    }

    @Test
    fun `recovery logs once and then stays quiet`() {
        var previous: Boolean? = false
        val firstAfterRecovery = channelAvailabilityLog(hasChannel = true, previousHasChannel = previous)
        previous = true

        assertNotNull(firstAfterRecovery)
        assertNull(channelAvailabilityLog(hasChannel = true, previousHasChannel = previous))
    }

    @Test
    fun `first round always logs so the initial state is on record`() {
        assertNotNull(channelAvailabilityLog(hasChannel = false, previousHasChannel = null))
        assertNotNull(channelAvailabilityLog(hasChannel = true, previousHasChannel = null))
    }
}
