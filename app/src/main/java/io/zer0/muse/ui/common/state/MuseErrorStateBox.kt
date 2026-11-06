package io.zer0.muse.ui.common.state

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill

/**
 * Componente de estado de error unificado,Con botón de reintento.
 *
 * v1.0.20 Actualización visual(Componente zombie de resurrección):
 *  - Se cambió de la barra de error de fila a la página de estado centrada en la columna.,con [MuseEmptyState] simetría visual
 *  - Contenedor de fondo de color de error redondo(error 8% abajo + icono de error 60%)
 *  - Textura cálida en lugar de rojo frío. errorContainer,Reducir la agresión visual
 *  - El diseño centrado es más adecuado para estados de error de pantalla completa,Reemplace el mensaje de error de texto simple en cada página
 *
 * v1.0.23 Efecto de micromovimiento de admisión.:
 *  - con [MuseEmptyState] Desvanecimiento simétrico + deslizamiento hacia arriba(20px)Transición
 *  - uso [MuseAnimation.NORMAL_MS](240ms)+ [MuseAnimation.EaseOutCubic] Curva
 *
 * @param message Copia incorrecta
 * @param onRetry Reintentar devolución de llamada(Opcional,Mostrar después de proporcionar"Inténtalo de nuevo"botón)
 * @param onDismiss Cerrar devolución de llamada(Opcional,Mostrar después de proporcionar"cerrar"botón)
 * @param modifier modificador
 */
@Composable
fun MuseErrorStateBox(message: String, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    // v1.0.23: La primera combinación desencadena la transición de entrada.(AnimatedVisibility inicial visible=true No reproduzcas animación.).
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(
            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
        ) + slideInVertically(
            animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS, easing = MuseAnimation.EaseOutCubic),
            // v2.6.7: El desplazamiento recibió el 5%. Originalmente la mitad de la altura (50%), para grandes piezas de contenido, como estados vacíos
            // Se muestra como"Toda la pieza fue levantada"; El estado corto debe aumentar suave y ligeramente.
            initialOffsetY = { (it * MuseAnimation.SLIDE_FRACTION_CONTENT).toInt() },
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(MusePaddings.screen * 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // v1.0.20: Contenedor de fondo redondo de color incorrecto
            Box(
                modifier = Modifier
                    .size(MuseIconSizes.iconEmpty + MusePaddings.screen * 2)
                    .background(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.alertCircle,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconEmpty),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null || onDismiss != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    if (onRetry != null) {
                        FilledTonalButton(
                            onClick = onRetry,
                            shape = MuseShapes.pill,
                        ) {
                            Text(stringResource(R.string.common_retry))
                        }
                    }
                    if (onDismiss != null) {
                        FilledTonalButton(
                            onClick = onDismiss,
                            shape = MuseShapes.pill,
                        ) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
        }
    }
}
