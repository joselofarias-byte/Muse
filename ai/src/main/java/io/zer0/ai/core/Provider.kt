package io.zer0.ai.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Eventos incrementales generados durante la inferencia de transmisión.
 * UI La capa consume estos eventos para actualizar el contenido de las burbujas, el proceso de razonamiento y las indicaciones de error.
 */
sealed class ChatStreamEvent {
    /**
     * incremento del proceso de razonamiento(o1 / thinking modelo).
     *
     * v1.80 (M-ANT4): [signature] Llevando la firma del bloque de pensamiento Antrópico(Opcional),
     * Se utiliza para la reproducción de conversaciones de pensamiento de múltiples rondas.(signature_delta evento emitido).
     * Para modelos no pensantes/otros proveedores como OpenAI, este campo es nulo.
     * lado del consumidor(Como por ejemplo ChatViewModel)debe ser depositado [UIMessage.thinkingSignature],
     * y devolverlo a la siguiente ronda de solicitudes Anthropic(De lo contrario, múltiples rondas de pensamiento fracasarán.).
     */
    data class ReasoningDelta(
        val delta: String,
        val signature: String? = null,
        /** B5-03: OpenAI Responses reasoning item de encrypted_content(Tecla de reproducción). */
        val encryptedContent: String? = null,
    ) : ChatStreamEvent()

    /** Incremento de texto. */
    data class ContentDelta(val delta: String) : ChatStreamEvent()

    /**
     * Phase 8.6: Incremento de imagen(Gemini Dibujo devuelto inlineData).
     *
     * @param imageBase64 Datos de imagen base64(Ninguno dato: prefijo)
     * @param mimeType MIME Tipo,Como por ejemplo "image/png"
     */
    data class ImageDelta(val imageBase64: String, val mimeType: String = "image/png") : ChatStreamEvent()

    /**
     * Incremento de llamada de herramienta(Phase 7 function calling).
     *
     * OpenAI en protocolo de streaming,tool_calls es una matriz,Cada elemento tiene index/id/function{name/arguments}.
     * La misma llamada a herramienta se puede enviar a través de múltiples fragmentos de SSE(arguments fragmentación),Debe acumularse según el índice.
     *
     * @param index El índice de la llamada a la herramienta en la matriz.(por acumulación)
     * @param id llamada de herramienta id(El primer trozo lleva,Los fragmentos siguientes son null)
     * @param name nombre de la función(El primer trozo lleva,Los fragmentos siguientes son null)
     * @param argumentsDelta Parámetro fragmento delta JSON
     * @param isSnapshot v1.0.81: true Indica que argumentsDelta es una instantánea completa de los parámetros.(en lugar de incremental),
     *   Los consumidores deberían reemplazar en lugar de agregar. Utilizado por el modelo para dividir argumentos en múltiples fragmentos de objetos JSON completos,
     *   Escenario en el que el proveedor se ha fusionado en el origen(Como por ejemplo DeepSeek V4).
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
        val isSnapshot: Boolean = false,
    ) : ChatStreamEvent()

    /** Una ronda termina normalmente,Con motivo de parada opcional. */
    data class Done(val finishReason: String? = null) : ChatStreamEvent()

    /** error,La transmisión fue interrumpida. */
    data class Error(val message: String, val throwable: Throwable? = null) : ChatStreamEvent()

    /**
     * v1.0.15: Conexión de transmisión interrumpida(conmutación de red/Background Doze/servidor desconectado a mitad de camino),Pero se ha recibido algo de contenido.
     *
     * con [Error] la diferencia:
     *  - [Error]: Aún no he recibido nada(anyDeltaSent=false)simplemente falla,UI Se deben mostrar los errores y permitir reintentos.
     *  - [StreamInterrupted]: Parte del contenido ha sido recibido.(anyDeltaSent=true)Después de que se interrumpe la conexión,
     *    UI El contenido recibido debe conservarse y solicitarse."Corte de red,Tratando de restaurar",Se vuelve a conectar automáticamente después de la recuperación de la red
     *
     * lado del consumidor(ChatViewModel)debería:
     *  1. No borrar elementos recolectados ContentDelta/ReasoningDelta,Conserva algunas de las respuestas generadas
     *  2. mostrar"Corte de red"Consejos(error no fatal,No mostrar cuadro de diálogo de error)
     *  3. monitorear NetworkMonitor,Una vez restaurada la red, la solicitud se reiniciará y generará automáticamente nuevamente.
     */
    data class StreamInterrupted(
        val message: String,
        val throwable: Throwable? = null,
    ) : ChatStreamEvent()

