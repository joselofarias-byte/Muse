package io.zer0.muse.tools

/**
 * v1.0.52 P2-1: subagent_run Entrada de herramientas.
 *
 * El agente principal delega subtareas a [SubagentRunner](Pasivos independientes del contexto agent).
 *
 * con [SubagentTool] relación:
 *  - [SubagentTool](subagent_task) = Modo asincrónico sin bloqueo,Adecuado para tareas largas,Regresar inmediatamente taskId,
 *    El agente principal puede continuar la conversación.,Una vez completada la subtarea, se enviará a través de DeferredResultStore.
 *  - **[SubagentRunSkill]**(subagent_run) = modo de bloqueo sincrónico,Adecuado para tareas de investigación cortas.,El agente principal espera,
 *    El agente secundario devuelve resultados XML estructurados después de ejecutar el ciclo completo de la herramienta.
 *
 * Guía de selección de agentes maestros(Indíquelo en la descripción de la herramienta LLM):
 *  - Las tareas requieren múltiples llamadas a herramientas y no quieren contaminar el contexto principal. → subagent_run
 *  - La tarea es una operación que requiere mucho tiempo y el agente principal debe seguir respondiendo. → subagent_task
 *
 * parámetros:
 *  - task: Requerido,Descripción de la tarea del delegado
 *  - context_text: Opcional,Restricciones/Criterios de aceptación
 *  - target_paths: Opcional,Ruta del archivo prioritario(separados por comas)
 *  - max_tool_calls: Opcional,Cuota de llamadas de herramientas(Predeterminado 8,límite superior 20)
 *  - timeout_ms: Opcional,Tiempo de espera de ejecución general(Predeterminado 240s,alcance 30s~900s)——Recomendado para misiones profundas.
 *  - thread_id: Opcional(v1.0.53+),Continuar hilo id;Pase el thread_id devuelto por el subagent_run anterior para continuar con la misma sesión del subagente.
 *  - close_thread: Opcional(v1.0.53+),"true" Cerrar el hilo después de completar la ejecución.(Escenario de delegación única)
 *
 * Regresar:Cadena de protocolo XML trifásico
 *  <subagent_start .../>
 *  <subagent_progress .../> (0~N Artículo)
 *  <subagent_result ... thread_id="...">...</subagent_result>
 */
