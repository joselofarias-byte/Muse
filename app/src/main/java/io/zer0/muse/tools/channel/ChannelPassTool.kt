package io.zer0.muse.tools.channel

import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * Herramienta para omitir chat grupal — Phone Session En modo, el agente elige no hablar en esta ronda.
 *
 * diseño:
 *  - agent debe estar en channel_reply / channel_pass / channel_read_context entre los tres
 *    Llame al menos a una declaración,De lo contrario desencadenar repair attempt(Reintentar 1 vez)
 *  - Llame a esta herramienta para dejar clara su postura"No hables esta ronda",Registrado por la persona que llama activity
 *  - Parámetro de motivo opcional,Usado para depurar/auditar por qué no hablar
 *
 * Diferencia con el canal_pass existente en SkillExecutor:
 *  - SkillExecutor versión:LLM pasar chatId/assistantId,Sesión principal ajustable
 *  - esta versión:No acepta parámetros chatId/assistantId,Unión de capas de marco,
 *    Solo para uso de sesión telefónica de chat grupal
 */
class ChannelPassTool(
    private val onPass: suspend (reason: String?) -> Unit,
) {

    /**
     * Definición de herramienta.
     *
     * reason es un parámetro opcional,LLM Puedo explicar por qué no hablas,Fácil de depurar y auditar.
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = "channel_pass",
        description = "Elige no hablar en esta ronda. Si crees que no es necesario responder,O quieres esperar a que otros miembros hablen primero.,Por favor llame a esta herramienta." +
            "Puedes dar una razón de por qué no hablas.(Opcional).",
        parameters = mapOf(
            "reason" to "Opcional. Razón para no hablar,Fácil de depurar y auditar",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * herramienta de ejecución:sacar opcional reason,Pase [onPass] La devolución de llamada notifica a la persona que llama.
     *
     * @param args Parámetros de la herramienta,LLM Los argumentos entrantes JSON analizados map
     * @return Cadena de resultado de ejecución
     */
    suspend fun execute(args: Map<String, String>): String {
        val reason = args["reason"]?.trim()?.takeIf { it.isNotBlank() }
        onPass(reason)
        return "Esta ronda se ha saltado"
    }
}
