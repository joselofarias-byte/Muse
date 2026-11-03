package io.zer0.muse.data.sticker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * v1.95: Almacén de paquetes de expresión — Almacenamiento basado en archivos(no tocar Room/MuseDb).
 *
 * estructura de almacenamiento:
 *  - Archivos de imagen: `filesDir/stickers/<category>/<filename>`
 *  - archivo de manifiesto: `filesDir/stickers/manifest.json`(JSON serializado [StickerItem] lista)
 *
 * Puntos de diseño:
 *  - Al importar zip, presione dentro del zip**estructura de carpetas**como clasificación(como en zip `gato gato/001.png` subsumido"gato gato"Clasificación);
 *    Imágenes sin carpetas"Predeterminado"clasificación.
 *  - Sólo aceptar archivos de imagen(png/jpg/jpeg/gif/webp/bmp),Ignore archivos ruidosos como __MACOSX/.DS_Store.
 *  - Para leer y escribir listas [Mutex] proteger,IO en [Dispatchers.IO].
 *  - usar [AppJson] serialización(configurado ignoreUnknownKeys,Evolución del campo compatible).
 *
 * @param appContext Solicitud Context(usado para filesDir / contentResolver)
 */
class StickerLibraryRepository(private val appContext: Context) {

    /** stickers directorio raíz(`filesDir/stickers`). */
    private val rootDir: File get() = File(appContext.filesDir, "stickers").apply { if (!exists()) mkdirs() }

    /** archivo de manifiesto(`filesDir/stickers/manifest.json`). */
    private val manifestFile: File get() = File(rootDir, "manifest.json")

    /** Lista de mutex de lectura y escritura(Importación simultánea garantizada/Atomicidad de la eliminación). */
    private val manifestMutex = Mutex()

    /** Extensiones de imagen compatibles(minúscula). */
    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    // v1.117: Límite de volumen de importación,Bomba anti-ZIP / OOM(Alinear la política de restricción de DocumentParser)
    private val MAX_SINGLE_ENTRY_BYTES = 10L * 1024 * 1024 // imagen única 10MB
    private val MAX_TOTAL_IMPORT_BYTES = 200L * 1024 * 1024 // acumulativo 200MB
    private val MAX_ENTRY_COUNT = 1000 // Hasta 1000 archivos

    // ── publico API ──────────────────────────────────────────────────────────

    /** Listar todas las categorías(Ordenar por nombre,La biblioteca vacía devuelve una lista vacía). */
    suspend fun listCategories(): List<String> = withContext(Dispatchers.IO) {
        manifestMutex.withLock { readManifest() }.map { it.category }.distinct().sorted()
    }

    /**
     * lista de memes(Filtrar por categoría).
     *
     * @param category Filtro de clasificación;null o una cadena vacía para enumerar todos
     */
    suspend fun listStickers(category: String? = null): List<StickerItem> = withContext(Dispatchers.IO) {
        val all = manifestMutex.withLock { readManifest() }
        val filtered = if (category.isNullOrBlank()) all else all.filter { it.category == category }
        filtered.sortedWith(compareBy({ it.category }, { it.fileName }))
    }

