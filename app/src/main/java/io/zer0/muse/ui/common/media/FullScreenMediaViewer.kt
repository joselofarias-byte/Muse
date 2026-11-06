package io.zer0.muse.ui.common.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.zer0.muse.R
import io.zer0.muse.ui.SmartImage
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseDialogWindowEffect
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch

/**
 * v1.0.15: Imágenes ampliables — Admite zoom con dos dedos, hacer doble clic para hacer zoom y arrastrar y desplazarse después de hacer zoom.
 *
 * Extraído de la implementación de MessageBubble.kt v1.60-B como componente compartido,Para la reutilización del visor multimedia en pantalla completa de chat individual y chat grupal.
 * - Rango de zoom 1x-5x(detectTransformGestures)
 * - Haga doble clic en 1x ↔ 2.5x Cambio suave entre(300ms Animatable Transición)
 * - scale/offset usar rememberSaveable,Mantener el estado de zoom después de girar la pantalla
 */
@Composable
internal fun ZoomableImage(model: Any?, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var scale by rememberSaveable { mutableStateOf(1f) }
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val doubleTapSpec = MuseMotion.tween<Float>(
        durationMillis = MuseAnimation.SLOW_MS,
        easing = FastOutSlowInEasing,
    )
    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    if (newScale > 1f) {
                        scale = newScale
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scope.launch {
                            val targetScale = if (scale > 1f) 1f else 2.5f
                            val animatable = Animatable(scale)
                            animatable.animateTo(
                                targetValue = targetScale,
                                animationSpec = doubleTapSpec,
                            ) {
                                scale = value
                            }
                            if (targetScale == 1f) {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                )
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SmartImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * v1.0.15: Visor multimedia en pantalla completa — HorizontalPager Cambiar izquierda y derecha + dos dedos/Haga doble clic para ampliar.
 *
 * Extraído de MessageBubble.kt v1.60-B como componente compartido,Unifique la interacción de visualización de imágenes del chat individual y del chat grupal.
 * - máscara colorScheme.scrim(0.95 alpha)
 * - arriba:Indicador de página(Cuando hay varias imágenes)+ botón cerrar
 * - dismissOnClickOutside = false,Evite cerrar accidentalmente el gesto de zoom
 *
 * @param images Lista de URL de imagen/URI de datos
 * @param initialIndex Índice de la página de visualización inicial
 * @param onDismiss Cerrar devolución de llamada
 * @param onSaveImage v1.0.80: Devolución de llamada del botón Descargar en la esquina inferior derecha(Pase en la imagen de la página actual. URI);null El botón no se muestra.
 *   La entrada de descarga se ha movido de la tarjeta de burbujas de mensaje a la vista previa de la imagen grande.,Evite los botones que bloquean la pantalla.
 */
@Composable
internal fun FullScreenMediaViewer(
    images: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onSaveImage: ((imageUri: String) -> Unit)? = null,
) {
    if (images.isEmpty()) return
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
    ) { images.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
        ),
    ) {
        MuseDialogWindowEffect(forceFullScreen = true)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.95f)),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(
                    model = images[page],
                    contentDescription = stringResource(R.string.chat_image_page_cd, page + 1, images.size),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // arriba:Botón cerrar + indicador de página
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.itemGap, vertical = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Marcador de posición a la izquierda(El ancho coincide con el objetivo táctil del botón de cierre),Centra el número de página y mueve el botón de cerrar hacia la derecha.
                Spacer(modifier = Modifier.width(MuseIconSizes.touchTarget))
                if (images.size > 1) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.labelVerticalGap),
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                ) {
                    Icon(
                        imageVector = MuseIcons.x,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // v1.0.80: Botón de descarga en la esquina inferior derecha(Solo se muestra si la devolución de llamada no es nula)
            if (onSaveImage != null) {
                val currentImage = images.getOrNull(pagerState.currentPage)
                if (currentImage != null) {
                    IconButton(
                        onClick = { onSaveImage(currentImage) },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(MusePaddings.itemGap)
                            .size(MuseIconSizes.touchTarget)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                shape = CircleShape,
                            ),
                    ) {
                        Icon(
                            imageVector = MuseIcons.download,
                            contentDescription = stringResource(R.string.chat_save_image_cd),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                }
            }
        }
    }
}
