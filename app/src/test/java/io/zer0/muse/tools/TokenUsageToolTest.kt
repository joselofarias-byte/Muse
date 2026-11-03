package io.zer0.muse.tools

import io.zer0.muse.data.stats.UsageByModel
import io.zer0.muse.data.stats.UsageByDay
import io.zer0.muse.data.stats.UsageBySource
import io.zer0.muse.data.stats.UsageRecordDao
import io.zer0.muse.data.stats.UsageRecordEntity
import io.zer0.muse.data.stats.UsageTotals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * get_token_usage El formato de retorno y la ruta de degradación.
 *
 * Lo que está fijado aquí son las "esas líneas vistas por el modelo": el calibre de abreviatura numérica, el punto de partida de la ventana de hoy y la identificación de sesión que falta. /
 * Qué se debe mostrar cuando no hay datos o falta la sonda de contexto.DAO Solo se usa como sustituto y la agregación real se ejecuta en TokenUsageDaoTest.
 */
class TokenUsageToolTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-29 10:00 (+08:00), el punto de partida de hoy debería ser 2026-10-28T16:00Z. */
    private val now = Instant.parse("2026-10-29T02:00:00Z").toEpochMilli()
    private val startOfToday = Instant.parse("2026-10-28T16:00:00Z").toEpochMilli()

    private fun context(sessionId: String? = "s-1") =
        ToolExecutionContext(scope = "chat", spaceId = "default", sessionId = sessionId)

    private class FakeDao(
        var totals: UsageTotals? = null,
        var models: List<UsageByModel> = emptyList(),
        var sources: List<UsageBySource> = emptyList(),
    ) : UsageRecordDao {
        var lastSinceMs: Long? = null

        override suspend fun insert(record: UsageRecordEntity) = Unit

        override suspend fun insertAll(records: List<UsageRecordEntity>) = Unit

        override suspend fun totalsSince(sinceMs: Long): UsageTotals? {
            lastSinceMs = sinceMs
            return totals
        }

        override suspend fun bySourceSince(sinceMs: Long): List<UsageBySource> = sources

        override suspend fun topModelsSince(sinceMs: Long, limit: Int): List<UsageByModel> = models

        override suspend fun byDaySince(sinceMs: Long): List<UsageByDay> = emptyList()

        override suspend fun deleteBefore(beforeMs: Long): Int = 0

        override suspend fun count(): Int = 0
    }

    private fun totals(requests: Int = 12, prompt: Long = 1_234_567L, completion: Long = 45_678L, cached: Long = 0L) =
        UsageTotals(
            requests = requests,
            promptTokens = prompt,
            completionTokens = completion,
            reasoningTokens = 0,
            cachedTokens = cached,
            cacheWriteTokens = 0,
            avgFirstTokenMs = 0,
        )

    @Test
    fun `formatTokens shortens large numbers`() {
        assertEquals("0", TokenUsageTool.formatTokens(0))
        assertEquals("999", TokenUsageTool.formatTokens(999))
        assertEquals("1k", TokenUsageTool.formatTokens(1_000))
        assertEquals("12.3k", TokenUsageTool.formatTokens(12_300))
        assertEquals("45.6k", TokenUsageTool.formatTokens(45_678))
        assertEquals("123.4k", TokenUsageTool.formatTokens(123_456))
        assertEquals("1.2M", TokenUsageTool.formatTokens(1_234_567))
        assertEquals("12.3M", TokenUsageTool.formatTokens(12_345_678))
    }

    @Test
    fun `default scope reports today since local midnight`() = runTest {
        val dao = FakeDao(totals = totals(), models = listOf(UsageByModel("deepseek-v4-flash", 10, 1_200_000)))
        val text = TokenUsageTool.execute(emptyMap(), context(), dao, now = now, zone = zone)

        assertEquals(startOfToday, dao.lastSinceMs)
        assertTrue(text.contains("today 2026-10-29"))
        assertTrue(text.contains("12 req"))
        assertTrue(text.contains("in 1.2M"))
        assertTrue(text.contains("out 45.6k"))
        assertTrue(text.contains("models: deepseek-v4-flash 1.2M (10)"))
    }

    @Test
    fun `all scope queries from zero`() = runTest {
        val dao = FakeDao(totals = totals())
        val text = TokenUsageTool.execute(mapOf("scope" to "all"), context(), dao, now = now, zone = zone)

        assertEquals(0L, dao.lastSinceMs)
        assertTrue(text.contains("Token usage · all time"))
    }

    @Test
    fun `session line uses injected reader`() = runTest {
        val dao = FakeDao(totals = totals())
        val text =
            TokenUsageTool.execute(
                emptyMap(),
                context(sessionId = "s-1"),
                dao,
                sessionUsage = { id -> if (id == "s-1") TokenUsageTool.SessionUsage(3, 320_000, 21_000) else null },
                now = now,
                zone = zone,
            )

        assertTrue(text.contains("session: 3 req · in 320k · out 21k · all 341k"))
    }

    @Test
    fun `session scope without session id reports unavailable instead of guessing`() = runTest {
        val dao = FakeDao(totals = totals())
        val text =
            TokenUsageTool.execute(
                mapOf("scope" to "session"),
                context(sessionId = null),
                dao,
                sessionUsage = { TokenUsageTool.SessionUsage(3, 1_000, 1_000) },
                now = now,
                zone = zone,
            )

        assertTrue(text.contains("session: unavailable"))
        // Si no se puede obtener la dimensión de la sesión, no se comprobará el flujo: no se creará un número de "esta sesión" según la ventana de tiempo.
        assertNull(dao.lastSinceMs)
    }

    @Test
    fun `no session usage yet degrades to a clear sentence`() = runTest {
        val dao = FakeDao(totals = totals())
        val text =
            TokenUsageTool.execute(
                mapOf("scope" to "session"),
                context(sessionId = "s-1"),
                dao,
                sessionUsage = { null },
                now = now,
                zone = zone,
            )

        assertTrue(text.contains("no measured usage yet"))
    }

    @Test
    fun `empty window degrades without fake zeros`() = runTest {
        val dao = FakeDao(totals = totals(requests = 0, prompt = 0, completion = 0))
        val text = TokenUsageTool.execute(emptyMap(), context(sessionId = null), dao, now = now, zone = zone)

        assertTrue(text.contains("no requests recorded in this window"))
        assertFalse(text.contains("models:"))
    }

    @Test
    fun `context occupancy line appears only when provider gives a limit`() = runTest {
        val dao = FakeDao(totals = totals())
        val withProvider =
            TokenUsageTool.execute(
                emptyMap(),
                context(),
                dao,
                contextOccupancy = { 45_000 to 128_000 },
                now = now,
                zone = zone,
            )
        assertTrue(withProvider.contains("context: 45k / 128k (35%)"))

        val noProvider = TokenUsageTool.execute(emptyMap(), context(), dao, now = now, zone = zone)
        assertFalse(noProvider.contains("context:"))

        val unknownLimit =
            TokenUsageTool.execute(
                emptyMap(),
                context(),
                dao,
                contextOccupancy = { 45_000 to 0 },
                now = now,
                zone = zone,
            )
        assertFalse(unknownLimit.contains("context:"))
    }

    @Test
    fun `missing usage store is an explicit error`() = runTest {
        val text = TokenUsageTool.execute(emptyMap(), context(), null, now = now, zone = zone)
        assertTrue(text.startsWith("Error:"))
    }

    @Test
    fun `unknown scope is rejected`() = runTest {
        val text = TokenUsageTool.execute(mapOf("scope" to "week"), context(), FakeDao(), now = now, zone = zone)
        assertTrue(text.startsWith("Error: scope"))
    }

    @Test
    fun `output stays within a screen`() = runTest {
        val dao =
            FakeDao(
                totals = totals(),
                models = (1..5).map { UsageByModel("model-$it", it, it * 100_000L) },
                sources = (1..4).map { UsageBySource("src-$it", it, 1_000L, 1_000L) },
            )
        val text = TokenUsageTool.execute(emptyMap(), context(), dao, now = now, zone = zone)
        assertTrue(text.lines().size <= 6)
    }
}
