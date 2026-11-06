package io.zer0.muse.ui.chat

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.StreamRoundParams
import io.zer0.muse.tools.StreamRoundResult
import io.zer0.muse.tools.ToolApprovalState
import io.zer0.muse.tools.ToolLoopHost
import io.zer0.muse.tools.ToolLoopParams
import io.zer0.muse.tools.ToolLoopResult
import io.zer0.muse.tools.ToolOrchestrator
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolResultJudge
import io.zer0.muse.ui.ChatErrorType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * canal de interpolación entre ruedas de herramientas (ToolLoopHost.drainSteeringMessages).
 *
 * Fija tres cosas:
 *  1. Cuando hay una interrupción, se sigue como un mensaje de USUARIO independiente.**Después de todos los resultados de la herramienta**, antes de la próxima ronda de solicitudes;
 *  2. Cuando no hay interrupción (implementación vacía predeterminada),history Concordancia textual con cuando no existe el canal de interjección;
 *  3. Interrumpir sin interrumpir la herramienta de ejecución. —— La recogida se produce una vez completada la ejecución de la herramienta y los resultados se completan como de costumbre.
 */
class ToolLoopSteeringTest {

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context
    private lateinit var accessor: InMemoryChatStateAccessor
    private lateinit var coordinator: ChatTaskCardCoordinator

    @Before
    fun setUp() {
        registry = mockk(relaxed = true)
        skillRepository = mockk(relaxed = true)
        skillExecutor = mockk(relaxed = true)
        every { skillExecutor.getActivePlans() } returns emptyMap()
        assistantRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        accessor = InMemoryChatStateAccessor()
        coordinator = mockk(relaxed = true)
        every { coordinator.isToolResultSuccess(any()) } answers {
            ToolResultJudge.isSuccess(firstArg())
        }
        every { coordinator.markTaskStepRunningIfNotPaused(any(), any(), any(), any()) } returns true
    }