    /**
     * B3-01: SSE No hay un primer evento durante mucho tiempo después de la creación.,Degradado automáticamente a reintentos sin transmisión.
     * UI Esto se puede utilizar para mostrar recordatorios únicos.
     */
    data class FallbackNotice(val message: String) : ChatStreamEvent()

    /**
     * A5: Uso de token durante la transmisión(provider Valor real).
     *
     * Cada proveedor analiza y emite el evento de uso antes del final de la transmisión.:
     *  - OpenAI Responses: response.completed campo de evento/uso(necesidad stream_options.include_usage)
     *  - Anthropic: message_delta.usage(salida token) + message_start.message.usage(entrada token)
     *  - Gemini: del último fragmento de SSE usageMetadata
     *
     * Se puede enviar varias veces(Como el aporte antrópico/La salida se divide en dos),El consumidor debe tomar la última(cantidad total).
     * provider Este evento no se enviará cuando no se devuelva el uso.(El consumidor vuelve a la estimación local).
     */
    data class UsageDelta(val usage: UsageTokens) : ChatStreamEvent()

    /** Provider URL de referencia devueltas por búsquedas nativas; El orden permanece estable con los marcadores de citas en el texto. */
    data class CitationDelta(val urls: List<String>) : ChatStreamEvent()
}

/**
 * v1.0.7: Modo de solicitud de chat(mode: "chat" | "utility").
 *
 * - [CHAT]:Ruta de diálogo del usuario,reasoningLevel por preferencia del usuario/session decidir,Se puede activar o desactivar
 * - [UTILITY]:Ruta de generación de texto breve de fondo(memory Resumen/extracción de hechos/ayuda visual/ejecución de habilidades /
 *   Compresión de contexto/mensajes activos/tareas programadas, etc.),fuerza reasoningLevel = OFF
 *   (La producción corta no requiere pensamiento en cadena + ahorra tokens + reduce la latencia),sin inyectar ProviderPromptPatches
 *
 * principios de diseño:
 *  - UTILITY modo,Provider Fuerza internamente el nivel de razonamiento efectivo a OFF,
 *    El nivel de razonamiento pasado por la persona que llama se sobrescribe(utility inyección de patrón reasoningLevel: "off")
 *  - Cada proveedor determina si debe omitir el pensamiento. Inyección de formato según el modo al crearRequestBody
 */
enum class ChatRequestMode {
    CHAT,
    UTILITY,
}

/**
 * Un parámetro de entrada para una solicitud de chat.
 *
 * @param messages Historial completo de conversaciones(Contiene system / user / assistant),recortado por la persona que llama
 * @param model modelo objetivo
 * @param temperature Temperatura de muestreo,null Indica que el servidor utiliza de forma predeterminada
 * @param maxTokens Límite superior de salida,null Indica que el servidor utiliza de forma predeterminada
 * @param abortSignal Bandera de cancelación en poder de la persona que llama,Interrumpe la transmisión lo antes posible después de establecerla como verdadera.
 * @param tools Lista opcional de definiciones de herramientas(Phase 7 function calling),null Indica que la llamada a herramientas no está habilitada
 * @param toolChoice Estrategia de selección de herramientas("auto" / "required" / "none"),null Indica que lo maneja el Proveedor de forma predeterminada.
 * @param reasoningLevel nivel de razonamiento(Phase 8.1 M11),null para expresar [ReasoningLevel.DEFAULT]
 *   Cada Proveedor se basa en [Model.supportsReasoning] y este campo determina si se envía el campo de pensamiento
 *   v1.0.7: cuando [mode]=[ChatRequestMode.UTILITY] tiempo,Este campo se ve obligado a anularse como OFF
 * @param mode v1.0.7: modo de solicitud,Predeterminado [ChatRequestMode.CHAT];
 *   UTILITY Patrón de pensamiento forzado(memory Resumen/extracción de hechos/asistencia visual y otras tareas de fondo)
 */