object SubagentRunSkill {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "subagent_run",
        description = "Delegue una subtarea autónoma a un subagente con un contexto independiente." +
            "El subagente ejecutará el ciclo completo de la herramienta.(Hasta max_tool_calls veces),Devuelve resultados XML estructurados." +
            "Adecuado para subtareas de investigación, búsqueda y análisis que requieren múltiples llamadas a herramientas." +
            "Pase el thread_id devuelto para continuar con la misma sesión de subagente." +
            "Para tareas asincrónicas de larga duración, utilice subagent_task en su lugar.",
        parameters = mapOf(
            "task" to "Required. The sub-task description to delegate.",
            "context_text" to "Optional. Constraints / acceptance criteria for the sub-task.",
            "target_paths" to "Optional. Comma-separated file paths the sub-agent should inspect first.",
            "max_tool_calls" to "Optional. Max tool calls budget (default 8, hard cap 20).",
            "timeout_ms" to "Optional. Overall execution timeout in ms " +
                "(default 240000, range 30000-900000). Raise for deep multi-tool tasks.",
            "thread_id" to "Optional. Thread id from a previous subagent_run call; continues that sub-agent session.",
            "close_thread" to "Optional. 'true' to close the thread after this run (one-shot delegation).",
            "token_budget" to "Optional. Token budget; when exhausted the sub-agent summarizes early.",
        ),
        required = setOf("task"),
        category = "built-in",
        parameterTypes = mapOf(
            "max_tool_calls" to "integer",
            "token_budget" to "integer",
            "timeout_ms" to "integer",
        ),
        // HIGH riesgo:El subagente puede llamar a múltiples herramientas,Posibles efectos secundarios,Requiere confirmación del usuario
        riskLevel = ToolRiskLevel.HIGH,
    )

    /**
     * Ejecute la herramienta subagent_run.
     *
     * @param args Parámetros de la herramienta(task / context_text / target_paths / max_tool_calls)
     * @param subagentRunner corredor agente infantil
     * @return Cadena de protocolo XML trifásico
     */
    suspend fun execute(args: Map<String, String>, subagentRunner: SubagentRunner): String {
        val task = args["task"]?.trim()
            ?: return errorXml("Falta el parámetro requerido task")
        if (task.isBlank()) return errorXml("task El parámetro está vacío.")

        val contextText = args["context_text"]?.trim()?.takeIf { it.isNotBlank() }
        val targetPaths = args["target_paths"]?.trim()?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
        val maxToolCalls = args["max_tool_calls"]?.toIntOrNull()
            ?.coerceIn(1, SubagentRunner.MAX_TOOL_CALLS_HARD_CAP)
            ?: SubagentRunner.DEFAULT_MAX_TOOL_CALLS

        // v2.x(Proyecto Deuda Técnica E): Transmisión transparente después del tiempo de espera——Tareas profundas (múltiples rondas de llamadas a herramientas/Búsqueda de contexto grande)
        // 120s El valor predeterminado no es suficiente, el agente principal puede darle explícitamente 600. alcance 30s~900s.
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()
            ?.coerceIn(30_000L, 900_000L)
            ?: SubagentRunner.DEFAULT_TIMEOUT_MS

        // v1.0.53: Parámetros de continuación
        val threadId = args["thread_id"]?.trim()?.takeIf { it.isNotBlank() }
        val closeAfterRun = args["close_thread"]?.trim()?.equals("true", ignoreCase = true) ?: false
        // v1.0.53 Phase 3: token presupuesto(Opcional,null=sin limite)
        val tokenBudget = args["token_budget"]?.toIntOrNull()?.takeIf { it > 0 }

        val params = SubagentRunner.Params(
            task = task,
            contextText = contextText,
            targetPaths = targetPaths,
            maxToolCalls = maxToolCalls,
            timeoutMs = timeoutMs,
            threadId = threadId,
            closeAfterRun = closeAfterRun,
            tokenBudget = tokenBudget,
        )

        // Fase de inicio de renderizado
        val startXml = SubagentXmlRenderer.renderStart(task, maxToolCalls)

        // corredor agent
        val result = subagentRunner.run(params)

        // Etapa de progreso de renderizado
        val progressXml = result.progressEntries.joinToString("\n") { entry ->
            SubagentXmlRenderer.renderProgress(
                round = entry.round,
                maxToolCalls = maxToolCalls,
                toolName = entry.toolName,
                argsJson = entry.argsJson,
                result = entry.result,
                success = entry.success,
            )
        }

        // Etapa de resultado de renderizado(v1.0.53: Transmita de forma transparente el threadId para permitir que el agente principal continúe la conexión.)
        // v1.0.53 Phase 3: Aviso de agotamiento de cuota(maxToolCalls Marcado por separado con presupuesto simbólico)
        val summaryWithNote = buildString {
            if (result.budgetExhausted) {
                appendLine(SubagentXmlRenderer.renderBudgetExhausted(maxToolCalls))
            }
            if (result.tokenBudgetExhausted) {
                appendLine("[Consejos] El subagente rescindió anticipadamente debido al agotamiento del presupuesto simbólico.,El resumen se genera en función del contexto existente.")
            }
            append(result.summary)
        }.trimEnd()
        val resultXml = SubagentXmlRenderer.renderResult(
            success = result.success,
            rounds = result.rounds,
            toolCalls = result.toolCalls,
            summary = summaryWithNote,
            error = result.error,
            threadId = result.threadId,
        )

        // Tres etapas de empalme.
        return buildString {
            appendLine(startXml)
            if (progressXml.isNotEmpty()) {
                appendLine(progressXml)
            }
            append(resultXml)
        }
    }

    /** Cuando los parámetros de renderizado son incorrectos XML(Sin promotor agent,Devolver error directamente). */
    private fun errorXml(message: String): String {
        return SubagentXmlRenderer.renderResult(
            success = false,
            rounds = 0,
            toolCalls = 0,
            summary = "",
            error = message,
        )
    }
}
