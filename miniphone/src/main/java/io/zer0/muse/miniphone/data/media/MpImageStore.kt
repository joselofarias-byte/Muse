package io.zer0.muse.miniphone.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * La imagen se coloca en el plato.
 *
 * Todas las imágenes seleccionadas se copiarán en el directorio privado del módulo.:Sólo la ruta local se almacena en el mensaje.,El usuario elimina el álbum original y el anfitrión borra el caché.,
 * El historial de chat no se convertirá en una imagen vacía. El nombre del archivo toma los primeros 16 bits de SHA-256 del contenido. —— Seleccione la misma imagen dos veces para obtener
 * El mismo archivo, la misma ruta,Los dos mensajes de Curry apuntan a la misma copia,No ocupará espacio repetido.
 *
 * exceder [compressMinBytes] La imagen se reduce primero al lado largo. [maxEdgePx] guardar de nuevo:Página de chat mostrada como máximo 200dp,
 * No tiene sentido guardar más de diez MB de imágenes originales. Si la reducción falla, guarde la imagen original.,No permita que la compresión se convierta en un motivo para perder imágenes.
 *
 * @param directory Descargar directorio(Modulo privado,no expuesto al mundo exterior)
 * @param openStream Obtener el flujo de entrada de uri;No se puede leer la devolución null
 */
class MpImageStore(
    private val directory: File,
    private val openStream: (Uri) -> InputStream?,
    private val compressMinBytes: Long = DEFAULT_COMPRESS_MIN_BYTES,
    private val maxEdgePx: Int = DEFAULT_MAX_EDGE_PX,
) {
    /**
     * poner [uri] La imagen puntiaguda se almacena en un directorio privado.,Devuelve la ruta absoluta local.
     *
     * no puedo leer(Se revocaron los permisos, se eliminó el documento original y la transmisión se interrumpió a la mitad.)O regresa cuando no se puede escribir en el disco. null:
     * Elige nueve a la vez,No puedes renunciar a todo el lote sólo porque uno de ellos falla.,La persona que llama puede simplemente saltarse este.
     */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        val original = readBytes(uri) ?: return@withContext null
        val payload = shrink(original) ?: original
        write(payload, digestOf(original))?.absolutePath
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        openStream(uri)?.use(InputStream::readBytes)
    }.getOrNull()?.takeIf(ByteArray::isNotEmpty)

    /** Si el archivo con el mismo nombre ya existe, reutilícelo directamente.:Mismo contenido,No es necesario volver a escribirlo. */
    private fun write(payload: ByteArray, digest: String): File? = runCatching {
        directory.mkdirs()
        val target = File(directory, "$digest.${extensionOf(payload)}")
        if (!target.exists()) target.writeBytes(payload)
        target
    }.getOrNull()

    /** La recodificación solo se producirá si se excede el umbral y el lado largo excede el límite superior.;Volver a codificar imágenes pequeñas solo degradará la calidad de la imagen. */
    private fun shrink(bytes: ByteArray): ByteArray? {
        if (bytes.size.toLong() <= compressMinBytes) return null
        val scaled = decodeScaled(bytes) ?: return null
        return ByteArrayOutputStream().use { out ->
            if (scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) out.toByteArray() else null
        }
    }

    /**
     * Desenvolver y encoger hasta el límite superior del lado largo.;Devuelve nulo si no es necesario comprimirlo o no se puede expandir.
     *
     * Primero solo usa [BitmapFactory.Options.inJustDecodeBounds] tamaño de lectura,Luego presione la potencia de 2 para obtener la frecuencia de muestreo.,
     * Evite leer toda la imagen grande en la memoria sólo para medir el tamaño una vez.
     */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        if (longEdge <= maxEdgePx) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(longEdge, maxEdgePx) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return scaleDown(decoded)
    }

    /** La frecuencia de muestreo es solo una potencia de 2.,Después del muestreo, es posible que aún sea mayor que el límite superior del lado largo.,Aquí está nuevamente reducido. */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longEdge = max(bitmap.width, bitmap.height)
        if (longEdge <= maxEdgePx) return bitmap
        val ratio = maxEdgePx.toFloat() / longEdge
        val width = max(MIN_EDGE_PX, (bitmap.width * ratio).roundToInt())
        val height = max(MIN_EDGE_PX, (bitmap.height * ratio).roundToInt())
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** El sufijo es reconocido por el encabezado del archivo.:La imagen comprimida ya está JPEG,No se puede utilizar el sufijo original. */
    private fun extensionOf(bytes: ByteArray): String = when {
        bytes.hasPrefix(PNG_MAGIC) -> "png"
        bytes.hasPrefix(JPEG_MAGIC) -> "jpg"
        bytes.hasPrefix(GIF_MAGIC) -> "gif"
        else -> "img"
    }

    private fun ByteArray.hasPrefix(magic: IntArray): Boolean =
        size >= magic.size && magic.indices.all { (this[it].toInt() and BYTE_MASK) == magic[it] }

    /** El resumen de contenido ocupa los primeros 8 bytes.(16 caracteres hexadecimales):lo suficientemente largo,No hará que el camino sea difícil de identificar. */
    private fun digestOf(bytes: ByteArray): String = MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes)
        .take(NAME_HASH_BYTES)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

    companion object {
        /** directorio privado del módulo,relativamente `context.filesDir`. */
        const val DIRECTORY_NAME: String = "miniphone/images"

        private const val DEFAULT_COMPRESS_MIN_BYTES: Long = 8L * 1024 * 1024
        private const val DEFAULT_MAX_EDGE_PX: Int = 1600
        private const val JPEG_QUALITY: Int = 85
        private const val MIN_EDGE_PX: Int = 1
        private const val NAME_HASH_BYTES: Int = 8
        private const val BYTE_MASK: Int = 0xFF
        private const val HASH_ALGORITHM: String = "SHA-256"

        private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47)
        private val JPEG_MAGIC = intArrayOf(0xFF, 0xD8, 0xFF)
        private val GIF_MAGIC = intArrayOf(0x47, 0x49, 0x46, 0x38)
    }
}

/** La frecuencia de muestreo es una potencia de 2.:Deje que el lado largo decodificado no sea menos de la mitad del valor objetivo,El escalado de precisión hace el resto. */
internal fun sampleSizeFor(longEdge: Int, maxEdge: Int): Int {
    if (maxEdge <= 0) return 1
    var sample = 1
    while (longEdge / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/** Instancia predeterminada dentro del módulo:Los directorios están anclados en archivos privados.,El flujo de entrada se toma de ContentResolver. */
fun miniPhoneImageStore(context: Context): MpImageStore = MpImageStore(
    directory = File(context.filesDir, MpImageStore.DIRECTORY_NAME),
    openStream = { uri -> context.contentResolver.openInputStream(uri) },
)