data class ChatRequest(
    val messages: List<UIMessage>,
    val model: Model,
    val temperature: Float? = null,
    /** presionado [ModelOutputPolicy.resolve] Un límite superior de la salida restringido a las capacidades del modelo. */
    val maxTokens: Int? = null,
    val abortSignal: AbortSignal = AbortSignal(),
    val tools: List<ToolDefinition>? = null,
    val toolChoice: String? = null,
    /** Búsqueda de red nativa: implementada por un Proveedor específico utilizando su propio protocolo de herramienta de búsqueda. */
    val nativeWebSearch: Boolean = false,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.DEFAULT,
    val mode: ChatRequestMode = ChatRequestMode.CHAT,
    /** Probabilidad del núcleo de muestreo,Pasado por la configuración topP de nivel asistente; null El valor predeterminado lo proporciona el proveedor/servidor. */
    val topP: Float? = null,
    /**
     * v2.6.2: Identificación de la sesión actual. Para que las estaciones de retransmisión fuercen los encabezados de sesión (como OpenCode Go)
     * `x-opencode-session`) obtiene un valor estable por sesión; puede ser nulo cuando la tarea en segundo plano no tiene contexto de sesión,
     * En este momento por [RelayRequiredHeaders] Degenera en valor estable de granularidad del proveedor.
     */
    val sessionId: String? = null,
)

/**
 * Definición de herramienta(Provider representación intermedia irrelevante).
 *
 * @param name nombre de la función
 * @param description Descripción(LLM Decide si llamar basándose en esto)
 * @param parametersJsonSchema Cadena de esquema JSON para el parámetro(OpenAI Formatos compatibles)
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

/** Señales de cancelación colaborativas.UI punto"detenerse"tiempo [aborted] Establecer verdadero. */
class AbortSignal {
    @Volatile
    var aborted: Boolean = false
        private set

    private val lock = Any()
    private val listeners = LinkedHashSet<() -> Unit>()

    /**
     * El número de oyentes que todavía están registrados actualmente, solo para completar la limpieza de aserciones de las pruebas de regresión del ciclo de vida del módulo ai.
     */
    internal val listenerCount: Int
        get() = synchronized(lock) { listeners.size }

    /**
     * Registre una devolución de llamada de cancelación única. El identificador devuelto se puede cerrar cuando se completa la solicitud, evitando retener el recurso HTTP subyacente.
     * Si la señal ha sido cancelada, la devolución de llamada se ejecutará inmediatamente y no se guardará.
     */
    fun addAbortListener(listener: () -> Unit): AutoCloseable {
        var invokeImmediately = false
        synchronized(lock) {
            if (aborted) invokeImmediately = true else listeners += listener
        }
        if (invokeImmediately) listener()
        return AutoCloseable { synchronized(lock) { listeners.remove(listener) } }
    }

    fun abort() {
        val pending: List<() -> Unit>
        synchronized(lock) {
            if (aborted) return
            aborted = true
            pending = listeners.toList()
            listeners.clear()
        }
        pending.forEach { listener -> runCatching { listener() } }
    }
}

/**
 * El resultado de una llamada sin transmisión.memory Se utiliza para tareas en segundo plano, como módulos. [completeText] obtener texto completo,
 * No es necesario consumir eventos incrementales gradualmente.
 *
 * Phase 7: [toolCalls] Resultados de la invocación de herramientas para escenarios sin transmisión.
 *
 * v1.80 (M-OAI8): Nuevo [reasoningContent] campo,Llevar el contenido de razonamiento del modelo de razonamiento.
 * modelo de inferencia parcial(o1/DeepSeek-R1 espera)En las respuestas que no son de transmisión solo pueden regresar reasoning_content
 * Y el contenido está vacío,En este momento, la persona que llama debe juzgar si la respuesta es válida en función de esto.,Evite falsos positivos"texto vacío"Error.
 */
