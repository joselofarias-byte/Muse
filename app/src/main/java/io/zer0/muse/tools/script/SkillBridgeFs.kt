package io.zer0.muse.tools.script

import io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS
import io.zer0.muse.tools.ToolTextFilePager
import java.io.File
import java.io.IOException

/**
 * v2.2.1 gran caja de arena:Habilidades/Operaciones del sistema de archivos sandbox puenteado por complementos —— Todas las rutas están ancladas dentro del directorio raíz de la zona de pruebas proporcionado por la persona que llama.
 *
 * seguro:
 *  - Después del análisis de la ruta, realice la verificación de inclusión canónica,Rechazar caminos absolutos con `..` Saltar,Los escapes de enlaces simbólicos también son canonical
 *    comprobar la interceptación;
 *  - leer/Hay un límite superior en el tamaño de escritura.,Hay un límite superior en la cantidad de listados de directorios.;
 *  - pura logica(no depende de Android Framework),Facilita las pruebas unitarias.
 */
internal object SkillBridgeFs {

    /** El umbral para ingresar automáticamente al modo de paginación al leer archivos grandes. */
    const val MAX_READ_BYTES = 1 shl 20

    /** El límite de escritura única es 1 MB. */
    const val MAX_WRITE_BYTES = 1 shl 20

    /** El listado del directorio devuelve el límite superior de entradas. */
    const val MAX_LIST_ENTRIES = 200

    /** Error en la operación del archivo Sandbox(Camino fuera de límites/El tipo no coincide/por encima del límite),La información se puede enviar directamente al autor de la llamada del script. */
    class BridgeFsException(message: String) : IOException(message)

    /** Entradas en el sandbox(Para serialización fs_list). */
    data class Entry(val name: String, val dir: Boolean, val size: Long)

    /**
     * Resolver rutas relativas en el sandbox a absolutas [File];cualquier cosa fuera de los límites(camino absoluto/`..`/Escape de enlace simbólico)Todos arrojan excepciones.
     */
    fun resolve(root: File, path: String): File {
        val cleaned = path.trim().replace('\\', '/')
        if (cleaned.isEmpty()) return root.canonicalFile
        if (cleaned.startsWith("/") || cleaned.split('/').any { it == ".." }) {
            throw BridgeFsException("camino ilegal(Sólo se permiten rutas relativas dentro del sandbox): $path")
        }
        val rootPath = root.canonicalFile
        val target = File(root, cleaned).canonicalFile
        val rootPrefix = rootPath.path + File.separator
        if (target != rootPath && !target.path.startsWith(rootPrefix)) {
            throw BridgeFsException("Camino fuera de límites: $path")
        }
        return target
    }

    /** directorio de lista(la mayoría [MAX_LIST_ENTRIES] Artículo). */
    fun list(root: File, path: String): List<Entry> {
        val dir = resolve(root, path)
        if (!dir.isDirectory) throw BridgeFsException("No es un directorio: $path")
        return dir.listFiles().orEmpty()
            .sortedBy { it.name }
            .take(MAX_LIST_ENTRIES)
            .map { Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L) }
    }

    /** Leer archivo de texto(UTF-8);Los archivos grandes se paginan mediante el cursor de carácter. */
    fun read(root: File, path: String, offsetChars: Int? = null, lengthChars: Int? = null): String {
        val file = resolve(root, path)
        if (!file.isFile) throw BridgeFsException("no es un archivo: $path")
        if (offsetChars != null || lengthChars != null || file.length() > MAX_READ_BYTES) {
            return ToolTextFilePager.readPage(
                file = file,
                path = path,
                charset = Charsets.UTF_8,
                requestedOffset = offsetChars ?: 0,
                requestedLength = lengthChars ?: TOOL_OUTPUT_READ_PAGE_CHARS,
                continuationTool = "fs_read",
            )
        }
        return file.readText(Charsets.UTF_8)
    }

    /** escribir archivo de texto(UTF-8,Crear directorio automáticamente,límite superior [MAX_WRITE_BYTES]);Devuelve el número de bytes escritos. */
    fun write(root: File, path: String, content: String): Int {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            throw BridgeFsException("El contenido excede el límite de escritura(${MAX_WRITE_BYTES / 1024}KB)")
        }
        val file = resolve(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return bytes.size
    }

    /** Eliminar archivos o directorios vacíos;Negarse a eliminar el directorio raíz de la zona de pruebas. */
    fun delete(root: File, path: String): Boolean {
        val file = resolve(root, path)
        if (file == root.canonicalFile) throw BridgeFsException("No se permite eliminar el directorio raíz de la zona de pruebas")
        if (!file.exists()) throw BridgeFsException("no existe: $path")
        if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) {
            throw BridgeFsException("El directorio no está vacío.,no se puede eliminar: $path")
        }
        return file.delete()
    }
}
