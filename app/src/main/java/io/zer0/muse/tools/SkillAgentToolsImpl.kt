package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.schedule.GroupChatScheduler
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.AgentPlanStep
import io.zer0.muse.ui.taskcard.AgentPlanStepStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * P1-3e Deslocalización:SkillExecutor Flujo de trabajo del agente/Implementación de herramienta de chat grupal.
 *
 * Capacidad de carga:
 * - task_plan / update_plan_step(caché del plan de memoria)
 * - channel_reply / channel_pass / channel_read_context(Herramienta de toma de decisiones de chat grupal)
 * - agent_phone(El agente principal activa un chat privado entre los miembros del chat grupal)
 *
 * delegateAgent Todavía en SkillExecutor (alto acoplamiento de recursividad, puntos de pausa, seguimiento de enlaces y dependencias de Koin).
 */
class SkillAgentToolsImpl(
    private val context: Context,
    private val assistantRepository: AssistantRepository,
    private val groupChatRepository: GroupChatRepository?,
    private val groupChatSchedulerProvider: (() -> GroupChatScheduler?)?,
) {

    /**
     * Caché de memoria del plan actualmente activo(planId → AgentPlan).ChatViewModel Este caché también se lee para actualizar la interfaz de usuario.
     *
     * LRU eliminar(Máximo 50 artículos), para evitar pérdidas de memoria causadas por planes de acumulación en ejecución a largo plazo.
     */
    private val activePlans: MutableMap<String, AgentPlan> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, AgentPlan>(
                64,
                0.75f,
                true,
            ) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AgentPlan>): Boolean = size > 50
            },
        )

    /** Lea todos los planes activos, reservados para personas que llaman anteriormente, como estadísticas globales. */
    fun getActivePlans(): Map<String, AgentPlan> = synchronized(activePlans) { activePlans.toMap() }

    /** Lea el plan activo de la sesión especificada para evitar la serialización de sesiones paralelas. */
    fun getActivePlans(sessionId: String): Map<String, AgentPlan> = synchronized(activePlans) {
        activePlans.toMap().filter { (_, plan) -> plan.sessionId == sessionId }
    }

    /**
     * El plan para reproducir el historial de mensajes se descarga en la caché del ejecutor.
     *
     * El mensaje de visualización planificado se ha eliminado; este paso de reinyección permite que la llamada continúe después de volver a ingresar a la sesión. update_plan_step
     * no se devolverá porque activePlans en el proceso está vacío“El plan no existe”.
     */
    fun restoreActivePlans(plans: Map<String, AgentPlan>, sessionId: String = "default") {
        // Sólo reemplaza la antigua proyección de la sesión actual, preservando los planes que se están ejecutando en otras sesiones.
        synchronized(activePlans) {
            activePlans.entries.removeIf { it.value.sessionId == sessionId }
            plans.forEach { (id, plan) ->
                activePlans[id] = if (plan.sessionId == sessionId) plan else plan.copy(sessionId = sessionId)
            }
        }
    }

    /**
     * v2.6.4: Finalizar el plan inacabado en la sesión especificada —— Convierta el paso PENDIENTE/EN_PROGRESO en CANCELADO.
     *
     * fondo:El avance del plan depende completamente del ajuste activo de update_plan_step del modelo. Una vez que el ciclo de la herramienta se agota debido a las rondas,
     * Salida por fallo continuo, parada de usuario, etc.,Ya nadie actualiza el estado del paso,La tarjeta del plan siempre se detendrá en“girar en circulos”estado.
     * Este método es el secreto para saldar la cuenta final. —— Llamado por el orquestador al final de la carrera.,Asegúrate de que la tarjeta termine bien.
     *
     * @return El plan que se cierra automáticamente (para que la persona que llama se sincronice con la interfaz de usuario).
     */
    fun settleUnfinishedPlans(sessionId: String = "default"): Map<String, AgentPlan> {
        synchronized(activePlans) {
            val now = System.currentTimeMillis()
            val settled = linkedMapOf<String, AgentPlan>()
            activePlans.entries.forEach { (id, plan) ->
                if (plan.sessionId != sessionId) return@forEach
                if (plan.isAllSettled) return@forEach
                val steps = plan.steps.map { step ->
                    when (step.status) {
                        AgentPlanStepStatus.PENDING, AgentPlanStepStatus.IN_PROGRESS ->
                            step.copy(
                                status = AgentPlanStepStatus.CANCELLED,
                                finishedAt = if (step.finishedAt == 0L) now else step.finishedAt,
                            )
                        else -> step
                    }
                }
                val updated = plan.copy(steps = steps)
                activePlans[id] = updated
                settled[id] = updated
            }
            return settled
        }
    }

    suspend fun execTaskPlan(args: Map<String, String>, sessionId: String = "default"): String {
        val title = args["title"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_title)
        if (title.isBlank()) return context.getString(R.string.skill_title_blank)

        val stepsJson = args["steps"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_steps)

        val steps = resultOf {
            val jsonArray = io.zer0.common.AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.json.JsonElement.serializer()),
                stepsJson,
            )
            jsonArray.mapIndexed { idx, element ->
                val obj = element.jsonObject
                val stepTitle = obj["title"]?.jsonPrimitive?.content?.trim() ?: "pasos ${idx + 1}"
                val stepDesc = obj["description"]?.jsonPrimitive?.content?.trim() ?: ""
                AgentPlanStep(
                    id = "step-$idx",
                    title = stepTitle,
                    description = stepDesc,
                )
            }
        }.onError { msg, _ ->
            Logger.w("SkillAgentTools", "task_plan steps El análisis falló: $msg")
        }.getOrNull()
            ?: return context.getString(R.string.skill_steps_invalid)

        if (steps.isEmpty()) return context.getString(R.string.skill_steps_empty)

        val planId = "plan-${System.currentTimeMillis()}"
        val plan = AgentPlan(
            id = planId,
            title = title,
            steps = steps,
            sessionId = sessionId,
        )

        activePlans[planId] = plan

        val planSummary = buildString {
            appendLine("Plan creado.planId: $planId")
            appendLine("Título: $title")
            appendLine("pasos:")
            steps.forEachIndexed { idx, step ->
                appendLine("  $idx. ${step.title}${if (step.description.isNotBlank()) " — ${step.description.take(80)}" else ""}")
            }
            appendLine()
            appendLine("Siga los pasos en orden. cada paso completado,llamar update_plan_step(planId=\"$planId\", stepIndex=índice, status=\"done\") Progreso de la actualización.")
        }
        return planSummary
    }

    suspend fun execUpdatePlanStep(args: Map<String, String>, sessionId: String = "default"): String {
        val planId = args["planId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_plan_id)
        val stepIndex = args["stepIndex"]?.toIntOrNull()
            ?: return context.getString(R.string.skill_missing_param_step_index)
        val statusStr = args["status"]?.trim()?.lowercase()
            ?: return context.getString(R.string.skill_missing_param_status)
        val result = args["result"]?.trim() ?: ""

        val plan = activePlans[planId]
            ?.takeIf { it.sessionId == sessionId }
            ?: return context.getString(R.string.skill_plan_not_found, planId)

        if (stepIndex < 0 || stepIndex >= plan.steps.size) {
            return context.getString(R.string.skill_step_index_out_of_range, stepIndex, plan.steps.size)
        }

        val status = when (statusStr) {
            "done", "complete", "completed" -> AgentPlanStepStatus.DONE
            "failed", "error" -> AgentPlanStepStatus.FAILED
            "in_progress", "running" -> AgentPlanStepStatus.IN_PROGRESS
            "skipped", "skip" -> AgentPlanStepStatus.SKIPPED
            else -> return context.getString(R.string.skill_unknown_status, statusStr)
        }

        val updatedSteps = plan.steps.mapIndexed { idx, step ->
            if (idx == stepIndex) {
                step.copy(
                    status = status,
                    result = result,
                    startedAt = if (status == AgentPlanStepStatus.IN_PROGRESS) System.currentTimeMillis() else step.startedAt,
                    finishedAt = if (status == AgentPlanStepStatus.DONE || status == AgentPlanStepStatus.FAILED) System.currentTimeMillis() else step.finishedAt,
                )
            } else {
                step
            }
        }
        activePlans[planId] = plan.copy(steps = updatedSteps)

        val step = updatedSteps[stepIndex]
        val completedCount = updatedSteps.count { it.status == AgentPlanStepStatus.DONE }
        return "pasos $stepIndex「${step.title}” ha sido actualizado a: ${context.getString(status.labelRes)}. Progreso: $completedCount/${updatedSteps.size}"
    }

    suspend fun execChannelReply(args: Map<String, String>): String {
        val repo = groupChatRepository
            ?: return context.getString(R.string.skill_channel_reply_not_configured)
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val assistantId = args["assistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_assistant_id)
        val body = args["body"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_body)
        if (body.isBlank()) return context.getString(R.string.skill_body_blank)

        val assistant = resultOf { assistantRepository.getById(assistantId) }
            .onError { msg, _ -> Logger.w("SkillAgentTools", "channel_reply getById falló: $msg") }
            .getOrNull()
        val senderName = assistant?.name ?: context.getString(R.string.skill_unknown_agent)

        repo.sendMessage(
            chatId = chatId,
            senderType = "assistant",
            senderId = assistantId,
            senderName = senderName,
            body = body,
        )

        return context.getString(R.string.skill_message_sent, body.take(50))
    }

    suspend fun execChannelPass(args: Map<String, String>): String {
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val assistantId = args["assistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_assistant_id)
        return context.getString(R.string.skill_skipped, chatId, assistantId)
    }

    suspend fun execChannelReadContext(args: Map<String, String>): String {
        val repo = groupChatRepository
            ?: return context.getString(R.string.skill_channel_read_not_configured)
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val limit = args["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20

        val messages = repo.getRecentMessages(chatId, limit)
        if (messages.isEmpty()) return context.getString(R.string.skill_chat_no_messages, chatId)

        val sb = StringBuilder(context.getString(R.string.skill_chat_messages_header))
        val timeFormatter = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        for (msg in messages) {
            val timeStr = timeFormatter.format(java.util.Date(msg.timestamp))
            sb.appendLine("[${msg.senderName} | $timeStr] ${msg.body}")
        }
        return sb.toString().trimEnd()
    }

    suspend fun execAgentPhone(args: Map<String, String>): String {
        val chatId = args["chatId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_chat_id)
        val targetAssistantId = args["targetAssistantId"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_target_assistant_id)
        val message = args["message"]?.trim()
            ?: return context.getString(R.string.skill_missing_param_message)
        if (message.isBlank()) return context.getString(R.string.skill_message_blank)

        val scheduler = groupChatSchedulerProvider?.invoke()
            ?: return context.getString(R.string.skill_agent_phone_not_configured)

        val target = resultOf { assistantRepository.getById(targetAssistantId) }
            .onError { msg, _ -> Logger.w("SkillAgentTools", "agent_phone getById falló: $msg") }
            .getOrNull()
        if (target == null) return context.getString(R.string.skill_agent_phone_target_not_found)

        scheduler.launchWhisper(
            chatId = chatId,
            targetAssistantId = targetAssistantId,
            text = message,
        )
        return context.getString(R.string.skill_whisper_sent, target.name)
    }
}
