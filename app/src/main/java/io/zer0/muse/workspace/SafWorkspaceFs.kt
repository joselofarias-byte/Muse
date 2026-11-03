package io.zer0.muse.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Un sistema de archivos montado en el directorio autorizado del sistema.
 *
 * Una vez almacenada la partición del sistema, no se puede acceder a los directorios "Álbum" y "Descargar". [java.io.File],
 * Solo puede obtener un URI de directorio y luego usar el identificador del documento para acceder a él capa por capa. El límite de seguridad está garantizado por esa propia autorización:
 * Todas las búsquedas comienzan desde la raíz de autorización y profundizan sección por sección. Los caminos absolutos no están detallados, por lo que no hay cruce de límites.
 *
 * Para escribir archivos `wa` Adición de modo; si algunos proveedores no lo admiten, volverá a "leer el monto total y luego volver a escribirlo".
 *
 * @param context Contexto de la aplicación, utilizado para obtener ContentResolver
 * @param treeUri Persistencia del directorio de autorización de usuarios. URI
 */
@Suppress("TooManyFunctions")
class SafWorkspaceFs(private val context: Context, treeUri: Uri) : WorkspaceFs {

    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw WorkspaceIoException("No se puede abrir el directorio de autorización")

    /** Profundice sección por sección hasta llegar al documento de destino. */
    private fun find(relative: String): DocumentFile? {
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        var current: DocumentFile = root
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /** Busque el directorio principal y el nombre de la última sección del documento de destino. */
    private fun findParent(relative: String): Pair<DocumentFile, String>? {
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        var current: DocumentFile = root
        for (segment in segments.dropLast(1)) {
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current to segments.last()
    }

    /** Cree directorios nivel por nivel y regrese al directorio final. */
    private fun ensureDirectory(relative: String): DocumentFile? {
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        var current: DocumentFile = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment) ?: return null
                existing.isDirectory -> existing
                else -> return null
            }
        }
        return current
    }

    override suspend fun list(relative: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = find(relative) ?: throw WorkspaceIoException("El directorio no existe: $relative")
        if (!dir.isDirectory) throw WorkspaceIoException("No es una carpeta: $relative")
        val base = relative.trim('/')
        dir.listFiles().map { doc ->
            val name = doc.name ?: "(Sin nombre)"
            FsEntry(
                name = name,
                relativePath = if (base.isEmpty()) name else "$base/$name",
                isDirectory = doc.isDirectory,
                size = if (doc.isFile) doc.length() else 0L,
                lastModified = doc.lastModified(),
            )
        }.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun exists(relative: String): Boolean = withContext(Dispatchers.IO) {
        if (relative.isBlank()) root.exists() else find(relative)?.exists() == true
    }

    override suspend fun isDirectory(relative: String): Boolean = withContext(Dispatchers.IO) {
        if (relative.isBlank()) root.isDirectory else find(relative)?.isDirectory == true
    }

    override suspend fun size(relative: String): Long = withContext(Dispatchers.IO) {
        find(relative)?.takeIf { it.isFile }?.length() ?: 0L
    }

    override suspend fun readText(relative: String): String = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: throw WorkspaceIoException("El archivo no existe: $relative")
        if (!doc.isFile) throw WorkspaceIoException("no es un archivo: $relative")
        workspaceIo("leer") {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } ?: throw WorkspaceIoException("No se puede abrir el archivo: $relative")
        }
    }

    override suspend fun readBytes(relative: String): ByteArray? = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: return@withContext null
        if (!doc.isFile) return@withContext null
        runCatching {
            context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    override suspend fun readTextRange(relative: String, offsetChars: Int, lengthChars: Int): String = withContext(Dispatchers.IO) {
        // La lectura paginada lee el archivo completo en el directorio autorizado y luego lo divide: los archivos aquí suelen ser documentos y descripciones de imágenes.
        // Las lecturas que exceden el límite superior son interceptadas por la capa superior y los archivos grandes no se arrastrarán a la memoria.
        val text = readText(relative)
        val start = offsetChars.coerceIn(0, text.length)
        val end = (start + lengthChars.coerceAtLeast(1)).coerceAtMost(text.length)
        text.substring(start, end)
    }

    override suspend fun writeText(relative: String, content: String, append: Boolean): Unit = withContext(Dispatchers.IO) {
        val parentAndName = findParent(relative) ?: throw WorkspaceIoException("Camino ilegal: $relative")
        val (parent, name) = parentAndName
        val existing = parent.findFile(name)
        val target = existing ?: parent.createFile(MIME_BINARY, name)
            ?: throw WorkspaceIoException("No se puede crear el archivo: $relative")
        workspaceIo("escribir") {
            if (append && existing != null) {
                appendBytes(target, content.toByteArray(Charsets.UTF_8))
            } else {
                context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw WorkspaceIoException("incapaz de escribir: $relative")
            }
        }
    }

    override suspend fun writeStream(relative: String, source: InputStream, maxBytes: Long): Long = withContext(Dispatchers.IO) {
        val parentAndName = findParent(relative) ?: throw WorkspaceIoException("Camino ilegal: $relative")
        val (parent, name) = parentAndName
        val target = parent.findFile(name) ?: parent.createFile(MIME_BINARY, name)
            ?: throw WorkspaceIoException("No se puede crear el archivo: $relative")
        var written = 0L
        val result = resultOf {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = source.read(buffer)
                while (read >= 0) {
                    if (read > 0) {
                        written += read
                        if (written > maxBytes) throw WorkspaceIoException("Archivo demasiado grande")
                        out.write(buffer, 0, read)
                    }
                    read = source.read(buffer)
                }
            } ?: throw WorkspaceIoException("incapaz de escribir: $relative")
        }
        if (result is Result.Error) {
            runCatching { target.delete() }
            val cause = result.throwable
            throw if (cause is WorkspaceIoException) cause else WorkspaceIoException("Error de escritura: ${result.message}", cause)
        }
        written
    }

    /** Escritura adicional: prioridad `wa` Abra, lea la sobrescritura completa si el proveedor no lo admite. */
    private fun appendBytes(target: DocumentFile, bytes: ByteArray) {
        val appended = runCatching {
            context.contentResolver.openOutputStream(target.uri, "wa")?.use { out ->
                out.write(bytes)
                true
            } ?: false
        }.getOrDefault(false)
        if (appended) return
        val old = readBytesSync(target) ?: ByteArray(0)
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
            out.write(old)
            out.write(bytes)
        } ?: throw WorkspaceIoException("No se puede agregar escritura")
    }

    private fun readBytesSync(doc: DocumentFile): ByteArray? = runCatching {
        context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
    }.getOrNull()

    override suspend fun delete(relative: String): Unit = withContext(Dispatchers.IO) {
        val doc = find(relative) ?: throw WorkspaceIoException("el camino no existe: $relative")
        if (!doc.delete()) throw WorkspaceIoException("Error al eliminar: $relative")
    }

    override suspend fun mkdirs(relative: String): Unit = withContext(Dispatchers.IO) {
        ensureDirectory(relative) ?: throw WorkspaceIoException("Error al crear el directorio: $relative")
    }

    override suspend fun move(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = find(from) ?: throw WorkspaceIoException("La ruta de origen no existe: $from")
        val fromParent = findParent(from) ?: throw WorkspaceIoException("Camino ilegal: $from")
        val toParentAndName = findParent(to) ?: throw WorkspaceIoException("La ruta de destino es ilegal.: $to")
        val (toParent, toName) = toParentAndName
        if (toParent.findFile(toName) != null) throw WorkspaceIoException("El objetivo ya existe: $to")
        if (fromParent.first.uri == toParent.uri && src.renameTo(toName)) return@withContext
        copy(from, to)
        if (!src.delete()) throw WorkspaceIoException("Error de limpieza después de la mudanza: $from")
    }

    override suspend fun copy(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = find(from) ?: throw WorkspaceIoException("La ruta de origen no existe: $from")
        val (parent, name) = findParent(to) ?: throw WorkspaceIoException("La ruta de destino es ilegal.: $to")
        if (parent.findFile(name) != null) throw WorkspaceIoException("El objetivo ya existe: $to")
        copyDocument(src, parent, name)
    }

    /** Copie recursivamente un documento al directorio de destino. */
    private fun copyDocument(src: DocumentFile, parent: DocumentFile, name: String) {
        if (src.isDirectory) copyDirectory(src, parent, name) else copyFile(src, parent, name)
    }

    private fun copyDirectory(src: DocumentFile, parent: DocumentFile, name: String) {
        val dir = parent.createDirectory(name) ?: throw WorkspaceIoException("No se puede crear el directorio: $name")
        src.listFiles().forEach { child ->
            copyDocument(child, dir, child.name ?: return@forEach)
        }
    }

    private fun copyFile(src: DocumentFile, parent: DocumentFile, name: String) {
        val target = parent.createFile(MIME_BINARY, name) ?: throw WorkspaceIoException("No se puede crear el archivo: $name")
        workspaceIo("Copiar") {
            openInput(src).use { input ->
                openOutput(target, name).use { output -> input.copyTo(output) }
            }
        }
    }

    /** Abra la secuencia de lectura del documento fuente. */
    private fun openInput(src: DocumentFile): InputStream =
        context.contentResolver.openInputStream(src.uri) ?: throw WorkspaceIoException("No puedo leer ${src.name}")

    /** Abre el flujo de escritura del documento de destino. */
    private fun openOutput(target: DocumentFile, name: String): OutputStream =
        context.contentResolver.openOutputStream(target.uri, "wt") ?: throw WorkspaceIoException("incapaz de escribir $name")

    override suspend fun walkFiles(relative: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val start = if (relative.isBlank()) {
            root
        } else {
            find(relative) ?: throw WorkspaceIoException("El directorio no existe: $relative")
        }
        val base = relative.trim('/')
        fun visit(dir: DocumentFile) {
            for (doc in dir.listFiles()) {
                if (out.size >= limit) return
                val name = doc.name ?: continue
                val rel = if (base.isEmpty()) name else "$base/$name"
                if (doc.isDirectory) {
                    if (!name.startsWith(".")) visit(doc)
                } else {
                    out.add(rel)
                }
            }
        }
        visit(start)
        out
    }

    companion object {
        /** Tipos binarios universales: evita que algunos proveedores agreguen automáticamente extensiones por tipo. */
        private const val MIME_BINARY = "application/octet-stream"
    }
}
