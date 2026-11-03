@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError", "UnusedPrivateProperty")

package io.zer0.muse.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.perf.DataUriImageCache
import io.zer0.muse.ui.common.animation.MuseInkGlyph
import io.zer0.muse.ui.common.animation.MuseThinkingGlyphSize
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.tiny
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** data-URI Prefijo de imagen:Sólo este tipo de imagen debe ser decodificada antes de poder conocer las proporciones. */
private const val DATA_URI_IMAGE_PREFIX = "data:image/"

/**
 * marco de tarjeta de trabajo(4:3).
 *
 * data-URI Primero debes decodificarlo para conocer las verdaderas proporciones.,Y el diseño no puede esperar a ser decodificado.:La altura después del marco fijo ocupado es desde el primer marco.
 * altura final,No se realizarán cambios una vez completada la decodificación.,Utilice ContentScale.Fit para dejar el contenido en blanco.
 */
private const val GENERATED_IMAGE_ASPECT = 4f / 3f

/**
 * Si la tarjeta de trabajo ocupa previamente el marco:data-URI Regresar 4:3,Otros(URL / ruta del archivo)Devuelve nulo para indicar que no hay preferencia.
 *
 * URL / La proporción de rutas de archivos que nadie conoce de antemano(Coil También es necesario cargarlo antes de que el tamaño esté disponible.),Bloquear a 4:3 hará que la imagen sea horizontal./
 * Hay una gran cantidad de espacio en blanco para imágenes verticales largas.,Peor que antes, el cambio "crece hasta el límite superior de 420 dp en proporción a sí mismo";sigue asi con este tipo de imagenes
 * Intervalo de altura original de 120..420 dp,Ampliar al tamaño real. Por el contrario, el URI de datos no puede utilizar rangos.——su decodificacion
 * Se completa de forma asincrónica en el hilo IO.,El intervalo hará que la altura salte una vez antes y después de la decodificación.
 */
internal fun generatedImageAspectOf(imageUri: String): Float? =
    if (imageUri.startsWith(DATA_URI_IMAGE_PREFIX)) GENERATED_IMAGE_ASPECT else null

/**
 * Tarjeta con imagen generada — Esquinas redondeadas + clic para obtener una vista previa.
 *
 * v1.0.80:
 *  - Ampliar pantalla:ContentScale.Fit Mostrar completo,No más truncamiento de cultivos de parcelas 1:1,El límite superior de altura se relaja para 420dp
 *  - Quitar el botón de descarga externa:La entrada de descarga está incluida en la página de vista previa de la imagen grande.(esquina inferior derecha),Evitar que los botones de la tarjeta bloqueen la pantalla
 */
/**
 * v2.0.1: tarjeta de trabajo — Imagen de trabajo grande con esquinas redondeadas + barra de trabajo (resumen de palabras rápidas/guardar/compartir).
 * Haga clic en la imagen para ver una imagen más grande; la entrada de operaciones está justo debajo de la imagen y ya no está oculta en la página de vista previa.
 */
