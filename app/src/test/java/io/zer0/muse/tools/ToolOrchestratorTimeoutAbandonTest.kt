package io.zer0.muse.tools

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatSessionState
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P2-33: Las herramientas de bloqueo deben cortar la reescritura después del tiempo de espera.
 *
 * escena:El cuerpo de ejecución de la herramienta ignora las interrupciones del subproceso y retrasa el retorno.(WebView / Comportamiento real del bloqueo de IO).
 * La implementación anterior solo hizo `future.cancel(true)`,El resultado es "ilegible" pero aún accesible.;El resultado después de la reparación.
 * [ToolResultGate] publicar,No válido antes del tiempo de espera,Se descartan los resultados tardíos,TaskCard / noticias / toolCallHistory
 * No debe haber rastro de resultados tardíos.,El estado permanece TIMED_OUT y no se sobrescribe por finalizaciones tardías.
 */
class ToolOrchestratorTimeoutAbandonTest {

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        registry = mockk(relaxed = true)
        skillRepository = mockk(relaxed = true)
        skillExecutor = mockk(relaxed = true)
        assistantRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
    }

    /** Accesores compatibles con ChatUiState real: tarjeta de herramientas/toolCallHistory/legibilidad y aserción de mensajes. */
    private class RecordingAccessor : ChatStateAccessor {
        @Volatile
        private var state: ChatUiState = ChatUiState(
            sessionState = ChatSessionState(currentSessionId = "session-1"),
        )

        @Volatile
        var messages: List<UIMessage> = emptyList()
            private set

        override val snapshot: ChatUiState get() = state

        override fun update(transform: (ChatUiState) -> ChatUiState) {
            synchronized(this) { state = transform(state) }
        }

        override val messagesSnapshot: List<UIMessage> get() = messages

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) {
            synchronized(this) { messages = transform(messages) }
        }

        override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    }

    private class FakeToolLoopHost(private val results: ArrayDeque<StreamRoundResult>) : ToolLoopHost {
        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult = results.removeFirst()

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState = ToolApprovalState.Approved()

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit
    }

    private fun orchestrator(timeoutMs: Long) = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
        toolTimeoutMs = timeoutMs,
    )

    private fun params() = ToolLoopParams(
        sessionId = "session-1",
        // turnId No vacío → Registro estructurado de rueda de herramientas(toolCallRecord / ToolRoundEntity)Sólo así quedará en el inventario.
        turnId = "turn-1",
        initialAssistantId = kotlin.uuid.Uuid.random(),
        baseHistorySize = 0,
        maxRounds = 5,
        tools = emptyList(),
        skillMap = emptyMap(),
        model = null,
        providerConfig = null,
        temperature = null,
        maxTokens = null,
        reasoningLevel = ReasoningLevel.OFF,
    )

    private fun toolRound(toolCalls: List<ToolCall>): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = toolCalls),
        hasToolCalls = toolCalls.isNotEmpty(),
        contentLength = 0,
        firstTokenTime = 0L,
    )

    private fun finalRound(): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "done"),
        hasToolCalls = false,
        contentLength = 0,
        firstTokenTime = 0L,
    )

    @Test
    fun `late result of interrupt-ignoring tool is dropped and never pollutes state`() = runBlocking {
        val latePayload = "LATE_PAYLOAD_MUST_NOT_LAND"
        val toolCallId = "t-late-1"
        val returned = CountDownLatch(1)

        // Ignorar interrupciones y retrasar la devolución del cuerpo de ejecución:Simule llamadas de bloqueo ininterrumpidas como WebView
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            val deadline = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    // clave:tragar interrumpir,Continuar la ejecución —— cancel(true) inválido para ello
                }
            }
            returned.countDown()
            latePayload
        }

        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall(toolCallId, "calculator", """{"expression":"1"}"""))),
                    finalRound(),
                ),
            ),
        )
        val accessor = RecordingAccessor()
        val coordinator = ChatTaskCardCoordinator(accessor, registry)
        val orchestrator = orchestrator(timeoutMs = 60L)
        // Registro estructurado de rueda de herramientas(ToolRoundEntity)Soltar captura de biblioteca → Afirmar que toolCallRecord no se ve afectado por resultados tardíos
        val persistedRounds = java.util.Collections.synchronizedList(
            mutableListOf<io.zer0.muse.data.session.ToolRoundEntity>(),
        )
        coEvery { sessionRepository.upsertToolRound(any()) } answers { persistedRounds.add(firstArg()) }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator.runLoop(params(), history, host, accessor, coordinator)

        // 1) La semántica del tiempo de espera persiste:Para LLM/UI es [tiempo de espera] estado final
        val toolMessage = history.first { it.role == MessageRole.TOOL }
        assertTrue(toolMessage.content.startsWith("[tiempo de espera]"))
        assertEquals(ToolOrchestrator.ToolExecStatus.TIMED_OUT.name, result.toolRounds.single().status)
        val cardAtTimeout = accessor.snapshot.taskCards.values.single()
        assertEquals(TaskStepStatus.TIMED_OUT, cardAtTimeout.steps.single().status)

        // 2) La ejecución tardía se agota(ignorar la interrupción),El resultado fue descartado por la puerta.
        assertTrue("Las herramientas de bloqueo aún deberían terminar de ejecutarse después del tiempo de espera", returned.await(5, TimeUnit.SECONDS))
        withTimeout(5_000) {
            while (orchestrator.droppedLateResultCount() == 0) delay(10)
        }
        assertEquals(1, orchestrator.droppedLateResultCount())
        assertTrue("Después del tiempo de espera, el toolCallId", orchestrator.isToolCallAbandoned(toolCallId))

        // 3) Los resultados tardíos no deben escribirse en ningún estado.:Historia / Noticias / toolCallHistory / TaskCard
        assertFalse("Es posible que los resultados tardíos no se ingresen en el historial de conversaciones.", history.any { it.content.contains(latePayload) })
        assertFalse(
            "Los resultados tardíos no deben ingresar al mensaje de la interfaz de usuario",
            accessor.messages.any { msg ->
                msg.content.contains(latePayload) || msg.toolCallInfo?.result?.contains(latePayload) == true
            },
        )
        assertFalse(
            "No se pueden escribir resultados tardíos. toolCallRecord",
            accessor.snapshot.toolCallHistory.any { it.result.contains(latePayload) },
        )
        assertTrue("Las rondas de tiempo de espera deben colocarse en el inventario.", persistedRounds.isNotEmpty())
        assertFalse(
            "Los resultados tardíos no se deben escribir en registros estructurados de rueda de herramientas",
            persistedRounds.any { it.resultJson?.contains(latePayload) == true },
        )
        assertTrue(
            "El estado final al ingresar a la biblioteca debe ser TIMED_OUT",
            persistedRounds.any { it.status == ToolOrchestrator.ToolExecStatus.TIMED_OUT.name },
        )
        val cardAfterLate = accessor.snapshot.taskCards.values.single()
        assertEquals("La finalización tardía no debe anular el estado final TIMED_OUT", TaskStepStatus.TIMED_OUT, cardAfterLate.steps.single().status)
        assertFalse(cardAfterLate.steps.single().result.contains(latePayload))
        assertTrue(cardAfterLate.steps.single().result.contains("tiempo de espera"))
    }

    @Test
    fun `timeout before completion still returns the gate value when tool finishes in time`() = runBlocking {
        // control inverso:No agotado(Ventana de tiempo de espera suficiente)Los resultados se publicarán como de costumbre.,La puerta no mata por error.
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }

        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("t-ok-1", "calculator", """{"expression":"1+2"}"""))),
                    finalRound(),
                ),
            ),
        )
        val accessor = RecordingAccessor()
        val coordinator = ChatTaskCardCoordinator(accessor, registry)
        val orchestrator = orchestrator(timeoutMs = 5_000L)

        val history = mutableListOf<UIMessage>()
        val result = orchestrator.runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals("3", history.first { it.role == MessageRole.TOOL }.content)
        assertEquals(ToolOrchestrator.ToolExecStatus.SUCCESS.name, result.toolRounds.single().status)
        assertEquals(0, orchestrator.droppedLateResultCount())
        assertFalse(orchestrator.isToolCallAbandoned("t-ok-1"))
    }
}
