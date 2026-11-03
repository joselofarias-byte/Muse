package io.zer0.muse.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.speech.VoiceMessageStore
import io.zer0.muse.ui.speech.VoicePlaybackController
import org.koin.compose.koinInject

/** Identificación de nodos de barra de voz (para pruebas y posicionamiento de accesibilidad). */
internal const val VOICE_MESSAGE_BUBBLE_TAG = "voice-message-bubble"

/** ID de nodo estático cuando el archivo de voz ha caducado. */
internal const val VOICE_MESSAGE_EXPIRED_TAG = "voice-message-expired"

/** jugar/Pausa la identificación del icono (afirma que el icono está realmente cortado). */
internal const val VOICE_MESSAGE_PLAY_ICON_TAG = "voice-message-icon-play"
internal const val VOICE_MESSAGE_PAUSE_ICON_TAG = "voice-message-icon-pause"

/** El tamaño del icono del botón de reproducción. */
private val VOICE_ICON_SIZE = 20.dp

/** Altura de la barra de voz(altura de la cápsula,Los objetivos táctiles están cubiertos por el espaciado de lista exterior). */
private val VOICE_ROW_HEIGHT = 40.dp

/** Número de columnas de forma de onda: no importa cuán densa sea, no podrás verla"forma de onda", la sensación de avance desaparece al adelgazar y rellenar. */
private const val VOICE_WAVE_BAR_COUNT = 26

/**
 * El ancho de la barra de voz se divide en pasos (no aumenta uniformemente con la cantidad de segundos).
 *
 * Tres niveles en lugar de un mapeo continuo: la duración del habla está distribuida de manera extremadamente desigual (la mayoría 3–10 segundos), el mapeo uniforme hará que la mayoría
 * Si la voz se aprieta en el extremo más estrecho, no se puede ver ninguna diferencia, y si la voz es muy larga, se estirará hasta llenar toda la línea. Dividido en corto, medio y largo.
 * Cada uno tiene un ancho visual estable y el más largo es significativamente más corto que el ancho disponible de la burbuja.
 */
internal fun voiceBubbleWidth(durationMs: Long): Dp = when {
    durationMs <= 15_000L -> 132.dp
    durationMs <= 60_000L -> 176.dp
    else -> 220.dp
}

/**
 * Copia de duración de voz: milisegundos → "M:SS"(más de 1 hora "H:MM:SS").
 *
 * Duración desconocida (<= 0) tiempo dado "--:--" en lugar de "0:00" —— Este último se leerá como"Voz de cero segundos".
 * Números puros + dos puntos, sin localización.
 */
internal fun formatVoiceDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Tira de mensajes de voz.
 *
 * Los mensajes del asistente se comparten con los mensajes del usuario: cápsula con esquinas redondeadas + reproducción en el lado izquierdo/Pausa + forma de onda estrecha en el medio + duración a la derecha.
 * Los derechos de reproducción se otorgan a los compartidos. [VoicePlaybackController], la burbuja en sí no sostiene al jugador —— mismo momento
 * Solo se reproducirá una voz y al hacer clic para cambiar la voz se detendrá primero la voz anterior.
 *
 * Cuando se desconoce la duración, se muestra a la derecha. "--:--";Durante la reproducción, se muestran el tiempo de reproducción transcurrido y el tiempo de inactividad./Mostrar la duración total mientras está en pausa
 * (Sepa mientras juega"¿Dónde lo escuchaste?", para saber cuando tienes tiempo libre"¿Cuánto dura este?").
 *
 * [store] Las referencias que no se pudieron resolver (el archivo se borró) se degradan a estáticas"La voz ha caducado", no se puede hacer clic en él sin generar una excepción.
 *
 * @param voiceUri [VoiceMessageStore] Citas archivadas para
 * @param durationMs Duración total de la voz (milisegundos),0 Indica desconocido
 * @param isFromUser El color de la burbuja en el lado del usuario es diferente al del lado del asistente.
 */
