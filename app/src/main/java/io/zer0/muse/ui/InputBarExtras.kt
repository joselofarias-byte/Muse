@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")

package io.zer0.muse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.image.ImageGenParams
import io.zer0.ai.image.ImageModelCatalog
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.safeFraction
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Barra de ondas de grabación:Representa el historial de amplitud reciente como barras verticales.
 * v1.91: La amplitud cambió a normalizada. Float(0-1f),Ya no es necesario dividir por 32768.
 */
@Composable
internal fun RecordingWaveform(amplitudes: List<Float>) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.height(MuseIconSizes.waveformHeight),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tinyGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        amplitudes.forEach { amp ->
            // v1.91: amp Ya es un valor normalizado de 0-1f.
            // defensa contra el colapso: coerceIn No válido para NaN(NaN relativamente constante false),NaN La fuga en fillMaxHeight arrojará
            // "Expecting fraction between 0 and 1. Got NaN"; Utilice la normalización de SafeFraction en su lugar.
            val fraction = amp.safeFraction(min = 0.05f, fallback = 0.05f)
            val animatedHeight by animateFloatAsState(
                targetValue = fraction,
                animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS),
                label = "wave",
            )
            Box(
                modifier = Modifier
                    .width(MuseIconSizes.waveformBarWidth)
                    .fillMaxHeight(animatedHeight)
                    .clip(RoundedCornerShape(MuseIconSizes.waveformBarRadius))
                    .background(primary.copy(alpha = 0.7f)),
            )
        }
    }
}

/**
 * v0.35: Panel de parámetros del modo de dibujo — Tamaño/calidad/Anulación temporal de estilo + imagen de referencia.
 */
