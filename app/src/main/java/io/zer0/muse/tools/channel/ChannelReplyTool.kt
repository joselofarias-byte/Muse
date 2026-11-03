package io.zer0.muse.tools.channel

import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel

/**
 * Herramienta de respuesta de chat grupal — Phone Session En modo el agente usa esta herramienta para hablar.
 *
 * diseño:
 *  - Solo el contenido de esta herramienta se escribirá en los mensajes de chat grupal.(Otras herramientas no)
 *  - chatId / senderAssistantId Vincular en el momento de la construcción de la herramienta,LLM No es necesario y no se puede pasar
 *  - Pase [onReply] La devolución de llamada devuelve el contenido a la persona que llama.(Persistente en el chat grupal por GroupChatScheduler)
 *
 * Diferencia con el canal_reply existente en SkillExecutor:
 *  - SkillExecutor versión:LLM pasar chatId/assistantId/body,Sesión principal ajustable
 *  - esta versión:chatId/assistantId atado por marco,Solo para uso de sesión telefónica de chat grupal,
 *    Evite el uso indebido de las herramientas de chat grupal mediante master session LLM
 */
class ChannelReplyTool(
    private val groupChatId: String,
    private val senderAssistantId: String,
    private val onReply: suspend (content: String) -> Unit,
) {

    /**
     * Definición de herramienta.
     *
     * LLM Simplemente proporcione el parámetro de contenido.;chatId / assistantId Ya encuadernado en el momento de la construcción.,
     * no expuesto a LLM,Evite que LLM falsifique la identidad del remitente.
     */
    fun toolDef() = ToolRegistry.ToolDef(
        name = "channel_reply",
        description = "Habla en un chat grupal. Su respuesta se enviará al canal de chat grupal a través de esta herramienta." +
            "Esta es la única forma de hacer que tu mensaje aparezca en el chat grupal." +
            "si no quieres hablar,Utilice channel_pass.",
        parameters = mapOf(
            "content" to "Requerido. La respuesta que quieres enviar en el chat grupal.",
        ),
        required = setOf("content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * herramienta de ejecución:Verificar contenido y aprobar [onReply] Devolución de llamada enviada.
     *
     * @param args Parámetros de la herramienta,LLM Los argumentos entrantes JSON analizados map
     * @return Cadena de resultado de ejecución(mensaje de éxito/error)
     */
    suspend fun execute(args: Map<String, String>): String {
        val content = args["content"]?.trim()
        if (content.isNullOrEmpty()) {
            return "error: content no puede estar vacío"
        }
        // groupChatId / senderAssistantId Sólo para contexto vinculante,No participa en la lógica visible para LLM,
        // Pero mantén el cheque:Si no está atada, la herramienta fue construida incorrectamente.,Informe un error directamente en lugar de enviarlo en silencio.
        if (groupChatId.isBlank() || senderAssistantId.isBlank()) {
            return "error: La herramienta de chat grupal no está vinculada correctamente(chatId/assistantId esta vacio)"
        }
        onReply(content)
        return "Enviado al chat grupal"
    }
}
