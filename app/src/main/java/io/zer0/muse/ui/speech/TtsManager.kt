package io.zer0.muse.ui.speech

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.ResolvedSpeechEndpoint
import io.zer0.muse.data.SpeechProviderResolver
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.ui.common.feedback.MuseToast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 8.7: administrador de texto a voz(Android sistema TextToSpeech).
 *
 * opciones de diseño: Utilice el motor TextToSpeech que viene con el marco de Android,0 APK Volumen,
 * con existente [SpeechInput](sistema Intent ASR)simetria — El servicio del sistema es responsable de la entrada./producción.
 *
 * v1.4 Transformación:
 *  - ya no se usa TextToSpeech.speak() Juega en tiempo real(sin pausa/Progreso),
 *    Utilice synthesizeToFile en su lugar para escribir archivos wav temporales → MediaPlayer jugar
 *  - usar [TextChunker] Dividido por puntuación,Síntesis en serie + reproducción,Pausa de soporte/restaurar/avance rápido/velocidad
 *  - expuesto [playbackState] StateFlow dar UI(progreso de doble anillo:Progreso de la película + progreso del audio en la película)
 *  - Mantener singleton TextToSpeech(muse Ya diseñado,Evite crear nuevos cada vez)
 *  - Reservar [speakStream] Lectura en streaming + [stripMarkdown] + [applyConfig] configuración de medios
 *
 * relación de llamada:
 *  - ChatViewModel.toggleTts → [speak] (utteranceId Resaltado de mensajes de seguimiento)
 *  - TtsControllerWidget → [playbackState] / [pause] / [resume] / [stop] / [seekBy] / [setSpeed]
 *
 * @param context Solicitud Context(Se utiliza para crear una instancia de TextToSpeech + acceso cacheDir)
 */
