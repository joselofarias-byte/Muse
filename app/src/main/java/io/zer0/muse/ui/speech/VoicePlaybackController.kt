package io.zer0.muse.ui.speech

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Estado de reproducción de voz.
 *
 * [uri] si"Derechos de reproducción actualmente ocupados"El mensaje de voz; si está vacío, significa que está libre.[positionMs] solo jugando/Válido durante el período de suspensión,
 * Se borra a 0 después del reinicio.
 */
data class VoicePlaybackState(
    val uri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
)

/**
 * Puerto del motor de reproducción.
 *
 * La interfaz se extrae para que se pueda obtener una vista previa de la barra de voz./Robolectric Estructura interna: máquina real [MediaPlayerVoiceAudioPlayer],
 * La prueba se puede afirmar dando una implementación falsa."¿Cuántas veces ha sido transferido? ¿Ha sido suspendido?", sin tocar la pila de audio real.
 */
interface VoiceAudioPlayer {
    /**
     * Cargue y comience a jugar.
     *
     * @param onCompletion Juega naturalmente hasta el final (excluyendo [release] causado por la parada)
     * @param onError decodificar/Error al cargar
     * @return Si la carga se realizó correctamente;false La persona que llama se restablecerá al estado inactivo cuando
     */
    fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean

    /** Reanude la reproducción desde la posición de pausa. */
    fun start()

    /** Pausa, conserva la posición de reproducción para continuar. */
    fun pause()

    /** Posición de reproducción actual (milisegundos). */
    fun positionMs(): Long

    /** Liberar recursos subyacentes. */
    fun release()
}

/** basado en el sistema [MediaPlayer] Implementación (sin introducir dependencias de reproductores de terceros). */
class MediaPlayerVoiceAudioPlayer : VoiceAudioPlayer {
    private var player: MediaPlayer? = null
    private var prepared = false

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean =
        runCatching {
            release()
            val mp = MediaPlayer()
            player = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener { ready ->
                prepared = true
                runCatching { ready.start() }
                    .onFailure { e ->
                        Logger.w(TAG, "Error al iniciar por voz: ${e.message}")
                        onError(e)
                    }
            }
            mp.setOnCompletionListener { onCompletion() }
            mp.setOnErrorListener { _, what, extra ->
                onError(IllegalStateException("MediaPlayer error what=$what extra=$extra"))
                true
            }
            mp.prepareAsync()
            true
        }.getOrElse { e ->
            Logger.w(TAG, "Error al cargar la voz: ${e.message}")
            runCatching { release() }
            onError(e)
            false
        }

    override fun start() {
        val mp = player ?: return
        if (!prepared) return
        runCatching { mp.start() }.onFailure { Logger.w(TAG, "Error en la reproducción de voz: ${it.message}") }
    }

    override fun pause() {
        val mp = player ?: return
        if (!prepared) return
        runCatching { mp.pause() }.onFailure { Logger.w(TAG, "Error en la pausa de voz: ${it.message}") }
    }

    override fun positionMs(): Long = runCatching {
        val mp = player ?: return 0L
        if (!prepared) 0L else mp.currentPosition.toLong()
    }.getOrDefault(0L)

    override fun release() {
        val mp = player ?: return
        player = null
        prepared = false
        runCatching { mp.release() }.onFailure { Logger.w(TAG, "Error al liberar el reproductor de voz: ${it.message}") }
    }

    private companion object {
        private const val TAG = "MediaPlayerVoiceAudioPlayer"
    }
}

/**
 * Controlador de reproducción para compartir mensajes de voz.
 *
 * Sólo hay una instancia de toda la aplicación (Koin Caso único): La barra de voz en sí no sujeta al reproductor. Haga clic aquí para solicitar únicamente los derechos de reproducción.
 * Por lo tanto"Sólo suena una voz al mismo tiempo."Es una garantía estructural, no el resultado de la coordinación entre burbujas.
 *
 * - [toggle] La nueva voz detendrá la anterior antes de iniciarla nuevamente;
 * - Terminó naturalmente / cometió un error / tomó la iniciativa [stop] liberará el reproductor y lo restablecerá al modo inactivo;
 * - El progreso depende del muestreo de tiempo interno y las burbujas están en un estado de solo lectura.
 *
 * El estado lo transmite el estado de la instantánea de redacción y el lector (burbuja) se reorganiza automáticamente sin suscripción adicional.
 */
class VoicePlaybackController(
    private val playerFactory: () -> VoiceAudioPlayer = { MediaPlayerVoiceAudioPlayer() },
) {
    var state: VoicePlaybackState by mutableStateOf(VoicePlaybackState())
        private set

    private var player: VoiceAudioPlayer? = null

    /** Creación diferida: los procesos que nunca se ejecutan (incluidas la mayoría de las pruebas individuales) no tocarán el programador principal. */
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var ticker: Job? = null

    /** Hacer clic: cambiar la reproducción de la misma voz/Pausa; cambie uno para detener el anterior y comenzar de nuevo. */
    fun toggle(uri: String, file: File) {
        val current = state
        if (current.uri == uri) {
            if (current.isPlaying) pause() else resume(file)
            return
        }
        stop()
        play(uri, file)
    }

    /** Detenga la voz actual y suelte el reproductor (pase al fondo/Salir de la página/Se llama al cambiar de barra y se puede llamar repetidamente). */
    fun stop() {
        ticker?.cancel()
        ticker = null
        val current = player
        player = null
        current?.let { runCatching { it.release() } }
        if (state != VoicePlaybackState()) state = VoicePlaybackState()
    }

    /** solo [uri] Detener cuando sea la actual (se utiliza cuando la página donde se encuentra la burbuja retrocede a un segundo plano para evitar detener a otras accidentalmente). */
    fun stopIfActive(uri: String) {
        if (state.uri == uri) stop()
    }

    private fun play(uri: String, file: File) {
        val engine = playerFactory()
        val loaded = runCatching {
            engine.load(
                file = file,
                onCompletion = { stop() },
                onError = { stop() },
            )
        }.getOrElse { e ->
            Logger.w(TAG, "El reproductor de voz se carga de forma anormal: ${e.message}")
            false
        }
        if (!loaded) {
            runCatching { engine.release() }
            stop()
            return
        }
        player = engine
        state = VoicePlaybackState(uri = uri, isPlaying = true, positionMs = 0L)
        startTicker()
    }

    private fun pause() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.pause() } }
        state = state.copy(isPlaying = false)
    }

    private fun resume(file: File) {
        val engine = player
        val uri = state.uri
        if (engine == null || uri == null) {
            // El jugador ha sido liberado (por ejemplo, otra voz se lo arrebató a mitad de camino)——Procesar como nuevo comienzo
            if (uri != null) play(uri, file)
            return
        }
        val started = runCatching { engine.start() }.isSuccess
        if (!started) {
            stop()
            return
        }
        state = state.copy(isPlaying = true)
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val engine = player ?: break
                val position = runCatching { engine.positionMs() }.getOrDefault(0L)
                if (state.isPlaying && position != state.positionMs) {
                    state = state.copy(positionMs = position)
                }
            }
        }
    }

    private companion object {
        private const val TAG = "VoicePlaybackController"

        /** Intervalo de muestreo de progreso: no es necesario que el llenado de la barra de voz sea cuadro por cuadro,10 fps Lo suficientemente suave y energéticamente eficiente. */
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}
