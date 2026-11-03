package io.zer0.muse.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * v2.0.1: Herramientas de medios de canal — Compresión de imágenes entrantes y codificación base64.
 *
 * objetivo:Controlar el tamaño de almacenamiento de conversaciones(destino de una sola imagen ≤ [TARGET_MAX_BYTES]),manteniendo al mismo tiempo una claridad visualmente utilizable;
 * Volver si falla la decodificación null,Degradado por la persona que llama mediante texto de marcador de posición.
 */
object ChannelMediaUtils {

    private const val MAX_DIMENSION = 1280
    private const val PRIMARY_QUALITY = 82
    private const val FALLBACK_QUALITY = 62
    private const val TARGET_MAX_BYTES = 320 * 1024

    /** Decodifica y comprime los bytes de la imagen original en JPEG base64(Ninguno dato: prefijo);Devuelve nulo en caso de error. */
    fun toCompactImageBase64(bytes: ByteArray): String? = runCatching {
        // 1. muestreo de límites:Cuando el borde largo excede el límite superior, reduzca la resolución en la potencia de 2
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null
        // 2. compresión de calidad(Cuando se supere el presupuesto, la calidad se reducirá una vez más.)
        var encoded = compressJpeg(bitmap, PRIMARY_QUALITY)
        if (encoded != null && encoded.size > TARGET_MAX_BYTES) {
            encoded = compressJpeg(bitmap, FALLBACK_QUALITY)
        }
        bitmap.recycle()
        encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
    }.getOrNull()

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()
}