class TtsManager(
    context: Context,
    /** v1.97: Servicio TTS en la nube(cuando ttsEngine != "system" usado cuando). */
    private val cloudTtsService: CloudTtsService,
) {

    companion object {
        // M-SP2 Reparación: Promocionar la expresión regular recién creada para cada llamada en talkStream/stripMarkdown a una constante de objeto complementario,
        // Evite la sobrecarga de compilación de patrones Regex(13 un Regex × por mensaje = Estrés significativo de GC)
        private val SENTENCE_END_REGEX = Regex("[。！？.!?]")
        private val HEADING_PREFIX_REGEX = Regex("^#+\\s*")
        private val UNORDERED_LIST_REGEX = Regex("^[-*+]\\s+")
        private val ORDERED_LIST_REGEX = Regex("^\\d+\\.\\s+")
        private val QUOTE_PREFIX_REGEX = Regex("^>\\s*")
        private val IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
        private val LINK_REGEX = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
        private val INLINE_CODE_REGEX = Regex("`([^`]*)`")
        private val BOLD_ASTERISK_REGEX = Regex("\\*\\*([^*]*)\\*\\*")
        private val BOLD_UNDERSCORE_REGEX = Regex("__([^_]*)__")
        private val ITALIC_ASTERISK_REGEX = Regex("\\*([^*]*)\\*")
        private val ITALIC_UNDERSCORE_REGEX = Regex("_([^_]*)_")
        private val HORIZONTAL_RULE_REGEX = Regex("---+")

        /**
         * v1.97: Lista de motores TTS en la nube compatibles(engineId → Recurso de cadena de nombre para mostrar).
         *
         * "system" No en esta lista(Representado por el valor predeterminado de SettingsRepository,UI Manejar por separado).
         * El orden determina el orden en que se muestran las páginas de configuración.
         */
        val CLOUD_TTS_ENGINES: List<Pair<String, Int>> = listOf(
            "openai" to R.string.settings_media_tts_engine_openai,
            "minimax" to R.string.settings_media_tts_engine_minimax,
            "edge" to R.string.settings_media_tts_engine_edge,
            "gemini" to R.string.settings_media_tts_engine_gemini,
            "dashscope" to R.string.settings_media_tts_engine_dashscope,
            "fish" to R.string.settings_media_tts_engine_fish,
            "elevenlabs" to R.string.settings_media_tts_engine_elevenlabs,
            "groq" to R.string.settings_media_tts_engine_groq,
            "qwen" to R.string.settings_media_tts_engine_qwen,
            "step" to R.string.settings_media_tts_engine_step,
            "xai" to R.string.settings_media_tts_engine_xai,
        )
    }

    /** El prefijo utteranceId utilizado para sintetizar archivos(Distinguir entre verdadero de talkStream id). */
    private val SYNTH_PREFIX = "syn_"

    private val appContext = context.applicationContext
    private val cacheDir: File = appContext.cacheDir
    private val ready = AtomicBoolean(false)

    /**
     * v1.0.4 (P2): TTS Estado listo para la inicialización(expuesto a UI).
     *
     * Inicialización asíncrona del motor TTS del sistema,Es posible que no esté listo cuando haga clic por primera vez en Leer en voz alta.
     * UI Este flujo se puede leer para determinar si se necesitan indicaciones"TTS Inicializando".
     * (El motor TTS en la nube no requiere este juicio.,Simplemente llama y habla directamente.)
     */
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    /**
     * Estado de error de inicialización.
     *
     * Solucione los errores anteriores:init En caso de fallo, sólo se recuerdan las banderas internas,UI No tengo forma de saberlo, así que sigo recordándolo.
     * “TTS Inicializando” —— Lo que ve el usuario es“siempre en inicialización”.
     * UI Este flujo debe juzgarse primero: avisar cuando falla“Falló la inicialización del motor de voz”(y arrancar el motor TTS),
     * en lugar de indefinidamente“Por favor inténtalo de nuevo más tarde.”.
     */
    private val _initFailed = MutableStateFlow(false)
    val initFailedState: StateFlow<Boolean> = _initFailed.asStateFlow()
    private var currentUtteranceId: String? = null

    /**
     * v2.x: búfer de adición de cola — speak(flush=false) y esta jugando,Se cuelgan nuevos fragmentos en esta cola.,
     * Continuar en secuencia después de reproducir el segmento actual(Como por ejemplo"Leer varios mensajes uno tras otro"escena). detenerse/Se borra cuando se interrumpe.
     */
    private val pendingTail = ArrayDeque<Pair<List<String>, String>>()

    /** v0.52: Almacenamiento en búfer de oraciones para lectura en streaming(Espere la frase completa antes de entregársela. TTS,Evite la fragmentación de oraciones a nivel de token y oraciones incómodas). */
    private val sentenceBuffer = StringBuilder()

    /** Leyendo devolución de llamada de estado: (utteranceId, isSpeaking) — UI Cambie el icono en consecuencia. */
    var onStateChange: ((utteranceId: String?, isSpeaking: Boolean) -> Unit)? = null

    /** v0.33: Configuración de medios actualmente efectiva(Suscríbase a la llamada posterior a mediaConfigFlow de MuseApp [applyConfig] actualizar). */
    @Volatile
    private var mediaConfig: MediaConfig = MediaConfig()

    /**
     * Lista de proveedores actual(Se llama después de que MuseApp se suscribe a proveedoresFlow [applyProviders] actualizar).
     *
     * Solo se utiliza para el análisis del modo "Seguir la configuración del proveedor" baseUrl / apiKey,Completamente desacoplado cuando el interruptor está apagado.
     */
    @Volatile
    private var providerConfigs: List<ProviderConfig> = emptyList()

    /** El último motivo de reserva registrado,Evite repetir el mismo WARN para cada síntesis. */
    @Volatile
    private var lastFallbackReason: String? = null

    /**
     * Lista de proveedores de aplicaciones(Para "Seguir la configuración del proveedor" para analizar las credenciales).
     *
     * Suscríbete por MuseApp [io.zer0.muse.data.SettingsRepository.providersFlow] llamado más tarde.
     */
    fun applyProviders(configs: List<ProviderConfig>) {
        providerConfigs = configs
    }

    /**
     * Analice el efecto real de este TTS en la nube baseUrl / apiKey / model.
     *
     * Volver al modo manual tal cual [mediaConfig] El valor completado a mano en;Falta el patrón de seguimiento en el proveedor/desactivar/Cuando no se selecciona ningún modelo
     * Vuelva a la configuración manual y escriba una línea aquí WARN(Solo pelea una vez por la misma razón.,Evite sintetizar frase por frase para cepillar los registros.).
     */
    private fun resolveCloudEndpoint(): ResolvedSpeechEndpoint {
        val resolved = SpeechProviderResolver.resolveMedia(mediaConfig, providerConfigs)
        if (resolved.isProviderFallback) {
            if (lastFallbackReason != resolved.fallbackReason) {
                lastFallbackReason = resolved.fallbackReason
                Logger.w(
                    "TtsManager",
                    "TTS Error al seguir la configuración del proveedor,Volver a la configuración manual: ${resolved.fallbackReason}" +
                        "(providerId=${mediaConfig.ttsProviderId}, modelId=${mediaConfig.ttsModelId})",
                )
            }
        } else {
            lastFallbackReason = null
        }
        return resolved
    }

    /** Consulta la lista de sonidos TTS disponibles en el sistema. */
    fun getAvailableVoices(): List<android.speech.tts.Voice> {
        return if (ready.get()) {
            @Suppress("DEPRECATION")
            tts.voices?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * v1.99(4.4): Extraiga dinámicamente la lista de timbres del motor TTS en la nube.
     *
     * Para uso del selector de tono de la página de configuración TTS,Devuelve una lista vacía en caso de error.
     */
    suspend fun listCloudVoices(): List<VoiceInfo> {
        val engine = mediaConfig.ttsEngine
        val provider = CloudTtsProvider.fromEngine(engine) ?: return emptyList()
        val resolved = resolveCloudEndpoint()
        return cloudTtsService.listVoices(provider, resolved.apiKey, resolved.baseUrl)
    }

    /**
     * v1.99(4.8): por corriente [mediaConfig] estructura [CloudTtsConfig].
     *
     * Cada motor accede a los campos correspondientes según sea necesario;dejar en blanco/El valor predeterminado viene dado por [CloudTtsService] Todos se dan cuenta de la verdad.
     */
    private fun buildCloudConfig(emotionOverride: String? = null): CloudTtsConfig = CloudTtsConfig(
        stability = mediaConfig.ttsStability,
        similarityBoost = mediaConfig.ttsSimilarityBoost,
        // Los mensajes de voz transmiten emociones según parámetros: solo se cubre esta síntesis y la situación global no cambia (TtsManager Singleton, el enlace de lectura puede estar ejecutándose al mismo tiempo)
        emotion = emotionOverride?.takeIf { it.isNotBlank() } ?: mediaConfig.ttsEmotion,
        speed = mediaConfig.ttsCloudSpeed,
        responseFormat = mediaConfig.ttsResponseFormat.ifBlank { "mp3" },
    )

    /** Velocidad de reproducción actual(por [setSpeed] ajustes,Afecta la velocidad de reproducción de MediaPlayer,No afecta la síntesis de TTS.). */
    @Volatile
    private var playbackSpeed: Float = 1.0f

    /** R-UI-07: Estado de error de inicialización del TTS del sistema y aviso único. */
    @Volatile
    private var initFailed = false

    @Volatile
    private var initHintShown = false

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            // Chino predeterminado(Volver al idioma predeterminado cuando el TTS del sistema no lo admite)
            applyLanguage(Locale.CHINESE)
            ready.set(true)
            // v1.0.4 (P2): expuesto sincrónicamente a UI
            _isReady.value = true
        } else {
            // v1.0.47: TTS init La mayoría de las fallas ocurren porque el dispositivo no tiene un motor TTS.,es una situación común,Baje de Logger.e a Logger.d para reducir el ruido del registro de inicio
            Logger.d("TtsManager", "TTS init failed: status=$status")
            // v1.98: Eliminar mensaje de brindis,Procesamiento silencioso(Naturalmente, los usuarios sabrán cuando la función de lectura en voz alta no esté disponible.)
            initFailed = true
            // UI necesito saber“es un fracaso”en lugar de“Todavía inicializando”,De lo contrario, el mensaje siempre se detendrá en“Por favor inténtalo de nuevo más tarde.”
            _initFailed.value = true
        }
    }

    /** v1.4: synthesizeToFile Controlador de devolución de llamada de finalización(de serie,Una pieza a la vez). */
    @Volatile
    private var synthesizeDone: CompletableDeferred<Unit>? = null

    /** v1.4: Instancia actual de MediaPlayer(pause/resume/seekBy Objetivo de operación). */
    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    /** v1.4: Corrutina de juego en serie(Composición + Reproducción + Actualización de progreso). */
    private var playbackJob: Job? = null

    /** v1.4: rutina de sondeo de ubicación(Actualizar cada 100 ms positionMs). */
    private var positionUpdateJob: Job? = null

    // ── v1.99: Tubería de transmisión de TTS en la nube(4.3 La lectura en streaming es compatible con la nube TTS) ──
    // Serie de doble canal:cola de oraciones → Corrutina sintética → cola de archivos → Jugar rutina
    // La rutina sintética se ejecuta primero.,Reproducir archivos de consumo de rutina,darse cuenta"Toca mientras compones"línea de montaje,
    // La primera frase se sintetiza y se reproduce inmediatamente.,Sintetiza la segunda oración al mismo tiempo.,Reduce significativamente la latencia de la primera frase.
    private var cloudSynthChannel: Channel<String> = Channel(Channel.UNLIMITED)
    private var cloudPlayChannel: Channel<File> = Channel(Channel.UNLIMITED)
    private var cloudSynthJob: Job? = null
    private var cloudPlayJob: Job? = null

    @Volatile
    private var cloudStreamUtteranceId: String? = null

    /** v1.4: Jugar rutina scope(Main hilo,MediaPlayer Requiere operación del hilo principal). */
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** v1.4: Lista de fragmentos actual(para visualización de estado totalChunks). */
    @Volatile
    private var currentChunks: List<String> = emptyList()

    /** v1.4: Estado de juego — Expuesto al controlador flotante impulsado por la interfaz de usuario. */
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // speakStream tomar el camino onStateChange;La síntesis de ruta sintética no se activa UI
                if (utteranceId == null || utteranceId.startsWith(SYNTH_PREFIX)) return
                currentUtteranceId = utteranceId
                onStateChange?.invoke(utteranceId, true)
            }

            override fun onDone(utteranceId: String?) {
                val id = utteranceId ?: return
                if (id.startsWith(SYNTH_PREFIX)) {
                    // Síntesis completada,despierta esperando synthesizeToFile
                    synthesizeDone?.complete(Unit)
                } else {
                    // speakStream Frase única terminada
                    currentUtteranceId = null
                    onStateChange?.invoke(id, false)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                handleSynthOrSpeakError(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                handleSynthOrSpeakError(utteranceId)
            }
        })
    }

    /** síntesis de procesamiento unificado/Leer devolución de llamada de error. */
    private fun handleSynthOrSpeakError(utteranceId: String?) {
        val id = utteranceId ?: return
        if (id.startsWith(SYNTH_PREFIX)) {
            synthesizeDone?.completeExceptionally(RuntimeException("TTS synthesize error"))
        } else {
            currentUtteranceId = null
            onStateChange?.invoke(id, false)
        }
    }

    /**
     * v0.33: Aplicar configuración de medios(Velocidad del habla/tono/lenguaje).
     *
     * Suscríbete por MuseApp [io.zer0.muse.data.SettingsRepository.mediaConfigFlow] llamar después,
     * Usuario en "Configuración" → medios de comunicación → El "Anuncio de voz" entrará en vigor inmediatamente después del ajuste.(Los nuevos parámetros se aplicarán la próxima vez que lea en voz alta.).
     *
     * @param config Nueva configuración de medios
     */
    fun applyConfig(config: MediaConfig) {
        mediaConfig = config
        if (ready.get()) {
            // velocidad al hablar(0.5-2.0,1.0 para normales)— Afecta la tasa de síntesis de TTS
            tts.setSpeechRate(globalSpeechRate())
            // tono(0.5-2.0,1.0 para normales)
            tts.setPitch(globalPitch())
            // Idioma(null Sigue el sistema,De lo contrario intenta cambiar)
            applyLanguage(globalLanguage())
            // Sonido TTS del sistema(Sólo tiene efecto cuando el motor está en sistema)
            if (config.ttsVoiceName.isNotBlank() && config.ttsEngine == "system") {
                val voice = tts.voices?.firstOrNull { it.name == config.ttsVoiceName }
                if (voice != null) {
                    tts.setVoice(voice)
                }
            }
            Logger.d(
                "TtsManager",
                "applyConfig: rate=${globalSpeechRate()}, pitch=${globalPitch()}, lang=${globalLanguage()}, voice=${config.ttsVoiceName}",
            )
        }
    }

    /** Velocidad de voz para la configuración de medios globales(0.5-2.0). */
    private fun globalSpeechRate(): Float = mediaConfig.ttsSpeechRate.coerceIn(0.5f, 2.0f)

    /** discurso de configuración de medios globales(0.5-2.0). */
    private fun globalPitch(): Float = mediaConfig.ttsPitch.coerceIn(0.5f, 2.0f)

    /** Idioma para la configuración global de medios Locale(Seguir el sistema cuando no esté configurado). */
    private fun globalLanguage(): Locale = mediaConfig.ttsLanguage?.takeIf { it.isNotBlank() }?.let { parseLocale(it) }
        ?: Locale.getDefault()

    /** Analice las cadenas BCP-47/ISO-639 en la configuración regional. */
    private fun parseLocale(tag: String): Locale {
        // Análisis sencillo:apoyo "zh"/"zh-CN"/"en-US" espera
        return runCatching {
            val parts = tag.replace("_", "-").split("-")
            if (parts.size == 1) {
                Locale(parts[0])
            } else {
                Locale(parts[0], parts[1])
            }
        }.getOrDefault(Locale.getDefault())
    }

    /** idioma de aplicación(Volver al valor predeterminado si no es compatible). */
    private fun applyLanguage(locale: Locale) {
        val result = tts.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Logger.w("TtsManager", "Language $locale not available, fallback to default")
            tts.setLanguage(Locale.getDefault())
        }
    }

    /**
     * Sintetiza texto en el archivo especificado (sin reproducir).
     *
     * Para personas que llaman y necesitan convertir la voz en un archivo de larga duración (mensaje de voz); por leer el enlace en voz alta
     * `playChunks` / `speakStream` No afectado, no pasa por aquí.
     *
     * La misma implementación sintética se reutiliza internamente: el motor de la nube tiene prioridad, el TTS del sistema recurre en caso de falla y se garantiza un tiempo de espera de 30 segundos para un solo chip.
     * La implementación de la composición utiliza un controlador de devolución de llamada de finalización única para los resultados,**No llamar al mismo tiempo**.
     *
     * @param text Texto sin formato a sintetizar (no fragmentado, la persona que llama decide la longitud)
     * @param outputFile El archivo de destino se creará automáticamente si el directorio principal no existe.
     * @param emotionOverride La emoción sintetizada esta vez (vacío/Usar configuración global cuando esté en blanco); solo aplica para esta convocatoria
     * @return Uno de tres resultados: éxito del motor preferido/éxito del sonido del sistema de respaldo/fallo total (consulte [TtsSynthResult])
     */
    suspend fun synthesizeTo(text: String, outputFile: File, emotionOverride: String? = null): TtsSynthResult {
        if (text.isBlank()) return TtsSynthResult.Failure("El texto a sintetizar está vacío.")
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                Logger.w("TtsManager", "synthesizeTo: Error al crear el directorio de destino ${parent.absolutePath}")
                return TtsSynthResult.Failure("Error al crear el directorio de destino")
            }
        }
        return synthesizeToFile(text, outputFile, emotionOverride)
    }

    /**
     * v1.4: Compone una sola pieza de texto en un archivo wav temporal.
     *
     * usar [TextToSpeech.synthesizeToFile] Escritura asincrónica,[UtteranceProgressListener.onDone]
     * La devolución de llamada despierta la rutina en espera.30 Segundo tiempo de espera(Evite ciertas devoluciones de llamada de ROM atascadas).
     *
     * @param text Texto plano a sintetizar(monolítico,fragmentado)
     * @param file Ruta del archivo wav de destino(La persona que llama es responsable de crear/limpiar)
     * @param emotionOverride La cobertura emocional de esta síntesis.(El enlace de lectura no se transmite.,Heredar configuración global)
     * @return uno de tres resultados,ver [TtsSynthResult]
     */
    private suspend fun synthesizeToFile(text: String, file: File, emotionOverride: String? = null): TtsSynthResult {
        // v1.97: El motor TTS en la nube toma el camino CloudTtsService
        if (mediaConfig.ttsEngine != "system") {
            val resolved = resolveCloudEndpoint()
            val cloudOk = cloudTtsService.synthesizeToFile(
                text = text,
                engine = mediaConfig.ttsEngine,
                apiKey = resolved.apiKey,
                model = resolved.model,
                voice = mediaConfig.ttsVoice,
                endpoint = resolved.baseUrl,
                outputFile = file,
                cloudConfig = buildCloudConfig(emotionOverride),
            )
            // v1.104: Recurrir al sistema cuando falla el TTS en la nube TTS,Evite cortes de red/La lectura silenciosa falla cuando se agota la cuota
            val reason = cloudFailureReason(resolved)
            if (!cloudOk) Logger.w("TtsManager", "Cloud TTS no surte efecto: $reason")
            return resolveCloudSynthOutcome(
                cloudOk = cloudOk,
                failureReason = reason,
                systemTtsReady = ready.get(),
            ) { synthesizeWithSystemTts(text, file) }
        }

        return if (synthesizeWithSystemTts(text, file)) {
            TtsSynthResult.Success
        } else {
            TtsSynthResult.Failure("La síntesis TTS del sistema falló")
        }
    }

    /**
     * Una breve razón para que la persona que llama (y el usuario) vea cuando la nube no está sintetizada.
     *
     * Priorizar el uso de la razón alternativa de la capa de análisis (siguiendo el motivo específico del error de configuración del proveedor); si no,
     * La clave API vacía es un defecto común que se puede ver de un vistazo y debe señalarse por separado; al final, es un fallo general de síntesis.
     */
    private fun cloudFailureReason(resolved: ResolvedSpeechEndpoint): String =
        resolved.fallbackReason
            ?: if (resolved.apiKey.isBlank()) "API Key esta vacio" else "Error en la síntesis de TTS en la nube"

    /**
     * v1.104: Síntesis TTS del sistema(Extraído de sintetizarToFile,Proporcionado para el respaldo y la reutilización de fallas en la nube.).
     */
    private suspend fun synthesizeWithSystemTts(text: String, file: File): Boolean {
        if (!ready.get()) {
            Logger.w("TtsManager", "synthesizeToFile: TTS not ready")
            return false
        }
        // Asegúrese de que se apliquen los parámetros de composición(Evite el uso de valores predeterminados cuando lea en voz alta por primera vez.)
        if (globalSpeechRate() != 1.0f || globalPitch() != 1.0f) {
            tts.setSpeechRate(globalSpeechRate())
            tts.setPitch(globalPitch())
        }
        applyLanguage(globalLanguage())
        val deferred = CompletableDeferred<Unit>()
        synthesizeDone = deferred
        val utteranceId = SYNTH_PREFIX + System.nanoTime()
        // synthesizeToFile sobrecarga:(CharSequence, Bundle, File, String) — Pasar archivo en lugar de ruta
        val result = tts.synthesizeToFile(text, Bundle(), file, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            synthesizeDone = null
            Logger.w("TtsManager", "synthesizeToFile failed: result=$result")
            return false
        }
        return try {
            // 30 Segundo tiempo de espera(Una sola pieza de 150 caracteres normalmente se completa en segundos)
            val ok = withTimeoutOrNull(30_000) { deferred.await() } != null
            if (!ok) Logger.w("TtsManager", "synthesizeToFile timeout: ${text.take(20)}...")
            ok
        } catch (e: Exception) {
            Logger.e("TtsManager", "synthesizeToFile error: ${e.message}")
            false
        } finally {
            synthesizeDone = null
        }
    }

    /**
     * Leer texto en voz alta(v1.4 Transformación:Cortar + Componer en archivo + Reproducción en serie de MediaPlayer).
     *
     * Compatible con personas mayores:Mantener el parámetro utteranceId(Se utiliza en la interfaz de usuario para resaltar el mensaje que se está reproduciendo actualmente.).
     * Para uso interno [TextChunker] Dividido por puntuación,Síntesis en serie + reproducción,Pausa de soporte/restaurar/avance rápido/velocidad.
     *
     * @param text Texto sin formato para leer en voz alta.(Contener Markdown será [stripMarkdown] limpiar)
     * @param utteranceId Seguimiento id(normalmente envio noticias id,Se utiliza para alternar iconos de UI)
     * @param flush true interrumpir la solicitud anterior(Predeterminado);false Cola para agregar(Al reproducir, continúa el segmento actual después de reproducirlo.)
     * @return true Empezó a jugar;false TTS No listo o el texto está vacío
     */
    fun speak(text: String, utteranceId: String, flush: Boolean = true): Boolean {
        // v1.97: Cloud TTS no necesita esperar a que el sistema TTS esté listo
        if (mediaConfig.ttsEngine == "system" && !ready.get()) {
            Logger.w("TtsManager", "TTS not ready yet")
            if (initFailed && !initHintShown) {
                initHintShown = true
                MuseToast.show(appContext.getString(R.string.speech_tts_no_engine), 2500)
            }
            return false
        }
        // v0.52: Borrar el búfer de transmisión al leer en su totalidad,Evite cadenas de texto residuales
        sentenceBuffer.clear()
        val clean = MoodSkinParser.cleanForExport(stripMarkdown(text)).trim()
        if (clean.isEmpty()) return false
        // Utilice TextChunker para dividir por puntuación
        val chunks = TextChunker.chunk(clean)
        if (chunks.isEmpty()) return false
        // Manejo de nuevas solicitudes:
        // - flush=true(Predeterminado): interrumpir la solicitud anterior,Reproduce contenido nuevo ahora
        // - flush=false: si juegas,Reproducir después de que finalice el segmento actual(Cola para agregar),no interrumpas
        if (flush) {
            stopInternal(resetState = false)
            pendingTail.clear()
        } else if (playbackJob?.isActive == true) {
            pendingTail.addLast(chunks to utteranceId)
            Logger.d("TtsManager", "speak(flush=false): Cola para agregar ${chunks.size} película,profundidad de la cola=${pendingTail.size}")
            return true
        }
        currentUtteranceId = utteranceId
        currentChunks = chunks
        // Marcar inicio de lectura en voz alta(UI Resaltar)
        onStateChange?.invoke(utteranceId, true)
        // Iniciar la rutina de reproducción en serie
        startPlayback(chunks, utteranceId)
        return true
    }

    /**
     * v1.4: Iniciar la rutina de reproducción en serie(fusionarse en uno → tocar una pieza → siguiente pieza).
     *
     * La corrutina está en [playbackScope] (Main hilo)ejecutar,MediaPlayer La operación debe ser en el hilo principal.
     * Cuando se cancela la rutina(stop / nuevo speak),MediaPlayer Liberación y limpieza de archivos temporales.
     */
    private fun startPlayback(chunks: List<String>, utteranceId: String) {
        playbackJob?.cancel()
        startPositionUpdates()
        playbackJob = playbackScope.launch {
            updateState {
                it.copy(
                    status = PlaybackStatus.Playing,
                    currentChunkIndex = 0,
                    totalChunks = chunks.size,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
            // v2.x: Soporte adicional en cola — El cuerpo principal se reproduce segmento por segmento.,Consumo entre segmentos [pendingTail](flush=false Se agregaron segmentos posteriores)
            var segment = chunks
            var segmentUtterance = utteranceId
            while (isActive) {
                playChunks(segment, segmentUtterance)
                val next = if (isActive) pendingTail.removeFirstOrNull() else null
                if (next == null) break
                segment = next.first
                segmentUtterance = next.second
                currentUtteranceId = segmentUtterance
                currentChunks = segment
                updateState {
                    it.copy(
                        currentChunkIndex = 0,
                        totalChunks = segment.size,
                        positionMs = 0L,
                        durationMs = 0L,
                    )
                }
                onStateChange?.invoke(segmentUtterance, true)
            }
            // Terminé de jugar todo
            if (isActive) {
                updateState { it.copy(status = PlaybackStatus.Ended, positionMs = it.durationMs) }
                currentUtteranceId = null
                onStateChange?.invoke(segmentUtterance, false)
            }
        }
    }

    /**
     * v2.x: Reproducir un segmento(fusionarse en uno → tocar una pieza),suministro [startPlayback] La ruta principal se comparte con la reproducción en cola.
     *
     * L-SP3: Utilice try-finally para asegurarse de que los archivos wav temporales se limpien en cualquier ruta,
     * Contiene: La síntesis falló(continue), cancelación de rutina(CancellationException finalmente ejecutado al propagar), se completa la reproducción normal.
     * La cancelación se propaga naturalmente mediante la cadena de llamada suspendida.(synthesizeToFile / playWithMediaPlayer),No se requiere verificación explícita.
     */
    private suspend fun playChunks(chunks: List<String>, utteranceId: String) {
        for ((index, chunk) in chunks.withIndex()) {
            updateState { it.copy(currentChunkIndex = index, positionMs = 0L, durationMs = 0L) }
            // Componer en un archivo wav temporal
            val file = File(cacheDir, "tts_chunk_${utteranceId}_$index.wav")
            try {
                val result = synthesizeToFile(chunk, file)
                // No hay cambios perceptibles por el usuario en el enlace de lectura en voz alta: el sonido del sistema seguirá reproduciéndose cuando el sistema se caiga, y la siguiente pieza se omitirá si falla por completo.
                if (result is TtsSynthResult.Failure) continue
                // Jugar con MediaPlayer(Cuando está en pausa, la corrutina se cuelga en await completion)
                playWithMediaPlayer(file)
            } finally {
                file.delete()
            }
        }
    }

    /**
     * v1.4: Reproduce archivos wav temporales con MediaPlayer(Suspender hasta que se complete la reproducción).
     *
     * - prepareAsync Preparación asincrónica → onPrepared en devolución de llamada start
     * - Aplicar actual [playbackSpeed]
     * - onCompletion Corrutina de reanudación de llamada,Ir a la siguiente pieza
     * - Cuando la corrutina se cancela(onCancellation)liberar MediaPlayer
     *
     * @param file archivo wav a reproducir
     */
    private suspend fun playWithMediaPlayer(file: File) {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val mp = MediaPlayer()
            mediaPlayer = mp
            try {
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { player ->
                    // Velocidad de reproducción de la aplicación(API 23+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        runCatching {
                            player.playbackParams = player.playbackParams.setSpeed(playbackSpeed)
                        }.onFailure { Logger.w("TtsManager", "apply playback speed failed: ${it.message}", it) }
                    }
                    player.start()
                    updateState { it.copy(durationMs = player.duration.toLong()) }
                }
                mp.setOnCompletionListener { player ->
                    player.release()
                    if (mediaPlayer === player) mediaPlayer = null
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                mp.setOnErrorListener { player, what, extra ->
                    Logger.e("TtsManager", "MediaPlayer error: what=$what extra=$extra")
                    // v1.68: Notificar al usuario cuando se produzcan errores de reproducción.,De lo contrario, la lectura se detiene repentinamente sin ningún aviso.
                    MuseToast.show(appContext.getString(R.string.speech_tts_play_error), 2500)
                    runCatching { player.release() }
                    if (mediaPlayer === player) mediaPlayer = null
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    true
                }
                mp.prepareAsync()
            } catch (e: Exception) {
                Logger.e("TtsManager", "playWithMediaPlayer setup failed: ${e.message}")
                runCatching { mp.release() }
                if (mediaPlayer === mp) mediaPlayer = null
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                runCatching { mp.release() }
                if (mediaPlayer === mp) mediaPlayer = null
            }
        }
    }

    /** v1.4: Iniciar la rutina de sondeo de ubicación(Actualizar cada 100 ms positionMs,Para que la barra de progreso se actualice). */
    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = playbackScope.launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    runCatching {
                        val pos = mp.currentPosition.toLong()
                        updateState { it.copy(positionMs = pos) }
                    }.onFailure { Logger.w("TtsManager", "position update failed: ${it.message}", it) }
                }
                delay(100)
            }
        }
    }

    /** v1.4: Pausar la reproducción(MediaPlayer.pause Pausar audio,La corrutina aguanta await completion). */
    fun pause() {
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) {
                    mp.pause()
                    updateState {
                        it.copy(status = PlaybackStatus.Paused, positionMs = mp.currentPosition.toLong())
                    }
                }
            }.onFailure { Logger.w("TtsManager", "pause failed: ${it.message}", it) }
        }
    }

    /** v1.4: Reanudar la reproducción(MediaPlayer.start continuar,La rutina reanuda automáticamente la serialización.). */
    fun resume() {
        mediaPlayer?.let { mp ->
            runCatching {
                if (!mp.isPlaying) {
                    mp.start()
                    updateState { it.copy(status = PlaybackStatus.Playing) }
                }
            }.onFailure { Logger.w("TtsManager", "resume failed: ${it.message}", it) }
        }
    }

    /** v1.4: avance rápido/Rebobinar(Relativo a la ubicación actual seek,[ms] Los números positivos avanzan rápidamente, los números negativos retroceden rápidamente). */
    fun seekBy(ms: Int) {
        mediaPlayer?.let { mp ->
            runCatching {
                val target = (mp.currentPosition + ms).coerceIn(0, mp.duration.coerceAtLeast(0))
                mp.seekTo(target)
                updateState { it.copy(positionMs = target.toLong()) }
            }.onFailure { Logger.w("TtsManager", "seekBy failed: ${it.message}", it) }
        }
    }

    /** v1.4: posición absoluta seek(milisegundos). */
    fun seekTo(ms: Long) {
        mediaPlayer?.let { mp ->
            runCatching {
                val target = ms.coerceIn(0, mp.duration.coerceAtLeast(0).toLong())
                mp.seekTo(target.toInt())
                updateState { it.copy(positionMs = target) }
            }.onFailure { Logger.w("TtsManager", "seekTo failed: ${it.message}", it) }
        }
    }

    /**
     * Saltar a la siguiente pieza(si existe).
     * Navegación segmentada de texto largo para controladores TTS.
     */
    fun nextChunk() {
        val state = _playbackState.value
        val chunks = currentChunks
        if (chunks.isEmpty() || state.currentChunkIndex >= chunks.size - 1) return
        // Cancelar la reproducción de la película actual,Iniciar el siguiente segmento
        stopPlayback()
        startPlaybackFromIndex(chunks, state.currentChunkIndex + 1)
    }

    /**
     * Saltar a la pieza anterior(si existe).
     */
    fun previousChunk() {
        val state = _playbackState.value
        val chunks = currentChunks
        if (chunks.isEmpty() || state.currentChunkIndex <= 0) return
        stopPlayback()
        startPlaybackFromIndex(chunks, state.currentChunkIndex - 1)
    }

    /** Iniciar la reproducción en serie desde el índice de fragmentos especificado(Para reutilización por nextChunk/previousChunk). */
    private fun startPlaybackFromIndex(chunks: List<String>, startIndex: Int) {
        val utteranceId = currentUtteranceId ?: return
        startPositionUpdates()
        playbackJob = playbackScope.launch {
            updateState {
                it.copy(
                    status = PlaybackStatus.Playing,
                    currentChunkIndex = startIndex,
                    totalChunks = chunks.size,
                    positionMs = 0L,
                    durationMs = 0L,
                )
            }
            for (index in startIndex until chunks.size) {
                if (!isActive) return@launch
                updateState { it.copy(currentChunkIndex = index, positionMs = 0L, durationMs = 0L) }
                val file = File(cacheDir, "tts_chunk_${utteranceId}_$index.wav")
                try {
                    val outcome = synthesizeToFile(chunks[index], file)
                    if (outcome is TtsSynthResult.Failure) continue
                    if (!isActive) return@launch
                    playWithMediaPlayer(file)
                } finally {
                    file.delete()
                }
            }
            updateState { it.copy(status = PlaybackStatus.Ended, positionMs = it.durationMs) }
            currentUtteranceId = null
            onStateChange?.invoke(utteranceId, false)
        }
    }

    /**
     * v1.4: Establecer la velocidad de reproducción(Afecta la velocidad de reproducción de MediaPlayer,No afecta la velocidad del habla sintetizada TTS).
     *
     * @param speed 0.8 / 1.0 / 1.2 / 1.5 espera(API 23+ apoyo)
     */
    fun setSpeed(speed: Float) {
        playbackSpeed = speed
        mediaPlayer?.let { mp ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    mp.playbackParams = mp.playbackParams.setSpeed(speed)
                }.onFailure { Logger.w("TtsManager", "setSpeed failed: ${it.message}", it) }
            }
        }
    }

    /**
     * v0.52: Streaming añade texto y lo lee frase por frase.
     *
     * Agregar fragmento incremental al búfer,encontrar oraciones completas(a . ! ?.!? finalizando)Sólo recórtalo y dáselo a TTS,
     * Cola en orden usando QUEUE_ADD,Evite la lectura fragmentada a nivel de token(suena más natural).
     *
     * v1.99(4.3): El motor TTS en la nube adopta una canalización de doble canal(Corrutina sintética → Jugar rutina),
     * La primera frase se sintetiza y se reproduce inmediatamente.,Sintetiza la segunda oración al mismo tiempo.,Reduce significativamente la latencia de la primera frase. El TTS del sistema todavía funciona
     * TextToSpeech.speak() Ruta de cola.
     *
     * Nota:Lectura en streaming y [speak] Una nueva implementación de la exclusión mutua — llamar [speak] dejará de almacenar en búfer la transmisión,
     * llamar [speakStream] Detendrá la nueva reproducción.
     *
     * @param chunk Transmisión de texto incremental(puede contener Markdown,Las frases se despegarán después de cortarlas.)
     * @param utteranceId Seguimiento id(Se utiliza para alternar iconos de UI)
     */
    fun speakStream(chunk: String, utteranceId: String) {
        if (chunk.isEmpty()) return
        // Ruta TTS en la nube:Tubería de doble canal
        if (mediaConfig.ttsEngine != "system") {
            // Los nuevos mensajes interrumpen el flujo anterior.(Un utteranceId diferente se considera un nuevo párrafo de lectura en tiempo real.)
            if (cloudStreamUtteranceId != null && cloudStreamUtteranceId != utteranceId) {
                stopCloudStream()
            }
            // Detenga la reproducción segmentada antes de cambiar a lectura en streaming.(MediaPlayer)
            stopPlayback()
            ensureCloudPipeline(utteranceId)
            sentenceBuffer.append(chunk)
            // Dividido por frase,Alimentar la síntesis Channel
            while (true) {
                val match = SENTENCE_END_REGEX.find(sentenceBuffer) ?: break
                val end = match.range.last + 1
                val sentence = sentenceBuffer.substring(0, end)
                sentenceBuffer.delete(0, end)
                val clean = MoodSkinParser.cleanForExport(stripMarkdown(sentence)).trim()
                if (clean.isNotEmpty() && !cloudSynthChannel.isClosedForSend) {
                    cloudSynthChannel.trySend(clean)
                }
            }
            return
        }
        // Ruta TTS del sistema
        if (!ready.get()) {
            Logger.w("TtsManager", "TTS not ready yet (stream)")
            return
        }
        // Detenga la reproducción segmentada antes de cambiar a lectura en streaming.(MediaPlayer)
        stopPlayback()
        sentenceBuffer.append(chunk)
        // M-SP2: La expresión regular que termina en la oración se ha promovido a constante de objeto complementario SENTENCE_END_REGEX
        while (true) {
            val match = SENTENCE_END_REGEX.find(sentenceBuffer) ?: break
            val end = match.range.last + 1
            val sentence = sentenceBuffer.substring(0, end)
            sentenceBuffer.delete(0, end)
            val clean = MoodSkinParser.cleanForExport(stripMarkdown(sentence)).trim()
            if (clean.isNotEmpty()) {
                // La primera frase interrumpe la antigua petición.,Las colas posteriores se ponen en cola secuencialmente utilizando QUEUE_ADD.
                val mode = if (currentUtteranceId == null) {
                    TextToSpeech.QUEUE_FLUSH
                } else {
                    TextToSpeech.QUEUE_ADD
                }
                currentUtteranceId = utteranceId
                tts.speak(clean, mode, null, utteranceId)
            }
        }
    }

    /**
     * v1.99(4.3): Asegúrese de que la canalización de transmisión en la nube se esté ejecutando.
     *
     * Serie de doble canal:
     *  - Corrutina sintética:de [cloudSynthChannel] tomar sentencia → [synthesizeToFile] → Feed de archivos [cloudPlayChannel]
     *  - Jugar rutina:de [cloudPlayChannel] Obtener archivos → [playWithMediaPlayer] → Eliminar archivos
     *
     * La rutina sintética se ejecuta primero.(UNLIMITED La contrapresión del canal de capacidad no bloquea),Jugar al consumo de rutinas,
     * realizado naturalmente"Toca mientras compones"línea de montaje.flushStream cerrar [cloudSynthChannel]
     * Activar la corrutina sintética para salir. → cerrar [cloudPlayChannel] → Jugar salidas de rutina → gatillo onStateChange(false).
     */
    private fun ensureCloudPipeline(utteranceId: String) {
        if (cloudSynthJob?.isActive == true && !cloudSynthChannel.isClosedForSend) {
            // El oleoducto está funcionando,Actualizar solo utterance id(modo anexar)
            cloudStreamUtteranceId = utteranceId
            return
        }
        // Cancelar las antiguas corrutinas restantes(Puede estar en la etapa final después del cierre.)
        cloudSynthJob?.cancel()
        cloudPlayJob?.cancel()
        // reconstruir Channel(El viejo es close)
        cloudSynthChannel = Channel(Channel.UNLIMITED)
        cloudPlayChannel = Channel(Channel.UNLIMITED)
        cloudStreamUtteranceId = utteranceId
        currentUtteranceId = utteranceId
        onStateChange?.invoke(utteranceId, true)
        startPositionUpdates()
        val uid = utteranceId
        cloudSynthJob = playbackScope.launch {
            try {
                for (sentence in cloudSynthChannel) {
                    if (!isActive) break
                    val file = File(cacheDir, "tts_stream_${uid}_${System.nanoTime()}.mp3")
                    val outcome = try {
                        synthesizeToFile(sentence, file)
                    } catch (e: Exception) {
                        Logger.w("TtsManager", "cloud stream synth failed: ${e.message}")
                        TtsSynthResult.Failure(e.message ?: "Excepción de síntesis de transmisión en la nube")
                    }
                    if (outcome !is TtsSynthResult.Failure) {
                        cloudPlayChannel.send(file)
                    } else {
                        file.delete()
                        // v1.140: La síntesis falló(El TTS del sistema Cloud + de un solo chip ha fallado)→
                        // Recoge el texto restante no reproducido,Bajar al sistema TTS en vivo,Evite las interrupciones silenciosas después de medias frases.
                        // synthesizeToFile Ya hecho en un solo chip cloud→system Ten todo en cuenta,ir aquí tts.speak()
                        // Ruta de transmisión en vivo(sin documentacion / MediaPlayer),Es posible tener éxito incluso si la devolución de llamada de composición del archivo está bloqueada.
                        val remaining = collectRemainingStreamText(sentence)
                        if (remaining.isNotEmpty()) {
                            streamFallbackToSystemTts(remaining, uid)
                        }
                        break
                    }
                }
            } finally {
                // Extremos de rutina compuestos(Channel close o cancelar)→ Apagar la reproducción Channel,Activar el final de la rutina de reproducción.
                cloudPlayChannel.close()
            }
        }
        cloudPlayJob = playbackScope.launch {
            for (file in cloudPlayChannel) {
                if (!isActive) break
                try {
                    playWithMediaPlayer(file)
                } catch (e: Exception) {
                    Logger.w("TtsManager", "cloud stream play failed: ${e.message}")
                } finally {
                    file.delete()
                }
            }
            // Terminé de jugar todo,Activar la interfaz de usuario para contraer el resaltado
            // v1.140: Si ha cambiado al sistema TTS, currentUtteranceId todavía uid,
            //         Limpiado por UtteranceProgressListener.onDone;Salte aquí para evitar borrar el estado antes de tiempo.
            if (cloudStreamUtteranceId == uid && currentUtteranceId == uid) {
                currentUtteranceId = null
                cloudStreamUtteranceId = null
                onStateChange?.invoke(uid, false)
            }
        }
    }

    /**
     * v1.140: Recopila el texto restante en una lectura en voz alta que aún no se ha reproducido.
     *
     * tiempo de llamada:[ensureCloudPipeline] Después de que falla una parte de la corrutina sintética,necesito:
     *  - Sentencia fallida actual [failedSentence]
     *  - todavía [cloudSynthChannel] Poner en cola oraciones no sintetizadas en
     *  - buffer [sentenceBuffer] El texto restante que aún no ha sido segmentado.
     * recogerlos todos,Déjelo en manos del sistema TTS para una transmisión única.,Evite las interrupciones a mitad de frase.
     */
    private fun collectRemainingStreamText(failedSentence: String): String {
        val collected = StringBuilder(failedSentence)
        // 1. Drenar las frases en cola en el canal sintetizador.
        while (true) {
            val next = cloudSynthChannel.tryReceive().getOrNull() ?: break
            collected.append(next)
        }
        // 2. Traiga el texto restante al búfer también(Puede contener el final de un punto)
        if (sentenceBuffer.isNotEmpty()) {
            collected.append(sentenceBuffer)
            sentenceBuffer.clear()
        }
        return collected.toString().trim()
    }

    /**
     * v1.140: Después de que falla la síntesis de transmisión,Bajar al sistema TextToSpeech Transmisión en vivo del texto restante.
     *
     * con [synthesizeWithSystemTts] la diferencia:
     *  - synthesizeWithSystemTts ir tts.synthesizeToFile → escribir wav → MediaPlayer jugar,
     *    En algunas ROM, la devolución de llamada de síntesis puede bloquearse provocando un tiempo de espera de 30 segundos.
     *  - Este método ajusta directamente tts.speak(),Informado por el propio motor TTS del sistema.,Omitir ruta de archivo,
     *    Es posible que aún tenga éxito cuando falle synthesizeToFile.
     *
     * Procesamiento de tiempo:
     *  - Si el sistema TTS no está listo,Registrar alarma y darse por vencido(No puedo bajar de categoría).
     *  - si esta listo,Primero detenga el proceso de nube y MediaPlayer,Luego use tts.speak para transmitir en vivo.
     *  - UtteranceProgressListener.onDone activará onStateChange(uid, false),No hay necesidad de limpiar aquí.
     *
     * @param remainingText Texto sin formato restante para transmitir(despojado Markdown)
     * @param utteranceId Seguimiento id,Se utiliza para resaltar la interfaz de usuario y devolver la llamada onDone
     */
    private fun streamFallbackToSystemTts(remainingText: String, utteranceId: String) {
        if (remainingText.isBlank()) return
        if (!ready.get()) {
            Logger.w("TtsManager", "stream fallback: El TTS del sistema no está listo,renunciar al descenso")
            // Aún borre el estado de la canalización en la nube,evitar colgar
            stopCloudStream()
            stopPlayback()
            currentUtteranceId = null
            cloudStreamUtteranceId = null
            onStateChange?.invoke(utteranceId, false)
            return
        }
        Logger.i("TtsManager", "stream fallback to system TTS: ${remainingText.length} chars")
        // 1. Detenga el proceso de la nube(Cerrar canal + cancelar rutina)
        stopCloudStream()
        // 2. Detener la reproducción de MediaPlayer,Evite la superposición con la transmisión en vivo TTS del sistema
        stopPlayback()
        // 3. idioma de aplicación(Sistema TTS predeterminado chino,fallback También habla chino)
        applyLanguage(Locale.CHINESE)
        // 4. Marcar estado de lectura(Reutilizar utteranceId,UI El resaltado permanece sin cambios)
        currentUtteranceId = utteranceId
        cloudStreamUtteranceId = utteranceId
        onStateChange?.invoke(utteranceId, true)
        // 5. Texto restante en vivo(QUEUE_FLUSH Romper la cola restante)
        val result = tts.speak(remainingText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            Logger.w("TtsManager", "stream fallback tts.speak failed: result=$result")
            currentUtteranceId = null
            cloudStreamUtteranceId = null
            onStateChange?.invoke(utteranceId, false)
            return
        }
        // 6. UI Indica al usuario que la voz del sistema ha sido cambiada
        MuseToast.show(appContext.getString(R.string.speech_tts_fallback_to_system), 2500)
        // Nota: onDone / onError Activado por UtteranceProgressListener,
        //     Utilice handleSynthOrSpeakError o talkStream para borrar el currentUtteranceId después de la ruta de transmisión de una sola oración.
    }

    /** v1.99(4.3): Detenga el proceso de transmisión en la nube(Cancelar rutina + cerrar Channel). */
    private fun stopCloudStream() {
        cloudSynthJob?.cancel()
        cloudPlayJob?.cancel()
        cloudSynthJob = null
        cloudPlayJob = null
        runCatching { cloudSynthChannel.close() }
        runCatching { cloudPlayChannel.close() }
        cloudStreamUtteranceId = null
    }

    /**
     * v0.52: Vaciar el texto restante del búfer de transmisión(Se llama después de que finaliza la lectura de la transmisión).
     *
     * Entregue el texto restante en el búfer que no haya llegado al punto final a TTS para que termine de leer.
     */
    fun flushStream() {
        // Cloud TTS: envíe texto residual a la canalización y luego cierre la síntesis Channel,Desencadenar un final natural
        if (mediaConfig.ttsEngine != "system") {
            if (sentenceBuffer.isNotEmpty()) {
                val clean = MoodSkinParser.cleanForExport(stripMarkdown(sentenceBuffer.toString())).trim()
                sentenceBuffer.clear()
                if (clean.isNotEmpty()) {
                    if (cloudSynthJob?.isActive != true || cloudSynthChannel.isClosedForSend) {
                        // Tubería no iniciada(Sólo se llama a FlushStream),Iniciar temporalmente un
                        val uid = currentUtteranceId ?: "flush"
                        ensureCloudPipeline(uid)
                    }
                    if (!cloudSynthChannel.isClosedForSend) {
                        cloudSynthChannel.trySend(clean)
                    }
                }
            }
            // Desactivar la composición Channel,Deje que el proceso termine naturalmente después de consumir la última oración.
            runCatching { cloudSynthChannel.close() }
            return
        }
        // Ruta TTS del sistema
        if (sentenceBuffer.isEmpty()) return
        val clean = MoodSkinParser.cleanForExport(stripMarkdown(sentenceBuffer.toString())).trim()
        sentenceBuffer.clear()
        if (clean.isNotEmpty() && ready.get()) {
            val mode = if (currentUtteranceId == null) {
                TextToSpeech.QUEUE_FLUSH
            } else {
                TextToSpeech.QUEUE_ADD
            }
            tts.speak(clean, mode, null, currentUtteranceId ?: "flush")
        }
    }

    /**
     * v1.99(4.7): Lea el texto de marcado SSML en voz alta.
     *
     * Analizar etiquetas SSML(<break>,<prosody rate="slow">,<emphasis> espera):
     *  - <break time="Xs"/> → Convertir a la pausa de puntuación correspondiente(≥1s punto final,De lo contrario coma)
     *  - <prosody rate="slow|fast|1.5"> → Ampliación de extracción,cobertura temporal [playbackSpeed](sistema TTS
     *    También se sincronizará setSpeechRate)con tono
     *  - <emphasis> → elevar el tono(sistema TTS)
     *  - Otras etiquetas(<speak>,<s>,<phoneme> espera)Pasar a texto plano
     *
     * Los motores en la nube que no admiten SSML se degradan a la reproducción de texto sin formato. Recupera automáticamente la velocidad original una vez finalizada la reproducción/Paso.
     *
     * @param ssml SSML texto
     * @param utteranceId Seguimiento id
     * @param flush true interrumpir la solicitud anterior(Predeterminado)
     * @return true Empezó a jugar;false El texto está vacío.
     */
    fun speakSsml(ssml: String, utteranceId: String, flush: Boolean = true): Boolean {
        val parsed = parseSsml(ssml)
        if (parsed.plainText.isEmpty()) return false
        val rateOverride = parsed.rate
        val prevSpeed = playbackSpeed
        // tarifa:Cloud TTS afecta la velocidad de reproducción de MediaPlayer;Impacto del sistema TTS setSpeechRate
        if (rateOverride != null) {
            playbackSpeed = (prevSpeed * rateOverride).coerceIn(0.5f, 2f)
        }
        if (mediaConfig.ttsEngine == "system" && ready.get()) {
            val effRate = (mediaConfig.ttsSpeechRate * (rateOverride ?: 1f)).coerceIn(0.5f, 2f)
            val effPitch = (mediaConfig.ttsPitch * (parsed.pitch ?: 1f)).coerceIn(0.5f, 2f)
            runCatching {
                tts.setSpeechRate(effRate)
                tts.setPitch(effPitch)
            }.onFailure { Logger.w("TtsManager", "set speech rate/pitch failed: ${it.message}", it) }
        }
        val ok = speak(parsed.plainText, utteranceId, flush)
        // Volver a la velocidad original una vez finalizada la reproducción/tono
        if (rateOverride != null || parsed.pitch != null) {
            playbackScope.launch {
                resultOf { playbackJob?.join() }
                playbackSpeed = prevSpeed
                if (mediaConfig.ttsEngine == "system" && ready.get()) {
                    runCatching {
                        tts.setSpeechRate(mediaConfig.ttsSpeechRate.coerceIn(0.5f, 2f))
                        tts.setPitch(mediaConfig.ttsPitch.coerceIn(0.5f, 2f))
                    }.onFailure { Logger.w("TtsManager", "restore speech rate/pitch failed: ${it.message}", it) }
                }
            }
        }
        return ok
    }

    /**
     * v1.99(4.7): SSML Analiza los resultados.
     *
     * @property plainText Texto sin formato después de quitar las etiquetas(break Convertido a puntuación)
     * @property rate prosody rate aumento(null significa no especificado)
     * @property pitch prosody pitch aumento(null significa no especificado)
     */
    private data class SsmlParsed(
        val plainText: String,
        val rate: Float? = null,
        val pitch: Float? = null,
    )

    /**
     * v1.99(4.7): Analizador SSML sencillo.
     *
     * Solo maneja etiquetas comunes(break / prosody rate|pitch / emphasis),Las etiquetas restantes se despegan directamente.
     * No depende de analizadores XML,Implementación regular,Alta tolerancia a fallos(SSML no estándar también se puede degradar a texto sin formato).
     */
    private fun parseSsml(ssml: String): SsmlParsed {
        var text = ssml
            .replace(Regex("<\\?xml[^>]*\\?>"), "")
            .replace(Regex("</?speak[^>]*>"), "")
        var rate: Float? = null
        var pitch: Float? = null
        // prosody rate="..."
        val prosodyRateRegex = Regex(
            "<prosody[^>]*rate=[\"']([^\"']+)[\"'][^>]*>(.*?)</prosody>",
            RegexOption.DOT_MATCHES_ALL,
        )
        text = prosodyRateRegex.replace(text) { m ->
            rate = parseProsodyValue(m.groupValues[1])
            m.groupValues[2]
        }
        // prosody pitch="..."
        val prosodyPitchRegex = Regex(
            "<prosody[^>]*pitch=[\"']([^\"']+)[\"'][^>]*>(.*?)</prosody>",
            RegexOption.DOT_MATCHES_ALL,
        )
        text = prosodyPitchRegex.replace(text) { m ->
            pitch = parseProsodyValue(m.groupValues[1])
            m.groupValues[2]
        }
        // restante prosody(Ninguno rate/pitch)Mantener el contenido directamente
        text = text.replace(Regex("<prosody[^>]*>(.*?)</prosody>", RegexOption.DOT_MATCHES_ALL), "$1")
        // emphasis → Contenido reservado(Sistema TTS mediante aumento de tono,No repetido aquí)
        text = text.replace(Regex("<emphasis[^>]*>(.*?)</emphasis>", RegexOption.DOT_MATCHES_ALL), "$1")
        // break → cambiar a pausa de puntuación(≥1s usar un punto,De lo contrario coma;Sin uso de tiempo coma)
        text = text.replace(Regex("<break[^>]*/?>")) { m ->
            val timeMatch = Regex("time=[\"']([\\d.]+)\\s*(s|ms)[\"']").find(m.value)
            if (timeMatch != null) {
                val amt = timeMatch.groupValues[1].toFloatOrNull() ?: 0f
                val unit = timeMatch.groupValues[2]
                val secs = if (unit == "ms") amt / 1000f else amt
                if (secs >= 1f) "." else ","
            } else {
                ","
            }
        }
        // Quitar las etiquetas desconocidas restantes
        text = text.replace(Regex("<[^>]+>"), "")
        // Decodificar entidades XML básicas
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return SsmlParsed(text.trim(), rate, pitch)
    }

    /** Analizar la tasa de prosodia/valor de tono(slow/medium/fast/Porcentaje/decimales). */
    private fun parseProsodyValue(raw: String): Float? = when {
        raw.equals("slow", true) -> 0.7f
        raw.equals("medium", true) -> 1.0f
        raw.equals("fast", true) -> 1.5f
        raw.equals("low", true) -> 0.8f
        raw.equals("high", true) -> 1.3f
        raw.endsWith("%") -> (raw.dropLast(1).toFloatOrNull() ?: 100f) / 100f
        else -> raw.toFloatOrNull()
    }

    /**
     * v1.99(4.6): Habla texto con una voz clonada.
     *
     * temporal [mediaConfig] Cambiar al proveedor correspondiente + clonar voiceId,llamar [speak] después
     * Restaura la configuración original cuando finaliza la reproducción. Actualmente el proveedor de clonación solo tiene elevenlabs
     * (ver [ElevenLabsVoiceCloningProvider]),Su voiceId se puede utilizar directamente como voz TTS.
     *
     * @param text Texto para leer en voz alta
     * @param utteranceId Seguimiento id
     * @param voiceId Clonar sonidos id(VoiceCloningService.cloneVoice valor de retorno)
     * @param providerId Proveedor de servicios de clonación(Predeterminado elevenlabs)
     */
    fun speakWithClonedVoice(text: String, utteranceId: String, voiceId: String, providerId: String = "elevenlabs"): Boolean {
        if (voiceId.isBlank()) return false
        val original = mediaConfig
        // Sólo si el motor actual no es el provider,O se sobrescribirá sólo si la voz actual no es la voz clonada.
        val needOverride = original.ttsEngine != providerId || original.ttsVoice != voiceId
        if (needOverride) {
            applyConfig(original.copy(ttsEngine = providerId, ttsVoice = voiceId))
        }
        val ok = speak(text, utteranceId, flush = true)
        if (needOverride) {
            playbackScope.launch {
                resultOf { playbackJob?.join() }
                applyConfig(original)
            }
        }
        return ok
    }

    /** Detener la lectura actual(Detener reproducción segmentada + lectura en streaming al mismo tiempo). */
    fun stop() {
        stopInternal(resetState = true)
    }

    /** v1.4: parada interna — resetState=true restablecer el estado de reproducción a Idle(Detenerse externamente). */
    private fun stopInternal(resetState: Boolean) {
        // v2.x: Borrar el búfer de anexos en cola cuando se detiene(Ya no se transmite)
        pendingTail.clear()
        stopPlayback()
        stopCloudStream()
        // Lectura de parada de flujo
        if (currentUtteranceId != null) {
            tts.stop()
            onStateChange?.invoke(null, false)
        }
        currentUtteranceId = null
        sentenceBuffer.clear()
        if (resetState) {
            updateState { PlaybackState() }
        }
    }

    /** v1.4: Detener la reproducción segmentada(Cancelar corrutina + lanzamiento MediaPlayer). */
    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        mediaPlayer?.let { mp ->
            runCatching { mp.release() }
        }
        mediaPlayer = null
    }

    /** Si actualmente se está leyendo en voz alta. */
    fun isSpeaking(): Boolean = currentUtteranceId != null

    /** Actualmente se está leyendo en voz alta. utterance id(Se utiliza en la interfaz de usuario para resaltar el mensaje que se está reproduciendo.). */
    fun currentUtterance(): String? = currentUtteranceId

    /** v1.4: Actualizar estado de reproducción(Seguridad del hilo update). */
    private fun updateState(block: (PlaybackState) -> PlaybackState) {
        _playbackState.update(block)
    }

    /**
     * Quitar etiquetas de rebajas,Genere texto sin formato adecuado para leer en voz alta.
     * - Eliminar bloque de código de cerca ```...```(El código no se lee en voz alta)
     * - Eliminar código en línea `...` comillas invertidas
     * - eliminar negrita **...** / cursiva *...* marcador
     * - Elimina el prefijo # del título
     * - Eliminar lista - / 1. prefijo
     * - Quitar enlace [text](url) sólo mantener text
     * - Eliminar comillas > prefijo
     * - Eliminar imágenes ![]() / fórmula $$...$$
     */
    private fun stripMarkdown(text: String): String {
        val sb = StringBuilder()
        val lines = text.split("\n")
        var inCodeBlock = false
        var inFormula = false
        for (line in lines) {
            val trimmed = line.trim()
            // Interruptor de bloqueo de código de cerca
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                continue
            }
            if (inCodeBlock) continue // Contenido del bloque de código omitido
            // bloque de fórmulas $$ ... $$(Líneas únicas o múltiples)
            if (trimmed.startsWith("$$")) {
                if (trimmed.endsWith("$$") && trimmed.length > 2) {
                    // fórmula de una sola línea,saltar
                    continue
                }
                inFormula = !inFormula
                continue
            }
            if (inFormula) continue
            // Cotización > prefijo
            // M-SP2: Las siguientes expresiones regulares han sido promovidas a constantes de objetos complementarios.,Evite recompilar el patrón cada vez que se llame.
            val cleaned = trimmed
                .replace(HEADING_PREFIX_REGEX, "") // Título
                .replace(UNORDERED_LIST_REGEX, "") // lista desordenada
                .replace(ORDERED_LIST_REGEX, "") // lista ordenada
                .replace(QUOTE_PREFIX_REGEX, "") // Cotización
                .replace(IMAGE_REGEX, "") // fotos
                .replace(LINK_REGEX, "$1") // Enlace reservado text
                .replace(INLINE_CODE_REGEX, "$1") // código en línea
                .replace(BOLD_ASTERISK_REGEX, "$1") // Negrita **
                .replace(BOLD_UNDERSCORE_REGEX, "$1") // Negrita _
                .replace(ITALIC_ASTERISK_REGEX, "$1") // cursiva *
                .replace(ITALIC_UNDERSCORE_REGEX, "$1") // cursiva _
                .replace(HORIZONTAL_RULE_REGEX, "") // linea horizontal
            if (cleaned.isNotBlank()) {
                sb.appendLine(cleaned)
            }
        }
        return sb.toString().trim()
    }

    /** Liberar recursos(Llamado cuando la aplicación sale.). */
    fun shutdown() {
        stopInternal(resetState = true)
        // H-SP1 Reparación: Cancelar la reproducciónScope mismo(SupervisorJob),En lugar de simplemente cancelar su trabajo secundario.
        // Originalmente solo stopInternal cancelado playbackJob/positionUpdateJob,playbackScope de SupervisorJob
        // todavía vivo,Provoca fugas en el alcance de la corrutina(Su contexto Dispatchers.Main.immediate no se reciclará).
        playbackScope.cancel()
        tts.stop()
        tts.shutdown()
        currentUtteranceId = null
        sentenceBuffer.clear() // v0.52: Borrar el búfer de transmisión
        ready.set(false)
        // v1.0.4 (P2): expuesto sincrónicamente a UI
        _isReady.value = false
    }
}

/**
 * v1.4: Estado de juego(Expuesto al controlador flotante impulsado por la interfaz de usuario).
 *
 * @property status Idle/Playing/Paused/Ended
 * @property currentChunkIndex Índice de fragmentos actual(0-based)
 * @property totalChunks Número total de fragmentos
 * @property positionMs Posición de audio actual en la película(ms)
 * @property durationMs Duración del audio de la película actual(ms)
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.Idle,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/** v1.4: Enumeración del estado del juego. */
enum class PlaybackStatus { Idle, Playing, Paused, Ended }
