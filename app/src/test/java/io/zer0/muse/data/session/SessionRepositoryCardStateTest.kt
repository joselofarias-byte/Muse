package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.markdown.appendCardStateToMessage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * v2.6.8: card_state limpieza de exportaciones de datos —— Vista previa de sesión e indexación FTS.
 *
 * Estos dos lugares resultaron ser los que “llegaron a los lectores más lejanos y tuvieron el impacto más duradero”:
 *  - vista previa de la sesión (stripForDisplay) no reconoce card_state y lo muestra directamente en la lista. `cardId=…`;
 *  - FTS El índice come el contenido original directamente, por lo que `card_state`,`cardId=`,JSON Se pueden buscar todos los valores clave.
 *    El fragmento encontrado sigue siendo un fragmento de JSON.
 *
 * Ahora los dos van a displayContent/searchableContent respectivamente. Esta prueba verifica un viaje de ida y vuelta desde la base de datos real,
 * No depende de si la persona que llama recuerda limpiar manualmente.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionRepositoryCardStateTest {

    private lateinit var context: Context
    private lateinit var database: MuseDb
    private lateinit var repository: SessionRepository
    private var previousFtsMode: Boolean = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        previousFtsMode = MessageFtsRuntime.useFts5
        // FTS4 Las ramas se escriben manualmente en ngram mediante SessionRepository;FTS5 Tome el disparador de contenido externo,
        // El texto indexado sigue siendo el texto original del contenido del mensaje (consulte las instrucciones de compensación en el informe).
        MessageFtsRuntime.useFts5 = false
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(MessageFtsDdl.createSql(useFts5 = false))
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .build()
        repository = SessionRepository(
            sessionDao = database.sessionDao(),
            messageDao = database.messageDao(),
            database = database,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "card-state-${UUID.randomUUID()}")),
        )
    }

    @After
    fun tearDown() {
        database.close()
        MessageFtsRuntime.useFts5 = previousFtsMode
    }

    @Test
    fun `session preview never shows the card state tail`() = runTest {
        val sessionId = newSession("preview")
        repository.appendMessage(
            sessionId,
            UIMessage(
                role = MessageRole.USER,
                content = withCardState("Configurar el sueño en 6 horas"),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val preview = database.sessionDao().getById(sessionId)!!.lastMessagePreview

        assertEquals("Configurar el sueño en 6 horas", preview)
        assertFalse(preview.contains("card_state"))
        assertFalse(preview.contains("cardId="))
        assertFalse(preview.contains("hours"))
    }

    @Test
    fun `fts index does not contain the card state tail`() = runTest {
        val sessionId = newSession("fts")
        val messageId = repository.appendMessage(
            sessionId,
            UIMessage(
                role = MessageRole.USER,
                content = withCardState("Configurar el sueño en 6 horas"),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val indexed = ftsContentFor(messageId)

        assertTrue("Los resúmenes de texto aún deben poder buscarse", indexed.contains("dormir"))
        assertFalse("La etiqueta de estado de la tarjeta no se puede indexar", indexed.contains("card"))
        assertFalse("La identidad de la tarjeta no se puede indexar", indexed.contains("cardid"))
        assertFalse("JSON Los valores clave no se pueden indexar", indexed.contains("hours"))
    }

    @Test
    fun `plain message keeps indexing and previewing verbatim`() = runTest {
        val sessionId = newSession("plain")
        val messageId = repository.appendMessage(
            sessionId,
            UIMessage(
                role = MessageRole.USER,
                content = "una frase común",
                createdAt = System.currentTimeMillis(),
            ),
        )

        assertEquals("una frase común", database.sessionDao().getById(sessionId)!!.lastMessagePreview)
        assertEquals("Ordinario, una frase, una frase.", ftsContentFor(messageId).trim())
    }

    private suspend fun newSession(tag: String): String {
        val now = System.currentTimeMillis()
        val id = "session-$tag-$now"
        database.sessionDao().insert(SessionEntity(id = id, title = tag, createdAt = now, updatedAt = now))
        return id
    }

    private fun ftsContentFor(messageId: String): String = database.openHelper.readableDatabase
        .query("SELECT content_ngram FROM messages_fts WHERE message_id = ?", arrayOf(messageId))
        .use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
        }

    private fun withCardState(text: String): String = appendCardStateToMessage(text = text, cardId = "msg-123", data = """{"hours":6}""")
}
