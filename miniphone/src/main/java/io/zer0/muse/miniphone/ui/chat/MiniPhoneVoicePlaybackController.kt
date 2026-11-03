package io.zer0.muse.miniphone.ui.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * El estado de reproducción de la barra de voz.
 *
 * [uri] Es la voz que actualmente tiene derecho a sonar.,Vacío significa inactivo;[positionMs] solo jugando/significativo durante la pausa,
 * Borrado después del reinicio.
 */
data class MpVoicePlaybackState(
    val uri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
)

/**
 * Puerto del motor de reproducción.
 *
 * La barra de voz no toca directamente [MediaPlayer]:maquina real [MediaPlayerMpVoiceAudioPlayer],La prueba se puede afirmar dando una implementación falsa.
 * "¿Cuántas veces se ha cargado, se ha pausado, se ha liberado después de jugar?",No es necesario tocar la pila de audio del sistema.
 */
interface MpVoiceAudioPlayer {
    /**
     * Cargue y comience a jugar.
     *
     * @param onCompletion Juega hasta el final de forma natural.(Toma la iniciativa [release] Paradas causadas por)
     * @param onError cargar/Falló la decodificación
     * @return ¿La carga es exitosa?;false La persona que llama se restablece al estado inactivo cuando
     */
    fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean

    /** Continúe desde donde hizo la pausa. */
    fun start()

    /** pausa,Reserva tu plaza para continuar. */
    fun pause()

    /** Posición de juego actual(milisegundos). */
    fun positionMs(): Long

    /** Liberar recursos subyacentes. */
    fun release()
}

/** basado en el sistema [MediaPlayer] realización:Los teléfonos celulares pequeños no utilizan reproductores de terceros. */
class MediaPlayerMpVoiceAudioPlayer : MpVoiceAudioPlayer {

    private var player: MediaPlayer? = null
    private var prepared: Boolean = false

    override fun load(file: File, onCompletion: () -> Unit, onError: (Throwable) -> Unit): Boolean = runCatching {
        release()
        val created = MediaPlayer()
        player = created
        created.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        created.setDataSource(file.absolutePath)
        created.setOnPreparedListener { ready ->
            prepared = true
            runCatching { ready.start() }.onFailure { error ->
                onError(error)
            }
        }
        created.setOnCompletionListener { onCompletion() }
        created.setOnErrorListener { _, what, extra ->
            onError(IllegalStateException("MediaPlayer error what=$what extra=$extra"))
            true
        }
        created.prepareAsync()
        true
    }.getOrElse { error ->
        runCatching { release() }
        onError(error)
        false
    }

    override fun start() {
        val playing = player ?: return
        if (!prepared) return
        runCatching { playing.start() }
    }

    override fun pause() {
        val playing = player ?: return
        if (!prepared) return
        runCatching { playing.pause() }
    }

    override fun positionMs(): Long = runCatching {
        val playing = player ?: return 0L
        if (prepared) playing.currentPosition.toLong() else 0L
    }.getOrDefault(0L)

    override fun release() {
        val playing = player ?: return
        player = null
        prepared = false
        runCatching { playing.release() }
    }
}

/**
 * El único controlador de reproducción de voz en el módulo.(Koin singleton).
 *
 * La barra de voz en sí no sujeta al reproductor.,Haga clic aquí sólo para solicitar los derechos de reproducción. —— "Sólo uno está emitiendo a la vez", por lo que es una garantía estructural,
 * No confíes en que cada burbuja se coordine entre sí.:Al cambiar uno, primero se detendrá y se liberará el anterior.
 *
 * El estado se transmite utilizando el estado de composición de instantáneas.,Lea sus burbujas para reorganizarse automáticamente;El progreso depende del muestreo de tiempo interno,Las burbujas solo leen resultados.
 *
 * @param playerFactory fábrica de jugadores;Inyección de prueba de implementación falsa,Sin tocar la pila de audio real
 */
class MiniPhoneVoicePlaybackController(
    private val playerFactory: () -> MpVoiceAudioPlayer = { MediaPlayerMpVoiceAudioPlayer() },
) {

    var state: MpVoicePlaybackState by mutableStateOf(MpVoicePlaybackState())
        private set

    private var player: MpVoiceAudioPlayer? = null

    /** creación perezosa:Proceso que nunca juega(Contiene la mayoría de las pruebas individuales.)El programador principal no será tocado. */
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    private var ticker: Job? = null

    /** Haga clic:Simplemente juega el mismo elemento./pausa,Para cambiar uno, detenga el anterior y luego inícielo nuevamente. */
    fun toggle(uri: String, file: File) {
        val current = state
        if (current.uri == uri) {
            if (current.isPlaying) pause() else resume(uri, file)
            return
        }
        stop()
        play(uri, file)
    }

    /** Detenga la voz actual y suelte el reproductor.(Se llama al salir de la página, cambiar de sesión o cambiar de barra.,Se puede llamar repetidamente). */
    fun stop() {
        ticker?.cancel()
        ticker = null
        val current = player
        player = null
        current?.let { runCatching { it.release() } }
        if (state != MpVoicePlaybackState()) state = MpVoicePlaybackState()
    }

    /** solo [uri] Detente en el actual:Se utiliza cuando la página vuelve al fondo.,No detengas a otros por error. */
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
        }.getOrElse { false }
        if (!loaded) {
            runCatching { engine.release() }
            stop()
            return
        }
        player = engine
        state = MpVoicePlaybackState(uri = uri, isPlaying = true, positionMs = 0L)
        startTicker()
    }

    private fun pause() {
        ticker?.cancel()
        ticker = null
        player?.let { runCatching { it.pause() } }
        state = state.copy(isPlaying = false)
    }

    private fun resume(uri: String, file: File) {
        val engine = player
        if (engine == null) {
            // El jugador ha sido liberado.(Fue arrebatado por otras voces a mitad de camino):Procesar como nuevo comienzo
            play(uri, file)
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
        /** Intervalo de muestreo de progreso:No es necesario que las barras de voz sean cuadro por cuadro,10 fps Lo suficientemente suave,También ahorra electricidad. */
        private const val PROGRESS_INTERVAL_MS = 100L
    }
}
