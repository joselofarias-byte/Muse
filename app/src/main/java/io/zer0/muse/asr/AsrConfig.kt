package io.zer0.muse.asr

import io.zer0.muse.data.SecureKeyStore
import kotlinx.serialization.Serializable

/**
 * Phase 9.3 (M2): ASR Provider Escriba enumeración.
 *
 * - [SYSTEM]: sistema ACTION_RECOGNIZE_SPEECH Intent(Confíe en los servicios de Google/proveedor,Es posible que falte la ROM nacional)
 * - [DASHSCOPE]: Reconocimiento de voz en tiempo real de Alibaba Cloud DashScope Paraformer(WebSocket transmisión)
 * - [STEP]: estrellas de paso Step-Audio(OpenAI compatibles API,audio entrada)
 * - [DASHSCOPE_FILE]: Phase 11.1.5 DashScope Transcripción de archivos asincrónica(submit → query sondeo),
 *   Adecuado para archivos de audio largos(Primero debe cargar en OSS para obtener URL,O cárguelo directamente a la red pública para acceder URL)
 * - [OPENAI_WHISPER]: Punto final genérico compatible con OpenAI Whisper(POST {baseUrl}/audio/transcriptions),
 *   Transmisión no verdadera por lotes segmentados,Se adapta a cualquier estación de transferencia compatible con OpenAI(Contiene agnes)
 * - [OPENAI_REALTIME]: OpenAI Realtime WebSocket transmisión(wss://api.openai.com/v1/realtime?intent=transcription),
 *   VAD del lado del servidor + devolución de llamada incremental,Transmisión verdadera
 * - [AGNES]: Agnes estación de transferencia(OpenAI compatibles),Reutilizar [OPENAI_WHISPER] adaptador,baseUrl Señale el punto final de Agnes.
 */
enum class AsrProviderType {
    /** Reconocimiento de voz de intención del sistema(Predeterminado,Sin dependencia de la red, sino de los servicios del fabricante.). */
    SYSTEM,

    /** Nube de Alibaba DashScope Paraformer(wss://dashscope.aliyuncs.com/api-ws/v1/inference). */
    DASHSCOPE,

    /** estrellas de paso Step-Audio(OpenAI compatibles API,audio base64 entrada). */
    STEP,

    /** Phase 11.1.5: DashScope Transcripción de archivos asincrónica(POST submit → GET query sondeo). */
    DASHSCOPE_FILE,

    /** Punto final genérico compatible con OpenAI Whisper(multipart POST /audio/transcriptions). */
    OPENAI_WHISPER,

    /** OpenAI Realtime WebSocket transmisión(Servidor VAD + delta transcription). */
    OPENAI_REALTIME,

    /** Agnes estación de transferencia(OpenAI compatibles,camina adentro [OPENAI_WHISPER] adaptador,baseUrl diferente). */
    AGNES,
}

/**
 * Phase 9.3 (M2): ASR configuración.
 *
 * @param provider ASR Provider Tipo
 * @param apiKey DashScope/Step/OpenAI API Key(SYSTEM patrón ignorar)
 * @param model Nombre del modelo(DashScope Predeterminado paraformer-real-time-v2,Step Predeterminado step-audio-r1.1,
 *   OPENAI_WHISPER Predeterminado whisper-1,OPENAI_REALTIME Predeterminado gpt-realtime-whisper,
 *   DASHSCOPE_FILE Predeterminado paraformer-v2)
 * @param sampleRate Tasa de muestreo(DashScope Paraformer v2 Apoyar cualquier,Predeterminado 16000)
 * @param language Consejos de idioma(DashScope: zh/en/ja/yue/ko/de/fr/ru,null=reconocimiento automático)
 * @param enablePunctuation Si se debe habilitar la predicción de puntuación(DashScope v2,Predeterminado true)
 * @param enableInverseTextNormalization Ya sea para habilitar la regularización de texto inverso(números chinos→números arábigos,Predeterminado true)
 * @param fileAudioUrl Phase 11.1.5: Audio transcrito de archivos asincrónicos URL(Red pública accesible,Como por ejemplo OSS URL)
 * @param pollIntervalMs Phase 11.1.5: Intervalo de sondeo(Predeterminado 3000ms)
 * @param pollTimeoutMs Phase 11.1.5: Tiempo de espera total de sondeo(Predeterminado 300000ms = 5 minutos)
 * @param asrEndpoint L-ASR3: DashScope WebSocket punto final(Vacío predeterminado,Utilice el valor predeterminado incorporado del código cuando esté vacío)
 * @param baseUrl Conceptos básicos de API personalizadas URL(OPENAI_WHISPER/OPENAI_REALTIME/AGNES usar,
 *   OPENAI_WHISPER/AGNES se usa por defecto cuando está vacío https://api.openai.com/v1,OPENAI_REALTIME Uso de puntos finales integrados de wss)
 * @param hotwords lista de palabras calientes,Mejorar la tasa de reconocimiento de nombres propios(Solo se aprobarán los proveedores admitidos:
 *   DashScope/SenseVoice/Whisper parámetro de solicitud para puntos finales compatibles)
 * @param vadEnabled Si se debe habilitar la detección de silencio del VAD local(Predeterminado false,
 *   OPENAI_REALTIME El VAD del lado del servidor no requiere local;DashScope/Whisper/Step Habilitación opcional)
 * @param vadThreshold VAD umbral de energía(normalización RMS 0-1f,Predeterminado 0.05f,Los valores por debajo de esto se consideran mudos.)
 * @param vadSilenceDurationMs VAD Duración de la parada automática silenciosa(milisegundos,Predeterminado 1500ms)
 * @param followProvider Si se debe seguir la configuración del proveedor(Credenciales después de la apertura/Modelo tomado de [providerId] + [modelId])
 * @param providerId Proveedor seleccionado en modo seguimiento id
 * @param modelId Modelo seleccionado en modo seguimiento id
 */
