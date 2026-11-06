package io.zer0.muse.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import io.zer0.common.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Phase 8.6: Administrador de OCR local(ML Kit Text Recognition v2,chino e ingles).
 *
 * - Identificación fuera de línea(Los modelos están empaquetados con el APK.,Descargado por GMS y almacenado en caché en el dispositivo en el primer uso)
 * - Admite caracteres chinos + latinos(dependencia única `text-recognition-chinese` Eso es todo)
 * - entrada: URI(Bitmap decodificar) o Bitmap
 * - salida: Texto plano concatenado(Saltos de línea entre párrafos)
 *
 * limitaciones:
 *  - ML Kit depender de Google Play services(Es posible que sea necesario instalar manualmente las ROM nacionales GMS)
 *  - El primer uso activará la descarga del modelo.(~20MB)
 *  - No admite el reconocimiento de escritura a mano(ML Kit Modelo de escritura a mano separada,No integrado)
 */
class OcrManager {

    // L-OCR2: recognizer Permanente con el ciclo de vida de la aplicación.(lazy singleton),todavía no close().
    // TextRecognizer.close() Lanzará recursos modelo.,Pero recrear requiere recargar el modelo.(Hay una sobrecarga de descarga por primera vez.),
    // La ganancia supera la ganancia en escenarios frecuentes de OCR. Si necesita administrar la memoria con cuidado en el futuro,Se puede cerrar en Actividad onDestroy.
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * Identificar texto de URI(Lee automáticamente los metadatos de rotación EXIF para corregir la orientación).
     * @param uri fotos URI(SAF / Disponible en álbum de fotos/cámara)
     * @param context usado para ContentResolver
     * @return Texto plano reconocido;Devuelve una cadena vacía en caso de error
     */
    suspend fun recognize(uri: Uri, context: Context): String {
        val rotation = readExifRotation(uri, context)
        val bitmap = decodeBitmap(uri, context) ?: return ""
        return recognize(bitmap, rotation)
    }

    /**
     * Reconocer texto de mapa de bits(Orientación correcta utilizando el ángulo de rotación especificado).
     * @param bitmap mapa de bits de entrada
     * @param rotationDegrees ángulo de rotación(0/90/180/270),Calculado a partir de metadatos EXIF
     * @return Texto plano reconocido;Devuelve una cadena vacía en caso de error
     */
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val sb = StringBuilder()
                for (block in result.textBlocks) {
                    val blockText = block.text.trim()
                    if (blockText.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.appendLine()
                        sb.append(blockText)
                    }
                }
                cont.resume(sb.toString())
            }
            .addOnFailureListener { e ->
                Logger.w("OcrManager", "OCR El reconocimiento falló: ${e.message}", e)
                cont.resume("")
            }
        cont.invokeOnCancellation { }
    }

    /** Leer los metadatos de rotación EXIF de la imagen señalada por el URI,Devuelve el ángulo de rotación.(0/90/180/270). Devuelve 0 si la lectura falla. */
    private fun readExifRotation(uri: Uri, context: Context): Int {
        return runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching 0
            input.use { stream ->
                val exif = android.media.ExifInterface(stream)
                when (
                    exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrNull() ?: 0
    }

    /** Decodificar el URI para Bitmap;Escalar automáticamente al tamaño objetivo(protegerse contra OOM). */
    private fun decodeBitmap(uri: Uri, context: Context): Bitmap? {
        return runCatching {
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri) ?: return null
            input.use {
                // primera vez:Solo tamaño de decodificación
                val opts = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, opts)
                // Calcular en Tamaño de muestra: lado más largo ≤ 2048(OCR No hay necesidad de ultra alta definición)
                val target = 2048
                var sample = 1
                while (opts.outWidth / sample > target || opts.outHeight / sample > target) {
                    sample *= 2
                }
                // segunda vez:decodificación real
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                }
                resolver.openInputStream(uri)?.use { realInput ->
                    BitmapFactory.decodeStream(realInput, null, decodeOpts)
                }
            }
        }.recoverCatching { e ->
            // L-OCR4: Registro de errores de decodificación,Evite devolver nulo silenciosamente, lo cual es difícil de solucionar
            Logger.w("OcrManager", "Bitmap Falló la decodificación: uri=$uri, ${e.message}", e)
            null
        }.getOrNull()
    }
}
