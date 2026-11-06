package io.zer0.muse.ui.speech

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import org.koin.compose.koinInject

/**
 * v1.4: Controlador TTS suspendido.
 *
 * Implementado con ventana flotante Redactar(No depende de la biblioteca de terceros de FloatingWindow),Mostrado en la esquina inferior derecha de la pantalla,
 * InputBar arriba. solo si [TtsManager.playbackState] Se muestra cuando no está inactivo.
 *
 * Diseño(Botón principal redondo + barra de herramientas horizontal):
 *  - Botón principal redondo:progreso de doble anillo(Progreso del audio del círculo exterior/progreso de fragmentación del círculo interior) + Ícono central de reproducción/pausa
 *  - botón de parada
 *  - Después de desplegar:botón de velocidad(0.8x/1.0x/1.2x/1.5x bucle)+ Botón de avance rápido 5s
 *  - Expandir/Botón contraer
 *
 * Uso:Box Riga en ChatScreen `TtsControllerWidget(Modifier.align(BottomEnd))`.
 *
 * @param modifier alineación externa + padding(Ubicación especificada por la persona que llama)
 */
@Composable
fun TtsControllerWidget(modifier: Modifier = Modifier, ttsManager: TtsManager = koinInject()) {
    val state by ttsManager.playbackState.collectAsStateWithLifecycle()
    val visible = state.status != PlaybackStatus.Idle

    // Índice de ciclo de velocidad(0.8x → 1.0x → 1.2x → 1.5x)
    val speeds = remember { listOf(0.8f, 1.0f, 1.2f, 1.5f) }
    // L-SP5 Reparación: speedIndex/expanded Usar en su lugar rememberSaveable,Cambios de configuración(rotar/Reciclaje de fondo)El estado final no se pierde.
    var speedIndex by rememberSaveable { mutableIntStateOf(1) } // Predeterminado 1.0x
    var expanded by rememberSaveable { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MuseMotion.tween(MuseAnimation.TACTILE_MS)) +
            scaleIn(MuseMotion.tween(MuseAnimation.TACTILE_MS), initialScale = 0.7f),
        exit = fadeOut(MuseMotion.tween(MuseAnimation.TACTILE_MS)) +
            scaleOut(MuseMotion.tween(MuseAnimation.TACTILE_MS), targetScale = 0.7f),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = CircleShape,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // Botón principal redondo:Progreso de doble anillo + Centro Play/Pause
                CircleProgressButton(
                    isPlaying = state.status == PlaybackStatus.Playing,
                    audioProgress = if (state.durationMs > 0) {
                        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    chunkProgress = if (state.totalChunks > 0) {
                        (state.currentChunkIndex.toFloat() / state.totalChunks).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onClick = {
                        if (state.status == PlaybackStatus.Playing) {
                            ttsManager.pause()
                        } else {
                            ttsManager.resume()
                        }
                    },
                )

                // botón de parada
                CircleIconButton(
                    contentDescription = stringResource(R.string.speech_stop_reading_cd),
                    onClick = { ttsManager.stop() },
                ) {
                    Icon(
                        imageVector = MuseIcons.stop,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Después de desplegar:Navegación por cortes + 5 segundos hacia adelante y hacia atrás + doble velocidad
                AnimatedVisibility(
                    visible = expanded,
                    enter = MuseMotion.expandFadeEnter(),
                    exit = MuseMotion.expandFadeExit(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        // Pieza anterior
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_previous_chunk_cd),
                            onClick = { ttsManager.previousChunk() },
                        ) {
                            Icon(
                                imageVector = MuseIcons.chevronLeft,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // siguiente pieza
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_next_chunk_cd),
                            onClick = { ttsManager.nextChunk() },
                        ) {
                            Icon(
                                imageVector = MuseIcons.chevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // Atrás 5s
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_seek_backward_5s_cd),
                            onClick = { ttsManager.seekBy(-5_000) },
                        ) {
                            Icon(
                                imageVector = MuseIcons.refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // avance rápido 5s
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_seek_forward_5s_cd),
                            onClick = { ttsManager.seekBy(5_000) },
                        ) {
                            Icon(
                                imageVector = MuseIcons.forward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        // botón de velocidad(Haga clic para repetir 0.8x → 1.0x → 1.2x → 1.5x)
                        CircleIconButton(
                            contentDescription = stringResource(R.string.speech_playback_speed_cd, speeds[speedIndex].toString()),
                            onClick = {
                                speedIndex = (speedIndex + 1) % speeds.size
                                ttsManager.setSpeed(speeds[speedIndex])
                            },
                        ) {
                            Text(
                                text = "${speeds[speedIndex]}x",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // Expandir/cerrar
                CircleIconButton(
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                    onClick = { expanded = !expanded },
                ) {
                    Icon(
                        imageVector = if (expanded) {
                            MuseIcons.chevronLeft
                        } else {
                            MuseIcons.chevronRight
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Botón principal redondo — progreso de doble anillo(Audio del anillo exterior + corte del anillo interior)+ Ícono central de reproducción/pausa.
 *
 * - anillo exterior [CircularProgressIndicator]:Progreso de la reproducción de audio(positionMs / durationMs)
 * - Círculo interior CircularProgressIndicator: progreso de fragmentación(currentChunkIndex / totalChunks)
 * - Centro:Playing mostrar Pause(Haga clic para pausar);Paused/Ended mostrar PlayArrow(Haga clic para restaurar)
 *
 * @param isPlaying ¿Se está reproduciendo actualmente?
 * @param audioProgress progreso de audio 0..1
 * @param chunkProgress Progreso de fragmentación 0..1
 * @param onClick Haga clic en devolución de llamada(cambiar de juego/pausa)
 */
@Composable
private fun CircleProgressButton(isPlaying: Boolean, audioProgress: Float, chunkProgress: Float, onClick: () -> Unit) {
    val progressSpec = MuseMotion.tween<Float>(
        durationMillis = MuseAnimation.FAST_NORMAL_MS,
        easing = MuseAnimation.EaseOutCubic,
    )
    val animatedAudio by animateFloatAsState(
        targetValue = audioProgress,
        animationSpec = progressSpec,
        label = "audioProgress",
    )
    val animatedChunk by animateFloatAsState(
        targetValue = chunkProgress,
        animationSpec = progressSpec,
        label = "chunkProgress",
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // anillo exterior:progreso de audio(Tamaño 48dp,llenar todo Box)
        MuseProgressBar(
            progress = animatedAudio,
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        // círculo interior:Progreso de fragmentación(Tamaño 38dp,Deje un espacio de 5 dp)
        MuseProgressBar(
            progress = animatedChunk,
            color = MaterialTheme.colorScheme.tertiary,
            trackColor = Color.Transparent,
        )
        // icono central(Play / Pause cambiar)
        Icon(
            imageVector = if (isPlaying) MuseIcons.pause else MuseIcons.play,
            contentDescription = if (isPlaying) stringResource(R.string.speech_pause_cd) else stringResource(R.string.speech_resume_cd),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Botón redondo pequeño universal(48dp clickable Box,Icono de poner en el centro/texto).
 *
 * Utilice Box + en el que se puede hacer clic en su lugar IconButton,Evite el relleno predeterminado de IconButton para expandir el diseño,Haga que las ventanas flotantes sean más compactas.
 * Toque la línea roja MD3 de alineación del objetivo(48dp = [MuseIconSizes.touchTarget]),Asegure la posibilidad de hacer clic en la ventana flotante.
 *
 * @param contentDescription Descripción de accesibilidad
 * @param onClick Haga clic en devolución de llamada
 * @param content contenido(Icon / Text)
 */
@Composable
private fun CircleIconButton(contentDescription: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(MuseIconSizes.touchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
