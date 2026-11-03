package io.zer0.muse.miniphone.ui.chat

import android.content.ContentResolver
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import java.io.File

/**
 * Burbuja de imagen:Un diagrama de esquinas redondeadas,Longitud lateral máxima [ImageMaxEdge],Se muestra en proporción a la imagen original.
 *
 * Primero ocupe un trozo de color de fondo durante la carga.,No expanda la altura de la fila desde 0 antes de saltar;Fallo de ruta(El archivo fue eliminado,uri no puedo leer)Haz un dibujo roto,
 * En lugar de dejar un espacio en blanco que siempre rodeará —— "Aquí había una imagen" en el historial de chat está más cerca de la verdad que en blanco.
 *
 * @param onLongPress Mantenga presionado para que aparezca el menú de operación.
 */
@Composable
internal fun ChatImageBubble(message: ChatMessageUi, maxWidth: Dp, onOpen: (String) -> Unit, onLongPress: () -> Unit = {}) {
    val colors = MiniPhoneTheme.colors
    val density = LocalDensity.current
    val path = message.mediaUri.orEmpty()
    // La ruta local ya no existe.:Haz el dibujo directamente,No es necesario hacer que el cargador funcione en vano
    val missing = path.isEmpty() || isMissingLocalFile(path)
    var loadFailed by remember(path) { mutableStateOf(false) }
    var natural by remember(path) { mutableStateOf(IntSize.Zero) }
    val broken = missing || loadFailed
    val maxEdge = minOf(maxWidth, ImageMaxEdge)
    val size = if (natural == IntSize.Zero) PlaceholderSize else fitInside(natural, maxEdge, density)
    Box(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.image(message.id))
            .clip(ImageShape)
            .background(colors.divider)
            .size(size)
            // ¿Puedo hacer clic en él para ver si la ruta está ahí?:No decodificar la miniatura no significa que no se pueda abrir la imagen original.
            .combinedClickable(
                onClick = { if (!missing) onOpen(path) },
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (broken) {
            BrokenImageContent(messageId = message.id)
        } else {
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { state -> natural = state.result.drawable.intrinsicSize() },
                onError = { loadFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Ver imágenes en pantalla completa:Fondo negro, centra la imagen, pellizca para hacer zoom, haz clic en cualquier lugar para cerrar.
 *
 * Zoom solo a proporciones iguales:Ya no hace zoom fuera de la imagen base después de hacer zoom,Alejar a 1x y volver al centro —— dejado atrás por un arrastre
 * El desplazamiento no sacará la imagen de la pantalla después de alejarla.
 */
@Composable
internal fun MiniPhoneImageViewer(uri: String, onDismiss: () -> Unit) {
    var scale by remember(uri) { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(MiniPhoneChatTags.IMAGE_VIEWER)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    offset = if (scale <= MIN_ZOOM) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/**
 * Visor de galería:Mira el mismo conjunto de imágenes a la vez.,Deslice hacia la izquierda o hacia la derecha para cambiar,Cubra con "3 / 9”.
 *
 * [initialIndex] es un subíndice de base cero:Cualquier imagen en la que haga clic comenzará desde esa imagen. Los gestos son los mismos que los del visor de imágenes únicas. -
 * Haga clic en cualquier lugar para cerrar;Una diapositiva más para cerrar. —— El gesto horizontal devuelve el paso de página.,Vertical izquierda para salida,No compitan entre sí.
 * El contenedor de paso de página maneja el deslizamiento hacia la izquierda y hacia la derecha por sí solo.,Por lo tanto, aquí no se realiza el arrastre horizontal.
 */
@Composable
internal fun MiniPhoneGalleryViewer(uris: List<String>, initialIndex: Int, onDismiss: () -> Unit) {
    if (uris.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, uris.lastIndex)) { uris.size }
    var draggedDown by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(MiniPhoneChatTags.IMAGE_GALLERY)
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { draggedDown = 0f },
                    onDragEnd = {
                        if (draggedDown > GALLERY_DISMISS_DRAG_PX) onDismiss()
                        draggedDown = 0f
                    },
                    onDragCancel = { draggedDown = 0f },
                ) { _, dragAmount ->
                    draggedDown += dragAmount
                }
            },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = uris[page],
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = galleryCounterOf(pagerState.currentPage, uris.size),
            style = MiniPhoneTheme.typography.title.copy(color = Color.White),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = MiniPhoneTheme.dimens.statusBarHeight + GalleryCounterGap)
                .testTag(MiniPhoneChatTags.IMAGE_GALLERY_COUNTER),
        )
    }
}

/** contar texto:¿Cuál es el panorama actual?(una base)con el número total. */
internal fun galleryCounterOf(pageIndex: Int, pageCount: Int): String = "${pageIndex + 1} / $pageCount"

/** imagen rota:Ícono autodibujado + descripción de una oración,Mismo tamaño que el marco exterior.,La lista no terminará por eso. */
@Composable
private fun BrokenImageContent(messageId: String) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .testTag(MiniPhoneChatTags.imageBroken(messageId))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = MiniPhoneIcons.BrokenImage,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(BrokenIconSize),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = BROKEN_IMAGE_TEXT,
            style = typography.timestamp.copy(color = colors.textSecondary),
            textAlign = TextAlign.Center,
        )
    }
}

/** Ruta local pero el archivo ya no está ahí;`content://` Este tipo de uri proporcionado por el exterior se deja a criterio del cargador. */
private fun isMissingLocalFile(path: String): Boolean {
    val parsed = Uri.parse(path)
    val local = parsed.scheme == null || parsed.scheme == ContentResolver.SCHEME_FILE
    return local && !File(parsed.path ?: path).exists()
}

/** Sangrar proporcionalmente a la imagen original [maxEdge] caja;Las imágenes pequeñas no se amplían.,Llenar el cuadro lo hará borroso. */
private fun fitInside(natural: IntSize, maxEdge: Dp, density: Density): DpSize = with(density) {
    val width = natural.width.toDp()
    val height = natural.height.toDp()
    val ratio = minOf(1f, maxEdge / maxOf(width, height))
    DpSize(width * ratio, height * ratio)
}

private fun Drawable?.intrinsicSize(): IntSize {
    val width = this?.intrinsicWidth ?: 0
    val height = this?.intrinsicHeight ?: 0
    return if (width > 0 && height > 0) IntSize(width, height) else IntSize.Zero
}

private val ImageShape = RoundedCornerShape(8.dp)
private val ImageMaxEdge = 200.dp
private val PlaceholderSize = DpSize(132.dp, 132.dp)
private val BrokenIconSize = 24.dp

/** Cuente el espacio que queda más allá del borde superior de la pantalla. */
private val GalleryCounterGap = 8.dp

/** Si se desliza más allá de este desplazamiento, se considerará que se \"apaga\".:Deslizar el dedo no se confundirá con salir. */
private const val GALLERY_DISMISS_DRAG_PX = 120f

private const val MIN_ZOOM: Float = 1f
private const val MAX_ZOOM: Float = 5f
private const val BROKEN_IMAGE_TEXT: String = "La imagen no se puede mostrar"
