package io.zer0.ai

import io.zer0.ai.anthropic.AnthropicProvider
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.ChatRequest
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelOutputPolicy
import io.zer0.ai.core.Provider
import io.zer0.ai.core.ProviderCompat
import io.zer0.ai.core.ProviderCompatRules
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.ai.core.ReasoningLevel
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.ai.core.firstEventTimeoutMs
import io.zer0.ai.core.withFirstEventWatchdog
import io.zer0.ai.gemini.GeminiProvider
import io.zer0.ai.openai.OpenAIProvider
import io.zer0.ai.registry.ModelRegistry
import io.zer0.common.ErrorCode
import io.zer0.common.Logger
import io.zer0.common.toMessage
import kotlinx.coroutines.flow.Flow

/**
 * Provider Registro. Según [ProviderConfig.type] Correspondencia dinámica de construcción. [Provider] Ejemplo.
 *
 * v1.80 (M-CORE9): Agregar caché simple — prensa config.id + apiKey + baseUrl Instancia de proveedor de caché de huellas dactilares,
 * Evite reconstruir objetos pesados ​​como OkHttpClient/VertexAiAuthToken en cada solicitud.
 * config Falla y reconstruye automáticamente cuando cambia el contenido.
 */
object ProviderRegistry {

    private data class CacheKey(val configId: String, val fingerprint: String)
    private val cache = java.util.concurrent.ConcurrentHashMap<CacheKey, Provider>()

    fun create(config: ProviderConfig): Provider {
        // huella digital:id + type + baseUrl + apiKey después4Bit + específico + parámetro de límite de corriente P1-3,El cambio significa fracaso
        // P1-3: Los parámetros limitantes actuales se incorporan a las huellas dactilares,Asegúrese de que el caché se invalide y se reconstruya después de que el usuario modifique RPM/concurrencia decorator
        val fp = "${config.type}|${config.resolvedBaseUrl()}|${config.apiKey.takeLast(4)}|${config.specific}" +
            "|rpm=${config.requestLimitPerMinute}|conc=${config.maxConcurrentRequests}"
        val key = CacheKey(config.id, fp)
        return cache.getOrPut(key) {
            val base = when (config.type) {
                ProviderType.OPENAI -> OpenAIProvider(config)
                ProviderType.ANTHROPIC -> AnthropicProvider(config)
                ProviderType.GEMINI -> GeminiProvider(config)
                // v1.0.6: OPENAI_RESPONSES reutilización temporal OpenAIProvider(Internamente, cambia aspecific.useResponseApi /v1/responses)
                ProviderType.OPENAI_RESPONSES -> OpenAIProvider(config)
            }
            // P1-3: Cualquier parámetro limitante actual > 0 superposición de tiempo RateLimitDecorator(RPM Ventana deslizante + concurrencia Semaphore)
            // decorator es control previo,con KeyRoulette(Interruptor de llave trasero 429)complementario;Ambos pueden superponerse y coexistir.
            if (config.requestLimitPerMinute > 0 || config.maxConcurrentRequests > 0) {
                io.zer0.ai.decorator.RateLimitDecorator(
                    delegate = base,
                    requestLimitPerMinute = config.requestLimitPerMinute,
                    maxConcurrentRequests = config.maxConcurrentRequests,
                )
            } else {
                base
            }
        }
    }

    /** borrar todo el caché(Eliminar configuración/Llamado al reiniciar). */
    fun clearCache() {
        cache.clear()
    }
}

/**
 * v2.4.5: Constante de normalización de la temperatura de muestreo en la salida.
 *
 * El rango de temperatura permitido dentro de la aplicación es [0, 2],Pero algunos proveedores abandonan la gama.(Como Shangtang [0.0, 2.0)).
 * Aquí hay un límite inferior general./límite superior,[ProviderCompat.maxTemperatureExclusive] El límite superior se puede ajustar aún más.
 */
