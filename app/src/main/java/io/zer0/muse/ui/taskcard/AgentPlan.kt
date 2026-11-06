package io.zer0.muse.ui.taskcard

import io.zer0.muse.R
import kotlinx.serialization.Serializable

/**
 * v1.55: Agent Planificación estructurada del flujo de trabajo.
 *
 * Cuando LLM enfrenta tareas complejas,Primero puede llamar a la herramienta task_plan para crear un plan paso a paso.,
 * Luego impleméntelo paso a paso. Los planes se presentan como listas de verificación en la interfaz de usuario.,Los usuarios pueden ver el progreso en tiempo real.
 */
@Serializable
data class AgentPlan(
    val id: String,
    val title: String,
    val steps: List<AgentPlanStep>,
    /** La sesión a la que pertenece; El valor predeterminado se usa cuando el historial anterior no tiene este campo y la persona que llama lo completará durante la recuperación. */
    val sessionId: String = "default",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * v1.137: Asocie el ID de mensaje del asistente que creó este plan.
     *
     * Se utiliza para fijar una tarjeta de planificación al mensaje que la creó.,Desplácese con este mensaje,
     * en lugar de siempre"saltar"Ir al último mensaje del asistente(Comentarios de los usuarios"La lista está fija en la parte inferior y no sigue el desplazamiento.").
     * null Indica no asociado(compatible con versiones anteriores),UI recurrir a la vieja lógica(Mostrado en el último mensaje del asistente).
     */
    val messageId: String? = null,
) {
    val totalSteps: Int get() = steps.size
    val completedSteps: Int get() = steps.count { it.status == AgentPlanStepStatus.DONE }

    /** Número de pasos fallidos(Incluyendo el tiempo de espera de ejecución;El tiempo de espera es el estado final de falla.,Incluido en resumen). */
    val failedSteps: Int get() = steps.count {
        it.status == AgentPlanStepStatus.FAILED || it.status == AgentPlanStepStatus.TIMED_OUT
    }
    val inProgressSteps: Int get() = steps.count { it.status == AgentPlanStepStatus.IN_PROGRESS }
    // L-TC1 Reparación: dividir isAllSettled(Por todas partes,Contiene FAILED/SKIPPED)con isAllSucceeded(Todo exitoso).
    // Originalmente está Todo Hecho contado FALLADO/SALTADO"Completo",Semántica poco clara,El icono de finalización en la barra de título puede engañar a los usuarios.
    // Phase 3: CANCELLED/TIMED_OUT También es el estado final.,debe ser tenido en cuenta isAllSettled,De lo contrario, la tarjeta de planificación seguirá girando para siempre.
    /** Todos los pasos completados(no más PENDING / IN_PROGRESS),Contiene éxito/falló/saltar/Cancelar/se acabó el tiempo. */
    val isAllSettled: Boolean get() = steps.isNotEmpty() && steps.all {
        it.status == AgentPlanStepStatus.DONE ||
            it.status == AgentPlanStepStatus.FAILED ||
            it.status == AgentPlanStepStatus.SKIPPED ||
            it.status == AgentPlanStepStatus.CANCELLED ||
            it.status == AgentPlanStepStatus.TIMED_OUT
    }

    /** Todos los pasos se completaron exitosamente(Ninguno FAILED / SKIPPED). */
    val isAllSucceeded: Boolean get() = steps.isNotEmpty() && steps.all {
        it.status == AgentPlanStepStatus.DONE
    }

    // Reserve isAllDone como alias para isAllSettled,Compatible con versiones anteriores con llamantes existentes(Juicio de la barra de título"¿Sigues corriendo?")
    val isAllDone: Boolean get() = isAllSettled
    val progress: Float get() = if (steps.isEmpty()) 0f else completedSteps.toFloat() / steps.size

    /**
     * v2.6.4: Cerrando los pasos inacabados —— Establezca PENDIENTE / EN_PROGRESS en CANCELADO.
     *
     * Utilizado para dos caminos de liquidación final.:El ciclo de la herramienta sale (se agota)./falló/error) se detiene con el usuario.
     * Pasos que han llegado al estado final (DONE/FAILED/SKIPPED/CANCELLED/TIMED_OUT) permanece sin cambios.
     */
    fun settleAsCancelled(now: Long = System.currentTimeMillis()): AgentPlan {
        if (isAllSettled) return this
        val settled = steps.map { step ->
            when (step.status) {
                AgentPlanStepStatus.PENDING, AgentPlanStepStatus.IN_PROGRESS ->
                    step.copy(
                        status = AgentPlanStepStatus.CANCELLED,
                        finishedAt = if (step.finishedAt == 0L) now else step.finishedAt,
                    )
                else -> step
            }
        }
        return copy(steps = settled)
    }
}

@Serializable
data class AgentPlanStep(
    val id: String,
    val title: String,
    val description: String = "",
    val status: AgentPlanStepStatus = AgentPlanStepStatus.PENDING,
    val result: String = "",
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
)

@Serializable
enum class AgentPlanStepStatus(val labelRes: Int) {
    PENDING(R.string.task_plan_status_pending),
    IN_PROGRESS(R.string.task_plan_status_in_progress),
    DONE(R.string.task_plan_status_done),
    FAILED(R.string.task_plan_status_failed),
    SKIPPED(R.string.task_plan_status_skipped),

    /** Phase 3: paso cancelado(Usuario detenido/transmisión interrumpida). */
    CANCELLED(R.string.task_plan_status_cancelled),

    /** Phase 3: Se agotó el tiempo de ejecución del paso. */
    TIMED_OUT(R.string.task_plan_status_timed_out),
}