@Serializable
data class AsrConfig(
    val provider: AsrProviderType = AsrProviderType.SYSTEM,
    val apiKey: String = "",
    val model: String = "paraformer-realtime-v2",
    val sampleRate: Int = 16000,
    val language: String? = "es",
    val enablePunctuation: Boolean = true,
    val enableInverseTextNormalization: Boolean = true,
    val fileAudioUrl: String = "",
    val pollIntervalMs: Long = 3000L,
    val pollTimeoutMs: Long = 300_000L,
    val asrEndpoint: String = "",
    // Nuevo:OpenAI Puntos finales compatibles/En tiempo real/Personalizaciones compartidas de Agnes baseUrl
    val baseUrl: String = "",
    // Nuevo:lista de palabras calientes(La separación de comas se maneja en el nivel de la interfaz de usuario.,Usado aquí List<String>)
    val hotwords: List<String> = emptyList(),
    // Nuevo:Configuración VAD local
    val vadEnabled: Boolean = false,
    val vadThreshold: Float = 0.05f,
    val vadSilenceDurationMs: Long = 1_500L,
    /**
     * Si se debe seguir la configuración del proveedor(Predeterminado false).
     *
     * Después de abrir, se toma apiKey / baseUrl de [providerId] Proveedor a señalar,Modelo tomado de [modelId];
     * Utilice siempre la configuración manual al apagar([apiKey] / [baseUrl] / [model]),El comportamiento es consistente con versiones anteriores.
     */
    val followProvider: Boolean = false,
    /** Proveedor seleccionado en modo seguimiento id(Cadena vacía significa no seleccionado). */
    val providerId: String = "",
    /** Modelo seleccionado en modo seguimiento id(Cadena vacía significa no seleccionado). */
    val modelId: String = "",
) {
    /** ¿Está configurado y disponible?(SYSTEM siempre disponible,API el patrón requiere apiKey). */
    val isConfigured: Boolean
        get() = provider == AsrProviderType.SYSTEM || apiKey.isNotBlank()

    /** Devuelve el nombre del modelo predeterminado según el proveedor. */
    fun defaultModel(): String = when (provider) {
        AsrProviderType.DASHSCOPE -> "paraformer-realtime-v2"
        AsrProviderType.STEP -> "step-audio-r1.1"
        AsrProviderType.DASHSCOPE_FILE -> "paraformer-v2"
        AsrProviderType.OPENAI_WHISPER -> "whisper-1"
        AsrProviderType.OPENAI_REALTIME -> "gpt-realtime-whisper"
        AsrProviderType.AGNES -> "whisper-1"
        AsrProviderType.SYSTEM -> ""
    }

    /**
     * Devuelve el valor predeterminado según el proveedor. baseUrl(Una cadena vacía indica que cada controlador determina internamente el punto final integrado.).
     * - OPENAI_WHISPER / AGNES: Predeterminado https://api.openai.com/v1(agnes El usuario necesita personalizar)
     * - OPENAI_REALTIME: WebSocket El punto final lo determina internamente el controlador
     * - Otros: cadena vacía(Utilice los puntos finales integrados de cada controlador)
     */
    fun defaultBaseUrl(): String = when (provider) {
        AsrProviderType.OPENAI_WHISPER -> "https://api.openai.com/v1"
        AsrProviderType.AGNES -> "https://api.openai.com/v1"
        else -> ""
    }

    /**
     * M-03: Devolver apiKey cifrado(ir [SecureKeyStore.encrypt])copia de,Por llamar antes que la perseverancia.
     * Los valores nulos se dejan como están.(No cifrar valores nulos). Comportamiento coherente con WebServerConfig.encrypted.
     */
    suspend fun encrypted(): AsrConfig = copy(
        apiKey = SecureKeyStore.encrypt(apiKey),
    )

    /**
     * M-03: Devolver apiKey descifrada(ir [SecureKeyStore.decrypt])copia de,Se llama después de leer desde la capa de persistencia.
     * El antiguo texto sin formato se transmite de forma transparente mediante descifrado.(compatibles). Comportamiento coherente con WebServerConfig.decrypted.
     */
    suspend fun decrypted(): AsrConfig = copy(
        apiKey = SecureKeyStore.decrypt(apiKey),
    )
}
