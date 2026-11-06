package io.zer0.muse.tools

import io.zer0.muse.data.SettingsRepository
import kotlinx.coroutines.flow.first

/** en chat normal“pide un deseo”: permite que el asistente actual se comunique proactivamente con el usuario más adelante. */
object ProactiveWishTool {
    fun toolDef() = ToolRegistry.ToolDef(
        name = "proactive_message_wish",
        description = "Cuando el usuario le pide al asistente que se comunique, salude o se preocupe más tarde,Habilite la mensajería proactiva para el asistente actual." +
            "El anfitrión vinculará automáticamente este deseo al asistente actual.,No pase la identificación del asistente.",
        parameters = mapOf(
            "schedule_hint" to
                "Opcional. Preferencia de tiempo del lenguaje natural,Por ejemplo, \"todas las noches\" y \"mañana por la mañana\"." +
                "Sólo desea registrar,No te comprometas con un momento específico.",
        ),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, settings: SettingsRepository, executionContext: ToolExecutionContext): String {
        val assistantId = executionContext.assistantId
            ?.takeIf { it.isNotBlank() }
            ?: executionContext.scope.takeIf { it.isNotBlank() && it != "main" }
            ?: "default"
        val current = settings.proactiveMessageConfigFlow.first()
        settings.saveProactiveMessageConfig(
            current.copy(
                enabled = true,
                agentId = assistantId,
                agentOnly = false,
                lastFailedAt = 0L,
                consecutiveFailures = 0,
                nextTriggerAt = 0L,
            ),
        )
        val hint = args["schedule_hint"]?.trim()?.takeIf { it.isNotBlank() }
        return if (hint == null) {
            "Este deseo ha sido tomado en cuenta. Luego me comunicaré con usted de manera proactiva en el momento apropiado; Se pueden enviar mensajes proactivos a chats habituales, no solo a conversaciones de agentes."
        } else {
            "Este deseo ha sido anotado (preferencia horaria:$hint). Combinaré su configuración de mensajes proactivos y horarios de no molestar, y no prometo ser preciso."
        }
    }
}
