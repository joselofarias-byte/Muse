package io.zer0.muse.miniphone.ui.chat

import io.zer0.muse.miniphone.data.voice.MpVoiceCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Tres estados de entrada de voz.
 *
 * "Detener la grabación" y "Comenzar a transcribir" son dos caras de la misma acción.:En el momento en que el usuario hace clic en el final, debería ver el mensaje de transliteración.,
 * En lugar de esperar hasta que la colección esté realmente completa antes de cambiar —— entonces [Transcribing] Ponte en posición antes de detener la minería.
 */
internal sealed interface MpVoiceInputState {

    /** No grabando,Tampoco se está realizando ninguna transcripción. */
    data object Idle : MpVoiceInputState

    /** Grabación;[elapsedSeconds] Comenzando desde 0 y aumentando en uno cada segundo. */
    data class Recording(val elapsedSeconds: Int) : MpVoiceInputState

    /** Recogida terminada,Espere a que el anfitrión convierta la grabación en texto. */
    data object Transcribing : MpVoiceInputState
}

/**
 * Máquina de estado de entrada de voz para el campo de entrada de la página de chat.
 *
 * ```
 * Idle → Recording(elapsedSeconds) → Transcribing → Idle
 *              ↓ Cancelar
 *            Idle
 * ```
 *
 * tres reglas:
 *  - La transcripción no está terminada y no se aceptarán nuevas grabaciones.([start] solo ignora),En caso contrario, los archivos temporales del párrafo anterior quedarán no reclamados.;
 *  - Cancelar solo pierde audio,transliteración sin ajuste —— Cuando el usuario presiona cancelar, significa "Ya no quiero esta oración".;
 *  - grabado [MAX_RECORDING_SECONDS] Pase automáticamente por "Detener y transcribir" en segundos,No confíe en que el usuario recuerde el punto final.
 *
 * El texto reconocido se entrega a [onTranscript];no puedo reconocer(cadena vacía,null, no se recopiló ningún audio)entregar
 * [onEmptyTranscript],Cómo decir "no escuché con claridad" está determinado por la interfaz—— Esta categoría no toca la redacción publicitaria.,Tampoco tostadas.
 * Darte el micrófono cuando no puedas levantarlo. [onStartFailed]:El usuario hizo clic pero no pasó nada.,Es más incómodo que informar un error.
 *
 * @param recorder grabadora;Cuando está parado, entrega toda la sección. WAV
 * @param transcribe Una entrada única para convertir WAV a texto,Retorno del fracaso null
 * @param canRecord ¿Se pueden utilizar los ajustes actuales para grabar?;cuando no es posible [start] No mueva la grabadora directamente
 * @param scope Las tareas de cronometraje y transcripción cuelgan aquí,Terminar con la pagina
 */
internal class MpVoiceInputController(
    private val recorder: MpVoiceCapture,
    private val transcribe: suspend (ByteArray) -> String?,
    private val onTranscript: (String) -> Unit,
    private val onEmptyTranscript: () -> Unit,
    private val onStartFailed: () -> Unit,
    private val canRecord: () -> Boolean,
    private val scope: CoroutineScope,
) {

    private val mutableState = MutableStateFlow<MpVoiceInputState>(MpVoiceInputState.Idle)

    val state: StateFlow<MpVoiceInputState> = mutableState.asStateFlow()

    /** Cronómetro corriendo;Cancele cuando salga del estado de grabación. */
    private var ticker: Job? = null

    /**
     * Comience a grabar. solo en [MpVoiceInputState.Idle] Y entrará en vigor cuando la configuración actual admita la grabación.
     *
     * Cuando no se puede obtener el micrófono, el estado permanece sin cambios y se envía una vez al mismo tiempo. [onStartFailed] —— Las dos situaciones de colocar cenizas y no colocar cenizas se manejan por separado.:
     * La configuración no es compatible porque "la función no está activada",Error de recopilación significa "no funcionó esta vez",Lo que el usuario quiere leer no es la misma frase.
     */
    fun start() {
        if (mutableState.value != MpVoiceInputState.Idle) return
        if (!canRecord()) return
        if (!recorder.start()) {
            onStartFailed()
            return
        }
        mutableState.value = MpVoiceInputState.Recording(elapsedSeconds = 0)
        ticker = scope.launch { tick() }
    }

    /** Detén la grabación y transcribe. solo en [MpVoiceInputState.Recording] Tener un efecto. */
    fun stop() {
        if (mutableState.value !is MpVoiceInputState.Recording) return
        ticker?.cancel()
        ticker = null
        mutableState.value = MpVoiceInputState.Transcribing
        scope.launch { finishTranscription() }
    }

    /** Cancelar esta grabación:audio perdido,No transcrito. */
    fun cancel() {
        if (mutableState.value !is MpVoiceInputState.Recording) return
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        mutableState.value = MpVoiceInputState.Idle
    }

    /** Finalizar al salir de la página.:Ya sea grabando o transcribiendo,Vuelva al modo inactivo y asegúrese de que se eliminen los archivos temporales. */
    fun release() {
        ticker?.cancel()
        ticker = null
        recorder.cancel()
        mutableState.value = MpVoiceInputState.Idle
    }

    /** Dejar de minar → Transcribir → Enviar texto. El resultado vacío también va de esta manera.,Sólo dáselo a [onEmptyTranscript]. */
    private suspend fun finishTranscription() {
        val wav = recorder.stop()
        val text = if (wav == null) null else transcribe(wav)
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) onEmptyTranscript() else onTranscript(trimmed)
        mutableState.value = MpVoiceInputState.Idle
    }

    /** Cronómetro:Un segundo salto,Salta al límite superior y presiona \"Finalizar\" tú mismo. */
    private suspend fun tick() {
        while (true) {
            delay(TICK_INTERVAL_MS)
            val recording = mutableState.value as? MpVoiceInputState.Recording ?: return
            val next = recording.elapsedSeconds + 1
            mutableState.value = MpVoiceInputState.Recording(next)
            if (next >= MAX_RECORDING_SECONDS) {
                stop()
                return
            }
        }
    }

    companion object {
        /** Límite de grabación única:Consistente con el límite de escritura propio de la grabadora. */
        const val MAX_RECORDING_SECONDS: Int = 60
    }
}

private const val TICK_INTERVAL_MS: Long = 1_000L

/** Duración de la grabación en el campo de entrada:`0:07`. */
internal fun voiceElapsedLabel(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}
