package io.zer0.muse.ui.chat

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import io.zer0.common.FileSizeFormatter
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Enumeración del tipo de archivo adjunto — Definición centralizada para reemplazar cadenas esparcidas por todas partes/MIME juez.
 *
 * Estado actual de la implementación anterior (sin enumeración unificada):
 *  - IMAGE:  ChatState.pendingImages / UIMessage.imageBase64List(base64 lista)
 *  - AUDIO:  ASR El proceso de grabación no se cuelga en el cuerpo del mensaje.
 *  - FILE:   DocumentPicker Después de la selección, DocumentParser lo analiza en texto.
 *  - VIDEO:  Este archivo se agrega recientemente y se envía directamente a un proveedor que admite video (como Gemini) o se degrada a una imagen de fotograma clave.
 */
enum class AttachmentType {
    IMAGE,
    AUDIO,
    FILE,
    VIDEO,
}

/**
 * Metadatos del archivo adjunto de vídeo.
 *
 * - [uri] uri de contenido de vídeo (SAF El uri de autorización temporal solo es válido durante el ciclo de vida del proceso actual)
 * - [durationMs] Duración (milisegundos)
 * - [width] Ancho del vídeo (píxeles)
 * - [height] Altura del vídeo (píxeles)
 * - [thumbnail] Miniatura del primer fotograma base64 (sin datos: prefijo), utilizada para la vista previa en las burbujas de mensajes
 */
data class VideoAttachment(
    val uri: Uri,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val thumbnail: String?,
)

/**
 * Procesador de archivos adjuntos de vídeo.
 *
 * Responsabilidades:
 *  - Verificar el tamaño del video (>50MB Error de devolución, lo que solicita al usuario que comprima o rechace)
 *  - usar [MediaMetadataRetriever] Extraer metadatos (duración/resolución)
 *  - Extraiga la miniatura del primer fotograma para obtener una vista previa de la interfaz de usuario
 *  - Extraer fotogramas clave (primer fotograma + fotograma medio + último fotograma)base64 lista,
 *    Para proveedores que no admiten video, cambie al envío de imágenes
 *
 * Restricciones:
 *  - Sin nuevas dependencias: use solo Android nativo [MediaMetadataRetriever]
 *  - Todas las E/S y la decodificación se cambian a [Dispatchers.IO], para evitar bloquear el hilo principal
 *  - MediaMetadataRetriever Debe liberarse después de su uso; de lo contrario, se producirá una fuga en el MediaPlayer subyacente.
 */
class VideoAttachmentHandler {

    companion object {
        private const val TAG = "VideoAttachmentHandler"

        /** Tamaño máximo de vídeo:50MB(Evitar el desbordamiento de memoria + alinear con los límites de carga del proveedor). */
        const val MAX_VIDEO_SIZE_BYTES = 50L * 1024 * 1024

        /** Calidad de compresión JPEG de miniaturas (0-100). */
        private const val THUMBNAIL_JPEG_QUALITY = 80

        /** Los píxeles del lado más largo de la miniatura (la vista previa visual no requiere resolución original, controla el volumen base64). */
        private const val THUMBNAIL_MAX_EDGE = 720
    }

    /**
     * Procese archivos adjuntos de video: verifique el tamaño + extraiga metadatos + genere la miniatura del primer fotograma.
     *
     * Proceso:
     *  1. Pase [ContentResolver] Tamaño del archivo de consulta,>50MB Fallo de devolución directa
     *  2. usar [MediaMetadataRetriever] Duración de la extracción/resolución
     *  3. getFrameAtTime(0) Obtenga el primer fotograma y codifíquelo como una miniatura JPEG base64
     *
     * @return Regresar exitosamente [VideoAttachment];Error (el archivo es demasiado grande/no se puede leer/no hay metadatos) devuelve Result.failure
     */
    suspend fun handleVideoAttachment(uri: Uri, context: Context): Result<VideoAttachment> {
        val r = resultOf {
            withContext(Dispatchers.IO) {
                // 1. Verificar el tamaño del archivo (>50MB rechazar)
                val size = queryFileSize(uri, context)
                require(size in 1..MAX_VIDEO_SIZE_BYTES) {
                    "El vídeo es demasiado grande (${formatBytes(size)}), comprímelo a menos de 50 MB y vuelve a intentarlo."
                }

                // 2. Extraer metadatos
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION,
                    )?.toLongOrNull() ?: 0L
                    val width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    )?.toIntOrNull() ?: 0
                    val height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                    )?.toIntOrNull() ?: 0

