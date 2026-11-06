package io.zer0.muse.data.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * Modelo de datos del artículo de portada.
 *
 * @param id UUID(identificador único)
 * @param fileName Nombre del archivo de disco,Como por ejemplo "cover_abc123.jpg"
 * @param source Fuente: "local"(Importación de álbum) / "generated"(AI generar) / "imported"
 * @param width Ancho de píxel(Grabar al dibujar;Importado como ancho comprimido)
 * @param height Altura de píxel
 * @param addedAt Marca de tiempo entrante
 */
@Serializable
data class CoverItem(
    val id: String,
    val fileName: String,
    val source: String,
    val width: Int = 0,
    val height: Int = 0,
    val addedAt: Long,
)

/**
 * v1.0.53: Portada Galería Almacén(Biblioteca de portadas para el flujo de trabajo de portadas).
 *
 * estructura de almacenamiento:
 *  - Archivos de imagen: `filesDir/covers/<fileName>`(unificar JPEG,lado más largo ≤ [MAX_DIMENSION])
 *  - archivo de manifiesto: `filesDir/covers/manifest.json`(JSON serializado [CoverItem] lista)
 *
 * Puntos de diseño:
 *  - Para leer y escribir listas [Mutex] proteger,IO en [Dispatchers.IO]
 *  - importar/Generar compresión unificada:El lado más largo se reduce a [MAX_DIMENSION](1600),JPEG calidad 85
 *  - Para imágenes muy grandes, utilice primero inSampleSize para evitar la reducción de resolución. OOM
 *  - manifest Escritura atómica(temp + rename),Lista de daños por colisión antiescritura
 *
 * @param appContext Solicitud Context(usado para filesDir)
 */
class CoverLibraryRepository(private val appContext: Context) {

    /** covers directorio raíz(`filesDir/covers`). */
    private val rootDir: File get() = File(appContext.filesDir, "covers").apply { if (!exists()) mkdirs() }

    /** archivo de manifiesto(`filesDir/covers/manifest.json`). */
    private val manifestFile: File get() = File(rootDir, "manifest.json")

    /** Lista de mutex de lectura y escritura(Importación simultánea garantizada/Eliminar/atomicidad de generación). */
    private val manifestMutex = Mutex()

    companion object {
        private const val TAG = "CoverLibraryRepository"

        /** El límite superior del lado más largo de la cubierta.(Píxel). */
        const val MAX_DIMENSION = 1600

        /** JPEG Calidad de compresión. */
        const val JPEG_QUALITY = 85

        /** Apunte al borde de la muestra al decodificar(exceder este tamaño primero inSampleSize). */
        private const val SAMPLE_THRESHOLD = 4096
    }

    /** Listar todas las portadas,En orden cronológico inverso. */
    suspend fun listCovers(): List<CoverItem> = withContext(Dispatchers.IO) {
        manifestMutex.withLock { readManifest() }.sortedByDescending { it.addedAt }
    }