@Composable
internal fun VoiceMessageBubble(
    voiceUri: String,
    durationMs: Long,
    isFromUser: Boolean,
    modifier: Modifier = Modifier,
    controller: VoicePlaybackController = koinInject(),
    store: VoiceMessageStore = koinInject(),
) {
    val resolved = remember(voiceUri, store) { store.resolve(voiceUri) }
    val width = voiceBubbleWidth(durationMs)
    if (resolved == null) {
        VoiceExpiredBubble(isFromUser = isFromUser, modifier = modifier.width(width))
        return
    }

    val playback = controller.state
    val ownsPlayback = playback.uri == voiceUri
    val isPlaying = ownsPlayback && playback.isPlaying
    val positionMs = if (ownsPlayback) playback.positionMs else 0L
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val timeText = formatVoiceDuration(if (isPlaying) positionMs else durationMs)
    val toggleLabel = stringResource(if (isPlaying) R.string.chat_voice_pause_cd else R.string.chat_voice_play_cd)

    // Cortar fondo/Suelta el reproductor al salir de la página: la burbuja es pasiva y sólo se detendrá cuando esté ocupando los derechos de reproducción.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, voiceUri) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.stopIfActive(voiceUri)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier =
        modifier
            .width(width)
            .heightIn(min = VOICE_ROW_HEIGHT)
            .clip(CircleShape)
            .background(capsuleColor(isFromUser))
            .clickable(role = Role.Button, onClick = { controller.toggle(voiceUri, resolved) })
            .semantics { contentDescription = toggleLabel }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(VOICE_MESSAGE_BUBBLE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (isPlaying) MuseIcons.pause else MuseIcons.play,
            contentDescription = null,
            tint = accentColor(isFromUser),
            modifier = Modifier
                .size(VOICE_ICON_SIZE)
                .testTag(if (isPlaying) VOICE_MESSAGE_PAUSE_ICON_TAG else VOICE_MESSAGE_PLAY_ICON_TAG),
        )
        VoiceWaveform(
            progress = progress,
            seed = voiceUri.hashCode(),
            activeColor = accentColor(isFromUser),
            inactiveColor = inactiveWaveColor(isFromUser),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** El archivo ha caducado: sólo queda una descripción estática. No se puede hacer clic en él ni se puede construir el reproductor. */
@Composable
private fun VoiceExpiredBubble(
    isFromUser: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
        modifier
            .heightIn(min = VOICE_ROW_HEIGHT)
            .clip(CircleShape)
            .background(capsuleColor(isFromUser))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(VOICE_MESSAGE_EXPIRED_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = MuseIcons.play,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(VOICE_ICON_SIZE),
        )
        Text(
            text = stringResource(R.string.chat_voice_expired),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Forma de onda estrecha: una fila de barras verticales con cabezas redondas de ancho fijo, la altura de la cresta de la onda es [seed] Decisión (la misma voz se dibuja consistentemente cada vez,
 * No saltará con la reorganización) y la parte reproducida se cambiará al color de acento.
 */
@Composable
private fun VoiceWaveform(
    progress: Float,
    seed: Int,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
) {
    val peaks = remember(seed) { wavePeaks(seed, VOICE_WAVE_BAR_COUNT) }
    Canvas(modifier = modifier.height(20.dp)) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (peaks.size - 1)) / peaks.size).coerceAtLeast(1f)
        val playedEdge = size.width * progress
        peaks.forEachIndexed { index, peak ->
            val barHeight = (size.height * peak).coerceAtLeast(1.5.dp.toPx())
            val left = index * (barWidth + gap)
            val played = left + barWidth <= playedEdge
            drawRoundRect(
                color = if (played) activeColor else inactiveColor,
                topLeft = Offset(left, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

/** Generar una relación de altura máxima estable a partir de la semilla (0.25–1.0). */
private fun wavePeaks(seed: Int, count: Int): List<Float> {
    var state = seed.toLong() * 2654435761L + 0x9E3779B9L
    return List(count) {
        state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
        val norm = ((state ushr 16) and 0x7FFF).toFloat() / 0x7FFF.toFloat()
        0.25f + norm * 0.75f
    }
}

@Composable
private fun capsuleColor(isFromUser: Boolean): Color =
    if (isFromUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }

@Composable
private fun accentColor(isFromUser: Boolean): Color =
    if (isFromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

@Composable
private fun inactiveWaveColor(isFromUser: Boolean): Color =
    if (isFromUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f)
    }
