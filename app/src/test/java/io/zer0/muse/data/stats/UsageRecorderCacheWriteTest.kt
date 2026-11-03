package io.zer0.muse.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * lectura de caché/Las divisiones de escritura se colocan en la base de datos: el volumen de escritura no se puede descartar como antes de la división.
 *
 * Antes de la división, el volumen de negocios estadístico era sólo `cached_tokens` Una columna contiene los valores combinados de "lectura + escritura".
 * Escritura de caché (Anthropic Se cobra a 1,25 veces el precio del insumo), por lo que nunca se incluirá en las estadísticas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsageRecorderCacheWriteTest {

    private class CapturingDao : UsageRecordDao {
        val inserted = mutableListOf<UsageRecordEntity>()

        override suspend fun insert(record: UsageRecordEntity) {
            inserted += record
        }

        override suspend fun insertAll(records: List<UsageRecordEntity>) {
            inserted += records
        }

        override suspend fun totalsSince(sinceMs: Long): UsageTotals? = null

        override suspend fun bySourceSince(sinceMs: Long): List<UsageBySource> = emptyList()

        override suspend fun topModelsSince(sinceMs: Long, limit: Int): List<UsageByModel> = emptyList()

        override suspend fun byDaySince(sinceMs: Long): List<UsageByDay> = emptyList()

        override suspend fun deleteBefore(beforeMs: Long): Int = 0

        override suspend fun count(): Int = inserted.size
    }

    @Test
    fun `cache read and cache write are recorded in separate columns`() = kotlinx.coroutines.runBlocking {
        val dao = CapturingDao()
        val recorder = UsageRecorder(dao)

        recorder.record(
            source = UsageSource.CHAT,
            modelId = "claude-sonnet-4",
            promptTokens = 1_000,
            completionTokens = 200,
            reasoningTokens = 0,
            cachedTokens = 5_000,
            cacheWriteTokens = 2_000,
        )

        assertEquals(1, dao.inserted.size)
        val row = dao.inserted.single()
        assertEquals("El caché lee una sola columna", 5_000, row.cachedTokens)
        assertEquals("La caché se escribe en una sola columna y no se puede leer simultáneamente.", 2_000, row.cacheWriteTokens)
        // El calibre existente no se mueve.
        assertEquals(1_000, row.promptTokens)
        assertEquals(200, row.completionTokens)
    }

    @Test
    fun `openai style record leaves cache write at zero`() = kotlinx.coroutines.runBlocking {
        val dao = CapturingDao()
        val recorder = UsageRecorder(dao)

        recorder.record(
            source = UsageSource.CHAT,
            promptTokens = 1_000,
            completionTokens = 100,
            cachedTokens = 800,
        )

        val row = dao.inserted.single()
        assertEquals(800, row.cachedTokens)
        assertEquals("OpenAI No hay elementos de facturación de escritura en caché", 0, row.cacheWriteTokens)
    }
}
