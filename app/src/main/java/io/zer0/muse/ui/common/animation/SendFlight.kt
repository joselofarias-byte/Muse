package io.zer0.muse.ui.common.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * Al enviar, el vuelo del "texto se eleva desde el cuadro de entrada y forma una burbuja".
 *
 * Solo se activa en el primer mensaje de la conversación: cada mensaje posterior se convertirá en ruido y la primera oración será "La conversación comienza".
 * Esto en sí mismo merece una acción.
 *
 * Compensación clave de implementación: la capa fantasma se dibuja en una ventana separada (Popup), el sistema de coordenadas utiliza límites de ventana.
 * De esta manera, el área de contenido de Scaffold no la recorta y no es necesario modificar el diseño raíz de la pantalla para apilar una capa.
 *
 * Al final del vuelo (el último 15%), la capa fantasma se desvanece para coincidir con las burbujas reales que ya están en su lugar en la lista.
 * Sin reflujo de texto en tiempo real: el texto real seguirá saltando líneas cuando se reduzca el ancho, lo cual es más confuso que el fundido cruzado.
 */

/** Envía la geometría y el contenido del vuelo de una vez. */
internal data class SendFlight(
    val text: String,
    val from: Rect,
    val to: Rect,
)

/** Duración total del vuelo. */
private const val FLIGHT_MS = 480

/**
 * Comienza rápidamente y se mueve lentamente con una cola larga. Sin rebote después del aterrizaje: El rebote hará que el movimiento parezca frívolo.
 */
private val FlightEasing = CubicBezierEasing(0.22f, 0.68f, 0f, 1f)

/** La relación de ancho máximo del punto de aterrizaje de la burbuja (la sensación es consistente con el ancho máximo de la burbuja de chat). */
private const val TARGET_WIDTH_RATIO = 0.72f

/**
 * El punto de ubicación se calcula a partir del rectángulo de la ventana del cuadro de entrada y la lista de mensajes.
 *
 * El punto de ubicación está una fila por encima del final de la lista: los mensajes nuevos siempre se publican al final de la lista después de enviarlos, y la capa fantasma cae aquí.
 * Presione justo encima de la burbuja real.
 */
internal fun computeSendFlight(text: String, input: Rect, list: Rect): SendFlight? {
    val valid = text.isNotBlank() && input.width > 0f && input.height > 0f && list.width > 0f
    if (!valid) return null
    val horizontalInset = list.width * 0.08f
    val width = (list.width * TARGET_WIDTH_RATIO).coerceAtMost(list.width - horizontalInset * 2f)
    val height = input.height
    val right = list.right - horizontalInset
    val top = (list.bottom - height - height * 0.35f).coerceAtLeast(list.top)
    val to = Rect(left = right - width, top = top, right = right, bottom = top + height)
    return SendFlight(text = text, from = input, to = to)
}

/**
 * Capa fantasma. Una capa del tamaño completo de la ventana usa desplazamiento para colocar el texto donde debería estar.
 *
 * @param flight No se representa cuando es nulo; devolución de llamada después de renderizar [onFinished] Deje que la persona que llama borre el estado
 */
@Composable
internal fun SendFlightLayer(flight: SendFlight?, onFinished: () -> Unit) {
    if (flight == null) return
    val progress = remember(flight) { Animatable(0f) }
    LaunchedEffect(flight) {
        progress.animateTo(targetValue = 1f, animationSpec = tween(FLIGHT_MS, easing = FlightEasing))
        onFinished()
    }
    val density = LocalDensity.current
    val p = progress.value
    val x = lerpFloat(flight.from.left, flight.to.left, p)
    val y = lerpFloat(flight.from.top, flight.to.top, p)
    val width = lerpFloat(flight.from.width, flight.to.width, p)
    val height = lerpFloat(flight.from.height, flight.to.height, p)
    // El primer 30% apenas ha despegado, el fondo sigue siendo transparente: como sacar texto de un cuadro de entrada"recogerlo".
    val backgroundAlpha = ((p - 0.30f) / 0.55f).coerceIn(0f, 1f)
    // El último 15% se desvanece y la verdadera burbuja toma el control.
    val ghostAlpha = if (p > 0.85f) ((1f - p) / 0.15f).coerceIn(0f, 1f) else 1f
    val cornerRadius = lerpFloat(10f, 18f, p)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(with(density) { width.toDp() })
                .height(with(density) { height.toDp() })
                .graphicsLayer { alpha = ghostAlpha }
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = backgroundAlpha))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = flight.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (useOnPrimaryText(backgroundAlpha)) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

/** El color del texto cambia cuando el fondo aparece gradualmente. Aquí hay un umbral de estado intermedio para una fácil observación. */
private const val TEXT_COLOR_FLIP_ALPHA = 0.5f

/** Cuando el fondo sea más de la mitad visible, utilice texto de color claro. */
internal fun useOnPrimaryText(backgroundAlpha: Float): Boolean = backgroundAlpha > TEXT_COLOR_FLIP_ALPHA

/**
 * Cuelga la capa fantasma en una ventana independiente y fija la posición al origen de la ventana.
 *
 * La razón para usar una ventana en lugar de una superposición normal: no está recortada por el área de contenido de Scaffold, ni es necesario superponerla para
 * Y transforme el diseño raíz de la pantalla; las coordenadas se unifican mediante límites de ventana, lo que es coherente con el método de medición de onGloballyPositioned.
 */
@Composable
internal fun SendFlightWindow(flight: SendFlight?, onFinished: () -> Unit) {
    if (flight == null) return
    val provider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset = IntOffset.Zero
        }
    }
    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = false),
    ) {
        SendFlightLayer(flight = flight, onFinished = onFinished)
    }
}