@Composable
internal fun GeneratedImageCard(
    imageUri: String,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    /** v2.0.1: Resumen de palabras rápidas (lado izquierdo de la barra de trabajo;null solo funciona en el lado derecho) */
    promptSummary: String? = null,
    /** v2.0.1: compartir devolución de llamada (null No muestre el botón de compartir cuando utilice recursos no locales; pasar nulo cuando se utilizan recursos no locales) */
    onShare: (() -> Unit)? = null,
    /** Las cuadrículas de varias imágenes se recortan 1:1; Los URI de datos de una sola imagen están enmarcados con 4:3 y el resto tiene sus propias proporciones (120..420dp). */
    compact: Boolean = false,
) {
    // estrategia de marco:data-URI preferencia 4:3(Altamente consistente antes y después de la decodificación.);URL / Mantenga la ruta del archivo como está
    // heightIn(120..420). Originalmente ambos tomaban intervalos,data-URI Sólo 120 dp antes de decodificar, salte a
    // Proporción real,El animateContentSize de MessageBubble superpuesto durante el desplazamiento es una serie de retransmisiones.
    val preAllocatedAspect = generatedImageAspectOf(imageUri)
    Column(modifier = modifier.fillMaxWidth()) {
        // Trabajo: Imagen grande de esquina redonda (haga clic para ampliar la imagen)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MusePaddings.tightGap)
                .then(if (compact) Modifier.aspectRatio(1f) else Modifier)
                .clip(MuseShapes.largeCard)
                .clickable(onClick = onPreview),
        ) {
            SmartImage(
                model = imageUri,
                contentDescription = stringResource(R.string.chat_generated_image_cd),
                contentScale = if (compact) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier
                    .then(
                        when {
                            compact -> Modifier.fillMaxSize()
                            preAllocatedAspect != null -> Modifier.fillMaxWidth().aspectRatio(preAllocatedAspect)
                            else -> Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 420.dp)
                        },
                    )
                    .clip(MuseShapes.largeCard),
            )
        }
        // Barra de trabajo: resumen de palabras rápidas + guardar/compartir (simplemente opere, sin cubrir la pantalla)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            if (!promptSummary.isNullOrBlank()) {
                Text(
                    text = promptSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            MuseTactileButton(
                icon = MuseIcons.download,
                onClick = onSave,
                contentDescription = stringResource(R.string.chat_save_image_cd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconSmall,
            )
            if (onShare != null) {
                MuseTactileButton(
                    icon = MuseIcons.share,
                    onClick = onShare,
                    contentDescription = stringResource(R.string.action_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmall,
                )
            }
        }
    }
}

/**
 * Elemento de la fila del panel de acción inferior — ancho completo Row(icono + texto),Haga clic para activar la devolución de llamada.
 */
