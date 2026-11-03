package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.ai.core.MessageRole
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.asr.ASRStatus
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.ui.ChatErrorType
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.ui.speech.VoiceConversationState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** P2-33: SPEAKING Tiempo de espera básico de vigilancia(milisegundos)— Iniciar transmisión/Tampón sintético. */
internal const val SPEAKING_WATCHDOG_BASE_MS = 8_000L

/** P2-33: Tiempo máximo de lectura permitido por carácter(milisegundos),Se utiliza para el tiempo de espera adaptable según la longitud del texto. */
internal const val SPEAKING_WATCHDOG_MS_PER_CHAR = 120L

/** P2-33: SPEAKING Límite inferior del tiempo de espera de vigilancia(milisegundos). */
internal const val SPEAKING_WATCHDOG_MIN_MS = 15_000L

/** P2-33: SPEAKING Límite de tiempo de espera de vigilancia(milisegundos). */
internal const val SPEAKING_WATCHDOG_MAX_MS = 120_000L

/** P2-33: Número máximo de renovaciones detectadas cuando TTS aún se está transmitiendo(Todavía no hay devolución de llamada final después de la renovación. → retroceso forzado). */
internal const val SPEAKING_WATCHDOG_MAX_EXTENSIONS = 1

/**
 * P2-33: SPEAKING tiempo de espera de vigilancia — Adaptarse a la longitud del texto.
 *
 * La duración de la lectura es aproximadamente lineal con la longitud del texto.([SPEAKING_WATCHDOG_MS_PER_CHAR] Un límite superior conservador para cada palabra.),
 * Después de superponer el búfer inicial, converge a [SPEAKING_WATCHDOG_MIN_MS] ~ [SPEAKING_WATCHDOG_MAX_MS]:
 * Cuente rápidamente la historia en oraciones cortas.,Los artículos largos no matan por error.
 */
internal fun adaptiveSpeakingWatchdogMs(textLength: Int): Long =
    (SPEAKING_WATCHDOG_BASE_MS + textLength.coerceAtLeast(0).toLong() * SPEAKING_WATCHDOG_MS_PER_CHAR)
        .coerceIn(SPEAKING_WATCHDOG_MIN_MS, SPEAKING_WATCHDOG_MAX_MS)

/**
 * v1.x: Medios extraídos de ChatViewModel/Controlador de Producto.
 *
 * Responsabilidades:producto(artifact)Modo tarjeta + conversación de voz(Grabación → identificar → pensar → bucle de transmisión).
 * Devolución de llamada de bucle de voz(send/stop/updateInput/addError)Desacoplado del lado de la producción,No hay dependencia inversa de ChatViewModel.
 */
