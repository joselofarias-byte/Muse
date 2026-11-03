package io.zer0.ai.gemini

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
import io.zer0.ai.core.malformedStreamFrameError
import io.zer0.ai.core.toProviderException
import io.zer0.common.AppJson
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/** A final usage-only Gemini frame is terminal only after visible content was emitted. */
internal fun shouldCompleteUsageOnlyFrame(
    hasEmittedContent: Boolean,
    candidateCount: Int,
    finished: Boolean,
): Boolean = !finished && candidateCount == 0 && hasEmittedContent

/**
 * Google Gemini Provider(Apoyar a ambos generativelanguage API + Vertex AI).
 *
 * ## generativelanguage API(Predeterminado)
 *  - URL: {baseUrl}/models/{model}:streamGenerateContent?key={apiKey}&alt=sse
 *  - Certificación: API key como query param(?key=...)
 *
 * ## Vertex AI(Phase 9.4 M10,Autenticación de cuenta de servicio)
 *  - URL: https://{location}-aiplatform.googleapis.com/v1/projects/{projectId}/locations/{location}/publishers/google/models/{model}:streamGenerateContent?alt=sse
 *  - Certificación: Authorization: Bearer {access_token}(Obtenido de la cuenta de servicio JWT Exchange)
 *  - El formato del cuerpo de la solicitud es el mismo que el de la API de lenguaje generativo.(Todos GeminiRequest)
 *  - La cuenta de servicio debe otorgarse en el proyecto GCP. "Vertex AI User" papel
 *
 * Universales:
 *  - systemInstruction es un campo de nivel superior(Extraído del SISTEMA UIMessage)
 *  - transmisión: alt=sse estándar de devolución SSE,Cada dato: es un GenerateContentResponse
 *  - assistant Llamado en Géminis "model"
 *  - Gemini No hay cadena pública de pensamiento nativo. API,reasoning ir sin(dejar en blanco)
 *
 * Phase 9.4 (M10): agregar [vertexAiAuthToken] campo. cuando [ProviderSpecificConfig.Gemini.useVertexAI]
 * es verdad y [ProviderSpecificConfig.Gemini.useServiceAccount] cuando es cierto,
 * usar [VertexAiAuthToken] Obtenga una ficha de portador.
 *
 * v1.80 Reparación(herencia ProviderHttpSupport,Llamada a función/filtrado de seguridad/reintento/desensibilización de registros, etc.):
 *  - H-GEM1: listModels El registro no se imprime ?key= de completo URL
 *  - H-GEM2: Soporte de llamada de función(tools Transmisión/función transparenteAnálisis de llamadas/herramientaRelleno de llamadas)
 *  - H-GEM3: ASSISTANT toolCalls → functionCall part;TOOL → functionResponse part
 *  - H-GEM4: promptFeedback.blockReason / SAFETY finishReason → Error
 *  - M-GEM1..14 / L-GEM7..11: Ver notas en todas partes
 */
