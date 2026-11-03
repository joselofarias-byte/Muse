package io.zer0.muse.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * v1.0.5 Reescritura de ayuda visual:Preprocesador de imágenes.
 *
 * Responsabilidades:
 *  - Compresión de tamaño de imágenes base64.(lado más largo ≤ [POLICY.maxDimension])y compresión de calidad JPEG
 *  - Olfateando tipos MIME reales a través de bytes mágicos(declaración de desconfianza)
 *  - Asigne el presupuesto de una sola imagen según la cantidad de imágenes.,más allá [POLICY.totalBase64BudgetBytes] Error presupuestario total
 *  - Conversión unificada de imágenes que no son JPEG/PNG/WebP/GIF JPEG
 *
 * Puntos de implementación:
 *  - Para escalado y compresión. [Bitmap.compress],No depende del navegador Canvas
 *  - Imagen única 4,5 MB + total 24MB,Android La compresión JPEG de extremo a extremo es generalmente más compacta que la del navegador
 *  - base64 usar [Base64.decode] Verificar directamente la longitud y el contenido
 */
object VisionImagePreprocessor {

    private const val TAG = "VisionImagePreprocessor"

    /**
     * Estrategia de entrada de imágenes.
     *
     * significado numérico:
     *  - [maxDimension] 2000:Lado más largo 2000px. lo suficientemente claro,También controla el volumen base64.
     *  - [maxImageBase64Bytes] 4.5MB:Límite superior único base64. Si excede, continuará comprimido en este volumen.
     *  - [totalBase64BudgetBytes] 24MB:Presupuesto total base64 de toda la solicitud(provider Margen de seguridad de límite de 32 MB).
     *  - [jpegQuality] 80:El punto de equilibrio entre masa y volumen.
     */
    object POLICY {
        const val maxDimension = 2000
        const val maxImageBase64Bytes = 4_500_000 // 4.5MB
        const val totalBase64BudgetBytes = 24_000_000 // 24MB
        const val jpegQuality = 80
    }

    /**
     * Resultados del preprocesamiento.
     *
     * @param base64 preprocesado base64(Ninguno dato: prefijo)
     * @param mimeType El tipo MIME real olfateado
     * @param originalWidth Ancho de la imagen original(px),La decodificación falló como 0
     * @param originalHeight Altura de la imagen original(px)
     * @param resizedWidth ancho después de la compresión(px)
     * @param resizedHeight Altura después de la compresión(px)
     */
    data class PreparedImage(
        val base64: String,
        val mimeType: String,
        val originalWidth: Int,
        val originalHeight: Int,
        val resizedWidth: Int,
        val resizedHeight: Int,
    )

    /**
     * Preprocesar una única imagen base64.
     *
     * proceso:
     *  1. oler MIME(magic bytes)
     *  2. Base64 Decodificar a matriz de bytes
     *  3. decodificado como Bitmap,Leer tamaño original
     *  4. prensa [perImageMaxBytes] Calcular escala,resize llegar ≤ [POLICY.maxDimension]
     *  5. JPEG Compresión,Si el volumen aún excede el presupuesto, la calidad se reducirá hasta alcanzar el estándar.
     *  6. Codificación Rebase64
     *
     * @param imageBase64 originales base64(Ninguno dato: prefijo)
     * @param imageCount El número total de imágenes en esta solicitud.,Se utiliza para asignar presupuesto de imagen única.
     * @return imágenes preprocesadas;Volver si falla la decodificación null(La persona que llama decide descartar o bajar de categoría)
     */
    suspend fun prepareSingle(imageBase64: String, imageCount: Int = 1): PreparedImage? = withContext(Dispatchers.IO) {
        if (imageBase64.isBlank()) return@withContext null

        // 1. Olfateando MIME + decodificación
        val mimeType = sniffMimeType(imageBase64)
        val bytes = runCatching {
            Base64.decode(imageBase64, Base64.NO_WRAP)
        }.getOrElse {
            Logger.w(TAG, "base64 Falló la decodificación: ${it.message}")
            return@withContext null
        }

        // 2. Decodificar mapa de bits para leer el tamaño original
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val origW = boundsOpts.outWidth.takeIf { it > 0 } ?: 0
        val origH = boundsOpts.outHeight.takeIf { it > 0 } ?: 0

        // 3. Escalado informático:objetivo ≤ maxDimension,Presione simultáneamente sampleSize 2^n Ampliar(evitar OOM)
        val targetMaxDim = POLICY.maxDimension
        var sample = 1
        while (origW / sample > targetMaxDim || origH / sample > targetMaxDim) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            ?: return@withContext null

        // 4. Escalado más preciso(Si la muestra está escalada y aún excede maxDimension)
        val resized = if (bitmap.width > targetMaxDim || bitmap.height > targetMaxDim) {
            val scale = minOf(
                targetMaxDim.toFloat() / bitmap.width,
                targetMaxDim.toFloat() / bitmap.height,
            ).coerceAtMost(1f)
            val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newW, newH, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }

        // 5. JPEG Compresión,Disminuir la calidad al presupuesto.
        val perImageMaxBytes = computePerImageBudget(imageCount)
        val compressed = compressToBudget(resized, POLICY.jpegQuality, perImageMaxBytes)
        resized.recycle()

        val resultBase64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
        PreparedImage(
            base64 = resultBase64,
            mimeType = "image/jpeg", // Transferencia unificada JPEG
            originalWidth = origW,
            originalHeight = origH,
            resizedWidth = if (origW > 0) minOf(origW, targetMaxDim) else 0,
            resizedHeight = if (origH > 0) minOf(origH, targetMaxDim) else 0,
        )
    }

