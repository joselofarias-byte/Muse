package io.zer0.ai.openai

import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.FreeModelConfig
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ProviderCompat
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderError
import io.zer0.ai.core.ProviderException
import io.zer0.ai.core.ProviderHttpDefaults
import io.zer0.ai.core.ProviderHttpSupport
import io.zer0.ai.core.ProviderPayloadNormalizer
import io.zer0.ai.core.ProviderPromptPatches
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderTemplateEngine
import io.zer0.ai.core.ReasoningCarrier
import io.zer0.ai.core.ReasoningReplayPolicy
import io.zer0.ai.core.ThinkingFormat
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolCallSanitizer
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.malformedStreamFrameError
import io.zer0.ai.core.toProviderException
import io.zer0.ai.ollama.OllamaVisionInferrer
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.AppJson
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.common.toMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * v2.x(Registro de solución de problemas): Responses API El resultado del procesamiento de un mensaje histórico durante el ensamblaje.
 *
 * fondo: Responses API Rechazar mensajes con contenido vacío (HTTP 400 `Message content cannot be empty`).
 * Una vez que el mensaje del asistente vacío (comúnmente encontrado en la rueda de herramientas o el remanente del último fallo) ingresa al historial, causará 400 en cada ronda posterior.
 * Formando un ciclo autorrecurrente.
 */
internal enum class ResponsesMessageDisposition {
    /** Conservar normalmente (junto con su elemento de mensaje). */
    KEEP,

    /** Conserve el mensaje (porque contiene llamadas a herramientas y debe convertirse a función_call), pero no genere un elemento de mensaje con contenido vacío. */
    SKIP_MESSAGE_ITEM,

    /** Un mensaje completamente vacío sin ningún contenido.: Saltar en general. */
    SKIP,
}

/** Extract final Responses API message text when delta events were omitted. */
internal fun responsesOutputText(response: ResponsesResult): String {
    response.outputText?.takeIf { it.isNotBlank() }?.let { return it }
    return response.output
        .asSequence()
        .filter { it.type == "message" }
        .flatMap { it.content.orEmpty().asSequence() }
        .filter { it.type == "output_text" || it.type == "text" }
        .mapNotNull { it.text }
        .joinToString("")
}

internal fun shouldRetryResponsesFailure(code: Int?, anyDeltaSent: Boolean, retryCount: Int, maxRetries: Int, aborted: Boolean): Boolean {
    val retryableHttp = code == 408 || code == 429 || code in 500..599
    val networkFailure = code == null || code <= 0
    return !aborted && !anyDeltaSent && retryCount < maxRetries && (retryableHttp || networkFailure)
}

/**
 * Reparación de auditoría (Comentarios de los usuarios): DeepSeek Normalización oficial del nombre del modelo API.
 * listModels Regreso con fecha/variaciones de casos id(Como por ejemplo "DeepSeek-V4-Flash-0731"),
 * Pero /chat/completions sólo acepta nombres canónicos en minúsculas(deepseek-v4-pro / deepseek-v4-flash),
 * Enviar la identificación original directamente 400 "The supported API model names are..."(Pruebas de usuarios reales).
 * regla de normalización: eliminar el separador después de minúsculas,Si comienza con deepseek-v4-flash → deepseek-v4-flash;
 * Comience con deepseek-v4-pro → deepseek-v4-pro; Solo tiene efecto para la baseUrl oficial de DeepSeek.
 * La API oficial que no pertenece a DeepSeek regresa tal cual(Las estaciones de tránsito pueden tener nombres personalizados).
 */
internal fun normalizeDeepSeekModelId(modelId: String, currentBaseUrl: String): String {
    if (!currentBaseUrl.lowercase().contains("api.deepseek.com")) return modelId
    val normalized = modelId.lowercase().replace("_", "-")
    return when {
        normalized.startsWith("deepseek-v4-flash") -> "deepseek-v4-flash"
        normalized.startsWith("deepseek-v4-pro") || normalized.startsWith("deepseek-v4") -> "deepseek-v4-pro"
        else -> modelId
    }
}

/**
 * v2.6.1: Quite la etiqueta del grupo principal adjunta a la lista de modelos de la estación de retransmisión del sistema one-api/new-api.
 *
 * Algunas estaciones de transferencia se utilizan en /v1/modelos `[grupo]Nombre del modelo` Mostrar agrupación de canales(Como por ejemplo `[ais]gemini-3.8-flash`),
 * Pero la sección de corchetes**No es un nombre de modelo enrutable**,Si lo envías tal cual, recibirás
 * `No available channel for model [ais]xxx ... code=model_not_found`(Pruebas de usuarios reales).
 * Los nombres de los modelos legales no contienen corchetes,Por lo tanto, mientras haya una dirección `[...]` Sólo quítalo;Soporte multinivel(Como por ejemplo `[A][vip]claude-3`).
 * Vaciar después de pelar(etiqueta pura,Sin nombre de modelo real)Volver al valor original,Evite enviar solicitudes a un modelo vacío.
 *
 * Ver la implementación [io.zer0.ai.core.stripChannelGroupLabels](función compartida,La capa de identificación de capacidades también utiliza).
 */
internal fun stripChannelGroupLabelsImpl(modelId: String): String = io.zer0.ai.core.stripChannelGroupLabels(modelId)

/**
 * OpenAI Compatible con proveedores. También es compatible con todas las transferencias que utilizan el protocolo OpenAI Chat Completions./Servicios autohospedados,
 * solo [ProviderConfig.baseUrl] Diferente.
 *
 * - transmisión: ir SSE,`data: [DONE]` Indica el final
 * - Cancelar: UI sintonizar [ChatRequest.abortSignal] -> Cancelar OkHttp Call
 * - error: HTTP Cuando no sea 200, lea el cuerpo para extraer la estructura de error de OpenAI,Volver al texto original en caso de error
 */
