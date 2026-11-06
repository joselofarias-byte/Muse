package io.zer0.muse.miniphone.data.moment.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Un puro juicio de "¿debería hacerse esta ronda?"
 *
 * No hay espacio involucrado, ni distribución de red, ni lectura del reloj del sistema.:El momento y la zona horaria los proporciona la prueba.,
 * Entonces "23 Se pueden fijar límites como "haga clic para omitir" y "debería omitir 30 minutos desde la última vez".
 */
class MpMomentAutoPlanTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun quietHoursAtNightSkips() {
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS), decide(at(23)))
    }

    @Test
    fun quietHoursBeforeMorningSkips() {
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS), decide(at(7)))
    }

    @Test
    fun eightOClockIsAlreadyAwake() {
        assertEquals(MpMomentAutoDecision.Generate, decide(at(8)))
    }

    @Test
    fun thirtyMinutesAfterLastGenerationSkipsAsTooSoon() {
        val state = decide(at(14), lastGeneratedAtMillis = at(13, minute = 30))
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.TOO_SOON), state)
    }

    @Test
    fun ninetyMinutesAfterLastGenerationMayPublish() {
        val state = decide(at(14), lastGeneratedAtMillis = at(12, minute = 30))
        assertEquals(MpMomentAutoDecision.Generate, state)
    }

    @Test
    fun highRollSkipsAsProbability() {
        val state = decide(at(14), roll = 0.9)
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.PROBABILITY), state)
    }

    @Test
    fun lowRollKeepsGoing() {
        assertEquals(MpMomentAutoDecision.Generate, decide(at(14), roll = 0.59))
    }

    @Test
    fun noCharacterSkips() {
        val state = decide(at(14), hasCharacter = false)
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.NO_CHARACTER), state)
    }

    @Test
    fun switchOffSkipsBeforeAnythingElse() {
        // Cuando el interruptor está apagado,Incluso si se cumplen el tiempo, la probabilidad y el rol, no sucederá.
        val state = decide(at(14), enabled = false)
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.DISABLED), state)
    }

    @Test
    fun quietHoursWinOverOtherReasons() {
        // 23 Haga clic y 10 minutos desde la última vez.:Las horas de silencio deben informarse primero,en lugar de too_soon
        val state = decide(at(23), lastGeneratedAtMillis = at(22, minute = 50))
        assertEquals(MpMomentAutoDecision.Skip(MpMomentSkipReason.QUIET_HOURS), state)
    }

    @Test
    fun quietHourCoversLateEveningAndEarlyMorning() {
        assertTrue(MpMomentAutoPlan.isQuietHour(22))
        assertTrue(MpMomentAutoPlan.isQuietHour(2))
        assertFalse(MpMomentAutoPlan.isQuietHour(8))
        assertFalse(MpMomentAutoPlan.isQuietHour(21))
    }

    @Test
    fun hintFollowsTheHourOfDay() {
        assertTrue(MpMomentAutoPlan.hintFor(9).startsWith("mañana"))
        assertTrue(MpMomentAutoPlan.hintFor(12).startsWith("mediodía"))
        assertTrue(MpMomentAutoPlan.hintFor(15).startsWith("tarde"))
        assertTrue(MpMomentAutoPlan.hintFor(20).startsWith("noche"))
    }

    private fun decide(
        now: Long,
        lastGeneratedAtMillis: Long? = null,
        roll: Double = 0.1,
        hasCharacter: Boolean = true,
        enabled: Boolean = true,
    ): MpMomentAutoDecision = MpMomentAutoPlan.decide(
        nowMillis = now,
        lastGeneratedAtMillis = lastGeneratedAtMillis,
        roll = roll,
        hasCharacter = hasCharacter,
        enabled = enabled,
        zone = zone,
    )

    private fun at(hour: Int, minute: Int = 0): Long = LocalDateTime.of(2026, 5, 12, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
