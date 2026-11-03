package io.zer0.muse.ui.speech

import android.util.Base64
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B6-04: Fish Audio Proveedor de clonación de voz.
 *
 * Punto final (predeterminado https://api.fish.audio）：
 * - POST   /model             multipart Cargue muestra + metainformación, devuelva _id (voice id)
 * - GET    /model/{id}        Sondeo del estado del clon (state=trained medios disponibles)
 * - GET    /model?page_size=100 Lista de sonidos clonados
 * - DELETE /model/{id}        borrar sonido
 *
 * v2.6.1 (#3): Corregido a puntos finales y campos oficiales reales. ——
 *   La antigua implementación era `/v1/voices`(La ruta no existe) y solo envía `name`/`files` campos,
 *   Como resultado, los usuarios pueden obtener errores estables incluso si obtienen la apiKey correcta. Los modelos creados oficialmente requieren varias partes.
 *   `type=tts` / `title` / `train_mode=fast` / `visibility` / `voices=@file`,
 *   El cuerpo de retorno es el objeto modelo (`_id` es la identificación del timbre,`state` para el estado de entrenamiento).
 *   Nota:TTS El punto final sintético sigue siendo /v1/tts (consulte CloudTtsService), un dominio diferente al clon.
 *
 * Certificación:`Authorization: Bearer {apiKey}`, con [CloudTtsService.synthesizeFishAudio] coherente.
 */
class FishAudioVoiceCloningProvider(
    private val client: OkHttpClient,
) : VoiceCloningProvider {

    @Volatile
    var apiKey: String = ""

    @Volatile
    var endpoint: String = DEFAULT_ENDPOINT

    private val timedClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override val providerId: String = "fish"

    override suspend fun cloneVoice(name: String, sampleAudioBase64: String): Result<String> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
            resultOf {
                val audioBytes = Base64.decode(sampleAudioBase64, Base64.DEFAULT)
                if (audioBytes.isEmpty()) throw IOException("Sample audio is empty after base64 decode")

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    // v2.6.1 (#3): Campos modelo creados oficialmente —— type=tts / title / train_mode=fast
                    //   / visibility=private / voices=Archivos de muestra.
                    .addFormDataPart("type", "tts")
                    .addFormDataPart("title", name)
                    .addFormDataPart("train_mode", "fast")
                    .addFormDataPart("visibility", "private")
                    .addFormDataPart("voices", SAMPLE_FILENAME, audioBytes.toRequestBody(SAMPLE_MIME.toMediaType()))
                    .build()
                val req = Request.Builder()
                    .url("${baseUrl()}/model")
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .header("Accept", "application/json")
                    .post(multipart)
                    .build()

                val voiceId = timedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Fish Audio cloneVoice failed: HTTP ${resp.code}, body=${resp.body.string()}")
                    }
                    val root = parseJson(resp.body.string())
                    // Devuelve oficialmente el objeto modelo, la identificación de voz está en _id (compatible con las variantes id/voz_id).
                    root["_id"]?.jsonPrimitive?.contentOrNull
                        ?: root["id"]?.jsonPrimitive?.contentOrNull
                        ?: root["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: throw IOException("Fish Audio cloneVoice response missing _id")
                }

                // Estado de clonación de encuesta, hasta 60s
                var ready = false
                repeat(MAX_POLL_ATTEMPTS) {
                    if (isVoiceReady(voiceId)) {
                        ready = true
                        return@repeat
                    }
                    delay(POLL_INTERVAL_MS)
                }
                if (!ready) throw IOException("Fish Audio cloneVoice timed out waiting for ready")
                Logger.i(TAG, "Cloned voice: name=$name, voiceId=$voiceId")
                voiceId
            }
        }

    override suspend fun listClonedVoices(): Result<List<ClonedVoice>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
        resultOf {
            val req = Request.Builder()
                .url("${baseUrl()}/model?page_size=100")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()
            timedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Fish Audio listVoices failed: HTTP ${resp.code}, body=${resp.body.string()}")
                }
                val root = parseJson(resp.body.string())
                // v2.6.1 (#3): Vuelve la lista oficial {items:[...]}(Compatible con alias de voces).
                val arr = root["items"] as? JsonArray
                    ?: root["voices"] as? JsonArray
                    ?: throw IOException("Fish Audio listVoices response missing items[]")
                arr.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val voiceId = obj["_id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["id"]?.jsonPrimitive?.contentOrNull
                        ?: obj["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    val voiceName = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: voiceId
                    ClonedVoice(voiceId = voiceId, name = voiceName, createdAt = 0L)
                }
            }
        }
    }

    override suspend fun deleteVoice(voiceId: String): Result<Unit> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext Result.Error("Fish Audio apiKey is empty")
        resultOf {
            val req = Request.Builder()
                .url("${baseUrl()}/model/$voiceId")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Accept", "application/json")
                .delete()
                .build()
            timedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Fish Audio deleteVoice failed: HTTP ${resp.code}, body=${resp.body.string()}")
                }
                Logger.i(TAG, "Deleted voice: voiceId=$voiceId")
                Unit
            }
        }
    }

    private suspend fun isVoiceReady(voiceId: String): Boolean = runCatching {
        val req = Request.Builder()
            .url("${baseUrl()}/model/$voiceId")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .get()
            .build()
        timedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use false
            val root = parseJson(resp.body.string())
            // v2.6.1 (#3): Utiliza oficialmente el estado (estado de formación), que es compatible con el antiguo estado del nombre de campo.
            val state = (root["state"] ?: root["status"])?.jsonPrimitive?.contentOrNull?.lowercase()
            // train_mode=fast Generalmente capacitado directamente;null Se considera disponible (comportamiento antiguo).
            state == null || state == "trained" || state == "ready" || state == "succeeded"
        }
    }.getOrDefault(false)

    private fun baseUrl(): String = normalizeEndpoint(endpoint)

    private fun parseJson(text: String): JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(text) as? JsonObject
        ?: throw IOException("Fish Audio response is not JSON")

    companion object {
        private const val TAG = "FishAudioVoiceClone"

        /**
         * v2.6.1 (#3): El punto final clonado es de un dominio diferente al del punto final TTS ——
         * Clon https://api.fish.audio/model（/v1),TTS Ir https://api.fish.audio/v1/tts。
         * valor antiguo `.../v1` Las /v1/voces detalladas no existen.
         */
        const val DEFAULT_ENDPOINT = "https://api.fish.audio"

        /** B8-05: Dirección del servicio Fish Audio normalizado,Conveniente para pruebas individuales puras. */
        internal fun normalizeEndpoint(endpoint: String): String = endpoint.trim().trimEnd('/')
            .ifBlank { DEFAULT_ENDPOINT }
            // Compatible con usuarios que hacen referencia erróneamente a puntos finales TTS (.../v1) para completar la dirección del clon: elimine el final /v1.
            .removeSuffix("/v1")
        private const val SAMPLE_MIME = "audio/mpeg"
        private const val SAMPLE_FILENAME = "sample.mp3"
        private const val TIMEOUT_SECONDS = 30L
        private const val MAX_POLL_ATTEMPTS = 12
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
