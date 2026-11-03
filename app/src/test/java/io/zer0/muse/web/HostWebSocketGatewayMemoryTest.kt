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
 * Se agregó el envío de comandos de solo lectura en la puerta de enlace.:assistants.detail / memory.list / memory.search.
 *
 * Las reglas de proyección son anuladas por HostReadCommandsTest/HostMemoryReadsTest,Sólo prueba aquí"¿Es correcto el nombre del comando?
 * ¿Cómo caen los parámetros en el recibo? ¿Se tragarán silenciosamente los parámetros faltantes?".
 */
class HostWebSocketGatewayMemoryTest {

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

    private class FakeMemorySource(private val records: List<HostMemoryRecord>) : HostMemorySource {
        override suspend fun entries(assistantId: String?): List<HostMemoryRecord> = records

        override suspend fun search(query: String, assistantId: String?, limit: Int): List<HostMemoryRecord> =
            records.filter { it.content.contains(query) }.take(limit)
    }

    private class Harness(val gateway: HostWebSocketGateway, val assistantRepo: AssistantRepository)

    private val tempDir = Files.createTempDirectory("host-gateway-memory").toFile()

    private fun harness(records: List<HostMemoryRecord> = defaultRecords()): Harness {
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.state } returns MutableStateFlow(ChatUiState())
        every { chat.messages } returns MutableStateFlow(emptyList())
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        val assistantRepo = mockk<AssistantRepository>(relaxed = true)
        coEvery { assistantRepo.getById(any()) } returns null
        return Harness(
            gateway = HostWebSocketGateway(
                chatViewModel = chat,
                generationManager = generations,
                sessionRepo = mockk<SessionRepository>(relaxed = true),
                assistantRepo = assistantRepo,
                settingsRepo = mockk<SettingsRepository>(relaxed = true),
                settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
                mediaLinks = HostMediaLinks(
                    HostMediaStore(
                        imageDir = File(tempDir, "muse_images").apply { mkdirs() },
                        voiceDir = File(tempDir, "voice_messages").apply { mkdirs() },
                    ),
                ),
                readSources = HostReadSources(memory = FakeMemorySource(records)),
            ),
            assistantRepo = assistantRepo,
        )
    }

    @Test
    fun `memory list answers with a projected page`() = runTest {
        val harness = harness()
        val browser = FakeConnection()
        val job = launch { harness.gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"memory.list","requestId":"req-list","limit":2}""")
        advanceUntilIdle()

        val event = browser.events.single { it.type == HostMemoryReads.TYPE_LIST }
        assertEquals("req-list", event.requestId)
        assertEquals(2, event.memory.size)
        assertEquals("3", event.memory.first().id)
        assertEquals(true, event.hasMore)
        assertNull(event.error)

        browser.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `memory search answers with hits and rejects an empty query`() = runTest {
        val harness = harness()
        val browser = FakeConnection()
        val job = launch { harness.gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"memory.search","requestId":"req-search","query":"librería"}""")
        advanceUntilIdle()

        val hit = browser.events.single { it.type == HostMemoryReads.TYPE_SEARCH }
        assertEquals("req-search", hit.requestId)
        assertTrue(hit.memoryHits.isNotEmpty())
        assertTrue(hit.memoryHits.single().snippet.contains("librería"))

        browser.send("""{"protocolVersion":1,"type":"memory.search","requestId":"req-blank","query":"   "}""")
        advanceUntilIdle()

        val error = browser.events.last { it.type == "error" }
        assertTrue("Una consulta vacía debería informar un error de protocolo: ${error.error}", error.error!!.contains("memory.search requires query"))

        browser.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `assistant detail for an unknown assistant reports assistant_not_found`() = runTest {
        val harness = harness()
        val browser = FakeConnection()
        val job = launch { harness.gateway.serve(browser.session) }
        advanceUntilIdle()

        browser.send("""{"protocolVersion":1,"type":"assistants.detail","requestId":"req-detail","assistantId":"ghost"}""")
        advanceUntilIdle()

        val event = browser.events.single { it.type == HostReadCommands.TYPE_ASSISTANT_DETAIL }
        assertEquals("req-detail", event.requestId)
        assertEquals(HostReadCommands.ERROR_ASSISTANT_NOT_FOUND, event.error)
        assertNull(event.assistantDetail)

        browser.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    private companion object {
        /** id Cuanto más grande, más nuevo:3 El último número,2 cinturón de cuerno"librería",1 No. es una entrada normal. */
        fun defaultRecords(): List<HostMemoryRecord> = listOf(
            HostMemoryRecord(
                id = "1",
                content = "La cafetería a la que suelen acudir los usuarios está abajo.",
                createdAt = "2026-10-30T10:00:00Z",
                source = HostMemorySourceKind.AUTO,
                pinned = false,
            ),
            HostMemoryRecord(
                id = "2",
                content = "A los usuarios les gusta la librería usada en Chengxi.",
                createdAt = "2026-10-31T10:00:00Z",
                source = HostMemorySourceKind.CONVERSATION,
                pinned = true,
            ),
            HostMemoryRecord(
                id = "3",
                content = "El usuario tiene un gato.",
                createdAt = "2026-10-31T11:00:00Z",
                source = HostMemorySourceKind.MANUAL,
                pinned = false,
            ),
        )
    }
}
