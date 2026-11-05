package io.zer0.muse.channel

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.tools.captureLargeToolOutput
import io.zer0.muse.transformer.TemplateTransformer
import io.zer0.muse.transformer.TransformContext
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
internal data class ChannelAgentCheckpoint(
    val dispatchId: String,
    val workingMessages: List<UIMessage>,
    val inFlightToolCallId: String? = null,
    val sideEffectOutcomeUnknown: Boolean = false,
)

internal const val CHANNEL_AGENT_CHECKPOINT_UNREADABLE = "CHECKPOINT_UNREADABLE"

/** Código de error final de la verificación previa de ventana en cola que excede: la cola de espera agotó la ventana de respuesta pasiva. */
internal const val REPLY_WINDOW_EXPIRED_BY_QUEUE = "La ventana de respuesta pasiva ha expirado(Tiempo de espera de cola)"

/**
 * Comprobación previa de la cola fuera de la ventana: si la ventana de respuesta pasiva se ha agotado debido a la cola de espera.
 *
 * Sólo tiene efecto práctico sobre QQ; otras plataformas [ChannelAutoReply.replyWindowDeadlineMillis] Regresar [Long.MAX_VALUE],
 * Esto siempre es falso. Cuando se devuelve verdadero, la persona que llama debe regresar directamente al estado final y ya no ingresar al modelo.
 */
internal fun queuedReplyWindowExpired(inbound: ChannelInbox.Inbound, nowMillis: Long): Boolean =
    nowMillis >= ChannelAutoReply.replyWindowDeadlineMillis(inbound.platform, inbound.timestamp)

internal class ChannelAgentCheckpointUnreadableException(cause: Throwable) :
    IllegalStateException("El punto de interrupción de recuperación del agente de canal no se puede leer", cause)

private sealed interface ChannelAgentRunResult {
    data class Ready(val text: String) : ChannelAgentRunResult
    data object Retry : ChannelAgentRunResult
    data class Blocked(val errorCode: String) : ChannelAgentRunResult
}

private data class ChannelToolExecution(
    val content: String,
    val outcomeUnknown: Boolean,
)

/** Incertidumbre en la ejecución de herramientas de fusión conservadoras,Evite los posibles efectos secundarios de volver a correr durante la recuperación posterior. */
internal fun channelToolOutcomeUnknown(
    previousUnknown: Boolean,
    recoveringUncertainCall: Boolean,
    executionOutcomeUnknown: Boolean,
): Boolean = previousUnknown || recoveringUncertainCall || executionOutcomeUnknown

/** NORMAL Cuando una herramienta arroja una excepción o devuelve un error estandarizado,Los efectos secundarios se consideran conservadoramente desconocidos. */
internal fun channelToolResultUnknown(risk: ToolRiskLevel, executionThrew: Boolean, result: String): Boolean {
    return risk == ToolRiskLevel.NORMAL &&
        (executionThrew || result.startsWith("Error:", ignoreCase = true))
}

private const val AGENT_CHECKPOINT_PREFIX = "muse.channel.agent-checkpoint.v1:"

internal suspend fun encryptChannelAgentCheckpoint(checkpoint: ChannelAgentCheckpoint): String {
    val encoded = AppJson.encodeToString(ChannelAgentCheckpoint.serializer(), checkpoint)
    return SecureKeyStore.encrypt(AGENT_CHECKPOINT_PREFIX + encoded)
}

