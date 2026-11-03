@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * menú de anclaje — sustituto Material3 `DropdownMenu`.
 *
 * con `DropdownMenu` La diferencia: el caparazón toma la ficha del artículo (esquinas redondeadas/color de fondo/sombra), las entradas las pasa la persona que llama
 * [io.zer0.muse.ui.common.surface.MuseListItem], por lo que todo el menú es visualmente coherente con el resto de la página.
 *
 * Mecanismo de posicionamiento y `DropdownMenu` Igual que: leer**contenedor de anclaje**(los límites del elemento componible donde se encuentra el punto de llamada),
 * De forma predeterminada, se expande hacia abajo y alinea el punto de anclaje a la izquierda; lo gira hacia arriba cuando no hay suficiente espacio debajo; converge hacia la ventana cuando cruza el límite horizontal.
 * Por lo tanto, el método de llamada es el mismo que `DropdownMenu` consistente —— Simplemente colóquelo en el mismo contenedor que el control del gatillo.
 *
 * Uso:
 * ```
 * Box {
 *     MuseTactileButton(icon = ..., onClick = { expanded = true }, contentDescription = "Más")
 *     MuseAnchoredMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
 *         MuseListItem(onClick = { ... }) { Text("Cambiar nombre") }
 *     }
 * }
 * ```
 *
 * @param expanded Ya sea para expandirse
 * @param onDismissRequest Se llama cuando se hace clic en la tecla externa o de retorno
 * @param modifier modificador
 * @param gap Espaciado entre menú y ancla
 * @param alignEnd true alinear el punto de anclaje a la derecha ("Más"el control de activación de clase es más natural), alineado a la izquierda de forma predeterminada
 * @param minWidth Ancho mínimo del menú
 * @param maxWidth ancho máximo del menú
 * @param maxHeight Altura máxima del menú (más allá del desplazamiento)
 * @param focusable Ya sea para aprovechar el foco de la ventana. Utilice verdadero para menús operativos (haga clic afuera para cerrar);
 *   Se debe utilizar el autocompletado del cuadro de entrada (como @mención) false —— Robar el foco quitará el foco del teclado y del cuadro de entrada.
 *   Se muestra como“Tan pronto como escribo @, el teclado desaparece y no se puede mostrar la finalización.”.
 * @param content contenido del menú (normalmente una columna [io.zer0.muse.ui.common.surface.MuseListItem])
 */
@Composable
fun MuseAnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = MusePaddings.contentGap,
    alignEnd: Boolean = false,
    minWidth: Dp = 160.dp,
    maxWidth: Dp = 240.dp,
    maxHeight: Dp = 288.dp,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    val properties = remember(focusable) { PopupProperties(focusable = focusable) }
    val positionProvider = remember(gapPx, alignEnd) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val rawX = if (alignEnd) {
                    anchorBounds.right - popupContentSize.width
                } else {
                    anchorBounds.left
                }
                val x = rawX.coerceIn(0, maxX)
                val below = anchorBounds.bottom + gapPx
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (anchorBounds.top - gapPx - popupContentSize.height).coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = minWidth, max = maxWidth)
                .heightIn(max = maxHeight),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = MuseElevation.none,
            shadowElevation = MuseElevation.modal,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                io.zer0.muse.ui.common.surface.LocalMuseCompactList provides true,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = MusePaddings.tightGap),
                    content = content,
                )
            }
        }
    }
}
