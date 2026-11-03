package io.zer0.muse.miniphone.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.data.moment.MOMENT_MAX_IMAGES
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Un área de imagen dinámica.
 *
 * El número de columnas está dado por [momentGridColumns] determinado:Una tarjeta es exclusiva, dos y cuatro están en dos columnas y las tres restantes están en tres columnas.
 * Corta cada cuadrícula en cuadrados.(Corte proporcional),Cuando una fila no está llena, las posiciones restantes quedan vacías. —— La imagen no se estirará solo porque haya pocas imágenes.
 *
 * @param maxWidth ancho disponible,por la persona que llama desde `BoxWithConstraints` medir
 * @param onOpen Devolución de llamada cuando se hace clic en una imagen,El parámetro es su índice en el grupo.(base cero) —— El visor de la galería comienza con este.
 */
@Composable
internal fun MomentImageGrid(momentId: String, images: List<String>, maxWidth: Dp, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shown = remember(images) { images.take(MOMENT_MAX_IMAGES) }
    if (shown.isEmpty()) return
    if (shown.size == 1) {
        MomentImageCell(
            path = shown[0],
            size = minOf(maxWidth, SingleImageMaxEdge),
            tag = MiniPhoneMomentsTags.image(momentId, 0),
            onOpen = { onOpen(0) },
            modifier = modifier,
        )
        return
    }
    val columns = momentGridColumns(shown.size)
    val cell = ((maxWidth - CellGap * (columns - 1)) / columns).coerceAtMost(CellMaxEdge)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CellGap)) {
        shown.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CellGap)) {
                row.forEachIndexed { columnIndex, path ->
                    val index = rowIndex * columns + columnIndex
                    MomentImageCell(
                        path = path,
                        size = cell,
                        tag = MiniPhoneMomentsTags.image(momentId, index),
                        onOpen = { onOpen(index) },
                    )
                }
            }
        }
    }
}

/**
 * Número de cuadrícula de nueve palacios.
 *
 * Dar una imagen grande uno por uno,Dos y cuatro cartas están dispuestas en dos columnas.(Cuatro son perfectos 2×2),Las tres columnas restantes ——
 * Tres cartas seguidas, de cinco a nueve cartas que llenan la cuadrícula de nueve cuadrados.,La relación entre el número de hojas y el espacio en blanco es la más intuitiva.
 */
internal fun momentGridColumns(count: Int): Int = when {
    count <= SINGLE_COLUMN_MAX -> 1
    count == TWO_COLUMN_EXACT || count == TWO_COLUMN_SQUARE -> 2
    else -> 3
}

/** foto de un marco:cuadrado fijo,Durante la carga, presione primero un color de fondo,No permita que la altura de la fila se aleje de 0. */
@Composable
private fun MomentImageCell(path: String, size: Dp, tag: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MiniPhoneTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(ImageShape)
            .background(colors.divider)
            .clickable(onClick = onOpen)
            .testTag(tag),
    ) {
        AsyncImage(
            model = path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size),
        )
    }
}

/** El límite superior de la longitud lateral de una imagen.:No importa qué tan grande sea, el texto desaparecerá de la pantalla. */
private val SingleImageMaxEdge = 220.dp

/** El límite superior de la longitud lateral de una cuadrícula cuando hay varias imágenes. */
private val CellMaxEdge = 96.dp

private val CellGap = 4.dp
private val ImageShape = RoundedCornerShape(6.dp)

private const val SINGLE_COLUMN_MAX: Int = 1
private const val TWO_COLUMN_EXACT: Int = 2
private const val TWO_COLUMN_SQUARE: Int = 4
