package io.zer0.muse.tools

/**
 * v1.0.52 P2-1: Passive Subagent XML Representador de protocolo.
 *
 * Represente el proceso de ejecución del subagente en tres etapas XML,Deje que el agente principal vea claramente:
 *  1. <subagent_start/>    — Comienza el subagente(Descripción de la tarea + cuota maxToolCalls)
 *  2. <subagent_progress/> — Progreso de cada ronda de llamadas de herramientas(rondas/Nombre de la herramienta/parámetros/Vista previa de resultados)
 *  3. <subagent_result>    — resultado final(éxito/Fallo + rondas totales + llamadas totales a herramientas + texto resumido)
 *
 * principios de diseño:
 *  - XML Estructuración de etiquetas,Facilita que el agente principal analice y comprenda el progreso del subagente
 *  - Los resultados finales y los resultados de las herramientas se devuelven en su totalidad.;XML grande está paginado por el archivo de salida de la herramienta externa
 *  - Aún devuelve información utilizable en caso de falla(Rondas completadas + motivo del error),Deje que el agente principal decida volver a intentarlo o cambiar de ruta.
 *  - Todo el texto controlable por el usuario.(task/args/result)Todos son XML escapados,Prevenir la inyección
 *
 * con existente [SubagentTool] la diferencia:
 *  - [SubagentTool] Es el modo asíncrono sin bloqueo.(Regresar inmediatamente taskId,Comentarios a través de DeferredResultStore),
 *    Su progreso se muestra a través de la cadena TaskState.progress,Sin acuerdo fijo
 *  - Este renderizador sirve [SubagentRunner] modo de bloqueo sincrónico,El agente principal espera a que complete el subagente.
 *    Recíbelo todo de una vez XML(Incluye todos los avances.),La protocolización facilita el análisis posterior por parte del agente principal
 */
object SubagentXmlRenderer {

    /** Número máximo de caracteres para argumentos de llamada de herramienta(más allá del truncamiento). */
    private const val ARGS_PREVIEW_CHARS = 500

    /**
     * Renderizar XML de la fase de inicio.
     *
     * @param task Descripción de la tarea del delegado
     * @param maxToolCalls Límite de cuota de llamadas de herramientas
     */
    fun renderStart(task: String, maxToolCalls: Int): String {
        val escapedTask = escapeXml(task)
        return "<subagent_start task=\"$escapedTask\" max_tool_calls=\"$maxToolCalls\"/>"
    }

    /**
     * Renderice un XML de progreso de llamada de herramienta de una sola ronda.
     *
     * @param round ronda actual(empezar desde 1)
     * @param maxToolCalls Límite de cuota de llamadas de herramientas
     * @param toolName Nombre de la herramienta
     * @param argsJson Cadena JSON del parámetro de herramienta
     * @param result Resultados de ejecución de herramientas
     * @param success Si la ejecución de la herramienta fue exitosa
     */
    fun renderProgress(round: Int, maxToolCalls: Int, toolName: String, argsJson: String, result: String, success: Boolean): String {
        val escapedArgs = escapeXml(truncate(argsJson, ARGS_PREVIEW_CHARS))
        val escapedResult = escapeXml(result)
        val successAttr = if (success) "true" else "false"
        return "<subagent_progress round=\"$round\" max_tool_calls=\"$maxToolCalls\" " +
            "tool=\"$toolName\" success=\"$successAttr\" " +
            "args=\"$escapedArgs\" result=\"$escapedResult\"/>"
    }

    /**
     * Renderice el resultado final XML.
     *
     * @param success Si el subagente en su conjunto tiene éxito
     * @param rounds rondas totales
     * @param toolCalls Llamadas totales a herramientas
     * @param summary texto resumen final(Salida de la última invocación del agente secundario que no fue una herramienta)
     * @param error Razón del fracaso(success=false el tiempo no está vacío)
     * @param threadId v1.0.53: hilo id(Cuando no está vacío, se genera como un atributo.,El agente proveedor continúa la conexión)
     */
    fun renderResult(
        success: Boolean,
        rounds: Int,
        toolCalls: Int,
        summary: String,
        error: String? = null,
        threadId: String? = null,
    ): String {
        val successAttr = if (success) "true" else "false"
        val body = if (success) {
            escapeXml(summary)
        } else {
            val errText = error?.takeIf { it.isNotBlank() } ?: "error desconocido"
            "[FAILED] ${escapeXml(errText)}" +
                if (summary.isNotBlank()) "\n[PARTIAL] ${escapeXml(summary)}" else ""
        }
        return buildString {
            append("<subagent_result success=\"$successAttr\" ")
            append("rounds=\"$rounds\" tool_calls=\"$toolCalls\"")
            // v1.0.53: Renovar bono — Una vez que el agente principal lo obtiene, puede pasar thread_id durante la siguiente llamada para continuar la conexión.
            if (!threadId.isNullOrBlank()) {
                append(" thread_id=\"").append(escapeXml(threadId)).append("\"")
            }
            append(">")
            appendLine()
            append(body)
            appendLine()
            append("</subagent_result>")
        }
    }

    /**
     * Representar un mensaje truncado cuando se alcanza el límite de maxToolCalls(como parte del resultado).
     *
     * Cuando un agente secundario se detiene debido a que alcanzó la cuota de llamadas de herramientas,Inserte este consejo antes del resumen,
     * Informe al agente maestro que el agente secundario se trunca por cuota en lugar de completarse de forma natural.
     */
    fun renderBudgetExhausted(maxToolCalls: Int): String {
        return "[NOTE] El subagente ha alcanzado el límite de cuota de llamadas de herramienta($maxToolCalls),Genera un resumen basado en la información recopilada."
    }

    /** XML Carácter especial que se escapa. */
    private fun escapeXml(text: String): String {
        if (text.isEmpty()) return ""
        return buildString(text.length + 16) {
            for (ch in text) {
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    '\n' -> append("&#10;")
                    '\r' -> append("&#13;")
                    '\t' -> append("&#9;")
                    else -> append(ch)
                }
            }
        }
    }

    /** Truncar a la longitud máxima,Agregue una marca de truncamiento cuando se exceda. */
    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "...[truncated ${text.length - maxChars} chars]"
    }
}
