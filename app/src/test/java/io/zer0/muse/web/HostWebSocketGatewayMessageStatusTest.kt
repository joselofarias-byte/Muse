package io.zer0.muse.web

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * Lado de comando del estado del nivel de mensaje:`chat.send` Recuperar el ID del mensaje,`chat.retry` Reenvío fallido de la aplicación de Alineación.
 *
 * reglas de enfoque y `chat.send` consistente —— Las conexiones que no mantienen el foco solo pueden obtener eventos de error, no sesiones.
 */
class HostWebSocketGatewayMessageStatusTest {

    private val workDir = Files.createTempDirectory("host-gateway-status").toFile()
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
        val messages: MutableStateFlow<List<UIMessage>>,
    )

    private fun harness(initial: List<UIMessage>): Harness {
        val chat = mockk<ChatViewModel>(relaxed = true)
        val messagesFlow = MutableStateFlow(initial)
        every { chat.state } returns MutableStateFlow(
            ChatUiState(sessionState = ChatSessionState(currentSessionId = SESSION_ID), streamState = ChatStreamState()),
        )
        every { chat.messages } returns messagesFlow
        val generations = mockk<ChatGenerationManager>(relaxed = true)
        every { generations.activeGenerations } returns
            MutableStateFlow<Map<String, ChatGenerationManager.ActiveGeneration>>(emptyMap())
        return Harness(
            gateway = HostWebSocketGateway(
                chatViewModel = chat,
                generationManager = generations,
                sessionRepo = mockk<SessionRepository>(relaxed = true),
                assistantRepo = mockk<AssistantRepository>(relaxed = true),
                settingsRepo = mockk<SettingsRepository>(relaxed = true),
                settingsStrings = HostSettingsStrings { name, _ -> "text:$name" },
                mediaLinks = HostMediaLinks(HostMediaStore(imageDir, voiceDir)),
            ),
            chat = chat,
            messages = messagesFlow,
        )
    }

    /** Deletrear un comando con contexto de sesión;[extra] por [field] Generar para evitar que al JSON escrito a mano le falten comas delimitadoras. */
    private fun command(type: String, requestId: String, extra: String = ""): String =
        "{\"protocolVersion\":1,\"type\":\"$type\",\"requestId\":\"$requestId\",\"sessionId\":\"$SESSION_ID\"$extra}"

    private fun field(name: String, value: String): String = ",\"$name\":\"$value\""

    @Test
    fun `chat send accepts with the id of the message it handed over`() {
        val sent = UIMessage(id = Uuid.random(), role = MessageRole.USER, content = "Hola")
        val previous = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, content = "última ronda")
        val harness = harness(listOf(previous))
        every { harness.chat.send() } answers { harness.messages.value = harness.messages.value + sent }

        runTest {
            val connection = FakeConnection()
            val job = launch { harness.gateway.serve(connection.session) }
            advanceUntilIdle()
            connection.incoming.send(Frame.Text(command("chat.send", "req-send", field("text", "Hola"))))
            advanceUntilIdle()

            val accepted = connection.events.firstOrNull { it.type == "command.accepted" && it.requestId == "req-send" }
            assertNotNull("Se debe aceptar el comando de envío,eventos reales: ${connection.events.map { it.type to it.error }}", accepted)
            assertEquals(SESSION_ID, accepted!!.sessionId)
            assertEquals("command.accepted Los mensajes optimistas de los usuarios adjuntos deberían recuperarse id", sent.id.toString(), accepted.messageId)

            connection.incoming.close()
            advanceUntilIdle()
            job.join()
        }
    }

    @Test
    fun `chat send still accepts when the message id cannot be observed`() {
        val harness = harness(emptyList())

        runTest {
            val connection = FakeConnection()
            val job = launch { harness.gateway.serve(connection.session) }
            advanceUntilIdle()
            connection.incoming.send(Frame.Text(command("chat.send", "req-blind", field("text", "Hola"))))
            advanceUntilIdle()

            val accepted = connection.events.firstOrNull { it.type == "command.accepted" && it.requestId == "req-blind" }
            assertNotNull("send Los pedidos aún deben procesarse cuando no se produzcan nuevos mensajes.: ${connection.events.map { it.type to it.error }}", accepted)
            coVerify(exactly = 1) { harness.chat.send() }

            connection.incoming.close()
            advanceUntilIdle()
            job.join()
        }
    }

    @Test
    fun `chat retry from a connection without the focus is refused`() {
        val last = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, content = "")
        val harness = harness(listOf(UIMessage(id = Uuid.random(), role = MessageRole.USER, content = "Hola"), last))

        runTest {
            val holder = FakeConnection()
            val holderJob = launch { harness.gateway.serve(holder.session) }
            advanceUntilIdle()
            val other = FakeConnection()
            val otherJob = launch { harness.gateway.serve(other.session) }
            advanceUntilIdle()

            other.incoming.send(Frame.Text(command("chat.retry", "req-retry", field("messageId", last.id.toString()))))
            advanceUntilIdle()

            val error = other.events.firstOrNull { it.type == "error" && it.requestId == "req-retry" }
            assertNotNull("Las conexiones que no mantienen el foco deberían recibir eventos de error", error)
            assertEquals("focus_not_held", error!!.error)
            coVerify(exactly = 0) { harness.chat.regenerateLastAssistant() }

            other.incoming.close()
            holder.incoming.close()
            advanceUntilIdle()
            otherJob.join()
            holderJob.join()
        }
    }

    @Test
    fun `chat retry on the failed reply regenerates it`() {
        val last = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, content = "")
        val harness = harness(listOf(UIMessage(id = Uuid.random(), role = MessageRole.USER, content = "Hola"), last))

        runTest {
            val connection = FakeConnection()
            val job = launch { harness.gateway.serve(connection.session) }
            advanceUntilIdle()
            connection.incoming.send(Frame.Text(command("chat.retry", "req-retry", field("messageId", last.id.toString()))))
            advanceUntilIdle()

            val accepted = connection.events.firstOrNull { it.type == "command.accepted" && it.requestId == "req-retry" }
            assertNotNull("El último mensaje del asistente debería poder reenviarse,eventos reales: ${connection.events.map { it.type to it.error }}", accepted)
            assertEquals(last.id.toString(), accepted!!.messageId)
            coVerify(exactly = 1) { harness.chat.regenerateLastAssistant() }

            connection.incoming.close()
            advanceUntilIdle()
            job.join()
        }
    }

    @Test
    fun `chat retry rejects an unknown id and a message that is not the current turn`() {
        val older = UIMessage(id = Uuid.random(), role = MessageRole.USER, content = "antes")
        val olderReply = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, content = "última ronda")
        val latest = UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, content = "esta ronda")
        val harness = harness(listOf(older, olderReply, latest))

        runTest {
            val connection = FakeConnection()
            val job = launch { harness.gateway.serve(connection.session) }
            advanceUntilIdle()
            connection.incoming.send(Frame.Text(command("chat.retry", "req-unknown", field("messageId", "no-such-message"))))
            connection.incoming.send(Frame.Text(command("chat.retry", "req-middle", field("messageId", olderReply.id.toString()))))
            advanceUntilIdle()

            assertEquals("message_not_found", connection.events.firstOrNull { it.requestId == "req-unknown" }?.error)
            assertEquals("retry_unsupported_message", connection.events.firstOrNull { it.requestId == "req-middle" }?.error)
            assertTrue(
                "Los reenvíos rechazados no deben generarse automáticamente",
                connection.events.none { it.type == "command.accepted" && it.requestId in setOf("req-unknown", "req-middle") },
            )
            coVerify(exactly = 0) { harness.chat.regenerateLastAssistant() }

            connection.incoming.close()
            advanceUntilIdle()
            job.join()
        }
    }

    private companion object {
        const val SESSION_ID = "session-1"
    }
}