    /**
     * Preprocesar varias imágenes en lotes,Consulta el presupuesto total.
     *
     * @param images Lista base64 sin procesar
     * @return Lista de imágenes preprocesadas(Las imágenes fallidas se descartan);Si el presupuesto total excede el límite, deséchelo. [VisionImagePreprocessException]
     */
    suspend fun prepareBatch(images: List<String>): List<PreparedImage> = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext emptyList()
        val count = images.size
        val results = mutableListOf<PreparedImage>()
        var totalBytes = 0L

        for (img in images) {
            val prepared = prepareSingle(img, count) ?: run {
                Logger.w(TAG, "No. ${results.size + 1} Error en el preprocesamiento de la imagen,descartar")
                continue
            }
            totalBytes += prepared.base64.length
            if (totalBytes > POLICY.totalBase64BudgetBytes) {
                throw VisionImagePreprocessException(
                    "El tamaño total de la imagen comprimida.(${totalBytes / 1024}KB)por encima del presupuesto(${POLICY.totalBase64BudgetBytes / 1024}KB)",
                )
            }
            results.add(prepared)
        }
        results
    }

    /** Calcular el presupuesto de una sola imagen:El presupuesto total se divide en partes iguales entre el número de imágenes.,Pero no excede el límite superior de una sola imagen. */
    private fun computePerImageBudget(imageCount: Int): Int {
        val safeCount = imageCount.coerceAtLeast(1)
        return minOf(
            POLICY.maxImageBase64Bytes,
            POLICY.totalBase64BudgetBytes / safeCount,
        )
    }

    /**
     * JPEG Ajustarse al presupuesto:de [startQuality] calidad decreciente,hasta volumen ≤ [maxBytes] O la calidad baja a 30.
     */
    private fun compressToBudget(bitmap: Bitmap, startQuality: Int, maxBytes: Int): ByteArray {
        var quality = startQuality.coerceIn(30, 100)
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            if (bytes.size <= maxBytes || quality <= 30) return bytes
            quality -= 10
        } while (true)
    }

    /**
     * Pasar caracteres de encabezado base64(magic bytes)Olfatear tipos MIME.
     *
     * Formatos soportados:JPEG / PNG / GIF / WebP,Otras alternativas son imagen/jpeg.
     */
    fun sniffMimeType(base64: String): String {
        val trimmed = base64.trim()
        return when {
            trimmed.startsWith("/9j/") -> "image/jpeg"
            trimmed.startsWith("iVBOR") -> "image/png"
            trimmed.startsWith("R0lGOD") -> "image/gif"
            trimmed.startsWith("UklGR") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * Calcular la longitud corta de la imagen base64. hash(para almacenamiento en caché key).
     * Obtenga los primeros 16 caracteres de SHA-256,Suficiente para distinguir entre diferentes imágenes.
     */
    fun hashShort(base64: String): String {
        if (base64.isBlank()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(base64.toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Excepción de preprocesamiento de imágenes(El presupuesto total excede el límite, etc.).
 */
class VisionImagePreprocessException(message: String) : Exception(message)
