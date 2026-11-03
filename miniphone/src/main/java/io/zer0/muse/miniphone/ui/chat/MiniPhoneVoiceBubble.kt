package io.zer0.muse.miniphone.ui.chat

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import org.koin.compose.koinInject
import java.io.File

/**
 * tira de mensajes de voz:Cápsula + botón de reproducción + forma de onda + duración.
 *
 * El diseño se fija en tres secciones.:jugar a la izquierda/pausa,Una fila de barras verticales en el medio.,Duración total a la derecha(Cambiar a la duración reproducida durante la reproducción).
 * El derecho de reproducción no pertenece a Bubble. —— Haga clic sólo para [MiniPhoneVoicePlaybackController] querer,Entonces en el mismo momento
 * solo uno esta jugando,Incluso si cambias de sesión o te retiras a un segundo plano, ningún sonido quedará atrás.
 *
 * Duración desconocida(<= 0)Cuando se escribe a la derecha `--:--` en lugar de `0:00`:Este último se leerá como "discurso de cero segundos".
 * Reducido a una nota estática cuando el archivo de audio se ha borrado,El reproductor no falla incluso si no lo juego.
 *
 * @param message mensaje de voz;usar [ChatMessageUi.mediaUri] Audio de posición,usar [ChatMessageUi.id] hacer etiquetas de prueba
 * @param controller Controlador de reproducción Singleton dentro del módulo
 * @param onLongPress Mantenga presionado para que aparezca el menú de operación.
 */
@Composable
internal fun ChatVoiceBubble(
    message: ChatMessageUi,
    controller: MiniPhoneVoicePlaybackController = koinInject(),
    onLongPress: () -> Unit = {},
) {
    val uri = message.mediaUri.orEmpty()
    val durationMs = message.voiceDurationMs
    val file = remember(uri) { localAudioFileOf(uri) }
    val width = voiceBubbleWidth(durationMs)
    if (file == null) {
        VoiceUnavailableBubble(message = message, width = width, onLongPress = onLongPress)
        return
    }

    val mine = message.isMine
    val playback = controller.state
    val owns = playback.uri == uri
    val playing = owns && playback.isPlaying
    val positionMs = if (owns) playback.positionMs else 0L
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val timeText = formatVoiceDuration(if (playing) positionMs else durationMs)

    // Salir de la página(ON_STOP)o la burbuja se destruye(Cambiar de sesión, cambiar de página)detener el actual
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, uri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stopIfActive(uri)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stopIfActive(uri)
        }
    }

    Row(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.voice(message.id))
            .width(width)
            .heightIn(min = VoiceRowHeight)
            .clip(CircleShape)
            .background(if (mine) MiniPhoneTheme.colors.bubbleSelf else MiniPhoneTheme.colors.bubbleOther)
            .then(
                if (mine) {
                    Modifier
                } else {
                    Modifier.border(MiniPhoneTheme.dimens.hairline, MiniPhoneTheme.colors.bubbleOtherBorder, CircleShape)
                },
            )
            .combinedClickable(
                role = Role.Button,
                onClick = { controller.toggle(uri, file) },
                onLongClick = onLongPress,
            )
            .semantics { contentDescription = if (playing) VOICE_PAUSE_LABEL else VOICE_PLAY_LABEL }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (playing) MiniPhoneIcons.Pause else MiniPhoneIcons.Play,
            contentDescription = null,
            tint = if (mine) MiniPhoneTheme.colors.onAccent else MiniPhoneTheme.colors.accent,
            modifier = Modifier
                .size(VoiceIconSize)
                .testTag(if (playing) MiniPhoneChatTags.VOICE_PAUSE_ICON else MiniPhoneChatTags.VOICE_PLAY_ICON),
        )
        VoiceWaveform(
            progress = progress,
            seed = uri.hashCode(),
            activeColor = if (mine) MiniPhoneTheme.colors.onAccent else MiniPhoneTheme.colors.accent,
            inactiveColor = if (mine) {
                MiniPhoneTheme.colors.onAccent.copy(alpha = INACTIVE_BAR_ALPHA)
            } else {
                MiniPhoneTheme.colors.accent.copy(alpha = INACTIVE_BAR_ALPHA)
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timeText,
            style = MiniPhoneTheme.typography.timestamp.copy(
                color = if (mine) MiniPhoneTheme.colors.bubbleSelfText else MiniPhoneTheme.colors.bubbleOtherText,
            ),
        )
    }
}

/** El audio ya no esta:misma forma de cápsula,Deja solo el ícono y una descripción. —— no puedo jugar,Pero aún puedes mantener presionado para eliminar. */
@Composable
private fun VoiceUnavailableBubble(message: ChatMessageUi, width: Dp, onLongPress: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val mine = message.isMine
    Row(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.voiceUnavailable(message.id))
            .width(width)
            .heightIn(min = VoiceRowHeight)
            .clip(CircleShape)
            .background(if (mine) colors.bubbleSelf else colors.bubbleOther)
            .then(
                if (mine) {
                    Modifier
                } else {
                    Modifier.border(MiniPhoneTheme.dimens.hairline, colors.bubbleOtherBorder, CircleShape)
                },
            )
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MiniPhoneIcons.Play,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(VoiceIconSize),
        )
        Text(
            text = VOICE_UNAVAILABLE_TEXT,
            style = MiniPhoneTheme.typography.timestamp.copy(color = colors.textSecondary),
        )
    }
}