    /**
     * v1.0.53: Identificar e importar automáticamente desde Uri — Admite archivos ZIP e imágenes individuales.
     *
     * Determine el tipo según la extensión de archivo del Uri:
     *  - .zip → llamar [importZip]
     *  - png/jpg/jpeg/gif/webp/bmp → llamar [importImage]
     *  - Otros → error de retorno
     *
     * Esta es la entrada unificada para la importación de usuarios.,La página de administración debería llamar a este método en lugar de llamarlo directamente importZip,
     * Para admitir la importación de una sola imagen y evitar problemas de filtrado de tipo MIME.
     *
     * @return Cantidad de importación;resultOf retornos en caso de falla Error
     */
    suspend fun importUri(uri: Uri, onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null): Result<Int> =
        withContext(Dispatchers.IO) {
            val displayName = queryDisplayName(uri)
            Logger.i("StickerLibraryRepository", "importUri: uri=$uri, displayName=$displayName")
            if (displayName.isNullOrBlank()) {
                Logger.w("StickerLibraryRepository", "importUri: No se puede obtener el nombre del archivo,Intente procesar por ZIP")
                return@withContext importZip(uri, onProgress)
            }
            val lowerName = displayName.lowercase()
            when {
                lowerName.endsWith(".zip") -> {
                    Logger.i("StickerLibraryRepository", "importUri: Reconocido como archivo ZIP,llamar importZip")
                    importZip(uri, onProgress)
                }
                isImageFile(displayName) -> {
                    Logger.i("StickerLibraryRepository", "importUri: Reconocer como una sola imagen,llamar importImage")
                    importImage(uri, displayName, onProgress)
                }
                else -> {
                    Logger.w("StickerLibraryRepository", "importUri: Tipos de archivos no compatibles: $displayName")
                    Result.Error("Tipos de archivos no compatibles: $displayName(Sólo admite paquetes comprimidos ZIP o imágenes png/jpg/jpeg/gif/webp/bmp)")
                }
            }
        }

