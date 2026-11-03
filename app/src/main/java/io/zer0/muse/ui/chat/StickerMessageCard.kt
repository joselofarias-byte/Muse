package io.zer0.muse.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import io.zer0.common.resultOf
import io.zer0.muse.data.sticker.StickerItem
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.ui.common.media.FullScreenMediaViewer
import io.zer0.muse.ui.markdown.CardAction
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject

/**
 * v2.x: Tarjetas de emoticonos en el flujo de noticias. —— Sólo renderiza la imagen en sí.(Sin burbujas de aire/Sin texto/Sin fronteras).
 *
 * Puntos de diseño:
 *  - Análisis de tolerancia a fallos de clasificación.:El modelo se degradará y se ignorará si se escribe un nombre de clasificación incorrecto.(No se renderiza toda la tarjeta.,No hay espacio ocupado y no se reporta ningún error)
 *  - Semilla aleatoria:mismo [seed] Elige el mismo consistentemente(Reingresar a la sesión/La reorganización no salta)
 *  - Haga clic para ver en pantalla completa(Reutilizar [FullScreenMediaViewer],Misma interacción que la vista previa de la página de configuración)
 *  - GIF(GIF/WebP)Reproducido automáticamente por el GifDecoder del Coil ImageLoader global
 */
@Composable
internal fun StickerMessageCard(category: String, seed: Long, modifier: Modifier = Modifier) {
    val repo: StickerLibraryRepository = koinInject()
    // Clasificación de análisis tolerante a fallos + selección de semillas;La categoría no existe/esta vacio → item para null → no renderizar
    val item by
        produceState<StickerItem?>(initialValue = null, category, seed) {
            value =
                resultOf {
                    val resolved = repo.resolveCategory(category)
                    if (resolved == null) {
                        null
                    } else {
                        repo.pickSticker(repo.snapshot(), resolved, seed)
                    }
                }.getOrNull()
        }
    val sticker = item ?: return
    val file =
        remember(sticker) {
            repo.getStickerFileByPath(sticker.relativePath).takeIf { it.exists() }
        } ?: return

    var previewing by remember { mutableStateOf(false) }
    val painter = rememberAsyncImagePainter(model = file)
    // v2.2.0: Reparación "Size is unspecified" colapsar — El comentario anterior supone que cuando no se especifica, el ancho y el alto son NaN,
    // Sin embargo, algunas versiones Compose del descriptor de acceso Size.width lanzan directamente IllegalStateException para No especificado.
    // Utilice runCatching + isSpecified para doble protección: Cualquier excepción de reserva 1f(marcador de posición cuadrado), El renderizado ya no se interrumpe.
    val ratio = runCatching {
        val size = painter.intrinsicSize
        if (size.isSpecified && size.width > 0f && size.height > 0f) {
            size.width / size.height
        } else {
            1f
        }
    }.getOrDefault(1f)
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
        modifier
            .widthIn(max = 160.dp)
            .heightIn(max = 200.dp)
            .aspectRatio(ratio.coerceIn(0.4f, 2.5f))
            .clip(MuseShapes.small)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { previewing = true },
    )
    if (previewing) {
        FullScreenMediaViewer(
            images = listOf("file://${file.absolutePath}"),
            initialIndex = 0,
            onDismiss = { previewing = false },
        )
    }
}

/**
 * v2.x: Texto de rebajas con emoticones.
 *
 * Presiona el texto `[[sticker:Nombre de categoría]]` cortar en"Segmento de texto + segmento de emoticones"Renderizado pieza por pieza;
 * Paso cuando no está marcado [MarkdownText](El comportamiento es exactamente el mismo que la versión anterior.,Cero gastos generales adicionales);
 * La marca final sin cerrar viene dada por [StickerMarkup.split] tragar,Ninguna mitad del texto quedará expuesta durante la transmisión.
 */
@Composable
internal fun StickerAwareMarkdownBody(
    text: String,
    stickerSeed: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onBackground,
    citationUrls: List<String> = emptyList(),
    isStreaming: Boolean = false,
    disableLinks: Boolean = false,
    onLongPressOutside: (() -> Unit)? = null,
    onHtmlPreview: (String) -> Unit = {},
    onCardAction: ((CardAction) -> Unit)? = null,
    /** v2.6.13: Identidad de la tarjeta (identificación del mensaje en la escena del chat); debe pasarse a cada segmento de MarkdownText. */
    cardId: String? = null,
) {
    val segments = remember(text) { StickerMarkup.split(text) }
    val single = segments.singleOrNull()
    if (single is StickerMarkup.Segment.Text) {
        MarkdownText(
            text = single.text,
            modifier = modifier,
            style = style,
            color = color,
            citationUrls = citationUrls,
            isStreaming = isStreaming,
            disableLinks = disableLinks,
            onLongPressOutside = onLongPressOutside,
            onHtmlPreview = onHtmlPreview,
            onCardAction = onCardAction,
            cardId = cardId,
        )
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        segments.forEachIndexed { idx, seg ->
            when (seg) {
                is StickerMarkup.Segment.Text ->
                    MarkdownText(
                        text = seg.text,
                        modifier = Modifier.fillMaxWidth(),
                        style = style,
                        color = color,
                        citationUrls = citationUrls,
                        isStreaming = isStreaming,
                        disableLinks = disableLinks,
                        onLongPressOutside = onLongPressOutside,
                        onHtmlPreview = onHtmlPreview,
                        onCardAction = onCardAction,
                        cardId = cardId,
                    )
                is StickerMarkup.Segment.Sticker ->
                    StickerMessageCard(
                        category = seg.category,
                        seed = stickerSeed + idx,
                    )
            }
        }
    }
}
