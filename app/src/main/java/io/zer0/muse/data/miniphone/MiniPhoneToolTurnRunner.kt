package io.zer0.muse.data.miniphone

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Una ronda de generación de herramientas en mensajes privados en pequeños teléfonos celulares.
 *
 * No hay ningún enlace de aprobación de sesión aquí.,Nadie mira la pantalla.:La superficie de la herramienta consta de [MiniPhoneToolPolicy] estrecho,
 * Ejecute únicamente herramientas que estén en la lista blanca y cuyo nivel de riesgo no sea ALTO. El resultado de la ejecución se completa en el mensaje HERRAMIENTA y luego continúa con la siguiente ronda.,
 * Se cierra con un texto cuando termina el turno o el modelo ya no necesita la herramienta.
 *
 * Sólo emitido a terceros [MpReplyEvent]:El módulo no puede obtener el nombre de la herramienta, los parámetros y el valor de retorno.,Sólo puedes ver "lo que estás haciendo".
 *
 * @param chatService Generar entrada;esta vacio(Modelo no configurado)Esta ronda falló directamente
 * @param toolRegistry Fuente de herramienta;Cuando está vacío, no se le dan herramientas al modelo.,Degenerar en respuesta de texto plano
 * @param timeoutMs ronda completa(Todas las ruedas de herramientas incluidas.)tiempo de espera difícil
 * @param now fuente de tiempo,Pruebe usarlo para concretar el mensaje de relleno `createdAt`
 */
class MiniPhoneToolTurnRunner(
    private val chatService: ChatService?,
    private val toolRegistry: ToolRegistry?,
    private val timeoutMs: Long = REPLY_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * flujo frío:Cada colección ejecuta una ronda completa de generación.
     *
     * secuencia de eventos:varios [MpReplyEvent.Activity](Cada herramienta llama a la anterior.) → una pieza
     * [MpReplyEvent.Text];Solo uno cuando se agota el tiempo, no hay texto o la entrada de generación no está disponible [MpReplyEvent.Failed].
     */
    fun reply(
        messages: List<UIMessage>,
        temperature: Float?,
        maxTokens: Int?,
    ): Flow<MpReplyEvent> = flow {
        val service = chatService
        if (service == null) {
            emit(MpReplyEvent.Failed)
            return@flow
        }
        val tools = resolveTools()
        val conversation = messages.toMutableList()
        val answer = withTimeoutOrNull(timeoutMs) {
            converge(service, tools, conversation, temperature, maxTokens) { kind ->
                emit(MpReplyEvent.Activity(kind))
            }
        }
        val text = answer?.trim().orEmpty()
        if (text.isEmpty()) emit(MpReplyEvent.Failed) else emit(MpReplyEvent.Text(text))
    }

    /**
     * Corre hasta que obtengas una oración que no requiera herramientas.
     *
     * Más de varias rondas con solicitud de herramienta.;Cuando todavía necesitas herramientas al final de la ronda, o no hay herramientas disponibles desde el principio,Esta ronda termina sin herramientas.
     */
    private suspend fun converge(
        service: ChatService,
        tools: List<ToolDefinition>,
        conversation: MutableList<UIMessage>,
        temperature: Float?,
        maxTokens: Int?,
        onActivity: suspend (MpActivityKind) -> Unit,
    ): String {
        var rounds = 0
        while (true) {
            val useTools = tools.takeIf { rounds < MAX_TOOL_ROUNDS && it.isNotEmpty() }
            val completion = request(service, conversation, useTools, temperature, maxTokens)
            val calls = completion.toolCalls.orEmpty()
            if (useTools == null || calls.isEmpty()) return completion.text
            rounds += 1
            conversation += completion.toAssistantMessage(calls)
            calls.forEach { call ->
                onActivity(MiniPhoneToolPolicy.activityKindOf(call.name))
                conversation += executeTool(call)
            }
        }
    }

    /** Una solicitud sin transmisión. La ronda final se realiza explícitamente sin herramientas.,toolChoice ya no está especificado. */
    private suspend fun request(
        service: ChatService,
        conversation: List<UIMessage>,
        tools: List<ToolDefinition>?,
        temperature: Float?,
        maxTokens: Int?,
    ): ChatCompletion = service.completeText(
        messages = conversation,
        temperature = temperature,
        maxTokens = maxTokens,
        tools = tools,
        toolChoice = TOOL_CHOICE.takeIf { tools != null },
    )

    /** Ejecutar una llamada de herramienta,Envuelva el resultado en un mensaje de HERRAMIENTA para completarlo. */
    private suspend fun executeTool(call: ToolCall): UIMessage = UIMessage(
        role = MessageRole.TOOL,
        content = toolOutput(call),
        toolCallId = call.id,
        createdAt = now(),
    )

    /**
     * Valor de retorno de la herramienta(O una descripción de degradación).
     *
     * Verifique la lista blanca nuevamente antes de la ejecución.:El modelo puede mostrar nombres de herramientas que no están incluidos en la solicitud.,No se puede implementar basándose en una sola palabra.
     */
    private suspend fun toolOutput(call: ToolCall): String {
        val registry = toolRegistry ?: return TOOLS_UNAVAILABLE
        if (!MiniPhoneToolPolicy.isAllowed(call.name)) return TOOL_NOT_ALLOWED
        return resultOf { registry.executeFromJson(call.name, call.arguments) }
            .onError { msg, t -> Logger.w(TAG, "Excepción de ejecución de herramienta celular pequeña: ${t?.message ?: msg}") }
            .getOrNull()
            ?: TOOL_FAILED
    }

    /** Herramientas disponibles actualmente;Si el montaje falla, se manejará como \"No hay herramientas para esta ronda\".,No afecta las respuestas de texto. */
    private fun resolveTools(): List<ToolDefinition> {
        val registry = toolRegistry ?: return emptyList()
        return resultOf { MiniPhoneToolPolicy.filter(registry.listToolsAsToolDefinitions(null)) }
            .onError { msg, t -> Logger.w(TAG, "No se pudieron ensamblar pequeñas herramientas para teléfonos celulares. No se proporcionan herramientas para esta ronda.: ${t?.message ?: msg}") }
            .getOrNull()
            .orEmpty()
    }

    private fun ChatCompletion.toAssistantMessage(calls: List<ToolCall>): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        content = text,
        toolCalls = calls,
        createdAt = now(),
    )

    private companion object {
        private const val TAG = "MiniPhoneToolTurnRunner"

        /** Mismo calibre que las tareas programadas:Una herramienta de tres ruedas es suficiente para comprobar una vez y responder otra vez.,Un poco más y el modelo girará en círculos. */
        private const val MAX_TOOL_ROUNDS = 3

        /** Los mensajes privados están esperando sincrónicamente:Después de más de un minuto, el usuario se fue temprano.,Es mejor decir \"la otra parte no respondió\". */
        private const val REPLY_TIMEOUT_MS = 60_000L

        private const val TOOL_CHOICE = "auto"

        private const val TOOLS_UNAVAILABLE = "[La herramienta no está disponible] Actualmente no existe ninguna herramienta ejecutable."
        private const val TOOL_NOT_ALLOWED = "[No autorizado] Esta herramienta no se puede utilizar en teléfonos celulares pequeños."
        private const val TOOL_FAILED = "[falló] Error de ejecución de herramienta"
    }
}