data class ChatCompletion(
    val text: String,
    val finishReason: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val reasoningContent: String? = null,
    /** v1.0.53 Phase 3: token Dosis(prompt + completion + reasoning total).null Indica que el flujo ascendente no ha regresado. */
    val usageTokens: UsageTokens? = null,
    /** B5-03: thinking firma(Anthropic / OpenAI reasoning item id). */
    val thinkingSignature: String? = null,
    /** B5-03: OpenAI Responses reasoning encrypted_content. */
    val thinkingEncryptedContent: String? = null,
    /** URL de referencia devuelta por la búsqueda nativa. */
    val citationUrls: List<String> = emptyList(),
)

/**
 * v1.0.53 Phase 3: token Detalles de dosificación(Alinear los campos de uso de OpenAI).
 *
 * Provider Cuando no se devuelve el uso, es null,AgentTokenBudget Saltar acumulación(Evite juzgar erróneamente el agotamiento).
 *
 * A5: Nuevo [cachedTokens] — prompt token de caché. Sólo para visualización de información y estimación de costos.,No contado [total]
 * (Mantener sin cambios la semántica del presupuesto existente:Anthropic El token de caché no participa originalmente en la acumulación de consumo.).
 *
 * lectura de caché/escribe dividir:Los precios unitarios de los dos son diferentes.(Escribir suele ser más caro),Mezclarlo en un campo hará que la estimación del costo omita la parte escrita,
 * Entonces se lee una memoria caché de columna explícita [cachedTokens] escribir con caché [cacheWriteTokens].
 */
data class UsageTokens(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** razonamiento token(Algunos modelos se incluyen por separado.,Como OpenAI o1 completion_tokens_details.reasoning_tokens). */
    val reasoningTokens: Int = 0,
    /**
     * prompt caché**leer** token.
     *
     * El nombre del campo sigue siendo el mismo que antes de la división. [cachedTokens] ser compatible con los sitios de llamadas existentes y las columnas de persistencia,La semántica está fijada como "leída".:
     *  - OpenAI `prompt_tokens_details.cached_tokens`
     *  - Gemini `usageMetadata.cachedContentTokenCount`
     *  - Anthropic `usage.cache_read_input_tokens`
     *
     * Sólo para fines de visualización y fijación de precios.,No participar [total].
     */
    val cachedTokens: Int = 0,
    /**
     * prompt caché**escribir** token(Anthropic `usage.cache_creation_input_tokens`).
     *
     * OpenAI / Gemini Ningún elemento de facturación de escritura en caché independiente,Siempre 0.
     * El precio unitario de las escrituras en caché suele ser más alto que el de las entradas normales.(Anthropic 5 El TTL por minuto es 1,25 veces el precio de entrada),
     * debe incluirse por separado al calcular el precio,no se puede utilizar con [cacheReadTokens] Mézclalo. Sólo para fines de visualización y fijación de precios.,No participar [total].
     */
    val cacheWriteTokens: Int = 0,
    /**
     * [promptTokens] Ya sea**contiene**parte de caché——Es decir, el calibre rápido de fijación de precios del proveedor.
     *
     * Los dos acuerdos no prevén lo mismo.,Si se equivoca, el cálculo se revertirá.:
     *  - OpenAI / Gemini:`prompt_tokens` / `promptTokenCount` **Contiene**Parte de golpe de caché
     *    ([cachedTokens] es un subconjunto de)→ Predeterminado true,Al calcular el precio, réstalo del aviso.
     *  - Anthropic:`input_tokens` **Eso es todo**entrada no almacenada en caché,lectura de caché/Escribe los puntos enumerados en
     *    `cache_read_input_tokens` / `cache_creation_input_tokens` → Establecido por AnthropicProvider false,
     *    Nunca restes al calcular precios(De lo contrario, la entrada normal se recortará cuando el tamaño de la caché sea grande. 0,La tarifa de entrada se pierde directamente)
     *
     * Sólo afecta el precio,no cambiar [promptTokens] / [total] Su propio calibre de valor.
     */
    val promptTokensIncludeCache: Boolean = true,
) {
    /** [cachedTokens] una vista explícitamente nombrada de:Token de lectura de caché. */
    val cacheReadTokens: Int get() = cachedTokens

    /** Caché Lectura + Escritura Total(antes de dividir [cachedTokens] Un solo campo lleva esta suma.). */
    val cachedTokensTotal: Int get() = cachedTokens + cacheWriteTokens

    /** consumo total = prompt + completion(Incluido reasoning,No agregue repetidamente). */
    val total: Int get() = promptTokens + completionTokens
}

