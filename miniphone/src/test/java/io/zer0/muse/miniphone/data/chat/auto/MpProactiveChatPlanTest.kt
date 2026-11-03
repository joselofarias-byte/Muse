package io.zer0.muse.miniphone.data.chat.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Un juicio puro sobre "¿debería tomar la iniciativa de enviar mensajes privados en esta ronda?"
 *
 * No hay espacio involucrado, ni distribución de red, ni lectura del reloj del sistema.:Los puntos de tiempo, las zonas horarias y los dados se obtienen mediante pruebas.,
 * Entonces "23 Se pueden fijar límites como "se debe omitir el clic" y "debe omitirse 2 horas desde la última vez".
 */
class MpProactiveChatPlanTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun switchOffSkipsBeforeAnythingElse() {
        // Cuando el interruptor está apagado,Incluso si se cumplen el tiempo, la conversación y la probabilidad, no se enviará.(es el valor predeterminado,La gran mayoría de usuarios van por este camino.)
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.DISABLED), decide(at(14), enabled = false))
    }

    @Test
    fun quietHourAtNightSkips() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.QUIET_HOUR), decide(at(23)))
    }

    @Test
    fun noCharacterSkips() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_CHARACTER), decide(at(14), characterCount = 0))
    }

    @Test
    fun noEligibleSessionSkips() {
        assertEquals(
            MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.NO_HISTORY),
            decide(at(14), hasEligibleSession = false),
        )
    }

    @Test
    fun twoHoursAfterLastProactiveChatSkipsAsTooSoon() {
        val state = decide(at(14), lastProactiveAtMillis = at(12))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.TOO_SOON), state)
    }

    @Test
    fun highRollSkipsAsProbability() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.PROBABILITY), decide(at(14), roll = 0.9f))
    }

    @Test
    fun allClearSends() {
        assertEquals(MpProactiveChatDecision.Send, decide(at(14)))
    }

    @Test
    fun userTalkingThreeMinutesAgoSkipsAsJustActive() {
        val state = decide(at(14), lastUserMessageAtMillis = at(13, minute = 57))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.USER_JUST_ACTIVE), state)
    }

    @Test
    fun exactlyTenMinutesOfSilenceKeepsGoing() {
        // El límite está en "menos de 10 minutos":Faltan exactamente 10 minutos para el estreno.
        val state = decide(at(14), lastUserMessageAtMillis = at(13, minute = 50))
        assertEquals(MpProactiveChatDecision.Send, state)
    }

    @Test
    fun userNeverSpokeHereIsNotJustActive() {
        // El usuario nunca dijo una palabra durante la conversación.(null):No puede considerarse "simplemente activo"
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), lastUserMessageAtMillis = null))
    }

    @Test
    fun userJustActiveWinsOverTooSoon() {
        // 5 horas desde el último mensaje privado activo(Pasaron 4 horas),Pero el usuario habló hace 3 minutos. → debe ser reportado user_just_active
        val state = decide(at(14), lastProactiveAtMillis = at(9), lastUserMessageAtMillis = at(13, minute = 57))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.USER_JUST_ACTIVE), state)
    }

    @Test
    fun neverSentBeforeIsNotTooSoon() {
        // nunca publicado(lastProactiveAtMillis = null):No se puede considerar "demasiado rápido",De lo contrario, nunca se enviará por primera vez.
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), lastProactiveAtMillis = null))
    }

    @Test
    fun fourHoursAfterLastProactiveChatMaySend() {
        // exactamente con 4 horas de diferencia:El límite está en "menos que",Debería ser liberado en ese momento.
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), lastProactiveAtMillis = at(10)))
    }

    @Test
    fun rollJustBelowProbabilitySends() {
        assertEquals(MpProactiveChatDecision.Send, decide(at(14), roll = 0.34f))
    }

    @Test
    fun rollAtProbabilitySkips() {
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.PROBABILITY), decide(at(14), roll = 0.35f))
    }

    @Test
    fun quietHourWinsOverOtherReasons() {
        // 23 Haga clic y 10 minutos desde la última vez.:Las horas de silencio deben informarse primero,en lugar de too_soon
        val state = decide(at(23), lastProactiveAtMillis = at(22, minute = 50))
        assertEquals(MpProactiveChatDecision.Skip(MpProactiveChatSkipReason.QUIET_HOUR), state)
    }

    @Test
    fun quietHourCoversLateEveningAndEarlyMorning() {
        assertTrue(MpProactiveChatPlan.isQuietHour(22))
        assertTrue(MpProactiveChatPlan.isQuietHour(2))
        assertFalse(MpProactiveChatPlan.isQuietHour(8))
        assertFalse(MpProactiveChatPlan.isQuietHour(21))
    }

    private fun decide(
        now: Long,
        enabled: Boolean = true,
        lastProactiveAtMillis: Long? = null,
        lastUserMessageAtMillis: Long? = null,
        characterCount: Int = 1,
        hasEligibleSession: Boolean = true,
        roll: Float = 0.1f,
    ): MpProactiveChatDecision = MpProactiveChatPlan.decide(
        nowMillis = now,
        enabled = enabled,
        lastUserMessageAtMillis = lastUserMessageAtMillis,
        characterCount = characterCount,
        hasEligibleSession = hasEligibleSession,
        lastProactiveAtMillis = lastProactiveAtMillis,
        random = { roll },
        zone = zone,
    )

    private fun at(hour: Int, minute: Int = 0): Long = LocalDateTime.of(2026, 5, 12, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
