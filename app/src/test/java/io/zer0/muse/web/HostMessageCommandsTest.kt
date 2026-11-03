package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatStreamState
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.ChatViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Comando de escritura a nivel de mensaje:`message.delete` / `message.favorite` / `message.quote`.
 *
 * puerta con `chat.send` La misma regla de enfoque; la eliminación solo afecta los mensajes que realmente existen en la conversación actual.
 * Eliminar duplicados/No se informará ningún error si se realizan cobros repetidos.
 */
class HostMessageCommandsTest {

    private val workDir = Files.createTempDirectory("host-message-commands").toFile()
    private val imageDir = File(workDir, "muse_images").apply { mkdirs() }
    private val voiceDir = File(workDir, "voice_messages").apply { mkdirs() }

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
    }

    private class Harness(
        val gateway: HostWebSocketGateway,
        val chat: ChatViewModel,
        val repo: SessionRepository,
        val messages: MutableStateFlow<List<UIMessage>>,
        val favorites: MutableMap<String, Boolean>,
    )

    private fun harness(
        initial: List<UIMessage>,
        streaming: Boolean = false,
        entities: Map<String, MessageEntity> = emptyMap(),
    ): Harness {
        val chat = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow(initial)
        val favorites = mutableMapOf<String, Boolean>()
        every { chat.state } returns MutableStateFlow(
            ChatUiState(
                sessionState = ChatSessionState(currentSessionId = SESSION_ID),
                streamState = ChatStreamState(isStreaming = streaming),
            ),
        )
        every { chat.messages } returns messagesFlow
        // Haga que la lista sea realmente corta después de la eliminación; de lo contrario, no se podrá probar la idempotencia de "eliminar el mismo lote nuevamente".
        every { chat.deleteMessages(any()) } answers {
            val ids = firstArg<List<kotlin.uuid.Uuid>>().map { it.toString() }.toSet()
            messagesFlow.value = messagesFlow.value.filterNot { it.id.toString() in ids }
        }
        every { chat.setMessageFavorite(any(), any()) } answers {
            favorites[firstArg<kotlin.uuid.Uuid>().toString()] = secondArg()
        }
        val repo = mockk<SessionRepository>(relaxed = true)
        coEvery { repo.getMessageById(any()) } answers {
            entities[firstArg<String>()]
        }
        coEvery { repo.getMessageAsUiMessage(any()) } answers {
            initial.firstOrNull { it.id.toString() == firstArg<String>() }
        }
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        return Harness(
            gateway = HostWebSocketGateway(
                chatViewModel = chat,
                generationManager = generations,
                sessionRepo = repo,
                assistantRepo = mockk<AssistantRepository>(relaxed = true),
                settingsRepo = mockk<SettingsRepository>(relaxed = true),
                settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
                mediaLinks = HostMediaLinks(HostMediaStore(imageDir, voiceDir)),
            ),
            chat = chat,
            repo = repo,
            messages = messagesFlow,
            favorites = favorites,
        )
    }

    private fun message(role: MessageRole, content: String): UIMessage =
        UIMessage(id = kotlin.uuid.Uuid.random(), role = role, content = content)

    private fun entity(message: UIMessage, sessionId: String = SESSION_ID): MessageEntity = MessageEntity(
        id = message.id.toString(),
        sessionId = sessionId,
        role = message.role.name,
        content = message.content,
        createdAt = message.createdAt,
    )

    private fun command(type: String, requestId: String, payload: String): String =
        "{\"protocolVersion\":1,\"type\":\"$type\",\"requestId\":\"$requestId\",\"sessionId\":\"$SESSION_ID\"$payload}"

    private fun deleteCommand(requestId: String, ids: List<String>): String {
        val list = ids.joinToString(",") { "\"$it\"" }
        return command("message.delete", requestId, ",\"messageIds\":[$list]")
    }

    private fun favoriteCommand(requestId: String, id: String, favored: Boolean): String =
        command("message.favorite", requestId, ",\"messageId\":\"$id\",\"favored\":$favored")

    // ── 1. enfocar ────────────────────────────────────────────────

    @Test
    fun `delete and favorite without the focus are refused`() = runTest {
        val target = message(MessageRole.ASSISTANT, "Responder")
        val harness = harness(listOf(target), entities = mapOf(target.id.toString() to entity(target)))

        val holder = FakeConnection()
        val holderJob = launch { harness.gateway.serve(holder.session) }
        advanceUntilIdle()
        val other = FakeConnection()
        val otherJob = launch { harness.gateway.serve(other.session) }
        advanceUntilIdle()

        other.incoming.send(Frame.Text(deleteCommand("req-del", listOf(target.id.toString()))))
        other.incoming.send(Frame.Text(favoriteCommand("req-fav", target.id.toString(), true)))
        advanceUntilIdle()

        assertEquals("focus_not_held", other.events.firstOrNull { it.requestId == "req-del" }?.error)
        assertEquals("focus_not_held", other.events.firstOrNull { it.requestId == "req-fav" }?.error)
        verify(exactly = 0) { harness.chat.deleteMessages(any()) }
        verify(exactly = 0) { harness.chat.setMessageFavorite(any(), any()) }
        assertEquals("Cero cambios en el almacenamiento: las novedades siguen ahí", 1, harness.messages.value.size)
        assertTrue(harness.favorites.isEmpty())

        other.incoming.close()
        holder.incoming.close()
        advanceUntilIdle()
        otherJob.join()
        holderJob.join()
    }

    // ── 2. Eliminación por lotes ────────────────────────────────────────────

    @Test
    fun `batch delete reports deleted count and skipped ids`() = runTest {
        val first = message(MessageRole.USER, "uno")
        val second = message(MessageRole.ASSISTANT, "dos")
        val third = message(MessageRole.USER, "tres")
        val harness = harness(listOf(first, second, third))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(deleteCommand("req-del", listOf(first.id.toString(), second.id.toString(), "no-such-id"))),
        )
        advanceUntilIdle()

        val accepted = connection.events.firstOrNull { it.requestId == "req-del" }
        assertNotNull("Se considerará la eliminación: ${connection.events.map { it.type to it.error }}", accepted)
        assertEquals(2, accepted!!.deletedCount)
        assertEquals(listOf("no-such-id"), accepted.skippedMessageIds)
        assertEquals("A Curry solo le debería quedar el tercero", listOf(third.id.toString()), harness.messages.value.map { it.id.toString() })

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 3. El que se genera ──────────────────────────────────────

    @Test
    fun `deleting the streaming reply is refused as a whole`() = runTest {
        val user = message(MessageRole.USER, "pregunta")
        val streaming = message(MessageRole.ASSISTANT, "escribiendo")
        val harness = harness(listOf(user, streaming), streaming = true)

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(deleteCommand("req-del", listOf(user.id.toString(), streaming.id.toString()))),
        )
        advanceUntilIdle()

        val error = connection.events.firstOrNull { it.requestId == "req-del" }
        assertEquals("message_is_streaming", error?.error)
        verify(exactly = 0) { harness.chat.deleteMessages(any()) }
        assertEquals("Todo el lote fue rechazado: ambos artículos siguen ahí.", 2, harness.messages.value.size)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 4. idempotente ────────────────────────────────────────────────

    @Test
    fun `deleting the same ids twice is a no-op the second time`() = runTest {
        val first = message(MessageRole.USER, "uno")
        val second = message(MessageRole.ASSISTANT, "dos")
        val harness = harness(listOf(first, second))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val ids = listOf(first.id.toString(), second.id.toString())
        connection.incoming.send(Frame.Text(deleteCommand("req-1", ids)))
        advanceUntilIdle()
        connection.incoming.send(Frame.Text(deleteCommand("req-2", ids)))
        advanceUntilIdle()

        val firstResult = connection.events.firstOrNull { it.requestId == "req-1" }
        val secondResult = connection.events.firstOrNull { it.requestId == "req-2" }
        assertEquals(2, firstResult?.deletedCount)
        assertEquals("La segunda vez se debe omitir en lugar de informar un error.", 0, secondResult?.deletedCount)
        assertEquals(ids, secondResult?.skippedMessageIds)
        assertNull("Eliminar duplicados sin informar un error", secondResult?.error)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 5. Colección ────────────────────────────────────────────────

    @Test
    fun `favorite flips on and off and on without toggling unexpectedly`() = runTest {
        val target = message(MessageRole.ASSISTANT, "buena frase")
        val harness = harness(listOf(target), entities = mapOf(target.id.toString() to entity(target)))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val id = target.id.toString()
        connection.incoming.send(Frame.Text(favoriteCommand("req-1", id, true)))
        advanceUntilIdle()
        assertEquals("abierto", true, harness.favorites[id])
        connection.incoming.send(Frame.Text(favoriteCommand("req-2", id, false)))
        advanceUntilIdle()
        assertEquals("cerrar", false, harness.favorites[id])
        connection.incoming.send(Frame.Text(favoriteCommand("req-3", id, true)))
        advanceUntilIdle()
        assertEquals("Abrir de nuevo", true, harness.favorites[id])
        // Establecer repetidamente el mismo estado es idempotente: no se informará ningún error si se envía verdadero nuevamente y el valor permanece sin cambios.
        connection.incoming.send(Frame.Text(favoriteCommand("req-4", id, true)))
        advanceUntilIdle()
        assertEquals(true, harness.favorites[id])
        assertNull(connection.events.firstOrNull { it.requestId == "req-4" }?.error)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `favorite on an unknown id reports an error instead of crashing`() = runTest {
        val harness = harness(emptyList())

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(Frame.Text(favoriteCommand("req-fav", UUID.randomUUID().toString(), true)))
        advanceUntilIdle()

        assertEquals("message_not_found", connection.events.firstOrNull { it.requestId == "req-fav" }?.error)
        verify(exactly = 0) { harness.chat.setMessageFavorite(any(), any()) }

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    // ── 6. Cotización ────────────────────────────────────────────────

    @Test
    fun `quote returns the content for a message in the current session`() = runTest {
        val target = message(MessageRole.ASSISTANT, "cita original")
        val harness = harness(listOf(target), entities = mapOf(target.id.toString() to entity(target)))

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(command("message.quote", "req-quote", ",\"messageId\":\"${target.id}\"")),
        )
        advanceUntilIdle()

        val quoted = connection.events.firstOrNull { it.requestId == "req-quote" }
        assertEquals("message.quote", quoted?.type)
        assertEquals("cita original", quoted?.content)
        assertEquals(target.id.toString(), quoted?.messageId)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `quote refuses a message from another session`() = runTest {
        val target = message(MessageRole.ASSISTANT, "otras conversaciones")
        val harness = harness(
            listOf(target),
            entities = mapOf(target.id.toString() to entity(target, sessionId = "another-session")),
        )

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        connection.incoming.send(
            Frame.Text(command("message.quote", "req-quote", ",\"messageId\":\"${target.id}\"")),
        )
        advanceUntilIdle()

        val event = connection.events.firstOrNull { it.requestId == "req-quote" }
        assertEquals("message_not_in_current_session", event?.error)
        assertNull("El contenido no debe eliminarse entre sesiones.", event?.content)

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    @Test
    fun `snapshot advertises the message write capabilities`() = runTest {
        val harness = harness(emptyList())

        val connection = FakeConnection()
        val job = launch { harness.gateway.serve(connection.session) }
        advanceUntilIdle()
        val flags = connection.events.firstOrNull { it.type == "state.snapshot" }?.capabilityFlags.orEmpty()

        assertTrue(flags.containsAll(listOf("message_write", "message_multi_select")))

        connection.incoming.close()
        advanceUntilIdle()
        job.join()
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
