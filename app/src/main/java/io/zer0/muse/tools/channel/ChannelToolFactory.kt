package io.zer0.muse.tools.channel

import io.zer0.ai.core.ToolDefinition
import io.zer0.common.AppJson
import io.zer0.muse.tools.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tipo de función de ejecución de la herramienta de chat grupal.
 *
 * con [ToolRegistry] Los tipos internos de ToolFn son consistentes(suspend (Map<String, String>) -> String),
 * Pero ToolFn en ToolRegistry es private,No se puede hacer referencia externamente,Por lo tanto, aquí se divulga el alias de tipo con el mismo nombre.
 */
typealias ChannelToolFn = suspend (Map<String, String>) -> String

/**
 * v1.0.53 Phase 5: poner [ToolRegistry.ToolDef] Convertir a consumible LLM [ToolDefinition].
 *
 * con [ToolRegistry.listToolsAsToolDefinitions] Origen lógico interno,pero expuesto como una función de extensión,
 * dejar [ChannelToolFactory] Los conjuntos de herramientas únicos creados también se pueden pasar directamente a [io.zer0.ai.ChatService.streamChat].
 */
fun ToolRegistry.ToolDef.toToolDefinition(): ToolDefinition {
    val schema = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                parameters.forEach { (name, desc) ->
                    put(
                        name,
                        buildJsonObject {
                            put("type", parameterTypes[name] ?: "string")
                            put("description", desc)
                        },
                    )
                }
            },
        )
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }
    return ToolDefinition(
        name = name,
        description = description,
        parametersJsonSchema = AppJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            schema,
        ),
    )
}

/**
 * Fábrica de herramientas especiales de chat grupal — Phone Session Construcción dinámica en modo. channel_* Conjunto de tres piezas.
 *
 * Phone Session Inyecte 3 herramientas de decisión forzada,
 * agent debe ser llamado channel_reply / channel_pass / channel_read_context la postura de uno,
 * De lo contrario desencadenar repair attempt(Reintentar 1 vez).
 *
 * Diferencias con el registro de la herramienta de sesión principal:
 *  - La herramienta principal de sesión es proporcionada por [io.zer0.muse.tools.AgentToolsRegistrar.registerAll] Regístrese para
 *    situación general [ToolRegistry],Visible para todos los asistentes
 *  - channel_* la herramienta es**Solo chat grupal**de,No registrado en la sesión principal(De lo contrario, la sesión principal LLM verá estas herramientas.,
 *    Lista de posibles herramientas de mal uso/contaminación)
 *  - llamador(GroupChatScheduler)Aprobado durante la sesión telefónica [createChannelTools] Dinámico
 *    Construya un conjunto de herramientas de una sola vez,Vincular chatId/senderAssistantId actual con devolución de llamada,Descartado después de finalizar la sesión
 *
 * Contenido del conjunto de herramientas:
 *  1. [ChannelReplyTool]   — channel_reply,Escribir respuesta al chat grupal(La única herramienta que hace aparecer los mensajes)
 *  2. [ChannelPassTool]    — channel_pass,No hables esta ronda
 *  3. [ChannelReadContextTool] — channel_read_context,Leer más historial de chat grupal(Máximo 50)
 *
 * Ejemplos de uso(GroupChatScheduler en):
 * ```
 * val tools = ChannelToolFactory.createChannelTools(
 *     groupChatId = chatId,
 *     senderAssistantId = assistant.id,
 *     onReply = { content -> groupChatRepository.sendMessage(...) },
 *     onPass = { reason -> Logger.i(TAG, "Agent ${assistant.name} PASS: $reason") },
 *     contextProvider = { limit -> formatHistory(groupChatRepository.getRecentMessages(chatId, limit)) },
 * )
 * // Registrar herramientas en el temporal de la sesión actual. ToolRegistry,O deletréelo directamente en una lista ToolDefinition y páselo a streamChat
 * ```
 */
object ChannelToolFactory {

    /**
     * Construya un conjunto de herramientas especial para la sesión telefónica de chat grupal.
     *
     * @param groupChatId Chat grupal actual id(Vincular a la herramienta,LLM invisibles)
     * @param senderAssistantId El agente hablante actual. id(Vincular a la herramienta,LLM invisibles)
     * @param onReply channel_reply devolución de llamada:Conservar el contenido en el chat grupal
     * @param onPass channel_pass devolución de llamada:Registra a los que no hablan en esta ronda. activity(Opcional reason)
     * @param contextProvider channel_read_context devolución de llamada:Devuelve el texto del mensaje del historial formateado
     * @return conjunto de tres piezas(ToolDef, Ejecutar función),La persona que llama puede registrarse en un ToolRegistry temporal o transferir ToolDefinition
     */
    fun createChannelTools(
        groupChatId: String,
        senderAssistantId: String,
        onReply: suspend (String) -> Unit,
        onPass: suspend (String?) -> Unit,
        contextProvider: suspend (Int) -> String,
    ): List<Pair<ToolRegistry.ToolDef, ChannelToolFn>> {
        // Secuencia de inyección de tres piezas.:
        // reply → pass → read_context
        val reply = ChannelReplyTool(groupChatId, senderAssistantId, onReply)
        val pass = ChannelPassTool(onPass)
        val readContext = ChannelReadContextTool(groupChatId, contextProvider)
        return listOf(
            reply.toolDef() to { args: Map<String, String> -> reply.execute(args) },
            pass.toolDef() to { args: Map<String, String> -> pass.execute(args) },
            readContext.toolDef() to { args: Map<String, String> -> readContext.execute(args) },
        )
    }

    /**
     * v1.0.53 Phase 5: Creación de un conjunto de herramientas para sesiones telefónicas de chat grupal,Y devolver directamente los consumibles de LLM [ToolDefinition] lista
     * Mapeo de ejecutor con búsqueda por nombre(name → suspend fn).
     *
     * llamador(GroupChatScheduler.invokeAgent)usar [ToolDefinition] Lista pasada a streamChat,
     * Se envía por nombre utilizando el mapa ejecutor después de recibir ToolCallDelta.
     *
     * @return Pair(ToolDefinitions lista, Mapeo de ejecutores)
     */
    fun createChannelToolDefinitions(
        groupChatId: String,
        senderAssistantId: String,
        onReply: suspend (String) -> Unit,
        onPass: suspend (String?) -> Unit,
        contextProvider: suspend (Int) -> String,
    ): Pair<List<ToolDefinition>, Map<String, ChannelToolFn>> {
        val pairs = createChannelTools(groupChatId, senderAssistantId, onReply, onPass, contextProvider)
        val definitions = pairs.map { it.first.toToolDefinition() }
        val executors = pairs.associate { it.first.name to it.second }
        return definitions to executors
    }
}