class GeminiProvider(
    config: ProviderConfig,
) : ProviderHttpSupport(config) {

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName

    /**
     * Phase 9.4: Vertex AI Autenticador de cuenta de servicio(useVertexAI && useServiceAccount el tiempo esta mal null).
     *
     * L-GEM7: Cambiar a by lazy,Evite analizar sincrónicamente la clave privada de PEM cuando el hilo principal construye el proveedor,
     * Primera toma real token(en hilo IO)Sólo entonces será analizado.
     */
    private val vertexAiAuthToken: VertexAiAuthToken? by lazy {
        val specific = config.resolvedSpecific()
        if (specific is ProviderSpecificConfig.Gemini &&
            specific.useVertexAI &&
            specific.useServiceAccount &&
            specific.serviceAccountEmail.isNotBlank() &&
            specific.privateKey.isNotBlank()
        ) {
            // L-GEM1: Utilice resultOf en su lugar runCatching,propagar correctamente CancellationException
            // M-VTX1: compartir entrante httpClient,Evite la fuga del grupo de conexiones del cliente de creación propia VertexAiAuthToken
            resultOf {
                VertexAiAuthToken(
                    serviceAccountEmail = specific.serviceAccountEmail,
                    privateKeyPem = specific.privateKey,
                    client = httpClient,
                )
            }.onError { msg, t ->
                Logger.w(TAG, "VertexAiAuthToken La inicialización falló: $msg", t)
            }.getOrNull()
        } else {
            null
        }
    }

    private val sseFactory by lazy { EventSources.createFactory(httpClient) }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = channelFlow {
        val scope = this
        // Revisión de corrección (2.0 B-25): callbackFlow → channelFlow — constante del canal interno UNLIMITED,
        // Elimine la devolución de llamadas. Flujo de capacidad fija de 64 en producción rápida./Pérdida de película muda bajo consumo lento(Misma solución que OpenAI).
        // Provider La proyección de solicitudes comprime uniformemente las trayectorias antiguas de herramientas exitosas antes de la traducción del protocolo, conservando las más recientes./Emparejamiento de rondas fallido.
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages,
            request.model,
        )
        val (system, contents) = splitSystem(normalizedMessages)
        var body = buildRequestBody(
            system = system,
            contents = contents,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            reasoningLevel = request.reasoningLevel,
            supportsImageOutput = request.model.supportsImageOutput(),
            tools = request.tools,
            nativeWebSearch = request.nativeWebSearch,
            modelId = request.model.id,
        )
        // M-GEM2: buildUrl puede ser arrojado IllegalStateException(Vertex AI Configuración faltante),Envíos de paquete try-catch Error
        val url: String = try {
            buildUrl(model = request.model.id, stream = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.SERVICE_UNAVAILABLE.toMessage("url_build")))
            close()
            return@channelFlow
        }

        // M-GEM13: Vertex AI La configuración de la cuenta de servicio no es válida(VertexAiAuthToken La construcción falló)Enviar directamente Error
        // M-GEM4: token Cambiar a var,Analizar al reintentar(Evite la caducidad del token antiguo, lo que provocará que fallen los reintentos.)
        var token: String? = try {
            resolveToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.VERTEX_AI_TOKEN_FAILED.toMessage()))
            close()
            return@channelFlow
        }

        // M-GEM1: finished logotipo,onClosed En base a esto, se juzga si está cerrado de manera anormal.
        val finished = AtomicBoolean(false)
        // H-GEM1: generation El contador distingue las corrientes antiguas y nuevas,Determinación de la condición de carrera que reemplaza la bandera de reintento original.
        //  Cada vez que startStream incrementa la generación y captura myGen,listener Verificación de devolución de llamada myGen == generation.get()
        //  Es decir, la corriente actual.;La generación onClosed/onFailure de la secuencia anterior se avanzó durante el período de transición de reintento.,solo ignora,Evite errores falsos.
        val generation = AtomicInteger(0)
        val attempt = AtomicInteger(0)
        val currentEventSource = AtomicReference<EventSource?>(null)
        val currentCall = AtomicReference<Call?>(null)
        val abortListener = request.abortSignal.addAbortListener {
            currentEventSource.get()?.cancel()
            currentCall.get()?.cancel()
        }
        // M-GEM3: Asignar incremento para cada función Llamar index(Originalmente fijado en 0, lo que provoca que varias llamadas a herramientas se fusionen en una)
        val toolCallIndex = AtomicInteger(0)
        // v1.0.1 (P1): Cualquier ContentDelta/ImageDelta/ToolCallDelta emitido post- true,
        //   Comprobar antes de volver a intentarlo !anyDeltaSent.get(),Evite que el contenido filtrado se vuelva a publicar(Alineado con OpenAI/Anthropic).
        //   El Géminis original solo usaba el contador de generación para distinguir las corrientes antiguas y nuevas.,pero no impidió"Reenviar el contenido enviado después de volver a conectarse".
        val anyDeltaSent = AtomicBoolean(false)
        // B-42: Marque si esta transmisión realmente se envió answer content(con cualquierDeltaSent"Evitar el reenvío"separación semántica).
        //   Gemini Sin divulgación nativa reasoning,thinking El texto también va content parts;Si el flujo ocurre antes de que llegue FinishReason,
        //   nunca enviado Content/Image/ToolCall delta(Sólo razonamiento/pensamiento sin respuestas sustanciales),Se considera un final falso.
        val hasEmittedContent = AtomicBoolean(false)

        /** Los parámetros de pensamiento se han revertido del presupuesto simbólico a un engranaje: tolerar fallas solo una vez para evitar un bucle infinito. */
        var forceThinkingLevel = false

        /**
         * M-GEM2: empezar(o reiniciar)SSE fluir. Errores reintentables(UNAVAILABLE/5xx/429/Anormalidad de la red)Reintentar con retroceso exponencial,
         * la mayoría [MAX_RETRIES] veces.
         */
        fun startStream() {
            val myGen = generation.incrementAndGet()
            // v1.0.1 (P0): Reconstruye cada vez que te vuelves a conectar URL(Después del cambio de teclas múltiples ?key= Es necesario actualizar los parámetros.)
            val currentUrl = try {
                buildUrl(model = request.model.id, stream = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.SERVICE_UNAVAILABLE.toMessage("url_build")))
                close()
                return
            }
            val httpRequest = Request.Builder()
                .url(currentUrl)
                .header("Accept", "text/event-stream")
                .apply {
                    // Phase 9.4: Vertex AI Para autenticación de cuenta de servicio Bearer token,tener prioridad sobre API Key
                    if (token != null) {
                        header("Authorization", "Bearer $token")
                    }
                }
                // L-GEM8: Sin configuración manual Content-Type,Establecido automáticamente por toRequestBody
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = httpClient.newCall(httpRequest)
            currentCall.set(call)
            val listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    if (myGen != generation.get()) return // H-GEM1: Devolución de llamada de transmisión anterior,ignorar
                    if (!response.isSuccessful) {
                        val errText = ProviderHttpSupport.readBodyCapped(response)
                        val code = response.code
                        // Gemini 3 Daiqi ya no acepta presupuestos simbólicos, sólo tragamonedas.
                        // Si el nombre del modelo es irreconocible, utilice el método de escritura antiguo para publicarlo primero. Si 400 lo rechaza, cambie a otro modo e inténtelo de nuevo.
                        if (code == 400 && !anyDeltaSent.get() && !forceThinkingLevel &&
                            errText.contains("thinking budget is not supported", ignoreCase = true)
                        ) {
                            forceThinkingLevel = true
                            body = buildRequestBody(
                                system = system,
                                contents = contents,
                                temperature = request.temperature,
                                maxTokens = request.maxTokens,
                                reasoningLevel = request.reasoningLevel,
                                supportsImageOutput = request.model.supportsImageOutput(),
                                tools = request.tools,
                                nativeWebSearch = request.nativeWebSearch,
                                modelId = request.model.id,
                                forceThinkingLevel = true,
                            )
                            Logger.i(TAG, "Solicitud de transmisión rechazada(No aceptado thinking budget), cambie al nivel de pensamiento y reenvíe.")
                            generation.incrementAndGet()
                            eventSource.cancel()
                            call.cancel()
                            scope.launch {
                                if (request.abortSignal.aborted || scope.isClosedForSend) return@launch
                                startStream()
                            }
                            return
                        }
                        // v1.0.1 (P0): 429 Al limitar la corriente, intente cambiar primero key(Múltiples escenarios clave),Reintentar ahora No es necesario backoff
                        if (code == 429 && !anyDeltaSent.get() && attempt.get() < MAX_RETRIES &&
                            !request.abortSignal.aborted && !scope.isClosedForSend &&
                            switchToNextKey()
                        ) {
                            attempt.incrementAndGet()
                            generation.incrementAndGet()
                            eventSource.cancel()
                            call.cancel()
                            Logger.i(
                                TAG,
                                "streamChat onOpen 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ahora " +
                                    "(${attempt.get()}/$MAX_RETRIES)",
                            )
                            scope.launch {
                                if (request.abortSignal.aborted || scope.isClosedForSend) return@launch
                                startStream()
                            }
                            return
                        }
                        // v1.0.1 (P1): anyDeltaSent proteger — El contenido que se haya filtrado no se volverá a intentar.(Evite el envío duplicado)
                        // M-GEM2: Errores reintentables(5xx/429)Inténtalo de nuevo
                        if (attempt.get() < MAX_RETRIES && !anyDeltaSent.get() && isRetryableError(code, null)) {
                            attempt.incrementAndGet()
                            // H-GEM1: Generación avanzada para invalidar onClosed/onFailure posteriores de transmisiones antiguas
                            generation.incrementAndGet()
                            eventSource.cancel()
                            call.cancel()
                            scope.launch {
                                delay(backoffMs(attempt.get(), response.header("Retry-After")))
                                // M-GEM4: Analizar al reintentar token(Vertex AI Es posible que el token antiguo haya caducado)
                                token = try {
                                    resolveToken()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    trySend(ChatStreamEvent.Error(e.message ?: ErrorCode.VERTEX_AI_TOKEN_FAILED.toMessage()))
                                    close()
                                    return@launch
                                }
                                startStream()
                            }
                            return
                        }
                        val msg = parseErrorMessage(code, errText)
                        // v1.0.1: 401/403 Marcar la clave actual como fallida cuando falla la autenticación(Múltiples escenarios clave)
                        if (code == 401 || code == 403) {
                            markKeyFailed(hardBlock = true)
                        }
                        finished.set(true)
                        trySend(
                            ChatStreamEvent.Error(
                                msg,
                                ProviderException(
                                    ProviderError.from(
                                        code = code,
                                        body = errText,
                                        retryAfterSec = ProviderError.parseRetryAfter(response.header("Retry-After")),
                                    ),
                                ),
                            ),
                        )
                        close()
                        return
                    }
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (myGen != generation.get()) return // H-GEM1: Devolución de llamada de transmisión anterior,ignorar
                    if (data.isBlank()) return
                    val parsedFrame = decodeGeminiStreamFrame(data)
                    val chunk = parsedFrame.getOrNull()
                    if (chunk == null) {
                        val cause = (parsedFrame as? io.zer0.common.Result.Error)?.throwable
                        val error = malformedStreamFrameError("Gemini", data, cause)
                        Logger.w(TAG, error.message, cause)
                        trySend(error)
                        finished.set(true)
                        close()
                        return
                    }

                    // H-GEM4: promptFeedback Intercepción de seguridad de nivel rápido
                    chunk.promptFeedback?.blockReason?.takeIf { it.isNotBlank() }?.let { reason ->
                        finished.set(true)
                        trySend(ChatStreamEvent.Error(ErrorCode.PERMISSION_DENIED.toMessage("safety", reason)))
                        close()
                    }

                    // A5: usageMetadata es el valor acumulado,fin chunk(Contiene finishReason,Probablemente ninguno candidates)
                    // llevar el conteo final — Debe analizarse antes de que los candidatos se retiren anticipadamente,El consumidor toma el último no- null
                    chunk.usageMetadata?.let { usage ->
                        trySend(ChatStreamEvent.UsageDelta(usage.toUsageTokens()))
                    }
                    val candidate = chunk.candidates.firstOrNull()
                    if (candidate == null) {
                        if (shouldCompleteUsageOnlyFrame(hasEmittedContent.get(), chunk.candidates.size, finished.get())) {
                            finished.set(true)
                            trySend(ChatStreamEvent.Done("STOP"))
                            close()
                        }
                        return
                    }
                    if (request.nativeWebSearch) {
                        val urls = candidate.groundingMetadata?.groundingChunks.orEmpty()
                            .mapNotNull { it.web?.uri }
                            .filter { it.startsWith("https://") || it.startsWith("http://") }
                            .distinct()
                        if (urls.isNotEmpty()) trySend(ChatStreamEvent.CitationDelta(urls))
                    }
                    // Phase 8.6: atravesar parts,texto → ContentDelta,fotos → ImageDelta
                    candidate.content?.parts?.forEach { part ->
                        if (part.text.isNotEmpty()) {
                            // v1.0.1 (P1): Marcar contenido publicado,Evitar la retransmisión después de la reconexión
                            anyDeltaSent.set(true)
                            hasEmittedContent.set(true)
                            trySend(ChatStreamEvent.ContentDelta(part.text))
                        }
                        // Phase 8.6: Gemini Dibujo devuelto inlineData(base64)
                        val inline = part.inlineData
                        if (inline != null && inline.data.isNotEmpty()) {
                            anyDeltaSent.set(true)
                            hasEmittedContent.set(true)
                            trySend(
                                ChatStreamEvent.ImageDelta(
                                    imageBase64 = inline.data,
                                    mimeType = inline.mimeType,
                                ),
                            )
                        }
                        // H-GEM2: analizar functionCall → ToolCallDelta
                        // M-GEM3: Utilice un índice creciente para distinguir múltiples functionCall(El 0 fijo original se fusionará en la misma llamada de herramienta)
                        val fc = part.functionCall
                        if (fc != null) {
                            val argsJson = fc.args?.let { AppJson.encodeToString(it) } ?: "{}"
                            anyDeltaSent.set(true)
                            hasEmittedContent.set(true)
                            trySend(
                                ChatStreamEvent.ToolCallDelta(
                                    index = toolCallIndex.getAndIncrement(),
                                    id = fc.name,
                                    name = fc.name,
                                    argumentsDelta = argsJson,
                                ),
                            )
                        }
                    }
                    handleGeminiFinish(
                        reason = candidate.finishReason,
                        hasEmittedContent = hasEmittedContent,
                        finished = finished,
                        emit = { event -> trySend(event) },
                        close = { close() },
                    )
                }

                override fun onClosed(eventSource: EventSource) {
                    // H-GEM1: estilo antiguo onClosed(generation avanzado)solo ignora,Evite el período de transición de reintentos falsos Error
                    if (myGen != generation.get()) return
                    // M-GEM1: No terminó normalmente(No recibido finishReason)es decir, cerrar,enviar Error
                    if (!finished.get()) {
                        trySend(ChatStreamEvent.Error(ErrorCode.STREAM_INTERRUPTED.toMessage("gemini")))
                    }
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    // H-GEM1: estilo antiguo onFailure(generation avanzado)solo ignora
                    if (myGen != generation.get()) return
                    if (request.abortSignal.aborted) {
                        finished.set(true)
                        close()
                        return
                    }
                    val code = response?.code
                    // v1.0.1 (P0): 429 Al limitar la corriente, intente cambiar primero key(Múltiples escenarios clave),Reintentar ahora No es necesario backoff
                    if (code == 429 && !anyDeltaSent.get() && attempt.get() < MAX_RETRIES &&
                        !request.abortSignal.aborted && !scope.isClosedForSend &&
                        switchToNextKey()
                    ) {
                        attempt.incrementAndGet()
                        // H-GEM1: El avance de la generación invalida el cierre posterior de transmisiones antiguas
                        generation.incrementAndGet()
                        Logger.i(
                            TAG,
                            "streamChat onFailure 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ahora " +
                                "(${attempt.get()}/$MAX_RETRIES)",
                        )
                        scope.launch {
                            if (request.abortSignal.aborted || scope.isClosedForSend) return@launch
                            startStream()
                        }
                        return
                    }
                    // v1.0.1 (P1): anyDeltaSent proteger — El contenido que se haya filtrado no se volverá a intentar.(Evite el envío duplicado)
                    // M-GEM2: Errores reintentables(Anormalidad de la red/5xx/429/UNAVAILABLE)retroceso exponencial
                    if (attempt.get() < MAX_RETRIES && !anyDeltaSent.get() && isRetryableError(code, t)) {
                        attempt.incrementAndGet()
                        // H-GEM1: El avance de la generación invalida el cierre posterior de transmisiones antiguas
                        generation.incrementAndGet()
                        scope.launch {
                            delay(backoffMs(attempt.get(), response?.header("Retry-After")))
                            // M-GEM4: Analizar al reintentar token
                            token = try {
                                resolveToken()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                trySend(ChatStreamEvent.Error(e.message ?: "Vertex AI Error de autenticación"))
                                close()
                                return@launch
                            }
                            startStream()
                        }
                        return
                    }
                    // v1.0.1: 401/403 Marcar la clave actual como fallida cuando falla la autenticación(Múltiples escenarios clave)
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // v1.109 Reparación: SSE Creado(2xx)La post-interrupción es una desconexión,Priorizar mensajes arrojables
                    val msg = response?.let {
                        if (it.code in 200..299) {
                            t?.message?.takeIf { m -> m.isNotBlank() }
                                ?: ErrorCode.STREAM_INTERRUPTED.toMessage("gemini")
                        } else {
                            val respBody = ProviderHttpSupport.readBodyCapped(it)
                            parseErrorMessage(it.code, respBody)
                        }
                    } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
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

            val eventSource = sseFactory.newEventSource(httpRequest, listener)
            currentEventSource.set(eventSource)
        }

        startStream()

        awaitClose {
            // L-GEM11: desafinado request.abortSignal.abort(),solo cancel eventSource + call
            abortListener.close()
            currentEventSource.get()?.cancel()
            currentCall.get()?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun completeText(request: ChatRequest): ChatCompletion = completeTextImpl(request, 0)

    private suspend fun completeTextImpl(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        // Consistente con las rutas de transmisión: solo comprima las trayectorias antiguas de las herramientas exitosas en las proyecciones de solicitudes del proveedor.
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages,
            request.model,
        )
        val (system, contents) = splitSystem(normalizedMessages)
        val body = buildRequestBody(
            system = system,
            contents = contents,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            reasoningLevel = request.reasoningLevel,
            supportsImageOutput = request.model.supportsImageOutput(),
            tools = request.tools,
            nativeWebSearch = request.nativeWebSearch,
            modelId = request.model.id,
        )
        // M-GEM7: buildUrl/resolveToken incorporar try-catch,Lanzar IllegalStateException fuera del bloque try omitirá el manejo de errores
        // M-GEM13: Vertex AI resolveToken se lanza cuando la configuración de la cuenta de servicio no es válida. RuntimeException
        val httpRequest: Request
        val call: Call
        try {
            val url = buildUrl(model = request.model.id, stream = false)
            val token: String? = resolveToken()
            httpRequest = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    if (token != null) {
                        header("Authorization", "Bearer $token")
                    }
                }
                // L-GEM8: Sin configuración manual Content-Type,Establecido automáticamente por toRequestBody
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            call = httpClient.newCall(httpRequest)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // M-GEM7: buildUrl/resolveToken Las excepciones se lanzan uniformemente ProviderException,Registro por captura a continuación
            throw ProviderException(
                ProviderError.Unknown(displayMessage = e.message ?: ErrorCode.SERVICE_UNAVAILABLE.toMessage("request_build")),
                cause = e,
            )
        }
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    // v1.0.1 (P0): 429 Cambia de tecla y vuelve a intentarlo.(Múltiples escenarios clave);buildUrl Para uso interno effectiveApiKey(),
                    //   Llamar recursivamente a completeText lo reconstruirá con la nueva clave URL(?key= Actualización de parámetros)
                    if (code == 429 && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i(TAG, "completeText 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextImpl(request, keySwitchDepth + 1)
                    }
                    // v1.0.1: 401/403 Marcar la clave actual como fallida cuando falla la autenticación(Múltiples escenarios clave)
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // M-GEM14: Utilice readBodyCapped en su lugar runCatching { resp.body.string() }
                    val errText = ProviderHttpSupport.readBodyCapped(resp)
                    throw ProviderException(
                        ProviderError.from(
                            code = code,
                            body = errText,
                            retryAfterSec = ProviderError.parseRetryAfter(resp.header("Retry-After")),
                        ),
                    )
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<GeminiResponse>(raw)
                // H-GEM4: promptFeedback Filtrado de seguridad
                parsed.promptFeedback?.blockReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    throw ErrorCode.PERMISSION_DENIED.toProviderException("safety", reason)
                }
                val candidate = parsed.candidates.firstOrNull()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_candidates")
                // H-GEM2: recoger functionCall → toolCalls
                // M-GEM6: usar takeIf { it.isNotEmpty() } Asegúrese de que no se devuelva ninguna herramienta cuando se llame null(Devuelve una lista vacía)
                val toolCalls = candidate.content?.parts?.mapNotNull { it.functionCall }?.map { fc ->
                    ToolCall(
                        id = fc.name,
                        name = fc.name,
                        arguments = fc.args?.let { AppJson.encodeToString(it) } ?: "{}",
                    )
                }?.takeIf { it.isNotEmpty() }
                // Phase 8.6: fusionar todo text part(Ignorar imagen part,completeText Se utiliza para tareas en segundo plano como la memoria.)
                val text = candidate.content?.parts?.joinToString("") { it.text } ?: ""
                // M-GEM4: FinishReason relacionado con la seguridad arroja un error
                val reason = candidate.finishReason
                if (reason in SAFETY_FINISH_REASONS) {
                    throw ErrorCode.PERMISSION_DENIED.toProviderException("safety", reason)
                }
                if (text.isBlank() && toolCalls.isNullOrEmpty()) {
                    throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_text")
                }
                ChatCompletion(
                    text = text,
                    finishReason = reason,
                    toolCalls = toolCalls,
                    usageTokens = parsed.usageMetadata?.toUsageTokens(),
                    citationUrls = candidate.groundingMetadata?.groundingChunks.orEmpty()
                        .mapNotNull { it.web?.uri }
                        .filter { it.startsWith("https://") || it.startsWith("http://") }
                        .distinct(),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d(TAG, "completeText aborted by user")
            } else if (isKnownGeminiError(t)) {
                // Errores comerciales/HTTP registrados,No repetido log
            } else {
                Logger.e(TAG, "completeText anormal", t)
            }
            throw t
        } finally {
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    /**
     * Extraiga la lista de modelos ascendentes.
     *
     * GET {baseUrl}/models?pageSize=100&key={apiKey}&pageToken={token},analizar models[].name
     * (eliminar "models/" prefijo)y displayName,Filtrar por métodos de generación admitidos
     * (Conserve únicamente los modelos que admitan generar contenido).contextWindow usar [ModelContextWindowRegistry] Llega al fondo de esto.
     *
     * Nota:generativelanguage y se han implementado sucursales Vertex — Vertex Los puntos finales de la lista de modelos son diferentes(publishers/google/models),
     * desde dentro [listVertexModels] Proceso(Express usar ?key=,Para cuenta de servicio Bearer),Silenciosamente vuelve a una lista vacía cuando falla.
     *
     * v1.80 (M-GEM5): paginación de soporte,Solicitudes en bucle hasta que nextPageToken sea null,Agregue un límite superior en la cantidad de páginas seguras para evitar bucles infinitos de respuestas anormales.
     *
     * v1.132 Optimizar:
     *  - breve tiempo de espera client(30s),Evite el retraso en la solicitud de paginación y ocupe una conexión prolongada del chat
     *  - Eliminar duplicados por id + ordenar alfabéticamente
     *  - filtro vacío id
     */
    override suspend fun listModels(config: ProviderConfig): List<Model> = withContext(Dispatchers.IO) {
        val specific = config.resolvedSpecific()
        // B8: Vertex AI La lista de modelos utiliza puntos finales independientes.(publishers/google/models),Incompatible con el lenguaje generativo.
        //   Devuelve el modelo ascendente en caso de éxito.;cualquier paso falla(red/Autenticación/Punto final no compatible)Devuelve silenciosamente una lista vacía,
        //   La persona que llama vuelve al directorio del modelo integrado.(No bloquear la página de configuración/Por encima del pliegue).
        if (specific is ProviderSpecificConfig.Gemini && specific.useVertexAI) {
            return@withContext listVertexModels(config, specific)
        }
        val base = config.resolvedBaseUrl().trimEnd('/')
        // H-GEM1: El registro no se imprime ?key= de completo URL,solo golpea base + "/models"
        Logger.i(TAG, "listModels: GET $base/models")
        // v1.132: breve tiempo de espera client
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val seen = HashSet<String>()
        val models = mutableListOf<Model>()
        var pageToken: String? = null
        var pageCount = 0
        val maxPages = 10 // M-GEM5: límite superior de seguridad,Evitar que la excepción nextPageToken provoque un bucle infinito

        do {
            val urlBuilder = "$base/models".toHttpUrl().newBuilder()
                .addQueryParameter("pageSize", "100")
                .addQueryParameter("key", effectiveApiKey())
            // M-GEM5: Parámetros de paginación pageToken
            if (pageToken != null) urlBuilder.addQueryParameter("pageToken", pageToken)
            val httpRequest = Request.Builder()
                .url(urlBuilder.build())
                .header("Accept", "application/json")
                .get()
                .build()
            listClient.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // M-GEM14: usar readBodyCapped
                    val errText = ProviderHttpSupport.readBodyCapped(resp)
                    throw ProviderException(
                        ProviderError.from(
                            code = resp.code,
                            body = "model_list_fetch: $errText",
                            retryAfterSec = ProviderError.parseRetryAfter(resp.header("Retry-After")),
                        ),
                    )
                }
                val raw = resp.body.string()
                val parsed = AppJson.decodeFromString<GeminiModelsResponse>(raw)
                parsed.models
                    // L-GEM10: Los criterios de filtro cambiaron a == true
                    .filter { it.supportedGenerationMethods?.contains("generateContent") == true }
                    .map { m ->
                        val id = m.name.substringAfterLast("/").ifBlank { m.name }
                        Model(
                            id = id,
                            name = m.displayName ?: id,
                            providerId = config.id,
                            contextWindow = ModelContextWindowRegistry.lookup(id),
                        )
                    }
                    // v1.132: Filtrar identificadores vacíos + eliminar duplicados
                    .filter { it.id.isNotBlank() && seen.add(it.id) }
                    .also { models.addAll(it) }
                // M-GEM5: Pasar a la página siguiente token,null Indica que no hay más páginas
                pageToken = parsed.nextPageToken
            }
            pageCount++
        } while (!pageToken.isNullOrBlank() && pageCount < maxPages)

        // v1.132: Ordenar alfabéticamente por identificación,Fácil de encontrar para los usuarios
        models.sortedBy { it.id.lowercase() }
    }

    /**
     * B8: Vertex AI Lista de modelos.
     *
     * Los puntos finales y la autenticación se distinguen por modo:
     *  - cuenta de servicio: GET https://{location}-aiplatform.googleapis.com/v1/projects/{p}/locations/{l}/publishers/google/models (Bearer)
     *  - Express Mode:  GET https://aiplatform.googleapis.com/v1/publishers/google/models?key=<API Key>
     *
     * Vertex Los campos de entrada del modelo devuelto difieren ligeramente del lenguaje generativo(Probablemente ninguno supportedGenerationMethods),
     * Por lo tanto, no se realiza ningún filtrado de habilidades.,Tome directamente el último segmento del nombre como identificación del modelo. Cualquier error vuelve silenciosamente a la lista vacía.
     */
    private suspend fun listVertexModels(config: ProviderConfig, specific: ProviderSpecificConfig.Gemini): List<Model> = try {
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val urlBuilder: okhttp3.HttpUrl.Builder
        var bearer: String? = null
        if (specific.useServiceAccount) {
            bearer = resolveToken() ?: return emptyList()
            val projectId = specific.projectId.takeIf { it.isNotBlank() } ?: return emptyList()
            val location = specific.location.ifBlank { "us-central1" }
            urlBuilder = (
                "https://$location-aiplatform.googleapis.com/v1/projects/$projectId" +
                    "/locations/$location/publishers/google/models"
                ).toHttpUrl().newBuilder()
        } else {
            val key = effectiveApiKey()
            if (key.isBlank()) return emptyList()
            urlBuilder = "$VERTEX_EXPRESS_HOST/publishers/google/models".toHttpUrl().newBuilder()
                .addQueryParameter("key", key)
        }
        urlBuilder.addQueryParameter("pageSize", "100")
        val httpRequest = Request.Builder()
            .url(urlBuilder.build())
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .header("Accept", "application/json")
            .get()
            .build()
        Logger.i(TAG, "listModels(vertex): GET .../publishers/google/models")
        listClient.newCall(httpRequest).execute().use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "listModels(vertex) falló: HTTP ${resp.code}(Recurrir silenciosamente a la lista vacía)")
                return emptyList()
            }
            val raw = resp.body.string()
            if (raw.isBlank()) return emptyList()
            val parsed = AppJson.decodeFromString<GeminiModelsResponse>(raw)
            parsed.models
                .mapNotNull { m ->
                    val id = m.name.substringAfterLast('/').ifBlank { return@mapNotNull null }
                    Model(
                        id = id,
                        name = m.displayName ?: id,
                        providerId = config.id,
                        contextWindow = ModelContextWindowRegistry.lookup(id),
                    )
                }
                .distinctBy { it.id }
                .sortedBy { it.id.lowercase() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(TAG, "listModels(vertex) anormal,Recurrir silenciosamente a la lista vacía: ${e.message}")
        emptyList()
    }

    /**
     * Extraiga el mensaje del SISTEMA como systemInstruction,Otras transferencias Gemini contents(assistant → model).
     *
     * Phase 8.6: USER Si la noticia contiene imageBase64List,Convertir a inlineData part,
     * texto como separado text part,orden:Imágenes primero, texto después(Gemini Orden recomendado).
     *
     * H-GEM3: Función de reproducción del historial de llamadas
     *  - ASSISTANT Al transportar herramientasLlamadas,Cada herramienta llama a su vez functionCall part(junto con args)
     *  - TOOL transferencia de mensajes functionResponse part(Contiene name + response JSON)
     */
    private fun splitSystem(messages: List<UIMessage>): Pair<String?, List<GeminiContent>> {
        val systemParts = messages.filter { it.role == MessageRole.SYSTEM }
            .map { it.content }
            .filter { it.isNotBlank() }
        val system = systemParts.joinToString("\n\n").ifBlank { null }
        val rest = messages
            .filter { it.role != MessageRole.SYSTEM }
            .map { msg ->
                val geminiRole = when (msg.role) {
                    MessageRole.ASSISTANT -> "model"
                    MessageRole.USER -> "user"
                    MessageRole.TOOL -> "user" // Gemini Ninguno tool role,degradado a user
                    MessageRole.SYSTEM -> "user" // no llegará(SYSTEM Filtrado),Integridad de la compilación
                }
                val parts = buildList {
                    when {
                        // H-GEM3: ASSISTANT llevar toolCalls → functionCall part
                        msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty() -> {
                            msg.toolCalls.forEach { tc ->
                                // L-GEM1: Utilice resultOf en su lugar runCatching,propagar correctamente CancellationException
                                val args = resultOf { AppJson.parseToJsonElement(tc.arguments) }
                                    .getOrNull() ?: JsonNull
                                add(GeminiPart(functionCall = GeminiFunctionCall(name = tc.name, args = args)))
                            }
                            // texto(Si es así)
                            if (msg.content.isNotEmpty()) add(GeminiPart(text = msg.content))
                        }
                        // H-GEM3: TOOL noticias → functionResponse part(Contiene name + response JSON)
                        msg.role == MessageRole.TOOL -> {
                            // L-GEM1: Utilice resultOf en su lugar runCatching
                            val respJson = resultOf { AppJson.parseToJsonElement(msg.content) }
                                .getOrNull()
                                ?: JsonObject(mapOf("result" to JsonPrimitive(msg.content)))
                            // M-GEM8: toolCallId Usado cuando está vacío fallback,Evite que Gemini rechace la solicitud si functionResponse.name está vacío
                            // (Gemini Requiere que functionResponse.name coincida con el functionCall.name anterior)
                            val fnName = msg.toolCallId?.takeIf { it.isNotBlank() } ?: "unknown"
                            add(
                                GeminiPart(
                                    functionResponse = GeminiFunctionResponse(
                                        name = fnName,
                                        response = respJson,
                                    ),
                                ),
                            )
                        }
                        else -> {
                            // Normal USUARIO/ASISTENTE: Imagen primero(si existe)+ vídeo(si existe)+ texto
                            // E-P2: Alineado con OpenAI/Anthropic — Hasta 4 fotos,folleto base64 ≤2MB
                            val validImages = msg.imageBase64List.filter { it.isNotEmpty() }
                            if (validImages.size > MAX_VISION_IMAGES) {
                                Logger.w("GeminiProvider", "Número de fotos ${validImages.size} Superar el límite superior $MAX_VISION_IMAGES,descartar el exceso")
                            }
                            validImages.take(MAX_VISION_IMAGES).forEach { b64 ->
                                if (b64.length > MAX_IMAGE_BASE64_LEN) {
                                    Logger.w("GeminiProvider", "Longitud de la imagen base64 ${b64.length} exceder $MAX_IMAGE_BASE64_LEN,descartar")
                                    return@forEach
                                }
                                add(
                                    GeminiPart(
                                        inlineData = GeminiInlineData(
                                            // M-GEM11: mimeType Inferido de bytes mágicos del encabezado base64
                                            mimeType = inferMimeType(b64),
                                            data = b64,
                                        ),
                                    ),
                                )
                            }
                            // V-GEM1: Adjunto de vídeo(Subido a través de uploadFile a Files API,Sólo citado aquí fileUri)
                            val vUri = msg.videoFileUri
                            val vMime = msg.videoMimeType
                            if (!vUri.isNullOrBlank() && !vMime.isNullOrBlank()) {
                                add(
                                    GeminiPart(
                                        fileData = GeminiFileData(
                                            mimeType = vMime,
                                            fileUri = vUri,
                                        ),
                                    ),
                                )
                            }
                            // texto(Agregar incluso si está vacío,Evite matrices de piezas vacías)
                            add(GeminiPart(text = msg.content))
                        }
                    }
                }
                val finalParts = if (parts.isEmpty()) listOf(GeminiPart(text = msg.content)) else parts
                GeminiContent(role = geminiRole, parts = finalParts)
            }
        return system to rest
    }

    /**
     * Phase 9.4 (M10): URL Construir ramas.
     *
     * - generativelanguage API: {baseUrl}/models/{model}:{action}?key={apiKey}[&alt=sse]
     * - Vertex AI: https://{location}-aiplanet.googleapis.com/v1/projects/{projectId}/
     *              locations/{location}/publishers/google/models/{model}:{action}[?alt=sse]
     *   (Vertex AI Autenticar con token de portador,No agregado ?key=)
     *
     * M-GEM8: action Obtener valor despecific.streamPath/generatePath(ir al líder `:`),No codificado.
     * B14-3: Vertex Las sucursales se distinguen por useServiceAccount —— Ir al número de servicio OAuth Bearer,Express Mode ir ?key=API Key.
     */
    internal fun buildUrl(model: String, stream: Boolean): String {
        val specific = config.resolvedSpecific()
        val geminiSpecific = specific as? ProviderSpecificConfig.Gemini
        // M-GEM8: Obtener la ruta del punto final desde específico(ir al líder `:`),Si está vacío, use el valor predeterminado
        val action = if (stream) {
            geminiSpecific?.streamPath?.removePrefix(":")?.takeIf { it.isNotBlank() }
                ?: "streamGenerateContent"
        } else {
            geminiSpecific?.generatePath?.removePrefix(":")?.takeIf { it.isNotBlank() }
                ?: "generateContent"
        }

        // Phase 9.4 (M10): Vertex AI rama de punto final
        // B14-3: Admite dos modos de autenticación,Distinguir por useServiceAccount —
        //   1. cuenta de servicio(useServiceAccount=true): punto final de zona + Bearer(OAuth),necesidad projectId;
        //   2. Express Mode(useVertexAI=true y useServiceAccount=false): punto final global + ?key=<API Key>,
        //      No se requiere proyecto GCP/OAuth/cuenta de servicio(ver Google "Vertex AI in express mode" Documentación).
        //      Este es el método de acceso a Vertex de menor costo en escenarios BYOK.
        if (geminiSpecific != null && geminiSpecific.useVertexAI) {
            val actionPath = "$model:$action"
            if (geminiSpecific.useServiceAccount) {
                val location = geminiSpecific.location.ifBlank { "us-central1" }
                val projectId = geminiSpecific.projectId.ifBlank {
                    error(ErrorCode.VERTEX_AI_CONFIG_INVALID.toMessage("missing_project_id"))
                }
                val base = "https://$location-aiplatform.googleapis.com/v1"
                val urlBuilder = ("$base/projects/$projectId/locations/$location/publishers/google/models/$actionPath")
                    .toHttpUrl()
                    .newBuilder()
                if (stream) urlBuilder.addQueryParameter("alt", "sse")
                return urlBuilder.build().toString()
            }
            // Express Mode: punto final global + API Key(Coherente con el cuerpo de solicitud del lenguaje generativo.,Sólo la URL/autenticación es diferente)
            val key = effectiveApiKey()
            if (key.isBlank()) {
                error(ErrorCode.VERTEX_AI_CONFIG_INVALID.toMessage("missing_api_key"))
            }
            val urlBuilder = ("$VERTEX_EXPRESS_HOST/publishers/google/models/$actionPath")
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("key", key)
            if (stream) urlBuilder.addQueryParameter("alt", "sse")
            return urlBuilder.build().toString()
        }

        // generativelanguage API(Predeterminado)
        // v1.0.1: usar effectiveApiKey() Admite rotación de múltiples teclas(429 Después de cambiar de llave URL ?key= Es necesario actualizar los parámetros.)
        val base = config.resolvedBaseUrl().trimEnd('/')
        val urlBuilder = ("$base/models/$model:$action").toHttpUrl().newBuilder()
            .addQueryParameter("key", effectiveApiKey())
        if (stream) urlBuilder.addQueryParameter("alt", "sse")
        return urlBuilder.build().toString()
    }

    /**
     * Phase 8.6: buildRequestBody aumentar [supportsImageOutput] parámetros.
     *
     * Cuando se declara el modelo supportsImageOutput()(Model.outputModalities Contiene "image")tiempo,
     * Agregar generaciónConfig responseModalities = ["TEXT", "IMAGE"],
     * Deje que Gemini devuelva contenido mixto de texto e imagen(Gemini 2.5 Flash Image / Imagen via generateContent).
     *
     * H-GEM2: [tools] Transmisión transparente al formato de herramientas Gemini.(Una sola herramienta envuelve una serie de declaraciones de funciones.).
     */
    /**
     * Piense si utilizar engranajes o presupuesto simbólico para mayor profundidad.
     *
     * Gemini 3 De generación en generación (incluida la 4ª generación/Argon) solo se aceptan engranajes;Gemini 1/2 El agente sólo acepta presupuesto.
     * Si el nombre del modelo no coincide con ninguna generación, devuelve false para conservar el comportamiento anterior y quedar cubierto por el respaldo automático de la persona que llama.
     */
    private fun usesThinkingLevel(modelId: String?): Boolean {
        val id = modelId?.lowercase() ?: return false
        if (id.contains("gemini-1") || id.contains("gemini-2")) return false
        return true
    }

    private fun buildRequestBody(
        system: String?,
        contents: List<GeminiContent>,
        temperature: Float?,
        maxTokens: Int?,
        reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
        supportsImageOutput: Boolean = false,
        tools: List<ToolDefinition>? = null,
        nativeWebSearch: Boolean = false,
        modelId: String? = null,
        forceThinkingLevel: Boolean = false,
    ): String {
        // Considere dos formas de escribir parámetros:
        //  - Gemini 2 Generaciones y anteriores:thinkingConfig.thinkingBudget(token número)
        //  - Gemini 3 Generaciones superiores (incluida la 4.ª generación/Argon):thinkingConfig.thinkingLevel(engranaje)
        // Si envía un texto incorrecto, el servidor lo rechazará directamente con un error 400, así que elija según el modelo y la generación.
        // [forceThinkingLevel] Para respaldo automático: no reconoce el nombre del modelo y fuerza la emisión del equipo.
        val thinkingCfg = when (reasoningLevel) {
            ReasoningLevel.OFF -> null
            ReasoningLevel.AUTO -> null
            else -> if (forceThinkingLevel || usesThinkingLevel(modelId)) {
                reasoningLevel.effort?.let { GeminiThinkingConfig(thinkingLevel = it) }
            } else {
                reasoningLevel.budgetTokens?.let { GeminiThinkingConfig(thinkingBudget = it) }
            }
        }
        // Phase 8.6 + M-GEM12: responseModalities Capitalización unificada
        val responseModalities = if (supportsImageOutput) listOf("TEXT", "IMAGE") else null
        // H-GEM2: tools paso a través(Gemini Envuelva la matriz functionDeclarations con una sola herramienta)
        // H-COMPAT1: compat.supportsToolCalling=false No emitido cuando es forzado tools
        //   (Gemini Predeterminado true,Deje este juicio para mantener la derivación centralizada.,Facilitar la expansión futura mediante modelId)
        val compat: ProviderCompat = config.resolvedCompat()
        val geminiTools = when {
            // Gemini generateContent Actualmente no se permite mezclar google_search con herramientas de funciones normales.
            nativeWebSearch -> listOf(GeminiTool(googleSearch = GeminiGoogleSearch()))
            compat.supportsToolCalling -> tools?.takeIf { it.isNotEmpty() }?.let {
                listOf(GeminiTool(functionDeclarations = it.map { td -> td.toGeminiDeclaration() }))
            }
            else -> null
        }
        val payload = GeminiRequest(
            contents = contents,
            systemInstruction = system?.takeIf { it.isNotBlank() }
                ?.let { GeminiContent(role = "user", parts = listOf(GeminiPart(text = it))) },
            generationConfig = GeminiGenerationConfig(
                temperature = temperature?.toDouble(),
                maxOutputTokens = maxTokens,
                thinkingConfig = thinkingCfg,
                responseModalities = responseModalities,
            ),
            tools = geminiTools,
        )
        return AppJson.encodeToString(payload)
    }

    /** H-GEM2: ToolDefinition → GeminiFunctionDeclaration(parameters analizado como JsonElement). */
    private fun ToolDefinition.toGeminiDeclaration(): GeminiFunctionDeclaration = GeminiFunctionDeclaration(
        name = name,
        description = description,
        // L-GEM1: Utilice resultOf en su lugar runCatching,propagar correctamente CancellationException
        parameters = resultOf {
            AppJson.parseToJsonElement(parametersJsonSchema)
        }.getOrNull(),
    )

    /**
     * M-GEM11: Inferir el tipo MIME de la imagen a partir de los bytes mágicos del encabezado base64.
     * Bajar de categoría a image/jpeg(Fotos más comunes).
     */
    private fun inferMimeType(b64: String): String {
        val head = b64.take(16).uppercase()
        return when {
            head.startsWith("IVBORW0") -> "image/png" // PNG: iVBORw0K
            head.startsWith("/9J/") -> "image/jpeg" // JPEG: /9j/
            head.startsWith("UKLGR") -> "image/webp" // WebP: UklGR
            head.startsWith("R0LGOD") -> "image/gif" // GIF: R0lGOD
            else -> "image/jpeg"
        }
    }

    /**
     * M-GEM2: Determinar si el error se puede volver a intentar(UNAVAILABLE/5xx/429/Anormalidad de la red).
     * v1.80 (M-GEM1): 429(RESOURCE_EXHAUSTED)Incorporar reintentable,Originalmente, solo 5xx/IOException causaba que la limitación actual fallara directamente.
     */
    private fun isRetryableError(code: Int?, t: Throwable?): Boolean {
        if (t != null && t is java.io.IOException) return true
        if (code != null && (code == 429 || code in 500..599)) return true
        return false
    }

    /**
     * M-GEM2: retroceso exponencial(500ms / 1000ms / 2000ms).
     * v1.80 (L-GEM2): agregar al azar jitter(±20%),Evite los reintentos sincronizados de varios clientes que provocan ataques de pánico.
     */
    private fun backoffMs(attempt: Int, retryAfterHeader: String? = null): Long {
        ProviderError.parseRetryAfter(retryAfterHeader)?.let { return it.toLong() * 1000L }
        val shift = (attempt - 1).coerceIn(0, 4)
        val base = 500L * (1L shl shift)
        // L-GEM2: jitter = base * (0.8 + random*0.4),alcance [0.8*base, 1.2*base]
        val jitter = base * (0.8 + Random.nextDouble() * 0.4)
        return jitter.toLong()
    }

    /**
     * M-GEM3: Distinguir tipos de errores por estado,Dé indicaciones en chino legibles por humanos.
     */
    private fun parseErrorMessage(code: Int, body: String): String {
        if (body.isBlank()) return "HTTP $code"
        // L-GEM1: Utilice resultOf en su lugar runCatching,propagar correctamente CancellationException
        val detail = resultOf {
            AppJson.decodeFromString<GeminiErrorBody>(body).error
        }.getOrNull()
        val status = detail?.status
        val statusHint = when (status) {
            "RESOURCE_EXHAUSTED" -> "resource exhausted"
            "UNAVAILABLE" -> "unavailable"
            "PERMISSION_DENIED" -> "permission denied"
            "INVALID_ARGUMENT" -> "invalid argument"
            "FAILED_PRECONDITION" -> "failed precondition"
            else -> null
        }
        return buildString {
            append("HTTP ").append(code)
            status?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
            statusHint?.let { append(" [").append(it).append("]") }
            detail?.message?.takeIf { it.isNotBlank() }?.let {
                append(": ").append(ProviderError.safeErrorDetail(it) ?: it.take(200))
            }
        }
    }

    /**
     * M-GEM13: analizar Vertex AI Bearer token(Transmitiendo concompleteTextjuicio compartido).
     * - Modo de cuenta de servicio sin Vertex AI:Regresar null(ir ?key= Autenticación)
     * - Error en la construcción del autenticador/excepción en la recuperación del token:tirar RuntimeException(Atendido por la persona que llama)
     */
    internal suspend fun resolveToken(): String? {
        val specific = config.resolvedSpecific()
        val needToken = specific is ProviderSpecificConfig.Gemini &&
            specific.useVertexAI && specific.useServiceAccount
        if (!needToken) return null
        // by lazy Las propiedades de los delegados no se pueden convertir de forma inteligente,Captura con variables locales
        val auth = vertexAiAuthToken
        if (auth == null) {
            throw ErrorCode.VERTEX_AI_CONFIG_INVALID.toProviderException()
        }
        return try {
            auth.getValidToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ErrorCode.VERTEX_AI_TOKEN_FAILED.toProviderException(e.message ?: "")
        }
    }

    /**
     * V-GEM1: Subir vídeo(u otros archivos grandes)llegar Gemini Files API,Devuelve un fileUri al que se puede hacer referencia en generateContent.
     *
     * proceso:
     *  1. POST /upload/v1beta/files?key=... (multipart: metadata JSON + bytes de archivo)
     *     La respuesta contiene file.name / file.uri / file.state(inicial PROCESSING)
     *  2. sondeo GET /v1beta/files/{name}?key=... hasta state=ACTIVE(Lanzar un error en caso de falla)
     *  3. Regresar file.uri,La persona que llama lo completa [UIMessage.videoFileUri]
     *
     * Solo compatible con API de lenguaje generativo Files API;Vertex AI debería ir GCS uri(Este método no admite,
     * La persona que llama debe tomar una ruta alternativa en modo Vertex o arrojar un error).
     *
     * @param bytes bytes de archivo(La persona que llama es responsable de leer el Uri de Android y pasarlo.,Evite dependencias de este módulo. android.net.Uri)
     * @param mimeType MIME Tipo,Como por ejemplo "video/mp4"
     * @param displayName nombre de archivo(Opcional,Sólo para visualización del lado del servidor)
     * @return fileUri(en forma de "https://generativelanguage.googleapis.com/v1beta/files/abc123")
     */
    suspend fun uploadFile(bytes: ByteArray, mimeType: String, displayName: String? = null): String = withContext(Dispatchers.IO) {
        val specific = config.resolvedSpecific()
        // V-GEM1: Vertex AI no irse Files API,Rechazar directamente(La persona que llama debe ir a GCS o inlineData)
        require(specific !is ProviderSpecificConfig.Gemini || !specific.useVertexAI) {
            "Files API Solo compatible con API de lenguaje generativo,Vertex AI Por favor use GCS uri"
        }
        require(bytes.isNotEmpty()) { "Los datos cargados están vacíos" }
        require(mimeType.isNotBlank()) { "mimeType no puede estar vacío" }

        val base = config.resolvedBaseUrl().trimEnd('/').toHttpUrl().run {
            // generativelanguage API La URL base predeterminada se parece a https://generativelanguage.googleapis.com/v1beta
            // Files API El punto final /upload/v1beta/files está bajo la raíz del host,Es necesario eliminar la parte de la ruta de baseUrl.
            // Puerto personalizado(no 443/80)necesita ser retenido,para apoyar a la contrageneración/tránsito
            if (port == 443 || port == 80) "$scheme://$host" else "$scheme://$host:$port"
        }

        // 1. Subir archivos(multipart/form-data)
        val uploadUrl = "$base/upload/v1beta/files".toHttpUrl().newBuilder()
            .addQueryParameter("key", effectiveApiKey())
            .build()
        val metadataJson = AppJson.encodeToString(
            GeminiFileMetadata(name = displayName ?: "files/video_${System.currentTimeMillis()}"),
        )
        val mediaType = mimeType.toMediaType()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", metadataJson, metadataJson.toRequestBody(JSON_MEDIA_TYPE))
            .addFormDataPart("file", displayName ?: "video", bytes.toRequestBody(mediaType))
            .build()
        val uploadReq = Request.Builder()
            .url(uploadUrl)
            .header("Accept", "application/json")
            .header("X-Goog-Upload-Protocol", "multipart")
            .post(multipart)
            .build()
        val fileName: String = httpClient.newCall(uploadReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errText = ProviderHttpSupport.readBodyCapped(resp)
                throw ProviderException(ProviderError.from(code = resp.code, body = "Error al subir el archivo: $errText"))
            }
            val raw = resp.body.string()
            val parsed = AppJson.decodeFromString<GeminiFileUploadResponse>(raw)
            val f = parsed.file ?: throw ProviderException(
                ProviderError.InvalidRequest(displayMessage = "Falta el campo de archivo en la respuesta de carga: $raw"),
            )
            if (f.uri.isBlank() || f.name.isBlank()) {
                throw ProviderException(
                    ProviderError.InvalidRequest(displayMessage = "Cargar respuesta file.uri/file.name está vacío: $raw"),
                )
            }
            // La carga fue exitosa pero el servidor informó un error(Si mime no es compatible),tirar directamente
            f.error?.let { e ->
                throw ProviderException(
                    ProviderError.Unknown(displayMessage = "Carga de archivos rechazada: ${e.message ?: "error desconocido"}"),
                )
            }
            f.name
        }

        // 2. sondeo GET /v1beta/files/{name} hasta state=ACTIVE(límite superior 60s,intervalo 1s)
        val pollUrl = "$base/$fileName".toHttpUrl().newBuilder()
            .addQueryParameter("key", effectiveApiKey())
            .build()
        val deadline = System.currentTimeMillis() + FILE_POLL_TIMEOUT_MS
        var lastState = "PROCESSING"
        while (System.currentTimeMillis() < deadline) {
            val pollReq = Request.Builder()
                .url(pollUrl)
                .header("Accept", "application/json")
                .get()
                .build()
            val fileUri = httpClient.newCall(pollReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errText = ProviderHttpSupport.readBodyCapped(resp)
                    throw ProviderException(ProviderError.from(code = resp.code, body = "No se pudo consultar el estado del archivo: $errText"))
                }
                val raw = resp.body.string()
                val f = AppJson.decodeFromString<GeminiFileResource>(raw)
                lastState = f.state
                when (f.state) {
                    "ACTIVE" -> return@use f.uri // Carga completada,devolución disponible uri
                    "FAILED" -> {
                        val errMsg = f.error?.message ?: "Error en el procesamiento del lado del servidor"
                        throw ProviderException(ProviderError.ServerError(httpCode = 500, displayMessage = "Error al procesar el archivo: $errMsg"))
                    }
                    else -> null // PROCESSING estado intermedio,Continuar encuestando
                }
            }
            if (fileUri != null) return@withContext fileUri
            delay(FILE_POLL_INTERVAL_MS)
        }
        throw ProviderException(ProviderError.Unknown(displayMessage = "Tiempo de espera de procesamiento de archivos(60s),estado final: $lastState"))
    }

    /** V-GEM1: uploadFile subestructura de metadatos de(Solo campo de nombre,El servidor ignora otros). */
    @kotlinx.serialization.Serializable
    private data class GeminiFileMetadata(
        val name: String,
    )

    private fun decodeGeminiStreamFrame(data: String): io.zer0.common.Result<GeminiResponse> = resultOf {
        AppJson.decodeFromString<GeminiResponse>(data)
    }

    private fun isKnownGeminiError(throwable: Throwable): Boolean {
        val runtime = throwable as? RuntimeException ?: return false
        val message = runtime.message.orEmpty()
        return listOf("Gemini", "HTTP", "Gemini Filtrado de seguridad", "Vertex AI").any(message::contains)
    }

    private fun handleGeminiFinish(
        reason: String?,
        hasEmittedContent: AtomicBoolean,
        finished: AtomicBoolean,
        emit: (ChatStreamEvent) -> Unit,
        close: () -> Unit,
    ) {
        if (reason == null) return
        if (reason in SAFETY_FINISH_REASONS) {
            finished.set(true)
            emit(ChatStreamEvent.Error(ErrorCode.PERMISSION_DENIED.toMessage("safety", reason)))
            close()
            return
        }
        finished.set(true)
        if (!hasEmittedContent.get()) {
            Logger.w(
                TAG,
                "B-42: Gemini transmisión emptyContentButFinished(solo razonamiento ninguno content," +
                    " finishReason=$reason),Envíe StreamInterrupted para activar un respaldo sin transmisión",
            )
            emit(ChatStreamEvent.StreamInterrupted(ErrorCode.STREAM_INTERRUPTED.toMessage("gemini")))
        } else {
            emit(ChatStreamEvent.Done(reason))
        }
        close()
    }

    private companion object {
        const val TAG = "GeminiProvider"

        /**
         * B14-3: Vertex AI Express Mode punto final global(API Key Autenticación,No se requiere proyecto GCP/OAuth).
         * Ortografía del camino /publishers/google/models/{model}:{action},key Vaya a consultar parámetro.
         * ver Google "Vertex AI in express mode" Documentación.
         */
        const val VERTEX_EXPRESS_HOST = "https://aiplatform.googleapis.com/v1"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** M-GEM2: El número máximo de reintentos para la desconexión de la transmisión. */
        const val MAX_RETRIES = 3

        // completeText 429 Número máximo de veces para cambiar de clave,Prevenir la recursividad infinita
        const val MAX_KEY_SWITCHES = 3

        /** M-GEM4: Relacionado con la seguridad finishReason,Enviar error en lugar de listo. */
        val SAFETY_FINISH_REASONS = setOf("SAFETY", "RECITATION", "BLOCKLIST")

        /** V-GEM1: Files API Tiempo de espera total del sondeo de estado(60s,Cubre tiempos comunes de procesamiento de video.). */
        const val FILE_POLL_TIMEOUT_MS = 60_000L

        /** V-GEM1: Files API intervalo de sondeo de estado(1s,Equilibre la velocidad de respuesta y la presión del servidor). */
        const val FILE_POLL_INTERVAL_MS = 1_000L

        // E-P2: Limitaciones de entrada visual,Alineado con OpenAI/Anthropic(Hasta 4 fotos,folleto base64 ≤2MB)
        const val MAX_VISION_IMAGES = 4
        const val MAX_IMAGE_BASE64_LEN = 2 * 1024 * 1024
    }
}