private const val MIN_TEMPERATURE = 0f
private const val MAX_TEMPERATURE = 2f

/** El margen que queda cuando el límite superior exclusivo se estrecha,Garantizado para caer dentro del rango abierto. */
private const val TEMPERATURE_EPSILON = 0.0001f

/**
 * v2.4.5: La temperatura de muestreo en la salida está normalizada.
 *
 * El rango permitido dentro de la aplicación es [0, 2],Pero algunos proveedores abandonan la gama.(Como Shangtang [0.0, 2.0)),
 * por [ProviderCompat.maxTemperatureExclusive] declaración. El valor original será transmitido de forma transparente por el upstream como
 * 400 "field Temperature invalid" rechazar,Ajuste uniforme aquí.
 */
internal fun normalizeTemperature(temperature: Float?, compat: ProviderCompat): Float? {
    val raw = temperature ?: return null
    val upper = compat.maxTemperatureExclusive?.let { it - TEMPERATURE_EPSILON } ?: MAX_TEMPERATURE
    return raw.coerceIn(MIN_TEMPERATURE, upper)
}

/**
 * Decide whether the request should carry tool definitions.
 *
 * Provider model-list endpoints frequently omit capability metadata for custom
 * or relay models. An empty ability set therefore means "unknown", not
 * "unsupported"; only an explicit known capability set without TOOL should
 * suppress tools.
 *
 * v2.6.1: distinguir“No se admite la declaración explícita”con“No declarado”—— Estación de transferencia de reparación/El directorio de la nube solo escribe reasoning
 *   Cuando falta herramientaUtilizar(Como por ejemplo deepseek-flash,abilities=[REASONING])La cadena de herramientas está completamente
 *   Elimine el problema de que el modelo solo puede mostrar llamadas a herramientas como texto normal.
 *   prioridad(de mayor a menor):
 *     1) Modelos editados explícitamente por el usuario.(userEdited)→ Estrictamente basado en las habilidades del usuario.,
 *        Publique si los usuarios dicen que lo apoyan, pero no publique si dicen que es solo un razonamiento. —— La configuración del usuario tiene la máxima prioridad.
 *     2) abilities Contiene TOOL → Enviar.
 *     3) el resto(No declarado/solo declarado RAZONAMIENTO, etc.)→ Déjelo en manos de la matriz de capacidad del proveedor para la decisión final.,
 *        solo matriz**explícito**Sentencia no sustentada(Como por ejemplo deepseek-reasoner / o1-mini)Simplemente suprímelo.
 *
 * v2.1.0: Suprimir rutas y mantener registros uniformemente(El descarte silencioso una vez provocó que toda la cadena de herramientas del Agente fallara silenciosamente).
 */
internal fun shouldSendTools(model: Model, config: ProviderConfig, tools: List<ToolDefinition>?): Boolean {
    if (tools.isNullOrEmpty()) return false
    val sends =
        when {
            // 1) Capacidad para que los usuarios editen explícitamente → La configuración de usuario más alta.,Sujeto a su declaración.
            model.userEdited -> ModelAbility.TOOL in model.abilities
            // 2) Declarar explícitamente herramientas compatibles → Enviar.
            ModelAbility.TOOL in model.abilities -> true
            // 3) No declarado/parcialmente declarado → Decisión final de la matriz de compatibilidad;desconocido ≠ No compatible.
            else ->
                ProviderCompatRules.resolve(
                    providerType = config.type,
                    baseUrl = config.resolvedBaseUrl(),
                    modelId = model.id,
                ).supportsToolCalling
        }
    if (!sends) {
        Logger.w(
            "ChatService",
            "Herramienta no enviada: modelo ${model.id} Juzgado para no apoyar function calling | " +
                "abilities=${model.abilities}, userEdited=${model.userEdited}, " +
                "verification=${model.verification}, " +
                "discardedTools=${tools.size}, provider=${config.id}",
        )
    }
    return sends
}