                    // 3. Extraiga la miniatura del primer fotograma (el error no es fatal, se reduce a nulo)
                    val thumbnail = runCatching {
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { bmp -> encodeBitmapToBase64(bmp) }
                    }.onFailure { e ->
                        Logger.w(TAG, "No se pudo extraer la miniatura del primer fotograma", e)
                    }.getOrNull()

                    VideoAttachment(
                        uri = uri,
                        durationMs = durationMs,
                        width = width,
                        height = height,
                        thumbnail = thumbnail,
                    )
                } finally {
                    runCatching { retriever.release() }
                }
            }
        }
        r.onError { _, t -> Logger.e(TAG, "handleVideoAttachment failed", t) }
        return when (r) {
            is io.zer0.common.Result.Success -> Result.success(r.data)
            is io.zer0.common.Result.Error -> Result.failure(r.throwable ?: IllegalStateException(r.message))
        }
    }

    /**
     * Extraiga la lista base64 de fotogramas clave (primer fotograma + fotograma medio + último fotograma).
     *
     * Para proveedores que no admiten la carga de videos: degrade el video a varias imágenes y envíelas al modelo visual.
     *
     * @param frameCount Número deseado de fotogramas clave (predeterminado 3: primero/en/final), el número real de cuadros puede ser menor que este valor
     * @return base64 Lista de cadenas (sin datos: prefijo) o lista vacía en caso de error
     */
    suspend fun extractKeyFrames(uri: Uri, context: Context, frameCount: Int = 3): List<String> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0L
            if (durationMs <= 0L) return@withContext emptyList()

            // La unidad de marca de tiempo es microsegundos (us),durationMs es milisegundos y requiere *1000
            // Deje un margen de 50 ms en el último fotograma para evitar cruzar el límite (el fotograma final de algunos codificadores es inalcanzable)
            val timestamps = buildList {
                add(0L)
                if (frameCount >= 2) add(durationMs * 1000L / 2)
                if (frameCount >= 3) add((durationMs - 50).coerceAtLeast(0) * 1000L)
            }.distinct()

            timestamps.mapNotNull { tsUs ->
                runCatching {
                    retriever.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?.let { bmp -> encodeBitmapToBase64(bmp) }
                }.onFailure { e ->
                    Logger.w(TAG, "No se pudieron extraer fotogramas clave ts=$tsUs", e)
                }.getOrNull()
            }
        } catch (e: Exception) {
            Logger.e(TAG, "extractKeyFrames failed", e)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Obtenga la miniatura base64 del primer fotograma (sin datos: prefijo).
     *
     * Devuelve nulo en caso de error (la persona que llama debe dar explicaciones, como mostrar una imagen de marcador de posición).
     */
    suspend fun getVideoThumbnail(uri: Uri, context: Context): String? {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { bmp -> encodeBitmapToBase64(bmp) }
            } catch (e: Exception) {
                Logger.w(TAG, "getVideoThumbnail failed", e)
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    /**
     * Consulta la cantidad de bytes del archivo de video a través de ContentResolver.
     *
     * Utilice openAssetFileDescriptor para obtener longitud, compatible con content:// y file:// uri.
     * Devuelve -1 en caso de error (la persona que llama omite la verificación de tamaño o la rechaza en consecuencia).
     */
    private fun queryFileSize(uri: Uri, context: Context): Long {
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length
            } ?: -1L
        }.getOrDefault(-1L)
    }

    /** Codificar mapa de bits en base64 (JPEG, sin datos: prefijo), presione [THUMBNAIL_MAX_EDGE] Escala proporcionalmente. */
    private fun encodeBitmapToBase64(bmp: android.graphics.Bitmap): String {
        val scaled = scaleIfNeeded(bmp)
        val baos = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    /** Escale proporcionalmente al lado más largo no más de [THUMBNAIL_MAX_EDGE], para evitar la explosión de volumen de base64 causada por miniaturas demasiado grandes. */
    private fun scaleIfNeeded(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = bmp.width
        val h = bmp.height
        val longest = maxOf(w, h)
        if (longest <= THUMBNAIL_MAX_EDGE) return bmp
        val scale = THUMBNAIL_MAX_EDGE.toFloat() / longest
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(bmp, newW, newH, true)
    }

    /** Formatee el número de bytes en una cadena legible. */
    private fun formatBytes(bytes: Long): String = FileSizeFormatter.format(bytes)
}
