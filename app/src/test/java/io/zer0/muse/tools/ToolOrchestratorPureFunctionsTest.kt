package io.zer0.muse.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.AgentPlanStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2.3.5: ToolOrchestrator Pruebas unitarias funcionales puras.
 *
 * Cobertura(La visibilidad se ha relajado a interna para realizar pruebas.):
 *  - [ToolOrchestrator.extractWebSearchUrls] — Extracto de los resultados de web_search URL
 *  - [ToolOrchestrator.toolCallSignature] — firma de detección atascada(El orden no importa)
 *  - [ToolOrchestrator.mergeToolArguments] — Anulación y fusión de parámetros de etapa de aprobación
 *  - [ToolOrchestrator.parseTaskPlanStepCount] — task_plan arguments Analizando la tolerancia a fallos
 *  - [ToolOrchestrator.countTaskPlanSteps] — Múltiples fuentes(AgentPlan Mensajes de caché/historial)Resumen
 *  - [ToolOrchestrator.computeMaxRounds] — Cálculo de ronda dinámica + hardCap
 *  - [ToolOrchestrator.truncatedToolCallsForBackfill] / [ToolOrchestrator.truncatedArgumentsToolResult]
 *    — Cómo construir contenido de relleno para parámetros de herramientas con truncamiento del límite superior de salida
 *
 * Nota:Sólo se prueban funciones puras,Proceso de rutina que no involucra runLoop(Se puede complementar por separado más adelante. integration test).
 * runLoop Implica múltiples componentes de ChatViewModel/Provider/ToolRegistry,Necesito burlarse de toda la cadena.,
 * Baja relación entrada-salida,Priorizar la corrección de las funciones puras.
 *
 * Modo de operación: `./gradlew :app:testDebugUnitTest --tests "*ToolOrchestratorPureFunctionsTest*"`
 */
class ToolOrchestratorPureFunctionsTest {

    private lateinit var mockSkillExecutor: SkillExecutor
    private lateinit var orchestrator: ToolOrchestrator

    @Before
    fun setUp() {
        mockSkillExecutor = mockk(relaxed = true)
        // Pruebas de función pura:No se llamarán dependencias distintas a SkillExecutor,directo relaxed mock
        orchestrator = ToolOrchestrator(
            toolRegistry = mockk(relaxed = true),
            skillRepository = mockk(relaxed = true),
            skillExecutor = mockSkillExecutor,
            assistantRepository = mockk(relaxed = true),
            sessionRepository = mockk(relaxed = true),
            context = mockk<Context>(relaxed = true),
        )
    }

    // ── extractWebSearchUrls ─────────────────────────────────────────────

    @Test
    fun `extractWebSearchUrls extracts single URL line`() {
        val result = """
            Resultados de la búsqueda:
            URL: https://example.com/article1
            Resumen: Artículo de muestra
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(listOf("https://example.com/article1"), urls)
    }

    @Test
    fun `extractWebSearchUrls extracts multiple URLs in order`() {
        val result = """
            URL: https://a.com/1
            URL: https://b.com/2
            URL: https://c.com/3
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(
            listOf("https://a.com/1", "https://b.com/2", "https://c.com/3"),
            urls,
        )
    }

    @Test
    fun `extractWebSearchUrls handles leading whitespace`() {
        val result = "    URL:   https://spaced.com/path"
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(listOf("https://spaced.com/path"), urls)
    }

