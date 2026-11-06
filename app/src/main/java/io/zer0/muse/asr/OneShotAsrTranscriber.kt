package io.zer0.muse.asr

import io.zer0.ai.core.ProxyProvider
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.data.withResolvedEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Un transcriptor de una sola vez que convierte una grabación completa en texto.
 *
 * La grabación no está en este nivel.:La persona que llama recoge el PCM, sella el WAV y luego entrega los bytes.,Aquí solo nos centramos en "una entrada de audio".,Dígalo en una frase."
 * Los parámetros de conexión siempre se toman de la configuración de reconocimiento de voz del usuario.,y vete [SpeechProviderResolver] analizar —— Abierto
 * Usuarios de "Seguir configuración de proveedor",La identificación de grabación y transmisión utiliza el mismo conjunto de direcciones y credenciales.
 *
 * Contrario de controlador de flujo,Sin incrementos, sin puesta en escena, sin reintentos:Una solicitud corresponde a una frase.,
 * Si algún paso falla, se devolverá como "no escuché con claridad". null,No lanza una excepción a la persona que llama.
 */
class OneShotAsrTranscriber(
    private val settings: SettingsRepository,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * poner [wavBytes] Convierte todo el párrafo en texto.
     *
     * @return Eliminar el texto en blanco inicial y final;Si el motor no lo admite, el servicio de reconocimiento no está configurado, la solicitud falla o no hay texto disponible en la respuesta, se devolverá nulo.
     */
    suspend fun transcribe(wavBytes: ByteArray): String? {
        if (wavBytes.isEmpty()) return null
        val config = effectiveConfig() ?: return null
        if (!config.supportsFileTranscription()) {
            Logger.w(TAG, "Motor de reconocimiento actual(${config.provider})No se proporciona el punto final de transcodificación de audio completo")
            return null
        }
        val base = config.baseUrl.ifBlank { config.defaultBaseUrl().ifBlank { DEFAULT_BASE_URL } }
        return requestTranscription(
            url = oneShotTranscriptionUrl(base),
            apiKey = config.apiKey,
            model = config.model.ifBlank { config.defaultModel() },
            wavBytes = wavBytes,
        )
    }

    /**
     * ¿Puede la configuración actual convertir todo el audio en texto?
     *
     * con [transcribe] Compartir el mismo resultado de análisis y el mismo criterio —— Para decir soporte, debes poder realizar solicitudes.
     * No puedo decir que el apoyo esté ahí, pero [transcribe] Devuelve nulo en el acto.
     */
    suspend fun supportsTranscription(): Boolean = effectiveConfig()?.supportsFileTranscription() == true

    /** Leer la configuración una vez,Analizar \"Seguir proveedor\" en la configuración;Sin apiKey, no hay servicio de identificación disponible. */
    private suspend fun effectiveConfig(): AsrConfig? {
        val config = settings.asrConfigFlow.first()
        val providers = settings.providersFlow.first()
        val resolved = SpeechProviderResolver.resolveAsr(config, providers)
        if (resolved.isProviderFallback) {
            Logger.w(TAG, "Seguir la configuración del proveedor no está disponible(${resolved.fallbackReason}),La transcripción de la grabación cambió a configuración manual.")
        }
        val effective = config.withResolvedEndpoint(resolved)
        if (effective.apiKey.isBlank()) {
            Logger.w(TAG, "El reconocimiento de voz no está configurado apiKey,Saltar transcripción de grabación")
            return null
        }
        return effective
    }

    /**
     * POST `{baseUrl}/audio/transcriptions`(multipart/form-data).
     *
     * Sólo hay tres elementos en el formulario.:archivos de audio, modelos,`response_format=json`;sin language,
     * El idioma lo decide el servidor.,Esto evita que los usuarios se vean obligados a presionar chino para transferir un texto extranjero cuando no han seleccionado un idioma.
     */
    private suspend fun requestTranscription(url: String, apiKey: String, model: String, wavBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", FILE_NAME, wavBytes.toRequestBody(WAV_MEDIA_TYPE))
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .post(multipart)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logger.w(TAG, "Error en la transcripción de la grabación: HTTP ${response.code} ${response.message}")
                        return@withContext null
                    }
                    val text = parseOneShotTranscription(response.body?.string().orEmpty())
                    if (text == null) Logger.w(TAG, "No hay texto disponible en la respuesta transcrita grabada(HTTP ${response.code})")
                    text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Logger.w(TAG, "Excepción de solicitud de transcripción de grabación: ${e.message}")
                null
            }
        }

    private companion object {
        private const val TAG = "OneShotAsrTranscriber"

        /** Misma dirección que el cliente Whisper existente:Utilice el punto final oficial cuando el usuario no lo complete manualmente ni siga al proveedor. */
        private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

        private const val FILE_NAME = "voice.wav"

        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}