/**
 * Una entrada unificada para que la capa de aplicación llame a AI.
 *
 * Diseñado para ser apátrida:Cada llamada reconstruye el proveedor según la última configuración.
 * Toda la lógica pública se concentra en [buildProviderRequest] para eliminar la duplicación entre streamChat y completeText.
 */
class ChatService(
    private val configStore: ProviderConfigStore,
) {
    /**
     * Charla en streaming. Delegue en Provider.streamChat.
     * @see [buildProviderRequest] Responsable de la prelógica pública.
     */
    suspend fun streamChat(
        messages: List<UIMessage>,
        model: Model? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        tools: List<ToolDefinition>? = null,
        toolChoice: String? = null,
        nativeWebSearch: Boolean = false,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.CHAT,
        resumeFromText: String? = null,
        topP: Float? = null,
        sessionId: String? = null,
    ): Flow<ChatStreamEvent> {
        // C-11 ②: La capacidad de reanudar la transferencia está vinculada a la capacidad del proveedor. — Sólo si el Proveedor declara admiteResumeFromText,
        // Sólo entonces trate el texto generado como"mensaje final del asistente"inyectar,Dejemos que el modelo continúe donde lo dejó.
        // Los proveedores que no admiten transferencias de currículum malinterpretarán/Volver a responder un turno de asistente truncado,Desactivar la reanudación automática en este momento,
        // Déjelo en manos del LCP de la capa UI para la deduplicación.(duplicateRemaining)Hacerse cargo de la deduplicación,En lugar de enviar mensajes parciales al modelo.
        val effectiveMessages = if (!resumeFromText.isNullOrBlank() &&
            supportsResumeFromText(providerConfig, model)
        ) {
            messages + UIMessage(role = MessageRole.ASSISTANT, content = resumeFromText)
        } else {
            messages
        }
        val (provider, request) = buildProviderRequest(
            effectiveMessages,
            model,
            temperature,
            maxTokens,
            tools,
            toolChoice,
            nativeWebSearch,
            reasoningLevel,
            providerConfig,
            mode,
            topP,
            sessionId,
        )
        // B3-01: SSE No hay un primer evento durante mucho tiempo después de la creación.(Predeterminado 45s,razonamiento/Archivo de contexto muy largo 90s),Bajar automáticamente a no transmisión y volver a intentarlo una vez
        // v2.x: 15s→45s — La primera señal del modelo lento suele ser después de los 15 segundos.,Una rebaja prematura da como resultado“Aparece todo el párrafo”+ doble espera
        // Reparación de auditoría (7.8): Ya no cuando el usuario se ha detenido. fallback(Guardar una solicitud de facturación)
        return provider.streamChat(request).withFirstEventWatchdog(
            timeoutMs = model?.firstEventTimeoutMs() ?: 45_000L,
            fallback = { provider.completeText(request) },
            abortCheck = { request.abortSignal.aborted },
        )
    }

    /**
     * C-11: Consulta [ProviderCompatRules] Determine si el proveedor admite la transmisión de currículum resumeFromText.
     *
     * prensa providerType + baseUrl(host) + modelId Tres capas de análisis(Matriz de capacidad construida de manera consistente con los parámetros del cuerpo de la solicitud).
     * Provider Regresar de manera conservadora cuando no esté declarado true,El comportamiento es el mismo que antes de conectar el control de puerta de reanudación.
     */
    private suspend fun supportsResumeFromText(providerConfig: ProviderConfig?, model: Model?): Boolean {
        // providerConfig La persona que llama transmitida explícitamente generalmente accede directamente;Reserva predeterminada configStore(Mismo origen que buildProviderRequest).
        // Si no se puede obtener la configuración, se permitirá que la transferencia se reanude automáticamente.:Si no se libera, se omitirá silenciosamente la descarga del currículum.,Más insidioso que el peligro de posibles respuestas repetidas,Deje que la matriz de capacidades del proveedor se encargue de todo.
        val config = providerConfig ?: runCatching { configStore.get() }.getOrNull() ?: return true
        return ProviderCompatRules.resolve(config.type, config.resolvedBaseUrl(), model?.id).supportsResumeFromText
    }

    /**
     * Chat sin transmisión. Devolver resultados completos de una sola vez,Adecuado para compilación de memoria,fact Tareas en segundo plano como la extracción.
     * @see [buildProviderRequest] Responsable de la prelógica pública.
     *
     * v1.0.7: Predeterminado [mode]=[ChatRequestMode.UTILITY](callText codificado utility),
     *   Las tareas en segundo plano pueden cerrarse automáticamente sin pasar explícitamente el modo. La ruta del diálogo del usuario debe ajustarse. [streamChat].
     */
    suspend fun completeText(
        messages: List<UIMessage>,
        model: Model? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        tools: List<ToolDefinition>? = null,
        toolChoice: String? = null,
        nativeWebSearch: Boolean = false,
        reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.UTILITY,
        topP: Float? = null,
        sessionId: String? = null,
    ): ChatCompletion {
        val (provider, request) = buildProviderRequest(
            messages,
            model,
            temperature,
            maxTokens,
            tools,
            toolChoice,
            nativeWebSearch,
            reasoningLevel,
            providerConfig,
            mode,
            topP,
            sessionId,
        )
        return provider.completeText(request)
    }

    /**
     * [streamChat] y [completeText] prelógica pública.
     * Obtener configuración de configStore,modelo analítico,crear Provider,Construya ChatRequest.
     * La extracción de ambos métodos elimina la necesidad de mantener 15 líneas duplicadas de código repetitivo.
     */
    private suspend fun buildProviderRequest(
        messages: List<UIMessage>,
        model: Model?,
        temperature: Float?,
        maxTokens: Int?,
        tools: List<ToolDefinition>?,
        toolChoice: String?,
        nativeWebSearch: Boolean,
        reasoningLevel: ReasoningLevel,
        providerConfig: ProviderConfig? = null,
        mode: ChatRequestMode = ChatRequestMode.CHAT,
        topP: Float? = null,
        sessionId: String? = null,
    ): Pair<Provider, ChatRequest> {
        val config = providerConfig ?: configStore.get()
            ?: error(ErrorCode.NO_PROVIDER_CONFIGURED.toMessage())
        // v2.2.1: El respaldo preferido cambió a la tienda declarada"El modelo seleccionado actualmente por el usuario."(La implementación predeterminada nula mantiene el comportamiento anterior).
        // Originalmente tomado directamente config.models.firstOrNull():Al activar el Proveedor el primer modelo no está disponible/Cuando no está en el modelo conversacional,
        // Todas las tareas en segundo plano sin pasar el modelo.(mensajes no solicitados/diario/periódico diario/Traducción, etc.)Ejecutará silenciosamente el modelo incorrecto.
        val resolvedModel = model ?: configStore.getPreferredModel() ?: config.models.firstOrNull()
            ?: error(ErrorCode.NO_MODEL_SELECTED.toMessage())
        // Phase 2C:Capacidades de adaptación automática del modelo a través de ModelRegistry
        val enhancedModel = ModelRegistry.enhanceModel(resolvedModel)
        // Personalización de capacidades no declaradas./El modelo de tránsito no puede considerarse“Herramientas no compatibles”;Déjelo en manos de la matriz de capacidades del proveedor para que lo juzgue.
        val effectiveTools = tools.takeIf { shouldSendTools(enhancedModel, config, it) }
        val estimatedInputTokens = ModelOutputPolicy.estimateInputTokens(messages, effectiveTools)
        val regularMaxTokens = ModelOutputPolicy.resolve(maxTokens, enhancedModel)
        val effectiveMaxTokens = ModelOutputPolicy.resolveForContext(
            requestedMaxTokens = maxTokens,
            model = enhancedModel,
            inputTokens = estimatedInputTokens,
        )
        if (effectiveMaxTokens != regularMaxTokens || ModelOutputPolicy.wasClamped(maxTokens, enhancedModel)) {
            Logger.w(
                "ChatService",
                "Los presupuestos de producción se han visto ajustados por las capacidades del modelo.: provider=${config.id}, model=${enhancedModel.id}, " +
                    "requested=$maxTokens, inputTokens≈$estimatedInputTokens, " +
                    "modelLimit=${enhancedModel.maxOutputTokens}, contextWindow=${enhancedModel.contextWindow}, " +
                    "effective=$effectiveMaxTokens",
            )
        }
        val provider = ProviderRegistry.create(config)
        // v2.4.5: Obtener una matriz de capacidades,Para la normalización de la temperatura.(Mismo calibre que resolveCompat dentro del proveedor).
        val compat = config.resolvedCompat(enhancedModel.id)
        // v1.0.7: UTILITY Patrón de pensamiento forzado
        //  Cobertura unificada en la capa ChatService reasoningLevel=OFF,todos Provider(OpenAI/Anthropic/Gemini)
        //  Entra en vigor automáticamente,No es necesario determinar repetidamente el modo dentro de cada Proveedor.
        val effectiveReasoningLevel = if (mode == ChatRequestMode.UTILITY) {
            ReasoningLevel.OFF
        } else {
            reasoningLevel
        }
        // v2.4.5: Normalización de temperatura de salida — Cierra en la misma posición que topP.
        //  El límite superior del control deslizante de temperatura en la aplicación es 2,0 y el valor de importación de tarjetas de personajes de terceros puede ser arbitrario.,Algunos proveedores son
        //  temperature irse rango(Como Shangtang [0.0, 2.0),2.0 ilegal).
        val effectiveTemperature = normalizeTemperature(temperature, compat)
        val request = ChatRequest(
            messages = messages,
            model = enhancedModel,
            temperature = effectiveTemperature,
            maxTokens = effectiveMaxTokens,
            tools = effectiveTools,
            toolChoice = toolChoice
                ?.takeIf { it == "auto" || it == "required" || it == "none" }
                ?.takeIf { effectiveTools != null },
            // Las búsquedas nativas solo las realizan los proveedores que admiten explícitamente su protocolo; otros tipos fallan cerrados,
            // Evite que los campos google_search/web_search_preview se envíen a la capa de compatibilidad por error, provocando errores 400.
            nativeWebSearch = nativeWebSearch && config.type in setOf(
                ProviderType.GEMINI,
                ProviderType.OPENAI_RESPONSES,
            ),
            reasoningLevel = effectiveReasoningLevel,
            mode = mode,
            topP = topP?.coerceIn(0f, 1f),
            sessionId = sessionId,
        )
        return Pair(provider, request)
    }
}

/** Provider Abstracción de acceso a la configuración,Implementado por módulo de aplicación basado en DataStore. */
interface ProviderConfigStore {
    /** Configuración del proveedor actualmente efectiva,null Indica no configurado. */
    suspend fun get(): ProviderConfig?

    /** v1.54: Lista de todos los proveedores configurados(para incorporar la selección del proveedor). La lista predeterminada es vacía. */
    suspend fun getAllProviders(): List<ProviderConfig> = emptyList()

    /**
     * v2.2.1: Reserva preferida cuando la tarea en segundo plano no especifica explícitamente un modelo(normalmente regresa"El modelo seleccionado actualmente por el usuario.").
     *
     * [ChatService.buildProviderRequest] Modelo de cadena de tres niveles.:
     * La persona que llama pasa explícitamente → [getPreferredModel] → Activa el primer modelo del Proveedor.
     * La implementación predeterminada devuelve nulo para mantener el comportamiento anterior.(Sólo afecta a los dobles de prueba no anulados).
     */
    suspend fun getPreferredModel(): Model? = null
}
