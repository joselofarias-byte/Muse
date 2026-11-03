package io.zer0.muse.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * `message.delete` / `message.favorite` El efecto real después de caer en el almacén.
 *
 * La prueba de la capa de comando utiliza un almacén falso para probar las reglas. Aquí, use true Room + true. [MessageImageStore] prueba“¿Lo has eliminado?”:
 * Además de que la línea del mensaje desaparezca, el archivo de imagen del mensaje (incluido el generado por el lado web para base64 en línea) `{id}_inline_*`) debería hacer lo mismo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostMessageWritePersistenceTest {

    private lateinit var database: MuseDb
    private lateinit var imageDir: File

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        // messages_fts No en las entidades de Room (FTS La tabla virtual se crea mediante la devolución de llamada de creación de base de datos de MuseDb).
        // La biblioteca de memoria no ejecutará esas devoluciones de llamada. Se completan con el mismo DDL. De lo contrario, la sincronización FTS durante la eliminación no informará dicha tabla.
        database.openHelper.writableDatabase.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts` USING FTS4(`message_id` TEXT, `content_ngram` TEXT)",
        )
        imageDir = Files.createTempDirectory("host-message-write").toFile()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun repository(): SessionRepository = SessionRepository(
        sessionDao = database.sessionDao(),
        messageDao = database.messageDao(),
        database = database,
        context = context,
        messageImageStore = MessageImageStore(imageDir),
    )

    /** Primero cree una sesión y luego inserte el mensaje (messages tiene una clave externa para las sesiones). */
    private suspend fun seedSession(sessionId: String = SESSION_ID) {
        val now = 1_000L
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                title = sessionId,
                createdAt = now,
                updatedAt = now,
                assistantId = "default",
            ),
        )
    }

    private suspend fun seedMessage(id: String, sessionId: String = SESSION_ID): MessageEntity {
        val entity = MessageEntity(
            id = id,
            sessionId = sessionId,
            role = "assistant",
            content = "contenido",
            createdAt = 2_000L,
        )
        database.messageDao().upsert(entity)
        return entity
    }

    @Test
    fun `delete removes the row and both media file flavors`() = runBlocking {
        seedSession()
        val id = UUID.randomUUID().toString()
        seedMessage(id)
        val plain = File(imageDir, "${id}_0_deadbeef.bin").apply { writeBytes(ByteArray(8) { 1 }) }
        val inline = File(imageDir, "${id}_inline_cafebabe.png").apply { writeBytes(ByteArray(8) { 2 }) }
        assertTrue(plain.isFile)
        assertTrue(inline.isFile)

        repository().deleteMessage(id)

        assertNull("La línea del mensaje debe desaparecer.", database.messageDao().getByMessageId(id))
        assertTrue("Se deben borrar los cuadros de colocación ordinarios.", !plain.exists())
        assertTrue("Gráfico de ubicación en línea ({id}_inline_*) debe ser aclarado", !inline.exists())
    }

    @Test
    fun `deleting an already deleted id is a silent no-op`() = runBlocking {
        seedSession()
        val id = UUID.randomUUID().toString()
        seedMessage(id)

        repository().deleteMessage(id)
        repository().deleteMessage(id)

        assertNull(database.messageDao().getByMessageId(id))
    }

    @Test
    fun `favorite set is idempotent and readable`() = runBlocking {
        seedSession()
        val id = UUID.randomUUID().toString()
        seedMessage(id)
        val repository = repository()

        repository.setMessageFavorite(id, true)
        assertEquals(true, database.messageDao().getByMessageId(id)?.favorite)
        // Si se vuelve a establecer el mismo estado, no se informará ni se invertirá ningún error.
        repository.setMessageFavorite(id, true)
        assertEquals(true, database.messageDao().getByMessageId(id)?.favorite)

        repository.setMessageFavorite(id, false)
        assertEquals(false, database.messageDao().getByMessageId(id)?.favorite)
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
