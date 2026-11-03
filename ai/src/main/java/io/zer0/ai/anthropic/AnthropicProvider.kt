package io.zer0.ai.anthropic

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderCompat
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderPayloadNormalizer
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.UsageTokens
import io.zer0.ai.core.malformedStreamFrameError
import io.zer0.ai.core.toProviderException
import io.zer0.common.AppJson
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Returns the last system block that is stable when the final block is dynamic. */
internal fun anthropicCacheBreakpointIndex(systemBlockCount: Int): Int? = when {
    systemBlockCount <= 0 -> null
    systemBlockCount == 1 -> 0
    else -> systemBlockCount - 2
}

/**
 * Anthropic Claude Provider.
 *
 * API: Messages API (/v1/messages)
 *  - system es un campo de nivel superior(Extraído del SISTEMA UIMessage)
 *  - eventos de transmisión: content_block_delta(text_delta / thinking_delta / input_json_delta / signature_delta)
 *    / content_block_start / message_start / message_delta / message_stop / error
 *  - thinking modelo(claude-3-7-sonnet / claude-opus-4)cadena de pensamiento thinking_delta
 *  - Certificación: x-api-key + anthropic-version: 2023-06-01
 *
 * Phase 8.1 (M9): Admite almacenamiento en caché rápido. cuando [ProviderSpecificConfig.Anthropic.promptCaching]=true tiempo,
 *  Agregue la etiqueta cache_control al mensaje del sistema y al último mensaje del usuario,Claude El prefijo 5m/1h se almacenará en caché.
 *  Las solicitudes repetidas solo cuentan el 10% del costo del token de entrada,Ahorros significativos en escenas de diálogo largas.
 *
 * Phase 8.1 (M11): Nivel de razonamiento de soporte. Según [ChatRequest.reasoningLevel] Mapeo de la configuración del pensamiento:
 *  - OFF → thinking=disabled(No enviar campo de pensamiento.)
 *  - AUTO → No enviar campo de pensamiento.(Deja que el servidor decida)
 *  - LOW/MEDIUM/HIGH → thinking={type:enabled, budget_tokens:1000/2000/8000}
 *  - XHIGH → thinking={type:enabled, budget_tokens:16000}
 *
 * v1.80 (H-ANT1~4 / M-ANT1~11 / L-ANT1~11): Heredar ProviderHttpSupport cliente HTTP unificado,
 *  Arreglar la lectura de think_delta, el uso de herramientas de transmisión,error Eventos, reintentos de desconexión, errores estructurados,
 *  tool_result/tool_use Bloques, inferencia de tipo de imagen, extracción paginada, etc.
 */
