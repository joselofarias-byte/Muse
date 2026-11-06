@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import kotlin.math.roundToInt

// E4 (Especialización front-end H8): Capa elástica de punto de anclaje universal(Popover) — Los límites del componente de anclaje dentro de la ventana.
// (boundsInWindow)Contenido emergente base,El borde derecho está unido al borde derecho del punto de anclaje y el borde superior está gapDp por encima del punto de anclaje.
// Haga clic fuera de la capa elástica para activar automáticamente onDismiss(Popup comportamiento incorporado).
// Propósito:Tarjeta de capa flotante anclada(Menú de operación de mensajes, etc.);Compatible con dispositivos celulares y de escritorio.
@Composable
internal fun MusePopover(
    anchorBounds: Rect,
    gapDp: Int,
    onDismiss: () -> Unit,
    anchorPointInWindow: Offset? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { gapDp.dp.toPx().toInt() }
    val positionProvider = remember(anchorBounds, anchorPointInWindow, gapPx) {
        MusePopoverPositionProvider(
            fallbackAnchorBounds = anchorBounds,
            anchorPointInWindow = anchorPointInWindow,
            gapPx = gapPx,
        )
    }
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
    ) {
        // v2.x: Animación de entrada — fade + ligero zoom(Repare todas las personas que llaman Popover en un solo lugar);
        // Renuncia y no hagas nada.(La capa elástica se elimina directamente.,Nadie puede ver la animación de salida.,No vale la pena obsesionarse dismiss).
        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS)) +
                scaleIn(
                    initialScale = 0.94f,
                    animationSpec = MuseMotion.tween(MuseAnimation.TACTILE_MS),
                ),
            exit = ExitTransition.None,
        ) {
            content()
        }
    }
}

/**
 * Colocar capas elásticas según los puntos de presión.
 *
 * Cuando existe un punto de presión,La capa elástica está centrada horizontalmente en el dedo.,Se muestra primero encima del dedo.;Colóquelo debajo cuando no haya suficiente espacio arriba.
 * Mantenga la antigua burbuja en la posición superior derecha cuando no haya ningún punto de presión.,para rotación/Escenarios sin contexto de gestos como recuperación de procesos.
 */
internal class MusePopoverPositionProvider(
    private val fallbackAnchorBounds: Rect,
    private val anchorPointInWindow: Offset?,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val safeGap = gapPx.coerceAtLeast(0)
        val minX = safeGap
        val maxX = (windowSize.width - popupContentSize.width - safeGap).coerceAtLeast(minX)
        val minY = safeGap
        val maxY = (windowSize.height - popupContentSize.height - safeGap).coerceAtLeast(minY)

        val pressPoint = anchorPointInWindow
        if (pressPoint != null) {
            val x = (pressPoint.x - popupContentSize.width / 2f)
                .roundToInt()
                .coerceIn(minX, maxX)
            val spaceAbove = pressPoint.y - safeGap
            val spaceBelow = windowSize.height - pressPoint.y - safeGap
            val y = if (spaceAbove >= popupContentSize.height || spaceAbove >= spaceBelow) {
                pressPoint.y - popupContentSize.height - safeGap
            } else {
                pressPoint.y + safeGap
            }
                .roundToInt()
                .coerceIn(minY, maxY)
            return IntOffset(x, y)
        }

        val fallbackX = if (layoutDirection == LayoutDirection.Ltr) {
            fallbackAnchorBounds.right - popupContentSize.width
        } else {
            fallbackAnchorBounds.left
        }
        val fallbackY = fallbackAnchorBounds.top - popupContentSize.height - safeGap
        // v1.0.90: Punto de anclaje aún no medido(bounds para 0)Cuando la vieja lógica calcula un número negativo y luego lo fuerza a la esquina superior izquierda,
        // El menú aparecerá inexplicablemente en la parte superior izquierda de la pantalla, cubriendo el texto. Es mejor colocarlo en la esquina inferior derecha que fuera del área de lectura.
        if (fallbackAnchorBounds.width <= 0f && fallbackAnchorBounds.height <= 0f) {
            return IntOffset(maxX, maxY)
        }
        return IntOffset(
            fallbackX.roundToInt().coerceIn(minX, maxX),
            fallbackY.roundToInt().coerceIn(minY, maxY),
        )
    }
}