@Composable
internal fun ActionMenuItem(
    icon: ImageVector,
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
    // v1.48: Opcional tint,usado para"Eliminar mensaje"Espere a que las operaciones peligrosas se marquen en rojo
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val iconTint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    val textTint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
    Surface(
        onClick = onClick,
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.iconPadding, vertical = MusePaddings.inputPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.iconPadding),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = iconTint,
                // M-MB3: Los tamaños de los iconos se reemplazan con codificación mediante el token MuseIconSizes.iconMedium 22dp
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = textTint,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Tarea 2A: pantalla de esqueleto brillante + animación de carga del punto de pulso.
 * Los tres puntos escalan en secuencia./Aparece y desaparece,El texto de estado aparece a continuación.
 */
@Composable
internal fun LoadingDots(text: String = stringResource(R.string.chat_loading_thinking)) {
    val reducedMotion = MuseMotion.isReducedMotion()
    Column(
        modifier = Modifier.padding(MusePaddings.cardInner),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v1.79 (L-B15): Tres puntos comparten uno InfiniteTransition,Reducir la sobrecarga de animación.
            // reduced-motion La animación infinita no comienza.,Evite un bucle infinito de 0 ms que ocupe el hilo principal.
            val infiniteTransition = if (reducedMotion) null else rememberInfiniteTransition(label = "dots")
            repeat(3) { index ->
                val scale by if (infiniteTransition == null) {
                    androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
                } else {
                    infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = MuseMotion.tween(
                                MuseAnimation.LOOP_NORMAL_MS,
                                delayMillis = minOf(index, MuseAnimation.STAGGER_MAX_INDEX) * MuseAnimation.STAGGER_STEP_MS,
                                easing = FastOutSlowInEasing,
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot$index",
                    )
                }
                val alpha by if (infiniteTransition == null) {
                    androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
                } else {
                    infiniteTransition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = MuseMotion.tween(
                                MuseAnimation.LOOP_NORMAL_MS,
                                delayMillis = minOf(index, MuseAnimation.STAGGER_MAX_INDEX) * MuseAnimation.STAGGER_STEP_MS,
                                easing = FastOutSlowInEasing,
                            ),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dotAlpha$index",
                    )
                }
                Box(
                    modifier = Modifier
                        .size(MusePaddings.contentGap)
                        .scale(scale)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * v0.48: shimmer Marcador de posición de pantalla esqueleto — Barra de marcador de posición de representación de burbujas de mensajes de asistente de flujo vacío,
 * Reemplaza los antiguos LoadingDots"Pensando"texto,crear"AI escribiendo"La sensación de respirar.
 *
 * darse cuenta:uno fillMaxWidth(0.6f) / height 14dp rectángulo redondeado,
 * usar [Brush.linearGradient] cooperar [animateFloat] + [infiniteRepeatable]
 * Haz una animación de barrido de izquierda a derecha.(1800ms un ciclo,LinearEasing).
 * color:surfaceVariant(0.4) → primary(0.2) → surfaceVariant(0.4).
 */
@Composable
internal fun ShimmerPlaceholder(modifier: Modifier = Modifier) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val brush = if (reducedMotion) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        )
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnim by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(durationMillis = MuseAnimation.LOOP_EXTRA_SLOW_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmerTranslate",
        )
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
            start = Offset(translateAnim * -300f, 0f),
            end = Offset(translateAnim * 300f, 0f),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth(0.6f)
            .height(14.dp)
            .clip(MuseShapes.tiny)
            .background(brush),
    )
}

/**
 * v1.33: Renderizador de imágenes inteligente
 * Omitir la resolución de URI de datos de Coil(DataUriFetcher falla silenciosamente en algunos dispositivos)
 * data URI → Decodificar directamente usando BitmapFactory;ordinario URL/Uri → Todavía caminando Coil AsyncImage
 */
@Composable
fun SmartImage(
    model: Any?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    if (model is String && model.startsWith(DATA_URI_IMAGE_PREFIX)) {
        // data URI:Extraer parte base64,IO Decodificación de hilos
        val base64Part = remember(model) {
            val commaIndex = model.indexOf(',')
            if (commaIndex > 0) model.substring(commaIndex + 1) else null
        }
        if (base64Part != null) {
            // Caché a nivel de proceso:Cuando se golpea, el primer cuadro es un mapa de bits.(No aparecen más bloques grises en un cuadro),No es necesario volver a solucionarlo.
            // La caché sobrevive en todas las composiciones. —— Deslizarse fuera de la pantalla y volver a entrar no la desbloqueará nuevamente.
            val cacheKey = remember(model) { DataUriImageCache.keyOf(model) }
            val cachedBitmap = remember(cacheKey) { DataUriImageCache.get(cacheKey) }
            val bitmapState by produceState<android.graphics.Bitmap?>(initialValue = cachedBitmap, base64Part) {
                if (value == null) {
                    value = withContext(Dispatchers.IO) {
                        DataUriImageCache.decodeOrGet(cacheKey) { decodeDataUriBitmap(base64Part) }
                    }
                }
                // No más aquí awaitDispose { value?.recycle() }:El mapa de bits ahora está representado por [DataUriImageCache]
                // Mantener y reutilizar,Reciclar después de deslizarse fuera de la pantalla hará que el siguiente golpe haga que el mapa de bits reciclado falle directamente.
                // El reciclaje llega al límite de bytes LRU + GC.
            }
            val current = bitmapState
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = modifier,
                    contentScale = contentScale,
                )
            } else {
                // Decodificación/falló:Marcador de posición gris
                Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            }
        } else {
            Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
        }
    } else {
        // URL/Uri normal: Ir Coil AsyncImage
        // v1.0.74 fix: Si la carga falla y no hay ningún marcador de posición, todo el bloque estará en blanco.,Rellenar marcador de posición/bloque gris de error
        val imageModel = remember(model) {
            if (model is String && model.startsWith("/")) File(model) else model
        }
        AsyncImage(
            model = imageModel,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            ),
            error = androidx.compose.ui.graphics.painter.ColorPainter(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        )
    }
}