class AnthropicProvider(
    config: ProviderConfig,
) : ProviderHttpSupport(config) {

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName

    /** Configuración específica antrópica analizada(specific Cuando sea nulo, presione escribir). */
    private val anthropicConfig: ProviderSpecificConfig.Anthropic
        get() = config.resolvedSpecific() as? ProviderSpecificConfig.Anthropic
            ?: ProviderSpecificConfig.Anthropic()

    // M-ANT9: writeTimeout Ya configurado de manera uniforme por ProviderHttpSupport,No más crear el tuyo propio client

    private val sseFactory by lazy { EventSources.createFactory(httpClient) }

    /**
     * v2.5.3 (P2-3): Eliminar el bloque de pensamiento de los mensajes históricos del asistente/firma.
     *
     * usado para 400 “Los bloques de pensamiento están vinculados a diferentes sesiones.”Autorreparación: la antigua firma de thinkblock no coincide con el nuevo contexto después de editar el historial,
     * Volver a intentarlo después de la eliminación permite que upstream regenere el pensamiento. Mantenga el cuerpo con el bloque tool_use.
     */
    private fun stripThinkingFromMessages(messages: List<UIMessage>): List<UIMessage> =
        messages.map { m ->
            val hasThinking =
                m.role == MessageRole.ASSISTANT &&
                    (!m.reasoning.isNullOrBlank() || !m.thinkingSignature.isNullOrBlank())
            if (hasThinking) {
                m.copy(reasoning = null, thinkingSignature = null, thinkingEncryptedContent = null)
            } else {
                m
            }
        }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = channelFlow {
        val producerScope = this
        // Revisión de corrección (2.0 B-25): callbackFlow → channelFlow — constante del canal interno UNLIMITED,
        // Elimine la devolución de llamadas. Flujo de capacidad fija de 64 en producción rápida./Pérdida de película muda bajo consumo lento(Misma solución que OpenAI).
        // v1.0.5: Provider Seguridad de las exportaciones — Primero haga una limpieza general de la lista UIMessage
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages,
            request.model,
        )
        // v2.5.3 (P2-3): Reservar“no dividido system”La lista de mensajes original se utiliza para la reconstrucción después de eliminar el bloqueo del pensamiento durante 400 autocuración.
        var effectiveMessages: List<UIMessage> = normalizedMessages
        val url = baseUrl() + anthropicConfig.messagesPath
        Logger.i("AnthropicProvider", "streamChat: POST ${sanitizeUrl(url)} model=${request.model.id} msgs=${request.messages.size}")

        // v2.5.3 (P2-3): 400 Piensa en el indicador de autocuración del error de vinculación del bloque —— Sólo se cura a sí mismo una vez por conexión.
        var thinkingRecoveryUsed = false

        // M-ANT1: Seguimiento recibido content,Grabado cuando se desconecta y se vuelve a intentar(Anthropic No se admite la reanudación de la transmisión,Regenerar después de volver a conectarse)
        val accumulatedContent = StringBuilder()
        // H-ANT2 / L-ANT2: Realice un seguimiento del tipo de cada bloque de contenido
        val blockContext = mutableMapOf<Int, AnthropicContentBlock>()
        // M-ANT4: Acumulación de firmas(thinking Emitido a través de ReasoningDelta.signature al final del bloque,Para que los consumidores depositen UIMessage.thinkingSignature)
        val signatureAccumulator = StringBuilder()
        // M-ANT1: message_delta portátil stop_reason,Retrasado hasta message_stop y enviado junto con Listo(evitar repetidos Done)
        var pendingStopReason: String? = null
        // A5: message_start entrada usage — message_delta Fusionar salida cuando se alcance el uso UsageDelta(Toma la última vez)
        var inputUsage: AnthropicUsage? = null
        // H-ANT1: cualquiera ContentDelta/ReasoningDelta/ToolCallDelta problema postfijo true,Verifique antes de volver a intentarlo para evitar contenido duplicado
        val anyDeltaSent = AtomicBoolean(false)
        // H-ANT3: Si la transmisión ha terminado normalmente(message_stop/error/onFailure configuración de ruta final true),onClosed En base a esto, se juzga un apagado anormal.
        val finished = AtomicBoolean(false)
        // M-ANT2/L-ANT2: onOpen HTTP Los errores también participan en los reintentos.(Originalmente solo manejado por onFailure),Compartido con onFailure retryCount
        var retryCount = 0
        val maxRetries = 3
        // M-ANT3: AtomicReference reemplazar ordinario var,Garantizar la visibilidad entre la devolución de llamada del oyente y awaitClose
        val currentEventSource = AtomicReference<EventSource?>(null)
        val currentCall = AtomicReference<okhttp3.Call?>(null)
        val connectionGeneration = AtomicInteger(0)
        val abortListener = request.abortSignal.addAbortListener {
            connectionGeneration.incrementAndGet()
            currentEventSource.get()?.cancel()
            currentCall.get()?.cancel()
        }

        fun connect() {
            val myGeneration = connectionGeneration.incrementAndGet()
            // L-ANT3: Limpiar la acumulación antes de volver a conectar,Evite que el contenido residual de la ronda anterior se mezcle en la nueva ronda.
            accumulatedContent.setLength(0)
            signatureAccumulator.setLength(0)
            blockContext.clear()
            pendingStopReason = null
            inputUsage = null
            // v2.5.3 (P2-3): El cuerpo de la solicitud se construye dentro de connect. —— 400 EffectiveMessages se ha eliminado durante la autocuración.
            // Reintentar traerá"Sin bloqueo de pensamiento"nueva lista.system También se vuelve a extraer cada vez que te conectas.
            val (system, splitMessages) = splitSystem(effectiveMessages, request.model)
            val body =
                buildRequestBody(
                    model = request.model.id,
                    system = system,
                    messages = splitMessages,
                    temperature = request.temperature,
                    maxTokens = request.maxTokens,
                    stream = true,
                    reasoningLevel = request.reasoningLevel,
                    tools = request.tools,
                )
            // v1.0.1: usar effectiveApiKey() Admite rotación de múltiples teclas
            val httpRequest = Request.Builder()
                .url(url)
                .header("x-api-key", effectiveApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("anthropic-beta", buildBetaHeader(thinkingEnabled = isThinkingEnabled(request.reasoningLevel)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = httpClient.newCall(httpRequest)
            currentCall.set(call)
            // v2.5.3: Determinación unificada de condiciones previas para el reintento —— No se ha publicado ningún contenido, no se ha agotado el presupuesto y no se han realizado cancelaciones/Cerca.
            fun isAbortedOrClosed(): Boolean = request.abortSignal.aborted || producerScope.isClosedForSend

            fun canRetryNow(): Boolean = !anyDeltaSent.get() && retryCount < maxRetries && !isAbortedOrClosed()

            /** v2.5.3 (P2-3): 400 Y sólo se cura a sí mismo cuando hay un error vinculante en el bloque de pensamiento, una vez por conexión. */
            fun shouldSelfHealThinking(errText: String, code: Int, used: Boolean): Boolean =
                code == 400 && canRetryNow() && !used && ProviderError.isThinkingBlockBondError(errText)

            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    if (myGeneration != connectionGeneration.get()) return
                    if (!response.isSuccessful) {
                        // M-ANT10 / M-ANT11: readBodyCapped + cancelar primero y luego close
                        val errText = readBodyCapped(response)
                        val code = response.code
                        // v1.0.1 (P1): Reintentar alineación condicional OpenAI/Gemini — 429/408/5xx Reintentar ambos
                        //   Solo reintentos antrópicos originales 529/503,429 Los usuarios solo pueden volver a intentarlo manualmente cuando la corriente es limitada.
                        val isRetryable = code == 429 || code == 408 || code == 503 || code == 529 || code in 500..599
                        // v1.0.1 (P0): 429 Al limitar la corriente, intente cambiar primero key(Múltiples escenarios clave)
                        if (code == 429 && canRetryNow() && switchToNextKey()) {
                            retryCount++
                            Logger.i(
                                "AnthropicProvider",
                                "streamChat onOpen 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ahora " +
                                    "($retryCount/$maxRetries)",
                            )
                            connectionGeneration.incrementAndGet()
                            eventSource.cancel()
                            call.cancel()
                            producerScope.launch {
                                if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                                connect()
                            }
                            return
                        }
                        if (isRetryable && canRetryNow()) {
                            retryCount++
                            // v1.0.1 (P1): agregar jitter(0~499ms),Alineado con OpenAI
                            val baseDelay = 1000L shl (retryCount - 1) // 1s / 2s / 4s
                            val delayMs = baseDelay + kotlin.random.Random.nextLong(0, 500)
                            // v1.0.1: 429 La limitación actual utiliza primero el encabezado Retry-After.
                            val retryAfter = if (code == 429) {
                                ProviderError.parseRetryAfter(response.header("Retry-After"))?.let { it * 1000L }
                            } else {
                                null
                            }
                            val finalDelay = retryAfter ?: delayMs
                            Logger.w(
                                "AnthropicProvider",
                                "streamChat onOpen retryable HTTP $code, " +
                                    "retry $retryCount/$maxRetries after ${finalDelay}ms",
                            )
                            connectionGeneration.incrementAndGet()
                            eventSource.cancel()
                            call.cancel()
                            producerScope.launch {
                                delay(finalDelay)
                                if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                                connect()
                            }
                            return
                        }
                        val msg = parseErrorMessage(code, errText)
                        Logger.w("AnthropicProvider", "streamChat onOpen HTTP $code: $msg")
                        // V2.5.3 (P2-3): 400 autocuración —— “Los bloques de pensamiento están vinculados a diferentes sesiones.”(Común después de editar el historial)cuando,
                        // Eliminar el bloque de pensamiento de los mensajes históricos del asistente/Inténtalo de nuevo después de firmar. Solo trata este tipo de diagnóstico,
                        // Autenticación, etc. 400 se generará como de costumbre. Solo se volverá a intentar cuando no se haya enviado nada en esta ronda y solo se curará solo una vez por conexión.
                        if (shouldSelfHealThinking(errText, code, thinkingRecoveryUsed)) {
                            thinkingRecoveryUsed = true
                            effectiveMessages = stripThinkingFromMessages(effectiveMessages)
                            Logger.w(
                                "AnthropicProvider",
                                "streamChat 400 Error de vinculación del bloque de pensamiento, elimine el bloque de pensamiento histórico e inténtelo de nuevo",
                            )
                            eventSource.cancel()
                            call.cancel()
                            producerScope.launch {
                                if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                                connect()
                            }
                            return
                        }
                        // v1.0.1: 401/403 Marcar la clave actual como fallida cuando falla la autenticación
                        if (code == 401 || code == 403) {
                            markKeyFailed(hardBlock = true)
                        }
                        eventSource.cancel()
                        finished.set(true)
                        trySend(ChatStreamEvent.Error(msg))
                        close()
                    }
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (myGeneration != connectionGeneration.get()) return
                    if (data.isBlank()) return
                    // L-ANT1: Utilice resultOf en su lugar runCatching,propagar correctamente CancellationException
                    var parseError: Throwable? = null
                    val chunk = resultOf {
                        AppJson.decodeFromString<AnthropicStreamEvent>(data)
                    }.onError { _, throwable -> parseError = throwable }.getOrNull()
                    if (chunk == null) {
                        val error = malformedStreamFrameError("Anthropic", data, parseError)
                        Logger.w("AnthropicProvider", error.message, parseError)
                        trySend(error)
                        finished.set(true)
                        close()
                        return
                    }

                    when (chunk.type) {
                        // L-ANT1: message_start analizar usage — A5: Guardar entrada usage,message_delta fusionar salida UsageDelta
                        "message_start" -> {
                            val usage = chunk.message?.usage
                            if (usage != null) {
                                inputUsage = usage
                                Logger.d(
                                    "AnthropicProvider",
                                    "message_start: input=${usage.input_tokens} output=${usage.output_tokens} " +
                                        "cache_read=${usage.cache_read_input_tokens} cache_creation=${usage.cache_creation_input_tokens}",
                                )
                            }
                        }
                        // H-ANT2 / L-ANT2: content_block_start Tipos de bloques de seguimiento,tool_use enviar ToolCallDelta
                        "content_block_start" -> {
                            val idx = chunk.index ?: 0
                            val block = chunk.content_block
                            if (block != null) {
                                blockContext[idx] = block
                                if (block.type == "tool_use") {
                                    anyDeltaSent.set(true)
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = idx,
                                            id = block.id,
                                            name = block.name,
                                        ),
                                    )
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val delta = chunk.delta ?: return
                            val idx = chunk.index ?: 0
                            when (delta.type) {
                                // H-ANT1: thinking_delta leer delta.thinking(no delta.text)
                                "thinking_delta" -> delta.thinking?.takeIf { it.isNotEmpty() }
                                    ?.let {
                                        anyDeltaSent.set(true)
                                        trySend(ChatStreamEvent.ReasoningDelta(it))
                                    }
                                "text_delta" -> delta.text?.takeIf { it.isNotEmpty() }?.let {
                                    accumulatedContent.append(it)
                                    anyDeltaSent.set(true)
                                    trySend(ChatStreamEvent.ContentDelta(it))
                                }
                                // H-ANT2: input_json_delta incremento del parámetro de herramienta
                                "input_json_delta" -> delta.partial_json?.takeIf { it.isNotEmpty() }?.let {
                                    anyDeltaSent.set(true)
                                    trySend(ChatStreamEvent.ToolCallDelta(index = idx, argumentsDelta = it))
                                }
                                // M-ANT4: signature_delta Firmas acumuladas,y emitido a través de ReasoningDelta.signature,
                                // para los consumidores(ChatViewModel)Almacene UIMessage.thinkingSignature para múltiples rondas de reproducción de pensamiento
                                "signature_delta" -> delta.signature?.let { sig ->
                                    if (sig.isNotEmpty()) {
                                        signatureAccumulator.append(sig)
                                        anyDeltaSent.set(true)
                                        trySend(ChatStreamEvent.ReasoningDelta(delta = "", signature = sig))
                                    }
                                }
                            }
                        }
                        // M-ANT1: message_delta Sólo iniciar sesión stop_reason,ya no enviar inmediatamente Done(Evite la transmisión repetida con message_stop Done)
                        // A5: message_delta llevar salida usage(Valor acumulado),Combinado con el uso de entrada de message_start UsageDelta
                        "message_delta" -> {
                            val stopReason = chunk.delta?.stop_reason
                            if (stopReason != null) {
                                pendingStopReason = stopReason
                            }
                            val deltaUsage = chunk.usage
                            if (deltaUsage != null) {
                                trySend(
                                    ChatStreamEvent.UsageDelta(
                                        anthropicUsageTokens(inputUsage, deltaUsage.output_tokens),
                                    ),
                                )
                            }
                        }
                        // L-ANT6: message_stop hecho de nuevo close(M-ANT1: Sólo publicado aquí Done)
                        "message_stop" -> {
                            if (signatureAccumulator.isNotEmpty()) {
                                Logger.d(
                                    "AnthropicProvider",
                                    "streamChat signature accumulated: ${signatureAccumulator.length} chars",
                                )
                            }
                            finished.set(true)
                            trySend(ChatStreamEvent.Done(pendingStopReason))
                            close()
                        }
                        // H-ANT4: error evento
                        "error" -> {
                            val errMsg = chunk.error?.message ?: "Anthropic stream error"
                            Logger.w("AnthropicProvider", "streamChat error event: ${chunk.error?.type}: $errMsg")
                            finished.set(true)
                            trySend(ChatStreamEvent.Error(errMsg))
                            close()
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (myGeneration != connectionGeneration.get()) {
                        if (request.abortSignal.aborted || producerScope.isClosedForSend) producerScope.close()
                        return
                    }
                    // H-ANT3: No terminó normalmente(No recibido message_stop/error)es decir, cerrar,Enviar evento de terminación de error,
                    // evitar al consumidor(UI loading)Esperando indefinidamente
                    if (!finished.get()) {
                        Logger.w("AnthropicProvider", "streamChat onClosed before finished")
                        // v1.0.15: Degradado a cuando se ha recibido contenido parcial StreamInterrupted,UI Conservar el contenido recibido y provocar la interrupción de la red
                        if (anyDeltaSent.get()) {
                            trySend(ChatStreamEvent.StreamInterrupted(ErrorCode.STREAM_INTERRUPTED.toMessage("anthropic")))
                        } else {
                            trySend(ChatStreamEvent.Error(ErrorCode.STREAM_INTERRUPTED.toMessage("anthropic")))
                        }
                    }
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (myGeneration != connectionGeneration.get()) {
                        if (request.abortSignal.aborted || producerScope.isClosedForSend) producerScope.close()
                        return
                    }
                    if (request.abortSignal.aborted) {
                        Logger.d("AnthropicProvider", "streamChat aborted by user")
                        finished.set(true)
                        close()
                        return
                    }
                    val code = response?.code
                    // v1.0.1 (P1): Reintentar alineación condicional OpenAI/Gemini — 429/408/5xx/IOException Reintentar ambos
                    //   Solo reintentos antrópicos originales 529/503,429 No se volverá a intentar la limitación actual ni la IOException
                    val isRetryable = (code != null && (code == 429 || code == 408 || code == 503 || code == 529 || code in 500..599)) ||
                        (t is java.io.IOException)
                    // v1.0.1 (P0): 429 Al limitar la corriente, intente cambiar primero key(Múltiples escenarios clave)
                    if (code == 429 && !anyDeltaSent.get() && retryCount < maxRetries &&
                        !request.abortSignal.aborted && !producerScope.isClosedForSend &&
                        switchToNextKey()
                    ) {
                        retryCount++
                        Logger.i(
                            "AnthropicProvider",
                            "streamChat onFailure 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ahora " +
                                "($retryCount/$maxRetries)",
                        )
                        connectionGeneration.incrementAndGet()
                        eventSource.cancel()
                        producerScope.launch {
                            if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                            connect()
                        }
                        return
                    }
                    // H-ANT1: No volver a intentarlo si se ha emitido algún incremento(Evite que los consumidores reciban contenido duplicado)
                    if (isRetryable && !anyDeltaSent.get() && retryCount < maxRetries &&
                        !producerScope.isClosedForSend
                    ) {
                        retryCount++
                        // v1.0.1 (P1): agregar jitter(0~499ms),Alineado con OpenAI(Original Antrópico Ninguno jitter)
                        val baseDelay = 1000L shl (retryCount - 1) // 1s / 2s / 4s
                        var delayMs = baseDelay + kotlin.random.Random.nextLong(0, 500)
                        // v1.0.1: 429 La limitación actual utiliza primero el encabezado Retry-After.
                        if (code == 429) {
                            delayMs = ProviderError.parseRetryAfter(response?.header("Retry-After"))
                                ?.let { it * 1000L } ?: delayMs
                        }
                        Logger.w(
                            "AnthropicProvider",
                            "streamChat retryable failure(code=$code t=${t?.message}), " +
                                "retry $retryCount/$maxRetries after ${delayMs}ms, " +
                                "accumulated=${accumulatedContent.length} chars, anyDeltaSent=${anyDeltaSent.get()}",
                        )
                        connectionGeneration.incrementAndGet()
                        eventSource.cancel()
                        producerScope.launch {
                            delay(delayMs)
                            if (request.abortSignal.aborted || producerScope.isClosedForSend) return@launch
                            connect()
                        }
                        return
                    }
                    // v1.109 Reparación: SSE Creado(2xx)La post-interrupción es una desconexión,Priorizar mensajes arrojables
                    val msg = response?.let {
                        if (it.code in 200..299) {
                            t?.message?.takeIf { m -> m.isNotBlank() }
                                ?: ErrorCode.STREAM_INTERRUPTED.toMessage("anthropic")
                        } else {
                            val body = readBodyCapped(it)
                            parseErrorMessage(it.code, body)
                        }
                    } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
                    Logger.e("AnthropicProvider", "streamChat onFailure: $msg", t)
                    finished.set(true)
                    // v1.0.15: Enviado cuando se ha recibido parte del contenido StreamInterrupted,Deje que la interfaz de usuario conserve el contenido recibido y solicite la interrupción de la red(Puede reconectarse automáticamente)
                    if (anyDeltaSent.get()) {
                        trySend(ChatStreamEvent.StreamInterrupted(msg, t))
                    } else {
                        trySend(ChatStreamEvent.Error(msg, t))
                    }
                    close()
                }
            }

            currentEventSource.set(sseFactory.newEventSource(httpRequest, listener))
        }

        connect()

        awaitClose {
            // M-ANT2: desafinado request.abortSignal.abort()(Modificar el objeto de la persona que llama),Cancelar solo recursos HTTP
            connectionGeneration.incrementAndGet()
            abortListener.close()
            currentEventSource.get()?.cancel()
            currentCall.get()?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun completeText(request: ChatRequest): ChatCompletion = completeTextImpl(request, 0)

    private suspend fun completeTextImpl(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        // v1.0.5: Provider Seguridad de las exportaciones — Primero haga una limpieza general de la lista UIMessage
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages,
            request.model,
        )
        val (system, messages) = splitSystem(normalizedMessages, request.model)
        val body = buildRequestBody(
            model = request.model.id,
            system = system,
            messages = messages,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            stream = false,
            reasoningLevel = request.reasoningLevel,
            tools = request.tools,
        )
        val url = baseUrl() + anthropicConfig.messagesPath
        Logger.i("AnthropicProvider", "completeText: POST ${sanitizeUrl(url)} model=${request.model.id}")
        // v1.0.1: usar effectiveApiKey() Admite rotación de múltiples teclas
        val httpRequest = Request.Builder()
            .url(url)
            .header("x-api-key", effectiveApiKey())
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("anthropic-beta", buildBetaHeader(thinkingEnabled = isThinkingEnabled(request.reasoningLevel)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(httpRequest)
        try {
            // L-ANT10: execute inspección previa abortSignal
            if (request.abortSignal.aborted) {
                Logger.d("AnthropicProvider", "completeText aborted before execute")
                throw kotlinx.coroutines.CancellationException("aborted by user")
            }
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    // v1.0.1: 429 Cambia de tecla y vuelve a intentarlo.;401/403 Error al marcar la clave
                    if (code == 429 && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i("AnthropicProvider", "completeText 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextImpl(request, keySwitchDepth + 1)
                    }
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // M-ANT10: readBodyCapped sustituto runCatching
                    val errText = readBodyCapped(resp)
                    val msg = parseErrorMessage(code, errText)
                    Logger.w("AnthropicProvider", "completeText HTTP $code: $msg")
                    throw ProviderException(
                        ProviderError.from(
                            code = code,
                            body = errText,
                            retryAfterSec = ProviderError.parseRetryAfter(resp.header("Retry-After")),
                        ),
                    )
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<AnthropicCompletionResponse>(raw)
                // M-ANT4: atravesar todo content blocks,empalmar todo text block
                val text = parsed.content
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text }
                // M-ANT4: poner el pensamiento en ChatCompletion.reasoningContent(Alineado con OpenAI,para descarga continua/Uso de demostración)
                val thinkingText = parsed.content
                    .filter { it.type == "thinking" }
                    .joinToString("\n") { it.text }
                if (thinkingText.isNotBlank()) {
                    Logger.d("AnthropicProvider", "completeText thinking: ${thinkingText.length} chars")
                }
                // M-ANT4: Extraer el bloque tool_use como toolCalls
                val toolCalls = parsed.content
                    .filter { it.type == "tool_use" }
                    .mapNotNull { block ->
                        val tcId = block.id ?: return@mapNotNull null
                        val tcName = block.name ?: return@mapNotNull null
                        val tcArgs = block.input?.toString() ?: "{}"
                        ToolCall(id = tcId, name = tcName, arguments = tcArgs)
                    }
                    .takeIf { it.isNotEmpty() }
                if (text.isBlank() && toolCalls.isNullOrEmpty() && thinkingText.isBlank()) {
                    Logger.w("AnthropicProvider", "completeText Devolver texto vacío(thinking ser comido)")
                    throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_text")
                }
                Logger.d("AnthropicProvider", "completeText OK: ${text.length} chars, toolCalls=${toolCalls?.size ?: 0}")
                ChatCompletion(
                    text = text,
                    finishReason = parsed.stop_reason,
                    reasoningContent = thinkingText.takeIf { it.isNotBlank() },
                    toolCalls = toolCalls,
                    usageTokens = anthropicUsageTokens(parsed.usage, parsed.usage?.output_tokens ?: 0),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d("AnthropicProvider", "completeText aborted by user")
            } else if (t is RuntimeException && (t.message?.startsWith("Anthropic") == true || t.message?.contains("HTTP") == true)) {
                // Errores HTTP registrados,No repetido log
            } else {
                Logger.e("AnthropicProvider", "completeText anormal", t)
            }
            throw t
        } finally {
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    /**
     * Extraiga mensajes del SISTEMA en campos del sistema de nivel superior,El resto se mantiene en orden usuario/asistente.
     *
     * Phase 8.1 (M9): Si está habilitado promptCaching,Agregue cache_control al bloque del sistema y al último mensaje del usuario.
     *
     * v1.80 (M-ANT7): Anthropic Soporte nativo para uso de herramientas/resultado de herramientas:
     *  - TOOL Estándares de construcción de mensajes tool_result content block(role="user")
     *  - ASSISTANT Construido cuando se transportan llamadas de herramientas. tool_use content block(en lugar de degradar el texto sin formato)
     *
     * v1.80 (M-ANT6): Imagen media_type inferida de bytes mágicos base64.
     *
     * v1.80 (M-ANT4): v1.121 Completó múltiples rondas de reproducción completa del pensamiento.
     *  ASSISTANT Cuando se construye el mensaje,Si msg.reasoning no está vacío y msg.thinkingSignature no está vacío,
     *  Insertar al principio de la matriz de contenido. {type:"thinking", thinking, signature} block.
     *  v2.6.14: Los bloques de pensamiento sin firma no se reproducen —— Anthropic Verificar bloque de pensamiento,
     *  Los bloques sin firmar no pueden pasar la verificación,Enviarlo solo te devolverá algo 400(Vea también los comentarios dentro de splitSystem.).
     *  signature Almacenado de forma acumulativa en UIMessage.thinkingSignature por ChatStreamEvent.ReasoningDelta.signature.
     */
    private fun splitSystem(messages: List<UIMessage>, model: Model): Pair<List<AnthropicSystemBlock>?, List<AnthropicMessage>> {
        val systemParts = messages.filter { it.role == MessageRole.SYSTEM }
            .map { it.content }
            .filter { it.isNotBlank() }
        val cacheControl = if (anthropicConfig.promptCaching) {
            AnthropicCacheControl(ttl = anthropicConfig.promptCacheTtl.takeIf { it.isNotBlank() })
        } else {
            null
        }
        val cacheBreakpoint = if (cacheControl != null) {
            anthropicCacheBreakpointIndex(systemParts.size)
        } else {
            null
        }

        val system = systemParts.takeIf { it.isNotEmpty() }?.mapIndexed { index, text ->
            AnthropicSystemBlock(
                text = text,
                cache_control = cacheControl?.takeIf { index == cacheBreakpoint },
            )
        }

        val rest = messages
            .filter { it.role != MessageRole.SYSTEM }
            .map { msg ->
                // Phase 8.5 S19: Anthropic solo aceptar "user" / "assistant" dos tipos role
                val anthropicRole = when (msg.role) {
                    MessageRole.USER, MessageRole.TOOL -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "user" // no llegará(SYSTEM Filtrado antes),Integridad de la compilación
                }
                // M-ANT7: TOOL Construcción de mensajes tool_result;ASSISTANT toolCalls estructura tool_use
                // M-ANT6: Imagen media_type inferida de bytes mágicos base64
                val anthropicContent: kotlinx.serialization.json.JsonElement = when {
                    msg.role == MessageRole.TOOL && msg.toolCallId != null -> {
                        // M-ANT7: Estándar tool_result content block
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", msg.toolCallId)
                                    put("content", msg.content)
                                },
                            )
                        }
                    }
                    msg.role == MessageRole.ASSISTANT -> {
                        // ASSISTANT noticias:incluido bajo demanda thinking/text/tool_use content blocks
                        buildJsonArray {
                            // M-ANT4: Múltiples rondas de reproducción de pensamiento. — Si el mensaje tiene reasoning(pensar texto)y thinkingSignature,
                            // Insertar al principio de la matriz de contenido. {type:"thinking", thinking, signature} block,
                            // Permitir que el servidor verifique la integridad del pensamiento de pedido anticipado.,Evite cada vez el pensamiento regenerador.
                            // v2.6.14: Los bloques de pensamiento sin firmar ya no se envían —— Anthropic Verificación de firma de bloques de pensamiento.,
                            // Los bloques a los que les falta la firma son cargas útiles ilegales(y versión antigua/Los textos de pensamiento dejados por otros modelos no pueden verificarse por sí mismos.),
                            // Nunca reproducir; Los bloques text y tool_use se emiten como de costumbre.
                            if (!msg.reasoning.isNullOrBlank() && !msg.thinkingSignature.isNullOrBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", "thinking")
                                        put("thinking", msg.reasoning)
                                        put("signature", msg.thinkingSignature)
                                    },
                                )
                            }
                            if (msg.content.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", msg.content)
                                    },
                                )
                            }
                            if (!msg.toolCalls.isNullOrEmpty()) {
                                msg.toolCalls.forEach { tc ->
                                    val inputJson = resultOf {
                                        AppJson.parseToJsonElement(tc.arguments.ifBlank { "{}" })
                                    }.getOrNull() ?: buildJsonObject {}
                                    add(
                                        buildJsonObject {
                                            put("type", "tool_use")
                                            put("id", tc.id)
                                            put("name", tc.name)
                                            put("input", inputJson)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    msg.imageBase64List.isNotEmpty() && model.supportsVisionInput() -> {
                        // Anthropic Vision: content = [
                        //   {type:"text", text:"..."},
                        //   {type:"image", source:{type:"base64", media_type:"image/jpeg", data:"..."}}
                        // ]
                        buildJsonArray {
                            if (msg.content.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", msg.content)
                                    },
                                )
                            }
                            // Reparación de auditoría (7.4): Limitar la entrada visual — Alineación OpenAI(Hasta 4 fotos,folleto ≤2MB).
                            // Entrega completa originalmente realizada,Cuando los mensajes históricos contienen una gran cantidad de imágenes, el cuerpo de la solicitud es docenas de veces MB,La estación de tránsito agotó el tiempo de espera.
                            msg.imageBase64List
                                .filter { it.isNotEmpty() }
                                .take(MAX_VISION_IMAGES)
                                .forEach { b64 ->
                                    if (b64.length > MAX_IMAGE_BASE64_LEN) {
                                        Logger.w("AnthropicProvider", "drop oversize image (${b64.length} chars)")
                                        return@forEach
                                    }
                                    add(
                                        buildJsonObject {
                                            put("type", "image")
                                            put(
                                                "source",
                                                buildJsonObject {
                                                    put("type", "base64")
                                                    put("media_type", inferImageMediaType(b64))
                                                    put("data", b64)
                                                },
                                            )
                                        },
                                    )
                                }
                        }
                    }
                    else -> {
                        // v1.0.5: filtrado visual defensivo — Cuando el modelo no admite imágenes pero el mensaje lleva imágenes.,Descarta imágenes y ve con texto sin formato
                        if (msg.imageBase64List.isNotEmpty() && !model.supportsVisionInput()) {
                            Logger.w(
                                "AnthropicProvider",
                                "splitSystem: modelo ${model.id} Sin soporte visual,descartar ${msg.imageBase64List.size} fotos(filtrado defensivo)",
                            )
                        }
                        JsonPrimitive(msg.content)
                    }
                }
                AnthropicMessage(role = anthropicRole, content = anthropicContent)
            }

        // Agregar al último mensaje de usuario cache_control(Dejar caché de prefijo de conversación)
        val withLastCached = if (anthropicConfig.promptCaching && rest.isNotEmpty()) {
            val lastUserIdx = rest.indexOfLast { it.role == "user" }
            if (lastUserIdx >= 0) {
                rest.mapIndexed { idx, msg ->
                    if (idx == lastUserIdx) msg.copy(cache_control = cacheControl) else msg
                }
            } else {
                rest
            }
        } else {
            rest
        }

        return system to withLastCached
    }

    private fun baseUrl(): String = config.resolvedBaseUrl()

    /**
     * Extraiga la lista de modelos ascendentes.
     *
     * GET {resolvedBaseUrl()}/models(resolvedBaseUrl Incluido /v1),
     * x-api-key + anthropic-version Autenticación,analizar data[].id.
     * contextWindow usar [ModelContextWindowRegistry] Llega al fondo de esto.
     *
     * v1.80 (L-ANT8): paginación de soporte,Solicitudes de bucle hasta has_more==false.
     * v1.80 (L-ANT11): Desensibilización de URL de registro(Eliminar parámetro de consulta).
     *
     * v1.132 Optimizar:
     *  - breve tiempo de espera client(30s),Evite el retraso en la solicitud de paginación y ocupe una conexión prolongada del chat
     *  - Eliminar duplicados por id + ordenar alfabéticamente
     *  - filtro vacío id
     */
    override suspend fun listModels(config: ProviderConfig): List<Model> = withContext(Dispatchers.IO) {
        val specific = config.resolvedSpecific() as? ProviderSpecificConfig.Anthropic
            ?: ProviderSpecificConfig.Anthropic()
        val base = config.resolvedBaseUrl().trimEnd('/') + specific.modelsPath
        // v1.132: breve tiempo de espera client
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val seen = HashSet<String>()
        val models = mutableListOf<Model>()
        var afterId: String? = null
        var hasMore = false
        var pageCount = 0
        val maxPages = 20 // límite superior de seguridad,Evite que respuestas anormales causen bucles infinitos

        do {
            // M-ANT5: Utilice HttpUrl.Builder.addQueryParameter para codificar la URL after_id,
            // evitar caracteres especiales(Como por ejemplo +/=/espacio)La concatenación directa de cadenas hace que la URL de solicitud sea ilegal
            val urlBuilder = base.toHttpUrl().newBuilder()
                .addQueryParameter("limit", "100")
            if (afterId != null) urlBuilder.addQueryParameter("after_id", afterId)
            val url = urlBuilder.build()
            Logger.i("AnthropicProvider", "listModels: GET ${sanitizeUrl(url.toString())}")
            val httpRequest = Request.Builder()
                .url(url)
                // B-27: Cuando hay varias claves, config.apiKey contiene comas/nueva línea,Escribir directamente en x-api-key contaminará el encabezado y provocará 401.
                //     Usar en su lugar effectiveApiKey() Tome un solo efectivo key(Mismo patrón que la solicitud de chat.).
                .header("x-api-key", effectiveApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Accept", "application/json")
                .get()
                .build()
            listClient.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // M-ANT10: readBodyCapped sustituto runCatching
                    val errText = readBodyCapped(resp)
                    throw ProviderException(
                        ProviderError.from(
                            code = resp.code,
                            body = errText,
                            retryAfterSec = ProviderError.parseRetryAfter(resp.header("Retry-After")),
                        ),
                    )
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<AnthropicModelsResponse>(raw)
                parsed.data.map { m ->
                    Model(
                        id = m.id,
                        name = m.id,
                        providerId = config.id,
                        contextWindow = ModelContextWindowRegistry.lookup(m.id),
                    )
                }
                    // v1.132: Filtrar identificadores vacíos + eliminar duplicados
                    .filter { it.id.isNotBlank() && seen.add(it.id) }
                    .also { models.addAll(it) }
                hasMore = parsed.has_more
                afterId = parsed.last_id
            }
            pageCount++
        } while (hasMore && afterId != null && pageCount < maxPages)

        // v1.132: Ordenar alfabéticamente por identificación,Fácil de encontrar para los usuarios
        models.sortedBy { it.id.lowercase() }
    }

    /**
     * Construya el cuerpo de la solicitud.Phase 8.1 (M11): Mapear el campo de pensamiento según el nivel de razonamiento.
     *
     * v1.80 (H-ANT3): thinking Asegúrate de que esté habilitado max_tokens > budget_tokens + 1024.
     * v1.80 (M-ANT3): thinking No transmitido cuando está habilitado temperature(establecer en null).
     * v1.80 (H-ANT2): paso a través tools(ToolDefinition → AnthropicTool),Habilite la llamada a funciones nativas.
     */
    private fun buildRequestBody(
        model: String,
        system: List<AnthropicSystemBlock>?,
        messages: List<AnthropicMessage>,
        temperature: Float?,
        maxTokens: Int?,
        stream: Boolean,
        reasoningLevel: ReasoningLevel? = null,
        tools: List<ToolDefinition>? = null,
    ): String {
        // mapeo ReasoningLevel → thinking campo
        val thinking: AnthropicThinking? = reasoningLevel?.let { level ->
            when (level) {
                ReasoningLevel.OFF -> null // cerrar explícitamente,No enviar campo de pensamiento.
                ReasoningLevel.AUTO -> null // automático,Deja que el servidor decida
                ReasoningLevel.LOW, ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH, ReasoningLevel.XHIGH,
                ->
                    level.budgetTokens?.let { AnthropicThinking(budget_tokens = it) }
            }
        }

        // H-ANT3: thinking Asegúrate de que esté habilitado max_tokens > budget_tokens + 1024
        val effectiveMaxTokens = if (thinking != null) {
            maxOf(maxTokens ?: 4096, thinking.budget_tokens + 4096)
        } else {
            maxTokens ?: 4096
        }

        // M-ANT3: thinking No transmitido cuando está habilitado temperature(Anthropic Requiere modo de pensamiento no configurado temperature)
        val effectiveTemperature = if (thinking != null) null else temperature?.toDouble()

        // H-ANT2: ToolDefinition → AnthropicTool(input_schema Paso directo JSON Schema)
        // H-COMPAT1: compat.supportsToolCalling=false No emitido cuando es forzado tools
        //   (Anthropic Predeterminado true,Deje este juicio para mantener la derivación centralizada.,Facilita la futura expansión)
        val compat: ProviderCompat = config.resolvedCompat(model)
        val anthropicTools = if (compat.supportsToolCalling) {
            tools?.takeIf { it.isNotEmpty() }?.map { td ->
                AnthropicTool(
                    name = td.name,
                    description = td.description,
                    inputSchema = resultOf {
                        AppJson.parseToJsonElement(td.parametersJsonSchema)
                    }.getOrNull() ?: buildJsonObject {},
                )
            }?.takeIf { it.isNotEmpty() } // v1.0.5: stripEmptyTools — La lista de herramientas vacías cambió null,evitar `"tools": []` rechazado
        } else {
            null
        }

        val payload = AnthropicRequest(
            model = model,
            messages = messages,
            system = system,
            max_tokens = effectiveMaxTokens,
            temperature = effectiveTemperature,
            stream = stream,
            thinking = thinking,
            tools = anthropicTools,
        )
        return AppJson.encodeToString(payload)
    }

    /**
     * v1.80 (M-ANT2): Devuelve un mensaje de error estructurado.,Distinguir por tipo de error.
     */
    private fun parseErrorMessage(code: Int, body: String): String {
        if (body.isBlank()) {
            val category = ProviderHttpSupport.classifyHttpCode(code)
            return buildString {
                append("HTTP ").append(code)
                category?.let { append(" [").append(it).append("]") }
            }
        }
        val detail = resultOf {
            AppJson.decodeFromString<AnthropicErrorBody>(body).error
        }.getOrNull()
        val category = when (detail?.type) {
            "authentication_error" -> "authentication failed"
            "rate_limit_error" -> "rate limited"
            "overloaded_error" -> "overloaded"
            "invalid_request_error" -> "invalid request"
            "permission_error" -> "permission denied"
            "api_error" -> "API error"
            "not_found_error" -> "not found"
            else -> ProviderHttpSupport.classifyHttpCode(code)
        }
        return buildString {
            append("HTTP ").append(code)
            category?.let { append(" [").append(it).append("]") }
            detail?.type?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
            detail?.message?.takeIf { it.isNotBlank() }?.let {
                append(": ").append(ProviderError.safeErrorDetail(it) ?: it.take(200))
            }
        }
    }

    /**
     * v1.80 (M-ANT6): Inferir el tipo MIME de imagen a partir de bytes mágicos base64,Imagen alternativa/jpeg.
     */
    private fun inferImageMediaType(b64: String): String {
        val trimmed = b64.trim()
        return when {
            trimmed.startsWith("/9j/") -> "image/jpeg"
            trimmed.startsWith("iVBOR") -> "image/png"
            trimmed.startsWith("R0lGOD") -> "image/gif"
            trimmed.startsWith("UklGR") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * v1.80 (L-ANT11): Desensibilización de URL de registro,Eliminar parámetro de consulta(Puede contener información sensible).
     */
    private fun sanitizeUrl(url: String): String {
        val qIdx = url.indexOf('?')
        return if (qIdx >= 0) url.substring(0, qIdx) else url
    }

    /**
     * v1.80 (L-ANT5): Determinar si el nivel de razonamiento está realmente habilitado thinking(Corresponde a buildRequestBody thinking != null).
     *  BAJO/MEDIO/ALTO/XALTO solamente y budgetTokens != null el tiempo es true;OFF/AUTO es falso.
     *  De acuerdo con la lógica de cálculo de pensamiento de buildRequestBody,Asegúrese de que el encabezado beta solo se agregue cuando realmente se envíe el pensamiento.
     */
    private fun isThinkingEnabled(level: ReasoningLevel?): Boolean {
        if (level == null || level == ReasoningLevel.OFF || level == ReasoningLevel.AUTO) return false
        return level.budgetTokens != null
    }

    /**
     * v1.80 (L-ANT7 / M-ANT8): Construya un encabezado beta antrópico.
     *  - L-ANT5: solo en [thinkingEnabled] Agregar cuando sea verdadero interleaved-thinking-2025-05-14
     *    (El valor predeterminado original es enviar siempre,No tiene sentido enviar este encabezado beta varias veces para solicitudes irreflexivas y puede activar alarmas del lado del servidor.)
     *  - promptCacheTtl == "1h" Agregar cuando extended-cache-ttl-2025-04-11
     */
    private fun buildBetaHeader(thinkingEnabled: Boolean): String {
        val parts = mutableListOf<String>()
        if (thinkingEnabled) {
            parts.add(BETA_INTERLEAVED_THINKING)
        }
        if (anthropicConfig.promptCacheTtl == "1h") {
            parts.add(BETA_EXTENDED_CACHE_TTL)
        }
        return parts.joinToString(",")
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val BETA_INTERLEAVED_THINKING = "interleaved-thinking-2025-05-14"
        const val BETA_EXTENDED_CACHE_TTL = "extended-cache-ttl-2025-04-11"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // completeText 429 Número máximo de veces para cambiar de clave,Prevenir la recursividad infinita
        const val MAX_KEY_SWITCHES = 3

        // E-P2: Limitaciones de entrada visual,Alineado con OpenAI(Hasta 4 fotos,folleto base64 ≤2MB)
        const val MAX_VISION_IMAGES = 4
        const val MAX_IMAGE_BASE64_LEN = 2 * 1024 * 1024
    }
}

/**
 * Anthropic usage → [UsageTokens].
 *
 * lectura de caché(`cache_read_input_tokens`)escribir con caché(`cache_creation_input_tokens`)El precio unitario es diferente,
 * caer respectivamente [UsageTokens.cacheReadTokens] con [UsageTokens.cacheWriteTokens];
 * Ya no se agrega al mismo campo——Sumarlos hará que sea imposible obtener el monto escrito del precio.,Y las escrituras normalmente se facturan a 1,25 veces el precio de entrada.
 *
 * @param input `message_start` de `message.usage`(La ruta que no es de transmisión es el cuerpo de respuesta `usage`),null Indica que no hay retorno
 * @param outputTokens Número de token de salida(Transmisión `message_delta.usage.output_tokens`)
 */
internal fun anthropicUsageTokens(input: AnthropicUsage?, outputTokens: Int): UsageTokens =
    UsageTokens(
        promptTokens = input?.input_tokens ?: 0,
        completionTokens = outputTokens,
        cachedTokens = input?.cache_read_input_tokens ?: 0,
        // El upstream no devuelve este campo cuando no se han producido escrituras en caché.,Predeterminado 0
        cacheWriteTokens = input?.cache_creation_input_tokens ?: 0,
        // Anthropic Los propios input_tokens son entradas no almacenadas en caché(no contiene cache_read/cache_creation),
        // Lo opuesto al calibre de Prompt_tokens de OpenAI/Gemini,Debe indicarse al fijar el precio.,De lo contrario, se repetirá la resta.
        promptTokensIncludeCache = false,
    )
