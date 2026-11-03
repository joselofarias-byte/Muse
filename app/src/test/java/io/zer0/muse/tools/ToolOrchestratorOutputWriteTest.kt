package io.zer0.muse.tools

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
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.ChatUiState
import io.zer0.muse.ui.chat.ChatStateAccessor
import io.zer0.muse.ui.chat.ChatTaskCardCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/**
 * Phase 3: Tiempo de espera de colocación del disco de salida de herramienta/Pruebas de degradación.
 *
 * Portada:
 *  - La salida extra larga se coloca normalmente en el disco.(La ruta + referencia read_file aparece en los resultados de reposición);
 *  - Tiempo de espera de colocación → degradar al truncamiento de la memoria(con recordatorio claro),El bucle de herramientas no está bloqueado por el disco IO;
 *  - Colocación anormal → degradar al truncamiento de la memoria;
 *  - La salida no es demasiado larga y no se cae del disco en absoluto.(Comportamiento predeterminado sin cambios).
 *
 * correr: ./gradlew :app:testDebugUnitTest --tests "*ToolOrchestratorOutputWriteTest*"
 */
class ToolOrchestratorOutputWriteTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var registry: ToolRegistry
    private lateinit var skillRepository: SkillRepository
    private lateinit var skillExecutor: SkillExecutor
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var context: Context
    private lateinit var accessor: ChatStateAccessor
    private lateinit var coordinator: ChatTaskCardCoordinator

    private val bigOutput = "x".repeat(TOOL_OUTPUT_INLINE_FALLBACK_CHARS + 8_000)

    @Before
    fun setUp() {
        registry = mockk(relaxed = true)
        skillRepository = mockk(relaxed = true)
        skillExecutor = mockk(relaxed = true)
        every { skillExecutor.getActivePlans() } returns emptyMap()
        assistantRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.filesDir } returns tempFolder.root

        val snapshot = mockk<ChatUiState>(relaxed = true)
        every { snapshot.isAgentMode } returns false
        every { snapshot.currentSessionId } returns "session-1"
        every { snapshot.agentSessionId } returns null
        accessor = mockk(relaxed = true)
        every { accessor.snapshot } returns snapshot

        coordinator = mockk(relaxed = true)
        every { coordinator.isToolResultSuccess(any()) } answers {
            val result = firstArg<String>()
            !result.contains("error") && !result.startsWith("[tiempo de espera]")
        }
        every {
            coordinator.markTaskStepRunningIfNotPaused(any(), any(), any(), any())
        } returns true
    }

    private fun orchestrator(
        toolOutputWriteTimeoutMs: Long = TOOL_OUTPUT_WRITE_TIMEOUT_MS,
        writer: (suspend (File, String) -> Unit)? = null,
    ) = if (writer == null) {
        ToolOrchestrator(
            toolRegistry = registry,
            skillRepository = skillRepository,
            skillExecutor = skillExecutor,
            assistantRepository = assistantRepository,
            sessionRepository = sessionRepository,
            context = context,
            toolOutputWriteTimeoutMs = toolOutputWriteTimeoutMs,
        )
    } else {
        ToolOrchestrator(
            toolRegistry = registry,
            skillRepository = skillRepository,
            skillExecutor = skillExecutor,
            assistantRepository = assistantRepository,
            sessionRepository = sessionRepository,
            context = context,
            toolOutputWriteTimeoutMs = toolOutputWriteTimeoutMs,
            toolOutputWriter = writer,
        )
    }

    private fun params() = ToolLoopParams(
        sessionId = "session-1",
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

    private fun toolRound(toolCall: ToolCall): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(toolCall)),
        hasToolCalls = true,
        contentLength = 0,
        firstTokenTime = 0L,
    )

    private fun finalRound(): StreamRoundResult.Success = StreamRoundResult.Success(
        assistantMessage = UIMessage(role = MessageRole.ASSISTANT, content = "done"),
        hasToolCalls = false,
        contentLength = 0,
        firstTokenTime = 0L,
    )

    private class FakeToolLoopHost(
        private val results: ArrayDeque<StreamRoundResult>,
        private val availableContextTokens: Int = 0,
    ) : ToolLoopHost {
        override suspend fun streamRound(params: StreamRoundParams): StreamRoundResult = results.removeFirst()

        override suspend fun requestToolApproval(
            toolName: String,
            toolCallId: String,
            argsPreview: String,
            args: Map<String, Any?>,
        ): ToolApprovalState = ToolApprovalState.Approved()

        override fun onToolLoopError(type: ChatErrorType, message: String, recoverable: Boolean) = Unit

        override fun contextBudgetTokens(): Int = availableContextTokens
    }

    private suspend fun runWithOutput(
        toolName: String,
        output: String,
        orchestrator: ToolOrchestrator,
        availableContextTokens: Int = 0,
    ): String {
        val host = FakeToolLoopHost(
            ArrayDeque(listOf(toolRound(ToolCall("o1", toolName, "{}")), finalRound())),
            availableContextTokens,
        )
        coEvery { registry.executeFromJson(toolName, any()) } coAnswers { output }
        val history = mutableListOf<UIMessage>()
        val result = orchestrator.runLoop(params(), history, host, accessor, coordinator)
        assertTrue(result.success)
        return history.single { it.role == MessageRole.TOOL }.content
    }

    @Test
    fun `oversized output is written to disk and referenced in result`() = runBlocking {
        val content = runWithOutput("read_file", bigOutput, orchestrator())

        assertTrue(content.contains("[Resultados completos guardados"))
        assertTrue(content.contains("[El resultado completo se ha guardado en:"))
        assertTrue(content.contains("offset_chars"))
        val files = File(tempFolder.root, TOOL_OUTPUTS_DIR).listFiles()
        assertEquals(1, files?.size)
        assertEquals(bigOutput, files!!.single().readText())
    }

    @Test
    fun `command tool truncation keeps the tail and drops the head`() = runBlocking {
        val progress = "progress line\n".repeat(TOOL_OUTPUT_INLINE_FALLBACK_CHARS / 14 + 20)
        val output = progress + "FAILED: 3 tests failed\nexit=1"

        val content = runWithOutput("terminal_exec", output, orchestrator())

        assertTrue(content.contains("[Resultados completos guardados"))
        assertTrue("La conclusión del comando está al final y debe conservarse.", content.contains("FAILED: 3 tests failed"))
        assertTrue(content.contains("exit=1"))
        assertFalse(
            "El registro de progreso del encabezado no debe ocupar la vista previa.",
            content.contains(progress.take(TOOL_RESULT_PREVIEW_CHARS)),
        )
        assertTrue(content.contains("Vista previa al final de los resultados"))
    }

    @Test
    fun `file read truncation keeps the head`() = runBlocking {
        val output = "HEADER: schema version 3\n" + "x".repeat(TOOL_OUTPUT_INLINE_FALLBACK_CHARS) + "\nTRAILER"

        val content = runWithOutput("read_file", output, orchestrator())

        assertTrue("Los resultados de la clase de archivo son más útiles al principio y deben conservarse", content.contains("HEADER: schema version 3"))
        assertTrue(content.contains("[Resultados completos guardados"))
    }

    @Test
    fun `pagination notice reports total length of a spilled tool result`() = runBlocking {
        val content = runWithOutput("read_file", bigOutput, orchestrator())

        assertTrue(content.contains("total ${bigOutput.length} personaje"))
        assertTrue(content.contains("offset_chars=0"))
        assertTrue(content.contains("length_chars=$TOOL_OUTPUT_READ_PAGE_CHARS"))
    }

    @Test
    fun `large result is passed whole when current context has room`() = runBlocking {
        val output = "Devoluciones de API de usuario".repeat(8_000)
        val content = runWithOutput(
            toolName = "read_file",
            output = output,
            orchestrator = orchestrator(),
            availableContextTokens = 100_000,
        )

        assertEquals(output, content)
        assertFalse(File(tempFolder.root, TOOL_OUTPUTS_DIR).exists())
    }

    @Test
    fun `disk write timeout preserves the complete tool result in the conversation`() = runBlocking {
        val orchestrator = orchestrator(
            toolOutputWriteTimeoutMs = 20L,
            writer = { _, _ -> delay(60_000) },
        )
        val startedAt = System.currentTimeMillis()

        val content = runWithOutput("read_file", bigOutput, orchestrator)

        assertTrue("Se debe conservar el resultado original de la herramienta.", content.startsWith(bigOutput))
        assertTrue("La falta de creación de una copia del documento debe indicarse claramente.", content.contains("Tiempo de espera de colocación"))
        assertFalse(content.contains("[El resultado completo se ha guardado en:"))
        // El bucle de herramientas no está bloqueado por la escritura lenta de 60 segundos(El resultado completo de la memoria se devolverá después de un tiempo de espera de 20 ms.)
        assertTrue(
            "El tiempo de espera para realizar pedidos debe reducirse rápidamente,Consumo de tiempo real=${System.currentTimeMillis() - startedAt}ms",
            System.currentTimeMillis() - startedAt < 5_000,
        )
    }

    @Test
    fun `disk write failure preserves the complete tool result in the conversation`() = runBlocking {
        val orchestrator = orchestrator(
            writer = { _, _ -> throw java.io.IOException("disk full") },
        )

        val content = runWithOutput("read_file", bigOutput, orchestrator)

        assertTrue("Se debe conservar el resultado original de la herramienta.", content.startsWith(bigOutput))
        assertTrue(content.contains("Colocación fallida"))
        assertFalse(content.contains("[El resultado completo se ha guardado en:"))
    }

    @Test
    fun `small output never touches disk`() = runBlocking {
        val orchestrator = orchestrator(
            writer = { _, _ -> throw AssertionError("La salida pequeña no debe escribirse en el disco") },
        )

        val content = runWithOutput("echo", "small", orchestrator)

        assertEquals("small", content)
        assertFalse(File(tempFolder.root, TOOL_OUTPUTS_DIR).exists())
    }
}
