package io.zer0.muse.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * get_token_usage Calibre de consulta dependiente (habitación real, sin sustituto).
 *
 * Dos cosas para clavar por separado:
 *  - usage_records agregación existente de (totalsSince / bySourceSince / topModelsSince) filtra por ventana de tiempo;
 *  - messages La agregación de uso de la sesión solo cuenta los "mensajes del asistente con tokens medidos", excluyendo las filas eliminadas y de ceros.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenUsageDaoTest {

    private lateinit var database: MuseDb

    private val dayMs = 24L * 60 * 60 * 1000
    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun record(
        createdAt: Long,
        source: String = UsageSource.CHAT,
        modelId: String = "m-1",
        prompt: Int = 1_000,
        completion: Int = 100,
    ) {
        database.usageRecordDao().insert(
            UsageRecordEntity(
                createdAt = createdAt,
                source = source,
                modelId = modelId,
                promptTokens = prompt,
                completionTokens = completion,
            ),
        )
    }

    @Test
    fun `totals respect the time window`() = runTest {
        record(createdAt = base)
        record(createdAt = base + dayMs, prompt = 2_000, completion = 200)

        val all = database.usageRecordDao().totalsSince(0L)
        assertEquals(2, all?.requests)
        assertEquals(3_000L, all?.promptTokens)
        assertEquals(300L, all?.completionTokens)

        val recent = database.usageRecordDao().totalsSince(base + dayMs)
        assertEquals(1, recent?.requests)
        assertEquals(2_000L, recent?.promptTokens)
    }

    @Test
    fun `source and model breakdown only see the window`() = runTest {
        record(createdAt = base, source = UsageSource.CHAT, modelId = "m-1")
        record(createdAt = base + dayMs, source = UsageSource.TITLE, modelId = "m-2")

        val sources = database.usageRecordDao().bySourceSince(base + dayMs)
        assertEquals(listOf(UsageSource.TITLE), sources.map { it.source })

        val models = database.usageRecordDao().topModelsSince(base + dayMs, 5)
        assertEquals(listOf("m-2"), models.map { it.modelId })
        assertEquals(1_100L, models.first().totalTokens)
    }

    @Test
    fun `session usage counts only assistant messages with measured tokens`() = runTest {
        database.sessionDao().insert(
            SessionEntity(id = "s-1", title = "s-1", createdAt = base, updatedAt = base, assistantId = "default"),
        )
        database.sessionDao().insert(
            SessionEntity(id = "s-2", title = "s-2", createdAt = base, updatedAt = base, assistantId = "default"),
        )
        message("a-1", "s-1", prompt = 1_000, completion = 100)
        message("a-2", "s-1", prompt = 2_000, completion = 200)
        // Las filas con todos ceros no deben contarse (usage_records El lado tampoco recuerda todos los ceros)
        message("a-zero", "s-1", prompt = 0, completion = 0)
        // Los mensajes eliminados no se cuentan
        message("a-del", "s-1", prompt = 9_999, completion = 9_999, deletedAt = base + 10)
        // Otras sesiones no se cuentan
        message("a-other", "s-2", prompt = 5_000, completion = 500)
        // Los mensajes de usuario no tienen uso medido
        database.messageDao().upsert(
            MessageEntity(id = "u-1", sessionId = "s-1", role = "USER", content = "hi", createdAt = base),
        )

        val usage = database.messageDao().usageTotalsBySession("s-1")

        assertEquals(2, usage.requests)
        assertEquals(3_000L, usage.promptTokens)
        assertEquals(300L, usage.completionTokens)
    }

    @Test
    fun `session without measured usage returns zeros`() = runTest {
        database.sessionDao().insert(
            SessionEntity(id = "s-empty", title = "e", createdAt = base, updatedAt = base, assistantId = "default"),
        )

        val usage = database.messageDao().usageTotalsBySession("s-empty")

        assertEquals(0, usage.requests)
        assertEquals(0L, usage.promptTokens)
        assertEquals(0L, usage.completionTokens)
    }

    private suspend fun message(
        id: String,
        sessionId: String,
        prompt: Int,
        completion: Int,
        deletedAt: Long? = null,
    ) {
        database.messageDao().upsert(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = "ASSISTANT",
                content = "reply",
                createdAt = base,
                deletedAt = deletedAt,
                promptTokens = prompt,
                completionTokens = completion,
            ),
        )
    }
}