/**
 * data-URI El segmento base64 → Bitmap.
 *
 * de [SmartImage] Sácalo:La decodificación en sí no tiene nada que ver con el ciclo de vida de la composición.(Se ingresa el resultado[DataUriImageCache]),
 * Ponerlo afuera también deja a SmartImage solo con la rama de renderizado.
 */
private fun decodeDataUriBitmap(base64Part: String): android.graphics.Bitmap? = runCatching {
    // B-02: Parte de la salida ascendente está envuelta en 76 caracteres. base64,NO_WRAP La decodificación falla directamente cuando encuentra una nueva línea
    // (Mostrar bloques grises);Elimina las nuevas líneas antes de decodificar,Compatible con ambos formatos.
    val normalized = base64Part.replace("\n", "").replace("\r", "")
    val bytes = android.util.Base64.decode(normalized, android.util.Base64.NO_WRAP)
    // v1.79 (H-B1): Detectar el tamaño primero y luego reducir la muestra,Evite decodificar imágenes grandes OOM
    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val targetSize = 1024
    var sampleSize = 1
    while (options.outWidth / sampleSize > targetSize || options.outHeight / sampleSize > targetSize) {
        sampleSize *= 2
    }
    val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
}.onFailure { Logger.w("MessageBubble", "base64 image decode failed: ${it.message}", it) }.getOrNull()

/**
 * Reparación de auditoría (S-02): El vídeo del mensaje del asistente genera una tarjeta de resultados.
 *
 * generate_video Después del éxito, videoFileUri escribe el mensaje y lo coloca en la biblioteca.,Renderizado aquí:
 * Marcador de posición oscuro + icono de reproducción,Haga clic para iniciar el reproductor del sistema con ACTION_VIEW;
 * Revisión de corrección (2.0 C-01): ACTION_VIEW Básicamente no disponible para datos: vídeos URI(La mayoría de los reproductores del sistema.
 * No reconocido data: scheme),Cuando haga clic en él, primero decodifique el URI de datos y guárdelo en cacheDir antes de reproducir el archivo.,
 * http(s) URL Todavía inicia directamente el reproductor del sistema.;Aviso del sistema cuando no hay aplicaciones para procesar.
 */
@Composable
internal fun AssistantVideoCard(videoUri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                runCatching {
                    // C-01: data URI → Reproduzca el archivo después de decodificar cacheDir.;http(s) juego directo
                    val playable = if (videoUri.startsWith("data:")) {
                        val commaIndex = videoUri.indexOf(',')
                        val base64 = if (commaIndex > 0) videoUri.substring(commaIndex + 1) else videoUri
                        val normalized = base64.replace("\n", "").replace("\r", "")
                        val bytes = android.util.Base64.decode(normalized, android.util.Base64.NO_WRAP)
                        val file = java.io.File(context.cacheDir, "muse_video_${videoUri.hashCode()}.mp4")
                        file.writeBytes(bytes)
                        file.toURI().toString()
                    } else {
                        videoUri
                    }
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(playable), "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }.onFailure { e ->
                    MuseToast.show(context.getString(R.string.chat_video_open_failed, e.message ?: ""))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MuseIcons.video,
            contentDescription = stringResource(R.string.chat_generated_video_cd),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(MuseIconSizes.iconEmpty),
        )
        // icono de reproducción central(scrim El fondo semitransparente mejora el contraste.,Consistente con los archivos adjuntos de videos del usuario.)
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MuseIcons.play,
                contentDescription = stringResource(R.string.chat_video_play_cd),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(MuseIconSizes.iconLarge),
            )
        }
    }
}

