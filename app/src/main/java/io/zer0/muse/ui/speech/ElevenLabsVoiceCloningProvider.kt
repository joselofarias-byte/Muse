package io.zer0.muse.ui.speech

import android.util.Base64
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * P2-9: ElevenLabs Voice Cloning Provider Darse cuenta.
 *
 * punto final( baseUrl = https://api.elevenlabs.io/v1 ):
 *  - POST   /voices/add          — multipart Subir audio de muestra + name,Regresar voice_id
 *  - GET    /voices              — enumerar todo voice(Contiene clon + preset)
 *  - DELETE /voices/{voice_id}   — eliminar especificado voice
 *
 * Certificación:HTTP cabeza `xi-api-key: {apiKey}`.
 *
 * [apiKey] Establecido por la capa UI antes de cada operación(Paso a través de MediaConfig.ttsApiKey),
 * con [CloudTtsService] Las rutas TTS de ElevenLabs comparten la misma clave API.
 *
 * tiempo de espera:Reutilización inyectada OkHttpClient,pero a través de [newBuilder] Forzar anulación a tres tiempos de espera de 30 segundos,
 * satisfacer"API La llamada debe tener un tiempo de espera.(30 segundos)"restricción(La carga del clon no debe heredar el cliente de chat. Tiempo de espera de lectura de 5 minutos.).
 */
class ElevenLabsVoiceCloningProvider(
    private val client: OkHttpClient,
) : VoiceCloningProvider {

    companion object {
        private const val TAG = "ElevenLabsVoiceClone"

        /** ElevenLabs Voice Cloning API dirección base. */
        private const val BASE_URL = "https://api.elevenlabs.io/v1"

        /** tipo mimo de discurso clonado(ElevenLabs Acepta mp3/wav/m4a, etc.). */
        private const val SAMPLE_MIME = "audio/mpeg"

        /** Nombre del archivo al cargar audio de muestra(ElevenLabs Confíe únicamente en contenido binario,Simplemente coloque un nombre de archivo). */
        private const val SAMPLE_FILENAME = "sample.mp3"

        /** Forzar un tiempo de espera de 30 segundos(cumplir con las limitaciones de la tarea). */
        private const val TIMEOUT_SECONDS = 30L
    }

    /** ElevenLabs provider Identificador único. */
    override val providerId: String = "elevenlabs"

    /**
     * ElevenLabs API Key — Establecido por la interfaz de usuario antes de llamar(Paso a través de MediaConfig.ttsApiKey).
     * Utilice @Volatile para garantizar la visibilidad entre subprocesos(Las corrutinas se ejecutan en el programador IO.).
     */
    @Volatile
    var apiKey: String = ""

    /** con tiempo de espera de 30 segundos client(Crear bajo demanda para cada llamada,Heredar la configuración de proxy/interceptor del cliente base). */
    private val timedClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Crea una voz clonada.
     *
     * Solicitar:multipart/form-data
     *  - `name`  text campo
     *  - `files` audio/mpeg binario(Decodificado por base64)
     *
     * respuesta:`{"voice_id": "..."}` — Devuelve la nueva identificación de voz.
     */
    override suspend fun cloneVoice(name: String, sampleAudioBase64: String): Result<String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.Error("ElevenLabs apiKey is empty")
            }
            resultOf {
                val audioBytes = Base64.decode(sampleAudioBase64, Base64.DEFAULT)
                if (audioBytes.isEmpty()) {
                    throw IOException("Sample audio is empty after base64 decode")
                }

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("name", name)
                    .addFormDataPart(
                        "files",
                        SAMPLE_FILENAME,
                        audioBytes.toRequestBody(SAMPLE_MIME.toMediaType()),
                    )
                    .build()

                val req = Request.Builder()
                    .url("$BASE_URL/voices/add")
                    .header("xi-api-key", apiKey)
                    .header("Accept", "application/json")
                    .post(multipart)
                    .build()

                timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body.string()
                        throw IOException("ElevenLabs cloneVoice failed: HTTP ${resp.code}, body=$body")
                    }
                    val root = Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(resp.body.string()) as? JsonObject
                        ?: throw IOException("ElevenLabs cloneVoice response is not JSON")
                    val voiceId = root["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IOException("ElevenLabs cloneVoice response missing voice_id")
                    Logger.i(TAG, "Cloned voice: name=$name, voiceId=$voiceId")
                    voiceId
                }
            }
        }

    /**
     * enumerar todo voice,filtrar category == "cloned" artículos(ElevenLabs También vuelve al valor predeterminado. voice).
     *
     * respuesta:`{"voices": [{"voice_id": "...", "name": "...", "category": "...", "created_at": "..."}]}`
     * `created_at` como una cadena ISO 8601(Como por ejemplo "2024-01-15T12:34:56.789Z"),Regrese a 0L cuando falla el análisis.
     */
    override suspend fun listClonedVoices(): Result<List<ClonedVoice>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.Error("ElevenLabs apiKey is empty")
        }
        resultOf {
            val req = Request.Builder()
                .url("$BASE_URL/voices")
                .header("xi-api-key", apiKey)
                .header("Accept", "application/json")
                .get()
                .build()

            timedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body.string()
                    throw IOException("ElevenLabs listVoices failed: HTTP ${resp.code}, body=$body")
                }
                val root = Json { ignoreUnknownKeys = true }
                    .parseToJsonElement(resp.body.string()) as? JsonObject
                    ?: throw IOException("ElevenLabs listVoices response is not JSON")
                val arr = root["voices"] as? kotlinx.serialization.json.JsonArray
                    ?: throw IOException("ElevenLabs listVoices response missing voices[]")
                arr.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val voiceId = obj["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val voiceName = obj["name"]?.jsonPrimitive?.contentOrNull ?: voiceId
                    val category = obj["category"]?.jsonPrimitive?.contentOrNull ?: "cloned"
                    val createdAtStr = obj["created_at"]?.jsonPrimitive?.contentOrNull
                    val createdAt = parseIso8601ToMillis(createdAtStr)
                    ClonedVoice(
                        voiceId = voiceId,
                        name = voiceName,
                        createdAt = createdAt,
                        category = category,
                    )
                }.filter { it.category == "cloned" }
            }
        }
    }

    /**
     * Elimina la voz especificada.
     *
     * DELETE /voices/{voice_id}
     * Regresar exitosamente 204 No Content(cuerpo que no responde).
     */
    override suspend fun deleteVoice(voiceId: String): Result<Unit> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.Error("ElevenLabs apiKey is empty")
        }
        resultOf {
            val req = Request.Builder()
                .url("$BASE_URL/voices/$voiceId")
                .header("xi-api-key", apiKey)
                .header("Accept", "application/json")
                .delete()
                .build()

            timedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = runCatching { resp.body.string() }.getOrNull()
                    throw IOException("ElevenLabs deleteVoice failed: HTTP ${resp.code}, body=$body")
                }
                Logger.i(TAG, "Deleted voice: voiceId=$voiceId")
                Unit
            }
        }
    }

    /**
     * Analiza una cadena de tiempo ISO 8601 en una marca de tiempo de milisegundos.
     *
     * ElevenLabs Regresar como "2024-01-15T12:34:56.789Z" tiempo,Retorno del fracaso 0L(No bloquea la representación de la lista).
     */
    private fun parseIso8601ToMillis(input: String?): Long {
        if (input.isNullOrBlank()) return 0L
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (pattern in formats) {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            try {
                return sdf.parse(input)?.time ?: continue
            } catch (_: Exception) {
                // prueba el siguiente formato
            }
        }
        return 0L
    }
}