    /**
     * Importar una imagen de SAF Uri como portada(Comprimir hacia el lado más largo [MAX_DIMENSION],JPEG).
     *
     * @param uri contenido Uri(álbum de fotos/selector de archivos)
     * @return importado [CoverItem];Retorno del fracaso Error
     */
    suspend fun importFromUri(uri: Uri): Result<CoverItem> = withContext(Dispatchers.IO) {
        resultOf {
            val bitmap = decodeSampledBitmap(uri)
                ?: error("No se puede decodificar la imagen seleccionada")
            val (w, h) = bitmap.width to bitmap.height
            val fileName = "cover_${UUID.randomUUID().toString().take(8)}.jpg"
            val targetFile = File(rootDir, fileName)
            targetFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (!bitmap.isRecycled) bitmap.recycle()

            val item = CoverItem(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                source = "local",
                width = w,
                height = h,
                addedAt = System.currentTimeMillis(),
            )
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                current.add(item)
                writeManifest(current)
            }
            item
        }.onError { msg, t ->
            Logger.w(TAG, "No se pudo importar la portada: $msg", t)
        }
    }

    /**
     * Obtener los resultados del gráfico.(ruta del archivo local)Regístrese como portada.
     *
     * El producto del dibujo suele tener un tamaño aceptable.,Aquí todavía hacemos una bolsa de compresión.(Borde anti-extralargo),Y cópielo en el directorio de portadas.
     *
     * @param file Archivos locales del producto de dibujo.
     * @param width ancho de píxel original(Metadatos devueltos por imagen sin formato)
     * @param height altura de píxel original
     * @return después del registro [CoverItem];Retorno del fracaso Error
     */
    suspend fun registerGenerated(file: File, width: Int, height: Int): Result<CoverItem> = withContext(Dispatchers.IO) {
        resultOf {
            val fileName = "cover_${UUID.randomUUID().toString().take(8)}.jpg"
            val targetFile = File(rootDir, fileName)
            // Copiar(Si el lado es demasiado largo, comprímalo.;De lo contrario, copie los bytes directamente.)
            if (maxOf(width, height) <= MAX_DIMENSION) {
                file.inputStream().use { input -> targetFile.outputStream().use { output -> input.copyTo(output) } }
            } else {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: error("No se puede decodificar el resultado de la imagen sin procesar")
                targetFile.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }

            val item = CoverItem(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                source = "generated",
                width = width,
                height = height,
                addedAt = System.currentTimeMillis(),
            )
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                current.add(item)
                writeManifest(current)
            }
            item
        }.onError { msg, t ->
            Logger.w(TAG, "No se pudo registrar y generar cobertura: $msg", t)
        }
    }

    /** Quitar la cubierta(Archivo físico + eliminar del inventario). */
    suspend fun deleteCover(id: String): Boolean = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val target = current.firstOrNull { it.id == id } ?: return@withLock false
            resultOf {
                val file = File(rootDir, target.fileName)
                if (file.exists()) file.delete()
            }.onError { msg, t -> Logger.w(TAG, "No se pudo eliminar el archivo de portada: $msg", t) }
            current.remove(target)
            writeManifest(current)
            true
        }
    }

    /** Obtener archivo de portada(no existe retorno null). */
    fun getCoverFile(item: CoverItem): File = File(rootDir, item.fileName)

    /** Buscar entradas por ID(no existe retorno null). */
    suspend fun getById(id: String): CoverItem? = withContext(Dispatchers.IO) {
        manifestMutex.withLock { readManifest() }.firstOrNull { it.id == id }
    }

    // ── implementación interna ─────────────────────────────────────────────────────────

    /**
     * Decodificar y comprimir imágenes:Primero reduzca aproximadamente inSampleSize(>4096 tiempo),Luego escale exactamente al lado más largo 1600.
     */
    private fun decodeSampledBitmap(uri: Uri): Bitmap? {
        // primera decodificación:tamaño de solo lectura
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Calcular la frecuencia de muestreo:lado más largo > SAMPLE_THRESHOLD Reducir aproximadamente proporcionalmente
        var sampleSize = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sampleSize * 2) >= SAMPLE_THRESHOLD) sampleSize *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val full = appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: return null

        // escalado preciso:lado más largo > MAX_DIMENSION lapso de tiempo
        val w = full.width
        val h = full.height
        if (maxOf(w, h) <= MAX_DIMENSION) return full

        val scale = MAX_DIMENSION.toFloat() / maxOf(w, h)
        val scaled = Bitmap.createScaledBitmap(
            full,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== full && !full.isRecycled) full.recycle()
        return scaled
    }

    /** Leer archivo de manifiesto(La persona que llama debe mantener el candado). La lista no existe o el análisis falla y se devuelve una lista vacía. */
    private fun readManifest(): List<CoverItem> {
        if (!manifestFile.exists()) return emptyList()
        return resultOf {
            AppJson.decodeFromString(ListSerializer(CoverItem.serializer()), manifestFile.readText())
        }.onError { msg, t ->
            Logger.w(TAG, "Error al analizar la lista de portada,lista vacía alternativa: $msg", t)
        }.getOrNull() ?: emptyList()
    }

    /** Escribir archivo de manifiesto(La persona que llama debe mantener el candado). Escritura atómica(temp+rename). */
    private fun writeManifest(items: List<CoverItem>) {
        if (!rootDir.exists()) rootDir.mkdirs()
        val json = AppJson.encodeToString(ListSerializer(CoverItem.serializer()), items)
        val tmpFile = File(rootDir, "manifest.json.tmp")
        try {
            tmpFile.writeText(json)
            if (!tmpFile.renameTo(manifestFile)) {
                manifestFile.writeText(json)
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }
}