/**
 * Etapa 4: Cursor de transmisión — Una barra vertical parpadeante adjunta al final del texto del mensaje de transmisión de IA.
 *
 * diseño: 2.5dp Ancho/barra vertical 18dp alto,Pase [rememberInfiniteTransition] + [animateFloat]
 *       en 1.0 ↔ 0.2 FastOutSlowInEasing ida y vuelta(530ms ciclo),presente"respiración de máquina de escribir".
 * color: Tomado de MaterialTheme.colorScheme.primary(verde laurel #2D8C5F),
 *       Sea coherente con los colores de la marca.,conformarse a"lámpara de mesa nocturna"La regla de hierro de la combinación de colores(<5% Adorno de color de la marca.).
 *
 * v1.0.3 mejorar:
 *  - El tiempo del ciclo se reduce de 1 s a 530ms,parece más"activo",Haga coincidir un ritmo de entrada de contenido más rápido
 *  - alpha Rango de 0~1 Cambiar a 0.2~1,evitar desaparecer por completo,Más continuidad visual
 *  - La aceleración cambió de LinearEasing a FastOutSlowInEasing,La respiración se siente más natural
 *  - El ancho aumenta de 2dp a 2.5dp,La altura aumentó de 16 dp a 18dp,Un poco más llamativo
 */
@Composable
internal fun StreamingCursor(color: Color, modifier: Modifier = Modifier) {
    val alpha = if (MuseMotion.isReducedMotion()) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "streaming_cursor")
        val animatedAlpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = MuseMotion.tween(durationMillis = MuseAnimation.LOOP_NORMAL_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursor_alpha",
        )
        animatedAlpha
    }
    Box(
        modifier = modifier
            .size(width = 2.5.dp, height = 18.dp)
            .background(color = color.copy(alpha = alpha)),
    )
}

/**
 * AI transmisión/Indicador de estado del pensamiento — Animación de tinta + "pensando…"Redacción publicitaria.
 *
 * Utilice la misma animación en forma de tinta que la barra horizontal de la subtarea (las hojas/estrellas/anillos se deforman entre sí):
 * Las palabras "ocupadas" en los dos lugares deberían verse iguales, en lugar de girar en un lugar y saltar en el otro.
 * Para el tamaño [MuseThinkingGlyphSize], está reservado para este lugar.
 */
@Composable
internal fun ThinkingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MuseInkGlyph(
            modifier = Modifier.size(MuseThinkingGlyphSize),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.chat_thinking),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

/**
 * Función2: TTS Reproductor de mensajes de voz. Se muestra debajo de la burbuja de mensajes de IA,Aparece mientras TTS lee el mensaje actual.
 *
 * Contiene animación de barra de ondas + reproducción./Botón de pausa + barra de progreso + selección de doble velocidad.
 */
