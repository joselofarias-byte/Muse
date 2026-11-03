package io.zer0.muse.workspace

import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Error de IO en el sistema de archivos del espacio de trabajo. Los mensajes están orientados al usuario y se pueden mostrar tal cual. */
class WorkspaceIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Ejecute un bloque IO que puede generar una excepción y, si falla, se convertirá en [WorkspaceIoException].
 *
 * Ya [WorkspaceIoException] El fallo se descarta tal cual para evitar que el mensaje quede atrapado en dos capas.
 *
 * @param op El nombre de la acción se utiliza para escribir "Error de lectura".: xxx"Este tipo de mensaje para que los usuarios lo vean
 * @param block Acciones IO reales
 */
internal inline fun <T> workspaceIo(op: String, block: () -> T): T {
    val result = resultOf(block)
    if (result is Result.Error) {
        val cause = result.throwable
        if (cause is WorkspaceIoException) throw cause
        throw WorkspaceIoException("${op}falló: ${result.message}", cause)
    }
    return result.getOrThrow()
}

/**
 * Un directorio en el espacio de trabajo./Entrada de archivo.
 *
 * Diferentes fuentes (directorio local, directorio autorizado del sistema) se unifican en esta forma, y ​​la interfaz y las herramientas no necesitan preocuparse por la capa subyacente.
 *
 * @param name Nombre para mostrar (sin ruta)
 * @param relativePath La ruta relativa al directorio raíz de montaje propietario (que comienza con "/" separados, el directorio raíz es una cadena vacía)
 * @param isDirectory ¿Es un directorio?
 * @param size Número de bytes (el directorio siempre es 0)
 * @param lastModified Marca de tiempo de la última modificación (milisegundos)
 */
data class FsEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/**
 * El sistema de archivos detrás de un montaje.
 *
 * La razón para abstraer esta capa: el directorio local se puede obtener directamente [File], el directorio de autorización del sistema solo puede obtener el identificador del documento.
 * La semántica de las dos API es bastante diferente. El nivel superior solo enfrenta este conjunto de métodos y ya no está vinculado a la fuente.
 */
interface WorkspaceFs {
    /** Lista de contenidos. Se lanza cuando el directorio no existe o no es un directorio [WorkspaceIoException]. */
    suspend fun list(relative: String): List<FsEntry>

    /** existe. */
    suspend fun exists(relative: String): Boolean

    /** Si es un directorio. */
    suspend fun isDirectory(relative: String): Boolean

    /** Número de bytes; devuelve 0 si no existe o no es un archivo. */
    suspend fun size(relative: String): Long

    /** Lee el texto. Exceder el límite superior de la persona que llama está determinado por la capa superior, y aquí no hay un límite superior comercial. */
    suspend fun readText(relative: String): String

    /** Leer bytes sin procesar. */
    suspend fun readBytes(relative: String): ByteArray?

    /** Leer texto por intervalo de caracteres. */
    suspend fun readTextRange(relative: String, offsetChars: Int, lengthChars: Int): String

    /** escribir texto,[append] Se adjunta cuando sea cierto. Creado automáticamente si el directorio principal no existe. */
    suspend fun writeText(relative: String, content: String, append: Boolean)

    /**
     * Escribe una secuencia de entrada en un archivo y devuelve el número de bytes escritos.
     *
     * Al importar archivos externos, tome esta ruta: copia en streaming, que no lee el archivo completo en la memoria.
     * exceder [maxBytes] Lanza una excepción e interrumpe.
     */
    suspend fun writeStream(relative: String, source: java.io.InputStream, maxBytes: Long): Long

    /** Eliminar un archivo o directorio. */
    suspend fun delete(relative: String)

    /** Cree un directorio (con múltiples niveles). */
    suspend fun mkdirs(relative: String)

    /** Mover o cambiar el nombre. */
    suspend fun move(from: String, to: String)

    /** Copiar. */
    suspend fun copy(from: String, to: String)

    /** Recopile archivos de forma recursiva (rutas relativas), hasta [limit] , omita los directorios que comienzan con el punto. */
    suspend fun walkFiles(relative: String, limit: Int): List<String>
}

/**
 * El sistema de archivos se monta directamente en el directorio local.
 *
 * Todas las rutas primero toman canonicalPath y luego verifican la relación del prefijo con el directorio raíz:`..`, enlace simbólico,
 * El empalme de ruta relativa y estos métodos de derivación aparecerán después de canónico.
 *
 * @param root Montar directorio raíz
 */
class LocalWorkspaceFs(private val root: File) : WorkspaceFs {

