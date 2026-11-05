package io.zer0.muse.data.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


class ProactiveDailyGuaranteeTest {

    
    private val baseNow = localMillis(2026, 10, 25, 14, 0)

    private val hourMs = 60 * 60 * 1000L

    @Test
    fun `距上一条 23 小时且当天还有额度时由日保底放行`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 23 * hourMs),
        )

        assertEquals(ProactiveTimingEngine.Verdict.OK, decision.verdict)
        assertTrue(decision.dailyGuarantee)
    }

    @Test
    fun `日保底不越过当天额度`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 23 * hourMs, spentToday = 15),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `间隔已过但额度用完仍然不发`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 25 * hourMs, spentToday = 15),
        )

        assertEquals(ProactiveTimingEngine.Verdict.NO_BUDGET, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `未到保底窗口仍按间隔拦截`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(lastProactiveSentAt = baseNow - 21 * hourMs),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `关闭日保底后不再越过间隔`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(
                lastProactiveSentAt = baseNow - 23 * hourMs,
                dailyGuaranteeMs = 0L,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `日保底不越过免打扰时段`() {
        val lateNight = localMillis(2026, 10, 25, 23, 30)
        val decision = ProactiveTimingEngine.evaluate(
            input(
                now = lateNight,
                lastProactiveSentAt = lateNight - 23 * hourMs,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.QUIET_HOUR, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `日保底不越过冷却`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(
                lastProactiveSentAt = baseNow - 23 * hourMs,
                cooldownUntil = baseNow + hourMs,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.COOLDOWN, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    @Test
    fun `上一轮已经开跑时不被日保底越过`() {
        val decision = ProactiveTimingEngine.evaluate(
            input(
                lastProactiveSentAt = baseNow - 23 * hourMs,
                lastProactiveAttemptAt = baseNow - 5 * 60 * 1000L,
            ),
        )

        assertEquals(ProactiveTimingEngine.Verdict.TOO_SOON, decision.verdict)
        assertFalse(decision.dailyGuarantee)
    }

    private fun input(
        now: Long = baseNow,
        lastProactiveSentAt: Long = 0L,
        lastProactiveAttemptAt: Long = 0L,
        dailyGuaranteeMs: Long = ProactiveTimingEngine.DAILY_GUARANTEE_MS,
        spentToday: Int = 0,
        cooldownUntil: Long = 0L,
    ): ProactiveTimingEngine.Input = ProactiveTimingEngine.Input(
        now = now,
        relation = relation(spentToday, cooldownUntil),
        allowedHourStart = 8,
        allowedHourEnd = 22,
        allDayAllowed = false,
        lastUserMessageAt = 0L,
        isGenerating = false,
        lastProactiveSentAt = lastProactiveSentAt,
        lastProactiveAttemptAt = lastProactiveAttemptAt,
        pacing = ProactiveTimingEngine.Pacing(intervalMinutes = 1440),
        dailyGuaranteeMs = dailyGuaranteeMs,
    )

    private fun relation(spentToday: Int, cooldownUntil: Long): ProactiveRelationEntity = ProactiveRelationEntity(
        assistantId = "default",
        intimacy = 0.5f,
        cooldownUntil = cooldownUntil,
        intensity = ProactiveIntensity.STANDARD.name,
        budgetDate = "2026-10-25",
        spentToday = spentToday,
        earnedToday = 0f,
    )

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long = java.util.Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis
}