@Composable
internal fun TtsAudioPlayer(modifier: Modifier = Modifier) {
    val ttsManager: io.zer0.muse.ui.speech.TtsManager = org.koin.compose.koinInject()
    val state by ttsManager.playbackState.collectAsStateWithLifecycle()
    val isPlaying = state.status == io.zer0.muse.ui.speech.PlaybackStatus.Playing
    val isPaused = state.status == io.zer0.muse.ui.speech.PlaybackStatus.Paused
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f

    var speedIndex by rememberSaveable { mutableIntStateOf(1) }
    val speeds = remember { listOf(0.8f, 1.0f, 1.2f, 1.5f) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MuseShapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.bubbleInner),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                // jugar/botón de pausa
                MuseTactileButton(
                    icon = if (isPlaying) MuseIcons.pause else MuseIcons.play,
                    onClick = {
                        if (isPlaying) {
                            ttsManager.pause()
                        } else {
                            ttsManager.resume()
                        }
                    },
                    contentDescription = if (isPlaying) {
                        stringResource(
                            R.string.speech_pause_cd,
                        )
                    } else {
                        stringResource(R.string.speech_resume_cd)
                    },
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 20.dp,
                    modifier = Modifier.defaultMinSize(minWidth = MuseIconSizes.touchTarget, minHeight = MuseIconSizes.touchTarget),
                )
                // Animación de barra de ondas(4 tiras verticales,Rebote con estado de reproducción)
                WaveformBars(isActive = isPlaying)
                Spacer(Modifier.weight(1f))
                // Selección de doble velocidad
                Text(
                    text = "${speeds[speedIndex]}x",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            speedIndex = (speedIndex + 1) % speeds.size
                            ttsManager.setSpeed(speeds[speedIndex])
                        }
                        .clip(MuseShapes.tiny)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(MusePaddings.chipInnerLoose),
                )
            }
            Spacer(Modifier.height(MusePaddings.tinyGap))
            // barra de progreso
            MuseSlider(
                value = progress,
                onValueChange = { v ->
                    val targetMs = (v * state.durationMs).toLong()
                    val delta = (targetMs - state.positionMs).toInt()
                    ttsManager.seekBy(delta)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Animación de barra de ondas — 4 tiras verticales,Escalonar los rebotes uno por uno durante la reproducción.,Deténgase cuando esté en pausa.
 */
@Composable
internal fun WaveformBars(isActive: Boolean) {
    val reducedMotion = MuseMotion.isReducedMotion()
    val voicePlayingCd = stringResource(R.string.chat_voice_playing_cd)
    val voiceReadyCd = stringResource(R.string.chat_voice_ready_cd)
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        // v1.0.52: Accesibilidad — TalkBack Se puede informar el estado de la forma de onda
        modifier = Modifier.semantics {
            contentDescription = if (isActive) voicePlayingCd else voiceReadyCd
        },
    ) {
        val heights = listOf(MusePaddings.itemGap, 18.dp, 14.dp, 20.dp)
        val infiniteTransition = if (isActive && !reducedMotion) {
            rememberInfiniteTransition(label = "waveform")
        } else {
            null
        }
        heights.forEachIndexed { index, maxHeight ->
            val scale by if (infiniteTransition == null) {
                androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
            } else {
                infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = MuseAnimation.LOOP_NORMAL_MS
                            0.4f at 0
                            1f at (225 + (minOf(index, MuseAnimation.STAGGER_MAX_INDEX) * MuseAnimation.STAGGER_STEP_MS))
                            0.4f at MuseAnimation.LOOP_NORMAL_MS
                        },
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "bar$index",
                )
            }
            val currentHeight = if (isActive) maxHeight * scale else maxHeight * 0.4f
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(currentHeight)
                    .clip(MuseShapes.tiny)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isActive) 0.8f else 0.3f)),
            )
        }
    }
}

/**
 * v0.48: Formato de marca de tiempo del mensaje — Tiempo controlado por chatPrefs.use24Hour,
 * Visualización predeterminada de 24 horas "HH:mm",12 visualización de la hora "h:mm a".
 */
// v1.79 (L-B12): SimpleDateFormat caché a nivel de archivo,Evite crear cada mensaje de forma independiente
private val sdf24Hour by lazy {
    java.text.SimpleDateFormat(MuseDateFormats.TIME_SHORT, java.util.Locale.getDefault())
}
private val sdf12Hour by lazy {
    java.text.SimpleDateFormat(MuseDateFormats.TIME_12H, java.util.Locale.getDefault())
}

// L-MB3: Eliminar anotaciones @Composable redundantes(La función no utiliza ningún Composable API)
internal fun formatMessageTime(timestamp: Long, use24Hour: Boolean = true): String {
    val sdf = if (use24Hour) sdf24Hour else sdf12Hour
    return sdf.format(java.util.Date(timestamp))
}

/**
 * Formato de duración del vídeo:milisegundos → "M:SS"(Más de 1 hora "H:MM:SS").
 * Solo se usa para la etiqueta de duración en la esquina inferior derecha de la burbuja del mensaje.,La semántica es coherente con la función del mismo nombre en InputBar.
 */
internal object MessageBubbleFormatters {
    fun formatVideoDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"
        val totalSec = durationMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
