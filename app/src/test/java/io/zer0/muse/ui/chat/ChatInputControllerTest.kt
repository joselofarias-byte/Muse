package io.zer0.muse.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatInputState
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.PendingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** implementado en la memoria [ChatStateAccessor],Para pruebas de lógica pura del controlador. */
private class FakeAccessor(initial: ChatUiState) : ChatStateAccessor {
    private val state = MutableStateFlow(initial)
    override val snapshot: ChatUiState get() = state.value
    override fun update(transform: (ChatUiState) -> ChatUiState) = state.update(transform)
    override val messagesSnapshot: List<UIMessage> get() = emptyList()
    override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = Unit
    override val coroutineScope: CoroutineScope get() = CoroutineScope(Dispatchers.Unconfined)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatInputControllerTest {

    private val appContext: Context get() = ApplicationProvider.getApplicationContext()

    private fun controller(
        state: ChatUiState,
        onSend: (String, List<String>, String) -> Unit = { _, _, _ -> },
    ): Pair<ChatInputController, FakeAccessor> {
        val accessor = FakeAccessor(state)
        return ChatInputController(accessor, appContext, onSend) to accessor
    }

    @Test
    fun `updateInput does not mark typing as draft`() {
        // v2.6.7: Este es un retroceso directo a los comentarios de los usuarios.——“Lo que escribas aparecerá como un borrador.”.
        // La entrada es entrada, no estado de borrador; sólo el contenido restaurado desde la persistencia cuenta como borrador.
        // La marca de borrador ya no se ilumina,hasDraft Mantenga el valor en la entrada (false).
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "x", hasDraft = false, inputHistoryIndex = 0)),
        )
        controller.updateInput("y")
        assertEquals("y", accessor.snapshot.input)
        assertFalse("El usuario que escribe no debería iluminar los marcadores de borrador", accessor.snapshot.hasDraft)
        assertEquals(null, accessor.snapshot.inputHistoryIndex)
    }

    @Test
    fun `updateInput preserves restored draft flag`() {
        // Borradores restaurados desde la persistencia (hasDraft=true) no debe ser tratado como“nueva entrada”Y lávalo,
        // Los aportes ordinarios tampoco deberían convertirse en borrador.——Ambas partes se ocupan de sus propios asuntos.
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "Borrador restaurado", hasDraft = true)),
        )
        controller.updateInput("Cambios de borrador restaurados")
        assertEquals("Cambios de borrador restaurados", accessor.snapshot.input)
        assertTrue("Todavía un borrador", accessor.snapshot.hasDraft)
    }

    @Test
    fun `updateInput with blank text clears input`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "x", hasDraft = true)),
        )
        controller.updateInput("")
        assertEquals("", accessor.snapshot.input)
        // v2.6.7: Borrar la entrada ya no cambiará la marca del borrador (la vida y muerte del borrador la gestiona la capa de persistencia, no la entrada).
        assertTrue("Borrar texto no restablece la etiqueta de origen del borrador", accessor.snapshot.hasDraft)
    }

    @Test
    fun `enqueuePendingSend moves input and images into queue`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "hello", pendingImages = listOf("img1"))),
        )
        controller.enqueuePendingSend()
        val queue = accessor.snapshot.sendQueue
        assertEquals(1, queue.size)
        assertEquals("hello", queue[0].text)
        assertEquals(listOf("img1"), queue[0].images)
        assertEquals("", accessor.snapshot.input)
        assertTrue(accessor.snapshot.pendingImages.isEmpty())
    }

    @Test
    fun `enqueuePendingSend ignores blank input without images`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "   ", pendingImages = emptyList())),
        )
        controller.enqueuePendingSend()
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
    }

    @Test
    fun `navigateInputHistory walks older then newer`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(input = "", inputHistory = listOf("recent", "older"))),
        )
        controller.navigateInputHistory(-1)
        assertEquals("recent", accessor.snapshot.input)
        assertEquals(0, accessor.snapshot.inputHistoryIndex)
        controller.navigateInputHistory(-1)
        assertEquals("older", accessor.snapshot.input)
        assertEquals(1, accessor.snapshot.inputHistoryIndex)
    }

    @Test
    fun `clearPendingQueue empties queue`() {
        val (controller, accessor) = controller(
            ChatUiState(inputState = ChatInputState(sendQueue = listOf(PendingMessage("a"), PendingMessage("b")))),
        )
        controller.clearPendingQueue()
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
    }

    @Test
    fun `sendPendingSend dequeues and invokes send callback`() {
        var sent: Triple<String, List<String>, String>? = null
        val (controller, accessor) = controller(
            ChatUiState(
                inputState = ChatInputState(sendQueue = listOf(PendingMessage("hi", listOf("p")))),
                sessionState = ChatSessionState(currentSessionId = "s1"),
            ),
            onSend = { t, imgs, sid -> sent = Triple(t, imgs, sid) },
        )
        controller.sendPendingSend(0)
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
        assertEquals(Triple("hi", listOf("p"), "s1"), sent)
    }

    // ── P0-7: cola de espera"Enviar por separado"barandilla ──

    @Test
    fun `sendPendingSend in agent mode uses agent session id`() {
        var sent: Triple<String, List<String>, String>? = null
        val (controller, accessor) = controller(
            ChatUiState(
                inputState = ChatInputState(sendQueue = listOf(PendingMessage("agent msg"))),
                sessionState = ChatSessionState(currentSessionId = "task-session"),
                agentState = io.zer0.muse.ui.ChatAgentState(isAgentMode = true, agentSessionId = "agent-session"),
            ),
            onSend = { t, imgs, sid -> sent = Triple(t, imgs, sid) },
        )
        controller.sendPendingSend(0)
        assertTrue(accessor.snapshot.sendQueue.isEmpty())
        // P0-7: Agent El patrón debe ser enviado a agentSessionId,No cruzar a la sesión de tareas
        assertEquals(Triple("agent msg", emptyList<String>(), "agent-session"), sent)
    }

    @Test
    fun `sendPendingSend while streaming is blocked and message stays queued`() {
        var sent = false
        val (controller, accessor) = controller(
            ChatUiState(
                inputState = ChatInputState(sendQueue = listOf(PendingMessage("queued"))),
                sessionState = ChatSessionState(currentSessionId = "s1"),
                streamState = io.zer0.muse.ui.ChatStreamState(isStreaming = true),
            ),
            onSend = { _, _, _ -> sent = true },
        )
        controller.sendPendingSend(0)
        // P0-7: Deshabilitado durante la transmisión"Enviar por separado"(cancelará la compilación actual),El mensaje permanece en cola
        assertFalse("El envío solo durante la transmisión no debe activar el envío", sent)
        assertEquals("Los envíos únicos en streaming no se pueden quitar de la cola.", 1, accessor.snapshot.sendQueue.size)
    }
}
