package io.zer0.muse.ui.speech

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import kotlin.coroutines.resume

/**
 * v1.97: Servicio TTS en la nube — apoyo OpenAI / MiniMax / Edge TTS.
 *
 * cuando [MediaConfig.ttsEngine] != "system" tiempo,TtsManager Confía este servicio para sintetizar audio.
 * Devuelve una matriz de bytes mp3,Escriba en el archivo temporal y déjelo reproducir en MediaPlayer.(Consistente con la ruta TTS del sistema).
 *
 * Organizado por abstracción TTSProvider,Distribuido por proveedor dentro de un único archivo.
 *
 * v1.99: mejorar —
 *  - Reparar el nombre de dominio falso de Edge TTS(Hazlo configurable endpoint,Vacío predeterminado)
 *  - completo xAI TTS(POST /tts,body {text, voice_id, language})
 *  - Nuevo [listVoices] Extraer dinámicamente la lista de tonos
 *  - Nuevo [CloudTtsConfig] paso a través stability/similarity/emotion/speed/response_format
 */
class CloudTtsService(
    private val client: OkHttpClient,
    /**
     * Phase 3 (P1): Tiempo de espera de descarga de audio secundario(ms).
     *
     * Descarga de archivos de audio fuera de la solicitud maestra de síntesis(Por ejemplo, Qwen regresa la segunda vez después de salida.audio.url GET)Debes traer el tuyo
     * Tiempo de espera — Chat reutilizado OkHttpClient readTimeout más largo 300s,La URL de audio se sintetizará cuando se cuelgue.
     * La corrutina se bloquea durante varios minutos. Manejar el tiempo de espera como un error,Sistema de respaldo de capa superior TTS. El valor predeterminado deja el ensamblaje existente sin cambios.
     */
    private val audioDownloadTimeoutMs: Long = AUDIO_DOWNLOAD_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "CloudTtsService"

        /** Phase 3 (P1): Tiempo de espera predeterminado de descarga de audio secundario(ms). */
        const val AUDIO_DOWNLOAD_TIMEOUT_MS = 30_000L

        /** OpenAI TTS Punto final predeterminado. */
        private const val OPENAI_DEFAULT_ENDPOINT = "https://api.openai.com/v1"

        /** OpenAI TTS Modelo predeterminado. */
        private const val OPENAI_DEFAULT_MODEL = "gpt-4o-mini-tts"

        /** OpenAI TTS Tono predeterminado. */
        private const val OPENAI_DEFAULT_VOICE = "alloy"

        /** MiniMax TTS Punto final predeterminado. */
        private const val MINIMAX_DEFAULT_ENDPOINT = "https://api.minimaxi.com/v1"

        /** MiniMax TTS Modelo predeterminado. */
        private const val MINIMAX_DEFAULT_MODEL = "speech-2.6-turbo"

        /** MiniMax TTS Tono predeterminado. */
        private const val MINIMAX_DEFAULT_VOICE = "female-shaonv"

        /**
         * Edge TTS gratis endpoint(Compatible con el formato de interfaz OpenAI).
         *
         * v1.99: El valor predeterminado original https://edge-tts-api.example.com/v1 es un nombre de dominio falso de marcador de posición.,
         * Inaccesible.Edge TTS No hay servicio oficial compatible con OpenAI,Los usuarios deben implementarlo ellos mismos./rellenar
         * Dirección proxy compatible(Como el proxy comunitario edge-tts-openai). Cadena vacía predeterminada:La síntesis falla cuando se deja en blanco,
         * Sistema de respaldo de la capa superior TTS,Evite engaños.
         */
        private const val EDGE_DEFAULT_ENDPOINT = ""

        /** DashScope TTS (Nube de Alibaba) Punto final predeterminado. */
        private const val DASHSCOPE_DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1"

        /** DashScope TTS Modelo predeterminado. */
        private const val DASHSCOPE_DEFAULT_MODEL = "cosyvoice-v2"

        /** DashScope TTS Tono predeterminado. */
        private const val DASHSCOPE_DEFAULT_VOICE = "longxiaochun"

        /** Fish Audio TTS Punto final predeterminado. */
        private const val FISH_DEFAULT_ENDPOINT = "https://api.fish.audio/v1"

        /** Fish Audio TTS Modelo predeterminado. */
        private const val FISH_DEFAULT_MODEL = "s1"

        /** ElevenLabs TTS Punto final predeterminado. */
        private const val ELEVENLABS_DEFAULT_ENDPOINT = "https://api.elevenlabs.io/v1"

        /** ElevenLabs TTS Modelo predeterminado(multilingüe v2). */
        private const val ELEVENLABS_DEFAULT_MODEL = "eleven_multilingual_v2"

        /** ElevenLabs TTS Predeterminado Voice ID(Rachel). */
        private const val ELEVENLABS_DEFAULT_VOICE = "21m00Tcm4TlvDq8ikWAM"

        /** Groq TTS Predeterminado endpoint(OpenAI Ruta de compatibilidad). */
        private const val GROQ_DEFAULT_ENDPOINT = "https://api.groq.com/openai/v1"

        /** Groq TTS Modelo predeterminado. */
        private const val GROQ_DEFAULT_MODEL = "playai-tts"

        /** Groq TTS Tono predeterminado. */
        private const val GROQ_DEFAULT_VOICE = "Fritz-PlayAI"

        /** Qwen TTS(DashScope Interfaz nativa)Punto final predeterminado. con dashscope(OpenAI Modo de compatibilidad)Diferente. */
        private const val QWEN_DEFAULT_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1"

        /** Qwen TTS Modelo predeterminado(cosyvoice-v1). */
        private const val QWEN_DEFAULT_MODEL = "cosyvoice-v1"

        /** Qwen TTS Tono predeterminado(Largo Xiaochun). */
        private const val QWEN_DEFAULT_VOICE = "longxiaochun"

        /** StepFun TTS Punto final predeterminado. */
        private const val STEP_DEFAULT_ENDPOINT = "https://api.stepfun.com/v1"

        /** StepFun TTS Modelo predeterminado. */
        private const val STEP_DEFAULT_MODEL = "step-tts-mini"

        /** StepFun TTS Tono predeterminado. */
        private const val STEP_DEFAULT_VOICE = "speaker1"

        /** xAI TTS Punto final predeterminado. */
        private const val XAI_DEFAULT_ENDPOINT = "https://api.x.ai/v1"

        /** xAI TTS Tono predeterminado. */
        private const val XAI_DEFAULT_VOICE = "Alloy"

        // ── Sonidos comunes codificados para cada motor.(Sin interfaz /voices ni divulgación fuera de línea) ──
        private val OPENAI_VOICES = listOf(
            VoiceInfo("alloy", "Alloy", "en"),
            VoiceInfo("echo", "Echo", "en"),
            VoiceInfo("fable", "Fable", "en"),
            VoiceInfo("onyx", "Onyx", "en"),
            VoiceInfo("nova", "Nova", "en"),
            VoiceInfo("shimmer", "Shimmer", "en"),
            VoiceInfo("coral", "Coral", "en"),
            VoiceInfo("sage", "Sage", "en"),
            VoiceInfo("ash", "Ash", "en"),
            VoiceInfo("ballad", "Ballad", "en"),
        )
        private val EDGE_VOICES = listOf(
            VoiceInfo("zh-CN-XiaoxiaoNeural", "Xiaoxiao (zh-CN)", "zh-CN", "female"),
            VoiceInfo("zh-CN-YunxiNeural", "Yunxi (zh-CN)", "zh-CN", "male"),
            VoiceInfo("zh-CN-YunyangNeural", "yun yang (zh-CN)", "zh-CN", "male"),
            VoiceInfo("zh-CN-XiaoyiNeural", "xiaoyi (zh-CN)", "zh-CN", "female"),
            VoiceInfo("en-US-JennyNeural", "Jenny (en-US)", "en-US", "female"),
            VoiceInfo("en-US-GuyNeural", "Guy (en-US)", "en-US", "male"),
        )
        private val GEMINI_VOICES = listOf(
            VoiceInfo("Kore", "Kore", "en"),
            VoiceInfo("Puck", "Puck", "en"),
            VoiceInfo("Charon", "Charon", "en"),
            VoiceInfo("Fenrir", "Fenrir", "en"),
            VoiceInfo("Leda", "Leda", "en"),
            VoiceInfo("Orus", "Orus", "en"),
            VoiceInfo("Aoede", "Aoede", "en"),
        )
        private val DASHSCOPE_VOICES = listOf(
            VoiceInfo("longxiaochun", "Largo Xiaochun", "zh-CN", "female"),
            VoiceInfo("longyan", "longyan", "zh-CN", "male"),
            VoiceInfo("longxiao", "largo xiao", "zh-CN", "female"),
            VoiceInfo("longshu", "Tío largo", "zh-CN", "male"),
            VoiceInfo("longjing", "Cristal de dragón", "zh-CN", "female"),
            VoiceInfo("longmiao", "largo miao", "zh-CN", "female"),
        )
        private val FISH_VOICES = listOf(
            VoiceInfo("", "Tono predeterminado(vacío reference_id)"),
        )
        private val GROQ_VOICES = listOf(
            VoiceInfo("Fritz-PlayAI", "Fritz", "en"),
            VoiceInfo("Gwendolyn-PlayAI", "Gwendolyn", "en"),
            VoiceInfo("Hugo-PlayAI", "Hugo", "en"),
            VoiceInfo("Bella-PlayAI", "Bella", "en"),
        )
        private val QWEN_VOICES = DASHSCOPE_VOICES
        private val STEP_VOICES = listOf(
            VoiceInfo("speaker1", "speaker1", "zh-CN"),
            VoiceInfo("speaker2", "speaker2", "zh-CN"),
            VoiceInfo("speaker3", "speaker3", "zh-CN"),
        )
        private val XAI_VOICES = listOf(
            VoiceInfo("Alloy", "Alloy", "en"),
            VoiceInfo("Echo", "Echo", "en"),
            VoiceInfo("Nova", "Nova", "en"),
            VoiceInfo("Onyx", "Onyx", "en"),
        )

        /**
         * Un conjunto de ID de timbre integrados en el motor (que no dependen de Internet) para "verificar si el timbre antiguo sigue siendo válido al cambiar el motor".
         *
         * Devolver nulo significa que la colección de sonidos de este motor no está disponible:ElevenLabs / MiniMax Para extraer /voces en tiempo real,
         * CLONED por [VoiceCloningService] Administración, no se puede determinar la identificación del motor no reconocido.
         */
        fun builtInVoiceIds(engineId: String): Set<String>? = when (CloudTtsProvider.fromEngine(engineId)) {
            CloudTtsProvider.OPENAI -> OPENAI_VOICES.voiceIds()
            CloudTtsProvider.EDGE -> EDGE_VOICES.voiceIds()
            CloudTtsProvider.GEMINI -> GEMINI_VOICES.voiceIds()
            CloudTtsProvider.DASHSCOPE -> DASHSCOPE_VOICES.voiceIds()
            CloudTtsProvider.FISH -> FISH_VOICES.voiceIds()
            CloudTtsProvider.GROQ -> GROQ_VOICES.voiceIds()
            CloudTtsProvider.QWEN -> QWEN_VOICES.voiceIds()
            CloudTtsProvider.STEP -> STEP_VOICES.voiceIds()
            CloudTtsProvider.XAI -> XAI_VOICES.voiceIds()
            CloudTtsProvider.ELEVENLABS, CloudTtsProvider.MINIMAX, CloudTtsProvider.CLONED, null -> null
        }

        private fun List<VoiceInfo>.voiceIds(): Set<String> = mapTo(LinkedHashSet()) { it.id }
    }

    /**
     * Sintetizar texto en un archivo de audio.
     *
     * @param text Texto a sintetizar(despojado Markdown)
     * @param engine TTS motor:"openai" / "minimax" / "edge" espera
     * @param apiKey API Key
     * @param model Nombre del modelo(Déjelo en blanco para usar el valor predeterminado)
     * @param voice timbre(Déjelo en blanco para usar el valor predeterminado)
     * @param endpoint Personalizar endpoint(Déjelo en blanco para usar el valor predeterminado)
     * @param outputFile archivo de salida(.mp3)
     * @param cloudConfig v1.99: Parámetros del motor(stability/similarity/emotion/speed/response_format)
     * @return Regresar exitosamente true,Retorno del fracaso false
     */
    suspend fun synthesizeToFile(
        text: String,
        engine: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        outputFile: File,
        cloudConfig: CloudTtsConfig = CloudTtsConfig(),
    ): Boolean = withContext(AppDispatchers.io) {
        // Edge TTS Pasar por un agente compatible,endpoint Debe ser proporcionado por el usuario.(Ningún oficial endpoint)
        if (apiKey.isBlank() && engine != "edge") {
            Logger.w(TAG, "La apiKey de Cloud TTS está vacía,saltar")
            return@withContext false
        }

        val audioBytes = resultOf {
            when (engine) {
                "openai" -> synthesizeOpenAI(text, apiKey, model, voice, endpoint, cloudConfig)
                "minimax" -> synthesizeMiniMax(text, apiKey, model, voice, endpoint, cloudConfig)
                "edge" -> synthesizeEdge(text, apiKey, model, voice, endpoint, cloudConfig)
                "gemini" -> synthesizeGemini(text, apiKey, model, voice, endpoint)
                "dashscope" -> synthesizeDashScope(text, apiKey, model, voice, endpoint, cloudConfig)
                "fish" -> synthesizeFishAudio(text, apiKey, model, voice, endpoint)
                "elevenlabs" -> synthesizeElevenLabs(text, apiKey, model, voice, endpoint, cloudConfig)
                "groq" -> synthesizeGroq(text, apiKey, model, voice, endpoint, cloudConfig)
                "qwen" -> synthesizeQwen(text, apiKey, model, voice, endpoint)
                "step" -> synthesizeStep(text, apiKey, model, voice, endpoint, cloudConfig)
                "xai" -> synthesizeXai(text, apiKey, voice, endpoint)
                else -> {
                    Logger.w(TAG, "Motor TTS desconocido: $engine")
                    null
                }
            }
        }.onError { _, t ->
            Logger.w(TAG, "Error en la síntesis de TTS en la nube($engine)", t)
        }.getOrNull()

        if (audioBytes == null || audioBytes.isEmpty()) {
            return@withContext false
        }

        resultOf {
            outputFile.writeBytes(audioBytes)
        }.onError { _, t ->
            Logger.w(TAG, "Error al escribir en el archivo de audio TTS", t)
        }.isSuccess
    }

    /**
     * v1.99: Extrae dinámicamente la lista de sonidos del motor especificado.
     *
     * - ElevenLabs / MiniMax: Vaya a /voices/ /list_voice para obtener información en tiempo real
     * - otros motores: Devuelve sonidos comunes codificados(No se requiere interfaz pública ni autenticación)
     * - CLONED: por [VoiceCloningService] gestión,Volver vacío(TtsManager tratamiento secundario)
     *
     * @param provider Enumeración del motor
     * @param apiKey API Key
     * @param endpoint Personalizar endpoint(Déjelo en blanco para usar el valor predeterminado)
     * @return lista de sonidos;Devuelve una lista vacía en caso de error.
     */
    suspend fun listVoices(provider: CloudTtsProvider, apiKey: String, endpoint: String): List<VoiceInfo> = withContext(AppDispatchers.io) {
        resultOf {
            when (provider) {
                CloudTtsProvider.ELEVENLABS -> listElevenLabsVoices(apiKey, endpoint)
                CloudTtsProvider.MINIMAX -> listMiniMaxVoices(apiKey, endpoint)
                CloudTtsProvider.OPENAI -> OPENAI_VOICES
                CloudTtsProvider.EDGE -> EDGE_VOICES
                CloudTtsProvider.GEMINI -> GEMINI_VOICES
                CloudTtsProvider.DASHSCOPE -> DASHSCOPE_VOICES
                CloudTtsProvider.FISH -> FISH_VOICES
                CloudTtsProvider.GROQ -> GROQ_VOICES
                CloudTtsProvider.QWEN -> QWEN_VOICES
                CloudTtsProvider.STEP -> STEP_VOICES
                CloudTtsProvider.XAI -> XAI_VOICES
                CloudTtsProvider.CLONED -> emptyList()
            }
        }.onError { _, t ->
            Logger.w(TAG, "listVoices($provider) falló", t)
        }.getOrNull() ?: emptyList()
    }

    /**
     * OpenAI TTS — POST {endpoint}/audio/speech
     *
     * Cuerpo de la solicitud: {"model": "...", "input": "...", "voice": "...",
     *          "response_format": "mp3", "speed": 1.0}
     * respuesta: mp3 flujo binario
     */
    private suspend fun synthesizeOpenAI(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { OPENAI_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { OPENAI_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { OPENAI_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "OpenAI TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * MiniMax TTS — POST {endpoint}/t2a_v2
     *
     * Cuerpo de la solicitud: {"model": "...", "text": "...",
     *         "voice_setting": {"voice_id": "...", "speed": ..., "emotion": "..."}}
     * respuesta: {"data": {"audio": "hex_encoded_mp3"}}
     */
    private suspend fun synthesizeMiniMax(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { MINIMAX_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { MINIMAX_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { MINIMAX_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("text", JsonPrimitive(text))
            put(
                "voice_setting",
                kotlinx.serialization.json.buildJsonObject {
                    put("voice_id", JsonPrimitive(voiceName))
                    if (cloudConfig.speed != 1.0f) {
                        put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.5f, 2.0f).toDouble()))
                    }
                    if (cloudConfig.emotion.isNotBlank()) {
                        put("emotion", JsonPrimitive(cloudConfig.emotion))
                    }
                },
            )
        }.toString()

        val req = Request.Builder().url("$baseUrl/t2a_v2")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "MiniMax TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            val body = resp.body.string()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body) as? JsonObject ?: return ByteArray(0)
            val dataObj = root["data"] as? JsonObject ?: return ByteArray(0)
            val hexAudio = dataObj["audio"]?.jsonPrimitive?.contentOrNull ?: return ByteArray(0)
            // MiniMax Devolver codificado en hexadecimal mp3
            return hexToBytes(hexAudio)
        }
    }

    /**
     * Edge TTS — Compatible con el formato de interfaz OpenAI.
     *
     * v1.99: No es oficialmente compatible con OpenAI endpoint,[endpoint] Debe ser completado por el usuario
     * (Proxy edge-tts creado por la comunidad).endpoint Cuando esté vacío, regrese vacío directamente.,Sistema de respaldo de capa superior TTS.
     */
    private suspend fun synthesizeEdge(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { EDGE_DEFAULT_ENDPOINT }
        if (baseUrl.isBlank()) {
            Logger.w(TAG, "Edge TTS endpoint No configurado,saltar(Los usuarios deben completar la dirección de proxy compatible en la configuración)")
            return ByteArray(0)
        }
        val voiceName = voice.ifBlank { "zh-CN-XiaoxiaoNeural" }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(model.ifBlank { "tts-1" }))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val reqBuilder = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
        // Edge TTS Es posible que el agente no necesite API Key
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Edge TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /** hex Convierte una cadena en una matriz de bytes. */
    private fun hexToBytes(hex: String): ByteArray {
        // B-36: Guardia de longitud impar——Tomado previamente directamente hex[i+1],Las longitudes impares estarían fuera de los límites(<IndexOutOfBounds).
        //     Devolver matriz vacía,Si la capa superior no logra identificarlo, tomará el camino de degradación.(Sistema de respaldo TTS),Evite accidentes.
        if (hex.length % 2 != 0) {
            Logger.w(TAG, "hexToBytes: longitud impar(${hex.length}),Volver vacío")
            return ByteArray(0)
        }
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * B-36: Llamada HTTP síncrona cancelable.
     *
     * Llamada de bloqueo de implementación anterior client.execute() No se puede responder a la cancelación de la rutina(jugar/Cuando se cancela la corrutina sintética
     * La solicitud subyacente aún se bloquea en IO hasta que se agote el tiempo de espera). Cambiar a enqueue + [suspendCancellableCoroutine]:
     *  - Aprobado cuando se cancela la corrutina. [Call.cancel] Realmente interrumpe la solicitud subyacente.
     *  - Vuelve el fallo de red null(Fallo por parte de la persona que llama/Manejando resultados vacíos,Semántica diferente de la antigua ejecución lanzando IOException)
     *
     * @return cuerpo de respuesta(La persona que llama necesita use/consume),Devolución fallida o cancelada de la red null
     */
    private suspend fun executeCancellable(request: Request): Response? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                // La cancelación de la rutina activará [invokeOnCancellation]→call.cancel(),En este momento también se activa onFailure.
                // Si la suspensión ha sido cancelada,Salga lanzando CancellationException mediante el mecanismo de cancelación de rutina,No hace falta repetir aquí resume;
                // Sólo si es un fallo de red(No cancelado)tiempo resume(null) Tome la ruta de resultados vacía.
                if (!cont.isCancelled) {
                    cont.resume(null)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (!cont.isCancelled) {
                    cont.resume(response)
                } else {
                    runCatching { response.close() }
                }
            }
        })
        cont.invokeOnCancellation { runCatching { call.cancel() } }
    }

    /**
     * Phase 3 (P1): Cancelable"Solicitud + lectura del cuerpo de respuesta" — La sección completa puede ser cancelada por la corrutina./withTimeout Interrumpir.
     *
     * con [executeCancellable] diferencia: El cuerpo de la respuesta se lee antes de que se complete el cuerpo de continuación.,Cancelar/reunión de tiempo de espera
     * [Call.cancel] Cerrar conexión interrupción bloqueo lectura;y"Obtenga la respuesta primero, luego léala fuera de la rutina body"en el camino
     * body La lectura es una llamada de bloqueo normal.,withTimeout No se puede interrumpir(Esperará hasta que llegue el cuerpo de respuesta.).
     * Sólo para rutas de descarga que requieren protección de tiempo de espera(Como la descarga de audio secundaria de Qwen.)Usar.
     *
     * @return Bytes del cuerpo de respuesta;HTTP falló/Fallo de red/Cancelar/Regreso del tiempo de espera null
     */
    private suspend fun executeBytesCancellable(request: Request): ByteArray? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (!cont.isCancelled) {
                    cont.resume(null)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) {
                    runCatching { response.close() }
                    return
                }
                val bytes = runCatching {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            Logger.w(TAG, "TTS Descarga fallida: HTTP ${resp.code}")
                            null
                        } else {
                            resp.body.bytes()
                        }
                    }
                }.onFailure { e ->
                    Logger.w(TAG, "TTS No se pudo leer el cuerpo de la respuesta: ${e.message}")
                }.getOrNull()
                if (!cont.isCancelled) {
                    cont.resume(bytes)
                }
            }
        })
        cont.invokeOnCancellation { runCatching { call.cancel() } }
    }

    /**
     * Gemini TTS — POST {endpoint}/models/{model}:generateContent
     *
     * Utilice las capacidades de conversión de texto a voz de Gemini a través del punto final generateContent.
     * La respuesta contiene datos de audio codificados en base64.
     */
    private suspend fun synthesizeGemini(text: String, apiKey: String, model: String, voice: String, endpoint: String): ByteArray {
        val baseUrl = endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        val modelName = model.ifBlank { "gemini-2.5-flash-preview-tts" }
        val voiceName = voice.ifBlank { "Kore" }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put(
                "contents",
                kotlinx.serialization.json.buildJsonArray {
                    add(
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                "parts",
                                kotlinx.serialization.json.buildJsonArray {
                                    add(
                                        kotlinx.serialization.json.buildJsonObject {
                                            put("text", JsonPrimitive(text))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "generationConfig",
                kotlinx.serialization.json.buildJsonObject {
                    put(
                        "responseModalities",
                        kotlinx.serialization.json.buildJsonArray {
                            add(JsonPrimitive("AUDIO"))
                        },
                    )
                    put(
                        "speechConfig",
                        kotlinx.serialization.json.buildJsonObject {
                            put(
                                "voiceConfig",
                                kotlinx.serialization.json.buildJsonObject {
                                    put(
                                        "prebuiltVoiceConfig",
                                        kotlinx.serialization.json.buildJsonObject {
                                            put("voiceName", JsonPrimitive(voiceName))
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }.toString()

        val req = Request.Builder().url("$baseUrl/models/$modelName:generateContent")
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Gemini TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            val body = resp.body.string()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body) as? JsonObject ?: return ByteArray(0)
            // de candidates[0].content.parts[0].inlineData.data Extraer audio base64
            val candidates = root["candidates"]?.let { it as? JsonArray }
            val firstCandidate = candidates?.firstOrNull() as? JsonObject ?: return ByteArray(0)
            val content = firstCandidate["content"] as? JsonObject ?: return ByteArray(0)
            val parts = content["parts"]?.let { it as? JsonArray } ?: return ByteArray(0)
            val firstPart = parts.firstOrNull() as? JsonObject ?: return ByteArray(0)
            val inlineData = firstPart["inlineData"] as? JsonObject ?: return ByteArray(0)
            val base64Audio = inlineData["data"]?.jsonPrimitive?.contentOrNull ?: return ByteArray(0)
            return android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
        }
    }

    /**
     * DashScope TTS (Nube de Alibaba) — OpenAI Interfaz compatible.
     *
     * POST {endpoint}/audio/speech
     * El cuerpo de la solicitud es coherente con el formato OpenAI TTS.(apoyo speed / response_format).
     */
    private suspend fun synthesizeDashScope(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { DASHSCOPE_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { DASHSCOPE_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { DASHSCOPE_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "DashScope TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * Fish Audio TTS — POST {endpoint}/tts
     *
     * Cuerpo de la solicitud: {"text": "...", "format": "mp3", "reference_id": "...", "normalize": true}
     * respuesta: mp3 flujo binario
     */
    private suspend fun synthesizeFishAudio(text: String, apiKey: String, model: String, voice: String, endpoint: String): ByteArray {
        val baseUrl = endpoint.ifBlank { FISH_DEFAULT_ENDPOINT }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", JsonPrimitive(text))
            put("format", JsonPrimitive("mp3"))
            put("normalize", JsonPrimitive(true))
            if (voice.isNotBlank()) {
                put("reference_id", JsonPrimitive(voice))
            }
            if (model.isNotBlank() && model != FISH_DEFAULT_MODEL) {
                put("model", JsonPrimitive(model))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/tts")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Fish Audio TTS failed: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * ElevenLabs TTS — POST {endpoint}/text-to-speech/{voice_id}
     *
     * Encabezado de solicitud: xi-api-key + Accept: audio/mpeg
     * Cuerpo de la solicitud: {"text": "...", "model_id": "eleven_multilingual_v2",
     *         "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}}
     * respuesta: audio/mpeg flujo binario
     *
     * voice Parámetros para ElevenLabs Voice ID(Como por ejemplo 21m00Tcm4TlvDq8ikWAM,Contiene sonidos clonados.).
     * v1.99: stability / similarity_boost por [cloudConfig] paso a través(4.8 Parámetros configurables).
     */
    private suspend fun synthesizeElevenLabs(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { ELEVENLABS_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { ELEVENLABS_DEFAULT_MODEL }
        val voiceId = voice.ifBlank { ELEVENLABS_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", JsonPrimitive(text))
            put("model_id", JsonPrimitive(modelName))
            put(
                "voice_settings",
                kotlinx.serialization.json.buildJsonObject {
                    put("stability", JsonPrimitive(cloudConfig.stability.coerceIn(0f, 1f).toDouble()))
                    put("similarity_boost", JsonPrimitive(cloudConfig.similarityBoost.coerceIn(0f, 1f).toDouble()))
                },
            )
        }.toString()

        val req = Request.Builder().url("$baseUrl/text-to-speech/$voiceId")
            .header("Content-Type", "application/json")
            .header("xi-api-key", apiKey)
            .header("Accept", "audio/mpeg")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "ElevenLabs La síntesis falló: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * Groq TTS — POST {endpoint}/audio/speech(OpenAI Formatos compatibles)
     *
     * Encabezado de solicitud: Authorization: Bearer {apiKey}
     * Cuerpo de la solicitud: {"model": "playai-tts", "input": "...", "voice": "Fritz-PlayAI"}
     * respuesta: audio/mpeg flujo binario
     */
    private suspend fun synthesizeGroq(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { GROQ_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { GROQ_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { GROQ_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Groq La síntesis falló: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * Qwen TTS(DashScope Interfaz nativa)— POST {endpoint}/services/audio/tts/text-to-audio
     *
     * con dashscope(OpenAI Modo de compatibilidad)diferente,Esta interfaz utiliza DashScope nativo API:
     * Encabezado de solicitud: Authorization: Bearer {apiKey}, X-DashScope-DataInspection: enable
     * Cuerpo de la solicitud: {"model": "cosyvoice-v1", "input": {"text": "..."},
     *         "parameters": {"voice": "longxiaochun"}}
     * respuesta: JSON,El audio está en el campo salida.audio.url(Necesito descargar dos veces).
     */
    private suspend fun synthesizeQwen(text: String, apiKey: String, model: String, voice: String, endpoint: String): ByteArray {
        val baseUrl = endpoint.ifBlank { QWEN_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { QWEN_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { QWEN_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put(
                "input",
                kotlinx.serialization.json.buildJsonObject {
                    put("text", JsonPrimitive(text))
                },
            )
            put(
                "parameters",
                kotlinx.serialization.json.buildJsonObject {
                    put("voice", JsonPrimitive(voiceName))
                },
            )
        }.toString()

        val req = Request.Builder().url("$baseUrl/services/audio/tts/text-to-audio")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-DataInspection", "enable")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Qwen La síntesis falló: HTTP ${resp.code}")
                return ByteArray(0)
            }
            val body = resp.body.string()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body) as? JsonObject
                ?: run {
                    Logger.w(TAG, "Qwen La síntesis falló: Respuesta en formato no JSON")
                    return ByteArray(0)
                }
            // DashScope Estructura de retorno nativa:output.audio.url(Compatible con la parte superior audio.url)
            val audioUrl = (root["output"] as? JsonObject)
                ?.let { it["audio"] as? JsonObject }
                ?.let { it["url"]?.jsonPrimitive?.contentOrNull }
                ?: (root["audio"] as? JsonObject)?.let { it["url"]?.jsonPrimitive?.contentOrNull }
                ?: run {
                    Logger.w(TAG, "Qwen La síntesis falló: Falta respuesta output.audio.url")
                    return ByteArray(0)
                }
            // Segunda descarga de archivos de audio.(Phase 3 P1: withTimeoutOrNull Ten todo en cuenta,Tiempo de espera interrumpido por Call.cancel
            // Lectura del cuerpo de la respuesta bloqueada,No más esperas interminables por el audio URL)
            val audioReq = Request.Builder().url(audioUrl).build()
            val audioBytes = withTimeoutOrNull(audioDownloadTimeoutMs) {
                executeBytesCancellable(audioReq)
            }
            if (audioBytes == null) {
                Logger.w(TAG, "Qwen La síntesis falló: La descarga de audio secundaria se agota o falla(${audioDownloadTimeoutMs}ms)")
                return ByteArray(0)
            }
            return audioBytes
        }
    }

    /**
     * StepFun TTS — POST {endpoint}/audio/speech(OpenAI Formatos compatibles)
     *
     * Encabezado de solicitud: Authorization: Bearer {apiKey}
     * Cuerpo de la solicitud: {"model": "step-tts-mini", "input": "...", "voice": "speaker1"}
     * respuesta: audio/mpeg flujo binario
     */
    private suspend fun synthesizeStep(
        text: String,
        apiKey: String,
        model: String,
        voice: String,
        endpoint: String,
        cloudConfig: CloudTtsConfig,
    ): ByteArray {
        val baseUrl = endpoint.ifBlank { STEP_DEFAULT_ENDPOINT }
        val modelName = model.ifBlank { STEP_DEFAULT_MODEL }
        val voiceName = voice.ifBlank { STEP_DEFAULT_VOICE }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("model", JsonPrimitive(modelName))
            put("input", JsonPrimitive(text))
            put("voice", JsonPrimitive(voiceName))
            put("response_format", JsonPrimitive(cloudConfig.responseFormat.ifBlank { "mp3" }))
            if (cloudConfig.speed != 1.0f) {
                put("speed", JsonPrimitive(cloudConfig.speed.coerceIn(0.25f, 4.0f).toDouble()))
            }
        }.toString()

        val req = Request.Builder().url("$baseUrl/audio/speech")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "Step La síntesis falló: HTTP ${resp.code}")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * xAI TTS — POST {endpoint}/tts
     *
     * v1.99: Implementación completa del marcador de posición. Contrato de proveedor XAITTS existente:
     * Cuerpo de la solicitud: {"text": "...", "voice_id": "...", "language": "..."}
     * respuesta: flujo binario de audio(mp3).
     *
     * language Inferencia simple basada en el nombre del timbre.(Contiene "zh" → zh-CN,Contiene "en" → en-US,De lo contrario predeterminado zh-CN).
     * Si la interfaz ascendente realmente no está disponible,Después de que la llamada falla, la capa superior [synthesizeToFile] Sistema de respaldo TTS.
     */
    private suspend fun synthesizeXai(text: String, apiKey: String, voice: String, endpoint: String): ByteArray {
        val baseUrl = endpoint.ifBlank { XAI_DEFAULT_ENDPOINT }
        val voiceName = voice.ifBlank { XAI_DEFAULT_VOICE }
        val language = when {
            voiceName.contains("zh", ignoreCase = true) -> "zh-CN"
            voiceName.contains("en", ignoreCase = true) -> "en-US"
            else -> "es-ES"
        }

        val payload = kotlinx.serialization.json.buildJsonObject {
            put("text", JsonPrimitive(text))
            put("voice_id", JsonPrimitive(voiceName))
            put("language", JsonPrimitive(language))
        }.toString()

        val reqBuilder = Request.Builder().url("$baseUrl/tts")
            .header("Content-Type", "application/json")
            .header("Accept", "audio/mpeg")
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.build()

        val resp = executeCancellable(req) ?: return ByteArray(0)
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "xAI La síntesis falló: HTTP ${resp.code} (endpoint=$baseUrl, voice=$voiceName)")
                return ByteArray(0)
            }
            return resp.body.bytes()
        }
    }

    /**
     * ElevenLabs Listar todos los sonidos(Contiene clon + preset).
     *
     * GET {baseUrl}/voices,respuesta:{"voices":[{"voice_id","name","category",...}]}
     */
    private suspend fun listElevenLabsVoices(apiKey: String, endpoint: String): List<VoiceInfo> {
        if (apiKey.isBlank()) return emptyList()
        val baseUrl = endpoint.ifBlank { ELEVENLABS_DEFAULT_ENDPOINT }
        val req = Request.Builder().url("$baseUrl/voices")
            .header("xi-api-key", apiKey)
            .header("Accept", "application/json")
            .get()
            .build()
        val resp = executeCancellable(req) ?: return emptyList()
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "ElevenLabs listVoices failed: HTTP ${resp.code}")
                return emptyList()
            }
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(resp.body.string()) as? JsonObject
                ?: return emptyList()
            val arr = root["voices"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj["voice_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
                val category = obj["category"]?.jsonPrimitive?.contentOrNull
                VoiceInfo(id, name, description = category)
            }
        }
    }

    /**
     * MiniMax Lista de sonidos.
     *
     * GET {baseUrl}/list_voice,respuesta:{"voices":[{"voice_id","voice_name",...}]} o
     * {"system_support_voices":[{"voice_id",...}]}(Compatible con ambos nombres de campo).
     */
    private suspend fun listMiniMaxVoices(apiKey: String, endpoint: String): List<VoiceInfo> {
        if (apiKey.isBlank()) return emptyList()
        val baseUrl = endpoint.ifBlank { MINIMAX_DEFAULT_ENDPOINT }
        val req = Request.Builder().url("$baseUrl/list_voice")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()
        val resp = executeCancellable(req) ?: return emptyList()
        resp.use { resp ->
            if (!resp.isSuccessful) {
                Logger.w(TAG, "MiniMax listVoices failed: HTTP ${resp.code}")
                return emptyList()
            }
            val root = Json { ignoreUnknownKeys = true }
                .parseToJsonElement(resp.body.string()) as? JsonObject
                ?: return emptyList()
            val arr = (root["voices"] as? JsonArray)
                ?: (root["system_support_voices"] as? JsonArray)
                ?: return emptyList()
            return arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = obj["voice_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["voice_name"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull
                    ?: id
                VoiceInfo(id, name)
            }
        }
    }
}

/**
 * v1.99: Enumeración del motor TTS en la nube(usado para [CloudTtsService.listVoices] tipo de seguridad).
 *
 * con [MediaConfig.ttsEngine] Correspondencia uno a uno entre cadenas,CLONED por [VoiceCloningService] gestión.
 */
enum class CloudTtsProvider(val engineId: String) {
    OPENAI("openai"),
    MINIMAX("minimax"),
    EDGE("edge"),
    GEMINI("gemini"),
    DASHSCOPE("dashscope"),
    FISH("fish"),
    ELEVENLABS("elevenlabs"),
    GROQ("groq"),
    QWEN("qwen"),
    STEP("step"),
    XAI("xai"),
    CLONED("cloned"),
    ;

    companion object {
        fun fromEngine(id: String): CloudTtsProvider? = values().firstOrNull { it.engineId == id }
    }
}

/**
 * v1.99: información del timbre(Visualización y selección de lista de tonos para extracción dinámica).
 */
data class VoiceInfo(
    val id: String,
    val name: String,
    val language: String? = null,
    val gender: String? = null,
    val description: String? = null,
)

/**
 * v1.99: Parámetros del motor TTS en la nube(4.8 Parámetros configurables).
 *
 * Cada motor está disponible bajo demanda.:
 *  - ElevenLabs: [stability] / [similarityBoost]
 *  - MiniMax: [emotion] / [speed]
 *  - OpenAI compatibles(openai/dashscope/groq/step/edge): [speed] / [responseFormat]
 *  - Los motores que no admiten SSML/parámetros se degradan a los valores predeterminados
 */
data class CloudTtsConfig(
    /** ElevenLabs stability(0-1,Predeterminado 0.5). */
    val stability: Float = 0.5f,
    /** ElevenLabs similarity_boost(0-1,Predeterminado 0.75). */
    val similarityBoost: Float = 0.75f,
    /** MiniMax emoción(happy/sad/angry/neutral espera,Vacío significa predeterminado). */
    val emotion: String = "",
    /** Multiplicador de velocidad de voz sintética(0.25-4.0,1.0 para normales). */
    val speed: Float = 1.0f,
    /** formato de audio(mp3/opus/aac/flac/wav). */
    val responseFormat: String = "mp3",
)