/**
 * Analizar la respuesta transcrita `text` campo.
 *
 * Reconocer solo cadenas en texto JSON: falta el campo y no es una cadena.trim El resultado está vacío y la respuesta no lo es en absoluto. JSON,
 * Siempre devuelve nulo. Aquí no hay garantía de que "si la respuesta es texto sin formato, se eliminará el párrafo completo". —— `response_format=json`
 * La siguiente es probablemente una página de error.,Completar el cuadro de entrada como resultado del reconocimiento es peor que no completar nada.
 */
internal fun parseOneShotTranscription(body: String): String? = runCatching {
    val obj = Json.parseToJsonElement(body) as? JsonObject
    val text = (obj?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
    text?.trim()?.takeIf { it.isNotEmpty() }
}.getOrNull()

/**
 * Sólo si realmente puedes soportarlo `POST {baseUrl}/audio/transcriptions` El motor reconoce todo el audio.
 *
 * El resto de motores toman caminos separados.:La intención del sistema no tiene un punto final HTTP,DashScope ¿Wss es una transcripción de archivos asincrónica y en tiempo real?
 * Step Insertar audio en la solicitud de chat,Realtime es transmisión wss —— Tome su apiKey para deletrear
 * `/audio/transcriptions` Sólo recibirás una solicitud perdedora. Entonces, si es compatible o no depende del tipo de motor.,No es cierto sin mirar apiKey.
 *
 * También se requiere que apiKey no esté vacía y baseUrl esté http(s):Falta una de estas dos condiciones.,[OneShotAsrTranscriber.transcribe]
 * También devolverá nulo antes de enviar la solicitud.
 */
internal fun AsrConfig.supportsFileTranscription(): Boolean {
    if (provider != AsrProviderType.OPENAI_WHISPER && provider != AsrProviderType.AGNES) return false
    if (apiKey.isBlank()) return false
    val base = baseUrl.ifBlank { defaultBaseUrl() }
    return base.startsWith("https://") || base.startsWith("http://")
}

/**
 * Deletrea el punto final de la transliteración.
 *
 * El mismo conjunto de reglas que el cliente Whisper existente:asignado a `/v1` No repetir la versión ortográfica,
 * Los usuarios aún pueden usarlo pegando el punto final completo en la configuración.
 */
internal fun oneShotTranscriptionUrl(baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    return when {
        base.endsWith("/audio/transcriptions") -> base
        base.endsWith("/v1") -> "$base/audio/transcriptions"
        else -> "$base/v1/audio/transcriptions"
    }
}

/** Cliente HTTP para transcribir:El tiempo de espera toma el mismo conjunto de constantes que el cliente ASR existente,Y herede la configuración del proxy del host. */
private fun defaultClient(): OkHttpClient = ProxyProvider.applyTo(OkHttpClient.Builder())
    .connectTimeout(AsrConstants.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
    .readTimeout(AsrConstants.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
    .build()
