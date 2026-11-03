package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * lectura de caché/Escribe persistencia en columnas de ida y vuelta.
 *
 * Cubre dos cosas:
 *  - Nuevo récord:`cachedTokens`(leer) con `cacheWriteTokens`(escribir) Cada uno entra en su propia columna, y cuando la lee no la pierde;
 *  - Registros antiguos: filas antes de dividir solamente `cachedTokens`(Se instala con un valor combinado de lectura + escritura), la lectura no falla, presione "leer" para explicar,
 *    `cacheWriteTokens` Mantenga NULL (desconocido), sin reposición, sin recálculo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRepositoryCacheUsageTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "cache-usage-${UUID.randomUUID()}")),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `new message persists cache read and cache write separately`() = runTest {
        val sessionId = newSession("rt")
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "responder",
                createdAt = System.currentTimeMillis(),
                promptTokens = 1_000,
                completionTokens = 200,
                reasoningTokens = 0,
                cachedTokens = 5_000,
                cacheWriteTokens = 2_000,
            )

        val id = message.id.toString()
        repository.upsertMessage(sessionId, message, skipFts = true)
        val entity = database.messageDao().getByMessageId(id)

        assertNotNull(entity)
        assertEquals("Caída de lectura de caché cachedTokens", 5_000, entity!!.cachedTokens)
        assertEquals("Caída de escritura en caché cacheWriteTokens", 2_000, entity.cacheWriteTokens)
        // El calibre existente no se mueve.
        assertEquals(1_000, entity.promptTokens)
        assertEquals(200, entity.completionTokens)

        val restored = repository.getMessageAsUiMessage(id)
        assertNotNull(restored)
        assertEquals(5_000, restored!!.cachedTokens)
        assertEquals(2_000, restored.cacheWriteTokens)
    }

    @Test
    fun `legacy row with only cached tokens reads as cache read without crashing`() = runTest {
        val sessionId = newSession("legacy")
        // Filas antes de dividir: solo valores fusionados,cacheWriteTokens listado como NULL
        val legacy = MessageEntity(
            id = "legacy-${UUID.randomUUID()}",
            sessionId = sessionId,
            role = MessageRole.ASSISTANT.name,
            content = "Antigua respuesta",
            createdAt = System.currentTimeMillis(),
            promptTokens = 300,
            completionTokens = 50,
            reasoningTokens = 0,
            cachedTokens = 700,
        )
        database.messageDao().upsert(legacy)

        val entity = database.messageDao().getByMessageId(legacy.id)
        assertNotNull(entity)
        assertEquals("Los valores combinados de las filas antiguas se conservan tal como están sin relleno.", 700, entity!!.cachedTokens)
        assertNull("La fila anterior no tiene ningún registro de volumen de escritura y debería ser NULL en su lugar. 0", entity.cacheWriteTokens)

        val restored = repository.getMessageAsUiMessage(legacy.id)
        assertNotNull(restored)
        assertEquals(700, restored!!.cachedTokens)
        assertNull(restored.cacheWriteTokens)
    }

    @Test
    fun `openai message keeps cache write null`() = runTest {
        val sessionId = newSession("openai")
        val message =
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "responder",
                createdAt = System.currentTimeMillis(),
                promptTokens = 1_000,
                completionTokens = 100,
                cachedTokens = 800,
                cacheWriteTokens = 0,
            )

        val id = message.id.toString()
        repository.upsertMessage(sessionId, message, skipFts = true)
        val entity = database.messageDao().getByMessageId(id)

        assertEquals(800, entity!!.cachedTokens)
        // OpenAI Sin escrituras en caché: 0 explícito, distinguido de NULL (desconocido) para filas antiguas
        assertEquals(0, entity.cacheWriteTokens)
    }

    private suspend fun newSession(tag: String): String {
        val now = System.currentTimeMillis()
        val id = "session-cache-$tag-$now"
        database.sessionDao().insert(SessionEntity(id = id, title = tag, createdAt = now, updatedAt = now))
        return id
    }
}
