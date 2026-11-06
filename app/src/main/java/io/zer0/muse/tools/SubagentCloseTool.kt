package io.zer0.muse.tools

import io.zer0.muse.data.subagent.SubagentThreadStore

/**
 * v1.0.53 Phase 1: subagent_close Herramientas — Cierre activamente el hilo del agente secundario.
 *
 * Permitir que el agente principal cierre los subprocesos que ya no son necesarios,Libera el libro mayor. Después del cierre, el threadId no se puede continuar.:
 *  - subagent_task La respuesta volverá "thread is closed"
 *  - subagent_run Llevar este thread_id será rechazado y lanzado por runSerialized IllegalStateException
 *
 * restricciones de seguridad:
 *  - Está prohibido llamar a esta herramienta dentro del subagente.(Anti-autoapagado)— Ya me uní
 *    [SubagentRunner.RECURSIVE_FORBIDDEN_TOOLS](Filtrado de lista blanca de subagentes)
 *  - Nivel de riesgo NORMAL: Cerrar el hilo es una operación no destructiva(Cambiar solo el estado del libro mayor,No borrar datos)
 */
object SubagentCloseTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "subagent_close",
        description = "Cerrar un hilo de subagente,Liberar su contabilidad de sesión. Este thread_id ya no se puede utilizar para la continuación." +
            "Se utiliza cuando la sesión del subagente ya no es necesaria.",
        parameters = mapOf(
            "thread_id" to "Required. The thread id to close (returned by subagent_task launch or subagent_run).",
            "reason" to "Optional. Reason for closing (recorded in logs).",
        ),
        required = setOf("thread_id"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * Ejecute la herramienta subagent_close.
     *
     * @param args Parámetros de la herramienta(thread_id Requerido / motivo opcional)
     * @param threadStore Libro mayor de hilos de subagente(Versión persistente)
     * @return Cadena de resultado de la operación
     */
    suspend fun execute(args: Map<String, String>, threadStore: SubagentThreadStore): String {
        val threadId = args["thread_id"]?.trim()
            ?: return "Error: thread_id is required."
        if (threadId.isBlank()) return "Error: thread_id is empty."

        val reason = args["reason"]?.trim()?.takeIf { it.isNotBlank() }
        val closed = threadStore.close(threadId)
        return when {
            closed ->
                "Thread '$threadId' closed successfully." +
                    (reason?.let { " Reason: $it" } ?: "")
            else -> "Thread '$threadId' not found or already closed."
        }
    }
}
