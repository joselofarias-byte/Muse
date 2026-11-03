package io.zer0.muse.tools

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallSanitizer
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import io.zer0.muse.ui.chat.InMemoryChatStateAccessor
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.uuid.Uuid

/**
 * R-TEST-10: ToolOrchestrator La herramienta realiza pruebas de rutas reales.
 *
 * Anule la calculadora/web_search para realizar el reabastecimiento, la terminación del tiempo de espera, 3 fallas consecutivas y la ejecución paralela.
 * Pase mock ToolRegistry + fake ToolLoopHost inyectar,No depende de ChatViewModel.
 */
class ToolOrchestratorRunLoopTest {

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context
    private lateinit var accessor: ChatStateAccessor
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

        val snapshot = mockk<ChatUiState>(relaxed = true)
        every { snapshot.isAgentMode } returns false
        every { snapshot.currentSessionId } returns "session-1"
        every { snapshot.agentSessionId } returns null
        accessor = mockk(relaxed = true)
        every { accessor.snapshot } returns snapshot

        coordinator = mockk(relaxed = true)
        // P5-6: mock Debe reutilizar el determinante de producción. ToolResultJudge.isSuccess(20+ prefijo de falla),
        // No puedes usar "sólo reconocer error/[tiempo de espera]"Sustituto débil de,De lo contrario, los juicios de prueba y producción se desviarán.
        every { coordinator.isToolResultSuccess(any()) } answers {
            ToolResultJudge.isSuccess(firstArg())
        }
        every {
            coordinator.markTaskStepRunningIfNotPaused(any(), any(), any(), any())
        } returns true
    }

    private fun orchestrator(timeoutMs: Long = 120_000L, parallelReadOnlyToolsEnabled: Boolean = true) = ToolOrchestrator(
        toolRegistry = registry,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        assistantRepository = assistantRepository,
        sessionRepository = sessionRepository,
        context = context,
        toolTimeoutMs = timeoutMs,
        parallelReadOnlyToolsEnabled = parallelReadOnlyToolsEnabled,
    )

    private fun params(maxRounds: Int = 5, allowToolExecution: Boolean = true) = ToolLoopParams(
        sessionId = "session-1",
        initialAssistantId = Uuid.random(),
        baseHistorySize = 0,
        maxRounds = maxRounds,
        tools = emptyList(),
        skillMap = emptyMap(),
        model = null,
        providerConfig = null,
        temperature = null,
        maxTokens = null,
        reasoningLevel = ReasoningLevel.OFF,
        allowToolExecution = allowToolExecution,
    )

    private fun toolRound(toolCalls: List<ToolCall>): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = toolCalls,
        ),
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

    /** Una ronda donde los parámetros se truncan por el límite superior de salida:assistant traer toolCalls,Pero esos argumentos son JSON incompletos. */
    private fun truncatedToolRound(toolCalls: List<ToolCall>): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCalls = toolCalls,
        ),
        hasToolCalls = toolCalls.isNotEmpty(),
        contentLength = 0,
        firstTokenTime = 0L,
        toolCallsTruncated = true,
    )

    private class FakeToolLoopHost(
        private val results: ArrayDeque<StreamRoundResult>,
        private val onApproval: (String) -> Unit = {},
    ) : ToolLoopHost {
        val calls = mutableListOf<StreamRoundParams>()

        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult {
            calls += params
            return results.removeFirst()
        }

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState {
            onApproval(toolCallId)
            return ToolApprovalState.Approved()
        }

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit
    }

    @Test
    fun `calculator result is appended to history and loop completes`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("c1", "calculator", """{"expression":"1+2"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(ToolLoopTerminationReason.COMPLETED, result.terminationReason)
        assertEquals(1, result.totalToolCallCount)
        assertEquals(1, history.count { it.role == MessageRole.TOOL })
        assertTrue(history.any { it.role == MessageRole.TOOL && it.content == "3" })
    }

    @Test
    fun `paused tool execution skips side effects and still requests final response`() = runBlocking {
        val executions = AtomicInteger(0)
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("paused-1", "calculator", """{"expression":"1+2"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            executions.incrementAndGet()
            "must not run"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(
            params(allowToolExecution = false),
            history,
            host,
            accessor,
            coordinator,
        )

        assertTrue(result.success)
        assertEquals(0, executions.get())
        assertTrue(host.calls.last().forceFinalResponse)
        assertTrue(history.any { it.role == MessageRole.TOOL && it.content.contains("tool_execution_paused") })
    }

    @Test
    fun `single paused pending tool skips side effects after approval`() = runBlocking {
        val executions = AtomicInteger(0)
        val assistantId = Uuid.random()
        val uiAccessor = InMemoryChatStateAccessor()
        val realCoordinator = ChatTaskCardCoordinator(uiAccessor, registry)
        val host =
            FakeToolLoopHost(
                ArrayDeque(
                    listOf(
                        toolRound(listOf(ToolCall("paused-single", "calculator", """{"expression":"1+2"}"""))),
                        finalRound(),
                    ),
                ),
            ) { toolCallId ->
                assertEquals("paused-single", toolCallId)
                realCoordinator.toggleTaskStepPause(assistantId.toString(), "${assistantId}_0")
            }
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            executions.incrementAndGet()
            "must not run"
        }

        val history = mutableListOf<UIMessage>()
        val result =
            orchestrator().runLoop(
                params().copy(initialAssistantId = assistantId),
                history,
                host,
                uiAccessor,
                realCoordinator,
            )

        assertTrue(result.success)
        assertEquals(0, executions.get())
        assertTrue(history.any { it.role == MessageRole.TOOL && it.content.contains("tool_execution_paused") })
        assertEquals(TaskStepStatus.CANCELLED, uiAccessor.snapshot.taskCards.getValue(assistantId.toString()).steps.single().status)
        assertTrue(
            uiAccessor.snapshot.taskCards.getValue(assistantId.toString()).steps.single().result.contains("suspendido"),
        )
    }

    @Test
    fun `web search result is collected as citation url`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("w1", "web_search", """{"q":"kotlin"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("web_search", any()) } coAnswers { "URL: https://example.com/result" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(listOf("https://example.com/result"), result.citationUrls)
        assertTrue(history.any { it.role == MessageRole.TOOL })
    }

    @Test
    fun `tool timeout terminates execution with timeout message`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("t1", "calculator", """{"expression":"1"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            delay(500)
            "too late"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator(timeoutMs = 20L).runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        val toolMessage = history.first { it.role == MessageRole.TOOL }
        assertTrue(toolMessage.content.startsWith("[tiempo de espera]"))
        assertTrue(toolMessage.content.contains("calculator"))
    }

    @Test
    fun `three consecutive tool failures request a final response`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("f1", "calculator", """{"expression":"1"}"""))),
                    toolRound(listOf(ToolCall("f2", "calculator", """{"expression":"2"}"""))),
                    toolRound(listOf(ToolCall("f3", "calculator", """{"expression":"3"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { """{"error": "boom"}""" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        // Una vez que se destruye la herramienta, los resultados existentes aún deben entregarse al modelo para generar una ronda de respuestas finales sin herramientas.
        assertTrue(result.success)
        assertEquals(ToolLoopTerminationReason.COMPLETED_AFTER_TOOL_FAILURES, result.terminationReason)
        assertEquals(4, host.calls.size)
        assertTrue(host.calls.last().history.count { it.role == MessageRole.TOOL } >= 3)
        assertTrue(host.calls.last().forceFinalResponse)
        assertEquals(4, result.round)
        assertEquals(3, result.totalToolCallCount)
        assertEquals(3, history.count { it.role == MessageRole.TOOL })
        assertTrue(history.filter { it.role == MessageRole.TOOL }.all { it.content.contains("error") })
    }

    @Test
    fun `round limit exit reports ROUND_LIMIT failure`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("r1", "calculator", """{"expression":"1"}"""))),
                    toolRound(listOf(ToolCall("r2", "calculator", """{"expression":"2"}"""))),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "ok" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(maxRounds = 2), history, host, accessor, coordinator)

        assertFalse(result.success)
        assertEquals(ToolLoopTerminationReason.ROUND_LIMIT, result.terminationReason)
        assertNotNull(result.error)
        assertEquals(2, result.round)
        assertEquals(2, result.totalToolCallCount)
        // Una vez agotada la ronda, todavía se inyecta una copia final visible para el usuario.
        assertNotNull(result.finalAssistantMessage)
    }

    @Test
    fun `repeated identical tool calls abort with REPEATED_TOOL_CALLS`() = runBlocking {
        val repeated = listOf(
            toolRound(listOf(ToolCall("d1", "calculator", """{"expression":"1"}"""))),
            toolRound(listOf(ToolCall("d2", "calculator", """{"expression":"1"}"""))),
            // La duplicación de firmas en la ronda 3 alcanza el umbral,La interrupción del circuito se produce antes de la ejecución.
            toolRound(listOf(ToolCall("d3", "calculator", """{"expression":"1"}"""))),
        )
        val host = FakeToolLoopHost(ArrayDeque(repeated))
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "ok" }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertFalse(result.success)
        assertEquals(ToolLoopTerminationReason.REPEATED_TOOL_CALLS, result.terminationReason)
        assertEquals(3, result.round)
        // Ejecución real de las dos primeras rondas.,La tercera ronda fue interceptada por detección de pérdida.(Los recuentos se registran antes de la prueba.,3 veces en total"El modelo emite")
        assertEquals(3, result.totalToolCallCount)
        assertEquals(2, history.count { it.role == MessageRole.TOOL })
    }

    @Test
    fun `tool timeout is mapped to TIMED_OUT task step status`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(listOf(ToolCall("t1", "calculator", """{"expression":"1"}"""))),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            delay(500)
            "too late"
        }

        // Coordinador real + accesor a memoria: verificar el estado final del paso de la tarjeta de tareas(en lugar de solo texto de mensaje de herramienta)
        val uiAccessor = InMemoryChatStateAccessor()
        val realCoordinator = ChatTaskCardCoordinator(uiAccessor, registry)
        val history = mutableListOf<UIMessage>()
        val result = orchestrator(timeoutMs = 20L).runLoop(params(), history, host, uiAccessor, realCoordinator)

        assertTrue(result.success)
        val card = uiAccessor.snapshot.taskCards.values.single()
        assertEquals(1, card.steps.size)
        assertEquals(TaskStepStatus.TIMED_OUT, card.steps.first().status)
        assertTrue(card.hasFailedSteps)
    }

    @Test
    fun `tool calls executed sequentially after B-38`() = runBlocking {
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    toolRound(
                        listOf(
                            ToolCall("p1", "calculator", """{"expression":"1"}"""),
                            ToolCall("p2", "calculator", """{"expression":"2"}"""),
                        ),
                    ),
                    finalRound(),
                ),
            ),
        )
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current, ::max)
            delay(100)
            active.decrementAndGet()
            "ok"
        }

        val history = mutableListOf<UIMessage>()
        // B-38 semántica serial:Desactivar explícitamente el paralelismo de solo lectura(La producción está habilitada de forma predeterminada.,Se puede desactivar mediante la "Función experimental")
        val result = orchestrator(parallelReadOnlyToolsEnabled = false)
            .runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals(2, result.totalToolCallCount)
        assertEquals(2, history.count { it.role == MessageRole.TOOL })
        // B-38: La ejecución de la herramienta cambió a serie.,Eliminar la condición de carrera afterExecute/taskCard/count;Afirmar que ya no es concurrente
        assertEquals(
            "B-38 Las herramientas de publicación deben ejecutarse en serie.,La concurrencia máxima siempre debe ser 1,real=$maxActive",
            1,
            maxActive.get(),
        )
    }

    @Test
    fun `truncated tool call arguments are backfilled with an error result instead of executed`() = runBlocking {
        val executions = AtomicInteger(0)
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    truncatedToolRound(
                        listOf(
                            // Medio JSON: ilegal y no se puede analizar,No se permite ejecutar
                            ToolCall("tc-half", "write_file", """{"path":"a.md","content":"Escribió"""),
                            // El upstream ni siquiera envió la identificación completa.,Al rellenar, debe sintetizar una coincidencia
                            ToolCall("", "calculator", """{"exp"""),
                        ),
                    ),
                    finalRound(),
                ),
            ),
        )
        coEvery { registry.executeFromJson(any(), any()) } coAnswers {
            executions.incrementAndGet()
            "must not run"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertTrue(result.success)
        assertEquals("La rueda de corte no debe tocar la herramienta al ejecutar", 0, executions.get())

        val assistantToolMsg = history.first { !it.toolCalls.isNullOrEmpty() }
        val toolMsgs = history.filter { it.role == MessageRole.TOOL }
        assertEquals(2, toolMsgs.size)
        // Cada llamada de truncamiento tiene un par tool result,Y la identificación no está vacía(De lo contrario, la siguiente solicitud será rechazada por el proveedor.)
        assertEquals(assistantToolMsg.toolCalls!!.map { it.id }, toolMsgs.map { it.toolCallId })
        assertTrue(toolMsgs.all { !it.toolCallId.isNullOrBlank() })
        assertTrue(toolMsgs.all { it.content.contains("arguments_truncated_by_output_limit") })
        // Las llamadas de herramientas del asistente devueltas al proveedor deben ser legales. JSON,Los parámetros incompletos no aparecerán en el historial.
        assertTrue(assistantToolMsg.toolCalls!!.all { ToolCallSanitizer.isValidJson(it.arguments) })
        assertFalse(assistantToolMsg.toolCalls!!.any { it.arguments.contains("a.md") })

        // El resultado del error se envía con la siguiente ronda de solicitudes. —— El modelo puede ser reeditado solo.;El precio es que se comerá una ronda más en este ciclo.
        assertEquals(2, host.calls.size)
        assertEquals(2, result.round)
        assertEquals(
            2,
            host.calls[1].history.count {
                it.role == MessageRole.TOOL && it.content.contains("arguments_truncated_by_output_limit")
            },
        )
    }

    @Test
    fun `truncated round without any tool name falls back to invalid tool calls exit`() = runBlocking {
        val executions = AtomicInteger(0)
        val host = FakeToolLoopHost(
            ArrayDeque(
                listOf(
                    truncatedToolRound(listOf(ToolCall("tc-noname", "", """{"path"""))),
                ),
            ),
        )
        coEvery { registry.executeFromJson(any(), any()) } coAnswers {
            executions.incrementAndGet()
            "must not run"
        }

        val history = mutableListOf<UIMessage>()
        val result = orchestrator().runLoop(params(), history, host, accessor, coordinator)

        assertFalse(result.success)
        assertEquals(0, executions.get())
        // Cuando ni siquiera recibí el nombre de la herramienta, no pude describirla ni compararla.,devolver“No hay convocatoria válida en esta ronda”El camino de cierre existente de,
        // El asistente tool_call sin el resultado de la herramienta no quedará.
        assertEquals(ToolLoopTerminationReason.INVALID_TOOL_CALLS, result.terminationReason)
        assertEquals(0, history.count { it.role == MessageRole.TOOL })
        assertEquals(1, host.calls.size)
        assertNotNull(result.finalAssistantMessage)
    }
}