/**
 * Provider abstracto. cada fabricante específico(OpenAI / Google / Anthropic)darse cuenta de uno,
 * Responsable de unificar [ChatRequest] Traduzca a su propia llamada API HTTP y finalícela con [Flow] El formulario devuelve eventos de transmisión.
 */
interface Provider {
    val id: String
    val displayName: String

    /** Charla en streaming. El flujo devuelto realiza solicitudes de red mientras recopila,Cerrar al finalizar o por error. */
    fun streamChat(request: ChatRequest): Flow<ChatStreamEvent>

    /**
     * Chat sin transmisión. Devolver resultados completos de una sola vez,Adecuado para compilación de memoria,fact Tareas en segundo plano como la extracción.
     * Lanzar por defecto [UnsupportedOperationException],Implementado bajo demanda por un Proveedor específico.
     */
    suspend fun completeText(request: ChatRequest): ChatCompletion =
        throw UnsupportedOperationException("$displayName does not implement completeText")

    /**
     * Extraiga la lista de modelos ascendentes.
     *
     * Cada proveedor específico llama a la interfaz ascendente /models para devolver los modelos disponibles.,Para la página de edición de UI"Tire del modelo aguas arriba"Usar.
     * [config] Pasado por la persona que llama(Puede contener ediciones no guardadas),La implementación analiza baseUrl/apiKey por él.
     * Devuelve una lista vacía por defecto,Indica que el proveedor no implementa pull(Si personalizas la transferencia, puedes usar OpenAI compatible).
     */
    suspend fun listModels(config: ProviderConfig): List<Model> = emptyList()

