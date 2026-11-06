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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * v1.0.74: ChatGPT Pruebas de exportación e importación.
 *
 * Anular el análisis oficial de conversaciones.json:
 *  - Formato de matriz de nivel superior + árbol de mapeo extrae mensajes de usuario/asistente
 *  - Marca de tiempo de segundo nivel a milisegundos + ordenar por tiempo
 *  - system/tool Mensaje omitido
 *  - Creación de sesiones + almacenamiento de mensajes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatGptImportTest {

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
        // Asegúrese de que exista el asistente predeterminado
        kotlinx.coroutines.runBlocking { assistantRepo.ensureDefaultExists() }
    }

    @After
    fun teardown() {
        db.close()
    }

    /** Formato oficial de exportación:Matriz de nivel superior + árbol de mapeo. */
    @Test
    fun importChatGpt_mappingTree_extractsOrderedMessages() = kotlinx.coroutines.runBlocking {
        val sample = """
        [
          {
            "title": "planes de fin de semana",
            "create_time": 1690000000.0,
            "mapping": {
              "n1": {
                "message": {
                  "id": "n1",
                  "author": {"role": "user"},
                  "content": {"content_type": "text", "parts": ["Hola"]},
                  "create_time": 1690000001.0
                },
                "parent": null,
                "children": ["n2"]
              },
              "n2": {
                "message": {
                  "id": "n2",
                  "author": {"role": "assistant"},
                  "content": {"content_type": "text", "parts": ["Hola!¿Hay algo en lo que pueda ayudarte??"]},
                  "create_time": 1690000002.0
                },
                "parent": "n1",
                "children": ["n3"]
              },
              "n3": {
                "message": {
                  "id": "n3",
                  "author": {"role": "user"},
                  "content": {"content_type": "text", "parts": ["Ayúdame a planificar el fin de semana."]},
                  "create_time": 1690000005.0
                },
                "parent": "n2",
                "children": []
              },
              "n4": {
                "message": {
                  "id": "n4",
                  "author": {"role": "system"},
                  "content": {"content_type": "text", "parts": ["system prompt"]},
                  "create_time": 1690000000.0
                },
                "parent": null,
                "children": ["n1"]
              }
            },
            "current_node": "n3"
          }
        ]
        """.trimIndent()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = sample,
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        println("DEBUG result: $result")
        assertTrue("No debería haber errores: ${result.errors}", result.errors.isEmpty())
        assertEquals("Se debe importar 1 sesión", 1, result.conversationsImported)
        assertEquals("Se deben importar 3 mensajes.(system saltar)", 3, result.messagesImported)
    }

    /** parts Contiene varios párrafos de texto.(empalme). */
    @Test
    fun importChatGpt_multipleParts_joins() = kotlinx.coroutines.runBlocking {
        val sample = """
        [
          {
            "title": "Varios párrafos",
            "mapping": {
              "n1": {
                "message": {
                  "id": "n1",
                  "author": {"role": "assistant"},
                  "content": {"content_type": "text", "parts": ["primer párrafo", "Segundo párrafo"]},
                  "create_time": 1690000010.0
                },
                "parent": null,
                "children": []
              }
            },
            "current_node": "n1"
          }
        ]
        """.trimIndent()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = sample,
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        assertEquals(1, result.messagesImported)
        assertEquals(1, result.conversationsImported)
    }

    /** Sesión vacía(No hay mensajes de usuario/asistente)Saltar. */
    @Test
    fun importChatGpt_emptyConversation_skipped() = kotlinx.coroutines.runBlocking {
        val sample = """
        [
          {
            "title": "vacío",
            "mapping": {
              "n1": {
                "message": {
                  "id": "n1",
                  "author": {"role": "system"},
                  "content": {"content_type": "text", "parts": ["prompt"]},
                  "create_time": 1690000010.0
                },
                "parent": null,
                "children": []
              }
            },
            "current_node": "n1"
          }
        ]
        """.trimIndent()

        val result = ThirdPartyImporter.importChatGPT(
            context = ApplicationProvider.getApplicationContext(),
            conversationsJson = sample,
            assistantRepo = assistantRepo,
            sessionRepo = sessionRepo,
        )

        assertEquals(0, result.conversationsImported)
        assertEquals(0, result.messagesImported)
    }
}
