package io.zer0.muse.ui.common.state

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion

/**
 * componente de progreso local — Pequeños círculos y barras de progreso en botones, esquinas de tarjetas y filas.
 *
 * La carga a nivel de página todavía se utiliza [MuseLoadingState];Este archivo sólo es responsable de "una pequeña parte de la página está ocupada".
 *
 * con Material3 `CircularProgressIndicator` La diferencia:
 *  - El color sigue por defecto [LocalContentColor], colocado en sólido/Los botones de colores peligrosos no se volverán invisibles en el mismo color;
 *  - El tamaño y el ancho de línea están estandarizados y unificados en todas las aplicaciones;
 *  - Autodibujado, no se introducen nuevas dependencias de control nativo.
 *
 * @param size diámetro
 * @param strokeWidth Ancho de línea
 * @param color Color del arco (el valor predeterminado sigue el color del texto actual)
 */
@Composable
fun MuseSpinner(
    modifier: Modifier = Modifier,
    size: Dp = MuseIconSizes.iconSmall,
    strokeWidth: Dp = MuseIconSizes.progressStroke,
    color: Color = LocalContentColor.current,
) {
    val density = LocalDensity.current
    val diameterPx = with(density) { size.toPx() }
    val strokePx = with(density) { strokeWidth.toPx() }
    val reducedMotion = MuseMotion.isReducedMotion()

    val angle = if (reducedMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "muse_spinner")
        val animated by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "muse_spinner_angle",
        )
        animated
    }
    // Respiración de longitud de arco: la duración del ciclo se reduce uniformemente a 1,8 segundos por ciclo.;La longitud del arco es 60°↔300° ida y vuelta entre,Evite el reinicio completo.
    // Visualmente cerca de los indicadores estándar de materiales.,Se puede ver que se mueve sin llamar la atención.
    val sweep = if (reducedMotion) {
        90f
    } else {
        val transition = rememberInfiniteTransition(label = "muse_spinner_sweep")
        val animated by transition.animateFloat(
            initialValue = 60f,
            targetValue = 300f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_SLOW_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "muse_spinner_sweep",
        )
        animated
    }

    Canvas(modifier = modifier.size(size)) {
        val inset = strokePx / 2f
        val arcSize = Size(diameterPx - strokePx, diameterPx - strokePx)
        drawArc(
            color = color.copy(alpha = 0.2f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round),
        )
    }
}

/**
 * Barra de progreso determinista (0..1).
 *
 * @param progress progreso, converge automáticamente a 0..1
 * @param height altura orbital
 */
@Composable
fun MuseProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MuseActionColors.container,
    trackColor: Color = MuseActionColors.neutralContainer,
    height: Dp = MuseProgressDefaults.trackHeight,
) {
    val fraction = progress.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        if (fraction > 0f) {
            drawRoundRect(
                color = color,
                size = Size(size.width * fraction, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * Barra de progreso incierta — Se utiliza para cargas de duración desconocida (reemplazando el modo infinito del LinearProgressIndicator nativo).
 */
@Composable
fun MuseIndeterminateProgressBar(
    modifier: Modifier = Modifier,
    color: Color = MuseActionColors.container,
    trackColor: Color = MuseActionColors.neutralContainer,
    height: Dp = MuseProgressDefaults.trackHeight,
) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val head = if (reducedMotion) {
        0.35f
    } else {
        val transition = rememberInfiniteTransition(label = "muse_progress")
        val animated by transition.animateFloat(
            initialValue = -MuseProgressDefaults.segmentFraction,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(MuseAnimation.LOOP_EXTRA_SLOW_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "muse_progress_head",
        )
        animated
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        val segment = size.width * MuseProgressDefaults.segmentFraction
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * head, 0f),
            size = Size(segment, size.height),
            cornerRadius = radius,
        )
    }
}

/** Constantes de tamaño para el componente de progreso. */
object MuseProgressDefaults {
    /** Altura orbital. */
    val trackHeight: Dp = 4.dp

    /** No estoy seguro de la relación de ancho del segmento deslizante de la barra de progreso. */
    const val segmentFraction: Float = 0.3f
}
