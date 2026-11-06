package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.asr.ASRController
import io.zer0.muse.asr.ASRState
import io.zer0.muse.asr.AsrClientFactory
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.speech.TtsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * v1.105 Etapa 1: Audio extraído de ChatViewModel(TTS / ASR)Coordinator.
 *
 * Responsabilidades:
 *  - toggleTts / stopTts: texto a voz
 *  - Gestión del controlador Streaming ASR(getOrCreateAsrController / start / stop / cancel / dispose)
 *  - saveAsrConfig / shouldUseApiRecording
 *
 * mantener [asrController] y [asrBaseText] Dos estados mutables(Se migran los campos originales de ChatViewModel.).
 */
class ChatAudioCoordinator(
    private val accessor: ChatStateAccessor,
    private val ttsManager: TtsManager,
    private val settings: SettingsRepository,
    private val context: Context,
) {

    /** v1.91: Instancia de controlador ASR de transmisión por secuencias(creación perezosa,Provider Reconstruir en el interruptor). */
    private var asrController: ASRController? = null
    private var asrControllerConfig: AsrConfig? = null
    private var asrStateJob: Job? = null

    // Monotonic session guard: late callbacks from a disposed/replaced controller must not mutate current UI/input.
    private var asrGeneration: Long = 0L

    /** v1.91: Instantánea del texto del cuadro de entrada antes de grabar(Se utiliza para empalmar resultados y recuperar al cancelar). */
    private var asrBaseText = ""
    private var lastAsrTranscript = ""

    /** Phase 8.7: Alternar lectura TTS(detener el mismo mensaje,Iniciar una nueva lectura con un mensaje diferente). */
    fun toggleTts(messageId: Uuid, content: String, reportError: (String) -> Unit) {
        val current = accessor.snapshot.speakingMessageId
        if (current == messageId) {
            ttsManager.stop()
        } else {
            val utteranceId = messageId.toString()
            accessor.coroutineScope.launch {
                // P1-2: Streaming de acceso a lectura de textos largos TTS — speakStream Síntesis frase por frase/jugar(Canalización de doble canal en la nube),
                // flushStream Lave las oraciones restantes al final.;con [TtsManager.speak] Los fragmentos seriales se leen entre sí como caminos alternativos.
                if (content.isBlank()) {
                    reportError(context.getString(R.string.speech_tts_engine_not_ready)) // CONS-05: Migración de chino codificado a recursos de cadena
                    return@launch
                }
                ttsManager.speakStream(content, utteranceId)
                ttsManager.flushStream()
            }
        }
    }

    /** Phase 8.7: Detener la lectura actual(Cambiar de sesión/Llamado al salir de la página.). */
    fun stopTts() {
        ttsManager.stop()
    }

    /**
     * v1.91: Crear o reutilizar ASRController(Según la corriente asrConfig).
     * - SYSTEM / DASHSCOPE_FILE Regresar null(sigue el viejo camino)
     * - DASHSCOPE / STEP / OPENAI_WHISPER / OPENAI_REALTIME / AGNES Crear el flujo correspondiente Controller,E iniciar la rutina de observación de estado.
     */
    private fun getOrCreateAsrController(): ASRController? {
        val cfg = accessor.snapshot.asrConfig
        val supportsStreaming = cfg.provider != AsrProviderType.SYSTEM &&
            cfg.provider != AsrProviderType.DASHSCOPE_FILE
        if (!supportsStreaming || cfg.apiKey.isBlank()) {
            // Provider Al volver al reconocimiento del sistema o borrar la clave API,El antiguo Mando ya no puede ocupar el micrófono/red.
            if (asrController != null || asrStateJob != null) {
                asrGeneration++
                asrStateJob?.cancel()
                asrStateJob = null
                asrController?.dispose()
                asrController = null
                asrControllerConfig = null
                accessor.update { it.copy(asrState = ASRState()) }
            }
            if (cfg.apiKey.isBlank() && supportsStreaming) {
                accessor.update {
                    it.copy(
                        asrState = ASRState(
                            status = io.zer0.muse.asr.ASRStatus.Error,
                            isAvailable = false,
                            errorMessage = context.getString(R.string.asr_missing_api_key_error),
                        ),
                    )
                }
            }
            return null
        }
        val existing = asrController
        if (existing != null && asrControllerConfig == cfg) return existing
        asrGeneration++
        asrStateJob?.cancel()
        existing?.dispose()
        val controller = AsrClientFactory.createController(cfg, context) ?: return null
        asrController = controller
        asrControllerConfig = cfg
        asrStateJob = accessor.coroutineScope.launch {
            controller.state.collect { state ->
                if (controller === asrController) {
                    accessor.update { it.copy(asrState = state) }
                }
            }
        }
        return controller
    }

    /** v1.91: Inicie el reconocimiento de grabación en streaming. */
    fun startStreamingAsr() {
        val controller = getOrCreateAsrController() ?: return
        if (controller.state.value.isRecording) return
        asrBaseText = accessor.snapshot.input
        lastAsrTranscript = ""
        val generation = ++asrGeneration
        controller.start { transcript ->
            if (generation != asrGeneration || controller !== asrController) return@start
            accessor.update { state ->
                val current = state.input
                val base = if (lastAsrTranscript.isNotEmpty() && current.endsWith(lastAsrTranscript)) {
                    current.removeSuffix(lastAsrTranscript).trimEnd()
                } else {
                    current
                }
                val spacer = if (base.isBlank() || transcript.isBlank()) "" else " "
                lastAsrTranscript = transcript
                state.copy(input = base + spacer + transcript)
            }
        }
    }

    /**
     * Dedicado al modo de conversación de voz.:Iniciar la grabación en streaming,Reconocer texto a través de [onTranscript] devolución de llamada a la persona que llama,
     * No escriba en el cuadro de entrada(El modo de diálogo de voz no utiliza el flujo de texto de la barra de entrada).
     *
     * con [startStreamingAsr] la diferencia:
     *  - [startStreamingAsr]:transcript Rellenar en el campo de entrada,Los usuarios pueden editar y enviar manualmente
     *  - [startVoiceConversationListening]:transcript Informe mediante devolución de llamada,Enviado automáticamente por ViewModel
     *
     * @param onTranscript Reconocimiento en tiempo real de devoluciones de llamadas de texto(hilo principal,Incluyendo resultados intermedios y finales.)
     */
    fun startVoiceConversationListening(onTranscript: (String) -> Unit) {
        val controller = getOrCreateAsrController() ?: return
        if (controller.state.value.isRecording) return
        lastAsrTranscript = ""
        val generation = ++asrGeneration
        controller.start { transcript ->
            if (generation != asrGeneration || controller !== asrController) return@start
            onTranscript(transcript)
        }
    }

    /** Dedicado al modo de conversación de voz.:dejar de grabar,Espere a que regrese el último resultado y luego vuelva a estar inactivo. */
    fun stopVoiceConversationListening() {
        asrController?.stop()
    }

    /** Salir/Interrumpir el diálogo de voz: descarte la devolución de llamada de cierre final para evitar que la transcripción tardía vuelva a aparecer en la interfaz de usuario después de detenerse. */
    fun cancelVoiceConversationListening() {
        asrGeneration++
        asrStateJob?.cancel()
        asrStateJob = null
        asrController?.dispose()
        asrController = null
        asrControllerConfig = null
        accessor.update { it.copy(asrState = ASRState()) }
    }

    /** v1.91: Detener la grabación en streaming,A la espera de resultados finales. */
    fun stopStreamingAsr() {
        asrController?.stop()
    }

    /** v1.91: Cancelar grabación de transmisión(Restaurar el texto del cuadro de entrada original). */
    fun cancelStreamingAsr() {
        // No puedo salir si se cancela stop():stop Vaciará la última pieza de audio.,Su devolución de llamada asincrónica puede ocurrir después de que el usuario desliza hacia arriba para cancelar.
        // Luego escriba el texto reconocido nuevamente en el cuadro de entrada. Libera directamente la corriente. Controller,La siguiente grabación se reconstruye según lo configurado.
        asrGeneration++
        asrStateJob?.cancel()
        asrStateJob = null
        asrController?.dispose()
        asrController = null
        asrControllerConfig = null
        accessor.update { state ->
            val current = state.input
            val restored = if (lastAsrTranscript.isNotEmpty() && current.endsWith(lastAsrTranscript)) {
                current.removeSuffix(lastAsrTranscript).trimEnd()
            } else {
                // Cuando el usuario continúa escribiendo durante la grabación, la entrada actual se conserva y no se sobrescribe con instantáneas caducadas.
                current
            }
            lastAsrTranscript = ""
            state.copy(input = restored.ifBlank { asrBaseText }, asrState = ASRState())
        }
    }

    /** v1.91: liberar ASR Controller(Cambio de sesión/ViewModel cuando destruido). */
    fun disposeAsr() {
        asrGeneration++
        asrStateJob?.cancel()
        asrStateJob = null
        asrController?.dispose()
        asrController = null
        asrControllerConfig = null
        accessor.update { it.copy(asrState = ASRState()) }
    }

    /** Phase 9.3 (M2): Guardar configuración ASR(Settings Llamado después de editar la página.). */
    fun saveAsrConfig(config: AsrConfig) {
        accessor.coroutineScope.launch {
            resultOf { settings.saveAsrConfig(config) }
        }
    }

    /**
     * Phase 9.3 (M2): Determinar si la ruta de grabación API se debe tomar actualmente(en lugar de sistema Intent).
     * - true: Cualquier transcripción que no sea de SISTEMA o de archivos Provider,Incluso si falta la clave, debe seguir la ruta API.
     *   Por getOrCreateAsrController“desaparecido key”Muéstrelo claramente a los usuarios en lugar de degradar silenciosamente el reconocimiento de otro sistema.
     * - false: SYSTEM o DASHSCOPE_FILE(La transcripción de archivos no pertenece a la grabación en tiempo real del campo de entrada).
     */
    fun shouldUseApiRecording(): Boolean {
        val p = accessor.snapshot.asrConfig.provider
        return p != AsrProviderType.SYSTEM && p != AsrProviderType.DASHSCOPE_FILE
    }
}