    private fun orchestrator() = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
    )

    private fun params() = ToolLoopParams(
        sessionId = SESSION_ID,
        initialAssistantId = Uuid.random(),
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

    private fun toolRound(vararg ids: String): StreamRoundResult.Success {
        val calls = ids.map { ToolCall(it, "calculator", """{"expression":"1+2"}""") }
        return StreamRoundResult.Success(
            assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = calls),
            hasToolCalls = true,
            contentLength = 0,
            firstTokenTime = 0L,
        )
    }

    private fun finalRound(): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "done"),
        hasToolCalls = false,
        contentLength = 0,
        firstTokenTime = 0L,
    )

    /**
     * @param steering Cada drenaje devuelve los elementos a su vez (List<String> lista), regresa vacío después del agotamiento
     * @param overrideSteering false La implementación predeterminada de ToolLoopHost se utiliza por completo (no anula el canal de barcaza)
     */
    private class SteeringHost(
        private val results: ArrayDeque<StreamRoundResult>,
        steering: List<List<String>> = emptyList(),
        private val overrideSteering: Boolean = true,
        private val onDrain: () -> Unit = {},
    ) : ToolLoopHost {
        val calls = mutableListOf<StreamRoundParams>()
        private val pendingSteering: ArrayDeque<List<String>> = ArrayDeque(steering)
        var drainCount = 0
            private set

        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult {
            calls += params
            return results.removeFirst()
        }

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState = ToolApprovalState.Approved()

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit

        override suspend fun drainSteeringMessages(): List<String> {
            if (!overrideSteering) return super.drainSteeringMessages()
            drainCount++
            onDrain()
            return if (pendingSteering.isEmpty()) emptyList() else pendingSteering.removeFirst()
        }
    }

    private fun steerableProjection(history: List<UIMessage>): List<Triple<MessageRole, String, String?>> =
        history.map { Triple(it.role, it.content, it.toolCallId) }

    @Test
    fun `steering message enters next round as user message after tool results`() = runBlocking {
        val toolExecuted = AtomicInteger(0)
        // Hora de recogida:drain Cuando se llama, la ronda actual de la herramienta debe haber terminado de ejecutarse (la interrupción no interrumpe la herramienta en ejecución).
        var executedAtDrain = -1
        val host =
            SteeringHost(
                results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
                steering = listOf(listOf("Espera, no cambies ese archivo.")),
                onDrain = { executedAtDrain = toolExecuted.get() },
            )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            toolExecuted.incrementAndGet()
            "3"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(1, host.drainCount)
        assertEquals(2, host.calls.size)

        val request = host.calls[1].history
        val lastToolIndex = request.indexOfLast { it.role == MessageRole.TOOL }
        val steeringIndex = request.indexOfLast { it.role == MessageRole.USER && it.content == "Espera, no cambies ese archivo." }
        assertTrue("La interjección debe aparecer en la siguiente ronda de solicitudes.", steeringIndex >= 0)
        assertTrue("Las interjecciones deben ordenarse después de todos los resultados de la herramienta.", steeringIndex > lastToolIndex)
        assertEquals("La interrupción debe caer al final de la solicitud.", request.size - 1, steeringIndex)
        // No se permite que las interjecciones se rompan. assistant(tool_calls) con los resultados de su herramienta
        assertEquals(1, request.count { it.role == MessageRole.TOOL && it.toolCallId == "c1" })
        assertTrue(request.any { it.role == MessageRole.TOOL && it.content == "3" })

        // La herramienta solo se ejecutó una vez y se completó antes de que se tomara la interrupción. —— interviniendo sin interrumpirlo
        assertEquals(1, toolExecuted.get())
        assertEquals(1, executedAtDrain)

        // La interrupción que cae en esta ronda de la historia también es posterior al resultado de la herramienta.
        val historyLastTool = history.indexOfLast { it.role == MessageRole.TOOL }
        val historySteering = history.indexOfLast { it.content == "Espera, no cambies ese archivo." }
        assertTrue(historySteering > historyLastTool)
    }

    @Test
    fun `without steering history is identical to the default empty channel`() = runBlocking {
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }

        val defaultHistory = mutableListOf<UIMessage>()
        val defaultHost = SteeringHost(
            results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
            overrideSteering = false,
        )
        orchestrator().runLoop(params(), defaultHistory, defaultHost, accessor, coordinator)

        val explicitEmptyHistory = mutableListOf<UIMessage>()
        val explicitEmptyHost = SteeringHost(
            results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
            steering = listOf(emptyList()),
        )
        orchestrator().runLoop(params(), explicitEmptyHistory, explicitEmptyHost, accessor, coordinator)

        // Una implementación vacía no produce ningún mensaje adicional: rol/Texto/toolCallId La secuencia es consistente una por una y no hay ningún mensaje de USUARIO.
        assertEquals(steerableProjection(defaultHistory), steerableProjection(explicitEmptyHistory))
        assertFalse(defaultHistory.any { it.role == MessageRole.USER })
        assertEquals(0, defaultHost.drainCount)
        // La siguiente ronda de solicitudes después de la implementación vacía es la misma que antes del cambio:assistant(tool_calls) + TOOL
        val request = defaultHost.calls[1].history
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.TOOL), request.map { it.role })
    }

    @Test
    fun `multiple steering messages keep their order between rounds`() = runBlocking {
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }
        val host = SteeringHost(
            results = ArrayDeque(listOf(toolRound("c1"), finalRound())),
            steering = listOf(listOf("No te muevas todavía utils.kt", "Cambiar a verificación de solo lectura")),
        )

        val history = mutableListOf<UIMessage>()
        val result: ToolLoopResult = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        val users = host.calls[1].history.filter { it.role == MessageRole.USER }.map { it.content }
        assertEquals(listOf("No te muevas todavía utils.kt", "Cambiar a verificación de solo lectura"), users)
    }

    private companion object {
        const val SESSION_ID = "session-steering"
    }
}