    /**
     * v1.0.53: Importar una sola imagen a"Predeterminado"clasificación.
     *
     * @param uri fotos Uri
     * @param displayName nombre de archivo(Se utiliza para determinar la extensión.)
     * @return Cantidad de importación(1 o 0);resultOf retornos en caso de falla Error
     */
    suspend fun importImage(
        uri: Uri,
        displayName: String,
        onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null,
    ): Result<Int> = withContext(Dispatchers.IO) {
        resultOf {
            Logger.i("StickerLibraryRepository", "importImage: Comience a importar una sola imagen, uri=$uri, name=$displayName")
            val now = System.currentTimeMillis()
            val category = "Predeterminado"
            val targetDir = File(rootDir, category).apply { mkdirs() }

            // Agregue un sufijo uuid corto en caso de conflicto de nombre de archivo
            var finalName = displayName
            var targetFile = File(targetDir, finalName)
            if (targetFile.exists()) {
                val dotIdx = displayName.lastIndexOf('.')
                val base = if (dotIdx > 0) displayName.substring(0, dotIdx) else displayName
                val ext = if (dotIdx > 0) displayName.substring(dotIdx) else ""
                finalName = "${base}_${UUID.randomUUID().toString().take(6)}$ext"
                targetFile = File(targetDir, finalName)
                Logger.i("StickerLibraryRepository", "importImage: Conflicto de nombre de archivo,Cambiar nombre a $finalName")
            }

            // Lea la imagen y escríbala en el archivo de destino.
            appContext.contentResolver.openInputStream(uri).use { input ->
                if (input == null) error("No se puede abrir la imagen seleccionada")
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val fileSize = targetFile.length()
            Logger.i("StickerLibraryRepository", "importImage: La imagen ha sido escrita. ${targetFile.path} ($fileSize bytes)")

            // escribe una lista
            val item = StickerItem(
                id = UUID.randomUUID().toString(),
                category = category,
                fileName = finalName,
                relativePath = "stickers/$category/$finalName",
                addedAt = now,
            )
            manifestMutex.withLock {
                val current = readManifest().toMutableList()
                current.add(item)
                writeManifest(current)
            }
            Logger.i("StickerLibraryRepository", "importImage: Importación exitosa, id=${item.id}")
            1
        }.onError { msg, t ->
            Logger.w("StickerLibraryRepository", "importImage falló: $msg", t)
        }
    }

    /**
     * v1.0.53: Consultar el nombre del archivo para mostrar de Uri(Pase ContentResolver OPENABLE_COLUMNS).
     *
     * Se utiliza para determinar el tipo de archivo antes de importar(ZIP Todavía una imagen). Devuelve nulo para indicar que no se puede obtener el nombre del archivo
     * (Se ve comúnmente en algunos administradores de archivos que no devuelven la columna DISPLAY_NAME).
     */
    private fun queryDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            if (t is kotlin.coroutines.cancellation.CancellationException) throw t
            Logger.w("StickerLibraryRepository", "queryDisplayName falló: ${t.message}")
            null
        }
    }

    /**
     * Lea el zip de Uri y extráigalo al directorio de pegatinas,Ordene automáticamente por estructura de carpetas dentro del zip.
     *
     * Reglas de descompresión:
     *  - zip dentro `gato gato/001.png` subsumido"gato gato"Clasificación
     *  - zip No hay fotos en la carpeta.(Como por ejemplo `001.png`)subsumido"Predeterminado"Clasificación
     *  - Sólo aceptar archivos de imagen(png/jpg/jpeg/gif/webp/bmp),Ignorar otros archivos(__MACOSX/.DS_Store espera)
     *  - Cuando el nombre del archivo contiene caracteres chinos:Pruebe UTF-8 primero,Aparecen personajes de reemplazo(personajes confusos)Presione GBK nuevamente para volver a intentarlo.
     *
     * @return Cantidad de importación;resultOf retornos en caso de falla Error
     */
    suspend fun importZip(uri: Uri, onProgress: ((phase: String, done: Int, total: Int?) -> Unit)? = null): Result<Int> =
        withContext(Dispatchers.IO) {
            resultOf {
                Logger.i("StickerLibraryRepository", "importZip: Empezar a importar, uri=$uri")
                val imported = mutableListOf<StickerItem>()
                val now = System.currentTimeMillis()
                // v1.0.52: Cambiar a la ubicación de preparación del streaming — Escribir en el directorio temporal inmediatamente después de leer una entrada,
                // Sólo se retienen en la memoria los bytes de una única imagen.(≤10MB),Evite paquetes grandes que almacenen todos los ByteArrays de imágenes en la memoria, lo que provocará OOM.
                // Después de todo el éxito, mueva los átomos al directorio oficial + escriba una lista;Limpieza si algún paso falla staging,No queda ningún trabajo en progreso.
                val stagingDir = File(rootDir, ".staging_${UUID.randomUUID().toString().take(8)}")
                stagingDir.mkdirs()
                // Cada entrada de puesta en escena:Clasificación + nombre de archivo final + nombre de archivo provisional
                val pendingEntries = mutableListOf<PendingEntry>()
                // v1.117: tamaño acumulado/Contador de cantidades,Bomba anti-ZIP
                var totalBytes = 0L

                try {
                    val input = appContext.contentResolver.openInputStream(uri)
                    if (input == null) {
                        Logger.w("StickerLibraryRepository", "importZip: ContentResolver Regresar null InputStream")
                        error("El archivo seleccionado no se puede abrir")
                    }
                    Logger.i("StickerLibraryRepository", "importZip: InputStream Abierto,Comience a leer entradas ZIP")
                    input.use { stream ->
                        // v1.112 (F4): Utilice el juego de caracteres GBK para construir ZipInputStream para corregir nombres de archivos chinos confusos.
                        // ZIP normativo:entry La bandera EFS de(bit 11)=1 Cuando ZipInputStream ignora la coerción del conjunto de caracteres entrante UTF-8;
                        // EFS=0 Se utiliza el juego de caracteres pasado.Windows herramientas chinas(tan estresante/2345/WinRAR versión china)generado zip
                        // normalmente EFS=0 Y el nombre del archivo está codificado en GBK.,GBK se puede decodificar correctamente;Estándar UTF-8 zip(EFS=1)Sigo usando UTF-8.
                        ZipInputStream(stream, Charset.forName("GBK")).use { zis ->
                            var entry: ZipEntry? = zis.nextEntry
                            var entryCount = 0
                            while (entry != null) {
                                entryCount++
                                // v1.0.54: Devolución de llamada del progreso de la fase de descompresión(total desconocido,contar primero)
                                if (entryCount % 10 == 0) {
                                    onProgress?.invoke("Descomprimiendo", entryCount, null)
                                }
                                if (!entry.isDirectory) {
                                    val rawName = entry.name
                                    // Omitir el directorio del sistema macOS y el ruido .DS_Store
                                    if (rawName.contains("__MACOSX") || rawName.endsWith(".DS_Store")) {
                                        if (entryCount % 100 == 0) {
                                            Logger.d(
                                                "StickerLibraryRepository",
                                                "importZip: saltado $entryCount elementos de ruido(Ejemplo: $rawName)",
                                            )
                                        }
                                        zis.closeEntry()
                                        entry = zis.nextEntry
                                        continue
                                    }
                                    // Analizar categorías y nombres de archivos
                                    val (category, fileName) = parseCategoryAndName(rawName)
                                    if (fileName != null && isImageFile(fileName)) {
                                        if (entryCount % 100 == 0) {
                                            Logger.d(
                                                "StickerLibraryRepository",
                                                "importZip: Encontrado $entryCount artículos,imagen actual: $rawName",
                                            )
                                        }
                                        // v1.117: Límite de cantidad
                                        if (pendingEntries.size >= MAX_ENTRY_COUNT) {
                                            error("La cantidad de imágenes en el paquete comprimido excede el límite $MAX_ENTRY_COUNT,Importación cancelada")
                                        }
                                        // v1.117: Leer con límite de tamaño,Entrada única anti-ZIP bomba OOM
                                        val bytes = readZipEntryWithLimit(zis, MAX_SINGLE_ENTRY_BYTES, rawName)
                                        totalBytes += bytes.size
                                        if (totalBytes > MAX_TOTAL_IMPORT_BYTES) {
                                            error("El volumen acumulado del paquete comprimido excede el límite. ${MAX_TOTAL_IMPORT_BYTES / 1024 / 1024}MB,Importación cancelada")
                                        }
                                        // Escriba el archivo temporal provisional inmediatamente,bytes puede ser GC,pico de memoria = imagen única
                                        val stagingFile = File(stagingDir, "${pendingEntries.size}_$fileName")
                                        stagingFile.outputStream().use { it.write(bytes) }
                                        pendingEntries.add(PendingEntry(category, fileName, stagingFile))
                                    } else {
                                        if (entryCount % 100 == 0) {
                                            Logger.d(
                                                "StickerLibraryRepository",
                                                "importZip: saltado $entryCount artículos(Actualmente no es una imagen: $rawName)",
                                            )
                                        }
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                            Logger.i("StickerLibraryRepository", "importZip: ZIP Lectura completada,total $entryCount artículos,entre ellos ${pendingEntries.size} fotos")
                        }
                    }

                    if (pendingEntries.isEmpty()) error("Archivo de imagen no encontrado en el paquete comprimido(png/jpg/jpeg/gif/webp/bmp)")

                    Logger.i("StickerLibraryRepository", "importZip: Comience a escribir el catálogo oficial y el manifiesto.")
                    // staging → Catálogo Oficial + Lista de Escritura(Completado dentro del bloqueo,Lanzar una excepción en caso de falla y finalmente limpiar)
                    manifestMutex.withLock {
                        val current = readManifest().toMutableList()
                        for ((idx, pe) in pendingEntries.withIndex()) {
                            // v1.0.54: Progreso de la fase de escritura(Se conoce el numero total)
                            if (idx % 10 == 0) {
                                onProgress?.invoke("escribiendo", idx, pendingEntries.size)
                            }
                            // Agregue un sufijo uuid corto en caso de conflicto de nombre de archivo,evitar sobrescribir
                            val targetDir = File(rootDir, pe.category).apply { mkdirs() }
                            var finalName = pe.fileName
                            var targetFile = File(targetDir, finalName)
                            // v1.113: Zip Slip protección — Asegúrese de que la ruta de destino de descompresión esté dentro de rootDir
                            val canonicalTarget = targetFile.canonicalPath
                            val canonicalRoot = rootDir.canonicalPath
                            if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                                Logger.w("StickerLibraryRepository", "Saltar entradas de cruce de ruta: ${pe.category}/${pe.fileName} -> $canonicalTarget")
                                continue
                            }
                            if (targetFile.exists()) {
                                val dotIdx = pe.fileName.lastIndexOf('.')
                                val base = if (dotIdx > 0) pe.fileName.substring(0, dotIdx) else pe.fileName
                                val ext = if (dotIdx > 0) pe.fileName.substring(dotIdx) else ""
                                finalName = "${base}_${UUID.randomUUID().toString().take(6)}$ext"
                                targetFile = File(targetDir, finalName)
                            }
                            // staging Mover archivos al directorio oficial(Cambiar el nombre de la misma partición de forma atómica y sin copiar.)
                            if (!pe.stagingFile.renameTo(targetFile)) {
                                // rename falló(raro),Copia alternativa
                                pe.stagingFile.copyTo(targetFile, overwrite = false)
                            }
                            val item = StickerItem(
                                id = UUID.randomUUID().toString(),
                                category = pe.category,
                                fileName = finalName,
                                relativePath = "stickers/${pe.category}/$finalName",
                                addedAt = now,
                            )
                            current.add(item)
                            imported.add(item)
                        }
                        writeManifest(current)
                    }
                    Logger.i("StickerLibraryRepository", "importZip: Importación completada,total ${imported.size} fotos")
                } finally {
                    // Sin importar el éxito o el fracaso,Limpiar directorio de preparación(vacío en el éxito,El fracaso contiene productos semiacabados.)
                    resultOf { stagingDir.deleteRecursively() }
                        .onError { msg, t -> Logger.w("StickerLibraryRepository", "No se pudo limpiar el directorio de preparación: $msg", t) }
                }
                imported.size
            }.onError { msg, t ->
                Logger.w("StickerLibraryRepository", "importZip falló: $msg", t)
            }
        }

    /**
     * Eliminar un solo paquete de emoticones(Eliminar archivo físico + eliminar de la lista).
     * @return true Indica una eliminación exitosa;false Indica que no se encontró la entrada correspondiente
     */
    suspend fun deleteSticker(id: String): Boolean = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val target = current.firstOrNull { it.id == id } ?: return@withLock false
            // Eliminar archivos físicos(Solo falla al iniciar sesión,No bloquear actualizaciones de la lista)
            resultOf {
                val file = File(appContext.filesDir, target.relativePath)
                if (file.exists()) file.delete()
            }.onError { msg, t -> Logger.w("StickerLibraryRepository", "No se pudo eliminar el archivo de emoticones: $msg", t) }
            current.remove(target)
            writeManifest(current)
            true
        }
    }

    /**
     * v1.112 (F1-F2): Eliminar emoticonos en lotes(Eliminar archivo físico + eliminar de la lista).
     *
     * @param ids Lista de identificadores de emoticones que se eliminarán
     * @return Número real de eliminaciones
     */
    suspend fun deleteStickers(ids: Set<String>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val toRemove = current.filter { it.id in ids }
            if (toRemove.isEmpty()) return@withLock 0
            // Eliminar archivos físicos uno por uno(Solo falla al iniciar sesión)
            for (item in toRemove) {
                resultOf {
                    val file = File(appContext.filesDir, item.relativePath)
                    if (file.exists()) file.delete()
                }.onError { msg, t -> Logger.w("StickerLibraryRepository", "No se pudieron eliminar archivos de emoticones en lotes: $msg", t) }
            }
            current.removeAll(toRemove)
            writeManifest(current)
            toRemove.size
        }
    }

    /**
     * v1.112 (F1-F2): Elimina todos los emoticonos de la categoría especificada.
     *
     * @param category Nombre de categoría
     * @return Número real de eliminaciones
     */
    suspend fun deleteCategory(category: String): Int = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest().toMutableList()
            val toRemove = current.filter { it.category == category }
            if (toRemove.isEmpty()) return@withContext 0
            for (item in toRemove) {
                resultOf {
                    val file = File(appContext.filesDir, item.relativePath)
                    if (file.exists()) file.delete()
                }.onError { msg, t -> Logger.w("StickerLibraryRepository", "No se pudieron eliminar los archivos del paquete de emoticones clasificados: $msg", t) }
            }
            // Eliminar categorías vacías
            resultOf {
                val dir = File(rootDir, category)
                if (dir.exists() && dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
            }
            current.removeAll(toRemove)
            writeManifest(current)
            toRemove.size
        }
    }

    /**
     * v1.112 (F1-F2): Borrar todos los emoticones.
     *
     * @return Número real de eliminaciones
     */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        manifestMutex.withLock {
            val current = readManifest()
            if (current.isEmpty()) return@withContext 0
            // Eliminar todo el directorio de stickers(recursividad)
            resultOf {
                if (rootDir.exists()) rootDir.deleteRecursively()
            }.onError { msg, t -> Logger.w("StickerLibraryRepository", "No se pudo borrar el directorio del paquete de emoticones: $msg", t) }
            writeManifest(emptyList())
            current.size
        }
    }

    /** Obtenga el archivo del paquete de emoticonos según la identificación(El archivo no existe regresar null). */
    suspend fun getStickerFile(id: String): File? = withContext(Dispatchers.IO) {
        val item = manifestMutex.withLock { readManifest() }.firstOrNull { it.id == id } ?: return@withContext null
        val file = File(appContext.filesDir, item.relativePath)
        if (file.exists()) file else null
    }

    /**
     * Obtener archivos según la ruta relativa(Para herramientas para llamar rutas de lectura directa,No comprueba si está en la lista.).
     * La persona que llama debe asegurarse de que la ruta sea legal.
     */
    fun getStickerFileByPath(relativePath: String): File = File(appContext.filesDir, relativePath)

    // ── v2.x: Soporte de enlace de etiqueta(dentro del mensaje [[sticker:Nombre de categoría]] Para análisis) ─────────────────

    /** Memoria caché de instantáneas(Experiencia en operaciones de escritura [writeManifest] Automáticamente inválido). */
    @Volatile
    private var snapshotCache: StickerSnapshot? = null

    /** Leer instantánea de clasificación(Con memoria caché;Permanente después de la primera lectura,Automáticamente inválido durante la operación de escritura). */
    suspend fun snapshot(): StickerSnapshot = withContext(Dispatchers.IO) {
        snapshotCache?.let { return@withContext it }
        manifestMutex.withLock {
            snapshotCache?.let { return@withLock it }
            val items = readManifest()
            val snap =
                StickerSnapshot(
                    categories = items.map { it.category }.distinct().sorted(),
                    byCategory = items.groupBy { it.category },
                )
            snapshotCache = snap
            snap
        }
    }

    /** Resumen de clasificación(Nombre de categoría → Número de hojas),Para la generación de avisos del sistema"Categorías disponibles"Lista de verificación. */
    suspend fun categorySummary(): List<Pair<String, Int>> {
        val snap = snapshot()
        return snap.categories.map { it to (snap.byCategory[it]?.size ?: 0) }
    }

    /**
     * Análisis tolerante a fallos de nombres de categorías(Los nombres de clasificación generados por el modelo pueden ser inexactos).
     *
     * orden coincidente:preciso → Ignorar mayúsculas y espacios en blanco → editar distancia ≤ 1 → Sólo incluido.
     * Retorno de todos los fracasos null(prensa de llamada"Ignora esta etiqueta"Proceso).
     */
    suspend fun resolveCategory(raw: String): String? = matchStickerCategory(snapshot().categories, raw)

    /**
     * Semillas seleccionadas al azar:mismo [seed] Regreso estable a la misma imagen.(Reingresar a la sesión/La reorganización no salta).
     * no suspend(Basado en la instantánea leída,Renderizar la capa primero [snapshot] llamar de nuevo).
     */
    fun pickSticker(snap: StickerSnapshot, category: String, seed: Long): StickerItem? {
        val list = snap.byCategory[category] ?: return null
        if (list.isEmpty()) return null
        val idx = Math.floorMod(seed, list.size.toLong()).toInt()
        return list[idx]
    }

    // ── auxiliar interno ─────────────────────────────────────────────────────────

    /**
     * Artículos descomprimidos que se colocarán(Categoría + nombre de archivo + archivo temporal provisional).
     *
     * v1.0.52: Se cambió de matriz de bytes a referencia de archivo provisional — El contenido de la imagen se coloca en el disco durante la lectura.,
     * La memoria no se acumula.,Evite los grandes memes OOM.
     */
    private data class PendingEntry(
        val category: String,
        val fileName: String,
        val stagingFile: File,
    )

    /** Leer archivo de manifiesto(La persona que llama debe mantener el candado). La lista no existe o el análisis falla y se devuelve una lista vacía. */
    private fun readManifest(): List<StickerItem> {
        if (!manifestFile.exists()) return emptyList()
        return resultOf {
            AppJson.decodeFromString(ListSerializer(StickerItem.serializer()), manifestFile.readText())
        }.onError { msg, t ->
            Logger.w("StickerLibraryRepository", "Falló el análisis del manifiesto,lista vacía alternativa: $msg", t)
        }.getOrNull() ?: emptyList()
    }

    /** Escribir archivo de manifiesto(La persona que llama debe mantener el candado).v1.117: Escritura atómica(temp+rename)Evite escribir una lista de daños por accidente a mitad de camino. */
    private fun writeManifest(items: List<StickerItem>) {
        // v2.x: cambios de lista → Invalidación de caché de instantáneas de categoría(Enlace de etiqueta leído)
        snapshotCache = null
        if (!rootDir.exists()) rootDir.mkdirs()
        val json = AppJson.encodeToString(ListSerializer(StickerItem.serializer()), items)
        // Escritura atómica:Escribe .tmp primero y luego rename,Evite que writeText falle a mitad de camino, dejando la mitad del JSON y provocando la pérdida de todos los metadatos.
        val tmpFile = File(rootDir, "manifest.json.tmp")
        try {
            tmpFile.writeText(json)
            if (!tmpFile.renameTo(manifestFile)) {
                // rename caso raro de fracaso(La misma partición generalmente no fallará),Retrocede y escribe directamente
                manifestFile.writeText(json)
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
    }

    /**
     * Analice categorías y nombres de archivos a partir de nombres de entradas zip.
     *
     * Reglas de clasificación(Tome el último nombre del directorio en la ruta como categoría):
     *  - "Emoticones/feliz/001.png" → ("feliz", "001.png")  Se omiten los directorios de empaquetado de nivel superior
     *  - "feliz/001.png" → ("feliz", "001.png")
     *  - "gato gato/diariamente/001.png" → ("diariamente", "001.png")
     *  - "001.png" → ("Predeterminado", "001.png") archivo del directorio raíz"Predeterminado"
     */
    private fun parseCategoryAndName(rawName: String): Pair<String, String?> {
        // v1.113: Rechace rápidamente rutas que contengan ..(protegerse contra Zip Slip)
        if (rawName.contains("..")) {
            return "Predeterminado" to null // La persona que llama omitirá el nombre de archivo nulo que devuelve
        }
        // Separador de ruta uniforme(zip Para uso estándar "/",Algunas herramientas pueden usar "\\")
        val normalized = rawName.replace('\\', '/')
        // Prensa / segmento,Filtrar segmentos vacíos(como al principio/barra diagonal)
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "Predeterminado" to null
        val fileName = parts.last().trim()
        if (parts.size == 1) {
            // archivo directo del directorio raíz → Clasificación predeterminada
            return "Predeterminado" to fileName.ifBlank { null }
        }
        // Tome el último nombre del directorio como categoría:Saltar directorio de embalaje de nivel superior(Como por ejemplo "Emoticones/"),
        // Los nombres de categorías son etiquetas semánticas.(feliz/triste/enojado…),En base a esto, el modelo determina qué tipo de emoticón enviar.
        val category = parts[parts.size - 2].trim()
        return (if (category.isBlank()) "Predeterminado" else category) to fileName.ifBlank { null }
    }

    /** Determinar si el nombre del archivo es un tipo de imagen compatible(Las extensiones no distinguen entre mayúsculas y minúsculas). */
    private fun isImageFile(fileName: String): Boolean {
        val dotIdx = fileName.lastIndexOf('.')
        if (dotIdx < 0) return false
        val ext = fileName.substring(dotIdx + 1).lowercase()
        return ext in imageExtensions
    }
}

/**
 * Modelo de datos de elementos meme.
 *
 * @param id UUID(identificador único)
 * @param category Clasificación(Obtener del nombre de la carpeta del paquete comprimido,Si no hay ninguna carpeta, use"Predeterminado")
 * @param fileName nombre de archivo
 * @param relativePath ruta relativa,Formato "stickers/<category>/<fileName>"
 * @param addedAt Importar marca de tiempo
 */
@Serializable
data class StickerItem(
    val id: String,
    val category: String,
    val fileName: String,
    val relativePath: String,
    val addedAt: Long,
)

/**
 * v2.x: Instantánea de clasificación de la biblioteca de emojis(Para leer enlaces de etiquetas).
 *
 * @param categories Todos los nombres de categorías(ordenar)
 * @param byCategory Clasificación → Lista de entrada
 */
data class StickerSnapshot(
    val categories: List<String>,
    val byCategory: Map<String, List<StickerItem>>,
) {
    val isEmpty: Boolean get() = categories.isEmpty()
}

/**
 * v2.x: Coincidencia tolerante a fallos de nombre de categoría —— Alineación difusa de los nombres de categorías generados por el modelo con las categorías de la biblioteca.
 *
 * orden coincidente:preciso → normalización(caso/en blanco) → editar distancia ≤ 1(nombre corto solamente) → Sólo incluido.
 * Retorno de todos los fracasos null(prensa de llamada"Ignora esta etiqueta"Proceso,Prefiero tener menos pelo que pelo rebelde).
 */
internal fun matchStickerCategory(candidates: List<String>, raw: String): String? {
    val name = raw.trim()
    if (name.isEmpty() || candidates.isEmpty()) return null
    // 1. preciso
    candidates.firstOrNull { it == name }?.let { return it }
    // 2. normalización(Ignorar mayúsculas y espacios en blanco)
    val norm = name.lowercase().filterNot { it.isWhitespace() }
    candidates.firstOrNull { it.lowercase().filterNot { c -> c.isWhitespace() } == norm }?.let { return it }
    // 3. editar distancia ≤ 1(Sólo nombres cortos y de igual longitud. —— Restricción de igual longitud"feliz"No coincide"infeliz"diferencia de inserción)
    if (name.length <= 8) {
        candidates.firstOrNull {
            it.length == name.length && levenshteinDistance(it, name) <= 1
        }?.let { return it }
    }
    // 4. solo contiene(Sólo el nombre contiene la dirección del candidato:El modelo alarga un poco el nombre de la categoría.;
    //    revertir(candidate contiene name)Desactivar —— "feliz" No coincide "infeliz" riesgo antisentido)
    val contained = candidates.filter { name.contains(it, ignoreCase = true) }
    if (contained.size == 1) return contained[0]
    return null
}

/** Distancia estándar de Levenshtein(cuerda corta DP). */
internal fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        curr.copyInto(prev)
    }
    return prev[b.length]
}
