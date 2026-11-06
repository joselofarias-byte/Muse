package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Envío de conocimientos/automatización y comportamiento de enfoque de tres comandos de solo lectura en la puerta de enlace.
 *
 * El token central solo bloquea los comandos de escritura: la conexión conectada primero mantiene el foco y la conexión conectada más tarde no puede obtenerlo, pero el comando de solo lectura aún debe poder recibir un recibo.
 * Aquí están las pruebas en este orden. —— Conexión sin foco `knowledge.list` / `knowledge.documents` /
 * `tasks.list` Todos recibieron recibos normales, enviados en la misma conexión. `session.rename` Acabo de recibirlo `focus_not_held`.
 */
class HostCatalogFocusTest {

    private class FakeConnection {
        val incoming = Channel<Frame>(Channel.UNLIMITED)
        val events = mutableListOf<HostEvent>()
        private val mock = mockk<DefaultWebSocketServerSession>(relaxed = true)

        val session: DefaultWebSocketServerSession = mock

        init {
            every { mock.incoming } returns incoming
            coEvery { mock.send(any<Frame>()) } coAnswers {
                val frame = firstArg<Frame>()
                if (frame is Frame.Text) {
                    events += AppJson.decodeFromString<HostEvent>(frame.data.decodeToString())
                }
            }
        }

        suspend fun send(json: String) {
            incoming.send(Frame.Text(json))
        }
    }

    private class FakeKnowledgeSource : HostKnowledgeSource {
        override suspend fun bases(): List<HostKnowledgeBase> = listOf(
            HostKnowledgeBase(id = "kb-1", name = "ley", documentCount = 2, chunkCount = 7, embeddingBackend = "local-keyword-v1"),
        )

        override suspend fun documents(kbId: String, cursor: String?, limit: Int): HostKnowledgeReads.DocumentPage? {
            if (kbId != "kb-1") return null
            val documents = (0 until limit).map { index ->
                HostKnowledgeDocument(id = "doc-$index", title = "title-$index", chunkCount = index, sizeBytes = index.toLong())
            }
            return HostKnowledgeReads.DocumentPage(documents = documents, hasMore = false)
        }
    }

    private class FakeTaskSource : HostTaskSource {
        override suspend fun tasks(): List<HostScheduledTask> = listOf(
            HostScheduledTask(id = "task-1", name = "sesión informativa matutina", cron = "0 9 * * *", enabled = true, nextRunAt = 5_000L),
        )
    }

    private val tempDir = Files.createTempDirectory("host-gateway-catalog").toFile()

    private fun gateway(): HostWebSocketGateway {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(ChatUiState())
        every { chat.messages } returns MutableStateFlow(emptyList())
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        return HostWebSocketGateway(
            chatViewModel = chat,
            generationManager = generations,
            sessionRepo = mockk<SessionRepository>(relaxed = true),
            assistantRepo = mockk<AssistantRepository>(relaxed = true),
            settingsRepo = mockk<SettingsRepository>(relaxed = true),
            settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
            mediaLinks = HostMediaLinks(
                HostMediaStore(
                    imageDir = File(tempDir, "muse_images").apply { mkdirs() },
                    voiceDir = File(tempDir, "voice_messages").apply { mkdirs() },
                ),
            ),
            readSources = HostReadSources(
                knowledge = FakeKnowledgeSource(),
                tasks = FakeTaskSource(),
            ),
        )
    }

    @Test
    fun `knowledge and task reads answer without holding the focus lock`() = runTest {
        val gateway = gateway()
        // La conexión conectada primero mantiene el foco; no se puede acceder a la conexión conectada posteriormente, simulando "otro navegador".
        val holder = FakeConnection()
        val holderJob = launch { gateway.serve(holder.session) }
        advanceUntilIdle()
        val other = FakeConnection()
        val otherJob = launch { gateway.serve(other.session) }
        advanceUntilIdle()

        other.send("""{"protocolVersion":1,"type":"knowledge.list","requestId":"req-kb"}""")
        other.send("""{"protocolVersion":1,"type":"knowledge.documents","requestId":"req-docs","kbId":"kb-1","limit":3}""")
        other.send("""{"protocolVersion":1,"type":"tasks.list","requestId":"req-tasks"}""")
        advanceUntilIdle()

        val knowledge = other.events.single { it.type == HostKnowledgeReads.TYPE_LIST }
        assertEquals("req-kb", knowledge.requestId)
        assertNull(knowledge.error)
        assertEquals("kb-1", knowledge.knowledgeBases.single().id)
        assertEquals(7, knowledge.knowledgeBases.single().chunkCount)

        val documents = other.events.single { it.type == HostKnowledgeReads.TYPE_DOCUMENTS }
        assertEquals("req-docs", documents.requestId)
        assertEquals("kb-1", documents.kbId)
        assertNull(documents.error)
        assertEquals(3, documents.documents.size)
        assertEquals(false, documents.hasMore)

        val tasks = other.events.single { it.type == HostTaskReads.TYPE_LIST }
        assertEquals("req-tasks", tasks.requestId)
        assertNull(tasks.error)
        assertEquals("task-1", tasks.tasks.single().id)
        assertEquals(5_000L, tasks.tasks.single().nextRunAt)

        // Los comandos de escritura emitidos en la misma conexión aún están bloqueados por el bloqueo de enfoque. —— Explique que los tres elementos anteriores no son "el bloqueo no es efectivo" sino "el modo de solo lectura no requiere un bloqueo".
        other.send("""{"protocolVersion":1,"type":"session.rename","requestId":"req-rename","sessionId":"s-1","title":"x"}""")
        advanceUntilIdle()
        val rejected = other.events.first { it.requestId == "req-rename" }
        assertEquals("error", rejected.type)
        assertEquals(HostSessionCommands.ERROR_FOCUS_NOT_HELD, rejected.error)

        holder.incoming.close()
        other.incoming.close()
        advanceUntilIdle()
        holderJob.join()
        otherJob.join()
    }

    @Test
    fun `knowledge documents reports a missing library and rejects a blank kb id`() = runTest {
        val gateway = gateway()
        val browser = FakeConnection()
        val job = launch { gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"knowledge.documents","requestId":"req-miss","kbId":"kb-2"}""")
        advanceUntilIdle()
        val missing = browser.events.first { it.requestId == "req-miss" }
        assertEquals(HostKnowledgeReads.TYPE_DOCUMENTS, missing.type)
        assertEquals(HostKnowledgeReads.ERROR_KB_NOT_FOUND, missing.error)

        browser.send("""{"protocolVersion":1,"type":"knowledge.documents","requestId":"req-blank"}""")
        advanceUntilIdle()
        val rejected = browser.events.first { it.type == "error" && it.requestId == null }
        assertTrue(rejected.error.orEmpty().contains("kbId"))

        browser.incoming.close()
        advanceUntilIdle()
        job.join()
    }
}
