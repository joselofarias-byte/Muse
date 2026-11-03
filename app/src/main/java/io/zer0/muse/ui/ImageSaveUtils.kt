package io.zer0.muse.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.zer0.muse.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Guarde imágenes en el directorio de imágenes privado de la aplicación,Y se inserta automáticamente en el álbum del sistema en Android Q+.
 *
 * Ventajas:
 *  - No depende del permiso WRITE_EXTERNAL_STORAGE,Versión completa disponible.
 *  - Android Q+ Escribiendo a través de MediaStore Pictures/Muse/,El álbum de fotos es visible.
 *  - Android P y lo siguiente se escribe en el directorio privado fuera de la aplicación,Y haga que el álbum sea visible a través de MediaScanner.
 *
 * @param uri fotos URI(http/https/data URI/Se puede utilizar cualquier archivo local.)
 * @param displayName Mostrar el nombre del archivo después de guardarlo(sin extensión)
 * @return Ruta del archivo local guardado o cadena URI de MediaStore
 */
suspend fun saveImageToGallery(context: Context, uri: String, displayName: String = "muse_${System.currentTimeMillis()}"): String =
    withContext(Dispatchers.IO) {
        val bitmap = loadBitmapFromUri(context, uri)
            ?: throw IllegalStateException(context.getString(R.string.image_save_decode_failed))

        try {
            val fileName = "$displayName.png"

            // 1. Primero escriba en el directorio privado fuera de la aplicación.(Pictures/Muse/),como caché estable
            val appPicturesDir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "Muse",
            ).apply { mkdirs() }
            val cacheFile = File(appPicturesDir, fileName)
            FileOutputStream(cacheFile).use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            // 2. Android Q+ Inserte también MediaStore para que el álbum sea visible
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Muse")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                var imageUri: Uri? = null
                try {
                    imageUri = context.contentResolver.insert(collection, contentValues)
                        ?: throw IllegalStateException(context.getString(R.string.image_save_mediastore_insert_failed))
                    context.contentResolver.openOutputStream(imageUri)?.use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    } ?: throw IllegalStateException(context.getString(R.string.image_save_open_stream_failed))
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(imageUri, contentValues, null, null)
                    return@withContext imageUri.toString()
                } catch (e: Throwable) {
                    imageUri?.let { context.contentResolver.delete(it, null, null) }
                    throw e
                }
            } else {
                // Android P y abajo: Escanear al álbum
                suspendCancellableCoroutine { continuation ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(cacheFile.absolutePath),
                        arrayOf("image/png"),
                    ) { path, _ -> continuation.resume(path) }
                }
                return@withContext cacheFile.absolutePath
            }
        } finally {
            // Liberar la memoria de mapa de bits una vez completada la escritura,evitar OOM(Especialmente aquellos creados manualmente con createBitmap Bitmap)
            bitmap.recycle()
        }
    }

private suspend fun loadBitmapFromUri(context: Context, uri: String): Bitmap? = withContext(Dispatchers.IO) {
    when {
        uri.startsWith("data:image") -> {
            val base64 = uri.substringAfter("base64,", "")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            // Reducción de resolución:Primero use inJustDecodeBounds para obtener el tamaño,Luego presione inSampleSize para decodificar,Evite las imágenes de alta resolución OOM
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                // Calcule inSampleSize para que la longitud máxima del lado <= 2048px(Guardar en un álbum de fotos no requiere resolución original)
                val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 2048) sampleSize *= 2
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            }
        }
        uri.startsWith("http") -> {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            (result as? SuccessResult)?.drawable?.let { drawable ->
                val width = drawable.intrinsicWidth
                val height = drawable.intrinsicHeight
                // límite de tamaño:Reducir el tamaño del dibujo del lienzo proporcionalmente cuando supere los 2048 px,evitar OOM(Guardar en un álbum de fotos no requiere resolución original)
                val maxDim = maxOf(width, height)
                val scale = if (maxDim > 2048) 2048f / maxDim else 1f
                val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                val targetHeight = (height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                if (scale < 1f) canvas.scale(scale, scale)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
                bitmap
            }
        }
        else -> {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                // Reducción de resolución:Primero use inJustDecodeBounds para obtener el tamaño,Luego vuelva a abrir la transmisión y use inSampleSize para decodificar,Evite las imágenes de alta resolución OOM
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, boundsOptions)
                if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
                    null
                } else {
                    // Calcule inSampleSize para que la longitud máxima del lado <= 2048px(Guardar en un álbum de fotos no requiere resolución original)
                    val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 2048) sampleSize *= 2
                    // La primera decodificación consume la transmisión.,Es necesario volver a abrir InputStream para decodificar con inSampleSize
                    context.contentResolver.openInputStream(Uri.parse(uri))?.use { decodeInput ->
                        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                        BitmapFactory.decodeStream(decodeInput, null, decodeOptions)
                    }
                }
            }
        }
    }
}