@Composable
internal fun ImageGenParamsPanel(params: ImageGenParams, onParamsChange: (ImageGenParams) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            scope.launch {
                // v1.79 (H-I4): Lectura de imágenes grandes + codificación Base64 movida al hilo IO,Evite bloquear el hilo principal.
                // v1.140: Comprime automáticamente las imágenes a 1024 px después de la selección + JPEG 85,Después de limitar base64 <= 4MB,
                //         Evite OOM y solicite tiempo de espera causado por la transferencia directa de la imagen original 10 MB+
                withContext(Dispatchers.IO) {
                    runCatching {
                        compressReferenceImageToDataUri(uri = it, context = context)
                    }
                }.onSuccess { result ->
                    onParamsChange(params.copy(referenceImageUri = result.dataUri))
                    // UI Tamaño comprimido rápido(Experiencia transparente)
                    MuseToast.show(
                        context.getString(R.string.chat_ref_image_compressed, result.describe()),
                        2500,
                    )
                }.onFailure { e ->
                    // v1.79 (H-I4+M-I2): Agregar mensaje de error
                    MuseToast.show(context.getString(R.string.chat_ref_image_load_failed, e.message ?: ""))
                }
            }
        }
    }

    val model = remember(params.model) { ImageModelCatalog.resolveById(params.model) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MusePaddings.contentGap)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = MusePaddings.itemGap, vertical = MusePaddings.auxGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_draw_params),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                // LAYOUT-01: Row Ancho del texto interno + límite de línea, evita el ajuste de línea palabra por palabra al ampliar el tamaño de fuente
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            model?.let {
                Text(
                    text = it.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Tamaño
        val sizes = model?.supportedSizes
        if (!sizes.isNullOrEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(sizes, key = { it }) { value ->
                    MuseChip(
                        selected = params.size == value,
                        onClick = { onParamsChange(params.copy(size = value)) },
                        label = value,
                    )
                }
            }
        }

        // calidad
        val qualities = model?.supportedQualities
        if (!qualities.isNullOrEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(qualities, key = { it }) { value ->
                    val label = when (value) {
                        "standard" -> stringResource(R.string.chat_quality_standard)
                        "hd" -> stringResource(R.string.chat_quality_hd)
                        "high" -> stringResource(R.string.chat_quality_high)
                        "medium" -> stringResource(R.string.chat_quality_medium)
                        "low" -> stringResource(R.string.chat_quality_low)
                        "auto" -> stringResource(R.string.chat_quality_auto)
                        else -> value
                    }
                    MuseChip(
                        selected = params.quality == value,
                        onClick = { onParamsChange(params.copy(quality = value)) },
                        label = label,
                    )
                }
            }
        }

        // estilo
        val styles = model?.supportedStyles
        if (!styles.isNullOrEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                items(styles, key = { it }) { value ->
                    val label = when (value) {
                        "vivid" -> stringResource(R.string.chat_style_vivid)
                        "natural" -> stringResource(R.string.chat_style_natural)
                        else -> value
                    }
                    MuseChip(
                        selected = params.style == value,
                        onClick = { onParamsChange(params.copy(style = value)) },
                        label = label,
                    )
                }
            }
        }

        // Imagen de referencia
        val supportsRef = model?.supportsReferenceImage == true
        if (params.referenceImageUri.isNullOrBlank()) {
            MuseChip(
                selected = false,
                onClick = {
                    if (supportsRef) imagePicker.launch("image/*")
                },
                enabled = supportsRef,
                label = if (supportsRef) {
                    stringResource(
                        R.string.chat_ref_image_add,
                    )
                } else {
                    stringResource(R.string.chat_ref_image_not_supported)
                },
                leadingIcon = {
                    Icon(
                        imageVector = MuseIcons.image,
                        // v1.79 (L-I3): Accesibilidad contentDescription
                        contentDescription = stringResource(R.string.chat_ref_image_cd),
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = MusePaddings.maxInlineWidth)
                    .clip(MuseShapes.small),
            ) {
                SmartImage(
                    model = params.referenceImageUri,
                    contentDescription = stringResource(R.string.chat_ref_image_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = { onParamsChange(params.copy(referenceImageUri = null)) },
                    contentDescription = stringResource(R.string.chat_ref_image_clear_cd),
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.iconSmallTiny,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

/**
 * v1.140: Resultados de compresión de imágenes de referencia.
 *
 * @property dataUri en forma de `data:image/jpeg;base64,...` de Data URI,Se puede entregar directamente a ImageProvider
 * @property width ancho después de la compresión
 * @property height Altura después de la compresión
 * @property byteCount Bytes JPEG comprimidos(todavía no base64)
 */
private data class CompressedReferenceImage(
    val dataUri: String,
    val width: Int,
    val height: Int,
    val byteCount: Int,
) {
    /** tamaño legible por humanos/Descripción del volumen,Se utiliza para indicaciones de brindis. */
    fun describe(): String {
        val kb = byteCount / 1024
        return "${width}x$height, ${kb}KB"
    }
}

/**
 * v1.140: Comprimir el URI de la imagen de referencia seleccionado por el usuario para que se ajuste al tamaño/URI de datos para la restricción de tamaño.
 *
 * Flujo de procesamiento:
 *  1. Primero decodifica los límites para obtener el tamaño original.(API 28+ usar ImageDecoder,Para versión inferior BitmapFactory)
 *  2. calcular inSampleSize,encoger el lado largo para [maxSide] cerca(2 reducción de resolución a la potencia de)
 *  3. Después de decodificar para obtener el mapa de bits.,Mientras el lado esté quieto > [maxSide],Escalado preciso con Matrix
 *  4. JPEG Calidad de compresión [quality],base64 Si aún excede [maxBase64Bytes],regla
 *     Reducir gradualmente la calidad/tamaño,Hasta que se cumpla la restricción de volumen o se reduzca el límite inferior
 *
 * tirar [IllegalStateException] Indica falla de compresión(La imagen original no se puede decodificar o aún es demasiado grande después de la compresión).
 */
private fun compressReferenceImageToDataUri(
    uri: Uri,
    context: android.content.Context,
    maxSide: Int = 1024,
    quality: Int = 85,
    maxBase64Bytes: Int = 4 * 1024 * 1024,
): CompressedReferenceImage {
    val resolver = context.contentResolver

    // 1. Decodificar tamaño original
    val (origW, origH) = decodeImageBounds(resolver, uri)
    if (origW <= 0 || origH <= 0) {
        error("decode bounds failed for $uri")
    }

    // 2. calcular inSampleSize(2 al poder de,Haga que el lado largo después de reducir la resolución esté lo más cerca posible de maxSide, pero no más de 2 veces)
    var sample = 1
    while (origW / sample / 2 >= maxSide || origH / sample / 2 >= maxSide) sample *= 2

    // 3. decodificado como Bitmap(Después de reducir la resolución)
    var bitmap = decodeSampledBitmap(resolver, uri, sample)
        ?: error("decode bitmap failed for $uri")

    // 4. Escale exactamente dentro de maxSide(Mantener relación de aspecto)
    val scaled = scaleBitmapToMaxSide(bitmap, maxSide)
    if (scaled !== bitmap) {
        bitmap.recycle()
        bitmap = scaled
    }

    // 5. Compresión paso a paso,Hasta que el volumen base64 cumpla con las restricciones o caiga al límite inferior
    var currentQuality = quality
    var currentBmp = bitmap
    var bytes = compressJpeg(currentBmp, currentQuality)
    var base64Len = base64Length(bytes.size)

    // 5.1 Primero intenta solo reducir la calidad.(75 → 65 → 55)
    val qualitySteps = listOf(75, 65, 55)
    var stepIndex = 0
    while (base64Len > maxBase64Bytes && stepIndex < qualitySteps.size) {
        currentQuality = qualitySteps[stepIndex++]
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = base64Length(bytes.size)
    }

    // 5.2 Si todavía está por encima del límite, reduzca el tamaño.(768 → 512 → 384)
    val sideSteps = listOf(768, 512, 384)
    var sideIndex = 0
    while (base64Len > maxBase64Bytes && sideIndex < sideSteps.size) {
        val newSide = sideSteps[sideIndex++]
        val shrunk = scaleBitmapToMaxSide(currentBmp, newSide)
        if (shrunk !== currentBmp) {
            currentBmp.recycle()
            currentBmp = shrunk
        }
        bytes = compressJpeg(currentBmp, currentQuality)
        base64Len = base64Length(bytes.size)
    }

    val width = currentBmp.width
    val height = currentBmp.height
    currentBmp.recycle()

    if (base64Len > maxBase64Bytes) {
        // Aún por encima del límite:Negarse a subir,Evite OOM/tiempos de espera
        error("image still too large after compression (${width}x$height, ${bytes.size / 1024}KB)")
    }

    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    val dataUri = "data:image/jpeg;base64,$base64"
    return CompressedReferenceImage(
        dataUri = dataUri,
        width = width,
        height = height,
        byteCount = bytes.size,
    )
}

/** Decodificar los límites de la imagen original(ancho y alto),No carga píxeles en la memoria. */
private fun decodeImageBounds(resolver: android.content.ContentResolver, uri: Uri): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
    return opts.outWidth to opts.outHeight
}

/** Decodificar mapa de bits mediante inSampleSize. */
private fun decodeSampledBitmap(resolver: android.content.ContentResolver, uri: Uri, sampleSize: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, opts)
    }
}

/** Escale el mapa de bits proporcionalmente al lado largo <= maxSide;Si está satisfecho, devuélvalo tal cual. */
private fun scaleBitmapToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
    val w = src.width
    val h = src.height
    val longSide = maxOf(w, h)
    if (longSide <= maxSide) return src
    val scale = maxSide.toFloat() / longSide
    val matrix = Matrix().apply { setScale(scale, scale) }
    return Bitmap.createBitmap(src, 0, 0, w, h, matrix, true)
}

/** JPEG Comprimido en una matriz de bytes. */
private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** base64 El volumen después de la codificación es aproximadamente los bytes originales. * 4/3,Redondeo. */
private fun base64Length(byteCount: Int): Int {
    // cada 3 bytes → 4 personaje;Si es menos de 3, cuente como 3.NO_WRAP No se agregan nuevas líneas.
    return ((byteCount + 2) / 3) * 4
}