    @Test
    fun `extractWebSearchUrls returns empty list when no URL lines`() {
        val result = """
            Búsqueda completada,No se encontraron resultados relevantes.
            Se recomienda utilizar otras palabras clave.
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        assertTrue("Debería devolver una lista vacía cuando no hay filas de URL", urls.isEmpty())
    }

    @Test
    fun `extractWebSearchUrls ignores lines without URL prefix`() {
        val result = """
            URL: https://valid.com/1
            NOT-URL: https://invalid.com/x
            ordinario URL: https://also-invalid.com/y
            URL:https://valid.com/2
        """.trimIndent()
        val urls = orchestrator.extractWebSearchUrls(result)
        // regular ^\s*URL:\s*(.+)$ Solicitar inicio de línea(Permitir espacios en blanco iniciales),coincidencia estricta "URL:" prefijo
        // Línea 4 "URL:https..." Sin espacios entremedio,todavía debería coincidir(regular \s* Permitir 0 espacios)
        assertEquals(
            "Sólo debe coincidir con el comienzo de la línea. 'URL:' prefijo(Ignorar la distinción entre mayúsculas y minúsculas:Regularidad no añadida IGNORE_CASE,Estrictamente mayúsculas)",
            listOf("https://valid.com/1", "https://valid.com/2"),
            urls,
        )
    }

    @Test
    fun `extractWebSearchUrls handles empty input`() {
        assertTrue(orchestrator.extractWebSearchUrls("").isEmpty())
    }

    @Test
    fun `extractWebSearchUrls preserves query string and fragment`() {
        val result = "URL: https://example.com/path?q=hello&lang=en#section"
        val urls = orchestrator.extractWebSearchUrls(result)
        assertEquals(listOf("https://example.com/path?q=hello&lang=en#section"), urls)
    }

    // ── toolCallSignature ───────────────────────────────────────────────

    @Test
    fun `toolCallSignature returns empty string for empty list`() {
        assertEquals("", orchestrator.toolCallSignature(emptyList()))
    }

    @Test
    fun `toolCallSignature returns single call signature`() {
        val tc = ToolCall(id = "call_1", name = "web_search", arguments = """{"q":"kotlin"}""")
        assertEquals(
            """web_search({"q":"kotlin"})""",
            orchestrator.toolCallSignature(listOf(tc)),
        )
    }

    @Test
    fun `toolCallSignature sorts by id ascending`() {
        val tc1 = ToolCall(id = "call_3", name = "c", arguments = "{}")
        val tc2 = ToolCall(id = "call_1", name = "a", arguments = "{}")
        val tc3 = ToolCall(id = "call_2", name = "b", arguments = "{}")
        val sig = orchestrator.toolCallSignature(listOf(tc1, tc2, tc3))
        // Debe ordenarse por identificación: call_1 → call_2 → call_3, Eso es a|b|c
        assertEquals("""a({})|b({})|c({})""", sig)
    }

    @Test
    fun `toolCallSignature is order-independent for same set`() {
        val tc1 = ToolCall(id = "call_1", name = "a", arguments = "{}")
        val tc2 = ToolCall(id = "call_2", name = "b", arguments = "{}")
        val sigOrder1 = orchestrator.toolCallSignature(listOf(tc1, tc2))
        val sigOrder2 = orchestrator.toolCallSignature(listOf(tc2, tc1))
        // El mismo conjunto pero en diferente orden debería tener la misma firma.(Utilizado para la detección de atascos,Evite errores de cálculo por fluctuación de secuencia de herramientas en paralelo)
        assertEquals(
            "El mismo conjunto pero en diferente orden debería tener la misma firma.(Evite las fluctuaciones en la secuencia de herramientas paralelas y los errores de cálculo atascados)",
            sigOrder1,
            sigOrder2,
        )
    }

    @Test
    fun `toolCallSignature distinguishes different arguments`() {
        val tc1 = ToolCall(id = "c1", name = "search", arguments = """{"q":"a"}""")
        val tc2 = ToolCall(id = "c1", name = "search", arguments = """{"q":"b"}""")
        // Nota: id La misma escena aunque no normal.,Pero si los argumentos utilizados para la verificación son diferentes, la firma será diferente.
        val sig1 = orchestrator.toolCallSignature(listOf(tc1))
        val sig2 = orchestrator.toolCallSignature(listOf(tc2))
        assertTrue(
            "La firma debe ser diferente cuando los parámetros son diferentes.(Se utiliza para determinar si está atascado.)",
            sig1 != sig2,
        )
    }

    // ── mergeToolArguments ──────────────────────────────────────────────

    /** Analiza JSON combinado y devuelve el valor de cadena del campo especificado,Dé errores claros cuando las afirmaciones fallen. */
    private fun jsonStringField(json: String, key: String): String? {
        val obj = AppJson.decodeFromString(JsonObject.serializer(), json)
        return obj[key]?.let { (it as? JsonPrimitive)?.content }
    }

    @Test
    fun `mergeToolArguments returns original when overrides empty`() {
        val original = """{"q":"kotlin","limit":10}"""
        val merged = orchestrator.mergeToolArguments(original, emptyMap())
        assertEquals(original, merged)
    }

    @Test
    fun `mergeToolArguments overrides existing key`() {
        val original = """{"q":"kotlin","limit":10}"""
        val merged = orchestrator.mergeToolArguments(original, mapOf("limit" to "5"))
        assertEquals("limit debe ser anulado como 5", "5", jsonStringField(merged, "limit"))
        assertEquals("q debe mantenerse kotlin", "kotlin", jsonStringField(merged, "q"))
    }

    @Test
    fun `mergeToolArguments adds new key when not present`() {
        val original = """{"q":"kotlin"}"""
        val merged = orchestrator.mergeToolArguments(original, mapOf("lang" to "zh"))
        assertEquals("Nuevo lang=zh", "zh", jsonStringField(merged, "lang"))
        assertEquals("Se conserva la q original", "kotlin", jsonStringField(merged, "q"))
    }

    @Test
    fun `mergeToolArguments handles multiple overrides`() {
        val original = """{"q":"kotlin"}"""
        val merged = orchestrator.mergeToolArguments(
            original,
            mapOf("limit" to "5", "lang" to "zh", "safe" to "true"),
        )
        assertEquals("kotlin", jsonStringField(merged, "q"))
        assertEquals("5", jsonStringField(merged, "limit"))
        assertEquals("zh", jsonStringField(merged, "lang"))
        assertEquals("true", jsonStringField(merged, "safe"))
    }

    @Test
    fun `mergeToolArguments falls back to overrides only when original invalid`() {
        val invalidOriginal = "not a valid json"
        val merged = orchestrator.mergeToolArguments(
            invalidOriginal,
            mapOf("q" to "fallback"),
        )
        // Cuando falla el análisis JSON sin formato,Construir nuevo con solo anulaciones JSON
        assertEquals(
            "El nuevo JSON solo debe construirse con anulaciones cuando el JSON original no sea válido JSON",
            "fallback",
            jsonStringField(merged, "q"),
        )
    }

    @Test
    fun `mergeToolArguments handles empty original json`() {
        val merged = orchestrator.mergeToolArguments("{}", mapOf("q" to "value"))
        assertEquals("value", jsonStringField(merged, "q"))
    }

    @Test
    fun `mergeToolArguments preserves non-overridden keys from original`() {
        val original = """{"a":"1","b":"2","c":"3"}"""
        val merged = orchestrator.mergeToolArguments(original, mapOf("b" to "X"))
        assertEquals("1", jsonStringField(merged, "a"))
        assertEquals("X", jsonStringField(merged, "b"))
        assertEquals("3", jsonStringField(merged, "c"))
    }

    // ── parseTaskPlanStepCount ──────────────────────────────────────────

    @Test
    fun `parseTaskPlanStepCount returns count for valid steps array`() {
        val json = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"}]}"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertEquals(3, count)
    }

    @Test
    fun `parseTaskPlanStepCount returns zero for empty steps array`() {
        val json = """{"steps":[]}"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertEquals(0, count)
    }

