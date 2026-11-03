package io.zer0.muse.tools

import io.zer0.muse.channel.ChannelManager
import kotlinx.coroutines.runBlocking

/**
 * v1.0.92 Dedomaining: Registrador de herramientas de canales de mensajes.
 *
 * poner [ChannelManager] Las capacidades de envío se exponen como herramientas LLM.:
 *  - [TOOL_SEND_CHANNEL] a canales externos configurados(Feishu/QQ espera)Enviar un mensaje de texto(HIGH,Ir para aprobación);
 *  - [TOOL_LIST_CHANNELS] Enumera los canales configurados.
 *
 * La configuración del canal la proporciona la página de la guía./Configurar el mantenimiento de la interfaz de usuario;Actualizar antes de enviar(La sobrecarga de lectura del disco es un archivo leído.,La operación de baja frecuencia es aceptable).
 */
class ChannelToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val channelManager: ChannelManager,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_SEND_CHANNEL,
                description = "Al canal de mensajería externo configurado(Feishu/QQ espera)Envía un mensaje de texto." +
                    "Primero use channel_list para consultar los canales disponibles y sus id;Adecuado para enviar resultados o notificaciones a la mensajería instantánea del usuario.",
                parameters = mapOf(
                    "channel_id" to "Requerido,canal id(ver channel_list)",
                    "text" to "Requerido,Contenido del texto a enviar",
                ),
                required = setOf("channel_id", "text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val channelId = args["channel_id"]?.trim().orEmpty()
            val text = args["text"].orEmpty()
            if (channelId.isBlank()) return@register "desaparecido channel_id"
            if (text.isBlank()) return@register "desaparecido text"
            runBlocking {
                channelManager.refresh()
                val result = channelManager.sendText(channelId, text)
                if (result.ok) result.detail else "Envío fallido: ${result.detail}"
            }
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_LIST_CHANNELS,
                description = "Listar canales de mensajería externos configurados(Feishu/QQ espera)y su estado de id/plataforma/habilitado.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) {
            runBlocking {
                channelManager.refresh()
                val list = channelManager.channels.value
                if (list.isEmpty()) {
                    "Aún no se han configurado canales."
                } else {
                    list.joinToString("\n") { c ->
                        "${c.id} | ${c.platform.name} | ${c.name.ifBlank { "-" }} | " +
                            if (c.enabled) "enabled" else "disabled"
                    }
                }
            }
        }
    }

    companion object {
        /** Enviar mensajes a canales externos(Ejecutar después de la aprobación). */
        const val TOOL_SEND_CHANNEL = "send_channel_message"

        /** Enumera los canales configurados. */
        const val TOOL_LIST_CHANNELS = "channel_list"
    }
}