@Suppress("TooGenericExceptionCaught") // Checkpoint decoding must classify every storage/format failure as terminal.
internal suspend fun decryptChannelAgentCheckpoint(encrypted: String): ChannelAgentCheckpoint {
    return try {
        val plaintext = SecureKeyStore.decryptOrNull(encrypted)
            ?: error("No se puede descifrar el punto de interrupción de recuperación del agente del canal")
        if (!plaintext.startsWith(AGENT_CHECKPOINT_PREFIX)) {
            error("El formato del punto de interrupción de recuperación del agente de canal no coincide")
        }
        AppJson.decodeFromString(
            ChannelAgentCheckpoint.serializer(),
            plaintext.removePrefix(AGENT_CHECKPOINT_PREFIX),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw ChannelAgentCheckpointUnreadableException(error)
    }
}

/**
 * v2.0: Respuesta automática del canal. — Bucle cerrado de mensajería entrante(recibido → correr una ronda → Devolución de datos a la fuente).
 *
 * por [ChannelInbox] Controlador de cola de persistencia:Los mensajes entrantes se colocan de manera confiable después de su colocación,si existe
 * encender [ChannelConfig.autoReply] configuración del canal,Luego atar/El asistente predeterminado ejecuta una ronda de conversaciones que no se transmiten,
 * Y enviar la respuesta de vuelta a la fuente del mensaje a través del canal correspondiente.(from).
 *
 * restricciones de diseño:
 *  - consumidor único global:Los eventos permanecen persistentes hasta que se completa el procesamiento,Continuar la distribución después de reiniciar el proceso.;
 *  - Si falla, presione Escape para intentarlo nuevamente.,Los resultados generados se comercializan primero y luego se envían.;
 *  - El ACK entrante solo acusa recibo por parte de la cola local,Las ventanas de envío externo se manejan con semántica de al menos una vez.
 */
@Suppress("LargeClass") // Durable dispatch, agent recovery, and channel tool policy share one lifecycle owner.
class ChannelAutoReply(
    private val channelManager: ChannelManager,
    private val chatService: ChatService,
    private val assistantRepository: AssistantRepository,
    private val context: Context,
    private val appScope: CoroutineScope,
    /** v2.0.1: Perfil de usuario(Nombre del asistente/Apodo de usuario) — Proporciona representación variable de plantilla de Word de aviso del sistema. */
    private val settings: SettingsRepository,
    /** v2.0.1: Registro de herramientas — Conjunto de herramientas del asistente de herencia de conversaciones de canales(Excluir herramientas de chat grupal y herramientas de alto riesgo). */
    private val toolRegistry: ToolRegistry,
    /** v2.0.1: ayuda visual — Cuando el modelo no admite visión, la imagen se convertirá en una descripción de texto.(Ruta de degradación). */
    private val visionBridge: VisionBridge,
) {
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var dispatcherJob: Job? = null

    /** v2.0.1: renderizador de plantillas — Reemplazado consistentemente con el enlace de chat principal. {{char}}/{{user}} y otras variables. */
    private val templateTransformer = TemplateTransformer(context)

    /** Montar la escucha entrante(idempotente,App Llamado una vez al inicio). */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // App startup must keep retrying recoverable storage/key-store failures.
    fun start() {
        ChannelInbox.attach(context)
        ChannelConversationStore.attach(context)
        ChannelInbox.journal.setPendingListener {
            wakeups.trySend(Unit)
        }
        if (dispatcherJob?.isActive != true) {
            dispatcherJob = appScope.launch {
                while (currentCoroutineContext().isActive) {
                    try {
                        ChannelInbox.journal.recoverInterruptedDispatches()
                    } catch (error: Exception) {
                        Logger.e(TAG, "No se pudo restaurar el evento del canal interrumpido: ${error.message}", error)
                        kotlinx.coroutines.delay(DISPATCH_STATE_RETRY_DELAY_MS)
                        continue
                    }
                    drainPending()
                    val retryDelay = ChannelInbox.journal.nextRetryDelayMillis()
                    if (retryDelay == null) {
                        wakeups.receive()
                    } else if (retryDelay > 0L) {
                        withTimeoutOrNull(retryDelay) { wakeups.receive() }
                    }
                }
            }
        }
        wakeups.trySend(Unit)
    }

    private suspend fun drainPending() {
        while (currentCoroutineContext().isActive) {
            if (!drainOnePending()) break
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun claimNextPendingOrDelay(): ChannelInbox.Inbound? = try {
        ChannelInbox.journal.claimNextPending()
    } catch (error: Exception) {
        Logger.e(TAG, "No se pudo recibir el evento entrante del canal: ${error.message}", error)
        kotlinx.coroutines.delay(DISPATCH_STATE_RETRY_DELAY_MS)
        null
    }

    @Suppress("TooGenericExceptionCaught") // Convert unexpected handler failures to a durable retry outcome.
    private suspend fun drainOnePending(): Boolean {
        val inbound = claimNextPendingOrDelay() ?: return false
        val outcome = try {
            handle(inbound)
        } catch (error: CancellationException) {
            runCatching { ChannelInbox.journal.releaseDispatch(inbound.dispatchId) }
            throw error
        } catch (error: Exception) {
            Logger.w(TAG, "Excepción de manejo de respuesta automática,Vuelve a intentarlo más tarde: ${error.message}", error)
            HandleResult.Retry
        }
        return persistOutcome(inbound, outcome)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun persistOutcome(inbound: ChannelInbox.Inbound, outcome: HandleResult): Boolean = try {
        when (outcome) {
            is HandleResult.Sent -> {
                ChannelInbox.journal.completeDispatch(inbound.dispatchId, ignored = false)
                appScope.launch {
                    runCatching { maybeCompress(outcome.config, outcome.from) }
                        .onFailure { error -> Logger.w(TAG, "La compresión del contexto falló: ${error.message}") }
                }
            }
            HandleResult.Ignored -> ChannelInbox.journal.completeDispatch(inbound.dispatchId, ignored = true)
            is HandleResult.Blocked -> ChannelInbox.journal.blockDispatch(inbound.dispatchId, outcome.errorCode)
            HandleResult.Retry -> ChannelInbox.journal.retryDispatch(inbound.dispatchId)
        }
        true
    } catch (error: Exception) {
        Logger.e(TAG, "Responder automáticamente al estado de distribución y la realización del pedido falló.: ${error.message}", error)
        false
    }

    private suspend fun handle(inbound: ChannelInbox.Inbound): HandleResult {
        val text = inbound.summary.trim()
        return if (text.isBlank()) HandleResult.Ignored else handleMessage(inbound, text)
    }

    private suspend fun handleMessage(inbound: ChannelInbox.Inbound, text: String): HandleResult {
        channelManager.refresh()
        val config = selectAutoReplyConfig(channelManager.channels.value, inbound)
        if (config == null) return HandleResult.Ignored
        ChannelRelayLedger.beginTurn()
        // Verificación previa de la cola fuera de la ventana: el juicio de la ventana originalmente solo se realiza en runAgent, después de que el mensaje se coloca en la cola y pasa a través de la ventana
        // De todos modos, primero revisé una ronda del modelo, solo para descubrir que no se puede enviar en absoluto.
        // Debe ser anterior a appendTurn: de lo contrario, se dejará un mensaje de usuario sin respuesta en la sesión, contaminando el contexto posterior.
        val now = System.currentTimeMillis()
        if (queuedReplyWindowExpired(inbound, now)) {
            val deadline = replyDeadline(inbound)
            Logger.w(
                TAG,
                "La cola de mensajes entrantes excede la ventana,Saltar llamada de modelo(platform=${inbound.platform}, from=${inbound.from}, " +
                    "espera ${now - inbound.timestamp}ms, deadline=$deadline, now=$now)",
            )
            return HandleResult.Blocked(REPLY_WINDOW_EXPIRED_BY_QUEUE)
        }
        ChannelConversationStore.appendTurn(
            config.id,
            inbound.from,
            ChannelConversationStore.Turn(
                role = "user",
                text = text,
                mediaKind = inbound.mediaKind,
                mediaBase64 = inbound.mediaBase64,
                mediaPath = inbound.mediaPath,
                dispatchId = inbound.dispatchId,
            ),
        )
        if (ChannelRelayLedger.isEcho(config.id, text, System.currentTimeMillis())) {
            Logger.i(TAG, "Eco ignorado (coincide con un reenvío reciente): channel=${config.id}")
            return HandleResult.Ignored
        }
        return when (val reply = prepareReply(inbound, config)) {
            is ReplyPreparation.Ready -> sendPreparedReply(inbound, config, reply.text)
            ReplyPreparation.Ignored -> HandleResult.Ignored
            is ReplyPreparation.Blocked -> HandleResult.Blocked(reply.errorCode)
            ReplyPreparation.Retry -> HandleResult.Retry
        }
    }

    private suspend fun prepareReply(inbound: ChannelInbox.Inbound, config: ChannelConfig): ReplyPreparation {
        val prepared = inbound.preparedReply
        if (prepared.isNotBlank()) return ReplyPreparation.Ready(prepared)
        return when (val generated = runAgent(config, inbound.from, inbound.dispatchId, replyDeadline(inbound))) {
            is ChannelAgentRunResult.Blocked -> ReplyPreparation.Blocked(generated.errorCode)
            ChannelAgentRunResult.Retry -> ReplyPreparation.Retry
            is ChannelAgentRunResult.Ready -> if (generated.text.isBlank()) {
                Logger.w(TAG, "El modelo devuelve una respuesta vacía,Este mensaje entrante está marcado para ignorar")
                ReplyPreparation.Ignored
            } else if (ChannelInbox.journal.savePreparedReply(inbound.dispatchId, generated.text)) {
                ReplyPreparation.Ready(generated.text)
            } else {
                ReplyPreparation.Retry
            }
        }
    }

    private suspend fun sendPreparedReply(inbound: ChannelInbox.Inbound, config: ChannelConfig, reply: String): HandleResult {
        // v2.6.0 (AUD-CHANNEL-F9): appendTurn Pasar a después de enviar correctamente.
        // La implementación original appendTurn primero y luego envía:Envío fallido→Retry→Regenerar texto nuevo en la siguiente ronda. reply',
        // El segundo appendTurn se omite debido a la deduplicación de despachoId. → El inventario de sesiones es el primer texto.,
        // Y el usuario realmente recibe el segundo,deriva del contexto(La próxima conversación citará la versión incorrecta.).
        val contextTokenOverride = resolveReplyContextToken(inbound)
        return when (contextTokenOverride) {
            // v2.6.0 (AUD-CHANNEL-F7): El descifrado no llega al estado final BLOCKED,Ya no es infinito Retry
            is ContextTokenOverride.DecryptFailed -> HandleResult.Blocked(contextTokenOverride.detail)
            ContextTokenOverride.Absent, is ContextTokenOverride.Value -> {
                val tokenValue = (contextTokenOverride as? ContextTokenOverride.Value)?.value
                deliverSegments(inbound, config, reply, tokenValue)
            }
        }
    }

    /**
     * Envíe una respuesta por partes.
     *
     * Por qué segmentación:QQ Los funcionarios permiten hasta cinco respuestas pasivas a un mensaje entrante, mientras que los modelos
     * A menudo escupe varios párrafos con líneas en blanco. —— Si lo publicas directamente, se convertirá en un gran espacio en blanco, pero si lo divides en varios pelos, parecerá una persona real.
     *
     * Reintentar semántica (clave):
     *  - No se envió ni un solo párrafo. → Se puede volver a intentar, pero error de configuración (no en el grupo/Sin permiso) estado final directo
     *  - enviado parcialmente → **no lo vuelvas a intentar**, de lo contrario el usuario recibirá un duplicado de los primeros párrafos
     */
    private suspend fun deliverSegments(
        inbound: ChannelInbox.Inbound,
        config: ChannelConfig,
        reply: String,
        tokenValue: String?,
    ): HandleResult {
        val segments = ChannelReplyFormatter.plan(reply)
        if (segments.isEmpty()) {
            Logger.w(TAG, "La respuesta está vacía después de darle forma.,Marcar este artículo como ignorado")
            return HandleResult.Ignored
        }
        val qqEventIdOverride = qqReplyEventIdOverride(
            platform = inbound.platform,
            target = inbound.from,
            sourceEventId = inbound.sourceEventId,
            receivedAt = inbound.timestamp,
        )
        val isQq = inbound.platform == ChannelPlatform.QQ.name
        // msg_seq Debe incrementarse dentro del mismo msg_id; al volver a intentarlo, continúe con la cantidad de intentos para evitar la deduplicación de la plataforma.
        // El límite superior depende de la plataforma: 5 veces de chat grupal y 4 veces de chat individual. La plataforma rechazará directamente el número de serie que supere el límite.
        val maxSeq = QqMsgIdCache.maxRepliesFor(inbound.from)
        val baseSeq = (inbound.attemptCount + 1).coerceIn(1, maxSeq)

        var sent = 0
        var lastError: String? = null
        for ((index, segment) in segments.withIndex()) {
            val result = channelManager.sendTextWithOptions(
                channelId = config.id,
                text = segment,
                options = ChannelSendOptions(
                    targetOverride = inbound.from.takeIf { it.isNotBlank() },
                    contextTokenOverride = tokenValue,
                    sourceEventIdOverride = qqEventIdOverride,
                    sourceEventSequenceOverride = if (isQq) (baseSeq + index).coerceAtMost(maxSeq) else null,
                ),
            )
            if (!result.ok) {
                lastError = result.detail
                break
            }
            sent += 1
        }
        return finishDelivery(inbound, config, segments, sent, lastError)
    }

    /** Esta respuesta debe enviarse antes de esa fecha; de lo contrario, se cerrará la ventana de respuesta pasiva. */
    private fun replyDeadline(inbound: ChannelInbox.Inbound): Long =
        replyWindowDeadlineMillis(inbound.platform, inbound.timestamp)

    /** Se emitieron varios párrafos para determinar el estado final en función de la situación real, y el“Lo que realmente reciben los usuarios”Vuelve a escribir a la sesión. */
    private suspend fun finishDelivery(
        inbound: ChannelInbox.Inbound,
        config: ChannelConfig,
        segments: List<String>,
        sent: Int,
        lastError: String?,
    ): HandleResult {
        if (sent == 0) {
            Logger.w(TAG, "No se pudo enviar la respuesta automática(${inbound.platform}): $lastError")
            return if (QqSendErrorMapper.isPermanent(lastError)) {
                HandleResult.Blocked(lastError.orEmpty())
            } else {
                HandleResult.Retry
            }
        }
        // Registre únicamente la parte realmente enviada: la respuesta de la biblioteca debe ser igual a lo que ve el usuario (F9 mismo calibre),
        // De lo contrario, el modelo de la siguiente ronda pensará que ha terminado de hablar y luego repetirá la segunda mitad.
        ChannelConversationStore.appendTurn(
            config.id,
            inbound.from,
            ChannelConversationStore.Turn(
                role = "assistant",
                text = segments.take(sent).joinToString("\n"),
                dispatchId = inbound.dispatchId,
            ),
        )
        val partial = if (sent < segments.size) "(${sent}/${segments.size} párrafo)" else ""
        Logger.i(TAG, "Respuesta automática enviada${partial}(${inbound.platform} ← ${inbound.from})")
        return HandleResult.Sent(config, inbound.from)
    }

    private sealed interface HandleResult {
        data class Sent(val config: ChannelConfig, val from: String) : HandleResult
        data class Blocked(val errorCode: String) : HandleResult
        data object Ignored : HandleResult
        data object Retry : HandleResult
    }

    private sealed interface ReplyPreparation {
        data class Ready(val text: String) : ReplyPreparation
        data class Blocked(val errorCode: String) : ReplyPreparation
        data object Ignored : ReplyPreparation
        data object Retry : ReplyPreparation
    }

    private sealed interface ContextTokenOverride {
        data class Value(val value: String?) : ContextTokenOverride

        /** Sin token (plataforma no compatible/token vacío)—— Libere normalmente y envíe sin token. */
        data object Absent : ContextTokenOverride

        /**
         * v2.6.0 (AUD-CHANNEL-F7): Hay un token cifrado pero se produjo un error en el descifrado (como un error en el almacén de claves).
         * El mismo calibre que el punto de control ilegible cambia al estado final BLOQUEADO, ya no es infinito Retry ——
         * De lo contrario, se volverá a intentar cada 5 minutos y el descifrado fallará cada vez, ocupando el tiempo del despachador compartido y eliminando los registros.
         */
        data class DecryptFailed(val detail: String) : ContextTokenOverride
    }

    // AUD-CHANNEL-F7: Si el descifrado falla, es necesario detectar cualquier excepción.(Keystore No disponible/Texto cifrado corrupto/Formato anormal
    // se puede clasificar como“El token no es legible”),Mismo calibre que el punto de control ilegible hasta el estado final;Aquí hay una exención punto por punto.
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private suspend fun resolveReplyContextToken(inbound: ChannelInbox.Inbound): ContextTokenOverride {
        val isSupported = inbound.platform == ChannelPlatform.WECLAW.name ||
            inbound.platform == ChannelPlatform.DINGTALK.name
        if (!isSupported) return ContextTokenOverride.Absent

        val encrypted = ChannelInbox.journal.encryptedReplyContextToken(inbound.dispatchId)
            ?: inbound.encryptedReplyContextToken
        if (encrypted.isBlank()) return ContextTokenOverride.Absent

        val restored = try {
            when (inbound.platform) {
                ChannelPlatform.WECLAW.name -> restoreWeClawReplyContextToken(encrypted)
                ChannelPlatform.DINGTALK.name -> restoreDingtalkReplyContextToken(encrypted)
                else -> null
            }
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.e(TAG, "Error al descifrar el token de contexto de devolución de datos(${inbound.platform}): ${error.message}", error)
            return ContextTokenOverride.DecryptFailed("context_token_unreadable")
        }
        return ContextTokenOverride.Value(restored)
    }

    /** Ejecutar una conversación(Contexto de conversación del canal + bucle de herramientas);Presione para recuperar si falla/Clasificación irrecuperable. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "TooGenericExceptionCaught")
    private suspend fun runAgent(config: ChannelConfig, from: String, dispatchId: String, deadlineAt: Long): ChannelAgentRunResult {
        val restoredCheckpoint = try {
            loadAgentCheckpoint(dispatchId)
        } catch (error: ChannelAgentCheckpointUnreadableException) {
            Logger.e(TAG, "El punto de interrupción de recuperación del agente de canal no se puede leer,Cambiar a revisión manual: ${error.message}", error)
            return ChannelAgentRunResult.Blocked(CHANNEL_AGENT_CHECKPOINT_UNREADABLE)
        }
        return try {
            val assistant = resolveAssistant(config)
            // v2.0.1: Variables de plantilla para representar palabras de aviso del sistema — Anteriormente el texto original se enviaba directamente,El modelo repetirá "{{char}}" Literal.
            val renderedSystem = if (restoredCheckpoint == null) renderSystemPrompt(assistant) else ""
            // v2.0.1: El mismo modelo de análisis de reglas que el enlace principal del chat./Provider — Arreglar la solicitud anterior sin modelo,
            // Caer al primer modelo de Proveedor(Se muestra como"Bajar al modelo gratuito por defecto")problema.
            val (model, providerConfig) = resolveModelAndProvider(assistant)
            val workingMessages = restoredCheckpoint?.workingMessages?.toMutableList()
                ?: buildInitialAgentMessages(config, from, renderedSystem, model)
            if (workingMessages.none { it.role == MessageRole.USER }) {
                if (restoredCheckpoint != null) {
                    throw ChannelAgentCheckpointUnreadableException(
                        IllegalStateException("Agent checkpoint does not contain a user message"),
                    )
                }
                // cobertura defensiva:En teoría, los mensajes entrantes se almacenan en la base de datos.,No llegará tan lejos.
                return ChannelAgentRunResult.Retry
            }
            // v2.0.1: Herencia de herramientas — Conjunto de herramientas que proviene de la misma fuente que el chat.(Lista blanca del asistente + excluir chat grupal/herramientas de alto riesgo).
            val toolDefs = resolveToolDefinitions(assistant)
            val offeredToolNames = toolDefs.mapTo(mutableSetOf()) { it.name }
            var checkpoint = restoredCheckpoint ?: ChannelAgentCheckpoint(
                dispatchId = dispatchId,
                workingMessages = workingMessages.toList(),
            )
            if (restoredCheckpoint != null) {
                checkpoint = executePendingToolCalls(workingMessages, offeredToolNames, checkpoint)
            }
            var finalText: String? = null
            var plainFallback = ""
            var round = 0
            // Condición de bucle con fecha límite:QQ La respuesta pasiva sólo tiene una ventana de 5 minutos. Si excede la ventana, se generará un mensaje en vano y no se podrá enviar.
            // El propósito de usar condiciones en lugar de pausa es permitir que las tres salidas de "agotamiento redondo/ventana excesiva/cerrada" se escriban en el mismo lugar.
            while (round < MAX_TOOL_ROUNDS && finalText == null && System.currentTimeMillis() < deadlineAt) {
                round++
                val budgetMs = deadlineAt - System.currentTimeMillis()
                val completion = withTimeoutOrNull(minOf(LLM_TIMEOUT_MS, budgetMs)) {
                    chatService.completeText(
                        messages = workingMessages,
                        model = model,
                        providerConfig = providerConfig,
                        temperature = assistant.temperature,
                        maxTokens = assistant.maxTokens,
                        tools = toolDefs.takeIf { it.isNotEmpty() },
                        mode = ChatRequestMode.CHAT,
                    )
                } ?: error(context.getString(R.string.channel_err_ai_timeout))
                plainFallback = completion.text
                val toolCalls = completion.toolCalls.orEmpty()
                if (toolCalls.isEmpty()) {
                    finalText = completion.text
                } else {
                    // Rellene los mensajes del asistente con tool_calls — OpenAI Los protocolos de compatibilidad requieren que un mensaje HERRAMIENTA lo preceda inmediatamente.
                    workingMessages += UIMessage(
                        role = MessageRole.ASSISTANT,
                        content = completion.text,
                        toolCalls = toolCalls,
                    )
                    checkpoint = checkpoint.copy(workingMessages = workingMessages.toList(), inFlightToolCallId = null)
                    persistAgentCheckpoint(checkpoint)
                    checkpoint = executePendingToolCalls(workingMessages, offeredToolNames, checkpoint)
                }
            }
            if (finalText == null && plainFallback.isBlank() && round > 0) {
                Logger.w(TAG, "La ventana de respuesta pasiva está agotada y no hay texto disponible; esta vez no se enviará | dispatchId=$dispatchId")
            }
            // Cuando se agotan los rounds y la boca sigue abierta,Rebobina la última salida de texto sin formato.
            val text = io.zer0.muse.transformer.stripThinkTags(finalText ?: plainFallback)
                .trim()
                .take(MAX_REPLY_LENGTH)
            ChannelAgentRunResult.Ready(text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ChannelAgentCheckpointUnreadableException) {
            Logger.e(TAG, "El punto de interrupción de recuperación del agente de canal no se puede leer,Cambiar a revisión manual: ${error.message}", error)
            ChannelAgentRunResult.Blocked(CHANNEL_AGENT_CHECKPOINT_UNREADABLE)
        } catch (error: Exception) {
            Logger.w(TAG, "Falló la generación de respuesta automática: ${error.message}")
            ChannelAgentRunResult.Retry
        }
    }

    private suspend fun buildInitialAgentMessages(
        config: ChannelConfig,
        from: String,
        renderedSystem: String,
        model: Model?,
    ): MutableList<UIMessage> {
        val conversation = ChannelConversationStore.conversation(config.id, from)
        val contextTurns = conversation?.turns?.takeLast(CONTEXT_TURNS).orEmpty()
        val imageContext = buildImageContext(contextTurns, model?.supportsVisionInput() == true)
        return buildList {
            if (renderedSystem.isNotBlank()) {
                add(UIMessage(role = MessageRole.SYSTEM, content = renderedSystem))
            }
            conversation?.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                add(UIMessage(role = MessageRole.SYSTEM, content = "Un resumen de una conversación anterior:$summary"))
            }
            contextTurns.forEach { turn ->
                add(buildInitialAgentTurn(config, from, turn, imageContext))
            }
        }.toMutableList()
    }

    private fun buildImageContext(turns: List<ChannelConversationStore.Turn>, visionCapable: Boolean): ChannelImageContext {
        val imageTurnAts = turns
            .filter { it.role == "user" && it.mediaKind == "image" && it.mediaBase64.isNotBlank() }
            .map { it.at }
        return ChannelImageContext(
            visionCapable = visionCapable,
            recentNativeAts = imageTurnAts.takeLast(MAX_NATIVE_VISION_TURNS).toSet(),
            recentFallbackAts = imageTurnAts.takeLast(MAX_FALLBACK_VISION_TURNS).toSet(),
        )
    }

    private suspend fun buildInitialAgentTurn(
        config: ChannelConfig,
        from: String,
        turn: ChannelConversationStore.Turn,
        imageContext: ChannelImageContext,
    ): UIMessage {
        val role = if (turn.role == "assistant") MessageRole.ASSISTANT else MessageRole.USER
        return if (turn.role == "user" && turn.mediaKind == "image" && turn.mediaBase64.isNotBlank()) {
            when {
                imageContext.visionCapable && turn.at in imageContext.recentNativeAts ->
                    UIMessage(
                        role = role,
                        content = turn.text.ifBlank { "[fotos]" },
                        imageBase64List = listOf(turn.mediaBase64),
                    )
                !imageContext.visionCapable && turn.at in imageContext.recentFallbackAts ->
                    UIMessage(role = role, content = resolveImageContent(config, from, turn))
                turn.mediaDescription.isNotBlank() -> UIMessage(role = role, content = turn.mediaDescription)
                else -> UIMessage(role = role, content = turn.text.ifBlank { "[fotos]" })
            }
        } else {
            UIMessage(role = role, content = turn.text.ifBlank { "[vacío]" })
        }
    }

    private data class ChannelImageContext(
        val visionCapable: Boolean,
        val recentNativeAts: Set<Long>,
        val recentFallbackAts: Set<Long>,
    )

    private suspend fun executePendingToolCalls(
        workingMessages: MutableList<UIMessage>,
        offeredToolNames: Set<String>,
        initialCheckpoint: ChannelAgentCheckpoint,
    ): ChannelAgentCheckpoint {
        val assistantIndex = workingMessages.indexOfLast {
            it.role == MessageRole.ASSISTANT && !it.toolCalls.isNullOrEmpty()
        }
        if (assistantIndex < 0) {
            if (initialCheckpoint.inFlightToolCallId != null) {
                throw ChannelAgentCheckpointUnreadableException(
                    IllegalStateException("Agent checkpoint references a missing tool call"),
                )
            }
            return initialCheckpoint
        }

        val toolCalls = workingMessages[assistantIndex].toolCalls.orEmpty()
        val resultIds = workingMessages.drop(assistantIndex + 1).asSequence()
            .filter { it.role == MessageRole.TOOL }
            .mapNotNull { it.toolCallId }
            .toMutableSet()
        var checkpoint = initialCheckpoint.copy(workingMessages = workingMessages.toList())
        val inFlightId = checkpoint.inFlightToolCallId
        if (inFlightId != null && toolCalls.none { it.id == inFlightId }) {
            throw ChannelAgentCheckpointUnreadableException(
                IllegalStateException("Agent checkpoint references a tool call outside the current batch"),
            )
        }
        if (inFlightId != null && inFlightId in resultIds) {
            checkpoint = checkpoint.copy(inFlightToolCallId = null)
            persistAgentCheckpoint(checkpoint)
        }

        for (toolCall in toolCalls) {
            if (toolCall.id in resultIds) continue
            checkpoint = executePendingToolCall(
                toolCall = toolCall,
                inFlightId = inFlightId,
                workingMessages = workingMessages,
                offeredToolNames = offeredToolNames,
                checkpoint = checkpoint,
            )
            resultIds += toolCall.id
        }
        return checkpoint
    }

    private suspend fun executePendingToolCall(
        toolCall: ToolCall,
        inFlightId: String?,
        workingMessages: MutableList<UIMessage>,
        offeredToolNames: Set<String>,
        checkpoint: ChannelAgentCheckpoint,
    ): ChannelAgentCheckpoint {
        val risk = ToolPermissionResolver.riskLevelFor(toolCall.name)
        val recoveringUncertainCall = toolCall.id == inFlightId
        var executionOutcomeUnknown = false
        val result = when {
            recoveringUncertainCall -> TOOL_OUTCOME_UNCERTAIN
            checkpoint.sideEffectOutcomeUnknown && risk == ToolRiskLevel.NORMAL ->
                TOOL_BLOCKED_AFTER_UNCERTAIN_ACTION
            !mayExecuteChannelToolCall(toolCall.name, offeredToolNames, risk) -> {
                Logger.w(TAG, "Denegar la ejecución de herramientas del canal que no estén autorizadas o requieran aprobación: ${toolCall.name}")
                "La herramienta no está autorizada o requiere aprobación interactiva y no se ejecuta la respuesta automática del canal."
            }
            else -> {
                if (risk == ToolRiskLevel.NORMAL) {
                    persistAgentCheckpoint(
                        checkpoint.copy(
                            workingMessages = workingMessages.toList(),
                            inFlightToolCallId = toolCall.id,
                        ),
                    )
                }
                val execution = executeChannelTool(toolCall)
                executionOutcomeUnknown = execution.outcomeUnknown
                execution.content
            }
        }
        val resultForModel =
            captureLargeToolOutput(
                context = context,
                filePrefix = "channel_${toolCall.id}",
                output = result,
            )
        workingMessages += UIMessage(
            role = MessageRole.TOOL,
            content = resultForModel,
            toolCallId = toolCall.id,
        )
        val updated = checkpoint.copy(
            workingMessages = workingMessages.toList(),
            inFlightToolCallId = null,
            sideEffectOutcomeUnknown = channelToolOutcomeUnknown(
                previousUnknown = checkpoint.sideEffectOutcomeUnknown,
                recoveringUncertainCall = recoveringUncertainCall,
                executionOutcomeUnknown = executionOutcomeUnknown,
            ),
        )
        persistAgentCheckpoint(updated)
        Logger.i(TAG, "Llamada a la herramienta de canal: ${toolCall.name}")
        return updated
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeChannelTool(toolCall: ToolCall): ChannelToolExecution {
        val risk = ToolPermissionResolver.riskLevelFor(toolCall.name)
        return try {
            val result = toolRegistry.executeFromJson(toolCall.name, toolCall.arguments)
            ChannelToolExecution(
                content = result,
                outcomeUnknown = channelToolResultUnknown(
                    risk = risk,
                    executionThrew = false,
                    result = result,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ChannelToolExecution(
                content = "La ejecución de la herramienta falló: ${error.message}",
                outcomeUnknown = channelToolResultUnknown(
                    risk = risk,
                    executionThrew = true,
                    result = "",
                ),
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // Corrupt ciphertext, schema, and dispatch identity are one terminal class.
    private suspend fun loadAgentCheckpoint(dispatchId: String): ChannelAgentCheckpoint? {
        val encrypted = ChannelInbox.journal.encryptedAgentCheckpoint(dispatchId).orEmpty()
        if (encrypted.isBlank()) return null
        return try {
            decryptChannelAgentCheckpoint(encrypted).also {
                check(it.dispatchId == dispatchId) { "El ID de envío del punto de interrupción de recuperación del agente de canal no coincide" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ChannelAgentCheckpointUnreadableException(error)
        }
    }

    private suspend fun persistAgentCheckpoint(checkpoint: ChannelAgentCheckpoint) {
        val encrypted = encryptChannelAgentCheckpoint(checkpoint)
        check(ChannelInbox.journal.saveEncryptedAgentCheckpoint(checkpoint.dispatchId, encrypted)) {
            "El punto de interrupción de recuperación del agente de canal no pudo persistir"
        }
    }

    /**
     * v2.0.1: Conjuntos de herramientas disponibles para canales — Misma fuente que el chat.([ToolRegistry.listToolsAsToolDefinitions]),
     * Filtrar por lista blanca de asistentes,y excluir chats grupales channel_* Herramientas y altos riesgos(HIGH)Herramientas.
     */
    private fun resolveToolDefinitions(assistant: AssistantEntity): List<ToolDefinition> {
        val configured = parseChannelToolAllowlist(assistant.toolIdsJson)
        if (configured == null) {
            Logger.w(TAG, "El formato de lista blanca de la herramienta asistente no es válido y la respuesta automática del canal no expone la herramienta.")
            return emptyList()
        }
        return runCatching {
            toolRegistry.listToolsAsToolDefinitions().filter { def ->
                val selectedByTool = configured.isEmpty() || def.name in configured
                val notGroupChatTool = !def.name.startsWith("channel_")
                val notHighRisk = ToolPermissionResolver.riskLevelFor(def.name) != ToolRiskLevel.HIGH
                selectedByTool && notGroupChatTool && notHighRisk
            }
        }.onFailure { e ->
            Logger.w(TAG, "Error al analizar el conjunto de herramientas: ${e.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * v2.0.1: degradación visual — Convierta imágenes en descripciones de texto a través de modelos visuales.(Los resultados se almacenan en caché. Turn,Cada imagen se analiza solo una vez.).
     * Devolver texto de marcador de posición en caso de error(No almacenar en caché,Reintentar la próxima vez).
     */
    private suspend fun resolveImageContent(config: ChannelConfig, from: String, turn: ChannelConversationStore.Turn): String {
        if (turn.mediaDescription.isNotBlank()) return turn.mediaDescription
        val prepared = runCatching {
            visionBridge.prepare(
                text = turn.text.ifBlank { "[fotos]" },
                images = listOf(turn.mediaBase64),
                userRequest = turn.text,
            )
        }.getOrNull()
        val text = prepared?.takeIf { it.success && it.text.isNotBlank() }?.text
        if (text != null) {
            ChannelConversationStore.updateTurnDescription(config.id, from, turn.at, text)
            return text
        }
        return "[fotos(La ayuda visual aún no está disponible)]" + if (turn.text.isNotBlank()) "\n${turn.text}" else ""
    }

    /**
     * v2.0.1: Compresión automática de contexto — rondas [COMPRESS_THRESHOLD] tiempo,
     * Fusionar la época más antigua en el resumen continuo mediante LLM;Solo falla al iniciar sesión,Inténtalo de nuevo la próxima vez que se active.
     */
    private suspend fun maybeCompress(config: ChannelConfig, from: String) {
        val conversation = ChannelConversationStore.conversation(config.id, from) ?: return
        if (conversation.turns.size <= COMPRESS_THRESHOLD) return
        val drainCount = conversation.turns.size - KEEP_TURNS
        if (drainCount <= 0) return
        val toCompress = conversation.turns.take(drainCount)
        val assistant = resolveAssistant(config)
        // v2.x: La compresión de resumen da prioridad al archivo "gadget" de enrutamiento del modelo auxiliar(Dejar en blanco para recurrir al asistente de análisis del modelo.)
        val routed = runCatching {
            io.zer0.muse.data.routing.UtilityModelRouter(settings)
                .resolve(io.zer0.muse.data.routing.UtilityTier.SMALL)
        }.getOrNull()
        val (model, providerConfig) = if (routed != null) {
            routed.second to routed.first
        } else {
            resolveModelAndProvider(assistant)
        }
        val prompt = buildString {
            if (conversation.summary.isNotBlank()) {
                appendLine("Resumen existente:${conversation.summary}")
            }
            appendLine("Nueva conversación:")
            toCompress.forEach { turn ->
                appendLine("${if (turn.role == "assistant") "asistente" else "Usuario"}:${turn.text}")
            }
            appendLine("Combine el contenido anterior en un resumen conciso de la conversación en español, conservando la información clave, el título y las tareas pendientes. Sólo se emite el texto resumido.")
        }
        val summary = withTimeoutOrNull(COMPRESS_TIMEOUT_MS) {
            runCatching {
                chatService.completeText(
                    messages = listOf(UIMessage(role = MessageRole.USER, content = prompt)),
                    model = model,
                    providerConfig = providerConfig,
                    maxTokens = SUMMARY_MAX_TOKENS,
                    mode = ChatRequestMode.UTILITY,
                )
            }.onFailure { e ->
                if (e !is CancellationException) {
                    io.zer0.muse.data.routing.UtilityModelHealth.record(
                        providerId = if (routed != null) routed.first.id else providerConfig?.id,
                        modelId = model?.id.orEmpty(),
                        message = e.message ?: e::class.simpleName.orEmpty(),
                    )
                }
            }.getOrThrow()
        }?.text?.let { io.zer0.muse.transformer.stripThinkTags(it).trim() } ?: return
        if (summary.isBlank()) return
        // v2.3.2: Reescritura condicional —— Los cartuchos recién llegados deben conservarse durante la compactación.(La implementación original de una cobertura total los desecharía),
        // Este resultado queda invalidado cuando el resumen ha sido actualizado por otra compresión.
        val applied =
            ChannelConversationStore.applyCompression(
                channelId = config.id,
                from = from,
                snapshotSummary = conversation.summary,
                drainedTurns = toCompress,
                summary = summary.take(SUMMARY_MAX_CHARS),
            )
        if (applied) {
            Logger.i(TAG, "El contexto está comprimido.(${config.id} ← $from): ${toCompress.size} resumen de fusión redonda")
        } else {
            Logger.i(TAG, "Los resultados de la compresión no se escriben.(${config.id} ← $from): La conversación ha sido actualizada.,Déjalo para intentarlo de nuevo la próxima vez.")
        }
    }

    /**
     * v2.0.1: El modelo que debe utilizar el asistente de análisis es el mismo que Provider — Mismas reglas que el enlace principal del chat.:
     * El modelId/providerId de la configuración del asistente tiene prioridad,Modelo de selección global alternativo,Luego regrese y active el primer modelo de Proveedor.
     */
    private suspend fun resolveModelAndProvider(assistant: AssistantEntity): Pair<Model?, ProviderConfig?> {
        val allProviders = runCatching { settings.getAllProviders() }.getOrDefault(emptyList())
        if (allProviders.isEmpty()) return null to null
        val activeProviderId = runCatching { settings.activeProviderIdFlow.first() }.getOrNull()
        val selectedModelId = runCatching { settings.selectedModelIdFlow.first() }.getOrNull()
        val assistantModelId = assistant.modelId?.takeIf { it.isNotBlank() }
        val assistantProviderId = assistant.providerId?.takeIf { it.isNotBlank() }
        val resolvedModel: Model? = if (assistantModelId != null && assistantProviderId != null) {
            allProviders.firstOrNull { it.id == assistantProviderId }
                ?.models?.firstOrNull { it.id == assistantModelId }
        } else {
            assistantModelId?.let { aid ->
                allProviders.firstOrNull { it.id == activeProviderId }
                    ?.models?.firstOrNull { it.id == aid }
                    ?: allProviders.flatMap { it.models }.firstOrNull { it.id == aid }
            }
        } ?: selectedModelId?.let { sid ->
            allProviders.firstOrNull { it.id == activeProviderId }
                ?.models?.firstOrNull { it.id == sid }
                ?: allProviders.flatMap { it.models }.firstOrNull { it.id == sid }
        } ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?.models?.firstOrNull()
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }?.models?.firstOrNull()
        val resolvedProvider = resolvedModel?.let { m -> allProviders.firstOrNull { it.id == m.providerId } }
            ?: allProviders.firstOrNull { it.id == activeProviderId && it.models.isNotEmpty() }
            ?: allProviders.firstOrNull { it.models.isNotEmpty() }
        return resolvedModel to resolvedProvider
    }

    /**
     * v2.0.1: Variables de plantilla del sistema de caracteres de representaciónPrompt({{char}}/{{user}}/Fecha, etc.).
     * El mismo conjunto que el enlace de chat principal. TemplateTransformer;La renderización falla y vuelve al texto original.,No bloquea las respuestas.
     */
    private suspend fun renderSystemPrompt(assistant: AssistantEntity): String {
        val raw = assistant.systemPrompt
        if (raw.isBlank() || (!raw.contains("{{") && !raw.contains("{%"))) return raw
        val rendered = runCatching {
            val userProfile = settings.getUserProfile()
            templateTransformer.transform(
                messages = listOf(UIMessage(role = MessageRole.SYSTEM, content = raw)),
                context = TransformContext(
                    extras = mapOf(
                        "user_nickname" to userProfile.userNickName,
                        "assistant_name" to (userProfile.assistantName ?: assistant.name),
                    ),
                ),
            ).firstOrNull()?.content
        }.onFailure { e ->
            Logger.w(TAG, "Falló la representación de la plantilla de Word del mensaje del sistema: ${e.message}")
        }.getOrNull()
        return rendered?.takeIf { it.isNotBlank() } ?: raw
    }

    /** v2.0.1: Se prefiere el asistente de vinculación de canales.,Volver al asistente predeterminado,Sé el primero en dar el paso;Lanza si no hay ningún ayudante(Capturado por runAgent). */
    private suspend fun resolveAssistant(config: ChannelConfig): AssistantEntity {
        val assistants = assistantRepository.observeAll.first()
        val bound = config.assistantId.takeIf { it.isNotBlank() }
            ?.let { id -> assistants.firstOrNull { it.id == id } }
        return bound
            ?: assistants.firstOrNull { it.id == "default" }
            ?: assistants.firstOrNull()
            ?: error(context.getString(R.string.channel_err_no_assistant))
    }

    companion object {
        private const val TAG = "ChannelAutoReply"

        /** Tiempo de espera de llamada de IA de una sola ronda(Consistente con las tareas programadas.,milisegundos). */
        private const val LLM_TIMEOUT_MS = 60_000L

    /** QQ Ventana de respuesta pasiva (valor oficial, consistente con chat grupal y chat único). */
    private const val QQ_PASSIVE_WINDOW_MS = 5 * 60 * 1000L

    /** El margen reservado para el envío en sí para evitar enviar hasta el último segundo y descubrir que la ventana se ha cerrado. */
    private const val SEND_MARGIN_MS = 15_000L

        /**
         * La hora de finalización de la ventana de respuesta pasiva (milisegundos).
         *
         * Solo tiene sentido para QQ: otras plataformas no tienen ventanas de respuesta pasiva, regrese [Long.MAX_VALUE] Indica no exceder nunca la ventana.
         * El juicio previo al envío y la verificación previa de la ventana de cola comparten este cálculo para evitar la desviación de los dos umbrales.
         */
        internal fun replyWindowDeadlineMillis(platform: String, receivedAtMillis: Long): Long = if (platform == ChannelPlatform.QQ.name) {
            receivedAtMillis + QQ_PASSIVE_WINDOW_MS - SEND_MARGIN_MS
        } else {
            Long.MAX_VALUE
        }

        /** Límite de texto de devolución,Evite que contenidos extremadamente largos excedan el límite de mensajes de cada plataforma. */
        private const val MAX_REPLY_LENGTH = 4_000
        private const val DISPATCH_STATE_RETRY_DELAY_MS = 1_000L

        /** v2.0.1: El límite de época más reciente proporcionado al modelo. */
        private const val CONTEXT_TURNS = 30

        /**
         * v2.0.1: Umbral de activación de compresión(rondas), retenga las rondas después de la compresión.
         *
         * X-5 Descripción: Prensa lateral del canal **turn(redondo)** en vez de **message(número de mensajes)** contando,
         * Y utilice ChannelConversationStore para almacenamiento independiente sin pasar por el proceso de Transformer. —— Compresión automática de conversaciones con el maestro.
         * (Ver [io.zer0.muse.transformer.CompressionPolicy]) es**Dos conjuntos de subsistemas**, los umbrales son deliberadamente independientes,
         * No fusionado con CompressionPolicy.
         */
        private const val COMPRESS_THRESHOLD = 40
        private const val KEEP_TURNS = 20

        /** v2.0.1: Tiempo de espera de llamada de compresión y límite de resumen. */
        private const val COMPRESS_TIMEOUT_MS = 30_000L
        private const val SUMMARY_MAX_TOKENS = 1200
        private const val SUMMARY_MAX_CHARS = 2_000

        /** v2.0.1: Límite del ciclo de herramienta. */
        private const val MAX_TOOL_ROUNDS = 5

        /** v2.0.1: El límite superior de imágenes transportadas dentro del contexto. — Las últimas N imágenes del modelo nativo y las últimas N imágenes del análisis degradado. */
        private const val MAX_NATIVE_VISION_TURNS = 4
        private const val MAX_FALLBACK_VISION_TURNS = 2
        private const val TOOL_OUTCOME_UNCERTAIN =
            "Tool execution outcome is uncertain after app recovery. " +
                "It was not repeated to avoid duplicate side effects. Ask the user to verify before retrying."
        private const val TOOL_BLOCKED_AFTER_UNCERTAIN_ACTION =
            "Tool not executed: an earlier side effect has an uncertain outcome. " +
                "Ask the user to verify it before another side effect."
    }
}

internal fun selectAutoReplyConfig(configs: List<ChannelConfig>, inbound: ChannelInbox.Inbound): ChannelConfig? {
    return configs.firstOrNull {
        it.enabled &&
            it.autoReply &&
            it.platform.name == inbound.platform &&
            it.id == inbound.sourceChannelId
    }
}

internal fun qqReplyEventIdOverride(
    platform: String,
    target: String,
    sourceEventId: String,
    receivedAt: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (platform != ChannelPlatform.QQ.name) return null
    val age = nowMillis - receivedAt
    // La caducidad debe devolver un valor nulo en lugar de una cadena vacía. La cadena vacía es un valor venenoso que "parece valioso":
    // Se utiliza QqMsgIdCache.replyContext en sentido descendente `sourceEventId != null` Determinar si tomar la rama de eventos,
    // La cadena vacía llegará a la rama y colapsará a nula en isNotBlank, evitando así por completo el msg_id disponible en el caché.
    return sourceEventId.takeIf {
        it.isNotBlank() && age in 0..QqMsgIdCache.windowFor(target)
    }
}