@Suppress("LongParameterList", "TooManyFunctions")
internal class ChatMediaController(
    private val accessor: ChatStateAccessor,
    private val artifactRepository: ArtifactRepository,
    private val voiceState: ChatVoiceState,
    private val stateStore: ChatStateStore,
    private val audioCoordinator: ChatAudioCoordinator,
    private val ttsManager: TtsManager,
    private val appContext: Context,
    private val onError: (ChatErrorType, String, Boolean) -> Unit,
    private val onSend: () -> Unit,
    private val onStop: () -> Unit,
    private val onUpdateInput: (String) -> Unit,
    private val settings: SettingsRepository,
    /**
     * P2-33: SPEAKING Proveedor de tiempo de espera de vigilancia(milisegundos,El parámetro de entrada es la longitud del texto que se leerá en voz alta.).
     * Adaptarse a la longitud del texto de forma predeterminada([adaptiveSpeakingWatchdogMs]);Una sola prueba inyecta un breve tiempo de espera para cubrir de manera determinista el camino inferior.
     */
    private val speakingWatchdogTimeoutMs: (Int) -> Long = ::adaptiveSpeakingWatchdogMs,
) {

    private companion object {
        const val TAG = "ChatMediaController"

        /** Razones para que el perro guardián retroceda al entrar en HABLAR(escribir diario,Facilitar el posicionamiento de los motores posteriormente./problema de devolución de llamada). */
        const val REASON_NO_CALLBACK = "No se recibió ninguna devolución de llamada de estado TTS después del tiempo de espera"
        const val REASON_STILL_SPEAKING = "TTS El final no se ha informado desde hace mucho tiempo.(Es posible que se pierda la devolución de llamada final)"
    }

    /** v1.43: Seleccionar tarjeta de producto,Abra ArtifactViewerDialog. */
    fun selectArtifact(artifact: ArtifactEntity) {
        accessor.update { it.copy(selectedArtifact = artifact) }
    }

    /** v1.43: Cierre la ventana emergente de vista de tarjeta de producto. */
    fun dismissArtifactViewer() {
        accessor.update { it.copy(selectedArtifact = null) }
    }

    /** v1.43: Ver la lista de tarjetas de productos asociadas con un mensaje. */
    fun observeArtifactsByMessage(messageId: String): Flow<List<ArtifactEntity>> {
        return artifactRepository.observeByMessage(messageId)
    }

    /** Sistema de consulta TTS lista de sonidos disponibles(Cambiar voz para la hoja inferior). */
    fun getAvailableTtsVoices(): List<android.speech.tts.Voice> = ttsManager.getAvailableVoices()

    /** Nombre del sonido TTS actualmente efectivo(Se utiliza para alternar la selección de marcas de hoja de voz.). */
    fun currentTtsVoiceName(): String = accessor.snapshot.mediaConfig.ttsVoiceName

    /** Cambiar sonido TTS:Aplicar inmediatamente a TtsManager,y persistir en Settings(Seguirá surtiendo efecto la próxima vez que lo inicies.). */
    fun setTtsVoice(voiceName: String) {
        val currentConfig = accessor.snapshot.mediaConfig
        val newConfig = currentConfig.copy(ttsVoiceName = voiceName)
        ttsManager.applyConfig(newConfig)
        accessor.coroutineScope.launch {
            resultOf { settings.saveMediaConfig(newConfig) }
        }
    }

    /**
     * Si el usuario ha finalizado explícitamente esta sección de entrada(Haga clic en "Finalizar"/Déjalo ir).
     *
     * solo [finishVoiceConversationListening] se establecerá;ASR regresar solo Idle/Error(extremo del servidor,
     * Error de reconocimiento)Siempre considerado interrumpido,No se podrán realizar presentaciones bajo esta base.
     */
    private var pendingCommit = false

    /** Ingrese al modo de conversación de voz:Inicie la primera ronda de ESCUCHA y comience la observación del bucle de la máquina de estados. */
    fun startVoiceConversation() {
        if (voiceState.state.value != VoiceConversationState.IDLE) return
        if (!audioCoordinator.shouldUseApiRecording()) {
            onError(ChatErrorType.UNKNOWN, appContext.getString(R.string.err_chat_voice_no_asr), true)
            return
        }
        // Cancelar la antigua rutina de bucle,Reinicie para asegurarse de que el estado esté limpio.
        voiceState.job?.cancel()
        pendingCommit = false
        voiceState.hint.value = null
        voiceState.clearTranscript()
        voiceState.state.value = VoiceConversationState.LISTENING
        startListeningForVoiceConversation()
        observeVoiceConversationLoop()
    }

    /** Salir del modo de conversación de voz:detenerse ASR/TTS,Cancelar la rutina de observación del bucle.,El estado se pone a cero. */
    fun stopVoiceConversation() {
        voiceState.job?.cancel()
        voiceState.job = null
        pendingCommit = false
        audioCoordinator.cancelVoiceConversationListening()
        ttsManager.stop()
        voiceState.state.value = VoiceConversationState.IDLE
        voiceState.clearTranscript()
        voiceState.aiReply.value = ""
        voiceState.hint.value = null
    }

    /** Interrumpir el estado actual de la conversación de voz.(El usuario hace clic en el botón principal.). */
    fun interruptVoiceConversation() {
        val current = voiceState.state.value
        if (current == VoiceConversationState.IDLE) return
        pendingCommit = false
        audioCoordinator.cancelVoiceConversationListening()
        ttsManager.stop()
        // THINKING La IA todavía se está generando en el estado.,Es necesario detener la generación para evitar activaciones falsas de devoluciones de llamada isStreaming posteriores. TTS
        if (current == VoiceConversationState.THINKING && accessor.snapshot.isStreaming) {
            onStop()
        }
        voiceState.state.value = VoiceConversationState.IDLE
        voiceState.clearTranscript()
        voiceState.aiReply.value = ""
        voiceState.hint.value = null
    }

    /**
     * Botón principal de conversación de voz(UI lado toggle,Sin presionar y mantener presionado):
     *  - IDLE:iniciar una conversación
     *  - LISTENING y grabando:Finaliza este párrafo y envíalo.
     *  - LISTENING pero no grabando:Empezar a grabar de nuevo
     *  - THINKING / SPEAKING:Interrumpir la respuesta actual
     */
    fun toggleVoiceConversation() {
        when (voiceState.state.value) {
            VoiceConversationState.IDLE -> startVoiceConversation()
            VoiceConversationState.LISTENING -> if (stateStore.state.value.asrState.isRecording) {
                finishVoiceConversationListening()
            } else {
                startListeningForVoiceConversation()
            }
            VoiceConversationState.THINKING, VoiceConversationState.SPEAKING -> interruptVoiceConversation()
        }
    }

    /**
     * Entrada de envío explícito:El usuario decide "esta frase se acabó".
     *
     * Apague inmediatamente el micrófono y deje que ASR termine.(Stopping → Idle),Una vez finalizado, el bucle de estado envía el texto de manera uniforme.
     * solo este camino(y [toggleVoiceConversation])Una sección de aportes se marcará como pendiente de envío.
     */
    fun finishVoiceConversationListening() {
        if (voiceState.state.value != VoiceConversationState.LISTENING) return
        if (!stateStore.state.value.asrState.isRecording) return
        pendingCommit = true
        voiceState.hint.value = null
        audioCoordinator.stopVoiceConversationListening()
    }

    /**
     * Iniciar una ronda de grabación ASR:El texto reconocido se fusiona con el texto acumulado de esta ronda.(No claro,La acumulación continuará después de reiniciar la grabación.).
     *
     * de cada Proveedor start() restablecerá su propio texto de la sesión.,Por lo tanto, la acumulación entre sesiones solo se puede realizar aquí.:
     * El servidor silenció la sesión y luego la volvió a abrir.,Lo dicho antes no se perderá.
     */
    private fun startListeningForVoiceConversation() {
        audioCoordinator.startVoiceConversationListening { transcript ->
            voiceState.accumulateTranscript(transcript)
        }
    }

    /**
     * ASR Finalizar sin envío del usuario(Error de reconocimiento/el servidor finaliza solo/no hay texto al enviar):
     * Ningún mensaje,Vuelva a ESCUCHAR y sintonice nuevamente.
     *
     * Continuar escuchando en silencio cuando ya haya textos acumulados en esta ronda(No solicite "Dilo de nuevo",La grabación se reinicia y la acumulación continúa.);
     * Sólo cuando la acumulación esté vacía se le pedirá que vuelva a intentarlo.ASR No reiniciar inmediatamente cuando se produzca un error
     * (De lo contrario start → Error Formará un bucle infinito.),Deje que el usuario haga clic en el botón y vuelva a intentarlo.
     */
    private fun resumeListeningAfterInterruptedAsr() {
        voiceState.aiReply.value = ""
        voiceState.hint.value = if (voiceState.transcript.value.isBlank()) {
            appContext.getString(R.string.voice_conversation_retry_hint)
        } else {
            null
        }
        voiceState.state.value = VoiceConversationState.LISTENING
        if (stateStore.state.value.asrState.status == ASRStatus.Error) return
        startListeningForVoiceConversation()
    }

    /** Inicie la rutina de observación del bucle de la máquina de estados:Monitorear ASR/Transmisión/TTS Cambio de estado,Controla automáticamente los bucles de la máquina de estados. */
    private fun observeVoiceConversationLoop() {
        voiceState.job = accessor.coroutineScope.launch {
            var wasRecording = false
            var wasStreaming = false
            var wasSpeaking = false
            // P2-33: SPEAKING perro guardián — Empezar después de entrar HABLAR,Si la devolución de llamada de estado TTS no se observa después del tiempo de espera, retrocederá.,
            // evitar speak() Devuelve verdadero pero falta la devolución de llamada isSpeaking/Atascado permanentemente en HABLAR cuando StateFlow lo fusiona.
            var speakingWatchdog: Job? = null
            var speakingEpoch = 0
            stateStore.state.collect { state ->
                // 1. LISTENING Durante la sesión ASR finaliza:Enviar solo si el usuario finaliza explícitamente la entrada en este párrafo
                if (voiceState.state.value == VoiceConversationState.LISTENING) {
                    if (state.asrState.isRecording) {
                        wasRecording = true
                        // Después de reiniciar la grabación, se borrará la ronda anterior de recordatorios luminosos.
                        if (voiceState.hint.value != null) voiceState.hint.value = null
                    } else if (wasRecording) {
                        wasRecording = false
                        val userCommitted = pendingCommit
                        pendingCommit = false
                        // Sólo ASR tiene un acabado limpio(Idle)Sólo entonces estará completa la entrada de este párrafo.;Error Esto demuestra que este reconocimiento no es digno de confianza.
                        val endedCleanly = state.asrState.status == ASRStatus.Idle
                        // El texto acumulado de esta ronda + el último párrafo llevado en el estado final de ASR:El estado de cierre y la devolución de llamada se reconocen al mismo tiempo.
                        // canal,Es posible que la devolución de llamada aún no haya alcanzado el nivel de acumulación.,Dos contenidos se superponen,Las reglas de fusión son responsables de la deduplicación
                        val text = voiceState.commitText(state.asrState.transcript)
                        if (userCommitted && endedCleanly && text.isNotEmpty()) {
                            // compromiso explícito:El micrófono está apagado.,Envía el texto completo de una vez
                            audioCoordinator.stopVoiceConversationListening()
                            voiceState.aiReply.value = ""
                            voiceState.state.value = VoiceConversationState.THINKING
                            onUpdateInput(text)
                            onSend()
                            // La ronda finaliza si la presentación tiene éxito.:Claro acumulado,La siguiente ronda comienza de la nada.
                            voiceState.clearTranscript()
                        } else {
                            // Envío de texto vacío/Error/el servidor finaliza solo:nunca enviar,Retención de texto acumulada
                            resumeListeningAfterInterruptedAsr()
                        }
                    }
                }
                // 2. THINKING → SPEAKING:AI Respuesta de transmisión completada,Leer automáticamente en voz alta
                if (voiceState.state.value == VoiceConversationState.THINKING) {
                    if (state.isStreaming) {
                        wasStreaming = true
                    } else if (wasStreaming) {
                        wasStreaming = false
                        val lastAssistant = stateStore.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT }
                        val content = lastAssistant?.content?.takeIf { it.isNotBlank() }
                        if (content != null) {
                            voiceState.aiReply.value = content
                            voiceState.state.value = VoiceConversationState.SPEAKING
                            // P2-33: Una nueva ronda de transmisión comienza desde un estado limpio. — La última ronda fue interrumpida./Cuando el perro guardián retrocede
                            // wasSpeaking puede permanecer true,Si no se reinicia, una nueva ronda de HABLAR se juzgará erróneamente inmediatamente como"terminó"
                            wasSpeaking = false
                            // Asegúrese de que el micrófono esté apagado antes de transmitir(idempotente):TTS Si usted mismo graba la voz, se reconocerá como el usuario que habla.,
                            // Forma de autointerrupción.SPEAKING Este ciclo no comenzará durante este período. ASR,Sólo se vuelve a abrir al volver a ESCUCHAR.
                            audioCoordinator.stopVoiceConversationListening()
                            val speakStarted = ttsManager.speak(content, lastAssistant.id.toString())
                            if (!speakStarted) {
                                // P2-14: speak() Fallo de devolución(Motor no listo/sin motor)cuando esHablar es nunca
                                // true,Paso 3 Nunca actives,SPEAKING Habrá un bucle sin fin.;Si falla, regrese directamente a ESCUCHAR para continuar escuchando.
                                voiceState.aiReply.value = ""
                                voiceState.state.value = VoiceConversationState.LISTENING
                                startListeningForVoiceConversation()
                            } else {
                                // P2-33: Armar el perro guardián sólo cuando la transmisión realmente comience;epoch Asegúrese de que el perro guardián caducado no dañe accidentalmente la nueva ronda. SPEAKING
                                speakingEpoch++
                                val epoch = speakingEpoch
                                speakingWatchdog?.cancel()
                                speakingWatchdog = launch {
                                    armSpeakingWatchdog(
                                        epoch = epoch,
                                        textLength = content.length,
                                        isCurrent = { epoch == speakingEpoch },
                                    )
                                }
                            }
                        } else {
                            voiceState.state.value = VoiceConversationState.IDLE
                        }
                    }
                }
                // 3. SPEAKING → LISTENING:TTS Leer en voz alta completado,reanudar la grabación(dialogo continuo)
                if (voiceState.state.value == VoiceConversationState.SPEAKING) {
                    if (state.isSpeaking) {
                        wasSpeaking = true
                    } else if (wasSpeaking) {
                        wasSpeaking = false
                        speakingWatchdog?.cancel()
                        speakingWatchdog = null
                        voiceState.aiReply.value = ""
                        voiceState.state.value = VoiceConversationState.LISTENING
                        startListeningForVoiceConversation()
                    }
                }
            }
        }
    }

    /**
     * P2-33: sujeto de vigilancia — espera[Tiempo de espera adaptativo][adaptiveSpeakingWatchdogMs].
     *
     * después del tiempo de espera:
     *  - El estado ya no es ronda actual. SPEAKING([isCurrent] es falso / ha sido interrumpido)→ Salir en silencio;
     *  - TTS Todavía transmitiendo(El estado del motor o de la interfaz de usuario aún se informa speaking)y no exceder [SPEAKING_WATCHDOG_MAX_EXTENSIONS] renovación
     *    → renovar una vez,Evite que se eliminen accidentalmente textos largos;
     *  - De lo contrario, vuelva a ESCUCHAR y registre el motivo.(No se recibió ninguna devolución de llamada/no se informó la finalización durante mucho tiempo),También detenga TTS para evitar reproducción residual.
     */
    private suspend fun armSpeakingWatchdog(epoch: Int, textLength: Int, isCurrent: () -> Boolean) {
        val timeoutMs = speakingWatchdogTimeoutMs(textLength)
        var extensions = 0
        while (true) {
            delay(timeoutMs)
            if (!isCurrent() || voiceState.state.value != VoiceConversationState.SPEAKING) return
            val stillSpeaking = ttsManager.isSpeaking() || stateStore.state.value.isSpeaking
            if (stillSpeaking && extensions < SPEAKING_WATCHDOG_MAX_EXTENSIONS) {
                extensions++
                Logger.w(
                    TAG,
                    "SPEAKING tiempo de espera de vigilancia(${timeoutMs}ms)Pero TTS sigue transmitiendo.,Renovar $extensions veces" +
                        " | textLength=$textLength | epoch=$epoch",
                )
                continue
            }
            val reason = if (stillSpeaking) REASON_STILL_SPEAKING else REASON_NO_CALLBACK
            Logger.w(
                TAG,
                "SPEAKING disparador de vigilancia,retroceder LISTENING | reason=$reason | timeout=${timeoutMs}ms" +
                    " | textLength=$textLength | epoch=$epoch",
            )
            runCatching { ttsManager.stop() }
            voiceState.aiReply.value = ""
            voiceState.state.value = VoiceConversationState.LISTENING
            startListeningForVoiceConversation()
            return
        }
    }
}