    /**
     * v1.0.8 (7.6): Verificación del estado del modelo — Verificar con una llamada real de LLM apiKey / baseUrl / model está disponible.
     *
     * darse cuenta:hacia [model] Enviar un "Reply exactly OK." mensaje de usuario,
     *  15s Si obtiene una respuesta que no esté vacía dentro del tiempo de espera, se considera saludable.(Regresar success=true).
     *  Aplicable a:
     *   - página de configuración"prueba"botón:Después de que el usuario cambia apiKey/baseUrl, verificación de conectividad con un clic
     *   - Verifique automáticamente si el proveedor predeterminado está disponible al inicio
     *
     *  con [listModels] la diferencia:
     *   - listModels Validar solo el punto final /models(ligero,Pero no hay garantía de que la interfaz de chat esté disponible.)
     *   - healthCheck Verificar enlace de chat completo(Enviar noticias reales,Puede detectar errores de identificación del modelo/saldo insuficiente, etc.)
     *
     *  La implementación predeterminada es [completeText],Se pueden anular proveedores específicos si se requiere un procesamiento especial.
     *
     * @param model Modelo a probar(Requerido,Determinar los campos modelo de la solicitud.)
     * @return [HealthCheckResult] Contiene indicador de éxito + mensaje de aviso en caso de error
     */
    suspend fun healthCheck(model: Model): HealthCheckResult {
        return try {
            val result = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                val request = ChatRequest(
                    messages = listOf(
                        UIMessage(role = MessageRole.USER, content = "Reply exactly OK."),
                    ),
                    model = model,
                    // issue #3.2: De 16 a 256.
                    // Valor original 16 pares de modelos de cadenas de pensamiento.(DeepSeek Flash / o serie, etc.)No es suficiente en absoluto:
                    // Todas las cuotas fueron devoradas por el razonamiento,content esta vacio → Error de juicio empty response,
                    // Y el chat real es completamente normal. Es por eso que los usuarios ven "casi todas las pruebas de modelos fallan".
                    maxTokens = 256,
                    // Modo de tarea en segundo plano:Guan Si pensando,Reducir la latencia
                    mode = ChatRequestMode.UTILITY,
                )
                completeText(request)
            }
            when {
                result == null -> HealthCheckResult(success = false, message = "timeout (15s)")
                // issue #3.2: Las herramientas de llamada también pueden considerarse acceso a enlaces. —— Significa que el servicio modelo respondió normalmente.
                // La antigua lógica también juzgará como un error "solo devolver llamadas a herramientas pero no el texto".,Es una falsa alarma.
                !result.toolCalls.isNullOrEmpty() ->
                    HealthCheckResult(success = true, message = result.text.take(80).ifBlank { "tool_calls ok" })
                // issue #3.2: El texto está vacío pero la cadena de pensamiento no está vacía. → tiene sentido(Normalidad del modelo de cadena de pensamiento.).
                result.text.isBlank() && result.reasoningContent.isNullOrBlank() ->
                    HealthCheckResult(success = false, message = "empty response")
                result.text.isBlank() ->
                    HealthCheckResult(success = true, message = "reasoning ok")
                else -> HealthCheckResult(success = true, message = result.text.take(80))
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // issue #3.2: Se debe dar una razón procesable por el fracaso.,No se puede perder ni una sola frase del texto original.
            // Comentarios de los usuarios "Aparece algo inesperado pero realmente funciona"——El artículo original no es útil para solucionar problemas.
            HealthCheckResult(success = false, message = describeHealthCheckFailure(t))
        }
    }

    /** issue #3.2: Traduzca las excepciones de los controles de estado en motivos procesables para el usuario. */
    private fun describeHealthCheckFailure(t: Throwable): String {
        val raw = t.message?.take(120).orEmpty()
        val lower = raw.lowercase()
        val hint = when {
            "401" in lower || "unauthorized" in lower || "invalid api key" in lower ->
                "API Key Inválido o caducado"
            "403" in lower || "forbidden" in lower -> "Sin acceso(Verificar permisos clave/Restricciones regionales)"
            "404" in lower || "model not found" in lower || "does not exist" in lower ->
                "El modelo no existe o la ruta de la URL base es incorrecta"
            "429" in lower || "rate limit" in lower -> "Límite de corriente del disparador,Vuelve a intentarlo más tarde"
            "insufficient" in lower || "quota" in lower || "balance" in lower -> "Saldo o cuota insuficiente"
            "timeout" in lower || "timed out" in lower -> "Solicitar tiempo de espera(15s),Verifique la red o Base URL"
            "connect" in lower || "unable to resolve" in lower || "unknownhost" in lower ->
                "No se puede conectar(Verificar red/URL base/Proxy)"
            "unexpected" in lower -> "Upstream devolvió un formato inesperado(Principalmente debido a problemas de compatibilidad con la estación de transferencia,Es posible que el chat aún esté disponible)"
            raw.isBlank() -> "error desconocido(${t::class.simpleName ?: "Throwable"})"
            else -> raw
        }
        return if (raw.isNotBlank() && hint != raw) "$hint — $raw" else hint
    }

    companion object {
        /** Tiempo de espera de control de salud(milisegundos),15s Tenga en cuenta la lentitud de la red y la experiencia del usuario. */
        const val HEALTH_CHECK_TIMEOUT_MS: Long = 15_000L
    }
}

/**
 * v1.0.8 (7.6): Resultados del control de salud.
 *
 * @param success pasar o no(La llamada real de LLM obtiene una respuesta no vacía)
 * @param message Resumen de respuestas para el modelo sobre el éxito(Para visualización de interfaz de usuario);Mensaje de error cuando falla
 */
data class HealthCheckResult(
    val success: Boolean,
    val message: String?,
)