/**
 * Una fila de barras verticales con cabezas redondeadas de ancho fijo.,La altura de la cresta de la ola está dada por [seed] decidir —— El mismo sonido de voz se ve igual cada vez que se dibuja.,La reorganización no saltará.
 *
 * Cambie el color de énfasis al segmento que ya se ha reproducido:No hay escala de tiempo en la barra de voz.,El progreso sólo se puede leer con este color.
 */
@Composable
private fun VoiceWaveform(progress: Float, seed: Int, activeColor: Color, inactiveColor: Color, modifier: Modifier = Modifier) {
    val peaks = remember(seed) { wavePeaks(seed, VOICE_WAVE_BAR_COUNT) }
    Canvas(modifier = modifier.height(VoiceWaveHeight)) {
        val gap = WaveBarGap.toPx()
        val barWidth = ((size.width - gap * (peaks.size - 1)) / peaks.size).coerceAtLeast(1f)
        val playedEdge = size.width * progress
        peaks.forEachIndexed { index, peak ->
            val barHeight = (size.height * peak).coerceAtLeast(MinBarHeight.toPx())
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = if (left + barWidth <= playedEdge) activeColor else inactiveColor,
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Proporción de pico estable distribuida por semillas(0.25–1.0):La congruencia lineal toma el orden inferior.,Aritmética pura, sin fuentes aleatorias. */
private fun wavePeaks(seed: Int, count: Int): List<Float> {
    var state = seed.toLong() * 2654435761L + 0x9E3779B9L
    return List(count) {
        state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
        val norm = ((state ushr 16) and 0x7FFF).toFloat() / 0x7FFF.toFloat()
        0.25f + norm * 0.75f
    }
}

/**
 * Ancho de la barra de voz:Dividido en tres niveles según la duración.,No se realiza ningún mapeo uniforme.
 *
 * La distribución de la duración del habla es extremadamente desigual.(Mayormente 3–10 segundos),El mapeo uniforme hará que la mayoría de las voces queden apretujadas en el extremo más estrecho, sin diferencias visibles.,
 * La voz extremadamente larga continuará hasta llenar toda la línea. Después de la agrupación, los anchos cortos, medianos y largos tienen estables.,El engranaje más largo también es significativamente más estrecho que el ancho disponible de la burbuja.
 */
internal fun voiceBubbleWidth(durationMs: Long): Dp = when {
    durationMs <= SHORT_VOICE_MS -> ShortVoiceWidth
    durationMs <= MEDIUM_VOICE_MS -> MediumVoiceWidth
    else -> LongVoiceWidth
}

/**
 * Redacción de duración:milisegundos → `M:SS`(más de una hora `H:MM:SS`).
 *
 * Duración desconocida(<= 0)dar `--:--`:No es una voz de cero segundos.,Es "no detectado".
 */
internal fun formatVoiceDuration(durationMs: Long): String {
    if (durationMs <= 0L) return UNKNOWN_DURATION
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * citas de discursos → archivo local.
 *
 * Las tiendas anfitrionas `file://` Agregar ruta absoluta;`content://` Referencias como esta no se pueden reproducir en este módulo.(El reproductor solo acepta archivos.),
 * Regresar incluso si el archivo ya no existe null,Degenerar de burbujas a instrucciones estáticas.
 *
 * Cuando hay barras invertidas o espacios en la ruta [Uri] No puedo entenderlo path(La barra invertida no es un separador,El párrafo anterior se considerará authority),
 * En este momento, elimine directamente el prefijo y utilícelo como ruta. —— La base para el juicio es que el camino resuelto debe ser coronado por `/` Comienzo.
 */
private fun localAudioFileOf(uri: String): File? {
    val raw = uri.trim()
    if (raw.isEmpty()) return null
    val parsed = Uri.parse(raw)
    val path = when (parsed.scheme) {
        null -> raw
        ContentResolver.SCHEME_FILE -> parsed.path?.takeIf { it.startsWith("/") } ?: raw.removePrefix(FILE_PREFIX)
        else -> null
    } ?: return null
    return File(path).takeIf { it.isFile }
}

private val VoiceRowHeight = 40.dp
private val VoiceIconSize = 18.dp
private val VoiceWaveHeight = 18.dp
private val WaveBarGap = 2.dp
private val MinBarHeight = 1.5.dp
private val ShortVoiceWidth = 140.dp
private val MediumVoiceWidth = 184.dp
private val LongVoiceWidth = 228.dp

/** Número de barras de la forma de onda.:18 Las raíces sólo cubren el nivel más estrecho.,No importa cuán densa sea, la forma de onda ya no será visible. */
private const val VOICE_WAVE_BAR_COUNT: Int = 18

private const val INACTIVE_BAR_ALPHA: Float = 0.28f

/** límite del contenedor:15 Menos de segundos se consideran cortos,1 Cuente en minutos. */
private const val SHORT_VOICE_MS: Long = 15_000L
private const val MEDIUM_VOICE_MS: Long = 60_000L

internal const val VOICE_UNAVAILABLE_TEXT: String = "No se puede reproducir la voz"
private const val UNKNOWN_DURATION: String = "--:--"
private const val VOICE_PLAY_LABEL: String = "reproducir voz"
private const val VOICE_PAUSE_LABEL: String = "pausar el discurso"
private const val FILE_PREFIX: String = "file://"