    @Test
    fun `parseTaskPlanStepCount returns zero when steps field missing`() {
        val json = """{"other":"value"}"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        // Nota: Lógica del código fuente,obj["steps"] cuando es nulo return 0(en lugar de null)
        assertEquals("No se debe devolver ningún campo de pasos 0", 0, count)
    }

    @Test
    fun `parseTaskPlanStepCount returns null for invalid json`() {
        val json = """not a json"""
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertNull("Se debe devolver el JSON ilegal null(tolerancia a fallas)", count)
    }

    @Test
    fun `parseTaskPlanStepCount returns null for empty string`() {
        val count = orchestrator.parseTaskPlanStepCount("")
        assertNull("Se debe devolver una cadena vacía null", count)
    }

    @Test
    fun `parseTaskPlanStepCount handles steps with complex objects`() {
        val json = """
            {
              "steps": [
                {"title": "Buscar", "description": "...", "status": "PENDING"},
                {"title": "Resumen", "description": "...", "status": "PENDING"}
              ]
            }
        """.trimIndent()
        val count = orchestrator.parseTaskPlanStepCount(json)
        assertEquals(2, count)
    }

    // ── countTaskPlanSteps ──────────────────────────────────────────────

    @Test
    fun `countTaskPlanSteps uses active plans from skillExecutor when available`() {
        // SkillExecutor Usar prioridad cuando haya planes activos en la memoria
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "plan1" to AgentPlan(
                id = "plan1",
                title = "P1",
                steps = listOf(
                    AgentPlanStep(id = "s1", title = "a"),
                    AgentPlanStep(id = "s2", title = "b"),
                    AgentPlanStep(id = "s3", title = "c"),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(emptyList())
        assertEquals("Número de pasos activos del plan que se deben priorizar utilizando SkillExecutor", 3, count)
    }

    @Test
    fun `countTaskPlanSteps sums multiple active plans`() {
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "plan1" to AgentPlan(
                id = "plan1",
                title = "P1",
                steps = listOf(AgentPlanStep(id = "s1", title = "a")),
            ),
            "plan2" to AgentPlan(
                id = "plan2",
                title = "P2",
                steps = listOf(
                    AgentPlanStep(id = "s1", title = "a"),
                    AgentPlanStep(id = "s2", title = "b"),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(emptyList())
        assertEquals("Se debe acumular el número de pasos para múltiples planes activos.", 3, count)
    }

    @Test
    fun `countTaskPlanSteps falls back to history messages when no active plans`() {
        // SkillExecutor Cuando no hay ningún plan activo,Analizar llamadas a la herramienta task_plan a partir de mensajes históricos
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"},{"title":"d"}]}""",
                    ),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("Cuando no hay un plan activo, recurrir al análisis histórico de mensajes", 4, count)
    }

    @Test
    fun `countTaskPlanSteps takes max step count across multiple task_plan calls`() {
        // Tome el valor máximo al llamar a la herramienta task_plan en varias rondas(corresponder"Máxima complejidad de planificación")
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"}]}""",
                    ),
                ),
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc2",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"}]}""",
                    ),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("Varias rondas de task_plan deben tomar el número máximo de pasos", 3, count)
    }

    @Test
    fun `countTaskPlanSteps ignores non-task_plan tool calls`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(id = "tc1", name = "web_search", arguments = """{"q":"a"}"""),
                    ToolCall(id = "tc2", name = "read_file", arguments = """{"path":"x"}"""),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("Las llamadas a herramientas que no sean de task_plan no deben contarse", 0, count)
    }

    @Test
    fun `countTaskPlanSteps ignores user role messages with tool_calls`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        // Incluso si el mensaje del usuario lleva el campo tool_calls(La teoría no debería suceder.),ni se debe contar
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"}]}""",
                    ),
                ),
            ),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals("Solo se debe contar task_plan para el rol de ASISTENTE", 0, count)
    }

    @Test
    fun `countTaskPlanSteps returns zero when no plans and no task_plan calls`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "hi"),
            UIMessage(role = MessageRole.ASSISTANT, content = "hello"),
        )
        val count = orchestrator.countTaskPlanSteps(messages)
        assertEquals(0, count)
    }

    // ── computeMaxRounds ────────────────────────────────────────────────

    @Test
    fun `computeMaxRounds returns default when no plan steps`() {
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "Debe usarse cuando no hay task_plan DEFAULT_MAX_TOOL_ROUNDS",
            DEFAULT_MAX_TOOL_ROUNDS,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds returns steps_times_2_plus_5 for small plans`() {
        // 3 paso → 3*2+5 = 11,no más que HARD_CAP(25)
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = listOf(
                    AgentPlanStep(id = "s1", title = "a"),
                    AgentPlanStep(id = "s2", title = "b"),
                    AgentPlanStep(id = "s3", title = "c"),
                ),
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(11, maxRounds)
    }

    @Test
    fun `computeMaxRounds is capped by MAX_TOOL_ROUNDS_HARD_CAP`() {
        // Cuando hay suficientes pasos,El resultado de la fórmula debe ser HARD_CAP(25) Truncar
        // 30 paso → 30*2+5 = 65,pero debe truncarse a 25
        val manySteps = List(30) { idx ->
            AgentPlanStep(id = "s$idx", title = "step-$idx")
        }
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(id = "p1", title = "T", steps = manySteps),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "Los resultados de la fórmula que excedan HARD_CAP deben truncarse a ${MAX_TOOL_ROUNDS_HARD_CAP}",
            MAX_TOOL_ROUNDS_HARD_CAP,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds respects caller-provided hardCap below HARD_CAP`() {
        // Cuando el hardCap pasado por la persona que llama es menor que el valor calculado,Sujeto a límite máximo(compatible con versiones anteriores params.maxRounds)
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val hardCap = 3
        val maxRounds = orchestrator.computeMaxRounds(emptyList(), hardCap = hardCap)
        assertEquals(
            "hardCap Se debe tomar cuando sea menor que el valor predeterminado. hardCap",
            hardCap,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds uses larger of formula and hardCap when hardCap exceeds HARD_CAP`() {
        // Cuando la persona que llama pasa en hardCap mayor que HARD_CAP,Todavía sujeto a HARD_CAP(Bolsillo de circulación anti-muertos)
        // Nota:Cuando no hay task_plan base=DEFAULT_MAX_TOOL_ROUNDS(10),min(10, 25, 100)=10
        // Verificar aquí hardCap > HARD_CAP no será amplificado inversamente por hardCap para 100
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val maxRounds = orchestrator.computeMaxRounds(
            emptyList(),
            hardCap = 100, // Mucho más allá HARD_CAP
        )
        assertEquals(
            "hardCap No se debe amplificar de forma inversa cuando se excede HARD_CAP,Hasta el menor de base y HARD_CAP",
            minOf(DEFAULT_MAX_TOOL_ROUNDS, MAX_TOOL_ROUNDS_HARD_CAP),
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds uses history task_plan when no active plans`() {
        // Cuando el plan activo está vacío pero el mensaje histórico contiene la llamada a la herramienta task_plan,Debe calcularse en función del número de pasos históricos.
        every { mockSkillExecutor.getActivePlans() } returns emptyMap()
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"},{"title":"d"},{"title":"e"}]}""",
                    ),
                ),
            ),
        )
        // 5 paso → 5*2+5 = 15
        val maxRounds = orchestrator.computeMaxRounds(messages)
        assertEquals(15, maxRounds)
    }

    @Test
    fun `computeMaxRounds prefers active plans over history`() {
        // Cuando hay tanto un plan activo como un task_plan histórico,Priorizar planes activos
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = listOf(AgentPlanStep(id = "s1", title = "a")),
            ),
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = "tc1",
                        name = "task_plan",
                        arguments = """{"steps":[{"title":"a"},{"title":"b"},{"title":"c"},{"title":"d"},{"title":"e"}]}""",
                    ),
                ),
            ),
        )
        // Plan activo 1 paso → 1*2+5 = 7,Historia 5 pasos → 5*2+5 = 15
        // Los planes activos deben usarse primero:7
        val maxRounds = orchestrator.computeMaxRounds(messages)
        assertEquals(
            "Cuando ambos existen, se debe usar primero el plan activo de SkillExecutor en lugar de los mensajes históricos.",
            7,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds boundary - single step plan yields 7 rounds`() {
        // frontera:1 paso → 1*2+5 = 7
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = listOf(AgentPlanStep(id = "s1", title = "a")),
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(7, maxRounds)
    }

    @Test
    fun `computeMaxRounds boundary - ten step plan yields hard cap`() {
        // frontera:10 paso → 10*2+5 = 25,exactamente igual a HARD_CAP,no debe desbordarse
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = List(10) { idx -> AgentPlanStep(id = "s$idx", title = "step-$idx") },
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "10 paso → 10*2+5=25,exactamente HARD_CAP,sin desbordamiento",
            25,
            maxRounds,
        )
    }

    @Test
    fun `computeMaxRounds boundary - eleven step plan yields hard cap not 27`() {
        // frontera:11 paso → 11*2+5 = 27,pero debe truncarse a HARD_CAP=25
        every { mockSkillExecutor.getActivePlans() } returns mapOf(
            "p1" to AgentPlan(
                id = "p1",
                title = "T",
                steps = List(11) { idx -> AgentPlanStep(id = "s$idx", title = "step-$idx") },
            ),
        )
        val maxRounds = orchestrator.computeMaxRounds(emptyList())
        assertEquals(
            "11 paso → 27,debe truncarse a HARD_CAP=25",
            25,
            maxRounds,
        )
    }

    @Test
    fun `tool timeout constant is 120 seconds`() {
        assertEquals(120_000L, TOOL_TIMEOUT_MS)
    }

    // ── truncatedToolCallsForBackfill / truncatedArgumentsToolResult ────

    @Test
    fun `truncated tool calls keep name and a matchable id and drop partial arguments`() {
        val calls = orchestrator.truncatedToolCallsForBackfill(
            listOf(
                ToolCall("dup", "read_file", """{"path":"a.md"""),
                // Duplicar con id:El segundo debe ser reemplazado.,De lo contrario, el resultado de la herramienta no puede corresponder uno a uno.
                ToolCall("dup", "read_file", """{"path":"b.md"""),
                // No enviado aguas arriba id
                ToolCall("", "write_file", """{"content":"""),
                // Ni siquiera recibí el nombre de la herramienta.:No hay forma de describirlos o emparejarlos.,Desechar toda la pieza
                ToolCall("tc-x", "", """{"a"""),
            ),
            round = 3,
        )

        assertEquals(listOf("read_file", "read_file", "write_file"), calls.map { it.name })
        assertTrue("id No debe estar vacío.", calls.all { it.id.isNotBlank() })
        assertEquals("id Sin duplicados", calls.size, calls.map { it.id }.distinct().size)
        assertTrue("Los parámetros incompletos no se ingresarán en el historial de devoluciones.", calls.all { it.arguments == "{}" })
    }

    @Test
    fun `truncated tool calls of an empty raw list yield nothing`() {
        assertEquals(emptyList<ToolCall>(), orchestrator.truncatedToolCallsForBackfill(emptyList(), round = 1))
    }

    @Test
    fun `truncated tool result is valid json naming the truncated tool`() {
        val json = orchestrator.truncatedArgumentsToolResult("show\"card")

        // Se puede analizar como JSON = Los parámetros incompletos no se integran en el contenido del modelo.
        assertEquals("arguments_truncated_by_output_limit", jsonStringField(json, "error"))
        assertEquals("show\"card", jsonStringField(json, "tool"))
        assertTrue(jsonStringField(json, "detail")!!.contains("Reiniciar"))
    }
}
