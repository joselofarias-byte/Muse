package io.zer0.muse.ui.common.form

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v1.40: control deslizante — Anular el valor predeterminado del material [androidx.compose.material3.Slider].
 *
 * diferencias visuales(Contraste Material Slider):
 *  - La pista es más delgada.(4dp vs Material 16dp),Más acorde con el estilo de configuración del sistema
 *  - Pulgar más pequeño(20dp vs Material 24dp)+ trazo blanco
 *  - Para la parte llena primary,Para vacantes surfaceVariant
 *  - Animación suave con zoom del pulgar al arrastrar
 *
 * Uso totalmente compatible con Material Slider:
 * ```
 * MuseSlider(
 *     value = temperature,
 *     onValueChange = { v -> update { it.copy(temperature = v) } },
 *     valueRange = 0f..2f,
 * )
 * ```
 *
 * @param value valor actual
 * @param onValueChange devolución de llamada de cambio de valor(Activación continua durante el arrastre)
 * @param valueRange Rango de valores(Predeterminado 0f..1f)
 * @param modifier Modifier
 * @param steps pasos discretos(0 = continuo,>0 = N pasos discretos,Consistente con el control deslizante de material)
 * @param showValueLabel Si se muestra el texto del valor actual a la derecha(Predeterminado true)
 * @param valueFormatter Función de formato de valor(Por defecto a 2 decimales)
 * @param onValueChangeFinished Arrastrar y finalizar la devolución de llamada(Se activa al soltar o cancelar,Vacío predeterminado,compatible con versiones anteriores).
 *   P0-2: Se utiliza para admitir RagSettingsPage, etc."Escribe cuando te sueltes DataStore"Escenarios de optimización de persistencia.
 */
@Composable
fun MuseSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    showValueLabel: Boolean = true,
    valueFormatter: (Float) -> String = { "%.2f".format(it) },
    onValueChangeFinished: () -> Unit = {},
) {
    val start = valueRange.start
    val end = valueRange.endInclusive
    val range = end - start
    val rawFraction = if (range > 0) ((value - start) / range).coerceIn(0f, 1f) else 0f
    // H-SL1: Alinear la semántica de los pasos del control deslizante Material3 — steps=N Indica que hay N puntos de parada intermedios entre inicio/fin,
    // Valores totales N+2,graduación discreta = N+1(en lugar del viejo N),La implementación anterior tiene un valor menos.
    val fraction = snapFraction(rawFraction, steps)
    val thumbSizePx = with(LocalDensity.current) { SliderThumbSize.toPx() }
    val trackHeightPx = with(LocalDensity.current) { SliderTrackHeight.toPx() }

    // Animación de zoom con el pulgar mientras arrastra.L-SL4: isDragging Utilice semántica booleana(La implementación anterior hizo un mal uso de Float 0f/1f cuando era booleano).
    var isDragging by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(
        targetValue = if (isDragging) 1.1f else 1f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
        label = "thumbScale",
    )
    val trackColorVal = trackColor()
    val filledColorVal = filledColor()
    // v1.48 (h20): thumb sobre fondo de color primario,Usar onPrimary en lugar de desnudo Color.White
    val thumbColorVal = thumbColor()
    // stringResource Debe extraerse en la ubicación de llamada directa de @Composable,no puedo estar ahí semantics{} Para uso interno.
    val sliderCd = stringResource(R.string.common_slider_cd, valueFormatter(value))

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .pointerInput(valueRange) {
                    val trackWidthPx = size.width.toFloat() - thumbSizePx
                    val usableWidth = trackWidthPx.coerceAtLeast(0f)
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val x = (offset.x - thumbSizePx / 2).coerceIn(0f, usableWidth)
                            val newFraction = if (usableWidth > 0) x / usableWidth else 0f
                            val stepped = snapFraction(newFraction, steps)
                            onValueChange((start + stepped * range).coerceIn(start, end))
                        },
                        onDragEnd = {
                            isDragging = false
                            onValueChangeFinished()
                        },
                        onDragCancel = {
                            isDragging = false
                            onValueChangeFinished()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val currentFraction = if (usableWidth > 0) {
                                ((change.position.x - thumbSizePx / 2) / usableWidth).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            val stepped = snapFraction(currentFraction, steps)
                            onValueChange((start + stepped * range).coerceIn(start, end))
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    // M-SL2: progressSemantics Registrar acciones semánticas ajustables + automáticas. stateDescription,
                    // TalkBack Utilice las teclas de volumen para ajustar el valor del control deslizante paso a paso(accessibility custom action).
                    .progressSemantics(value, valueRange, steps)
                    .semantics {
                        contentDescription = sliderCd
                    },
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val centerY = canvasHeight / 2
                val trackStart = thumbSizePx / 2
                val trackEnd = canvasWidth - thumbSizePx / 2
                val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
                val thumbCenter = trackStart + fraction * trackWidth

                // pista sin llenar(gris)
                drawRoundRect(
                    color = trackColorVal,
                    topLeft = Offset(trackStart, centerY - trackHeightPx / 2),
                    size = Size(trackWidth, trackHeightPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeightPx / 2, trackHeightPx / 2),
                )
                // Pista llena(primary)
                val filledWidth = (thumbCenter - trackStart).coerceAtLeast(0f)
                if (filledWidth > 0) {
                    drawRoundRect(
                        color = filledColorVal,
                        topLeft = Offset(trackStart, centerY - trackHeightPx / 2),
                        size = Size(filledWidth, trackHeightPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeightPx / 2, trackHeightPx / 2),
                    )
                }
                // pulgar(blanco + sombra)
                val thumbRadius = (thumbSizePx / 2) * thumbScale
                drawCircle(
                    color = thumbColorVal, // v1.48 (h20): onPrimary alternativa desnuda Color.White
                    radius = thumbRadius,
                    center = Offset(thumbCenter, centerY),
                )
                drawCircle(
                    color = filledColorVal.copy(alpha = 0.15f),
                    radius = thumbRadius * 0.7f,
                    center = Offset(thumbCenter, centerY),
                )
            }
        }
        if (showValueLabel) {
            Spacer(Modifier.width(MusePaddings.itemGap))
            Text(
                text = valueFormatter(value),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(48.dp),
            )
        }
    }
}

// L-SL3: Las dimensiones específicas del control deslizante se dibujan como tokens locales,Evite la dispersión desnuda de 22.dp / 6.dp.
/** Tamaño visual del pulgar deslizante. */
private val SliderThumbSize = 22.dp

/** Altura del carril deslizante. */
private val SliderTrackHeight = 6.dp

/**
 * H-SL1: Discretiza semánticamente fracciones continuas mediante los pasos del control deslizante Material3.
 * steps=N Indica que hay N puntos de parada intermedios entre inicio/fin(Valores totales N+2),
 * graduación discreta = N+1,Devuelve la fracción de adsorción entre 0..1.
 */
private fun snapFraction(fraction: Float, steps: Int): Float {
    if (steps <= 0) return fraction.coerceIn(0f, 1f)
    val divisor = steps + 1
    return (kotlin.math.round(fraction * divisor) / divisor).coerceIn(0f, 1f)
}

@Composable
private fun trackColor(): Color = MaterialTheme.colorScheme.surfaceVariant

@Composable
private fun filledColor(): Color = MaterialTheme.colorScheme.primary

// v1.48 (h20): color del pulgar — thumb Dibujar en la pista de relleno del color primario,Utilice onPrimary para garantizar el contraste.
@Composable
private fun thumbColor(): Color = MaterialTheme.colorScheme.onPrimary
