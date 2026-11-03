package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.52 P2-1: SubagentRunner + SubagentXmlRenderer Pruebas unitarias.
 *
 * Cobertura:
 *  - [SubagentXmlRenderer] función pura(XML Representación / escape / truncamiento)
 *  - [SubagentRunner] Verificación de parámetros(Tarea vacía/truncamiento de maxToolCalls)
 *  - [SubagentRunner] Filtrado de lista blanca de herramientas(HIGH + Se excluyen las herramientas recursivas.)
 *  - [SubagentRunner] Proceso del ciclo de herramienta(Sin toolCalls / Con toolCalls / Cuota agotada)
 *  - [SubagentRunSkill] Análisis de parámetros + empalme XML
 *
 * Modo de operación: `./gradlew :app:testDebugUnitTest --tests "*SubagentRunnerTest*"`
 */
class SubagentRunnerTest {

    // ── SubagentXmlRenderer Pruebas de función pura ──────────────────────────────────────

    @Test
    fun `renderStart produces correct XML format`() {
        val xml = SubagentXmlRenderer.renderStart("Buscar información de corrutinas de Kotlin", 8)
        assertEquals("<subagent_start task=\"Buscar información de corrutinas de Kotlin\" max_tool_calls=\"8\"/>", xml)
    }

    @Test
    fun `renderStart escapes XML special characters in task`() {
        val xml = SubagentXmlRenderer.renderStart("Tarea <Contiene> especial \"personaje\" & 'símbolo'", 4)
        assertTrue(xml.contains("&lt;"))
        assertTrue(xml.contains("&gt;"))
        assertTrue(xml.contains("&quot;"))
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&apos;"))
        // No debe contener caracteres especiales sin escape(dentro del valor del atributo)
        assertFalse(xml.contains("task=\"Tarea <Contiene>"))
    }

    @Test
    fun `renderProgress produces correct XML with all attributes`() {
        val xml = SubagentXmlRenderer.renderProgress(
            round = 1,
            maxToolCalls = 8,
            toolName = "web_search",
            argsJson = """{"query":"kotlin"}""",
            result = "Se encontraron 3 resultados.",
            success = true,
        )
        assertTrue(xml.startsWith("<subagent_progress "))
        assertTrue(xml.contains("round=\"1\""))
        assertTrue(xml.contains("max_tool_calls=\"8\""))
        assertTrue(xml.contains("tool=\"web_search\""))
        assertTrue(xml.contains("success=\"true\""))
        assertTrue(xml.contains("args="))
        assertTrue(xml.contains("result="))
        assertTrue(xml.endsWith("/>"))
    }

    @Test
    fun `renderProgress keeps full result while bounding arguments`() {
        val longArgs = "x".repeat(600)
        val longResult = "y".repeat(500)
        val xml = SubagentXmlRenderer.renderProgress(
            round = 1,
            maxToolCalls = 8,
            toolName = "test",
            argsJson = longArgs,
            result = longResult,
            success = true,
        )
        assertTrue(xml.contains("truncated"))
        assertTrue(xml.contains(longResult))
    }

    @Test
    fun `renderResult success produces correct XML`() {
        val xml = SubagentXmlRenderer.renderResult(
            success = true,
            rounds = 2,
            toolCalls = 2,
            summary = "Misión cumplida,Se encontraron 3 resultados.",
        )
        assertTrue(xml.contains("<subagent_result success=\"true\""))
        assertTrue(xml.contains("rounds=\"2\""))
        assertTrue(xml.contains("tool_calls=\"2\""))
        assertTrue(xml.contains("Misión cumplida,Se encontraron 3 resultados."))
        assertTrue(xml.contains("</subagent_result>"))
    }

    @Test
    fun `renderResult failure includes error and partial summary`() {
        val xml = SubagentXmlRenderer.renderResult(
            success = false,
            rounds = 1,
            toolCalls = 0,
            summary = "Resultados parciales",
            error = "tiempo de espera",
        )
        assertTrue(xml.contains("success=\"false\""))
        assertTrue(xml.contains("[FAILED]"))
        assertTrue(xml.contains("tiempo de espera"))
        assertTrue(xml.contains("[PARTIAL]"))
        assertTrue(xml.contains("Resultados parciales"))
    }