    /**
     * Analice la ruta relativa en un archivo real y realice una verificación fuera de límites.
     *
     * @param relative Ruta relativa al directorio raíz
     * @param allowRoot Si se permite apuntar al directorio raíz
     * @return Archivo analizado; regresa fuera de límites o falla en el análisis null
     */
    private fun resolve(relative: String, allowRoot: Boolean): File? {
        val segments = relative.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == ".." }) return null
        if (segments.isEmpty()) return if (allowRoot) root else null
        val candidate = File(root, segments.joinToString("/"))
        val canonical = resultOf { candidate.canonicalFile }
            .onError { message, _ -> Logger.w(TAG, "canonicalFile El análisis falló: $message") }
            .getOrNull() ?: return null
        val rootCanonical = root.canonicalPath
        val canonicalPath = canonical.canonicalPath
        val inside = canonicalPath == rootCanonical ||
            canonicalPath.startsWith(rootCanonical + File.separator)
        return if (inside) canonical else null
    }

    private fun require(relative: String, allowRoot: Boolean = false): File =
        resolve(relative, allowRoot) ?: throw WorkspaceIoException("Camino ilegal o acceso transfronterizo: $relative")

    override suspend fun list(relative: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dir = require(relative, allowRoot = true)
        if (!dir.isDirectory) throw WorkspaceIoException("No es una carpeta: $relative")
        val rootCanonical = root.canonicalPath
        (dir.listFiles()?.toList() ?: emptyList()).map { file ->
            FsEntry(
                name = file.name,
                relativePath = file.canonicalPath
                    .removePrefix(rootCanonical + File.separator)
                    .replace(File.separatorChar, '/'),
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModified = file.lastModified(),
            )
        }.sortedWith(compareByDescending<FsEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun exists(relative: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relative, allowRoot = true)?.exists() == true
    }

    override suspend fun isDirectory(relative: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relative, allowRoot = true)?.isDirectory == true
    }

    override suspend fun size(relative: String): Long = withContext(Dispatchers.IO) {
        val file = resolve(relative, allowRoot = false) ?: return@withContext 0L
        if (file.isFile) file.length() else 0L
    }

    override suspend fun readText(relative: String): String = withContext(Dispatchers.IO) {
        val file = require(relative)
        if (!file.isFile) throw WorkspaceIoException("no es un archivo: $relative")
        workspaceIo("leer") { file.readText(Charsets.UTF_8) }
    }

    override suspend fun readBytes(relative: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = resolve(relative, allowRoot = false) ?: return@withContext null
        if (!file.isFile) return@withContext null
        runCatching { file.readBytes() }.getOrNull()
    }

    override suspend fun readTextRange(relative: String, offsetChars: Int, lengthChars: Int): String = withContext(Dispatchers.IO) {
        val file = require(relative)
        if (!file.isFile) throw WorkspaceIoException("no es un archivo: $relative")
        workspaceIo("leer") {
            io.zer0.muse.tools.ToolTextFilePager.readPage(
                file = file,
                path = relative,
                charset = Charsets.UTF_8,
                requestedOffset = offsetChars,
                requestedLength = lengthChars,
                continuationTool = "workspace_read",
            )
        }
    }

    override suspend fun writeText(relative: String, content: String, append: Boolean): Unit = withContext(Dispatchers.IO) {
        val file = require(relative)
        workspaceIo("escribir") {
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            if (append) file.appendText(content, Charsets.UTF_8) else file.writeText(content, Charsets.UTF_8)
        }
    }

    override suspend fun writeStream(relative: String, source: java.io.InputStream, maxBytes: Long): Long = withContext(Dispatchers.IO) {
        val file = require(relative)
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
        var written = 0L
        val result = resultOf {
            file.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var read = source.read(buffer)
                while (read >= 0) {
                    if (read > 0) {
                        written += read
                        if (written > maxBytes) throw WorkspaceIoException("Archivo demasiado grande")
                        output.write(buffer, 0, read)
                    }
                    read = source.read(buffer)
                }
            }
        }
        if (result is Result.Error) {
            resultOf { file.delete() }
            val cause = result.throwable
            throw if (cause is WorkspaceIoException) cause else WorkspaceIoException("Error de escritura: ${result.message}", cause)
        }
        written
    }

    override suspend fun delete(relative: String): Unit = withContext(Dispatchers.IO) {
        val file = require(relative, allowRoot = false)
        if (!file.exists()) throw WorkspaceIoException("el camino no existe: $relative")
        workspaceIo("Eliminar") {
            val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
            if (!ok) throw WorkspaceIoException("Error al eliminar (puede haber subclaves o permisos insuficientes)")
        }
    }

    override suspend fun mkdirs(relative: String): Unit = withContext(Dispatchers.IO) {
        val dir = require(relative)
        if (dir.exists()) {
            if (!dir.isDirectory) throw WorkspaceIoException("El destino ya existe y no es una carpeta.: $relative")
            return@withContext
        }
        if (!dir.mkdirs()) throw WorkspaceIoException("Error al crear el directorio: $relative")
    }

    override suspend fun move(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = require(from)
        val dst = require(to)
        if (!src.exists()) throw WorkspaceIoException("La ruta de origen no existe: $from")
        if (dst.exists()) throw WorkspaceIoException("El objetivo ya existe: $to")
        dst.parentFile?.takeIf { !it.exists() }?.mkdirs()
        if (!src.renameTo(dst)) throw WorkspaceIoException("Movimiento fallido")
    }

    override suspend fun copy(from: String, to: String): Unit = withContext(Dispatchers.IO) {
        val src = require(from)
        val dst = require(to)
        if (!src.exists()) throw WorkspaceIoException("La ruta de origen no existe: $from")
        if (dst.exists()) throw WorkspaceIoException("El objetivo ya existe: $to")
        dst.parentFile?.takeIf { !it.exists() }?.mkdirs()
        workspaceIo("Copiar") {
            if (src.isDirectory) src.copyRecursively(dst, overwrite = false) else src.copyTo(dst, overwrite = false)
        }
    }

    override suspend fun walkFiles(relative: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val base = require(relative, allowRoot = true)
        val out = mutableListOf<String>()
        val rootCanonical = root.canonicalPath
        fun visit(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (out.size >= limit) return
                if (child.isDirectory) {
                    if (!child.name.startsWith(".")) visit(child)
                } else {
                    out.add(
                        child.canonicalPath
                            .removePrefix(rootCanonical + File.separator)
                            .replace(File.separatorChar, '/'),
                    )
                }
            }
        }
        visit(base)
        out
    }

    companion object {
        private const val TAG = "LocalWorkspaceFs"
    }
}