class OpenAIProvider(
    config: ProviderConfig,
) : ProviderHttpSupport(config) {

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName

    /** Configuración específica de OpenAI analizada(specific Cuando sea nulo, presione escribir).L-OAI12: by lazy Evite el análisis repetido. */
    private val openAIConfig: ProviderSpecificConfig.OpenAI by lazy {
        config.resolvedSpecific() as? ProviderSpecificConfig.OpenAI
            ?: ProviderSpecificConfig.OpenAI()
    }

    /** B3-05: Custom Configuración específica del proveedor(Plantilla de solicitud personalizada/ruta de respuesta/encabezados/campo de cuerpo). */
    private val customConfig: ProviderSpecificConfig.Custom? by lazy {
        config.specific as? ProviderSpecificConfig.Custom
    }

    /** Tanto los campos de solicitud adicionales específicos de OpenAI como los personalizados admiten campos de solicitud adicionales. */
    private val customHeaders: Map<String, String> by lazy {
        customConfig?.customHeaders ?: openAIConfig.customHeaders
    }

    /**
     * v2.6.1: Los encabezados de solicitud adicionales reales enviados.
     *
     * Codex Además del portador, el backend también necesita identificar la familia del cliente. originator / User-Agent,
     * Los encabezados adicionales del usuario están unificados aquí.(Se dará prioridad cuando el usuario configure explícitamente un encabezado con el mismo nombre.);Los puntos finales restantes mantienen su comportamiento original.
     * `ChatGPT-Account-ID` Escrito después de iniciar sesión en OAuth customHeaders,Haga efecto juntos.
     */
    private val requestHeaders: Map<String, String> by lazy {
        if (!isCodexBackend()) {
            customHeaders
        } else {
            buildMap {
                put("originator", "muse")
                put("User-Agent", CODEX_USER_AGENT)
                // v2.6.2: Codex /responses El punto final requiere el encabezado beta del protocolo Responses,
                // Tanto la CLI oficial como la implementación comunitaria vienen con `responses=experimental`(o códice-1).
                // Cuando falta, el backend puede rechazar directamente la solicitud de transmisión (tipo de contenido no válido medido por los usuarios).
                put("OpenAI-Beta", "responses=experimental")
                putAll(customHeaders)
            }
        }
    }
    private val customBody: Map<String, JsonElement> by lazy {
        customConfig?.customBody ?: openAIConfig.customBody
    }

    /**
     * v2.6.2: Los encabezados adicionales realmente enviados por esta solicitud.
     *
     * en estático [requestHeaders] Básicamente, presione baseUrl host para completar automáticamente el encabezado obligatorio de la estación de transferencia.
     * (como el de OpenCode Go `x-opencode-session`)—— Este encabezado debe ser estable por sesión, por lo que debe
     * En el nivel de solicitud presione [sessionId] Actualmente, no se puede almacenar en caché en instancias de proveedores.
     * Se dará prioridad cuando el usuario complete explícitamente el encabezado con el mismo nombre en el encabezado personalizado (ver [RelayRequiredHeaders.mergeWithUserHeaders]).
     */
    private fun requestHeadersFor(sessionId: String?): Map<String, String> {
        val auto = io.zer0.ai.core.RelayRequiredHeaders.autoHeaders(baseUrl(), sessionId, config.id)
        if (auto.isEmpty()) return requestHeaders
        return io.zer0.ai.core.RelayRequiredHeaders.mergeWithUserHeaders(auto, requestHeaders)
    }

    /**
     * v1.0.7: Ya sea para ir Responses API(/v1/responses punto final).
     *
     * Implementado de acuerdo con el protocolo API de Respuestas oficial de OpenAI.
     * cuando [ProviderSpecificConfig.OpenAI.useResponseApi]=true tiempo:
     *  - streamChat/completeText Cambia tu ruta [responsesPath] punto final(Predeterminado /responses)
     *  - En su lugar, el cuerpo de la solicitud utiliza la estructura ResponsesRequest.(messages → input, system → instructions)
     *  - Las respuestas en streaming utilizan el evento Response.output_text.delta en su lugar.(sustituto delta.content)
     *  - respuesta sin transmisión en su lugar output[] matriz(sustituto choices[0].message)
     *
     * Reparación"engañar a los usuarios bug":ProviderType.OPENAI_RESPONSES + useResponseApi=true declarado,
     * Pero las versiones anteriores de OpenAIProvider no leen esta bandera en absoluto,Siga siempre el protocolo de finalización del chat.,
     * Como resultado, después de que el usuario configura la API de respuestas, el formato de finalización del chat se envía a /chat/completions,
     * Y el punto final de la API de Responses es /responses y la solicitud/La estructura de respuesta es completamente diferente. — Serán 404 o 400.
     */
    private val useResponsesApi: Boolean by lazy { openAIConfig.useResponseApi }

    private val sseFactory by lazy {
        EventSources.createFactory(
            // v2.5.1 fix: Sin tiempo de espera total para la transmisión —— glm-5.3 Esperando el período de pensamiento del modelo de pensamiento profundo.(10min+)cero delta,
            // El callTimeout de 600 del cliente compartido acabará con toda la transmisión.(La segunda ronda de respuestas reales de los usuarios fue truncada.).
            // La suspensión silenciosa todavía la realiza readTimeout(300s) atrapar,La cancelación es manejada por abortSignal.
            httpClient.newBuilder()
                .callTimeout(ProviderHttpDefaults.STREAM_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build(),
        )
    }

    /**
     * Obtenga la identificación del modelo realmente enviada a la API.
     *
     * v1.0.2 Arreglar HTTP 400: eliminar el automático `substringAfterLast("/")` Elimina la lógica.
     *
     * Conclusiones prácticas compatibles.:Transmitir todo tal cual es model id,
     * No se realiza ninguna eliminación de prefijos.OpenRouter / Console GO / new-api Espere hasta que la estación de transferencia requiera explícitamente que se reserve. "provider/model"
     * prefijo de barra diagonal(por ejemplo `openai/gpt-4o`,`anthropic/claude-3`),El desmontaje automático evitará que se encuentre la estación de transferencia
     * modelo,Devuelve HTTP 400 invalid_request_error.
     *
     * v1.135 La extracción automática es"Reparación"estación de transferencia 400,De hecho, se convirtieron en las nuevas 400 causas fundamentales.
     *
     * estrategia actual:
     * - Transmisión transparente como está por defecto.
     * - Cuando el usuario necesita desnudarse,Configuración explícita [ProviderSpecificConfig.OpenAI.stripModelPrefix]
     *   (por ejemplo stripModelPrefix = "openai/",será despojado de "gpt-4o")
     * - v2.6.1: Pelado automático**Etiquetas de agrupación de corchetes iniciales**(Como por ejemplo `[ais]gemini-3.8-flash` en `[ais]`).
     *   Algunas estaciones de transferencia(one-api/new-api departamento)Utilizado en la lista de modelos. `[grupo]Nombre del modelo` Mostrar agrupación de canales,
     *   Pero la etiqueta de grupo no**No es un nombre de modelo enrutable**,Si lo envías tal cual, recibirás
     *   `HTTP 503 No available channel for model [ais]gemini-3.8-flash ... code=model_not_found`
     *   (Pruebas de usuarios reales). Los nombres de los modelos legales no contienen corchetes,Pelar con seguridad.
     */
    private fun effectiveModelId(modelId: String): String {
        val configuredPrefix = openAIConfig.stripModelPrefix.takeIf { it.isNotBlank() }
        val prefixStripped = if (configuredPrefix != null && modelId.startsWith(configuredPrefix)) {
            modelId.removePrefix(configuredPrefix)
        } else {
            modelId
        }
        // v2.6.1: Quitar las etiquetas del grupo principal en la lista de modelos de tránsito(Puede haber varios niveles,Como por ejemplo "[a][b]model").
        val base = stripChannelGroupLabelsImpl(prefixStripped)
        if (base != modelId) {
            Logger.d("OpenAIProvider", "Normalización del nombre del modelo: \"$modelId\" -> \"$base\"(Agrupación de tiras/Prefijo de configuración)")
        }
        // Reparación de auditoría (Comentarios de los usuarios): Solicitar normalización del nombre del modelo DeepSeek de capa(ver normalizeDeepSeekModelId)
        return normalizeDeepSeekModelId(base, baseUrl())
    }

    override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> {
        // v1.0.7: useResponseApi=true Es hora de ramificar la API de Respuestas(Reparación"engañar a los usuarios bug")
        if (useResponsesApi) return streamChatResponses(request)
        return streamChatCompletions(request)
    }

    /**
     * B-28: Desensibilización de URL de registro — Eliminar parámetro de consulta(Para algunas puertas de enlace ?key= Clave de paso,
     * URL completa en logcat/file log/DebugLogStore Puede ser recuperado por adb). Alineado con AnthropicProvider.
     */
    private fun sanitizeUrl(url: String): String {
        val qIdx = url.indexOf('?')
        return if (qIdx >= 0) url.substring(0, qIdx) else url
    }

    /**
     * v2.6.9: Sólo para la recopilación de pruebas —— Resumen de encabezados de respuesta.
     *
     * fondo:Codex Enlace esporádico `Invalid content-type: null`. Esta copia proviene de okhttp-sse
     * (RealEventSource.processResponse: Lanzado en respuesta a 2xx pero el tipo de contenido no es un flujo de eventos
     * IllegalStateException("Invalid content-type: " + body.contentType())).
     * `null` Indica que la respuesta no tiene ningún tipo de contenido. Originalmente, onFailure solo se leía después de cruzar la rama de reintento.
     * response?.code,Los encabezados de respuesta nunca se registran,No se puede localizar qué estación de transferencia es/Qué tipo de puerta de entrada le quitó la cabeza.
     *
     * Calibre de desensibilización:Solo generar encabezados de lista blanca(Metainformación de la capa de protocolo),Sin salida Authorization / Cookie /
     * cualquier encabezado de credencial personalizado;Content-Type Juega explícitamente cuando falta la cabeza. `null`,Fácil de distinguir"desaparecido"con"no event-stream".
     */
    private fun forensicResponseHead(response: Response): String = buildString {
        append("status=").append(response.code).append(' ').append(response.message)
        // Falta tipografía de encabezado null —— con"no event-stream"distinguir,Esta es la señal clave para esta investigación.
        append(", contentType=").append(response.header("Content-Type") ?: "null")
        append(", contentLength=").append(response.header("Content-Length") ?: "-")
        append(", contentEncoding=").append(response.header("Content-Encoding") ?: "-")
        append(", transferEncoding=").append(response.header("Transfer-Encoding") ?: "-")
        append(", server=").append(response.header("Server") ?: "-")
    }

    /**
     * v2.6.9: Sólo para la recopilación de pruebas —— Primer párrafo del cuerpo de la respuesta(Truncar [maxBytes] bytes).
     *
     * usar [Response.peekBody] en vez de [ProviderHttpSupport.readBodySafely]:
     * peekBody camina adentro `body.source().peek()`,**Solo espía, no consumas**,El cursor no se mueve después de leer.,
     * No afectará la creación posterior de flujos SSE.;readBodySafely voluntad `body.string()` Consume todo el flujo,
     * En la devolución de llamada de EventSource, también se generará una IllegalStateException porque el cuerpo ha sido eliminado.
     *
     * Siempre regresa en caso de fracaso null(No lanzar excepción):Los registros forenses en sí no deben afectar el flujo de control.
     * Devolver el contenido y pasar por otro proceso de desensibilización de credenciales.,Evite que la estación de tránsito repita la clave en el cuerpo del error.
     */
    private fun forensicBodyHead(response: Response, maxBytes: Long = 200L): String? = runCatching {
        val peeked = response.peekBody(maxBytes).string()
        if (peeked.isEmpty()) null else redactForLog(peeked)
    }.getOrNull()

    /**
     * v2.6.9: Responses Transmitir registro forense de fallas(Registro puro,No cambiar el flujo de control).
     *
     * Grabar en el primer punto de devolución de llamada:HTTP estado, original Content-Type(null Escriba explícitamente),
     * URL no sensible, tipo de excepción y mensaje,y**Solo espía, no consumas**Los primeros 200 bytes del cuerpo de la respuesta.
     *
     * Instrucciones de seguridad del cuerpo de respuesta:[Response.peekBody] camina adentro `body.source().peek()`,
     * Después de leer, el cursor permanece sin cambios y la transmisión no se cierra.,Por lo tanto, no afectará la creación posterior de flujos SSE.
     * No hay necesidad readBodySafely —— se sintoniza `body.string()` Consumirá toda la transmisión,y en EventSource
     * En la devolución de llamada, el cuerpo ha sido enmarcado. strip,La lectura también arroja una IllegalStateException.
     */
    private fun logResponsesFailureForensics(t: Throwable?, response: Response?) {
        runCatching {
            val head = response?.let { forensicResponseHead(it) } ?: "no-response(Fallo de la capa de red,Sin respuesta HTTP)"
            val bodyHead = response?.let { forensicBodyHead(it) }
            Logger.w(
                "OpenAIProvider",
                "[FORENSIC] streamChatResponses onFailure | url=${sanitizeUrl(baseUrl() + openAIConfig.responsesPath)} | " +
                    "$head | throwable=${t?.javaClass?.simpleName ?: "null"} | " +
                    "message=${t?.message?.let { redactForLog(it) } ?: "null"} | " +
                    "bodyHead200=${bodyHead ?: "<empty or unreadable>"}",
            )
        }
    }

    /** Desensibilización de registros:Borrar credenciales de portador/clase sk que puedan repetirse(Mismo calibre que ProviderError.redactSecrets). */
    private fun redactForLog(text: String): String {
        var value = text
        value = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""").replace(value, "Bearer [REDACTED]")
        value = Regex("""(?i)\b(?:sk|rk|pk|ak)-[A-Za-z0-9][A-Za-z0-9._-]{5,}""").replace(value, "[REDACTED]")
        value = Regex(
            """(?i)(api[\s_-]?key|token|password|secret|authorization|cookie)\s*[:=]?\s*["']?[A-Za-z0-9._~+/=-]{6,}""",
        ).replace(value) { "${it.groupValues[1]}=[REDACTED]" }
        return value.replace("\r", "\\r").replace("\n", "\\n")
    }

    /**
     * v1.0.7: Chat Completions API transmisión(Transmisión originalCuerpo del chat,Extraer en funciones independientes para facilitar la ramificación.).
     *
     * C-35: Cambiado de callbackFlow a channelFlow(El canal interno es siempre UNLIMITED,Los parámetros de capacidad no son obligatorios y no se pueden pasar).
     *   callbackFlow La capacidad del canal interno de se fija en 64,aguas abajo `.buffer(Channel.UNLIMITED)`
     *   Solo se aplica al siguiente nivel de reenvío de flujo. channel,**No cambia la capacidad interna de callbackFlow 64**;
     *   OkHttp Cuando el hilo de devolución de llamada interrumpe la entrega y la recopilación de la interfaz de usuario es lenta,El trySend interno fallará y descartará la pieza.
     *   Utilice channelFlow en su lugar para especificar explícitamente elementos internos ilimitados channel,El hilo de devolución de llamada trySend nunca falla debido a su capacidad total.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    // C-35: channelFlow No acepta parámetro de capacidad(Su canal interno es siempre UNLIMITED,exactamente la semántica requerida)
    private fun streamChatCompletions(request: ChatRequest): Flow<ChatStreamEvent> = channelFlow {
        val body = buildRequestBody(request)
        // M-OAI4: Utilice elementos de configuración en su lugar chatCompletionsPath(Admite rutas de transferencia personalizadas como Azure)
        val url = baseUrl() + chatCompletionsPath()
        // B-28: El registro sólo registra scheme+host+path,no lo recuerdo query
        Logger.i("OpenAIProvider", "streamChat: POST ${sanitizeUrl(url)} model=${request.model} msgs=${request.messages.size}")

        // v1.0.1: httpRequest Cambiar a var,429 Reconstruir después de cambiar de clave
        fun buildHttpRequest(): Request = Request.Builder()
            .url(url)
            // v1.0.18: ir modelo libre fallback(El usuario no completó la clave + modelo de lista blanca de SiliconFlow → Usar incorporado key)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { requestHeadersFor(request.sessionId).forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var httpRequest = buildHttpRequest()

        // Capturar ProducerScope,Para que el oyente anónimo inicie la rutina de reconexión
        val scope = this@channelFlow

        // v1.135 Diagnóstico de rendimiento:Registre el tiempo empleado en cada etapa.,Ayude a localizar fuentes de latencia para plataformas de tránsito como OpenCode
        val requestStartAt = System.currentTimeMillis()
        var firstByteAt = 0L
        var firstDeltaAt = 0L

        // M-OAI3: Parámetros de reconexión de retroceso exponencial finito
        val retryCount = AtomicInteger(0)
        // M-OAI3: No vuelva a intentarlo si se ha emitido el incremento tool_call(evitar la duplicación)
        val toolCallDeltaSent = AtomicBoolean(false)
        // H-OAI1: cualquiera ContentDelta/ReasoningDelta/ToolCallDelta problema postfijo true,
        //   Comprobar antes de volver a intentarlo !anyDeltaSent.get(),Evite que el contenido filtrado se vuelva a publicar(Solo cheque original toolCallDeltaSent
        //   No es suficiente para cubrir texto plano/Riesgo de repetición en escenarios de razonamiento puro).
        val anyDeltaSent = AtomicBoolean(false)
        // M-OAI4: tool_call index Ten todo en cuenta,Asigne incrementos locales por índice API usando un mapa acumulativo index
        val toolCallIndexMap = mutableMapOf<Int, Int>()
        var nextToolCallIndex = 0
        // v1.0.20: stream-guard — Acumule el nombre/argumentos/banderas enviadas de tool_call,
        //   Se utiliza para interceptar llamadas de herramientas no válidas con nombres vacíos y volver a ellas al finalizar ContentDelta
        val toolCallAccMap = mutableMapOf<Int, ToolCallAccState>()
        // v2.x: "Nombrado pero los parámetros aún están vacíos"llamada pendiente localIndex —— Para que se devuelva la continuación del parámetro sin nombre (consulte el análisis de llamadas a herramientas a continuación)
        var pendingArgsLocalIndex: Int? = null
        // v2.x: La estación de transferencia reescribirá los fragmentos de parámetros de la misma llamada lógica en nuevos índices consecutivos.
        // Registra la llamada con nombre más reciente que aún no se ha cerrado, como una secuencia de continuaciones sin nombre/sin nombre.
        var activeContinuationLocalIndex: Int? = null
        // v1.0.21: Evitar que emitDoneWithStreamGuard se ejecute dos veces(finishReason + [DONE] Disparar una vez cada uno),
        //   Hace que el texto recuperado mediante la llamada a la herramienta de nombre vacío se envíe dos veces,Generar contenido duplicado.
        val streamGuardDone = AtomicBoolean(false)
        // v1.0.23: Detección de citometría de final de flujo prematura — SenseTime deepseek-v4-flash y otras implementaciones de transmisión API están bug,
        //   first delta inmediatamente después finish(Solo genera de 1 a 4 caracteres),Pero la no transmisión puede devolver el contenido completo normalmente.
        //   Registre el número total de caracteres en ContentDelta,Comprobar cuando haya terminado,Si son muy pocos y lleva muy poco tiempo, volverá a completar Texto.
        val contentCharsSent = AtomicInteger(0)
        val emptyNameToolCallCount = AtomicInteger(0)
        val firstDeltaTimestamp = AtomicLong(0L)
        // v1.0.48: reasoning recuento de caracteres — Cuando el streaming solo envía razonamientos pero no contenidos, se acaba
        //   (Comportamiento conocido de SenseTime deepseek-v4-flash),No se debe activar el respaldo sin transmisión,De lo contrario conducirá a:
        //   1) Las solicitudes de reversión también son fáciles Connection reset,Se necesitaron más de 20 años para tener una conversación."Atascado y no terminará"
        //   2) generation finished Después de rebobinar, todavía se ejecuta en segundo plano.,Solicitud huérfana generada
        //   Hay un razonamiento que indica que el modelo sí responde.,Simplemente finalice el flujo directamente y permita que el usuario vea el proceso de pensamiento.
        val reasoningCharsSent = AtomicInteger(0)
        // issue #5(Opción C): Recuento de reintentos por malformaciones del protocolo (afirma llamar a la herramienta pero no a la llamada a la herramienta).
        //   Cuando se encuentra por primera vez un razonamiento sin vómitos, se activa un reintento; Si el problema persiste después del reintento, el sistema devolverá la garantía de nunca vacío.
        val malformedToolCallRetries = AtomicInteger(0)
        // v1.0.24: Bandera de corrutina alternativa en progreso — Evitar la segunda llamada emitDoneWithStreamGuard
        //   (finishReason + [DONE] Disparar una vez cada uno)Avanzar antes de que se complete la rutina de respaldo close flow,
        //   El resultado de completeText no se puede enviar a través del canal cerrado.
        val pendingFallback = AtomicBoolean(false)
        // El juicio de reversión debe distinguir entre"La transmisión también puede continuar generando eventos."y"SSE ha terminado".
        // Algunas puertas de enlace finish_reason=tool_calls inmediatamente después onClosed;En este momento, el existente
        // contentChars Como"Transmisión reanudada",De lo contrario, la rutina alternativa regresa antes,Flow Hecho nunca se recibe.
        val streamSourceClosed = AtomicBoolean(false)
        val consumerClosed = AtomicBoolean(false)

        /**
         * v1.0.20: stream-guard — Cheque acumulado antes del evento Listo toolCallAccMap,
         *   Devierte una llamada de herramienta no válida con nombre vacío pero argumentos para ContentDelta(texto visible).
         *
         * darse cuenta `recoverInvalidToolCallText` Semántica:
         *  - La llamada de herramienta con nombre vacío no se puede ejecutar(No puedo encontrar la herramienta correspondiente),habría sido descartado silenciosamente
         *  - Restaurar argumentos acumulados como texto,Permitir a los usuarios ver lo que realmente produce el modelo.
         *  - La interceptación ocurre en el evento Listo en lugar de cada delta,Porque el nombre puede llegar más tarde en la transmisión.
         */
        fun emitDoneWithStreamGuard(finishReason: String?) {
            // v1.0.24: Registro de diagnóstico — Confirme si se llama a emitDoneWithStreamGuard y los valores de cada variable
            // v1.0.21: Prevenir la doble ejecución — finishReason y [DONE] Cuando se activa una vez cada,
            //   Enviar Hecho directamente por segunda vez y close,Saltar texto recuperado,Evite el contenido duplicado.
            // v1.0.24: Si la rutina de reversión está en progreso,La segunda llamada es directamente return,No close,
            //   Espere a que se complete la rutina de reversión y envíela automáticamente. Done + close
            // v1.0.53: segunda llamada(finishReason=null)Es un final normal de doble gatillo.,no imprimir diagnose
            //   (Evite información engañosa en los registros finishReason=null + aborted=true combinación).
            if (!streamGuardDone.compareAndSet(false, true)) {
                if (pendingFallback.get()) {
                    Logger.d("OpenAIProvider", "stream-guard: Reversión en progreso, Saltar evento hecho")
                    return
                }
                // v1.0.53: Se ha enviado el primer emitDone Done+close,Aquí solo asegúrate de que la transmisión esté cerrada.,No reenviar Listo.
                //   finishReason y [DONE] Cuando se activa doblemente,La segunda vez Hecho traerá null finishReason,
                //   redundante Done(null) Puede hacer que la capa superior calcule mal el final anormal del flujo y provoque una interrupción.
                close()
                return
            }
            // v1.0.47: diagnose Mover a la primera rama de ejecución(La segunda llamada ya no imprime.,Evite registros engañosos)
            Logger.d(
                "OpenAIProvider",
                "stream-guard diagnose: emitDone called | finishReason=$finishReason | " +
                    "contentChars=${contentCharsSent.get()} | reasoningChars=${reasoningCharsSent.get()} | anyDeltaSent=${anyDeltaSent.get()} | " +
                    "aborted=${request.abortSignal.aborted} | useResponsesApi=$useResponsesApi | " +
                    "firstDeltaTs=${firstDeltaTimestamp.get()}",
            )
            // v1.0.52: Evaluar las condiciones de respaldo antes de restaurar la lógica —
            //   si finishReason=tool_calls y hay vacio name tool_call,Descripción del modelo en modo streaming.
            //   El nombre de la herramienta no se muestra correctamente,Necesidad de activar la reversión sin transmisión(En modo sin transmisión, tool_calls generalmente regresa completamente).
            //   En este momento, los argumentos no se pueden restaurar en ContentDelta(Ese es el parámetro de la herramienta.,No es el texto principal.),De lo contrario, los usuarios ven alucinaciones JSON.
            val hasEmptyNameToolCall = toolCallAccMap.values.any { !it.recoveredAsContent && it.name.isNullOrBlank() && it.args.isNotEmpty() }

            // v1.0.23: SenseTime deepseek-v4-flash y otra detección de errores de finalización prematura de transmisión
            //   La implementación de transmisión de API de SenseTime tiene un error: inmediatamente después del primer delta finish(Solo genera de 0 a 4 caracteres,
            //   Incluso solo el contenido de razonamiento se envía pero no se envía content),Sin embargo, la no transmisión puede devolver el contenido completo normalmente.(text=50-200 chars).
            //   v1.0.24: relajarse para 0-9 chars,Cubre la situación en la que sólo se envía razonamiento pero no contenido.
            //   Condiciones de detección:ContentDelta Total de caracteres 0-9 y recibidos anyDeltaSent(first delta Llegó),
            //   Y dentro de los 500 ms posteriores al primer delta finish,No usuario abort,API sin respuestas.
            //   v1.0.48: reasoning También debe incluirse en el juicio total de carácter. — SenseTime finalizará después de enviar solo de 1 a 4 caracteres de razonamiento.,
            //     Esta no es una respuesta completa.(La no transmisión puede regresar 253+ chars reasoning).
            //     contentChars + reasoningChars total < 10 Eso es todo"final prematuro",Activar respaldo sin transmisión.
            //   v1.0.51: Nuevo"Sólo el razonamiento no content"condición de reserva — SenseTime puede emitir una gran cantidad de reasoning
            //     Pero el contenido es siempre 0,combined > 9 omitir respaldo,El usuario ve una respuesta vacía.
            //     En este punto también se debería activar el respaldo sin transmisión(Los que no son de transmisión pueden devolver texto o utilizar el razonamiento para averiguarlo.).
            val totalChars = contentCharsSent.get()
            val reasoningChars = reasoningCharsSent.get()
            val combinedChars = totalChars + reasoningChars
            val firstDeltaTime = firstDeltaTimestamp.get()
            val deltaDuration = if (firstDeltaTime > 0) System.currentTimeMillis() - firstDeltaTime else Long.MAX_VALUE
            val shouldFallback = anyDeltaSent.get() &&
                !request.abortSignal.aborted &&
                !useResponsesApi &&
                (
                    // Condición A: Muy poco contenido(combined < 10),y corta duración(< 500ms) — lógica original
                    (combinedChars in 0..9 && deltaDuration < 500) ||
                        // Condiciones B(v1.0.51): content es 0 pero hay reasoning,y corta duración(< 2s) —
                        //   El modelo solo genera pensamientos pero no texto.,El usuario ve una respuesta vacía,Necesito retroceder
                        (totalChars == 0 && reasoningChars > 0 && deltaDuration < 2000) ||
                        // Condiciones C(v1.0.52): finishReason=tool_calls pero hay vacío name tool_call —
                        //   El nombre de la herramienta no se muestra correctamente en el modo de transmisión del modelo.(Como por ejemplo GLM-4-9B-0414),
                        //   args Almacenado pero no disponible como cuerpo(es el parámetro de la herramienta),Activar una reversión sin transmisión para regenerar el modelo
                        (finishReason == "tool_calls" && hasEmptyNameToolCall)
                    )

            // v1.0.52: Solo restaure el nombre vacío tool_call si no hay ningún respaldo ContentDelta
            //   No hay recuperación al retroceder(Evite enviar parámetros de herramientas como texto a los usuarios,Luego regresa y envía nuevamente.)
            if (!shouldFallback) {
                toolCallAccMap.forEach { (localIndex, acc) ->
                    // v1.0.22: Saltar restaurado incrementalmente acc,Evite el envío duplicado
                    if (acc.recoveredAsContent) return@forEach
                    if (acc.name.isNullOrBlank() && acc.args.isNotEmpty()) {
                        val recoveredText = acc.args.current()
                        Logger.w(
                            "OpenAIProvider",
                            "stream-guard: interceptar vacío name tool call (localIndex=$localIndex, args=${acc.args.length} chars)," +
                                "volver al texto: ${recoveredText.take(50)}",
                        )
                        contentCharsSent.addAndGet(recoveredText.length)
                        trySend(ChatStreamEvent.ContentDelta(recoveredText))
                    }
                }
            }
            // Borrar map,Evite que se restablezcan nuevamente los desencadenantes falsos posteriores
            toolCallAccMap.clear()

            if (shouldFallback) {
                pendingFallback.set(true)
                Logger.w(
                    "OpenAIProvider",
                    "stream-guard: Detectado fin prematuro de la transmisión (chars=$totalChars, reasoning=$reasoningChars, duration=${deltaDuration}ms), Respaldo automático a solicitudes que no son de transmisión | url=${sanitizeUrl(
                        url,
                    )}",
                )
                var fallbackFailure: Throwable? = null
                scope.launch {
                    try {
                        // v1.0.48: Acortar el retiro a 500ms+jitter — Los 1,5 segundos originales causaron un retraso obvio después del final.,
                        //   429 Ya existe un mecanismo de reintento,No es necesario retroceder demasiado
                        val backoffMs = 500L + Random.nextLong(0, 300)
                        Logger.d("OpenAIProvider", "stream-guard: Espere antes de revertir ${backoffMs}ms(rpm retirarse)")
                        delay(backoffMs)
                        if (request.abortSignal.aborted) {
                            Logger.d("OpenAIProvider", "stream-guard: El usuario cancela mientras espera,Renunciar y retroceder")
                            pendingFallback.set(false)
                            trySend(ChatStreamEvent.Done(finishReason))
                            close()
                            return@launch
                        }
                        // v1.0.49: Segunda verificación — Si la transmisión ha vuelto a la normalidad durante el período de espera(recibir más content/reasoning),
                        //   Explica el estilo Shangtang"final prematuro"es una falsa alarma(El delta posterior llega tarde),Ríndete y retrocede.
                        //   No enviar Hecho No close — Dejar que SSE siga recibiendo seguimiento delta,hasta lo real finishReason
                        //   O onClosed termina normalmente. Restablezca streamGuardDone para permitir que se vuelva a activar el emitDone posterior
                        //   (En este punto, contentChars tiene > 9, shouldFallback=false, directo Done+close).
                        // v1.0.51: Hay que tener en cuenta que la línea de base cuando se activa el retroceso relativo en realidad aumenta."recuperación de flujo".
                        //   Sólo comprobar content > 0 Juzgará mal el primer delta que se ha recibido como contenido nuevo;
                        //   Si la conexión realmente ya está onClosed,Esto hará que la rutina de reversión regrese directamente y suspenda permanentemente el flujo superior.
                        val currentContent = contentCharsSent.get()
                        val currentReasoning = reasoningCharsSent.get()
                        // Sólo la adición de texto visible cuenta como recuperación de transmisión. Si solo agregas reasoning,Algunos modelos aún pueden
                        // Desconéctese directamente después de que finalice tool_calls,Si continúa esperando en este momento, la llamada a la herramienta se perderá nuevamente.
                        val streamProducedMore = currentContent > totalChars
                        if (!streamSourceClosed.get() && streamProducedMore) {
                            Logger.i(
                                "OpenAIProvider",
                                "stream-guard: La transmisión se reanudó mientras se esperaba(content=$currentContent, reasoning=$currentReasoning)," +
                                    "Renunciar y retroceder,Continuar recibiendo streaming",
                            )
                            pendingFallback.set(false)
                            streamGuardDone.set(false)
                            return@launch
                        }
                        if (streamSourceClosed.get()) {
                            Logger.d("OpenAIProvider", "stream-guard: SSE Cerrado,Continuar con el respaldo sin transmisión")
                        }
                        // v1.0.48: Retrocede y vuelve a intentarlo — API SenseTime ocasionalmente Connection reset,Vuelva a intentarlo 1 vez para aumentar la tasa de éxito
                        var completion: ChatCompletion? = null
                        var lastError: Exception? = null
                        for (attempt in 1..2) {
                            if (request.abortSignal.aborted) break
                            try {
                                completion = completeText(request)
                                break
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                lastError = e
                                Logger.w("OpenAIProvider", "stream-guard: Respaldo sin transmisión $attempt falló: ${e.message}")
                                if (attempt < 2 && !request.abortSignal.aborted) {
                                    // v1.0.53: 429 Espere a que la ventana de RPM esté completa al acelerar(10s)Intentar otra vez,Evite alcanzar el límite actual continuamente
                                    val isRateLimited = e.message?.contains("429") == true ||
                                        e.message?.contains("rate limited") == true
                                    delay(if (isRateLimited) 10_000L else 1_000L)
                                }
                            }
                        }
                        if (completion != null) {
                            val text = completion.text.orEmpty()
                            val reasoning = completion.reasoningContent.orEmpty()
                            val toolCalls = ToolCallSanitizer.sanitize(completion.toolCalls.orEmpty())
                            if (toolCalls.isNotEmpty()) {
                                // v1.0.53: La reversión es exitosa y la decisión del modelo llama a la herramienta. — enviar ToolCallDelta,
                                // No puedes usar el razonamiento como texto.(ese es el proceso de pensamiento);Las llamadas a herramientas se entregan a la capa superior para su ejecución.
                                // Anteriormente, las llamadas a herramientas se descartaban silenciosamente.,Hace que se pierda el canal_contraseña/respuesta del chat grupal,
                                // Llamada de herramienta de chat única(web_search espera)perdido,Los usuarios solo ven el texto del pensamiento.
                                // v1.x: reasoning Todavía quiero enviar UI,De lo contrario, los usuarios sólo ven la primera palabra y no tienen ningún proceso de pensamiento.
                                if (reasoning.isNotEmpty()) {
                                    trySend(ChatStreamEvent.ReasoningDelta(reasoning))
                                }
                                toolCalls.forEachIndexed { idx, tc ->
                                    // Clave: La ruta alternativa envía**Parámetros completos**y el agregador agrega de forma incremental de forma predeterminada.
                                    // La fase de transmisión ha acumulado argumentos para el mismo índice una vez. si no hay
                                    // isSnapshot, los dos párrafos se juntarán (en el registro len=230 → argsLen=473,
                                    // casi exactamente dos veces), el JSON detallado ya no es válido → Analizar campos vacíos →
                                    // channel_reply Informe "content No puede estar vacío"→ Una ronda de silencio.
                                    // El primer delta también lleva el nombre/id y marca la instantánea para que el agregador la borre y reescriba.
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = idx,
                                            id = tc.id,
                                            name = tc.name,
                                            argumentsDelta = tc.arguments,
                                            isSnapshot = true,
                                        ),
                                    )
                                }
                            } else {
                                // v1.0.51: reasoning Ten todo en cuenta — deepseek-v4-flash El modelo de inferencia puede generar todos
                                //   Ponlo en razonamiento_content(text esta vacio),En este momento, utilice el razonamiento como cuerpo para enviar,
                                //   De lo contrario, el usuario verá una respuesta vacía. Es coherente con la lógica básica de MemoryLlmClient.
                                //
                                // issue #5 + Semántica del producto (opción C):
                                //   Herramienta de ajuste de reclamaciones de modelos (finish_reason=tool_calls) pero no devolvió una llamada a herramienta válida,
                                //   Es una malformación del protocolo. El comportamiento anterior utilizaba directamente el razonamiento como texto y el usuario veía "pensamiento filtrado + herramienta no ejecutada".
                                //   Si informa un error directamente, recibirá una respuesta en blanco (entra en conflicto con "vomitar está bien, pero vaciarlo no").
                                //   Entonces son dos pasos:
                                //     ① encuentro por primera vez → No vomites razonamientos, marca la necesidad de volver a intentarlo y deja que la capa superior lo vuelva a enviar.
                                //     ② Reintentar, sigue siendo anormal → De vuelta en el bolsillo, garantizado never-empty
                                val claimedToolCall = finishReason == "tool_calls" || finishReason == "function_call"
                                // v2.6.10: Cuando el texto principal ha sido escrito, ya no es“Afirmó que la herramienta de ajuste no se llamaba”Intentar otra vez.
                                // Volver a intentarlo descartará este párrafo, tomará otra ronda de tiempo del modelo y existe una alta probabilidad de que se vuelva a enviar el mismo modelo.
                                // Todavía deformado. Entregar el texto cuando está disponible es más útil que permitir que el usuario vea un mensaje de error.
                                val hasUsableText = text.isNotBlank()
                                val shouldRetryForMalformedToolCall = claimedToolCall &&
                                    !hasUsableText &&
                                    malformedToolCallRetries.get() < MAX_MALFORMED_TOOLCALL_RETRIES
                                if (shouldRetryForMalformedToolCall) {
                                    malformedToolCallRetries.incrementAndGet()
                                    Logger.w(
                                        "OpenAIProvider",
                                        "stream-guard: Malformación del protocolo — finish_reason=$finishReason pero no analizado toolCall" +
                                            " (text=${text.length}, reasoning=${reasoning.length})," +
                                            "No. ${malformedToolCallRetries.get()}/$MAX_MALFORMED_TOOLCALL_RETRIES veces, sin escupir razonamientos, provocando un nuevo intento",
                                    )
                                    if (reasoning.isNotEmpty()) {
                                        trySend(ChatStreamEvent.ReasoningDelta(reasoning))
                                    }
                                    fallbackFailure = IllegalStateException(
                                        "El modelo afirma llamar a la herramienta pero no devuelve una respuesta válida tool_call(finish_reason=$finishReason)",
                                    )
                                } else if (text.isNotBlank()) {
                                    trySend(ChatStreamEvent.ContentDelta(text))
                                    if (reasoning.isNotEmpty()) {
                                        trySend(ChatStreamEvent.ReasoningDelta(reasoning))
                                    }
                                } else if (reasoning.isNotEmpty()) {
                                    // Verdadero·reasoning-only, o intente nuevamente para obtener el resultado final que todavía parece normal:
                                    // Partiendo de la premisa de nunca estar vacío, prefiero dar un período de reflexión que dejarlo en blanco.
                                    if (claimedToolCall) {
                                        Logger.w(
                                            "OpenAIProvider",
                                            "stream-guard: Después de volver a intentarlo, el protocolo sigue siendo anormal y vuelve al razonamiento.(Evite respuestas en blanco)",
                                        )
                                    }
                                    trySend(ChatStreamEvent.ContentDelta(reasoning))
                                }
                            }
                            Logger.i(
                                "OpenAIProvider",
                                "stream-guard: La alternativa sin transmisión fue exitosa (text=${text.length} chars, reasoning=${reasoning.length} chars, toolCalls=${toolCalls.size})",
                            )
                        } else {
                            Logger.w("OpenAIProvider", "stream-guard: La alternativa sin transmisión finalmente falla: ${lastError?.message}")
                            fallbackFailure = lastError ?: IllegalStateException("La alternativa sin transmisión no arroja resultados")
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w("OpenAIProvider", "stream-guard: Excepción de reserva sin transmisión: ${e.message}")
                        fallbackFailure = e
                    }
                    pendingFallback.set(false)
                    when {
                        request.abortSignal.aborted -> {
                            scope.trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar", fallbackFailure))
                        }
                        fallbackFailure != null -> {
                            val failure = checkNotNull(fallbackFailure)
                            trySend(
                                ChatStreamEvent.Error(
                                    "Falló el respaldo sin transmisión: ${failure.message}",
                                    failure,
                                ),
                            )
                        }
                        else -> trySend(ChatStreamEvent.Done(finishReason))
                    }
                    close()
                }
                return
            }

            // Finalizando: StreamInterrupted se envía cuando el usuario se detiene (deje que FirstEventWatchdog sienta claramente la interrupción,
            // Ya no espere un tiempo de espera de 15 segundos para activar la reversión sin transmisión); enviar Hecho solo después de la finalización normal.
            if (request.abortSignal.aborted) {
                scope.trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar"))
            } else {
                trySend(ChatStreamEvent.Done(finishReason))
            }
            close()
        }

        val currentEventSource = AtomicReference<EventSource?>(null)
        val connectionGeneration = AtomicInteger(0)
        fun cancelCurrentEventSource() {
            connectionGeneration.incrementAndGet()
            currentEventSource.getAndSet(null)?.cancel()
        }
        fun installEventSource(eventSource: EventSource) {
            // newEventSource() starts its OkHttp Call before returning. If abort/collector
            // cancellation races that return, publish the source atomically and compensate by
            // cancelling it after publication when the request is already no longer active.
            val previous = currentEventSource.getAndSet(eventSource)
            if (previous != null && previous !== eventSource) previous.cancel()
            if (request.abortSignal.aborted || consumerClosed.get() || scope.isClosedForSend) {
                currentEventSource.compareAndSet(eventSource, null)
                eventSource.cancel()
            }
        }
        val abortListener = request.abortSignal.addAbortListener {
            // Cancel the active SSE connection immediately; all callbacks observe aborted
            // and therefore must not schedule fallback/reconnect work.
            cancelCurrentEventSource()
        }

        fun connect() {
            if (request.abortSignal.aborted || scope.isClosedForSend) {
                // A signal can already be aborted before collection starts. Close the producer
                // instead of entering awaitClose forever with no EventSource to trigger a callback.
                if (request.abortSignal.aborted) close()
                return
            }
            val myGeneration = connectionGeneration.incrementAndGet()
            streamSourceClosed.set(false)
            val eventSource = sseFactory.newEventSource(
                httpRequest,
                object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        if (myGeneration != connectionGeneration.get()) return
                        firstByteAt = System.currentTimeMillis()
                        Logger.i("OpenAIProvider", "streamChat TTFB: ${firstByteAt - requestStartAt}ms | url=${sanitizeUrl(url)}")
                        if (!response.isSuccessful) {
                            val code = response.code
                            // v1.0.1: 429 Intente cambiar de clave y vuelva a intentarlo durante la limitación actual(Múltiples escenarios clave)
                            if (code == 429 && !anyDeltaSent.get() && retryCount.get() < MAX_RETRIES &&
                                !request.abortSignal.aborted && switchToNextKey()
                            ) {
                                Logger.i("OpenAIProvider", "streamChat onOpen 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ahora")
                                httpRequest = buildHttpRequest()
                                retryCount.incrementAndGet()
                                connectionGeneration.incrementAndGet()
                                eventSource.cancel()
                                scope.launch {
                                    if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                        connect()
                                    }
                                }
                                return
                            }
                            // L-OAI1: Utilice readBodySafely en su lugar runCatching
                            val errText = ProviderHttpSupport.readBodySafely(response)
                            val msg = parseErrorMessage(code, errText)
                            Logger.w("OpenAIProvider", "streamChat onOpen HTTP $code: $msg")
                            // v1.0.28: HTTP 400 Registre el cuerpo de la solicitud y el cuerpo completo de la respuesta.,Ayuda a diagnosticar errores de parámetros de la estación de transferencia
                            // Reparación de auditoría (7.2): Cuerpo de respuesta truncado a 500 caracteres — Se repetirán algunas respuestas de error de la estación de transferencia
                            // Solicitar contenido(Contiene diálogo/parámetros),El registro completo puede escribir las conversaciones de los usuarios en el archivo de registro.
                            if (code == 400) {
                                Logger.w("OpenAIProvider", "streamChat 400 Resumen de la solicitud: ${describeRequestBody(body)}")
                                Logger.w(
                                    "OpenAIProvider",
                                    "streamChat 400 Resumen de respuesta: ${ProviderError.safeErrorDetail(errText) ?: "<empty>"}",
                                )
                            }
                            // v1.0.1: 401/403 Marcar la clave actual como fallida cuando falla la autenticación(Múltiples escenarios clave)
                            if (code == 401 || code == 403) {
                                markKeyFailed(hardBlock = true)
                            }
                            // L-OAI17: Evento de error al llevar throwable,Es conveniente que la capa superior distinga los tipos de error en consecuencia.
                            trySend(
                                ChatStreamEvent.Error(
                                    msg,
                                    OpenAIHttpException(
                                        code,
                                        msg,
                                        ProviderError.parseRetryAfter(response.header("Retry-After")),
                                    ),
                                ),
                            )
                            close()
                            return
                        }
                    }

                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (myGeneration != connectionGeneration.get()) return
                        if (data == "[DONE]") {
                            // v1.0.47: Cuando la reversión está en curso, el seguimiento se descarta directamente. [DONE] evento,No entrar emitDoneWithStreamGuard,
                            //   Reducir los registros redundantes desde la fuente(El SenseTime original enviará de 8 a 10 eventos vacíos seguidos para activar la actualización del registro de diagnóstico.)
                            if (pendingFallback.get()) return
                            // v1.0.20: stream-guard — Done Verificar acumulación en evento toolCallAccMap,
                            //   Una llamada de herramienta con un nombre vacío vuelve a ContentDelta
                            emitDoneWithStreamGuard(null)
                            return
                        }
                        // B3-05: Custom Ruta de respuesta de transmisión de proveedores(Como por ejemplo $.choices[0].delta.content)
                        val customStreamPath = customConfig?.streamResponsePath?.takeIf { it.isNotBlank() }
                        if (customStreamPath != null) {
                            var parseError: Throwable? = null
                            val element = resultOf { AppJson.parseToJsonElement(data) }
                                .onError { _, throwable -> parseError = throwable }
                                .getOrNull()
                            if (element == null) {
                                val error = malformedStreamFrameError("OpenAI", data, parseError)
                                Logger.w("OpenAIProvider", error.message, parseError)
                                trySend(error)
                                close()
                                return
                            }
                            val text = extractTextFromElement(
                                ProviderTemplateEngine.extractByPath(element, customStreamPath),
                            )
                            if (!text.isNullOrEmpty()) {
                                if (firstDeltaAt == 0L) {
                                    firstDeltaAt = System.currentTimeMillis()
                                    firstDeltaTimestamp.set(firstDeltaAt)
                                }
                                anyDeltaSent.set(true)
                                contentCharsSent.addAndGet(text.length)
                                trySend(ChatStreamEvent.ContentDelta(text))
                            }
                            val standardChunk = resultOf { AppJson.decodeFromString<OpenAIStreamChunk>(data) }
                                .getOrNull()
                            val finish = standardChunk?.choices?.firstOrNull()?.finishReason
                            if (!finish.isNullOrBlank() && !pendingFallback.get()) {
                                emitDoneWithStreamGuard(finish)
                            }
                            return
                        }
                        // M-OAI3: Usar en su lugar resultOf(revenderá CancellationException),sustituto runCatching(tragará CancellationException)
                        var parseError: Throwable? = null
                        val chunk = resultOf {
                            AppJson.decodeFromString<OpenAIStreamChunk>(data)
                        }.onError { _, throwable -> parseError = throwable }.getOrNull()
                        if (chunk == null) {
                            val error = malformedStreamFrameError("OpenAI", data, parseError)
                            Logger.w("OpenAIProvider", error.message, parseError)
                            trySend(error)
                            close()
                            return
                        }

                        // A5: stream_options.include_usage El último trozo sólo toma usage(choices es una matriz vacía),
                        // Debe analizarse antes de que las opciones salgan anticipadamente.,De lo contrario, el fragmento de uso se descarta silenciosamente.
                        chunk.usage?.let { usage ->
                            trySend(ChatStreamEvent.UsageDelta(usage.toUsageTokens()))
                        }

                        val choice = chunk.choices.firstOrNull() ?: return
                        val delta = choice.delta
                        if (delta != null) {
                            if (firstDeltaAt == 0L) {
                                firstDeltaAt = System.currentTimeMillis()
                                firstDeltaTimestamp.set(firstDeltaAt)
                                Logger.i(
                                    "OpenAIProvider",
                                    "streamChat first delta: ${firstDeltaAt - requestStartAt}ms " +
                                        "(TTFB=${firstByteAt - requestStartAt}ms) | url=${sanitizeUrl(url)}",
                                )
                            }
                            // v1.0.49: Se seguirá enviando normalmente durante el período de espera de reversión. delta — Shangtang"Las vacaciones terminan prematuramente"El siguiente delta llegará más tarde,
                            //   Si lo descartas, entonces ríndete. Al retroceder, el contenido se ha perdido y el flujo se ha interrumpido. close,Los usuarios solo ven el primer carácter.
                            //   Cambiar para enviar normalmente:El contenido no se pierde al rendirse y volver atrás;Solo comienza cuando la reversión es exitosa <10 Los personajes pueden repetirse,Aceptable.
                            delta.reasoningContent?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    anyDeltaSent.set(true)
                                    reasoningCharsSent.addAndGet(it.length)
                                    trySend(ChatStreamEvent.ReasoningDelta(it))
                                }
                            delta.content?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    anyDeltaSent.set(true)
                                    contentCharsSent.addAndGet(it.length)
                                    trySend(ChatStreamEvent.ContentDelta(it))
                                }
                            // Phase 7: Analizar el incremento de llamadas a herramientas(Cada índice corresponde a una llamada de herramienta.,arguments Acumulación de fragmentos)
                            delta.toolCalls?.forEach { tc ->
                                // M-OAI4: Utilice el mapa acumulativo para asignar por índice,Evite el 0 predeterminado para fusionar varias llamadas.
                                // Nueva llamada de herramienta(La primera pieza lleva identificación o name)Activar una nueva asignación de índice.
                                val apiIndex = tc.index
                                val assignment = assignToolCallLocalIndex(
                                    apiIndex = apiIndex,
                                    id = tc.id,
                                    name = tc.function?.name,
                                    indexMap = toolCallIndexMap,
                                    nextLocalIndex = nextToolCallIndex,
                                )
                                nextToolCallIndex = assignment.nextLocalIndex
                                // L-OAI13: toolCallIndexMap cuando falta(La primera película está perdida./fuera de servicio)No hay respaldo para 0,
                                //   De lo contrario, la película se fusionará por error index=0 llamada de herramienta.
                                var localIndex = assignment.localIndex
                                if (localIndex == null) {
                                    // v2.x Reparación: La secuela ha vuelto a su lugar.
                                    //   Medición real(Shangtang/transmisión de estación de transferencia)aparecerá"Una llamada lógica se divide en varias partes":
                                    //   La primera película tiene nombre pero los argumentos están vacíos, y las películas posteriores son puras continuaciones de parámetros.(newApiIndex,
                                    //   Sin identificación ni name)Nuevo índice asignado → Todos los parámetros caen en la llamada sin nombre,
                                    //   Done La llamada sin nombre se restablece al texto y**La longitud del parámetro de llamada con nombre real es 0**,
                                    //   Informes directos para la verificación posterior"Falta el parámetro requerido: query", el usuario ve el parámetro JSON mezclado en el texto.
                                    //   Aquí volvemos a fusionar las películas de parámetros puros en"El único parámetro actualmente por completar"llamada nombrada.
                                    //   Dar prioridad al uso"La convocatoria nombrada más reciente que aún no se ha cerrado";Si te llamaron recientemente
                                    //   no disponible, luego recurra al único candidato no emitido. Esto sobrescribirá los parámetros ya
                                    //   Las secuelas estrenadas después de la primera película también conservarán la primera película./Juicio conservador cuando los parámetros están fuera de orden.
                                    val pending = continuationTarget(
                                        accMap = toolCallAccMap,
                                        activeLocalIndex = activeContinuationLocalIndex,
                                        incomingName = tc.function?.name,
                                    )
                                    if (pending != null) {
                                        val target = toolCallAccMap[pending]
                                        if (target != null) {
                                            localIndex = pending
                                            toolCallIndexMap[apiIndex] = pending
                                            Logger.d(
                                                "OpenAIProvider",
                                                "tool_calls El regreso de la secuela: apiIndex=$apiIndex → localIndex=$pending" +
                                                    " (acumular args=${target.args.length} chars)",
                                            )
                                        }
                                    }
                                }
                                if (localIndex == null) {
                                    // v2.x(Problema n.º 5 de comentarios de los usuarios): Graba el metraje original cuando se perdió la primera película. ——
                                    // DeepSeek Esperando informe del modelo"reasoning fuga/El fragmento de protocolo ingresa al texto./La herramienta no se ejecuta"cuando,
                                    // Estos datos sin procesar son necesarios para localizar si el formato ascendente es anormal o se trata de un problema de análisis.
                                    Logger.w(
                                        "OpenAIProvider",
                                        "tool_calls fragmento apiIndex=$apiIndex no encontrado en el mapa(Primera película perdida?),Saltar esta película | " +
                                            "contenido del fragmento: id=${tc.id ?: "null"} name=${tc.function?.name ?: "null"} " +
                                            "argsLen=${tc.function?.arguments?.length ?: 0} " +
                                            "args=${tc.function?.arguments?.take(200) ?: ""}",
                                    )
                                    return@forEach
                                }
                                // v1.0.20: stream-guard — Acumular nombre y argumentos.
                                //   Acumular incluso si el nombre está vacío,Done Si el nombre aún está vacío, se restaurará en ContentDelta.
                                val acc = toolCallAccMap.getOrPut(localIndex) { ToolCallAccState() }
                                if (tc.id != null) acc.id = tc.id
                                tc.function?.name?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                                tc.function?.arguments?.let { acc.args.append(it) }
                                if (!tc.function?.name.isNullOrBlank()) {
                                    activeContinuationLocalIndex = localIndex
                                }
                                if (activeContinuationLocalIndex == localIndex && acc.args.isValidJson()) {
                                    activeContinuationLocalIndex = null
                                }
                                // v2.x: grabar"Nombrado pero los parámetros aún están vacíos"La llamada pendiente (para que la secuela sin nombre sea devuelta a su ubicación).
                                //   Homing sólo se permite cuando hay un solo candidato. Varios candidatos indican llamadas paralelas y no se pueden adivinar.
                                pendingArgsLocalIndex = pendingArgsCandidate(toolCallAccMap)

                                // v1.0.22: Restaurado incrementalmente a ContentDelta acc,Los argumentos posteriores continúan enviándose como ContentDelta
                                //   (Una vez que se determina que el nombre está vacío, la llamada a la herramienta será anormal y se restaurará de forma incremental.,name El texto enviado no será retirado aunque llegue más tarde.)
                                // v1.0.52: recoveredAsContent ya no está configurado incrementalmente en true(Cuando se cambia a Listo, se determina según el motivo de finalización.),
                                //   Esta rama conserva la compatibilidad con versiones anteriores, pero en realidad no se incluirá en ella.
                                if (acc.recoveredAsContent) {
                                    val argsDelta = tc.function?.arguments.orEmpty()
                                    if (argsDelta.isNotEmpty()) {
                                        trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                    }
                                    return@forEach
                                }

                                // stream-guard: Manejo de nombres vacíos
                                //   v1.0.20: Almacenar en búfer hasta que esté listo antes de continuar
                                //   v1.0.22: Cambiar a incremental inmediato volver a ContentDelta
                                //   v1.0.52: Recurrir solo al almacenamiento en búfer sin enviar — v1.0.22 La recuperación incremental da como resultado llamadas a herramientas reales
                                //     (finishReason=tool_calls) Los argumentos se convirtieron por error a texto.,Los usuarios ven la ilusión de fragmentos JSON.
                                //     buffer para Done,Done se determina en función de la razón de finalización:
                                //       - tool_calls + vacío name → Activar respaldo sin transmisión(El modo de transmisión del modelo no genera correctamente el nombre de la herramienta)
                                //       - Otro + vacío name → Volver a ContentDelta(ilusión de texto)
                                val currentName = acc.name
                                if (currentName.isNullOrBlank()) {
                                    toolCallDeltaSent.set(true)
                                    anyDeltaSent.set(true)
                                    // v1.0.52: Sólo búfer,no enviar ContentDelta
                                    val stormCount = emptyNameToolCallCount.incrementAndGet()
                                    if (stormCount > MAX_EMPTY_NAME_TOOL_CALLS) {
                                        // tormenta:El modelo pequeño divide el texto en una gran cantidad de espacios independientes. name tool call,Continuar amortiguando ralentizará toda la ronda.
                                        // Después de exceder el umbral, restaurar por incremento de texto,Deje que el nivel superior reciba el texto lo antes posible. Las llamadas a herramientas reales no tendrán tantos nombres vacíos seguidos.
                                        acc.recoveredAsContent = true
                                        val argsDelta = tc.function?.arguments.orEmpty()
                                        if (argsDelta.isNotEmpty()) {
                                            contentCharsSent.addAndGet(argsDelta.length)
                                            trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                        }
                                        Logger.w(
                                            "OpenAIProvider",
                                            "stream-guard: La llamada a la herramienta de nombre vacío excede $MAX_EMPTY_NAME_TOOL_CALLS Artículo,Restaurar por incremento de texto (localIndex=$localIndex)",
                                        )
                                        return@forEach
                                    }
                                    Logger.d(
                                        "OpenAIProvider",
                                        "stream-guard: Búfer de llamada de herramienta de nombre vacío (localIndex=$localIndex, acumular args=${acc.args.length} chars)",
                                    )
                                    return@forEach
                                }

                                // name Llegó — enviar ToolCallDelta
                                toolCallDeltaSent.set(true)
                                anyDeltaSent.set(true)
                                if (!acc.hasEmitted) {
                                    // Envío por primera vez — name tarde,Trae el dinero acumulado de una sola vez arguments(ponerse al día)
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = localIndex,
                                            id = acc.id,
                                            name = currentName,
                                            argumentsDelta = acc.args.current(),
                                        ),
                                    )
                                    acc.hasEmitted = true
                                } else {
                                    // Incrementos posteriores.
                                    // v1.0.81: Si se combinan demasiados objetos JSON en el acumulador(Mango modelo arguments
                                    // dividir en {"a":1}{"b":2}),El incremento original no se puede volver a enviar al flujo descendente. append(Eso será reescrito
                                    // cuerda de empalme),Vuelva a publicar la instantánea JSON completa fusionada,El flujo descendente sobrescribe con instantáneas en lugar de agregarlas.
                                    val deltaArgs = if (acc.args.hasMergedObjects()) {
                                        acc.args.current()
                                    } else {
                                        tc.function?.arguments.orEmpty()
                                    }
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = localIndex,
                                            id = tc.id,
                                            // v2.5.0 fix: El nombre de la película siguiente puede ser una cadena vacía.(Cada delta en la estación de transferencia tiene un campo de nombre.,
                                            // Las cadenas vacías que no son nulas penetrarán hasta el final y sobrescribirán los nombres de herramientas acumulados.,causa Sanitizer
                                            // a "name esta vacio" Descartar todas las llamadas a herramientas). Enviar siempre en blanco null,
                                            // Los nombres acumulados se conservan en sentido posterior con semántica nula.
                                            name = tc.function?.name?.takeIf { it.isNotBlank() },
                                            argumentsDelta = deltaArgs,
                                            isSnapshot = acc.args.hasMergedObjects(),
                                        ),
                                    )
                                }
                            }
                        }
                        if (!choice.finishReason.isNullOrBlank()) {
                            // v1.0.47: Descartar el evento FinishReason cuando la reversión esté en curso(Lo mismo [DONE] Lógica de salida anticipada)
                            if (pendingFallback.get()) return
                            // v1.0.20: stream-guard — Done Verificar acumulación en evento toolCallAccMap,
                            //   Una llamada de herramienta con un nombre vacío vuelve a ContentDelta
                            emitDoneWithStreamGuard(choice.finishReason)
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (myGeneration != connectionGeneration.get()) {
                            if (request.abortSignal.aborted) {
                                scope.trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar"))
                                close()
                            } else if (consumerClosed.get() || scope.isClosedForSend) {
                                close()
                            }
                            return
                        }
                        streamSourceClosed.set(true)
                        if (request.abortSignal.aborted) {
                            close()
                            return
                        }
                        // v1.0.23: Las API como SenseTime pueden cerrar directamente la conexión,No enviado [DONE] Tampoco enviado finishReason
                        //   Si aún no se ha activado stream-guard,Dispare aquí para detectar el final prematuro de la transmisión y el retroceso
                        // v1.0.24: No cuando la reversión está en curso close,Espere a que se complete la rutina alternativa
                        if (pendingFallback.get()) {
                            Logger.d("OpenAIProvider", "streamChat onClosed: Reversión en progreso, esperar a que se complete")
                            return
                        }
                        if (!streamGuardDone.get()) {
                            Logger.d("OpenAIProvider", "streamChat onClosed: Evento hecho no recibido, gatillo stream-guard")
                            emitDoneWithStreamGuard(null)
                        } else {
                            close()
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (myGeneration != connectionGeneration.get()) {
                            if (request.abortSignal.aborted) {
                                scope.trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar", t))
                                close()
                            } else if (consumerClosed.get() || scope.isClosedForSend) {
                                close()
                            }
                            return
                        }
                        streamSourceClosed.set(true)
                        // v1.0.24: No cuando la reversión está en curso close,Espere a que se complete la rutina alternativa
                        if (pendingFallback.get()) {
                            Logger.d("OpenAIProvider", "streamChat onFailure: Reversión en progreso, Ignorar errores de conexión t=${t?.message}")
                            return
                        }
                        if (streamGuardDone.get() || consumerClosed.get() || scope.isClosedForSend) {
                            Logger.d("OpenAIProvider", "streamChat onFailure: La transmisión se ha completado o el consumidor se ha cerrado.,Ignorar devoluciones de llamada de cierre")
                            close()
                            return
                        }
                        if (request.abortSignal.aborted) {
                            // v1.0.53: Distinguir entre aborto verdadero y limpieza final — Después de que el flujo se complete normalmente, ChatViewModel abort signal
                            //   Limpiar recursos,En este momento, si la devolución de llamada onFailure está volando,El registro original será engañoso ya que"Abortar usuario".
                            if (streamGuardDone.get()) {
                                Logger.d("OpenAIProvider", "streamChat onFailure: Devolución de llamada final después de que se haya completado la transmisión(ignorar,aborto no usuario)")
                            } else {
                                Logger.d(
                                    "OpenAIProvider",
                                    "streamChat aborted by user | contentChars=${contentCharsSent.get()} | " +
                                        "streamGuardDone=${streamGuardDone.get()} | anyDeltaSent=${anyDeltaSent.get()}",
                                )
                                // El usuario se detiene activamente: envíe StreamInterrupted para permitir que el flujo descendente (incluido FirstEventWatchdog) perciba claramente la interrupción.
                                // Evite el cierre silencioso, lo que provocaría que el perro guardián sea juzgado erróneamente como“Evento sin cabeza”Continúe activando el respaldo sin transmisión.
                                scope.trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar"))
                            }
                            close()
                            return
                        }
                        // M-OAI3: Determinar si se puede volver a intentar(408/429/5xx/IOException),y no se emitieron incrementos,Y no se ha alcanzado el límite de reintentos.
                        // H-OAI1: Reintentar la condición en su lugar anyDeltaSent(Cubre tres tipos de incrementos de Contenido/Razonamiento/Llamada a herramientas),
                        //   Solo verificar toolCallDeltaSent daría como resultado el flujo del texto/Se reedita el razonamiento.
                        val code = response?.code
                        val isRetryable = (code != null && (code == 408 || code == 429 || code in 500..599)) ||
                            (t is java.io.IOException)
                        if (isRetryable && !anyDeltaSent.get() &&
                            retryCount.incrementAndGet() <= MAX_RETRIES &&
                            !request.abortSignal.aborted
                        ) {
                            val attempt = retryCount.get()
                            var backoffMs = (1L shl attempt) * RETRY_BASE_DELAY_MS
                            // M-OAI7: 429 La limitación actual utiliza primero el encabezado de respuesta Reintentar después.(segundos → milisegundos)
                            if (code == 429) {
                                backoffMs = ProviderError.parseRetryAfter(response?.header("Retry-After"))
                                    ?.let { it * 1000L } ?: backoffMs
                                // v1.0.1: 429 cambiar al siguiente key(Múltiples escenarios clave)
                                //  Inténtalo de nuevo inmediatamente después de que el cambio sea exitoso.(no esperes backoff),Porque es posible que la nueva clave no tenga límite actual
                                if (switchToNextKey()) {
                                    Logger.i("OpenAIProvider", "streamChat 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ahora")
                                    backoffMs = 0L
                                }
                            }
                            // L-OAI9: agregar jitter(0~499ms),Evite los reintentos sincronizados de varios clientes que provocan grupos de pánico
                            if (backoffMs > 0) {
                                backoffMs += Random.nextLong(0, 500)
                            }
                            // v1.0.1: key Reconstruir después de cambiar httpRequest(actualizar Authorization header)
                            httpRequest = buildHttpRequest()
                            connectionGeneration.incrementAndGet()
                            // R-AI-03: Borre el grupo de conexiones medio abierto antes de volver a intentarlo/Conexión inactiva,Evite reutilizar conexiones de reinicio
                            ProviderHttpSupport.evictIdleConnections()
                            Logger.w(
                                "OpenAIProvider",
                                "streamChat onFailure, retry $attempt/$MAX_RETRIES after ${backoffMs}ms: ${t?.message ?: code}",
                            )
                            scope.launch {
                                delay(backoffMs)
                                if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                    connect()
                                }
                            }
                            return
                        }
                        // L-OAI1: Utilice readBodySafely en su lugar runCatching
                        // v1.109 Reparación: SSE Creado(2xx)La post-interrupción es una desconexión,No es un error HTTP
                        //   Priorizar mensajes arrojables,Evite construcciones engañosas "HTTP 200" error
                        val msg = response?.let {
                            if (it.code in 200..299) {
                                t?.message?.takeIf { m -> m.isNotBlank() }
                                    ?: ErrorCode.STREAM_INTERRUPTED.toMessage()
                            } else {
                                val bodyText = ProviderHttpSupport.readBodySafely(it)
                                parseErrorMessage(it.code, bodyText)
                            }
                        } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
                        Logger.e("OpenAIProvider", "streamChat onFailure: $msg", t)
                        // v1.0.15: Enviado cuando se ha recibido parte del contenido StreamInterrupted,Deje que la interfaz de usuario conserve el contenido recibido y solicite la interrupción de la red(Puede reconectarse automáticamente)
                        if (anyDeltaSent.get()) {
                            trySend(ChatStreamEvent.StreamInterrupted(msg, t))
                        } else {
                            trySend(ChatStreamEvent.Error(msg, t))
                        }
                        close()
                    }
                },
            )
            installEventSource(eventSource)
        }

        connect()

        awaitClose {
            abortListener.close()
            // Liberar solo recursos HTTP,No marque el cierre normal como cancelación iniciada por el usuario.
            consumerClosed.set(true)
            cancelCurrentEventSource()
        }
        // C-35: El canal interno ha sido cambiado a channelFlow(capacity = UNLIMITED),trySend No más pérdidas de tabletas debido a la capacidad total.
        //   aguas abajo `.buffer(Channel.UNLIMITED)` Solo es efectivo para el reenvío del siguiente nivel del productor al recolector.,
        //   No se puede solucionar el problema de pérdida del chip interno de 64 capacidades de CallbackFlow(Ha sido solucionado por la capacidad explícita de channelFlow anterior.).
    }.flowOn(Dispatchers.IO).buffer(Channel.UNLIMITED)

    /**
     * Chat sin transmisión.
     *
     * v1.80 (L-OAI15): decisiones de diseño — completeText Sin reintentos automáticos(diferente de streamChat).
     *   Razón:completeText Sirve principalmente para tareas en segundo plano, como compilación de memoria/extracción de hechos.,
     *   Estas tareas están programadas por JobWorker,Ya tiene un mecanismo de reintento.(Worker.Result.retry);
     *   Agregar otra capa de reintentos a la capa del proveedor aumenta la latencia y dificulta exponer el progreso al usuario.
     *   Si realmente necesitas intentarlo de nuevo,Debe ser utilizado por la persona que llama(Worker)Depende del tipo de excepción devuelta.
     */
    override suspend fun completeText(request: ChatRequest): ChatCompletion = completeTextImpl(request, 0)

    private suspend fun completeTextImpl(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        // v1.0.7: useResponseApi=true Es hora de ramificar la API de Respuestas
        if (useResponsesApi) return@withContext completeTextResponses(request, keySwitchDepth)
        val body = buildRequestBody(request, stream = false)
        // M-OAI4: Utilice elementos de configuración en su lugar chatCompletionsPath(Consistente con streamChat)
        val url = baseUrl() + chatCompletionsPath()
        Logger.i("OpenAIProvider", "completeText: POST ${sanitizeUrl(url)} model=${request.model}")
        // v1.0.1: usar effectiveApiKey() Admite rotación de múltiples teclas
        // v1.0.18: ir modelo libre fallback(El usuario no completó la clave + modelo de lista blanca de SiliconFlow → Usar incorporado key)
        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { requestHeadersFor(request.sessionId).forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(httpRequest)
        val abortListener = request.abortSignal.addAbortListener { call.cancel() }
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    // v1.0.1: 429 Cambia de tecla y vuelve a intentarlo.(Múltiples escenarios clave);401/403 Error al marcar la clave
                    if (code == 429 && !request.abortSignal.aborted && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i("OpenAIProvider", "completeText 429 Limitación de corriente,Cambiado al siguiente key,Inténtalo de nuevo ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextImpl(request, keySwitchDepth + 1)
                    }
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    // L-OAI1: Utilice readBodySafely en su lugar runCatching
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val msg = parseErrorMessage(code, errText)
                    Logger.w("OpenAIProvider", "completeText HTTP $code: $msg")
                    // v1.0.28: HTTP 400 Registre el cuerpo de la solicitud y el cuerpo completo de la respuesta.,Ayuda a diagnosticar errores de parámetros de la estación de transferencia
                    if (code == 400) {
                        Logger.w("OpenAIProvider", "completeText 400 Resumen de la solicitud: ${describeRequestBody(body)}")
                        Logger.w(
                            "OpenAIProvider",
                            "completeText 400 Resumen de respuesta: ${ProviderError.safeErrorDetail(errText) ?: "<empty>"}",
                        )
                    }
                    // L-OAI11: Utilice excepciones personalizadas para reemplazar el juicio de prefijo de cadena
                    throw OpenAIHttpException(code, msg, ProviderError.parseRetryAfter(response.header("Retry-After")))
                }
                // M-OAI6: body puede ser null(Aunque OkHttp rara vez se usa en la práctica null,Pero en términos de tipo Nullable),Seguridad de cortocircuito unificada
                val raw = resp.body?.string()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_body", resp.code)
                val customResponsePath = customConfig?.responsePath?.takeIf { it.isNotBlank() }
                if (customResponsePath != null) {
                    val parsedJson = AppJson.parseToJsonElement(raw)
                    val text = extractTextFromElement(
                        ProviderTemplateEngine.extractByPath(parsedJson, customResponsePath),
                    ) ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_custom_response")
                    return@withContext ChatCompletion(text = text)
                }
                val parsed = AppJson.decodeFromString<OpenAICompletionResponse>(raw)
                val choice = parsed.choices.firstOrNull()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_choices")
                val msg = choice.message
                val text = msg?.content.orEmpty()
                val reasoningContent = msg?.reasoningContent.orEmpty()
                val toolCalls = msg?.toolCalls?.map {
                    ToolCall(id = it.id, name = it.function.name, arguments = it.function.arguments)
                }
                // issue #9(v2.6.3 volver a probar): DeepSeek Flash regresar a menudo "toolCalls=1 Pero los argumentos están vacíos.",
                // Degradado por desinfectante a {} Más tarde, channel_reply falló debido a que faltaba contenido.
                // Aquí, escriba la forma del parámetro de cada llamada (duración, no contenido) —— Los parámetros pueden contener nombre de usuario./portapapeles),
                // Se utiliza para distinguir "la verdad ascendente está vacía" y "analizamos y perdemos":
                //   len=0        → Falta el campo de argumentos ascendentes o la cadena está vacía
                //   raw="{}"    → Lo que proporciona el upstream es un objeto vacío.
                //   Otros          → Hay contenido en el upstream, pero el problema radica en el proceso de seguimiento.
                if (!toolCalls.isNullOrEmpty()) {
                    // No recuerdo el nombre. Texto original: el modelo a veces coloca la línea completa en el nombre de la función, que puede contener contenido del usuario.
                    // Sólo recordar el formulario (longitud + si el identificador es legal + longitud del parámetro) es suficiente para localizar el problema.
                    val shape = toolCalls.joinToString(", ") { tc ->
                        val nameValid = ToolCallSanitizer.isValidName(tc.name)
                        "nameLen=${tc.name.length},valid=$nameValid,argsLen=${tc.arguments.length}" +
                            if (tc.arguments.trim() == "{}") ",emptyObj" else ""
                    }
                    Logger.i("OpenAIProvider", "completeText toolCalls forma paramétrica: $shape | finish=${choice.finishReason}")
                }
                // Phase 7: si hay tool_calls,Permitir que el texto esté vacío(Escenario de llamada de herramienta)
                // M-OAI8: Si hay contenido de inferencia.(reasoning_content),El texto también puede estar vacío.
                //   (Es posible que algunos modelos de razonamiento solo devuelvan contenido_razonamiento y el contenido esté vacío en respuestas que no son de transmisión.)
                if (text.isBlank() && toolCalls.isNullOrEmpty() && reasoningContent.isBlank()) {
                    // v2.6.8(comentarios de los usuarios): Solía ​​sólo informar aquí “empty_text”, el usuario no tiene absolutamente ninguna idea de lo que ha devuelto el flujo ascendente.
                    // Escenario típico: la estación de transferencia devuelve 200 pero el cuerpo es una opción vacía/con solo uso/o una estructura no estándar,
                    // El usuario piensa que la aplicación no funciona, pero en realidad el dispositivo ascendente está vacío.
                    // Ahora escriba el formulario de respuesta en el error (sin el texto, solo la estructura) para que el mensaje de error pueda explicarse por sí solo.
                    val shape = ProviderHttpSupport.describeJsonShape(raw)
                    Logger.w(
                        "OpenAIProvider",
                        "completeText Devolver texto vacío(Ninguno content/reasoning_content/tool_calls), formulario de respuesta: $shape",
                    )
                    throw ErrorCode.INVALID_RESPONSE.toProviderException(
                        "empty_text (upstream returned no content; response shape: $shape)",
                    )
                }
                Logger.d(
                    "OpenAIProvider",
                    "completeText OK: text=${text.length} chars, " +
                        "reasoning=${reasoningContent.length} chars, toolCalls=${toolCalls?.size ?: 0}",
                )
                // v2.x(Problema n.º 5 de comentarios de los usuarios): sospechoso"fuga de protocolo"Diagnóstico —— Upstream degrada la respuesta de tool_calls a
                // Cuando el texto sin formato (finishReason no tool_calls y toolCalls está vacío),text Aparecerá un fragmento de protocolo.
                // Solo los registros se registran sin intervención (para evitar dañar accidentalmente conversaciones normales sobre JSON), lo que permite a los usuarios localizar los registros después de exportarlos.
                val hasNoToolCalls = toolCalls.isNullOrEmpty()
                val mentionsToolCalls = text.contains("tool_calls")
                val mentionsFunctionOrArgs = text.contains("""\"function\"""") || text.contains("""\"arguments\"""")
                val looksLikeProtocolLeak = hasNoToolCalls && mentionsToolCalls && mentionsFunctionOrArgs
                if (looksLikeProtocolLeak) {
                    val leakPreview = text.take(200)
                    Logger.w(
                        "OpenAIProvider",
                        "Se filtra presunto acuerdo: finishReason=${choice.finishReason} " +
                            "Pero el texto contiene un fragmento del protocolo tool_calls.(${text.length} chars), primeras 200 palabras: $leakPreview",
                    )
                }
                ChatCompletion(
                    text = text,
                    finishReason = choice.finishReason,
                    toolCalls = toolCalls,
                    reasoningContent = reasoningContent.takeIf { it.isNotBlank() },
                    usageTokens = parsed.usage?.toUsageTokens(),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d("OpenAIProvider", "completeText aborted by user")
            } else if (t is OpenAIHttpException) {
                // L-OAI11: Errores HTTP registrados,No repetido log
            } else {
                Logger.e("OpenAIProvider", "completeText anormal", t)
            }
            throw t
        } finally {
            abortListener.close()
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    private fun baseUrl(): String = config.resolvedBaseUrl()

    /**
     * v2.6.1: baseUrl Si apuntar al backend Codex de la suscripción ChatGPT.
     */
    private fun isCodexUrl(url: String): Boolean = url.contains("chatgpt.com/backend-api/codex", ignoreCase = true)

    /**
     * v2.6.1: Si se debe procesar de acuerdo con el protocolo backend del Codex.
     *
     * Codex Por defecto, la sección del códice se coloca en baseUrl(`https://chatgpt.com/backend-api/codex`),
     * responsesPath mantener predeterminado `/responses`;Algunos usuarios también escriben la ruta completa en ResponsesPath.
     * Cualquier acierto se procesa según el protocolo del Codex.,De lo contrario, la solicitud no estará disponible. store=false / stream=true Y estaba aguas arriba 400.
     */
    private fun isCodexBackend(): Boolean = openAIConfig.responsesPath.contains("codex", ignoreCase = true) || isCodexUrl(baseUrl())

    /**
     * v2.6.1: Analizar la respuesta de la lista de modelos,Compatible con el backend del Codex `{models:[{slug}]}` forma.
     *
     * Codex /codex/models Regresar `{"models":[{"slug":"gpt-5.4"}]}`,
     * con estándares OpenAI `{data:[{id}]}` diferente;Aquí están unificados como [OpenAIModelInfo].
     */
    private fun parseModelList(raw: String): List<OpenAIModelInfo> {
        val root = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        val models = root?.get("models") as? JsonArray
        return if (models != null) {
            models.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val slug = (obj["slug"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                OpenAIModelInfo(id = slug)
            }
        } else {
            AppJson.decodeFromString<OpenAIModelsResponse>(raw).data.orEmpty()
        }
    }

    /**
     * Resolve the configured Chat Completions path for both OpenAI and Custom
     * specific configurations.
     *
     * Custom providers use [ProviderSpecificConfig.Custom] to carry their
     * path, so reading only [openAIConfig] silently discarded that setting
     * and could make a healthy `/models` check followed by a chat HTTP 404.
     */
    private fun chatCompletionsPath(): String {
        val configured = when (val specific = config.specific) {
            is ProviderSpecificConfig.Custom -> specific.chatCompletionsPath
            is ProviderSpecificConfig.OpenAI -> specific.chatCompletionsPath
            else -> ProviderSpecificConfig.OpenAI().chatCompletionsPath
        }.trim()
        return when {
            configured.isBlank() -> "/chat/completions"
            configured.startsWith("/") -> configured
            else -> "/$configured"
        }
    }

    /**
     * Extraiga la lista de modelos ascendentes.
     *
     * GET {resolvedBaseUrl()}/models(resolvedBaseUrl Incluido /v1),
     * Bearer Autenticación,analizar data[].id. La identificación/nombre de cada modelo devuelto es el upstream id,
     * contextWindow usar [ModelContextWindowRegistry] Ten todo en cuenta(El upstream no devuelve este campo).
     *
     * [config] Los parámetros formales permiten pasar valores editados no guardados(Si se cambia temporalmente apiKey/baseUrl).
     *
     * v1.80 (H-OAI2): Captar explícitamente CancellationException y call.cancel(),
     *   De lo contrario, OkHttp Call continuará bloqueando el hilo IO durante más tiempo. 300s(readTimeout),Como resultado, el hilo todavía está ocupado después de que se cancela la rutina.
     *
     * v1.132 Optimizar:
     *  - OpenRouter: analizar context_length / max_completion_tokens / pricing,
     *    Regístrese dinámicamente en [ModelContextWindowRegistry](Tres capas de superposición de metainformación);
     *    y agregue el encabezado de atribución HTTP-Referer/X-Title
     *  - Filtrar entradas de excepción:id Pseudo modelo igual a la identificación del propio proveedor(Filtrar pseudomodelos de DeepSeek)
     *  - Eliminar duplicados por id,mantener el primero
     *  - Ordenar alfabéticamente por identificación,Fácil de encontrar para los usuarios
     *  - Utilice tiempos de espera cortos independientes client(30s connect + 30s read),Evite el retraso de listModels y ocupe recursos de conexión de chat largos
     *  - 401/403 No fallback,Lanzar un error directamente(Los problemas de credenciales no están cubiertos,Manejar por nivel de error)
     *
     * v1.0.8 (7.3 / 7.5):
     *  - Capacidades del servidor resolución de nombres de múltiples campos:apoyo supports_tool_calls / function_calling /
     *    supports_vision / image_input / multimodal / supports_streaming / stream etc. nombre del campo,
     *    Cubrir las diferencias de nombres para diferentes servicios de tránsito/agregación.
     *  - fetchModels Registro de clasificación de errores en cascada:Registrado en listModels entrada/capa de acierto/capa de error Logger,
     *    Fácil de solucionar problemas"¿Por qué no obtuve el modelo?".
     *  - 401/403 Lanzar un error directamente(No fallback);404 / También se producen errores de red, pero las etiquetas de clasificación son diferentes.,
     *    por la persona que llama(ProviderSection.fetchModels)Utilice URL de múltiples estrategias para completar la solución.
     */
    override suspend fun listModels(config: ProviderConfig): List<Model> = withContext(Dispatchers.IO) {
        val resolvedBaseUrl = config.resolvedBaseUrl()
        // v1.0.18: modelo libre provider(SiliconFlow + El usuario no ha completado key)Sin ajuste remoto /models,
        //   Volver directamente a la lista de modelos gratuitos preestablecidos(Alineación FreeModelConfig.FREE_MODEL_IDS).
        //   Evite el retorno remoto debido a que no hay una llamada apiKey 401 / 403,Hazlo visible para los usuarios en la página del proveedor de SiliconFlow.
        //   GLM-4-9B / Qwen3-8B precargado listo para usar. Después de que el usuario completa la clave, isFreeProvider regresa false,
        //   Utilice la lógica de extracción remota original para desbloquear todos los modelos.
        if (FreeModelConfig.isFreeProvider(
                config.id,
                resolvedBaseUrl,
                config.apiKey,
                config.hiddenFromSettings,
            )
        ) {
            Logger.i("OpenAIProvider", "listModels: SiliconFlow modelo libre provider(No completado key),Volver al valor predeterminado ${config.models.size} modelos")
            return@withContext config.models
        }
        val url = resolvedBaseUrl.trimEnd('/') + "/models"
        // v2.6.1: Codex El punto final del directorio de modelos del backend tiene el parámetro de consulta requerido client_version.
        val isCodexModels = isCodexUrl(resolvedBaseUrl)
        val targetUrl = if (isCodexModels) "$url?client_version=$CODEX_CLIENT_VERSION" else url
        // P2-3: Identificando los servicios de Ollama(baseUrl host Contiene "ollama" o puerto 11434),
        //   Se llama a OllamaVisionInferrer para cada modelo en el momento de inferir soportesVision/supportsTools.
        val isOllama = isOllamaEndpoint(resolvedBaseUrl)
        // v1.0.8 (7.5): Registro de entrada — Registrar URL de destino/Id. de proveedor/si ollama,
        //   Es conveniente rastrear desde el registro qué nivel ha alcanzado la cascada de fetchModels.
        Logger.i("OpenAIProvider", "listModels: GET ${sanitizeUrl(url)}" + if (isOllama) " (ollama)" else "")

        // v1.0.8 (7.5): Verificación previa de credenciales — allowMissingApiKey=false Cuando apiKey está vacía, se genera un error directamente,
        //   Evite desperdiciar un viaje de ida y vuelta a la red enviando solicitudes 401 sin sentido.
        if (!config.allowMissingApiKey && config.apiKey.isBlank()) {
            Logger.w("OpenAIProvider", "listModels: apiKey esta vacio,omitir solicitud(providerId=${config.id})")
            throw OpenAIHttpException(401, ErrorCode.INVALID_RESPONSE.toMessage("model_list_missing_key", 401))
        }

        val builder = Request.Builder()
            .url(targetUrl)
            // B-27: Cuando hay varias claves, config.apiKey contiene comas/nueva línea,Escribir directamente contaminará el encabezado de Autorización provocando un 401.
            //     Usar en su lugar effectiveApiKey() Tome un solo efectivo key(Mismo patrón que la solicitud de chat/inserción).
            .header("Authorization", "Bearer ${effectiveApiKey()}")
            .header("Accept", "application/json")
            .apply { requestHeadersFor(null).forEach { (k, v) -> header(k, v) } }
        // v1.132: OpenRouter encabezado de atribución(OpenRouter Recomendación oficial)
        // Nombre de la aplicación del informe + fuente,Cumple con las reglas de clasificación de OpenRouter,Evite también ser identificado como tráfico anónimo
        if (url.contains("openrouter.ai")) {
            builder.header("HTTP-Referer", "https://github.com/zer0/muse")
                .header("X-Title", "Muse")
        }
        val httpRequest = builder.get().build()
        // v1.132: listModels Utilice tiempos de espera cortos independientes client(30s),Desconectado del chat por mucho tiempo de espera
        val listClient = httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        // H-OAI2: Imitación completaModo texto,explícito catch CancellationException + call.cancel()
        val call = listClient.newCall(httpRequest)
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    // L-OAI1: Utilice readBodySafely en su lugar runCatching
                    val contentType = resp.header("Content-Type").orEmpty()
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val htmlHint = openAiHtmlEndpointHint(contentType, errText)
                    val errMsg = htmlHint ?: (
                        ErrorCode.INVALID_RESPONSE.toMessage("model_list_fetch", resp.code) +
                            ProviderError.safeErrorDetail(errText)?.let { ": $it" }.orEmpty()
                        )
                    // v1.0.8 (7.5): Registro de clasificación de errores — 401/403 Los problemas de autenticación frente a los problemas de red 404/5xx se registran por separado
                    val category = when (resp.code) {
                        401, 403 -> "auth_failed"
                        404 -> "endpoint_not_found"
                        in 500..599 -> "server_error"
                        else -> "http_error"
                    }
                    Logger.w("OpenAIProvider", "listModels falló [$category] HTTP ${resp.code}: $errMsg")
                    // L-OAI16: Utilice OpenAIHttpException en lugar de genérico RuntimeException,
                    //   Mantener el código HTTP como información de tipo(Igual que texto completo),Es conveniente que la capa superior distinga la fuente de errores.
                    throw OpenAIHttpException(resp.code, errMsg)
                }
                // M-OAI6: body seguridad vacía
                val contentType = resp.header("Content-Type").orEmpty()
                val raw = resp.body?.string()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("model_list_empty", resp.code)
                openAiHtmlEndpointHint(contentType, raw)?.let { hint ->
                    throw OpenAIHttpException(resp.code, hint)
                }
                val models = try {
                    // v2.6.1: Compatible con el backend del Codex {models:[{slug}]} forma.
                    parseModelList(raw)
                } catch (error: kotlinx.serialization.SerializationException) {
                    throw ProviderException(
                        ProviderError.Unknown(
                            httpCode = resp.code,
                            displayMessage = "The models endpoint returned invalid JSON. Verify the OpenAI-compatible API base URL.",
                            cause = error,
                        ),
                    )
                }
                // v1.0.8 (7.5): registro de éxito — Registre el número de modelos devueltos por el upstream,Fácil de solucionar problemas"Devolver 0 modelos"escena
                Logger.i("OpenAIProvider", "listModels éxito: Regresar aguas arriba ${models.size} modelos")
                // v1.132: Deduplicación + filtrado + clasificación + enriquecimiento de metainformación
                val seen = HashSet<String>()
                models.asSequence()
                    // Filtrar identificadores vacíos y modelos falsos(id == provider id,Por ejemplo, la API de búsqueda profunda ocasionalmente devuelve "deepseek")
                    .filter { it.id.isNotBlank() && it.id != config.id }
                    // Eliminar duplicados por id,mantener el primero(Evitar que upstream devuelva duplicados)
                    .filter { seen.add(it.id) }
                    .map { m ->
                        // v1.132: OpenRouter/Together El context_length se transmite y registra de forma transparente en la tabla global.
                        // v1.0.8 Corrección:context_window Semántica diferente de max_completion_tokens,
                        // Este último sólo se utiliza para maxOutputTokens,Ya no se utiliza como ventana de contexto.
                        val contextLength = m.context_length
                        if (contextLength != null && contextLength > 0) {
                            ModelContextWindowRegistry.register(m.id, contextLength)
                        }
                        // v1.0.8: Servidor de análisis unificado capabilities + modalities,Construir modalidades de entrada/salida.
                        // prioridad: Declaración explícita del lado del servidor > Ollama inferencia > Solo texto predeterminado.
                        // v1.0.8 (7.3): Análisis extendido de nombres de campos múltiples — Diferentes estaciones de transferencia utilizan diferentes nombres de campos para declarar capacidades.,
                        //  Aquí se incluyen todos los alias posibles en la coincidencia.,Mejorar la compatibilidad.
                        //  Por ejemplo, OpenRouter utiliza "tools",Uso de transferencia parcial "supports_tool_calls",
                        //  Anthropic Para capa de compatibilidad "function_calling",Ollama usar "tool_call" Esperar.
                        val capabilitySet = m.capabilities
                            ?.map { it.trim().lowercase() }
                            ?.toSet()
                            ?: emptySet()
                        val modalitySet = m.modalities
                            ?.map { it.trim().lowercase() }
                            ?.toSet()
                            ?: emptySet()

                        // v1.0.8 (7.3): capacidad de entrada visual — Variaciones de nombre de campo admitidas
                        //  vision / image / image_input / supports_vision / multimodal / image_input_enabled
                        val serverVision = capabilitySet.contains("vision") ||
                            capabilitySet.contains("image") ||
                            capabilitySet.contains("image_input") ||
                            capabilitySet.contains("supports_vision") ||
                            capabilitySet.contains("multimodal") ||
                            modalitySet.contains("image")
                        // v1.0.8 (7.3): entrada de audio — audio / audio_input / supports_audio
                        val serverAudio = capabilitySet.contains("audio") ||
                            capabilitySet.contains("audio_input") ||
                            capabilitySet.contains("supports_audio") ||
                            modalitySet.contains("audio")
                        // v1.0.8 (7.3): entrada de vídeo — video_input / video / supports_video_input
                        val serverVideoIn = capabilitySet.contains("video_input") ||
                            capabilitySet.contains("video") ||
                            capabilitySet.contains("supports_video_input") ||
                            modalitySet.contains("video")
                        // salida de vídeo — video_output / video_generation / supports_video_output
                        val serverVideoOut = capabilitySet.contains("video_output") ||
                            capabilitySet.contains("video_generation") ||
                            capabilitySet.contains("supports_video_output")
                        // Salida de imagen — image_generation / image_output / dall-e / supports_image_output
                        val serverImageOut = capabilitySet.contains("image_generation") ||
                            capabilitySet.contains("image_output") ||
                            capabilitySet.contains("dall-e") ||
                            capabilitySet.contains("supports_image_output")
                        // v1.0.8 (7.3): llamada de herramienta — Variaciones de nombre de campo admitidas
                        //  tools / tool_call / tool_calls / function_call / function_calling /
                        //  supports_tool_calls / supports_tools / function_calling_enabled
                        val serverTools = capabilitySet.contains("tools") ||
                            capabilitySet.contains("tool_call") ||
                            capabilitySet.contains("tool_calls") ||
                            capabilitySet.contains("function_call") ||
                            capabilitySet.contains("function_calling") ||
                            capabilitySet.contains("supports_tool_calls") ||
                            capabilitySet.contains("supports_tools") ||
                            capabilitySet.contains("function_calling_enabled")
                        // v1.0.8 (7.3): capacidad de razonamiento — reasoning / reasoning_ability / supports_reasoning
                        val serverReasoning = capabilitySet.contains("reasoning") ||
                            capabilitySet.contains("reasoning_ability") ||
                            capabilitySet.contains("supports_reasoning")
                        // v1.0.8 (7.3): Nombre del campo de salida de streaming(streaming / stream / supports_streaming)
                        //  Actualmente solo aparece en los comentarios.,no afecta Model.supportsStreaming(Predeterminado true).
                        //  Para deshabilitar la transmisión según la declaración ascendente,Se puede escribir después de este juicio. Model.supportsStreaming = false.

                        val inferredVision = isOllama && !serverVision &&
                            OllamaVisionInferrer.inferSupportsVision(m.id)
                        val inferredTools = isOllama && !serverTools &&
                            OllamaVisionInferrer.inferSupportsTools(m.id)

                        val supportsVision = serverVision || inferredVision
                        val supportsVideoOut = serverVideoOut
                        val abilities = buildSet {
                            if (serverTools || inferredTools) add(ModelAbility.TOOL)
                            if (serverReasoning) add(ModelAbility.REASONING)
                        }

                        val inputModalities = buildSet {
                            add("text")
                            if (supportsVision || serverAudio || serverVideoIn) {
                                if (supportsVision) add("image")
                                if (serverAudio) add("audio")
                                if (serverVideoIn) add("video")
                            }
                        }
                        val outputModalities = buildSet {
                            add("text")
                            if (serverImageOut) add("image")
                            if (supportsVideoOut) add("video")
                        }

                        // Reparación de auditoría (Comentarios de los usuarios): DeepSeek Normalización oficial del nombre del modelo API — listModels
                        // Regreso con fecha/Variaciones de casos(Como por ejemplo DeepSeek-V4-Flash-0731),UI Después de guardar la identificación
                        // 400 cuando se solicite. Normalizado aquí al nombre canónico aceptado por la API oficial.,Haga que la interfaz de usuario sea coherente con las solicitudes.
                        val effectiveId = normalizeDeepSeekModelId(m.id, resolvedBaseUrl)
                        val rawModel = Model(
                            id = effectiveId,
                            name = effectiveId,
                            providerId = config.id,
                            // Priorizar aguas arriba context_length,A continuación revisa el formulario de inscripción.(El que acaba de registrar será afectado.)
                            contextWindow = contextLength ?: ModelContextWindowRegistry.lookup(effectiveId),
                            // v1.132: Pase de forma transparente max_completion_tokens como maxOutputTokens
                            maxOutputTokens = m.max_completion_tokens ?: m.top_provider?.max_completion_tokens,
                            // v1.0.8: Visión/vídeo/Herramientas/capacidad de razonamiento(Prioridad del servidor,Inferir el resultado final)
                            supportsVision = supportsVision,
                            supportsVideo = supportsVideoOut,
                            abilities = abilities,
                            inputModalities = inputModalities,
                            outputModalities = outputModalities,
                        )
                        // v1.0.8: Utilice el registro para completar la habilidad nuevamente./modal,Cubrir escenarios no declarados en el lado del servidor
                        //  (La detección de etiquetado incorrecto en la estación de transferencia interna se realizará internamente,Ver detalles ModelRegistry.enhanceModel)
                        ModelRegistry.enhanceModel(rawModel)
                    }
                    // Ordenar alfabéticamente por identificación,Fácil de encontrar para los usuarios
                    .sortedBy { it.id.lowercase() }
                    .toList()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (e: java.io.IOException) {
            // v1.0.8 (7.5): Registro de errores de red — Registro de tipos de IOException específicos,Fácil de solucionar problemas de DNS/TLS/tiempo de espera
            Logger.w("OpenAIProvider", "listModels error de red: ${e.javaClass.simpleName} - ${e.message}")
            throw e
        }
    }

    /**
     * P2-3: Determine si baseUrl es un servicio de Ollama.
     *
     * Ollama Puerto predeterminado 11434,Los usuarios también pueden utilizar nombres de host personalizados.(Como por ejemplo "ollama.local"),
     * o incluirlo explícitamente en baseUrl "ollama" logo. Golpea a cualquiera y presiona Ollama para procesar.,
     * gatillo [OllamaVisionInferrer] Inferencia.
     *
     * Nota: Solo para rama de inferencia de capacidad,No afecta la construcción de solicitudes ni la compatibilidad del protocolo.
     */
    private fun isOllamaEndpoint(baseUrl: String): Boolean {
        val lower = baseUrl.lowercase()
        return lower.contains(":11434") || lower.contains("ollama")
    }

    /**
     * B3-05: Representa el cuerpo de la solicitud según la configuración personalizada.
     *  - requestTemplate Reemplazar cuando no esté vacío {{model}} / {{messages}} / {{stream}} / {{prompt}} marcador de posición
     *  - customBody Fusionar con el nivel superior del JSON final cuando no esté vacío(Tanto la plantilla como el cuerpo de solicitud predeterminado son válidos.)
     */
    private fun renderCustomRequestBody(defaultBody: String, request: ChatRequest, effectiveModel: String, stream: Boolean): String {
        val template = customConfig?.requestTemplate?.takeIf { it.isNotBlank() }
        val body = if (template != null) {
            val defaultElement = runCatching { AppJson.parseToJsonElement(defaultBody) }.getOrNull() as? JsonObject
            val variables = mutableMapOf<String, JsonElement>(
                "model" to JsonPrimitive(effectiveModel),
                "stream" to JsonPrimitive(stream),
                "messages" to (defaultElement?.get("messages") ?: JsonArray(emptyList())),
            )
            request.messages.lastOrNull { it.role == MessageRole.USER }?.let {
                variables["prompt"] = JsonPrimitive(it.content)
            }
            val systemText = request.messages.filter { it.role == MessageRole.SYSTEM }
                .joinToString("\n\n") { it.content }
            if (systemText.isNotBlank()) variables["system"] = JsonPrimitive(systemText)
            defaultElement?.get("tools")?.let { variables["tools"] = it }
            defaultElement?.get("temperature")?.let { variables["temperature"] = it }
            defaultElement?.get("max_tokens")?.let { variables["max_tokens"] = it }
            ProviderTemplateEngine.renderRequestTemplate(template, variables)
        } else {
            defaultBody
        }
        return mergeCustomBody(body)
    }

    /** B3-05: Fusione los campos de nivel superior de customBody en el JSON de solicitud final. */
    private fun mergeCustomBody(body: String): String {
        val extras = customBody
        if (extras.isEmpty()) return body
        val element = runCatching { AppJson.parseToJsonElement(body) }.getOrNull()
        val obj = (element as? JsonObject)?.toMutableMap() ?: return body
        obj.putAll(extras)
        return AppJson.encodeToString(JsonObject.serializer(), JsonObject(obj))
    }

    /** B3-05: Convierta los elementos JSON extraídos en texto legible(primitive / array / object Campos de texto comunes). */
    private fun extractTextFromElement(element: JsonElement?): String? {
        return when (element) {
            null -> null
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
            is JsonArray -> element.mapNotNull { extractTextFromElement(it) }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
            is JsonObject -> {
                for (key in listOf("content", "text", "markdown", "value")) {
                    val child = element[key] ?: continue
                    val text = extractTextFromElement(child)
                    if (!text.isNullOrBlank()) return text
                }
                null
            }
        }
    }

    private fun buildRequestBody(request: ChatRequest, stream: Boolean = true): String {
        // v1.0.7: UTILITY Patrón de pensamiento forzado
        //  utility camino(memory Tareas de texto breves en segundo plano, como resumen/extracción de hechos/asistencia visual, etc.)No hay necesidad de pensar en la cadena.,
        //  fuerza effectiveReasoningLevel = OFF Para guardar token + reducir el retraso.
        val effectiveReasoningLevel = if (request.mode == ChatRequestMode.UTILITY) {
            io.zer0.ai.core.ReasoningLevel.OFF
        } else {
            request.reasoningLevel
        }
        // v1.138: Pensando en la optimización de niveles — Evite pensar demasiado en modelos de problemas simples.
        // - AUTO: No enviado reasoning_effort(Deja que el servidor decida por sí mismo)
        // - OFF: Solo oficial de OpenAI API(api.openai.com)enviar "minimal"(o1/o3 Serie de inferencias mínimas);
        //        Las estaciones de transferencia de terceros no realizan entregas("minimal" es un valor propietario de OpenAI,Si la estación de transferencia no lo reconoce, regresará 400)
        // - LOW/MEDIUM/HIGH/XHIGH: Enviar correspondencia explícitamente effort
        val isOpenAIOfficial = baseUrl().contains("api.openai.com")
        val effort = when (effectiveReasoningLevel) {
            io.zer0.ai.core.ReasoningLevel.AUTO -> null
            io.zer0.ai.core.ReasoningLevel.OFF ->
                if (request.model.supportsReasoning() && isOpenAIOfficial) "minimal" else null
            else -> effectiveReasoningLevel.effort
        }
        // compat derivado:prensa type + baseUrl + modelId Tres niveles de emparejamiento,Decida si inyectar razonamiento_effort/herramientas.
        // usar effectiveModelId(despojado stripModelPrefix)como modelId,Refleja el modelo realmente enviado a la API.
        // H-COMPAT1: Agregue juicio solo en los puntos de inyección de parámetros más obvios,No refactorice la lógica del cuerpo de construcción de la solicitud.
        val effectiveModel = effectiveModelId(request.model.id)
        val compat: ProviderCompat = config.resolvedCompat(effectiveModel)
        // v1.0.5: Provider Seguridad de las exportaciones — Primero realice una limpieza general independiente del proveedor en la lista UIMessage.
        //  (stripOrphanToolMessages Eliminar mensaje de HERRAMIENTA huérfana / stripNativeMediaAttachmentMarkers
        //   Limpiar etiquetas de imágenes redundantes),Luego haz la traducción del protocolo. Normalización de carga útil unificada.
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages,
            request.model,
        )
        // v1.0.7: Provider Prompt Patches — Inyectar un parche de aviso del sistema específico del proveedor
        //  (Actualmente, solo contrato de salida del modelo de inferencia de DeepSeek)
        //  UTILITY modo effectiveReasoningLevel=OFF,ProviderPromptPatches La inyección se omitirá internamente
        val promptPatches = ProviderPromptPatches.getProviderPromptPatches(
            model = request.model,
            baseUrl = baseUrl(),
            thinkingFormat = compat.thinkingFormat,
            reasoningLevel = effectiveReasoningLevel,
        )
        val messagesWithPatches = injectSystemPromptPatches(normalizedMessages, promptPatches)
        // Primero complete la limpieza del esquema y luego decida la elección de herramienta. Anteriormente, esto se juzgaba según request.tools,
        // pero ilegal/El esquema incompatible puede ser toOpenAISafely() filtrar todo,finalmente emitido
        // tool_choice=required Solicitudes donde faltan herramientas,Resultando en un 400 directo para la solicitud de MCP.
        val openAiTools = if (compat.supportsToolCalling) {
            request.tools?.mapNotNull { it.toOpenAISafely() }
                ?.distinctBy { it.function.name }
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val payload = OpenAIRequest(
            model = effectiveModel,
            messages = messagesWithPatches.map { it.toOpenAI(request.model, compat, carriesTools = !openAiTools.isNullOrEmpty()) },
            temperature = request.temperature,
            top_p = request.topP,
            // v1.0.2 Arreglar HTTP 400: verificación del rango max_tokens,0/Los valores negativos se consideran no establecidos.
            // Verificación estricta de algunas estaciones de transferencia compatibles con OpenAI max_tokens >= 1,Enviar 0 directamente devolverá 400 invalid_request_error.
            // null Será ignorado por kotlinx.serialization,No escriba el cuerpo de la solicitud,Deje que upstream use el valor predeterminado.
            max_tokens = request.maxTokens?.takeIf { it > 0 },
            stream = stream,
            // compat.supportsToolCalling=false No emitido cuando es forzado tools(Como por ejemplo deepseek-reasoner / o1-mini)
            // v1.0.4 Reparación HTTP 400 "Tool names must be unique":Deduplicación defensiva por función.nombre,
            // Incluso aguas arriba(ToolRegistry + SkillExecutor)Herramienta faltante con el mismo nombre.,También puedes interceptar aquí.,
            // Evite que las API como DeepSeek que verifican estrictamente la unicidad del nombre de la herramienta devuelvan 400.
            // v1.0.5: stripEmptyTools — La lista de herramientas vacía se cambia a null,evitar serializar
            //  `"tools": []` Rechazado por estricta estación de tránsito.
            tools = openAiTools,
            tool_choice = if (compat.supportsToolCalling && !openAiTools.isNullOrEmpty()) {
                request.toolChoice
            } else {
                null
            },
            // compat.supportsReasoningEffort=false No emitido cuando es forzado reasoning_effort
            //   (Como por ejemplo DeepSeek / Zhipu / Gemini OpenAI Capa de compatibilidad,Cada uno utiliza campos de razonamiento_contenido/pensamiento.)
            // v1.0.5: stripDisabledReasoningEffort — Cuando el valor es falso/ninguno/apagado, se considera no habilitado.,
            //   Cambie a nulo para no enviar.
            // v1.0.7: thinkingFormat != null Nunca te des cuenta reasoning_effort
            //   (Cambiar el campo de extensión del fabricante correspondiente,Como por ejemplo thinking / enable_thinking / chat_template_kwargs)
            reasoning_effort = if (compat.supportsReasoningEffort && compat.thinkingFormat == null) {
                effort?.takeIf { !isDisabledReasoningEffort(it) }
            } else {
                null
            },
        )
        Logger.d(
            "OpenAIProvider",
            "request contract | stream=$stream | mode=${request.mode} | " +
                "toolsIn=${request.tools?.size ?: 0} | toolsOut=${payload.tools?.size ?: 0} | " +
                "compatTools=${compat.supportsToolCalling} | model=$effectiveModel",
        )
        // v1.0.7: thinkingFormat inyectar — Construir parámetros de pensamiento por campo de extensión del fabricante.
        // Asigne campos del cuerpo de la solicitud en formatos thinkFormat 9.
        // darse cuenta:Primero serialice OpenAIRequest como JsonObject,Presione thinkFormat nuevamente para agregar/Modificar campos.
        // UTILITY modo effectiveReasoningLevel=OFF,injectThinkingFormat escribirá disabled
        val thinkingFormat = compat.thinkingFormat
        val defaultBody = if (thinkingFormat == null) {
            AppJson.encodeToString(payload)
        } else {
            injectThinkingFormat(payload, thinkingFormat, effectiveReasoningLevel)
        }
        // A5: Chat Completions transmisión usage — Protocolo estándar OpenAI(thinkingFormat==null)inyectar
        // stream_options.include_usage,Haz que el último trozo se lleve usage(choices es una matriz vacía).
        // Acuerdo de extensión del proveedor(thinkingFormat != null,Como por ejemplo DeepSeek/Kimi/Qwen/Zhipu)No inyectado,
        // Evite devoluciones estrictas de retransmisión en campos desconocidos 400;Dichos puntos finales no pueden transmitir usage,La capa superior vuelve a la estimación local.
        val bodyWithUsage = if (stream && thinkingFormat == null) {
            val element = runCatching { AppJson.parseToJsonElement(defaultBody) }.getOrNull()
            val obj = (element as? JsonObject)?.toMutableMap()
            if (obj != null) {
                obj["stream_options"] = buildJsonObject { put("include_usage", JsonPrimitive(true)) }
                AppJson.encodeToString(JsonObject.serializer(), JsonObject(obj))
            } else {
                defaultBody
            }
        } else {
            defaultBody
        }
        return renderCustomRequestBody(bodyWithUsage, request, effectiveModel, stream)
    }

    /**
     * v1.0.7: prensa [ThinkingFormat] Inyecte campos de pensamiento de extensión de proveedor.
     *
     * Implementado en 9 formatos según thinkFormat:
     *  - [ThinkingFormat.DEEPSEEK]:No envíes ningún parámetro de pensamiento.(El servidor está habilitado de forma predeterminada,Solo consume streaming reasoning_content)
     *  - [ThinkingFormat.KIMI]:`thinking: {type: "enabled"|"disabled", keep: false}`
     *  - [ThinkingFormat.QWEN]:`enable_thinking: bool` + Opcional `thinking_budget: int`(HIGH/XHIGH Shifa)
     *  - [ThinkingFormat.QWEN_CHAT_TEMPLATE]:`chat_template_kwargs: {enable_thinking: bool}`
     *  - [ThinkingFormat.ZHIPU]:`thinking: {type: "enabled"|"disabled", clear_thinking: false}`
     *  - [ThinkingFormat.OPENROUTER]:`reasoning: {effort: "low"|"medium"|"high"}`
     *  - [ThinkingFormat.VOLCENGINE]:`thinking: {type: "enabled"|"disabled"}`
     *  - [ThinkingFormat.LONGCAT]:`thinking: {type: "enabled"|"disabled"}`
     *  - [ThinkingFormat.ANTHROPIC]:OpenAIProvider No consumir(AnthropicProvider Procesar el propio bloque de pensamiento nativo)
     *
     * @param payload originales OpenAIRequest(Ya contiene campos estándar como mensajes/herramientas/transmisión)
     * @param format thinkingFormat valor de enumeración(no null)
     * @param level nivel de razonamiento(Determinar habilitado/deshabilitado + valor de esfuerzo)
     * @return Cadena JSON después de inyectar el campo de pensamiento
     */
    private fun injectThinkingFormat(payload: OpenAIRequest, format: ThinkingFormat, level: io.zer0.ai.core.ReasoningLevel): String {
        // Primero serialice OpenAIRequest como JsonObject
        val jsonElement = AppJson.encodeToJsonElement(OpenAIRequest.serializer(), payload)
        val baseObj = jsonElement as? kotlinx.serialization.json.JsonObject
            ?: return AppJson.encodeToString(payload)
        val baseMap = baseObj.toMutableMap()

        // interruptor de razonamiento:OFF → disabled,AUTO → enabled(Deja que el servidor decida la profundidad por sí solo.),Otros → enabled
        val thinkingEnabled = level != io.zer0.ai.core.ReasoningLevel.OFF

        when (format) {
            ThinkingFormat.DEEPSEEK -> {
                // DeepSeek No envíes ningún parámetro de pensamiento.(El servidor activa R1 de forma predeterminada. Pensando)
                // reasoning_content Resuelto en delta de transmisión,No se requiere control del cuerpo de solicitud
            }
            ThinkingFormat.KIMI -> {
                // lado oscuro de la luna Kimi:thinking: {type: "enabled"|"disabled", keep: false}
                // keep=false Indica que la cadena de pensamiento no se retiene en la respuesta final(guardar tokens)
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                    put("keep", false)
                }
            }
            ThinkingFormat.QWEN -> {
                // Qwen3:enable_thinking: bool + Opcional thinking_budget: int
                baseMap["enable_thinking"] = JsonPrimitive(thinkingEnabled)
                if (thinkingEnabled && level.budgetTokens != null && level.budgetTokens > 0) {
                    baseMap["thinking_budget"] = JsonPrimitive(level.budgetTokens)
                }
            }
            ThinkingFormat.QWEN_CHAT_TEMPLATE -> {
                // Alí Qwen3-Coder:chat_template_kwargs: {enable_thinking: bool}
                // (Qwen3-Coder Utilice el protocolo chat_template,enable_thinking Debe estar integrado en chat_template_kwargs)
                baseMap["chat_template_kwargs"] = buildJsonObject {
                    put("enable_thinking", thinkingEnabled)
                }
            }
            ThinkingFormat.ZHIPU -> {
                // espectro de sabiduría GLM-Z1:thinking: {type: "enabled"|"disabled", clear_thinking: false}
                // clear_thinking=false Indica no despejar la cadena del pensamiento.(permanecer en respuesta)
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                    put("clear_thinking", false)
                }
            }
            ThinkingFormat.OPENROUTER -> {
                // OpenRouter Transferencia de agregación:reasoning: {effort: "low"|"medium"|"high"}
                // (Chat Completions Extensión de protocolo,Diferente del razonamiento en la API de Respuestas)
                if (thinkingEnabled && level.effort != null) {
                    // OpenRouter No aceptado "minimal",OFF No emitir campos de razonamiento
                    val openRouterEffort = when (level) {
                        io.zer0.ai.core.ReasoningLevel.LOW -> "low"
                        io.zer0.ai.core.ReasoningLevel.MEDIUM -> "medium"
                        io.zer0.ai.core.ReasoningLevel.HIGH, io.zer0.ai.core.ReasoningLevel.XHIGH -> "high"
                        else -> null // AUTO/OFF No enviado
                    }
                    openRouterEffort?.let {
                        baseMap["reasoning"] = buildJsonObject {
                            put("effort", it)
                        }
                    }
                }
            }
            ThinkingFormat.VOLCENGINE -> {
                // motor volcánico Doubao Thinking:thinking: {type: "enabled"|"disabled"}
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                }
            }
            ThinkingFormat.LONGCAT -> {
                // meituán LongCat:thinking: {type: "enabled"|"disabled"}
                baseMap["thinking"] = buildJsonObject {
                    put("type", if (thinkingEnabled) "enabled" else "disabled")
                }
            }
            ThinkingFormat.ANTHROPIC -> {
                // Anthropic bloque de pensamiento nativo — OpenAIProvider No consumir
                // (AnthropicProvider Hazte nativo por tu cuenta Messages API,No sigas por este camino)
            }
        }

        return AppJson.encodeToString(kotlinx.serialization.json.JsonObject(baseMap))
    }

    /**
     * v1.0.5: Determinar si el valor de razonamiento_effort es"Discapacitado"Semántica.
     *
     * false / null / cadena vacía / "none" / "off" / "disabled" se consideran discapacitados,No debe enviarse a la API.
     */
    private fun isDisabledReasoningEffort(value: String?): Boolean {
        if (value == null) return true
        val normalized = value.lowercase()
        return normalized.isEmpty() || normalized == "none" || normalized == "off" || normalized == "disabled" || normalized == "false"
    }

    /**
     * L-OAI14: analizar [parametersJsonSchema] Omite la herramienta en caso de error y registra una advertencia,
     *   Evite el esquema ilegal de una sola herramienta que hace que falle toda la solicitud(Otras herramientas todavía se pueden llamar normalmente.).
     */
    private fun ToolDefinition.toOpenAISafely(): OpenAITool? = try {
        OpenAITool(
            function = OpenAIToolFunction(
                name = name,
                description = description,
                parameters = AppJson.decodeFromString(JsonElement.serializer(), parametersJsonSchema),
            ),
        )
    } catch (t: Throwable) {
        if (t is kotlin.coroutines.cancellation.CancellationException) throw t
        Logger.w(
            "OpenAIProvider",
            "Herramientas '$name' Falló el análisis de parámetros JsonSchema,Saltar esta herramienta: ${t.message}",
        )
        null
    }

    /**
     * Phase 8.6: UIMessage -> OpenAIMessage.
     *
     * procesamiento multimodal:
     *  - Sin fotos:imageBase64List esta vacio -> content = JsonPrimitive(text)(cadena simple)
     *  - hay fotos:imageBase64List No vacío -> content = JsonArray([
     *      {type:"text", text:"..."},
     *      {type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}
     *    ])(OpenAI Vision Acuerdo)
     *
     * M-OAI5: Tipo mime de imagen inferido a partir de bytes mágicos del encabezado base64.
     * M-OAI8: base64 Límite de cantidad de imágenes(<=4zhang)+ Límite de tamaño único(<=2MB),Se descarta si se excede.
     * L-OAI7: assistant + tool_calls Cuando el contenido esté vacío, pase JsonNull en lugar de una cadena vacía.
     *
     * v1.0.5: filtrado visual defensivo — cuando [model] Cuando la entrada visual no es compatible,Incluso si el mensaje lleva una imagen
     *  Tome también la ruta de texto sin formato(descartar imageBase64List),Evite enviar imágenes a modelos de solo texto para activar un 400.
     *  Esta es la última línea de defensa para las exportaciones de proveedores,Enrutamiento de ayuda visual para ChatViewModel en circunstancias normales
     *  La imagen debería haberse borrado antes de llamar a streamChat.(Vacío después de inyectar descripción de VisionBridge.prepare).
     *  Este filtro sólo se utiliza para excavar.:Escenarios anormales como imágenes residuales de mensajes históricos/falta de limpieza por parte de la persona que llama.
     */
    private fun UIMessage.toOpenAI(model: Model, compat: ProviderCompat? = null, carriesTools: Boolean = false): OpenAIMessage =
        OpenAIMessage(
            role = when (role) {
                MessageRole.SYSTEM -> "system"
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.TOOL -> "tool"
            },
            content = if (imageBase64List.isEmpty() || !model.supportsVisionInput()) {
                // v1.0.5: Cuando el modelo no admite imágenes pero el mensaje lleva imágenes.,Descarta la imagen y toma la ruta de texto sin formato.(Defensivo)
                if (imageBase64List.isNotEmpty() && !model.supportsVisionInput()) {
                    Logger.w(
                        "OpenAIProvider",
                        "toOpenAI: modelo ${model.id} Sin soporte visual,descartar ${imageBase64List.size} fotos(filtrado defensivo)",
                    )
                }
                // v1.0.2 Reparación HTTP 400: assistant + tool_calls cuando el contenido está vacío,Pase una cadena vacía en lugar de JsonNull.
                // Según prácticas compatibles:assistant + tool_calls El contenido se normaliza a una cadena vacía.
                // Normalizar contenido nulo/indefinido a "" cadena vacía,Evite los protocolos compatibles con OpenAI(Estación de transferencia especialmente estricta)
                // Rechazar mensajes del asistente con contenido: nulo. La transmisión JsonNull original de L-OAI7 activará 400 en algunas estaciones de transferencia.
                // OpenAI Los protocolos de compatibilidad también requieren que el contenido sea una cadena vacía en lugar de nulo.
                if (role == MessageRole.ASSISTANT && !toolCalls.isNullOrEmpty() && content.isBlank()) {
                    JsonPrimitive("")
                } else {
                    JsonPrimitive(content)
                }
            } else {
                buildJsonArray {
                    // texto part(Agregar incluso si está vacío,evitar messages[].content es una matriz vacía)
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", content)
                        },
                    )
                    // M-OAI8: Límite de cantidad de imágenes + límite de tamaño de una sola imagen,Descartar más allá del límite
                    val validImages = imageBase64List.filter { it.isNotEmpty() }
                    if (validImages.size > MAX_VISION_IMAGES) {
                        Logger.w("OpenAIProvider", "Número de fotos ${validImages.size} Superar el límite superior $MAX_VISION_IMAGES,descartar el exceso")
                    }
                    validImages.take(MAX_VISION_IMAGES).forEach { b64 ->
                        if (b64.length > MAX_IMAGE_BASE64_LEN) {
                            Logger.w("OpenAIProvider", "Longitud de la imagen base64 ${b64.length} exceder $MAX_IMAGE_BASE64_LEN,descartar")
                            return@forEach
                        }
                        // M-OAI5: Inferido de bytes mágicos mime type
                        val mimeType = inferMimeType(b64)
                        add(
                            buildJsonObject {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    buildJsonObject {
                                        put("url", "data:$mimeType;base64,$b64")
                                    },
                                )
                            },
                        )
                    }
                }
            },
            toolCalls = toolCalls?.map { it.toOpenAI() },
            toolCallId = toolCallId,
            // v1.0.7: Reproducción de razonamiento histórico — Determine si inyectar según el razonamientoReplayContract.carrier/policy reasoning_content
            reasoningContent = computeReasoningContentForReplay(compat, carriesTools),
        )

    /**
     * v1.0.7: Calcule el valor de Reasoning_content para repeticiones de razonamiento histórico.
     *
     * solo si compat.reasoningReplayContract.carrier == REASONING_CONTENT Considere inyectarse
     * (Kimi / DeepSeek / MiMo / Zhipu Chat Completions Acuerdo).
     *
     * policy toma de decisiones:
     *  - [ReasoningReplayPolicy.NONE]:No inyectado(Regresar null)
     *  - [ReasoningReplayPolicy.PRESERVE]:siempre inyectar(Si el razonamiento no es vacío.)
     *  - [ReasoningReplayPolicy.REQUIRE_TOOL_CALL]:ASSISTANT El mensaje se inyecta cuando la solicitud contiene herramientas.
     *    v1.0.81: Alinear el protocolo del modo de pensamiento oficial de DeepSeek — Las solicitudes con herramientas siguen a todos assistant
     *    Los mensajes deben devolver el campo Reasoning_Content.(Pase una cadena vacía incluso si está vacía,no se puede omitir),De lo contrario 400.
     *    No se envían conversaciones ordinarias sin herramientas.,Evite contaminar el contexto.
     *
     * Otros carrier(REASONING_ITEMS / REASONING_DETAILS / THINKING_BLOCKS / THOUGHT_SIGNATURE)
     * No procesado a través de esta función — ellos van Responses API / OpenRouter / Anthropic / Gemini respectivos caminos.
     *
     * v2.6.14: guardia de origen —— `thinkingSignature` / `thinkingEncryptedContent` No vacío indica que el bloque de pensamiento
     * Producido por un acuerdo de firma (Anthropic thinking block firma_delta,OpenAI Responses de
     * reasoning item id / encrypted_content), es imposible que sea causado por `reasoning_content` Generación de modelos de protocolos.
     * En este momento, el texto de pensamiento no se envía como razonamiento_contenido. Solo se decide según los requisitos del protocolo si se deja un marcador de posición de cadena vacío.
     */
    private fun UIMessage.computeReasoningContentForReplay(compat: ProviderCompat?, carriesTools: Boolean): String? {
        val contract = compat?.reasoningReplayContract ?: return null
        if (contract.carrier != ReasoningCarrier.REASONING_CONTENT) return null
        // v2.6.14: Los bloques de pensamiento producidos por protocolos de firma no se pueden reproducir a través de Reasoning_content (fuga entre protocolos).
        val hasForeignProvenance = !thinkingSignature.isNullOrBlank() || !thinkingEncryptedContent.isNullOrBlank()
        val replayable = if (hasForeignProvenance) null else reasoning
        return when (contract.policy) {
            ReasoningReplayPolicy.NONE -> null
            ReasoningReplayPolicy.PRESERVE -> replayable?.takeIf { it.isNotBlank() }
            ReasoningReplayPolicy.REQUIRE_TOOL_CALL -> {
                if (role != MessageRole.ASSISTANT) return null
                // v1.0.81: Alinear el protocolo del modo de pensamiento oficial de DeepSeek — Al solicitar con el parámetro de herramientas,
                // Todos los mensajes históricos del asistente posteriores en las solicitudes deben devolver el campo Reasoning_content.
                // Incluso si el modelo en esa ronda no produjo razonamiento/no se llamó a ninguna herramienta, de lo contrario la API devuelve 400:
                // "The reasoning_content in the thinking mode must be passed back to the API".
                // Cuando el razonamiento está vacío, se pasa la cadena vacía en lugar de nula (el campo debe existir). Cuando no haya herramientas, manténgalo nulo para guardar tokens.
                if (carriesTools) {
                    // v2.5.3: Sólo deben estar presentes los campos obligatorios del protocolo (requiresNonEmptyReasoning), se envía el marcador de posición de cadena vacía;
                    // Qwen/Doubao La cadena vacía del protocolo predeterminado se omite para guardar tokens.
                    if (contract.requiresNonEmptyReasoning) replayable.orEmpty() else replayable?.takeIf { it.isNotBlank() }
                } else {
                    replayable?.takeIf { it.isNotBlank() }
                }
            }
        }
    }

    /**
     * v1.0.7: Inyecte parches de aviso del proveedor en la lista de mensajes.
     *
     * Estrategia de inyección:
     *  - patches esta vacio:Volver como está(no copiar,Cero gastos generales)
     *  - Ya tengo mensaje del sistema:Agregar parches a**Artículo 1** system El final del contenido del mensaje.(separados por doble nueva línea)
     *    (Evite agregar múltiples mensajes del sistema,Algunas estaciones de transferencia tienen una verificación estricta de la cantidad de mensajes del sistema.)
     *  - No hay mensajes del sistema:Insertar un nuevo mensaje del sistema al principio de la lista(content = patches empalme)
     *
     * Nota:Esta función solo maneja el protocolo de finalización de chat.(messages matriz);
     *   Responses API El campo de instrucciones está en [buildResponsesRequestBody] procesados ​​por separado.
     */
    private fun injectSystemPromptPatches(messages: List<UIMessage>, patches: List<String>): List<UIMessage> {
        if (patches.isEmpty()) return messages
        val patchText = patches.joinToString("\n\n")
        val firstSystemIdx = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (firstSystemIdx >= 0) {
            // Se adjunta al final del primer mensaje del sistema.
            messages.toMutableList().also { list ->
                val orig = list[firstSystemIdx]
                list[firstSystemIdx] = orig.copy(
                    content = orig.content + "\n\n" + patchText,
                )
            }
        } else {
            // No hay mensajes del sistema,insertar una línea al principio
            listOf(
                UIMessage(role = MessageRole.SYSTEM, content = patchText),
            ) + messages
        }
    }

    private fun ToolCall.toOpenAI(): OpenAIToolCall = OpenAIToolCall(
        id = id,
        function = OpenAIToolCallFunction(name = name, arguments = arguments),
    )

    /**
     * M-OAI5: Inferir el tipo MIME de la imagen a partir de los bytes mágicos del encabezado base64.
     * - jpeg: 0xFFD8
     * - png:  0x89504E47
     * - gif:  0x47494638
     * - webp: 0x52494646
     * Imagen alternativa/jpeg.
     */
    private fun inferMimeType(base64: String): String {
        return try {
            // Consigue los primeros 8 caracteres base64(6 bytes)solía juzgar magic bytes
            val prefix = base64.take(8).padEnd(8, '=')
            val bytes = java.util.Base64.getMimeDecoder().decode(prefix)
            when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
                bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "image/png"
                bytes.size >= 4 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte() -> "image/gif"
                bytes.size >= 4 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() -> "image/webp"
                else -> "image/jpeg"
            }
        } catch (e: Exception) {
            "image/jpeg"
        }
    }

    /**
     * Analiza el mensaje de error.
     * M-OAI1: Utilice classifyHttpCode para clasificar códigos de estado.
     * M-OAI2: Truncamiento de mensajes(take 200).
     * L-OAI10: Adjuntar el campo código.detalle.
     * L-OAI11: Eliminar truncamiento duplicado(Originales safeBody=take(200) + msg.take(200) Truncado dos veces).
     */
    /** R-SEC-06: 400 La solución de problemas solo genera un resumen de la estructura de la solicitud,No incluya contenido de conversaciones de usuarios. */
    private fun describeRequestBody(body: String): String {
        val parsed = runCatching { AppJson.decodeFromString(OpenAIRequest.serializer(), body) }.getOrNull()
        val bytes = body.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hashPrefix = digest.take(4).joinToString("") { "%02x".format(it) }
        return "model=${parsed?.model ?: "unknown"} " +
            "messages=${parsed?.messages?.size ?: -1} " +
            "tools=${parsed?.tools?.size ?: 0} " +
            "bodyBytes=${bytes.size} sha256=$hashPrefix"
    }
    private fun parseErrorMessage(code: Int, body: String): String {
        // M-OAI1: HTTP Clasificación del código de estado
        val category = ProviderHttpSupport.classifyHttpCode(code)
        // M-OAI3: Usar en su lugar resultOf(revenderá CancellationException),sustituto runCatching
        val detail = resultOf {
            AppJson.decodeFromString<OpenAIErrorBody>(body).error
        }.getOrNull()
        return buildString {
            append("HTTP ").append(code)
            category?.let { append(" [").append(it).append("]") }
            // Priorizar lo analizado detail.message,Volver al original body(Truncar uniformemente una vez aquí)
            // L-OAI11: Quitar safeBody=body.take(200) pretruncamiento,solo aqui take(200)
            val msg = detail?.message?.takeIf { it.isNotBlank() }
                ?: ProviderError.safeErrorDetail(body)
            msg?.let { append(": ").append(ProviderError.safeErrorDetail(it) ?: it.take(200)) }
            // L-OAI10: Agregar campo de código de detalle
            detail?.code?.let { codeElem ->
                val codeStr = when (codeElem) {
                    is JsonPrimitive -> codeElem.content
                    else -> codeElem.toString()
                }
                if (codeStr.isNotBlank()) append(" [code=").append(codeStr).append("]")
            }
            detail?.type?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(")") }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // v1.0.7: OpenAI Responses API darse cuenta(/v1/responses punto final)
    //
    // Implementado de acuerdo con el protocolo API de Respuestas oficial de OpenAI.
    // Diferencias clave con la API de finalización de chat:
    //  - Cuerpo de la solicitud:messages → input;system role → instructions Campos de nivel superior;
    //    max_tokens → max_output_tokens;Nuevo reasoning: {effort, summary}
    //  - Respuesta de transmisión:event: response.output_text.delta + data: {delta: "..."},
    //    etiqueta final response.completed(concurrencia data: [DONE])
    //  - respuesta sin transmisión:choices[0].message.content → output[] matriz,
    //    reasoning / function_call Todos ellos son salidas de alto nivel. sibling(no en el mensaje)
    // ════════════════════════════════════════════════════════════════════════════

    /**
     * v1.0.7: Responses API Implementación de streaming.
     *
     * SSE tipo de evento(Según la especificación API de Respuestas):
     *  - response.output_text.delta:Incremento de texto → ContentDelta
     *  - response.output_text.done:Fin del texto(text Ten todo en cuenta)
     *  - response.reasoning_summary_text.delta:Delta de resumen de razonamiento → ReasoningDelta
     *  - response.function_call_arguments.delta:Incremento del parámetro de llamada de herramienta → ToolCallDelta
     *  - response.output_item.added:Nuevo output item(Como se inicia function_call,Contiene id+name)
     *  - response.completed:final de la corriente(response El campo contiene una matriz de salida final)→ Done
     *  - data: [DONE]:OpenAI terminador universal → Done
     *
     * C-35: Cambiado de callbackFlow a channelFlow(El canal interno es siempre UNLIMITED,Los parámetros de capacidad no son obligatorios y no se pueden pasar).
     *   callbackFlow El problema de pérdida del chip interno de 64 capacidades es el mismo que el del callbackFlow responsivo(ver streamChatCompletions),
     *   Cambiar a interior ilimitado explícito channel,OkHttp El hilo de devolución de llamada trySend ya no pierde piezas debido a una falla de capacidad total.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    // C-35: channelFlow No acepta parámetro de capacidad(Su canal interno es siempre UNLIMITED,exactamente la semántica requerida)
    private fun streamChatResponses(request: ChatRequest): Flow<ChatStreamEvent> = channelFlow {
        val body = buildResponsesRequestBody(request, stream = true)
        val url = baseUrl() + openAIConfig.responsesPath
        Logger.i("OpenAIProvider", "streamChatResponses: POST ${sanitizeUrl(url)} model=${request.model} msgs=${request.messages.size}")

        fun buildHttpRequest(): Request = Request.Builder()
            .url(url)
            // v1.0.18: ir modelo libre fallback(El usuario no completó la clave + modelo de lista blanca de SiliconFlow → Usar incorporado key)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply { requestHeadersFor(request.sessionId).forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var httpRequest = buildHttpRequest()
        val scope = this@channelFlow
        val requestStartAt = System.currentTimeMillis()
        var firstByteAt = 0L
        var firstDeltaAt = 0L

        val retryCount = AtomicInteger(0)
        val anyDeltaSent = AtomicBoolean(false)
        val responseTextSent = AtomicBoolean(false)
        // Responses API Acumulación de llamadas de herramientas:output_index → mapeo de índice local
        val toolCallIndexMap = mutableMapOf<String, Int>()
        var nextToolCallIndex = 0
        // output_item.added La información inicial de function_call transportada por el evento.(id+name)
        val pendingFunctionCalls = mutableMapOf<String, Pair<String, String>>() // item_id → (call_id, name)
        // v1.0.20: stream-guard — Acumule el nombre/argumentos/banderas enviadas de tool_call,
        //   Se utiliza para interceptar llamadas de herramientas no válidas con nombres vacíos y volver a ellas al finalizar ContentDelta
        val toolCallAccMap = mutableMapOf<Int, ToolCallAccState>()
        // v1.0.21: Evitar que emitDoneWithStreamGuard se ejecute dos veces
        val streamGuardDone = AtomicBoolean(false)
        val consumerClosed = AtomicBoolean(false)

        /**
         * v1.0.20: stream-guard — Cheque acumulado antes del evento Listo toolCallAccMap,
         *   Devierte una llamada de herramienta no válida con nombre vacío pero argumentos para ContentDelta(texto visible).
         *   Simétrico al emitDoneWithStreamGuard de la ruta ChatCompletions.
         */
        fun emitDoneWithStreamGuard(finishReason: String?) {
            if (!streamGuardDone.compareAndSet(false, true)) {
                // Reparación de auditoría (7.1): Sólo segunda convocatoria close(),Listo no se volverá a enviar.
                // Implementación original trySend(Done) + close(),Comportamiento inconsistente con la rama Finalizaciones de chat,
                // El consumidor podrá finalizar anticipadamente tras recibir dos Dones./Repita el proceso.
                close()
                return
            }
            toolCallAccMap.forEach { (localIndex, acc) ->
                // v1.0.22: Saltar restaurado incrementalmente acc,Evite el envío duplicado
                if (acc.recoveredAsContent) return@forEach
                if (acc.name.isNullOrBlank() && acc.args.isNotEmpty()) {
                    val recoveredText = acc.args.current()
                    Logger.w(
                        "OpenAIProvider",
                        "stream-guard(Responses): interceptar vacío name tool call (localIndex=$localIndex, args=${acc.args.length} chars)," +
                            "volver al texto: ${recoveredText.take(50)}",
                    )
                    trySend(ChatStreamEvent.ContentDelta(recoveredText))
                }
            }
            toolCallAccMap.clear()
            trySend(ChatStreamEvent.Done(finishReason))
            close()
        }

        val currentEventSource = AtomicReference<EventSource?>(null)
        val connectionGeneration = AtomicInteger(0)
        fun cancelCurrentEventSource() {
            connectionGeneration.incrementAndGet()
            currentEventSource.getAndSet(null)?.cancel()
        }
        fun installEventSource(eventSource: EventSource) {
            // newEventSource() starts its OkHttp Call before returning. If abort/collector
            // cancellation races that return, publish the source atomically and compensate by
            // cancelling it after publication when the request is already no longer active.
            val previous = currentEventSource.getAndSet(eventSource)
            if (previous != null && previous !== eventSource) previous.cancel()
            if (request.abortSignal.aborted || consumerClosed.get() || scope.isClosedForSend) {
                currentEventSource.compareAndSet(eventSource, null)
                eventSource.cancel()
            }
        }
        val abortListener = request.abortSignal.addAbortListener {
            // Cancel the active SSE connection immediately; all callbacks observe aborted
            // and therefore must not schedule fallback/reconnect work.
            cancelCurrentEventSource()
        }

        fun connect() {
            if (request.abortSignal.aborted || scope.isClosedForSend) {
                // A signal can already be aborted before collection starts. Close the producer
                // instead of entering awaitClose forever with no EventSource to trigger a callback.
                if (request.abortSignal.aborted) close()
                return
            }
            val myGeneration = connectionGeneration.incrementAndGet()
            val eventSource = sseFactory.newEventSource(
                httpRequest,
                object : EventSourceListener() {
                    override fun onOpen(eventSource: EventSource, response: Response) {
                        if (myGeneration != connectionGeneration.get()) return
                        firstByteAt = System.currentTimeMillis()
                        Logger.i("OpenAIProvider", "streamChatResponses TTFB: ${firstByteAt - requestStartAt}ms | url=${sanitizeUrl(url)}")
                        // v2.6.9 recopilación de pruebas: Línea base del encabezado de respuesta durante el establecimiento del flujo normal. Comparación con los registros forenses de onFailure
                        // Puede ver si la puerta de enlace eliminó el tipo de contenido cuando falló.(Invalid content-type: null).
                        Logger.i(
                            "OpenAIProvider",
                            "[FORENSIC] streamChatResponses onOpen | url=${sanitizeUrl(url)} | " +
                                forensicResponseHead(response),
                        )
                        if (!response.isSuccessful) {
                            val code = response.code
                            if (code == 429 && !anyDeltaSent.get() && retryCount.get() < MAX_RETRIES &&
                                !request.abortSignal.aborted && switchToNextKey()
                            ) {
                                Logger.i("OpenAIProvider", "streamChatResponses onOpen 429 Limitación de corriente,Cambiado key,Inténtalo de nuevo ahora")
                                httpRequest = buildHttpRequest()
                                retryCount.incrementAndGet()
                                connectionGeneration.incrementAndGet()
                                eventSource.cancel()
                                scope.launch {
                                    if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                        connect()
                                    }
                                }
                                return
                            }
                            val errText = ProviderHttpSupport.readBodySafely(response)
                            val msg = parseErrorMessage(code, errText)
                            Logger.w("OpenAIProvider", "streamChatResponses onOpen HTTP $code: $msg")
                            if (code == 401 || code == 403) {
                                markKeyFailed(hardBlock = true)
                            }
                            trySend(
                                ChatStreamEvent.Error(
                                    msg,
                                    OpenAIHttpException(
                                        code,
                                        msg,
                                        ProviderError.parseRetryAfter(response.header("Retry-After")),
                                    ),
                                ),
                            )
                            close()
                            return
                        }
                    }

                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        if (myGeneration != connectionGeneration.get()) return
                        // Responses API Usar al mismo tiempo [DONE] y respuesta.completada como marca final
                        if (data == "[DONE]") {
                            // v1.0.20: stream-guard — Done Verificar acumulación en evento toolCallAccMap,
                            //   Una llamada de herramienta con un nombre vacío vuelve a ContentDelta
                            emitDoneWithStreamGuard(null)
                            return
                        }

                        var parseError: Throwable? = null
                        val event = resultOf {
                            AppJson.decodeFromString<ResponsesStreamEvent>(data)
                        }.onError { _, throwable -> parseError = throwable }.getOrNull()
                        if (event == null) {
                            val error = malformedStreamFrameError("OpenAI Responses", data, parseError)
                            Logger.w("OpenAIProvider", error.message, parseError)
                            trySend(error)
                            close()
                            return
                        }

                        if (firstDeltaAt == 0L && (event.delta != null || event.text != null)) {
                            firstDeltaAt = System.currentTimeMillis()
                            Logger.i(
                                "OpenAIProvider",
                                "streamChatResponses first delta: ${firstDeltaAt - requestStartAt}ms " +
                                    "(TTFB=${firstByteAt - requestStartAt}ms) | url=${sanitizeUrl(url)}",
                            )
                        }

                        when (event.type) {
                            "response.output_text.delta" -> {
                                event.delta?.takeIf { it.isNotEmpty() }?.let {
                                    anyDeltaSent.set(true)
                                    responseTextSent.set(true)
                                    trySend(ChatStreamEvent.ContentDelta(it))
                                }
                            }
                            "response.output_text.done" -> {
                                // Some gateways omit delta events and only send the final text.
                                if (!responseTextSent.get()) {
                                    event.text?.takeIf { it.isNotBlank() }?.let {
                                        responseTextSent.set(true)
                                        anyDeltaSent.set(true)
                                        trySend(ChatStreamEvent.ContentDelta(it))
                                    }
                                }
                            }
                            "response.reasoning_summary_text.delta" -> {
                                event.delta?.takeIf { it.isNotEmpty() }?.let {
                                    anyDeltaSent.set(true)
                                    trySend(ChatStreamEvent.ReasoningDelta(it))
                                }
                            }
                            "response.function_call_arguments.delta" -> {
                                // Incremento del parámetro de llamada de herramienta,Acumular por item_id
                                val itemId = event.item_id ?: return
                                val localIndex = toolCallIndexMap.getOrPut(itemId) { nextToolCallIndex++ }
                                anyDeltaSent.set(true)
                                // v1.0.20: stream-guard — Acumular nombre y argumentos.
                                //   name de output_item.added(puede estar vacío),args Incremento de este evento.
                                val acc = toolCallAccMap.getOrPut(localIndex) { ToolCallAccState() }
                                // La primera película viene con id+name(Si output_item.added está almacenado en caché);Las películas posteriores no se repetirán.
                                if (!acc.hasEmitted) {
                                    val (callId, name) = pendingFunctionCalls.remove(itemId) ?: ("" to "")
                                    if (callId.isNotEmpty()) acc.id = callId
                                    if (name.isNotBlank()) acc.name = name
                                }
                                event.delta?.let { acc.args.append(it) }

                                // v1.0.22: Restaurado incrementalmente a ContentDelta acc,Los argumentos posteriores continúan enviándose como ContentDelta
                                if (acc.recoveredAsContent) {
                                    val argsDelta = event.delta.orEmpty()
                                    if (argsDelta.isNotEmpty()) {
                                        trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                    }
                                    return
                                }

                                // stream-guard: Manejo de nombres vacíos
                                //   v1.0.20: Almacenar en búfer hasta que esté listo antes de continuar — dando como resultado modelos pequeños"Muestra solo la primera palabra y el final."ilusión
                                //   v1.0.22: Cambiar a incremental inmediato volver a ContentDelta
                                val currentName = acc.name
                                if (currentName.isNullOrBlank()) {
                                    val argsDelta = event.delta.orEmpty()
                                    if (argsDelta.isNotEmpty()) {
                                        acc.recoveredAsContent = true
                                        trySend(ChatStreamEvent.ContentDelta(argsDelta))
                                        Logger.d(
                                            "OpenAIProvider",
                                            "stream-guard(Responses): Restaurar incrementalmente la llamada de la herramienta de nombre vacío a ContentDelta " +
                                                "(localIndex=$localIndex, esta vez=${argsDelta.length} chars, " +
                                                "acumular=${acc.args.length} chars)",
                                        )
                                    } else {
                                        Logger.d(
                                            "OpenAIProvider",
                                            "stream-guard(Responses): herramienta de nombre vacío llamada sin incremento de argumentos,saltar " +
                                                "(localIndex=$localIndex, acumular args=${acc.args.length} chars)",
                                        )
                                    }
                                    return
                                }

                                // name Llegó — enviar ToolCallDelta
                                if (!acc.hasEmitted) {
                                    // Envío por primera vez — Trae el dinero acumulado de una sola vez arguments(ponerse al día)
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = localIndex,
                                            id = acc.id,
                                            name = currentName,
                                            argumentsDelta = acc.args.current(),
                                        ),
                                    )
                                    acc.hasEmitted = true
                                } else {
                                    // incrementos posteriores
                                    trySend(
                                        ChatStreamEvent.ToolCallDelta(
                                            index = localIndex,
                                            id = null,
                                            name = null,
                                            argumentsDelta = event.delta.orEmpty(),
                                        ),
                                    )
                                }
                            }
                            "response.output_item.added" -> {
                                // Nuevo output item,Analizar la información de inicio de function_call
                                val item = event.item ?: return
                                val itemType = (item as? kotlinx.serialization.json.JsonObject)
                                    ?.get("type")?.let { (it as? JsonPrimitive)?.content }
                                if (itemType == "function_call") {
                                    val obj = item as? kotlinx.serialization.json.JsonObject ?: return
                                    val itemId = obj["id"]?.let { (it as? JsonPrimitive)?.content } ?: return
                                    val callId = obj["call_id"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                                    val name = obj["name"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                                    // v1.0.20: stream-guard — Escritura sincrónica accumulator(name puede estar vacío,El delta posterior se juzga en base a esto.)
                                    val localIndex = toolCallIndexMap.getOrPut(itemId) { nextToolCallIndex++ }
                                    val acc = toolCallAccMap.getOrPut(localIndex) { ToolCallAccState() }
                                    if (callId.isNotEmpty()) acc.id = callId
                                    if (name.isNotBlank()) acc.name = name
                                    // Aún conserva la compatibilidad pendiente de FunctionCalls con rutas antiguas(args delta La primera película intentará tomar)
                                    pendingFunctionCalls[itemId] = callId to name
                                }
                            }
                            "response.completed" -> {
                                // final de la corriente,con objeto de respuesta final
                                val status = event.response?.status
                                // Responses API usar status=incomplete + incomplete_details.reason expresar
                                // La salida alcanza el límite superior; el motivo específico se transmite de forma transparente a la capa superior; de lo contrario, la interfaz de usuario solo se puede ver incompleta.
                                // No se puede avisar al usuario“Respuesta truncada debido al límite de longitud”.
                                val finishReason = event.response?.incompleteDetails?.reason ?: status
                                // A5: La respuesta final lleva usage(Valor real) — Emitido antes de hecho,
                                // lado del consumidor(StreamRunState)Acumular la última aparición no nula
                                event.response?.usage?.let { usage ->
                                    trySend(ChatStreamEvent.UsageDelta(usage.toUsageTokens()))
                                }
                                // B5-03: Extraer la firma del razonamiento del resultado final/encrypted_content Para reproducción de biblioteca
                                val reasoningItem = event.response?.output?.firstOrNull { it.type == "reasoning" }
                                if (reasoningItem != null &&
                                    (!reasoningItem.id.isNullOrBlank() || !reasoningItem.encryptedContent.isNullOrBlank())
                                ) {
                                    trySend(
                                        ChatStreamEvent.ReasoningDelta(
                                            "",
                                            signature = reasoningItem.id,
                                            encryptedContent = reasoningItem.encryptedContent,
                                        ),
                                    )
                                }
                                if (!responseTextSent.get()) {
                                    responsesOutputText(event.response ?: ResponsesResult())
                                        .takeIf { it.isNotBlank() }
                                        ?.let {
                                            responseTextSent.set(true)
                                            anyDeltaSent.set(true)
                                            trySend(ChatStreamEvent.ContentDelta(it))
                                        }
                                }
                                if (request.nativeWebSearch) {
                                    val urls = event.response?.output.orEmpty()
                                        .flatMap { it.content.orEmpty() }
                                        .flatMap { it.annotations }
                                        .mapNotNull { it.url }
                                        .filter { it.startsWith("https://") || it.startsWith("http://") }
                                        .distinct()
                                    if (urls.isNotEmpty()) trySend(ChatStreamEvent.CitationDelta(urls))
                                }
                                // v1.0.20: stream-guard — Done Verificar acumulación en evento toolCallAccMap,
                                //   Una llamada de herramienta con un nombre vacío vuelve a ContentDelta
                                emitDoneWithStreamGuard(finishReason)
                            }
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        if (myGeneration != connectionGeneration.get()) {
                            if (request.abortSignal.aborted) {
                                scope.trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar"))
                                close()
                            } else if (consumerClosed.get() || scope.isClosedForSend) {
                                close()
                            }
                            return
                        }
                        // v1.0.23: Igual que la ruta ChatCompletions,Se activa cuando no se recibe el evento Listo stream-guard
                        if (!streamGuardDone.get()) {
                            Logger.d("OpenAIProvider", "streamChatResponses onClosed: Evento hecho no recibido, gatillo stream-guard")
                            emitDoneWithStreamGuard(null)
                        } else {
                            close()
                        }
                    }

                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        // v2.6.9 recopilación de pruebas: Debe colocarse antes de todas las sucursales.
                        // La implementación original solo se lee después de cruzar la rama de reintento. response?.code,encabezado de respuesta/cuerpo nunca registrado,
                        // resultando en okhttp-sse `Invalid content-type: null`(Ninguna respuesta en absoluto Content-Type)
                        // No se puede localizar. Registro puro:No consumir/No cerrar el cuerpo de la respuesta,No hay cambios en el flujo de control.
                        logResponsesFailureForensics(t, response)
                        if (myGeneration != connectionGeneration.get()) {
                            if (request.abortSignal.aborted) {
                                scope.trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar", t))
                                close()
                            } else if (consumerClosed.get() || scope.isClosedForSend) {
                                close()
                            }
                            return
                        }
                        if (streamGuardDone.get() || consumerClosed.get() || scope.isClosedForSend) {
                            Logger.d("OpenAIProvider", "streamChatResponses onFailure: La transmisión se ha completado o el consumidor se ha cerrado.,Ignorar devoluciones de llamada de cierre")
                            close()
                            return
                        }
                        if (request.abortSignal.aborted) {
                            Logger.d("OpenAIProvider", "streamChatResponses aborted by user")
                            trySend(ChatStreamEvent.StreamInterrupted("El usuario ha dejado de generar", t))
                            close()
                            return
                        }
                        val code = response?.code ?: -1
                        // Responses API Alinear finalizaciones de chat: 408/429/5xx/El error de red aún no se ha generado
                        // Es posible reintentar al incrementar y se respeta el reintento posterior.
                        if (shouldRetryResponsesFailure(
                                code = response?.code,
                                anyDeltaSent = anyDeltaSent.get(),
                                retryCount = retryCount.get(),
                                maxRetries = MAX_RETRIES,
                                aborted = request.abortSignal.aborted,
                            )
                        ) {
                            val attempt = retryCount.incrementAndGet()
                            var backoffMs = (RETRY_BASE_DELAY_MS * (1 shl (attempt - 1))) +
                                Random.nextLong(0, 200)
                            if (response?.code == 429) {
                                backoffMs = ProviderError.parseRetryAfter(response.header("Retry-After"))
                                    ?.let { it * 1000L } ?: backoffMs
                                if (switchToNextKey()) backoffMs = 0L
                            }
                            // R-AI-03: Borre el grupo de conexiones medio abierto antes de volver a intentarlo/Conexión inactiva
                            ProviderHttpSupport.evictIdleConnections()
                            Logger.w(
                                "OpenAIProvider",
                                "streamChatResponses onFailure, retry $attempt/$MAX_RETRIES after ${backoffMs}ms: ${t?.message ?: code}",
                            )
                            connectionGeneration.incrementAndGet()
                            scope.launch {
                                delay(backoffMs)
                                if (!request.abortSignal.aborted && !scope.isClosedForSend) {
                                    connect()
                                }
                            }
                            return
                        }
                        val msg = response?.let {
                            if (it.code in 200..299) {
                                t?.message?.takeIf { m -> m.isNotBlank() }
                                    ?: ErrorCode.STREAM_INTERRUPTED.toMessage()
                            } else {
                                val bodyText = ProviderHttpSupport.readBodySafely(it)
                                parseErrorMessage(it.code, bodyText)
                            }
                        } ?: (t?.message ?: ErrorCode.NETWORK_ERROR.toMessage())
                        Logger.e("OpenAIProvider", "streamChatResponses onFailure: $msg", t)
                        // v1.0.15: Enviado cuando se ha recibido parte del contenido StreamInterrupted,Deje que la interfaz de usuario conserve el contenido recibido y solicite la interrupción de la red(Puede reconectarse automáticamente)
                        if (anyDeltaSent.get()) {
                            trySend(ChatStreamEvent.StreamInterrupted(msg, t))
                        } else {
                            trySend(ChatStreamEvent.Error(msg, t))
                        }
                        close()
                    }
                },
            )
            installEventSource(eventSource)
        }

        connect()

        awaitClose {
            abortListener.close()
            // Liberar solo recursos HTTP,No marque el cierre normal como cancelación iniciada por el usuario.
            consumerClosed.set(true)
            cancelCurrentEventSource()
        }
        // C-35: El canal interno ha sido cambiado a channelFlow(capacity = UNLIMITED),trySend No más pérdidas de tabletas debido a la capacidad total.
        //   aguas abajo `.buffer(Channel.UNLIMITED)` Solo es efectivo para el reenvío del siguiente nivel del productor al recolector.,
        //   No se puede solucionar el problema de pérdida del chip interno de 64 capacidades de CallbackFlow(Ha sido solucionado por la capacidad explícita de channelFlow anterior.).
    }.flowOn(Dispatchers.IO).buffer(Channel.UNLIMITED)

    /**
     * v1.0.7: Responses API Implementación sin streaming.
     *
     * analizar output[] matriz,Extracto:
     *  - type="message" de content[].text(Texto visible)
     *  - type="reasoning" de summary(Contenido de razonamiento,Depósito reasoningContent)
     *  - type="function_call" de call_id/name/arguments(llamada de herramienta)
     */
    private suspend fun completeTextResponses(request: ChatRequest, keySwitchDepth: Int = 0): ChatCompletion = withContext(Dispatchers.IO) {
        // v2.6.1: Codex fuerza de fondo stream=true,La no transmisión será 400;Por lo tanto, cambiamos a la agregación de transmisión.
        if (isCodexBackend()) return@withContext completeTextResponsesViaStream(request)
        val body = buildResponsesRequestBody(request, stream = false)
        val url = baseUrl() + openAIConfig.responsesPath
        Logger.i("OpenAIProvider", "completeTextResponses: POST ${sanitizeUrl(url)} model=${request.model}")
        val httpRequest = Request.Builder()
            .url(url)
            // v1.0.18: ir modelo libre fallback(El usuario no completó la clave + modelo de lista blanca de SiliconFlow → Usar incorporado key)
            .header("Authorization", "Bearer ${resolveEffectiveApiKey(request.model.id)}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .apply { requestHeadersFor(request.sessionId).forEach { (k, v) -> header(k, v) } }
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = httpClient.newCall(httpRequest)
        val abortListener = request.abortSignal.addAbortListener { call.cancel() }
        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    if (code == 429 && !request.abortSignal.aborted && keySwitchDepth < MAX_KEY_SWITCHES && switchToNextKey()) {
                        Logger.i("OpenAIProvider", "completeTextResponses 429 Limitación de corriente,Cambiado key,Inténtalo de nuevo ($keySwitchDepth/$MAX_KEY_SWITCHES)")
                        return@withContext completeTextResponses(request, keySwitchDepth + 1)
                    }
                    if (code == 401 || code == 403) {
                        markKeyFailed(hardBlock = true)
                    }
                    val errText = ProviderHttpSupport.readBodySafely(resp)
                    val msg = parseErrorMessage(code, errText)
                    Logger.w("OpenAIProvider", "completeTextResponses HTTP $code: $msg")
                    throw OpenAIHttpException(code, msg, ProviderError.parseRetryAfter(response.header("Retry-After")))
                }
                val raw = resp.body?.string()
                    ?: throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_body", resp.code)
                val parsed = AppJson.decodeFromString<ResponsesResult>(raw)
                val text = extractResponsesVisibleText(parsed)
                val reasoningContent = extractResponsesReasoning(parsed)
                val toolCalls = extractResponsesToolCalls(parsed)
                // B5-03: Extraiga la firma y el contenido cifrado del elemento de razonamiento para múltiples rondas de reproducción.
                val reasoningItem = parsed.output.firstOrNull { it.type == "reasoning" }

                if (text.isBlank() && toolCalls.isNullOrEmpty() && reasoningContent.isBlank()) {
                    // Primero dejemos la estructura: diferentes estaciones de transferencia tienen diferentes formas de organizar la salida de las Respuestas.
                    // Sin la forma original, sólo podemos confiar en conjeturas. Aquí agregamos la ruta del valor y el fragmento de respuesta.
                    val outputTypes = parsed.output.mapNotNull { it.type }.distinct()
                    Logger.w(
                        "OpenAIProvider",
                        "completeTextResponses Volver vacío | outputSize=${parsed.output.size} " +
                            "| outputTypes=$outputTypes | outputTextBlank=${parsed.outputText.isNullOrBlank()} " +
                            "| status=${parsed.status} | rawHead=${raw.take(600)}",
                    )
                    // Cubierta suelta: escanee cualquier nodo en la salida que parezca texto
                    val loose = extractResponsesVisibleTextLoose(parsed)
                    if (loose.isNotBlank()) {
                        Logger.i("OpenAIProvider", "completeTextResponses Golpes de extracción sueltos ${loose.length} personaje")
                        return@withContext ChatCompletion(
                            text = loose,
                            finishReason = parsed.incompleteDetails?.reason ?: parsed.status,
                            toolCalls = null,
                            reasoningContent = null,
                        )
                    }
                    throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_text")
                }
                Logger.d(
                    "OpenAIProvider",
                    "completeTextResponses OK: text=${text.length} chars, " +
                        "reasoning=${reasoningContent.length} chars, toolCalls=${toolCalls?.size ?: 0}",
                )
                ChatCompletion(
                    text = text,
                    // Responses API Lo incompleto es sólo el estado externo, la razón específica del truncamiento está en los detalles.
                    finishReason = parsed.incompleteDetails?.reason ?: parsed.status,
                    toolCalls = toolCalls,
                    reasoningContent = reasoningContent.takeIf { it.isNotBlank() },
                    thinkingSignature = reasoningItem?.id,
                    thinkingEncryptedContent = reasoningItem?.encryptedContent,
                    usageTokens = parsed.usage?.toUsageTokens(),
                    citationUrls = parsed.output.orEmpty()
                        .flatMap { it.content.orEmpty() }
                        .flatMap { it.annotations }
                        .mapNotNull { it.url }
                        .filter { it.startsWith("https://") || it.startsWith("http://") }
                        .distinct(),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        } catch (t: Throwable) {
            if (request.abortSignal.aborted) {
                Logger.d("OpenAIProvider", "completeTextResponses aborted by user")
            } else if (t is OpenAIHttpException) {
                // Errores HTTP registrados
            } else {
                Logger.e("OpenAIProvider", "completeTextResponses anormal", t)
            }
            throw t
        } finally {
            abortListener.close()
            if (request.abortSignal.aborted) call.cancel()
        }
    }

    /**
     * v2.6.1: Codex Implementación equivalente sin transmisión del backend —— Agregado por evento después de la transmisión forzada.
     *
     * Codex Rechazos de backend stream=false,Por lo tanto, el escenario de texto completo se cambia a consumo. [streamChatResponses]
     * Usar juntos [io.zer0.ai.core.ChatEventReducer] agregación,Obtén lo mismo que el modo normal sin transmisión [ChatCompletion].
     */
    private suspend fun completeTextResponsesViaStream(request: ChatRequest): ChatCompletion {
        val reducer = io.zer0.ai.core.ChatEventReducer()
        streamChatResponses(request).collect { reducer.reduce(it) }
        val result = reducer.toResult()
        result.error?.let { message -> throw OpenAIHttpException(0, message) }
        if (result.text.isBlank() && result.toolCalls.isEmpty() && result.reasoning.isNullOrBlank()) {
            throw ErrorCode.INVALID_RESPONSE.toProviderException("empty_text")
        }
        return ChatCompletion(
            text = result.text,
            finishReason = result.finishReason,
            toolCalls = result.toolCalls.takeIf { it.isNotEmpty() },
            reasoningContent = result.reasoning,
            usageTokens = result.usage,
            thinkingSignature = result.thinkingSignature,
            thinkingEncryptedContent = result.thinkingEncryptedContent,
            citationUrls = result.citationUrls,
        )
    }

    /**
     * v1.0.7: Construya el cuerpo de solicitud de la API de respuestas.
     *
     * Reglas de conversión:
     *  - SYSTEM Los mensajes se extraen en el campo de nivel superior de instrucciones.(No incluido en la matriz de entrada)
     *  - USER/ASSISTANT/TOOL Los mensajes se convierten en elementos de matriz de entrada.
     *  - ASSISTANT Las llamadas a herramientas del mensaje se convierten en elementos de llamada de función.(nivel superior sibling,no en el mensaje)
     *  - TOOL Los mensajes se convierten en elementos function_call_output(Utilice toolCallId como call_id)
     *  - Utilizado para contenido multimodal ResponsesInputItem.content(JsonElement)oso
     *
     * Configuración de inferencia:
     *  - reasoningLevel != OFF/AUTO tiempo,estructura reasoning: {effort} campo
     *  - Responses API no hay universalidad“Razonamiento cercano” effort valor;OFF omitido cuando reasoning,
     *    Deje que el upstream lo maneje de acuerdo con su comportamiento predeterminado del protocolo.,Evite asignar incorrectamente OFF a algo que aún desencadene inferencias minimal
     *  - Codex Acuerdo(responsesPath=/codex/responses)fuerza store=false
     *
     * B5-03: REASONING_ITEMS Reproducción(encrypted_content transportista).
     *  OpenAI Responses API Se requiere que el elemento de razonamiento transporte contenido_encriptado para una reproducción completa en las rondas.
     *  cadena de pensamiento(El servidor utiliza cargas útiles cifradas para verificar la integridad de la cadena de pensamiento.),El texto resumido por sí solo no es suficiente. flujo de datos completo:
     *   1. Lado del análisis:streamChatResponses(response.completed)/ completeTextResponses de
     *      output[type=reasoning] tomar encrypted_content + id,Ingresado a través de ReasoningDelta
     *      UIMessage.thinkingEncryptedContent / thinkingSignature.
     *      Nota:El servidor solo responde con solicitudes. `include=["reasoning.encrypted_content"]` Este campo se devuelve sólo cuando,
     *      Por lo tanto, lleve siempre esta carga útil debajo include;De lo contrario, el contenido_encriptado es null,Esta repetición no se activará.
     *   2. lado de la solicitud:Atravesar aquí normalizedMessages,ASSISTANT noticias thinkingEncryptedContent
     *      tiempo no vacío,Insertar antes del elemento del mensaje type="reasoning" de input item
     *      (id + encrypted_content + vacío summary——summary Matriz requerida en la especificación de entrada).
     *  Cuando el campo está vacío(datos antiguos/punto final sin razonamiento/No encendido thinking)mantener completamente el status quo,No inserte este artículo.
     */
    /**
     * v2.x(Registro de solución de problemas): Responses API Determinación de la disposición de un mensaje durante el montaje.
     *
     * fondo: Responses API Rechazar mensajes con contenido vacío (HTTP 400 `Message content cannot be empty`).
     * Una vez que el mensaje del asistente vacío (comúnmente encontrado en la rueda de herramientas o el remanente de la última falla) ingresa al historial,**cada ronda**Ambos 400.
     */
    internal fun classifyResponsesMessage(msg: UIMessage): ResponsesMessageDisposition {
        if (!msg.content.isBlank()) return ResponsesMessageDisposition.KEEP
        val hasToolCalls = msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()
        val hasMedia = msg.imageBase64List.isNotEmpty() || msg.imageUrls.isNotEmpty()
        val hasToolOutput = !msg.toolCallId.isNullOrBlank()
        return when {
            // Asistente vacío con toolCalls: conserva el mensaje (convertido a function_call), pero no genera un mensaje vacío message item
            hasToolCalls -> ResponsesMessageDisposition.SKIP_MESSAGE_ITEM
            // Mensaje vacío con imagen.: El contenido es transportado por input_image y retenido
            hasMedia -> ResponsesMessageDisposition.KEEP
            // Mensaje de resultado de la herramienta (function_call_output): El contenido lo transporta call_id y se retiene.
            hasToolOutput -> ResponsesMessageDisposition.KEEP
            // Un mensaje verdaderamente vacío y sin contenido.: Saltar en general
            else -> ResponsesMessageDisposition.SKIP
        }
    }

    private fun buildResponsesRequestBody(request: ChatRequest, stream: Boolean = true): String {
        val effectiveModel = effectiveModelId(request.model.id)
        val normalizedMessages = ProviderPayloadNormalizer.normalizeMessages(
            request.messages,
            request.model,
        )
        // v1.0.7: compat derivado(Responses API camino,Se utiliza para la determinación de ProviderPromptPatches)
        val compat: ProviderCompat = config.resolvedCompat(effectiveModel)
        // v1.0.7: UTILITY Patrón de pensamiento forzado
        val effectiveReasoningLevel = if (request.mode == ChatRequestMode.UTILITY) {
            io.zer0.ai.core.ReasoningLevel.OFF
        } else {
            request.reasoningLevel
        }

        // Extracto system → instructions;Los mensajes restantes se transfieren a elementos de entrada.
        val instructionsBuilder = StringBuilder()
        val inputItems = mutableListOf<ResponsesInputItem>()
        for (msg in normalizedMessages) {
            if (msg.role == MessageRole.SYSTEM) {
                // SYSTEM Mensaje fusionado en instructions(Varias líneas separadas por saltos de línea)
                if (instructionsBuilder.isNotEmpty()) instructionsBuilder.append("\n\n")
                instructionsBuilder.append(msg.content)
            } else {
                // v2.x(Registro de solución de problemas): Los mensajes de contenido vacío no se pueden ingresar en Respuestas message item ——
                // API será directo 400 `Message content cannot be empty`. Este tipo de mensaje de asistente vacío
                // A menudo utilizado por la rueda de herramientas (content Vacío, solo llamadas a herramientas) o restos del fallo anterior,
                // Una vez que ingreses al historial, podrás**cada ronda**Ambos 400 (bucles infinitos autorreproductores) deben tratarse durante la fase de montaje.
                //
                // Ver las normas de tramitación [classifyResponsesMessage](v2.x Solución de problemas de registro):
                //  - Asistente vacío con llamadas a herramientas: aún debe conservarse (convertido a function_call a continuación),
                //    Pero no se puede reconstruir con un contenido vacío. message item —— Por lo tanto, sólo se omite el elemento del mensaje.
                //  - Un mensaje completamente vacío sin ningún contenido.: Saltar en general.

                val hasToolCalls = msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()
                val disposition = classifyResponsesMessage(msg)
                if (disposition == ResponsesMessageDisposition.SKIP) {
                    Logger.w(
                        "OpenAIProvider",
                        "buildResponsesRequestBody: Saltar mensajes puros y vacíos(Responses No acepta vacio content) | role=${msg.role}",
                    )
                    continue
                }
                val skipMessageItem = disposition == ResponsesMessageDisposition.SKIP_MESSAGE_ITEM
                // B5-03: OpenAI Responses Múltiples rondas de reproducción de pensamiento. — Insertar antes del mensaje del asistente reasoning item
                if (msg.role == MessageRole.ASSISTANT && !msg.thinkingEncryptedContent.isNullOrBlank()) {
                    inputItems.add(
                        ResponsesInputItem(
                            type = "reasoning",
                            id = msg.thinkingSignature,
                            encrypted_content = msg.thinkingEncryptedContent,
                            // input El elemento de razonamiento estándar requiere que el resumen sea una matriz requerida;
                            // El fragmento estructurado original no se conserva por separado,Rellene la matriz vacía según el ejemplo de reproducción oficial.
                            summary = buildJsonArray { },
                        ),
                    )
                }
                if (!skipMessageItem) {
                    inputItems.add(msg.toResponsesInputItem(request.model))
                }
                // ASSISTANT Las llamadas a herramientas del mensaje se convierten al nivel superior de function_call sibling
                if (msg.role == MessageRole.ASSISTANT && !msg.toolCalls.isNullOrEmpty()) {
                    msg.toolCalls.forEach { tc ->
                        inputItems.add(
                            ResponsesInputItem(
                                type = "function_call",
                                call_id = tc.id,
                                name = tc.name,
                                arguments = tc.arguments,
                            ),
                        )
                    }
                }
            }
        }

        // v1.0.7: Provider Prompt Patches — Agregar al final de las instrucciones.
        //  (Responses API El mensaje del sistema toma el campo de instrucciones de nivel superior.,No ingrese a la matriz de entrada)
        //  UTILITY modo effectiveReasoningLevel=OFF,ProviderPromptPatches La inyección se omitirá internamente
        val promptPatches = ProviderPromptPatches.getProviderPromptPatches(
            model = request.model,
            baseUrl = baseUrl(),
            thinkingFormat = compat.thinkingFormat,
            reasoningLevel = effectiveReasoningLevel,
        )
        for (patch in promptPatches) {
            if (instructionsBuilder.isNotEmpty()) instructionsBuilder.append("\n\n")
            instructionsBuilder.append(patch)
        }

        val instructions = instructionsBuilder.toString().takeIf { it.isNotBlank() }

        // Configuración de inferencia:
        // Responses API No existe un valor de esfuerzo de desactivación/desactivado común entre los proveedores.
        // OFF Omitir la configuración de razonamiento cuando,evitar poner“Cerrar pensamiento profundo”error asignado a minimal
        // (minimal Todavía es posible permitir la inferencia.);El protocolo ascendente determina si el flujo ascendente específico admite el apagado explícito.
        val effort = when (effectiveReasoningLevel) {
            io.zer0.ai.core.ReasoningLevel.AUTO -> null
            io.zer0.ai.core.ReasoningLevel.OFF -> null
            else -> effectiveReasoningLevel.effort
        }
        val reasoning = effort?.let { ResponsesReasoningConfig(effort = it) }

        // v2.6.1: Codex aplicación del protocolo store=false.
        // El juicio se cambia a baseUrl + ResponsesPath doble condición. —— Codex Por defecto, la sección del códice se coloca en baseUrl
        // (https://chatgpt.com/backend-api/codex),responsesPath Sigue siendo el predeterminado /responses;
        // Si la implementación anterior solo analiza la ruta de respuestas, no realizará el juicio.,Solicitud sin campo de tienda,De vuelta aguas arriba 400
        // "Store must be set to false"(Pruebas de usuarios reales).
        val isCodex = isCodexBackend()
        val store = if (isCodex) false else null

        val functionTools = request.tools?.mapNotNull { it.toOpenAISafely() }
            ?.distinctBy { it.function.name }
        val responseTools = buildList {
            if (request.nativeWebSearch) add(ResponsesTool(type = "web_search_preview"))
            functionTools.orEmpty().forEach { tool ->
                add(
                    ResponsesTool(
                        type = "function",
                        name = tool.function.name,
                        description = tool.function.description,
                        parameters = tool.function.parameters,
                    ),
                )
            }
        }.takeIf { it.isNotEmpty() }
        val payload = ResponsesRequest(
            model = effectiveModel,
            input = inputItems,
            instructions = instructions,
            stream = stream,
            max_output_tokens = request.maxTokens?.takeIf { it > 0 },
            // v2.6.1: Codex El backend no acepta temperature(voluntad 400 "Unsupported parameter: temperature"),
            // Omita este campo al presionar Codex,Los puntos finales de Respuestas restantes permanecen como están.
            temperature = request.temperature.takeIf { !isCodex },
            tools = responseTools,
            tool_choice = request.toolChoice?.takeIf { !functionTools.isNullOrEmpty() },
            reasoning = reasoning,
            store = store,
            // B5-03: Solicitar encrypted_content,De lo contrario, el elemento de razonamiento no tiene soporte de reproducción.
            include = listOf("reasoning.encrypted_content"),
        )
        return AppJson.encodeToString(payload)
    }

    /**
     * v1.0.7: UIMessage → ResponsesInputItem.
     *
     * Responses API La estructura del elemento de entrada es diferente del elemento de mensajes de Finalizaciones de chat.:
     *  - type="message"(noticias generales),role + content
     *  - type="function_call"(assistant llamada de herramienta,Construido por separado por buildResponsesRequestBody)
     *  - type="function_call_output"(tool Relleno de mensajes,call_id + output)
     *
     * content campo:
     *  - texto plano:string(Transmisión directa JsonPrimitive)
     *  - multimodal:JsonArray([{type:"input_text", text}, {type:"input_image", image_url}])
     *    (Nota:Responses API usar input_text / input_image,en lugar de finalizar el chat text / image_url)
     */
    private fun UIMessage.toResponsesInputItem(model: Model): ResponsesInputItem {
        // TOOL transferencia de mensajes function_call_output(Resultados de la herramienta de relleno)
        // v2.6.7 Reparación: Los nombres de los campos para los resultados de la herramienta son `output` en vez de `content`.
        // La implementación anterior utiliza contenido y el esquema del servidor informa. 422 `input: missing field 'output'`,
        // La actuación es "tan pronto como se ejecuta la herramienta y se devuelve el resultado, la ronda inevitablemente fallará".
        if (role == MessageRole.TOOL) {
            return ResponsesInputItem(
                type = "function_call_output",
                call_id = toolCallId,
                output = JsonPrimitive(content),
            )
        }

        val roleStr = when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
            MessageRole.TOOL -> "user" // Ten todo en cuenta(Teóricamente no vendrá aquí.)
        }

        val contentElement = if (imageBase64List.isEmpty() || !model.supportsVisionInput()) {
            if (imageBase64List.isNotEmpty() && !model.supportsVisionInput()) {
                Logger.w(
                    "OpenAIProvider",
                    "toResponsesInputItem: modelo ${model.id} Sin soporte visual,descartar ${imageBase64List.size} fotos(filtrado defensivo)",
                )
            }
            JsonPrimitive(content)
        } else {
            // multimodal:Responses API usar input_text / input_image(Tenga en cuenta que es diferente del texto/imagen_url de Finalizaciones de chat.)
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "input_text")
                        put("text", content)
                    },
                )
                val validImages = imageBase64List.filter { it.isNotEmpty() }
                if (validImages.size > MAX_VISION_IMAGES) {
                    Logger.w("OpenAIProvider", "Número de fotos ${validImages.size} Superar el límite superior $MAX_VISION_IMAGES,descartar el exceso")
                }
                validImages.take(MAX_VISION_IMAGES).forEach { b64 ->
                    if (b64.length > MAX_IMAGE_BASE64_LEN) {
                        Logger.w("OpenAIProvider", "Longitud de la imagen base64 ${b64.length} exceder $MAX_IMAGE_BASE64_LEN,descartar")
                        return@forEach
                    }
                    val mimeType = inferMimeType(b64)
                    add(
                        buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:$mimeType;base64,$b64")
                        },
                    )
                }
            }
        }

        return ResponsesInputItem(
            type = "message",
            role = roleStr,
            content = contentElement,
        )
    }

    /**
     * v1.0.7: Extraiga el cuerpo visible de ResponsesResult.
     *
     * prioridad:
     *  1. Campo de texto_salida de nivel superior(Si no está vacío, úselo directamente.)
     *  2. output[] en type="message" de content[].text(type="output_text" o "text")
     *
     * no puede ser de type="reasoning" Extraer texto del artículo
     */
    private fun extractResponsesVisibleText(result: ResponsesResult): String {
        result.outputText?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return result.output
            .filter { it.type == "message" }
            .flatMap { it.content ?: emptyList() }
            .filter { it.type == "output_text" || it.type == "text" }
            .mapNotNull { it.text?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * Extracción de texto relajada: una solución cuando no se puede encontrar la ruta estándar.
     *
     * Item.type y content.type no están limitados, sólo se reconocen "nodos con campos de texto".
     * Solía ser compatible con estaciones de transferencia que han ajustado la estructura de Respuestas (por ejemplo, escribir contenido directamente como una cadena,
     * O cambie el tipo a una variante como output_text_delta).
     *
     * Solo se llama cuando la ruta estándar está vacía, por lo que no cambia los resultados del análisis de las respuestas normales.
     */
    private fun extractResponsesVisibleTextLoose(result: ResponsesResult): String {
        val direct = result.outputText?.takeIf { it.isNotBlank() }
        if (direct != null) return direct.trim()
        return result.output
            .flatMap { item -> item.content ?: emptyList() }
            .mapNotNull { c -> c.text?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * v1.0.7: Extraer contenido de inferencia de ResponsesResult(reasoning item de summary).
     */
    private fun extractResponsesReasoning(result: ResponsesResult): String {
        return result.output
            .filter { it.type == "reasoning" }
            .flatMap { it.summary?.let { s -> parseSummaryTexts(s) } ?: emptyList() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /** v1.0.7: Analizar el campo de resumen del elemento de razonamiento(JsonElement,Puede ser una matriz o una cadena). */
    private fun parseSummaryTexts(summary: JsonElement): List<String> {
        return when (summary) {
            is kotlinx.serialization.json.JsonPrimitive -> listOf(summary.content)
            is kotlinx.serialization.json.JsonArray -> summary.mapNotNull { el ->
                (el as? kotlinx.serialization.json.JsonObject)
                    ?.get("text")?.let { (it as? JsonPrimitive)?.content }
            }
            else -> emptyList()
        }
    }

    /**
     * v1.0.7: Llamado desde la herramienta de extracción ResponsesResult.
     *
     * output[] en type="function_call" Los artículos contienen call_id / name / arguments.
     */
    private fun extractResponsesToolCalls(result: ResponsesResult): List<ToolCall>? {
        val calls = result.output
            .filter { it.type == "function_call" }
            .mapNotNull { item ->
                val callId = item.callId ?: return@mapNotNull null
                val name = item.name ?: return@mapNotNull null
                ToolCall(id = callId, name = name, arguments = item.arguments.orEmpty())
            }
        return calls.takeIf { it.isNotEmpty() }
    }

    private companion object {
        // L-OAI12: Quitar charset=utf-8(OpenAI / Las transferencias generalmente se procesan como UTF-8 application/json,
        //   Y OkHttp decodifica la aplicación/json en UTF-8 de forma predeterminada.;Conjunto de caracteres explícito bajo algunos relés
        //   En cambio, fue estrictamente verificado. 415).
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // M-OAI3: Parámetros de reconexión de streaming
        const val MAX_RETRIES = 3
        const val RETRY_BASE_DELAY_MS = 1000L

        // completeText 429 Número máximo de veces para cambiar de clave,Prevenir la recursividad infinita
        const val MAX_KEY_SWITCHES = 3

        /**
         * issue #5(Opción C): El número de reintentos por malformación del protocolo.
         *
         * periódico modelo finish_reason=tool_calls Pero cuando no hay una herramienta_call válida,
         * Por primera vez, el razonamiento no se trata como el texto principal (para evitar fugas de pensamiento), sino que se pide a la capa superior que lo reenvíe;
         * Sólo cuando todavía estaba deformado volvió al fondo del bolsillo (never-empty). Tómate 1 vez: más fichas se quemarán en vano.
         * Las anomalías del protocolo a menudo son causadas por nerviosismo puntual y la mayoría se puede recuperar después de la retransmisión.
         */
        const val MAX_MALFORMED_TOOLCALL_RETRIES = 1

        /** Umbral de tormenta de llamada de herramienta de nombre nulo:Después de exceder, restaurar según el incremento del texto.,Evite el almacenamiento en búfer infinito que ralentiza la transmisión. */
        const val MAX_EMPTY_NAME_TOOL_CALLS = 10

        // M-OAI8: Vision Restricciones de imagen
        const val MAX_VISION_IMAGES = 4
        const val MAX_IMAGE_BASE64_LEN = 2 * 1024 * 1024 // 2MB

        /**
         * v2.6.1: Codex Backend/codex/modelos Requerido client_version.
         *
         * El servidor verificará este valor.:Falta o es demasiado bajo devuelve una lista de modelos vacía(No se reportó ningún error),
         * Como resultado, fallaron tanto la conexión de prueba como el modelo extraído. La versión evoluciona con el cliente oficial.,Haga un seguimiento después de que el funcionario eleve el umbral.
         */
        const val CODEX_CLIENT_VERSION = "1.0.0"

        /** v2.6.1: Codex Solicitado User-Agent(Identifica la familia de la persona que llama,No pretendas ser oficial CLI). */
        const val CODEX_USER_AGENT = "muse/1.0 (Android; OpenAI Codex)"
    }
}

/**
 * L-OAI11: OpenAI HTTP Excepción de error,Llevar código de estado,Juicio de prefijo de cadena alternativo.
 *
 * v1.0.27 Phase 5-A: heredar en su lugar [ProviderException],Dejar pasar al consumidor.
 * `(throwable as? ProviderException)?.providerError` Obteniendo un error escrito.
 */
internal fun openAiHtmlEndpointHint(contentType: String, body: String): String? {
    val prefix = body.trimStart().take(256).lowercase()
    val isHtml = contentType.contains("text/html", ignoreCase = true) ||
        prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("<head")
    if (!isHtml) return null
    return "The server returned an HTML page instead of OpenAI-compatible JSON. " +
        "NewAPI base URLs usually need the /v1 path; check the API base URL, proxy route, and web-protection page."
}

internal class OpenAIHttpException(
    val code: Int,
    message: String,
    retryAfterSec: Int? = null,
) : ProviderException(
    providerError = when {
        // v2.6.8: 402 / insufficient_quota —— Saldo insuficiente. Debe ser juzgado antes de InvalidRequest,
        // De lo contrario, se guiará al usuario para que ajuste los parámetros (este es exactamente el tipo de engaño en la captura de pantalla del usuario).
        code == 402 -> ProviderError.InsufficientQuota(httpCode = code, displayMessage = message)
        ProviderError.isInsufficientQuotaBody(message) ->
            ProviderError.InsufficientQuota(httpCode = code, displayMessage = message)
        code == 401 || code == 403 -> ProviderError.AuthError(httpCode = code, displayMessage = message)
        code == 400 || code == 413 || code == 422 || code == 404 ->
            ProviderError.InvalidRequest(httpCode = code, displayMessage = message)
        code == 429 -> ProviderError.RateLimit(httpCode = code, retryAfterSec = retryAfterSec, displayMessage = message)
        code in 500..599 -> ProviderError.ServerError(httpCode = code, displayMessage = message)
        else -> ProviderError.Unknown(httpCode = code, displayMessage = message)
    },
)

/**
 * v2.x: descubrir"Nombrados, los parámetros aún están vacíos y aún no se han emitido ToolCallDelta"La llamada se utiliza como destino de referencia de la continuación del parámetro sin nombre.
 *
 * Devolver nulo en ambos casos significa**no puedo adivinar**:
 *  - No hay candidatos (es posible que los parámetros aún no hayan comenzado a llegar);
 *  - Hay varios candidatos (varias llamadas en paralelo están esperando parámetros y es imposible determinar a quién pertenece la secuela).
 * Es mejor mantener el comportamiento original (omitir el segmento y alertar) que pasar parámetros a la llamada incorrecta.
 */
internal fun pendingArgsCandidate(accMap: Map<Int, ToolCallAccState>): Int? =
    accMap.filterValues { acc -> !acc.name.isNullOrBlank() && acc.args.length == 0 && !acc.hasEmitted }
        .keys
        .singleOrNull()

/** Un nombre de función en blanco es sólo una continuación de la estación de transferencia; sólo una identificación o un nombre de función que no esté vacío abrirá una nueva llamada lógica. */
internal fun isNewToolCall(id: String?, name: String?): Boolean = !id.isNullOrBlank() || !name.isNullOrBlank()

internal data class ToolCallIndexAssignment(
    val localIndex: Int?,
    val nextLocalIndex: Int,
)

/**
 * Resolve a stable local index for one streamed provider index.
 *
 * A provider may repeat the function name on later fragments. Reusing the
 * existing API-index mapping prevents those fragments from becoming new
 * pseudo-calls with empty or incomplete arguments.
 */
internal fun assignToolCallLocalIndex(
    apiIndex: Int,
    id: String?,
    name: String?,
    indexMap: MutableMap<Int, Int>,
    nextLocalIndex: Int,
): ToolCallIndexAssignment {
    indexMap[apiIndex]?.let { existing ->
        return ToolCallIndexAssignment(existing, nextLocalIndex)
    }
    if (!isNewToolCall(id, name)) {
        return ToolCallIndexAssignment(null, nextLocalIndex)
    }
    indexMap[apiIndex] = nextLocalIndex
    return ToolCallIndexAssignment(nextLocalIndex, nextLocalIndex + 1)
}

/**
 * Seleccione la propiedad de la continuación sin parámetro id/nameless.
 *
 * La estación de transferencia podrá dividir los parámetros de una misma llamada en nuevos índices consecutivos. Dale prioridad al último que aún no esté cerrado.
 * llamada nombrada de ; sólo acepta contexto único si no hay pedido"El primero aún no ha sido emitido. ToolCallDelta"candidato.
 */
internal fun continuationTarget(accMap: Map<Int, ToolCallAccState>, activeLocalIndex: Int?, incomingName: String? = null): Int? {
    val active = activeLocalIndex?.let { index ->
        accMap[index]?.takeIf { acc ->
            !acc.name.isNullOrBlank() &&
                !acc.recoveredAsContent &&
                !acc.args.isValidJson() &&
                (incomingName.isNullOrBlank() || acc.name == incomingName)
        }?.let { index }
    }
    return when {
        active != null -> active
        !incomingName.isNullOrBlank() ->
            accMap
                .filterValues { acc ->
                    acc.name == incomingName &&
                        !acc.recoveredAsContent &&
                        !acc.args.isValidJson()
                }
                .keys
                .singleOrNull()
        else -> pendingArgsCandidate(accMap)
    }
}

/**
 * v1.0.20: stream-guard acumulador — Acumule si se han enviado el nombre/argumentos/de una única llamada_herramienta.
 *
 * adoptar `invalidToolCalls` Mecanismo de amortiguación:
 *  - el nombre puede estar vacío en la transmisión(El modelo pequeño genera vacío. name tool call),Los argumentos almacenados en el buffer no se envían en este momento ToolCallDelta
 *  - Si el nombre llega en delta posterior,Al enviar por primera vez traer el acumulado arguments(ponerse al día)
 *  - Si el nombre sigue vacío hasta el evento Listo,Convierta los argumentos acumulados a ContentDelta y restaure el texto visible
 *
 * Seguridad del hilo:EventSource Activador de devolución de llamada en serie(hilo único),No se requieren primitivas de sincronización.
 */
internal class ToolCallAccState {
    /** llamada de herramienta id(El primer trozo lleva). */
    var id: String? = null

    /** nombre de la función(Acumular el primer valor no nulo;Done Si sigue siendo nulo/en blanco, se considerará inválido. tool call). */
    var name: String? = null

    /** argumentos acumulados (v1.0.81: Fusione de forma inteligente varios fragmentos de objetos JSON para evitar empalmar cadenas). */
    val args: ToolCallArgsAccumulator = ToolCallArgsAccumulator()

    /** ¿Se ha enviado aguas abajo? ToolCallDelta(Se utiliza para enviar mensajes de actualización cuando el nombre llega tarde). */
    var hasEmitted: Boolean = false

    /**
     * v1.0.22: ¿Se ha restaurado como un delta ContentDelta?(Herramienta de nombre vacío llamada al modo de recuperación incremental).
     *
     * v1.0.20 El almacenamiento en búfer en modo Listo tiene serios problemas de experiencia:GLM-4-9B-0414 Espere hasta que el modelo pequeño
     * Todo el texto se divide en una gran cantidad de espacios de índice independientes. name tool call(cada uno 1-4 chars),
     * stream-guard Almacene todo en buffer hasta que esté listo y luego restáurelo todo de una vez,El usuario solo vio 1 palabra y pensó que había terminado y presionó detener.
     *
     * v1.0.22 Cambiar a recuperación incremental:Si el nombre todavía está vacío cuando llegan los primeros argumentos,Enviar inmediatamente como ContentDelta,
     * Los argumentos posteriores se envían de forma incremental directamente,Evite el almacenamiento en búfer"Parece que solo se emite y termina la primera palabra."espejismo.
     *
     * El nombre de una llamada de herramienta normal suele aparecer en la primera pieza.(OpenAI normativo),Si la primera película no tiene nombre, básicamente se puede juzgar como
     * Salida anormal del modelo pequeño,La recuperación incremental directa a texto es razonable.
     */
    var recoveredAsContent: Boolean = false
}