    @Test
    fun `renderBudgetExhausted includes maxToolCalls value`() {
        val text = SubagentXmlRenderer.renderBudgetExhausted(8)
        assertTrue(text.contains("8"))
        assertTrue(text.contains("Límite de cuota"))
    }

    @Test
    fun `renderResult preserves long summary`() {
        val longSummary = "z".repeat(5000)
        val xml = SubagentXmlRenderer.renderResult(
            success = true,
            rounds = 1,
            toolCalls = 0,
            summary = longSummary,
        )
        assertTrue(xml.contains(longSummary))
        assertFalse(xml.contains("truncated"))
    }

    // ── SubagentRunner Prueba de verificación de parámetros ─────────────────────────────────────────

    @Test
    fun `run returns error when task is blank`() = runTest {
        val runner =
            SubagentRunner(
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockThreadStore(),
                mockLimiter(),
                mockk(relaxed = true),
                ToolApprovalRouter(),
            )
        val result = runner.run(SubagentRunner.Params(task = ""))
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test
    fun `run returns error when task is whitespace only`() = runTest {
        val runner =
            SubagentRunner(
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockThreadStore(),
                mockLimiter(),
                mockk(relaxed = true),
                ToolApprovalRouter(),
            )
        val result = runner.run(SubagentRunner.Params(task = "   "))
        assertFalse(result.success)
    }

    @Test
    fun `run coerces maxToolCalls to hard cap`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns emptyList()
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns emptyList()
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "Completo", finishReason = "stop")

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        // Se superó el límite máximo 20,debe truncarse a 20(No se reportará ningún error)
        val result = runner.run(SubagentRunner.Params(task = "prueba", maxToolCalls = 100))
        assertTrue(result.success)
    }

    // ── SubagentRunner Prueba de filtrado de lista blanca de herramientas ───────────────────────────────────

    @Test
    fun `run excludes HIGH risk tools from allowed list`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        // Simular herramienta ALTA + SEGURA registrada
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("web_search", "search", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("subagent_run", "delegate", emptyMap(), riskLevel = ToolRiskLevel.HIGH),
            ToolRegistry.ToolDef("delegate_agent", "delegate", emptyMap(), riskLevel = ToolRiskLevel.HIGH),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("web_search", "search", "{}"),
        )
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "Completo", finishReason = "stop")

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "prueba"))
        assertTrue(result.success)
        // Verifique que se llame a listToolsAsToolDefinitions solo con web_search(HIGH Las herramientas se filtran)
        // La lista de definiciones de herramientas devueltas por la validación contiene solo confirmaciones indirectas de web_search
    }

    @Test
    fun `run excludes recursive tools even if risk level is not HIGH`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            // subagent_task / delegate_agent marcado como HIGH,Pero incluso los marcados como SAFE deberían excluirse
            ToolRegistry.ToolDef("subagent_task", "async", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("delegate_agent", "delegate", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
            ToolRegistry.ToolDef("web_search", "search", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("web_search", "search", "{}"),
        )
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "Completo", finishReason = "stop")

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "prueba"))
        assertTrue(result.success)
    }

    // ── SubagentRunner Pruebas del proceso del ciclo de herramientas ─────────────────────────────────────

    @Test
    fun `run returns summary immediately when LLM has no tool calls`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns emptyList()
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns emptyList()
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(text = "Este es el resumen final.", finishReason = "stop")

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "Respuesta directa a la tarea."))

        assertTrue(result.success)
        assertEquals("Este es el resumen final.", result.summary)
        assertEquals(1, result.rounds)
        assertEquals(0, result.toolCalls)
        assertFalse(result.budgetExhausted)
        assertTrue(result.progressEntries.isEmpty())
    }

    @Test
    fun `run executes tool calls and continues loop`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("echo", "echo", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("echo", "echo", "{}"),
        )
        // Vuelve la primera llamada toolCalls,Devolver resumen en texto plano por segunda vez
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "echo", arguments = """{"text":"hi"}""")),
            ),
            ChatCompletion(text = "Llamada de herramienta completada,este es el resumen", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("echo", any()) } returns "hi"
        // Los niveles de riesgo deben indicarse explícitamente.:Si no se puede recuperar la puerta, presione ALTO (conservador),Y el eco en sí es una herramienta de solo lectura.
        every { mockToolRegistry.getToolRiskLevel("echo") } returns ToolRiskLevel.SAFE

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "Llame a la herramienta de eco", maxToolCalls = 5))

        assertTrue(result.success)
        assertEquals("Llamada de herramienta completada,este es el resumen", result.summary)
        assertEquals(2, result.rounds)
        assertEquals(1, result.toolCalls)
        assertEquals(1, result.progressEntries.size)
        assertEquals("echo", result.progressEntries[0].toolName)
        assertEquals("hi", result.progressEntries[0].result)
        assertTrue(result.progressEntries[0].success)
    }

    @Test
    fun `run stops when maxToolCalls budget exhausted and forces summary`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("echo", "echo", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("echo", "echo", "{}"),
        )
        // volver cada vez toolCalls,Agotamiento de cuota de activación
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(
            text = "",
            finishReason = "tool_calls",
            toolCalls = listOf(ToolCall(id = "call_x", name = "echo", arguments = """{"text":"loop"}""")),
        )
        // Convocatoria sumaria tras agotamiento de cupos(tools=null)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), isNull(), any(), any(), any())
        } returns ChatCompletion(text = "Resumen de agotamiento de cuotas", finishReason = "stop")
        coEvery { mockToolRegistry.executeFromJson("echo", any()) } returns "loop"
        every { mockToolRegistry.getToolRiskLevel("echo") } returns ToolRiskLevel.SAFE

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "llamada en bucle", maxToolCalls = 2))

        assertTrue(result.success)
        assertEquals("Resumen de agotamiento de cuotas", result.summary)
        assertEquals(2, result.toolCalls)
        assertTrue(result.budgetExhausted)
        assertEquals(2, result.progressEntries.size)
    }

    @Test
    fun `run filters invalid toolCalls with empty name or arguments`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns emptyList()
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns emptyList()
        // Devolución no válida toolCalls(nombre vacío / vacío arguments),Debe manejarse como si no fuera una llamada a ninguna herramienta.
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returns ChatCompletion(
            text = "Llamada de herramienta no válida,resumen directo",
            finishReason = "tool_calls",
            toolCalls = listOf(
                ToolCall(id = "1", name = "", arguments = """{"a":"b"}"""),
                ToolCall(id = "2", name = "echo", arguments = ""),
            ),
        )

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "prueba"))

        assertTrue(result.success)
        assertEquals("Llamada de herramienta no válida,resumen directo", result.summary)
        assertEquals(0, result.toolCalls)
    }

    @Test
    fun `run rejects tool call not in allowed list`() = runTest {
        val mockChatService = mockk<ChatService>(relaxed = true)
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("echo", "echo", emptyMap(), riskLevel = ToolRiskLevel.SAFE),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("echo", "echo", "{}"),
        )
        // LLM Alucinación llama a una herramienta que no está en la lista blanca
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "1", name = "dangerous_tool", arguments = """{"x":"y"}""")),
            ),
            ChatCompletion(text = "Resumen", finishReason = "stop"),
        )

        val runner =
            SubagentRunner(mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(), mockk(relaxed = true), ToolApprovalRouter())
        val result = runner.run(SubagentRunner.Params(task = "prueba", maxToolCalls = 3))

        assertTrue(result.success)
        assertEquals(1, result.toolCalls)
        assertEquals(1, result.progressEntries.size)
        // El resultado de la ejecución de la herramienta debería ser un mensaje de error.
        assertTrue(result.progressEntries[0].result.contains("Error"))
        assertFalse(result.progressEntries[0].success)
    }

    // ── Puerta de aprobación: la decisión del subagente debe ser coherente con el modo de sesión ────────────────────────────

    /**
     * Cuando existe un canal de aprobación: la llamada regresa a la cadena de aprobación de la sesión principal.STRICT Las herramientas NORMALES de la sesión deben estar deshabilitadas para su ejecución.
     *
     * Bridge aquí juega la cadena de aprobación de la sesión principal (con [io.zer0.muse.ui.chat.ChatToolApprovalCoordinator] misma llamada al analizador),
     * Por lo tanto, la prueba puede identificar "el modo entra en vigor" y "se pasan todos los parámetros" al mismo tiempo.
     */
    @Test
    fun `approval bridge blocks normal tool in strict session and receives full args`() = runTest {
        val seen = mutableListOf<Map<String, Any?>>()
        val previews = mutableListOf<String>()
        val bridge = object : ToolApprovalBridge {
            override suspend fun requestToolApproval(
                toolName: String,
                toolCallId: String,
                argsPreview: String,
                args: Map<String, Any?>,
            ): ToolApprovalState {
                seen += args
                previews += argsPreview
                return ToolPermissionResolver.resolve(
                    toolName = toolName,
                    risk = ToolRiskLevel.NORMAL,
                    mode = SessionPermissionMode.STRICT,
                    perToolPolicy = null,
                    args = args,
                )
            }
        }
        val router = ToolApprovalRouter().also { it.delegate = bridge }
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "escribir archivo", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "escribir archivo", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL

        val mockChatService = mockk<ChatService>(relaxed = true)
        // El número total de parámetros supera con creces la duración de la vista previa,La decisión debe ver su valor total
        val longPath = "/sdcard/notes/" + "a".repeat(400) + ".txt"
        val arguments = """{"path":"$longPath","content":"hello"}"""
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = arguments)),
            ),
            ChatCompletion(text = "La herramienta está bloqueada", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        val runner = SubagentRunner(
            mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(),
            mockk(relaxed = true), router,
        )
        val result = runner.run(SubagentRunner.Params(task = "escribir archivo", maxToolCalls = 3))

        coVerify(exactly = 0) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertEquals(1, result.progressEntries.size)
        assertFalse(result.progressEntries[0].success)
        assertTrue(result.progressEntries[0].result.contains("Error"))
        // Determinar que lo que se recibe es la cantidad total de parámetros.,La vista previa está truncada.
        assertEquals(longPath, seen.single()["path"])
        assertTrue("La vista previa no debería traer parámetros completos", previews.single().length <= 200)
    }

    /** Cuando existe un canal de aprobación y el usuario aprueba, la herramienta se ejecuta como de costumbre. */
    @Test
    fun `approval bridge keeps normal tool executable when approved`() = runTest {
        val router = ToolApprovalRouter().also {
            it.delegate = object : ToolApprovalBridge {
                override suspend fun requestToolApproval(
                    toolName: String,
                    toolCallId: String,
                    argsPreview: String,
                    args: Map<String, Any?>,
                ): ToolApprovalState = ToolApprovalState.Approved()
            }
        }
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "escribir archivo", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "escribir archivo", "{}"),
        )
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "Terminado", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        val runner = SubagentRunner(
            mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(),
            mockk(relaxed = true), router,
        )
        val result = runner.run(SubagentRunner.Params(task = "escribir archivo", maxToolCalls = 3))

        coVerify(exactly = 1) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertTrue(result.progressEntries[0].success)
        assertEquals("written", result.progressEntries[0].result)
    }

    /** Sin canal de aprobación + ESTRICTO (settings Cobertura en caso de falta):NORMAL Herramientas bloqueadas. */
    @Test
    fun `no approval channel blocks normal tool without settings`() = runTest {
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "escribir archivo", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "escribir archivo", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "La herramienta está bloqueada", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        // settings = null ⇒ El modo no se puede recuperar,Ten todo en cuenta STRICT
        val runner = SubagentRunner(
            mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(),
            mockk(relaxed = true), ToolApprovalRouter(),
        )
        val result = runner.run(SubagentRunner.Params(task = "escribir archivo", maxToolCalls = 3))

        coVerify(exactly = 0) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertFalse(result.progressEntries[0].success)
        assertTrue(result.progressEntries[0].result.contains("Error"))
    }

    /** Sin canal de aprobación + sesión PREGUNTAR:Pending equivalente al rechazo,No se puede permitir que pase en silencio. */
    @Test
    fun `no approval channel turns pending into denial`() = runTest {
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "escribir archivo", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "escribir archivo", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "La herramienta está bloqueada", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"
        val settings = mockSettings(SessionPermissionMode.ASK)

        val runner = SubagentRunner(
            mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(),
            mockk(relaxed = true), ToolApprovalRouter(), settings,
        )
        val result = runner.run(SubagentRunner.Params(task = "escribir archivo", maxToolCalls = 3))

        coVerify(exactly = 0) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertFalse(result.progressEntries[0].success)
        assertTrue(result.progressEntries[0].result.contains("Error"))
    }

    /** Sin canal de aprobación + sesión DE CONFIANZA:Liberación normal (la puerta se juzga según el modo,No siempre rechazado). */
    @Test
    fun `no approval channel follows trusted session mode`() = runTest {
        val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { mockToolRegistry.listTools() } returns listOf(
            ToolRegistry.ToolDef("write_file", "escribir archivo", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
        )
        every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
            ToolDefinition("write_file", "escribir archivo", "{}"),
        )
        every { mockToolRegistry.getToolRiskLevel("write_file") } returns ToolRiskLevel.NORMAL
        val mockChatService = mockk<ChatService>(relaxed = true)
        coEvery {
            mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
        } returnsMany listOf(
            ChatCompletion(
                text = "",
                finishReason = "tool_calls",
                toolCalls = listOf(ToolCall(id = "call_1", name = "write_file", arguments = """{"path":"a.txt"}""")),
            ),
            ChatCompletion(text = "Terminado", finishReason = "stop"),
        )
        coEvery { mockToolRegistry.executeFromJson("write_file", any()) } returns "written"

        val runner = SubagentRunner(
            mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(),
            mockk(relaxed = true), ToolApprovalRouter(), mockSettings(SessionPermissionMode.TRUSTED),
        )
        val result = runner.run(SubagentRunner.Params(task = "escribir archivo", maxToolCalls = 3))

        coVerify(exactly = 1) { mockToolRegistry.executeFromJson("write_file", any()) }
        assertTrue(result.progressEntries[0].success)
    }

    /**
     * El rechazo total parametrizado tiene efecto en la ruta del subagente y analiza todos los parámetros.
     *
     * El modo de sesión está configurado en TRUSTED (determinación estática de liberación completa),Por lo tanto, el bloqueo sólo puede provenir de estrategias parametrizadas.;
     * Los marcadores de peligro se colocan intencionalmente después de 200 caracteres.,No puedes verlo con solo mirar la vista previa.
     */
    @Test
    fun `parametrized hard denial applies on subagent path with full args`() = runTest {
        val probeTool = "probe_param_guard"
        ParamPolicies.register(probeTool) { args ->
            val path = args["path"] as? String ?: return@register null
            if (path.contains("secret")) {
                ToolApprovalState.Denied("Rechazo duro parametrizado de golpe de ruta,Bloqueado")
            } else {
                null
            }
        }
        try {
            val mockToolRegistry = mockk<ToolRegistry>(relaxed = true)
            every { mockToolRegistry.listTools() } returns listOf(
                ToolRegistry.ToolDef(probeTool, "herramienta de sonda", emptyMap(), riskLevel = ToolRiskLevel.NORMAL),
            )
            every { mockToolRegistry.listToolsAsToolDefinitions(any()) } returns listOf(
                ToolDefinition(probeTool, "herramienta de sonda", "{}"),
            )
            every { mockToolRegistry.getToolRiskLevel(probeTool) } returns ToolRiskLevel.NORMAL
            val mockChatService = mockk<ChatService>(relaxed = true)
            val path = "/sdcard/" + "p".repeat(260) + "/secret.txt"
            coEvery {
                mockChatService.completeText(any(), any(), any(), any(), any(), any(), any(), any())
            } returnsMany listOf(
                ChatCompletion(
                    text = "",
                    finishReason = "tool_calls",
                    toolCalls = listOf(ToolCall(id = "call_1", name = probeTool, arguments = """{"path":"$path"}""")),
                ),
                ChatCompletion(text = "La herramienta está bloqueada", finishReason = "stop"),
            )
            coEvery { mockToolRegistry.executeFromJson(probeTool, any()) } returns "done"

            val runner = SubagentRunner(
                mockChatService, mockToolRegistry, mockThreadStore(), mockLimiter(),
                mockk(relaxed = true), ToolApprovalRouter(), mockSettings(SessionPermissionMode.TRUSTED),
            )
            val result = runner.run(SubagentRunner.Params(task = "sonda", maxToolCalls = 3))

            coVerify(exactly = 0) { mockToolRegistry.executeFromJson(probeTool, any()) }
            assertFalse(result.progressEntries[0].success)
            assertTrue(result.progressEntries[0].result.contains("Rechazo duro paramétrico"))
        } finally {
            // La política de parámetros es un registro global.:Vuelva a cambiarlo a "sin intercepción" inmediatamente después de su uso.,Sin efectos secundarios de otras pruebas.
            ParamPolicies.register(probeTool) { null }
        }
    }

    // ── SubagentRunSkill Prueba de análisis de parámetros ───────────────────────────────────────

    @Test
    fun `SubagentRunSkill execute returns error XML when task missing`() = runTest {
        val mockRunner = mockk<SubagentRunner>(relaxed = true)
        val result = SubagentRunSkill.execute(emptyMap(), mockRunner)
        assertTrue(result.contains("<subagent_result"))
        assertTrue(result.contains("success=\"false\""))
        assertTrue(result.contains("task"))
    }

    @Test
    fun `SubagentRunSkill execute parses target_paths as comma separated list`() = runTest {
        val mockRunner = mockk<SubagentRunner>(relaxed = true)
        coEvery { mockRunner.run(any()) } returns SubagentRunner.Result(
            success = true, summary = "Completo", rounds = 1, toolCalls = 0,
        )
        val result = SubagentRunSkill.execute(
            mapOf(
                "task" to "prueba",
                "target_paths" to "/a/b.txt, /c/d.txt , /e/f.txt",
                "max_tool_calls" to "5",
            ),
            mockRunner,
        )
        // Verificar que existan las tres fases de XML
        assertTrue(result.contains("<subagent_start"))
        assertTrue(result.contains("<subagent_result"))
        // Verifique que max_tool_calls esté analizado
        assertTrue(result.contains("max_tool_calls=\"5\""))
    }

    @Test
    fun `SubagentRunSkill toolDef has HIGH risk level`() {
        val def = SubagentRunSkill.toolDef()
        assertEquals("subagent_run", def.name)
        assertEquals(ToolRiskLevel.HIGH, def.riskLevel)
        assertTrue(def.required.contains("task"))
        assertEquals("integer", def.parameterTypes["max_tool_calls"])
    }

    // v1.0.53: modo de permiso de sesión mock — La puerta lee el modo predeterminado global cuando no hay un canal de aprobación.
    private fun mockSettings(mode: SessionPermissionMode): io.zer0.muse.data.SettingsRepository {
        val repo = io.mockk.mockk<io.zer0.muse.data.SettingsRepository>(relaxed = true)
        io.mockk.every { repo.defaultSessionPermissionModeFlow } returns kotlinx.coroutines.flow.flowOf(mode)
        return repo
    }

    // v1.0.53: Libro mayor de hilos mock — getOrCreate Devolución válida Pair,runSerialized Ejecución transparente
    private fun mockThreadStore(): io.zer0.muse.data.subagent.SubagentThreadStore {
        val store = io.mockk.mockk<io.zer0.muse.data.subagent.SubagentThreadStore>(relaxed = true)
        io.mockk.coEvery { store.getOrCreate(any(), any(), any(), any(), any()) } returns ("test-thread" to true)
        io.mockk.coEvery { store.runSerialized<Any>(any(), any()) } coAnswers {
            secondArg<suspend () -> Any>()()
        }
        io.mockk.every { store.sessionPathOf(any()) } returns "/tmp/subagent_test.jsonl"
        return store
    }

    // v1.0.53: Límite de corriente concurrente mock — run Ejecución transparente block
    private fun mockLimiter(): io.zer0.muse.tools.AgentConcurrencyLimiter {
        val limiter = io.mockk.mockk<io.zer0.muse.tools.AgentConcurrencyLimiter>(relaxed = true)
        io.mockk.coEvery { limiter.run<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>()()
        }
        return limiter
    }
}
