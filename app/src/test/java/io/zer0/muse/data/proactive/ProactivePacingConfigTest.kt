package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cómo la configuración del ritmo realmente entra en el juicio.
 *
 * En el pasado, estos campos solo los escribía la página de configuración y nunca los leía la capa de juicio: el usuario ajustó el intervalo a 1 hora, pero de hecho
 * Todavía juzgado según las 3 horas codificadas. Aquí analizamos "la configuración se cambia y la conclusión del juicio se cambia en consecuencia" uno por uno.
 * Por cierto, esté atento a la certeza del nerviosismo del día. —— Debe "fijarse el mismo día y cambiar entre días",
 * Cada vez, el intervalo ajustado por el usuario se cambia aleatoriamente a un intervalo.
 */
class ProactivePacingConfigTest {

    private val minute = 60_000L

    /** 2026-10-25 14:00 Hora local (tarde, fuera del período predeterminado de no molestar). */
    private val baseNow = localMillis(2026, 10, 25, 14, 0)

    // ══════════════════════════════════════════════════════════════
    // intervalo
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `El intervalo de tiempo configurado de 60 minutos realmente surte efecto`() {
        // No se inyecta ninguna fluctuación, simplemente pruebe el intervalo en sí.
        val pacing = pacing(intervalMinutes = 60, randomOffsetMinutes = 0)
        assertEquals(
            ProactiveTimingEngine.Verdict.TOO_SOON,
            evaluate(minutesSinceLastSent = 59, pacing = pacing).verdict,
        )
        assertEquals(
            ProactiveTimingEngine.Verdict.OK,
            evaluate(minutesSinceLastSent = 61, pacing = pacing).verdict,
        )
    }

    @Test
    fun `El intervalo predeterminado es 240 minutos.`() {
        val pacing = ProactiveTimingEngine.Pacing()
        assertEquals(240, ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing)))
        assertEquals(
            ProactiveTimingEngine.Verdict.TOO_SOON,
            evaluate(minutesSinceLastSent = 239, pacing = pacing).verdict,
        )
        assertEquals(
            ProactiveTimingEngine.Verdict.OK,
            evaluate(minutesSinceLastSent = 241, pacing = pacing).verdict,
        )
    }

    @Test
    fun `Los intervalos se limitan a 30 a 1440 minutos.`() {
        // La configuración anterior puede almacenarse durante 15 minutos o varios días y la capa de decisión no puede hacer que entre en vigor.
        assertEquals(
            ProactiveTimingEngine.MIN_INTERVAL_MINUTES,
            ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing(5, 0))),
        )
        assertEquals(
            ProactiveTimingEngine.MAX_INTERVAL_MINUTES,
            ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing(5000, 0))),
        )
    }

    // ══════════════════════════════════════════════════════════════
    // nerviosismo del día
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `El mismo asistente recibe la misma compensación el mismo día.`() {
        val key = ProactiveTimingEngine.dayKey(baseNow)
        val first = ProactiveTimingEngine.dailyOffsetMinutes(key, "a1", 60)
        val second = ProactiveTimingEngine.dailyOffsetMinutes(key, "a1", 60)

        assertEquals(first, second)
        assertTrue("El desplazamiento debe estar entre 0 y 60:$first", first in 0..60)
    }

    @Test
    fun `Otro día, la compensación cambia en consecuencia.`() {
        // Validación con entrada hash fija: la fecha es la única variable
        val today = ProactiveTimingEngine.dailyOffsetMinutes("2026-10-25", "default", 60)
        val tomorrow = ProactiveTimingEngine.dailyOffsetMinutes("2026-10-26", "default", 60)

        assertNotEquals(today, tomorrow)
    }

    @Test
    fun `Sin fluctuaciones cuando el límite superior de compensación es 0`() {
        assertEquals(0, ProactiveTimingEngine.dailyOffsetMinutes("2026-10-25", "default", 0))
    }

    @Test
    fun `Determinación de la participación de jitter`() {
        val pacing = pacing(intervalMinutes = 60, randomOffsetMinutes = 60, assistantId = "a1")
        val offset = ProactiveTimingEngine.dailyOffsetMinutes(
            ProactiveTimingEngine.dayKey(baseNow),
            "a1",
            60,
        )
        // intervalo real = 60 + Desplazamiento del día: un minuto todavía está demasiado cerca, por lo que lo publicaremos cuando sea exactamente a tiempo.
        assertEquals(
            ProactiveTimingEngine.Verdict.TOO_SOON,
            evaluate(minutesSinceLastSent = 59L + offset, pacing = pacing).verdict,
        )
        assertEquals(
            ProactiveTimingEngine.Verdict.OK,
            evaluate(minutesSinceLastSent = 60L + offset, pacing = pacing).verdict,
        )
        assertEquals(60 + offset, ProactiveTimingEngine.effectiveIntervalMinutes(input(0, pacing)))
    }

    // ══════════════════════════════════════════════════════════════
    // Ayuda a la construcción
    // ══════════════════════════════════════════════════════════════

    private fun pacing(
        intervalMinutes: Int,
        randomOffsetMinutes: Int,
        assistantId: String = "default",
    ): ProactiveTimingEngine.Pacing = ProactiveTimingEngine.Pacing(
        intervalMinutes = intervalMinutes,
        randomOffsetMinutes = randomOffsetMinutes,
        assistantId = assistantId,
    )

    private fun input(
        minutesSinceLastSent: Long,
        pacing: ProactiveTimingEngine.Pacing,
    ): ProactiveTimingEngine.Input = ProactiveTimingEngine.Input(
        now = baseNow,
        relation = relation(),
        allowedHourStart = 8,
        allowedHourEnd = 22,
        allDayAllowed = false,
        lastUserMessageAt = 0L,
        isGenerating = false,
        lastProactiveSentAt = if (minutesSinceLastSent <= 0) 0L else baseNow - minutesSinceLastSent * minute,
        pacing = pacing,
    )

    private fun evaluate(
        minutesSinceLastSent: Long,
        pacing: ProactiveTimingEngine.Pacing,
    ): ProactiveTimingEngine.Decision = ProactiveTimingEngine.evaluate(input(minutesSinceLastSent, pacing))

    private fun relation(): ProactiveRelationEntity = ProactiveRelationEntity(
        assistantId = "default",
        intimacy = 0.5f,
        cooldownUntil = 0L,
        intensity = ProactiveIntensity.STANDARD.name,
        budgetDate = "2026-10-25",
        spentToday = 0,
        earnedToday = 0f,
    )

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis
}
