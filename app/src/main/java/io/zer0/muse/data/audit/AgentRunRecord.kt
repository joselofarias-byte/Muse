package io.zer0.muse.data.audit

/**
 * F-08: Agent Run recibo unificado — Un registro completo de la ejecución de una sola herramienta.
 *
 * Se utiliza para ejecutar cada herramienta dentro del bucle del Agente.(padre/Los subagentes son los mismos.)Finalización de registros estructurados.,
 * para diagnóstico de memoria(AuditLogPage o página de depuración)Se utiliza con registros de auditoría. Todos los campos son abstractos./valor original,
 * No lleva parámetros sensibles.(argumentsSummary/resultSummary truncado).
 *
 * @param runId Lo único que llama esta herramienta id(tomar ToolCall.id),El subagente usa esto como parentRunId
 * @param parentRunId padre run id(null Representa una llamada a una herramienta dentro de la sesión raíz.;delegate_agent/
 *   subagent_task La llamada a la herramienta subagente lleva al iniciador. runId,formar una cadena de llamadas)
 * @param status con [io.zer0.muse.tools.ToolOrchestrator.ToolExecStatus] consistente
 */
data class AgentRunRecord(
    val runId: String,
    val parentRunId: String? = null,
    val sessionId: String,
    val round: Int,
    val toolName: String,
    val argumentsSummary: String = "",
    val resultSummary: String = "",
    val status: String,
    val durationMs: Long,
    /** F-11: Esta ronda de LLM es visible(expuesto)Una colección de identificadores de herramientas.(separados por comas),Herramientas expuestas/Realizar evidencia instantánea. */
    val exposedToolIds: String = "",
    /** F-12: enlace unificado id(de StreamRunState.traceId),Recupere toda la cadena de llamadas del Agente mediante traceId. */
    val traceId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)
