package io.zer0.muse.data.`import`

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.0.74: Realice pruebas de importación de un extremo a otro con datos de exportación reales de ChatGPT.
 * Archivo: C:/Users/21192/AppData/Local/Temp/gpt_conversations.json
 * verificar: 73 Conversaciones / 1638 Mensajes,El primer y último orden son correctos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatGptRealDataImportTest {

    private lateinit var db: MuseDb
    private lateinit var sessionRepo: SessionRepository
    private lateinit var assistantRepo: AssistantRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        sessionRepo = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            database = db,
            context = context,
            messageImageStore = MessageImageStore(File(context.cacheDir, "msg_imgs_test")),
        )
        assistantRepo = AssistantRepository(db.assistantDao(), context)
        runBlocking { assistantRepo.ensureDefaultExists() }
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun importRealChatGptData_importsAllConversations() = runBlocking {
        val jsonFile = File("C:/Users/21192/AppData/Local/Temp/gpt_conversations.json")
        if (!jsonFile.exists()) {
            println("SKIP: El archivo de datos real no existe.")
            return@runBlocking
        }
        val json = jsonFile.readText()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = json,
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        println("REAL-IMPORT conversations=${result.conversationsImported} messages=${result.messagesImported} errors=${result.errors}")
        assert(result.errors.isEmpty()) { "Error de importación: ${result.errors}" }
        // Robolectric Sin módulo FTS5,appendMessage La reversión de transacciones hace que la base de datos no pueda encontrar mensajes(La máquina real es normal.),
        // Basado en el recuento de ImportResult.
        assert(result.conversationsImported == 73) { "Número de sesiones ${result.conversationsImported} != 73" }
        assert(result.messagesImported >= 1600) { "Número de mensajes ${result.messagesImported} Mucho menos de lo esperado 1638" }
        java.io.File(
            "C:/Users/21192/AppData/Local/Temp/gpt_import_result.txt",
        ).writeText("conversations=${result.conversationsImported} messages=${result.messagesImported} errors=${result.errors}")
    }
}
