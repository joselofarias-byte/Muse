package io.zer0.muse.ui.common.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion

/**
 * Punto indicador de estado — Se utiliza para el estado de conexión del servidor/proveedor MCP.
 *
 * @param color color de estado(CONNECTED VERDE / CONECTANDO AMARILLO / ERROR ROJO / DESCONECTADO GRIS)
 * @param size Tamaño de punto(Predeterminado 8dp)
 * @param pulse Si se debe habilitar la animación de pulso(CONNECTING Para el estado)
 * @param contentDescription Descripción de accesibilidad(Como por ejemplo"Conectado"),Sin semántica de registro cuando es nulo
 */
@Composable
fun StatusDot(color: Color, size: Dp = 8.dp, pulse: Boolean = false, contentDescription: String? = null) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val alpha = if (!pulse) {
        1f
    } else if (reducedMotion) {
        0.65f
    } else {
        val transition = rememberInfiniteTransition(label = "status_dot_pulse")
        val animatedAlpha by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status_dot_alpha",
        )
        animatedAlpha
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
            // L-SC6: Registrar contenidoDescripción semántica, TalkBack Estado de conexión legible(Como por ejemplo"Conectado").
            .semantics(mergeDescendants = false) {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
    )
}
