package io.zer0.muse.backup

import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import java.io.File
import java.util.Base64

/**
 * v4.1: Almacenamiento de archivos de directorio(Nota adjunta/copia privada del avatar)copia de seguridad y recuperación.
 *
 * fondo:anterior [BackupService.backupFileStorePaths] Sobrescribir solo la lista blanca de un solo archivo,
 * `note_attachments/`(NoteAttachmentStore,Mezclando binario y texto)con `avatar/`
 * (AvatarStorage copia privada de)No hay ninguna copia de seguridad —— Después del reemplazo y recuperación. QuickNoteEntity.attachmentsJson
 * y AssistantEntity.avatarImageUrl apuntan a archivos que no existen.
 *
 * restricciones de diseño:
 *  - La clave de respaldo es**Ruta relativa a filesDir**(Como por ejemplo `note_attachments/img_x.png`),El valor es texto Base64;
 *  - solo permitido [allowedPrefixes] directorio en,Debe volver a verificarse durante la recuperación.,Está prohibido escribir fuera de la lista blanca.;
 *  - archivo único ≤ 2MB, total para cada directorio ≤ 16MB,Saltar y registrar cuando se exceda el límite(Sin truncamiento silencioso);
 *  - Leer saltos y registros de errores(No permita que falle la copia de seguridad general);Los errores de escritura se propagan hacia arriba(Activar la reversión del punto de recuperación).
 */
internal object BackupBinaryFiles {

    /** Prefijos de directorio permitidos para la copia de seguridad(relativamente filesDir),Verifique de acuerdo con la misma lista al restaurar. */
    val allowedPrefixes: List<String> = listOf(
        "note_attachments/",
        "avatar/",
        // 2026-11-01: Los datos de la tarjeta se cambian a fragmentación por tarjeta (CardDataStore), el archivo único card_data.json se reserva únicamente como fuente de migración.
        // Si no agrega este elemento, los datos de la tarjeta no se incluirán en el alcance de la copia de seguridad después de la fragmentación. —— Todo el contenido de show_card se pierde después de la recuperación.
        "card_data/",
        // 2026-11-01: La bandeja de entrada del canal cambió a fragmentación por elemento (ChannelInbox), el archivo único channel_inbox.json está reservado solo como fuente de migración.
        // Sin este elemento, todos los eventos que se enviarán y el historial entrante reciente se perderán después de la recuperación.
        "channel_inbox/",
    )

    /** Límite de un solo archivo:2MB. */
    const val MAX_FILE_BYTES: Long = 2L * 1024 * 1024

    /** Límite superior total para cada directorio de la lista blanca:16MB. */
    const val MAX_DIR_BYTES: Long = 16L * 1024 * 1024

    private const val TAG = "BackupBinaryFiles"

    /** Un artículo que se escribirá para exportar.:Ruta relativa + bytes sin formato. */
    data class Entry(val path: String, val bytes: ByteArray) {
        // ByteArray es un tipo de referencia,data class La semántica predeterminada igual puede ser engañosa;Apágalo explícitamente.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Saltar estadísticas. */
    data class SkipStats(val skippedFiles: Int = 0, val skippedBytes: Long = 0L)

    /** Exportar instantánea:Entradas ordenadas por ruta + omitir estadísticas. */
    data class Snapshot(val items: List<Entry>, val skipped: SkipStats = SkipStats()) {
        /** Para exportación JSON única:camino → Base64. */
        fun asBase64Map(): Map<String, String> = items.associate { it.path to Base64.getEncoder().encodeToString(it.bytes) }
    }

    /** Resultados de recuperación. */
    data class RestoreResult(
        val writtenFiles: Int = 0,
        val skippedFiles: Int = 0,
        val skippedBytes: Long = 0L,
    )

    /**
     * Verificación de ruta:El prefijo de lista blanca + rechaza cualquier escritura que pueda atravesar el directorio.
     *
     * rechazar expresamente:
     *  - camino absoluto(a `/` o `\` comienzo)
     *  - barra invertida(Utilice la misma clave de respaldo `/`;La barra invertida es delimitador en Windows,ambigüedad semántica)
     *  - cualquier cosa que contenga `..` clave(incluir `note_attachments/../../evil` este tipo de"El prefijo es legal pero contiene recorrido.")
     *  - Sección vacía / `.` segmento(Como por ejemplo `note_attachments//x`,`note_attachments/./x`)
     *  - NUL personaje
     *  - Sólo prefijo de directorio, sin clave de nombre de archivo
     */
    fun isAllowedPath(path: String): Boolean {
        val prefix = prefixOf(path)
        return prefix != null &&
            path.length > prefix.length &&
            !path.startsWith("/") &&
            !path.startsWith("\\") &&
            !path.contains('\\') &&
            !path.contains('\u0000') &&
            !path.contains("..") &&
            path.split('/').none { it.isBlank() || it == "." }
    }

    /** Devuelve el prefijo de la lista blanca al que pertenece la ruta.;Devuelve nulo si no está en la lista blanca. */
    fun prefixOf(path: String): String? = allowedPrefixes.firstOrNull { path.startsWith(it) }

    /**
     * Analice la clave de copia de seguridad en el archivo de destino en filesDir.
     *
     * doble verificación:pasar primero [isAllowedPath] lista blanca de sintaxis,Luego use canonicalFile para confirmar el directorio principal
     * de hecho cae dentro de filesDir(Enlace anti-soft `avatar/x` Apuntar a directorio externo).
     * @return archivo de destino legal;retorno ilegal null(La persona que llama debe saltarse,No se permite escribir en el disco).
     */
    fun resolveTarget(filesDir: File, path: String): File? = if (!isAllowedPath(path)) {
        null
    } else {
        val root = runCatching { filesDir.canonicalFile }.getOrNull()
        val target = File(filesDir, path)
        val parent = runCatching { target.canonicalFile }.getOrNull()?.parentFile
        target.takeIf { root != null && parent != null && isInside(root, parent) }
    }

    /** Si el directorio principal se encuentra dentro de la raíz(Contiene la raíz misma). */
    private fun isInside(root: File, parent: File): Boolean = parent.path == root.path || parent.path.startsWith(root.path + File.separator)

    /** Si el número total de archivos o directorios individuales excede el límite superior. */
    fun exceedsLimit(size: Long, usedBytes: Long): Boolean = size > MAX_FILE_BYTES || usedBytes + size > MAX_DIR_BYTES

    /**
     * Leer archivos en directorios incluidos en la lista blanca(bytes sin procesar).
     *
     * Si un solo archivo no se puede leer o excede el límite, solo se omitirá y se contará.,Finalmente, haz un registro W ——
     * Los usuarios deben poder ver"Esta copia de seguridad está incompleta",En lugar de asumir que la copia de seguridad está completa.
     * La lectura se completa antes de que se escriba el meta.,Por lo tanto, la cantidad de elementos declarados por meta es consistente con la cantidad de elementos realmente escritos.
     * (NDJSON La conciliación B-34 en el lado de las importaciones se basa en esto).
     */
    fun read(filesDir: File): Snapshot {
        val entries = mutableListOf<Entry>()
        val dirBytes = mutableMapOf<String, Long>()
        var skippedFiles = 0
        var skippedBytes = 0L
        collectFiles(filesDir).forEach { (relative, file) ->
            val prefix = prefixOf(relative)
            val size = file.length()
            if (prefix == null || !isAllowedPath(relative) || exceedsLimit(size, dirBytes[prefix] ?: 0L)) {
                skippedFiles++
                skippedBytes += size
                return@forEach
            }
            val bytes = runCatching { file.readBytes() }
                .onFailure { Logger.w(TAG, "No se pudo leer el archivo,saltado: $relative", it) }
                .getOrNull()
            val used = dirBytes[prefix] ?: 0L
            if (bytes == null || exceedsLimit(bytes.size.toLong(), used)) {
                skippedFiles++
                skippedBytes += size
                return@forEach
            }
            entries += Entry(relative, bytes)
            dirBytes[prefix] = used + bytes.size
        }
        val stats = SkipStats(skippedFiles, skippedBytes)
        logSkipped("Exportar", stats)
        return Snapshot(entries, stats)
    }

    /**
     * Escriba los archivos del directorio en la copia de seguridad en filesDir.
     *
     * saltar(No bloquear):Rutas fuera de la lista blanca,Base64 Ilegal y superando el límite de capacidad.
     * propagar(bloquear):[write] Cualquier excepción lanzada —— El almacenamiento basado en archivos comparte el libro de recuperación con tres bibliotecas de Room,
     * El error de escritura debe desencadenar la reversión del punto de recuperación,No puede dejar la mitad del directorio de archivos adjuntos en silencio.
     */
    suspend fun restore(
        files: Map<String, String>,
        filesDir: File,
        write: suspend (File, ByteArray) -> Unit = { target, bytes ->
            target.parentFile?.mkdirs()
            AtomicFileStore.writeBytes(target, bytes)
        },
    ): RestoreResult {
        var writtenFiles = 0
        var skippedFiles = 0
        var skippedBytes = 0L
        val dirBytes = mutableMapOf<String, Long>()
        files.toSortedMap().forEach { (path, encoded) ->
            val prefix = prefixOf(path)
            val target = if (prefix == null) null else resolveTarget(filesDir, path)
            if (prefix == null || target == null) {
                skippedFiles++
                skippedBytes += base64SizeHint(encoded)
                Logger.w(TAG, "Denegar la recuperación de rutas fuera de la lista blanca: $path")
                return@forEach
            }
            val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            if (bytes == null) {
                skippedFiles++
                skippedBytes += base64SizeHint(encoded)
                Logger.w(TAG, "Base64 Contenido ilegal,saltado: $path")
                return@forEach
            }
            val used = dirBytes[prefix] ?: 0L
            if (exceedsLimit(bytes.size.toLong(), used)) {
                skippedFiles++
                skippedBytes += bytes.size
                return@forEach
            }
            // fail-fast:El error de escritura no se captura,Déjelo en la capa superior para restaurar el libro mayor y revertirlo.
            write(target, bytes)
            dirBytes[prefix] = used + bytes.size
            writtenFiles++
        }
        val stats = SkipStats(skippedFiles, skippedBytes)
        logSkipped("restaurar", stats)
        return RestoreResult(writtenFiles = writtenFiles, skippedFiles = stats.skippedFiles, skippedBytes = stats.skippedBytes)
    }

    /**
     * Importar búfer lateral:Después de poner NDJSON `binaryFile` Las listas blancas y la reducción de capacidad se realizan antes de que las filas se conserven en la memoria.
     *
     * malicioso/Las copias de seguridad corruptas pueden declarar cualquier cantidad de entradas de cualquier tamaño;Si no lo intercepta aquí, primero se producirá OOM y luego se discutirá la verificación.
     * Al escribir disco [restore] Lo comprobaré de nuevo(defensa en profundidad).
     */
    class Buffer {
        private val files = linkedMapOf<String, String>()
        private val dirBytes = mutableMapOf<String, Long>()
        private var skippedFiles = 0
        private var skippedBytes = 0L

        val stats: SkipStats get() = SkipStats(skippedFiles, skippedBytes)

        fun offer(path: String, encoded: String) {
            val prefix = prefixOf(path)
            if (prefix == null || !isAllowedPath(path)) {
                skippedFiles++
                skippedBytes += base64SizeHint(encoded)
                Logger.w(TAG, "La ruta en la copia de seguridad no está en la lista blanca,saltado: $path")
                return
            }
            val estimated = base64SizeHint(encoded)
            if (exceedsLimit(estimated, dirBytes[prefix] ?: 0L)) {
                skippedFiles++
                skippedBytes += estimated
                return
            }
            files[path] = encoded
            dirBytes[prefix] = (dirBytes[prefix] ?: 0L) + estimated
        }

        fun toMap(): Map<String, String> = files
    }
}

/** Límite de profundidad de recursividad del directorio(Ambas tiendas son en realidad planas.,Limite la profundidad solo para evitar estructuras de directorios anormales). */
private const val MAX_DEPTH = 4

private const val BINARY_TAG = "BackupBinaryFiles"

/** Calcule el número bruto de bytes correspondientes al texto Base64(Solo se usa para omitir estadísticas y predicción de capacidad.,No participa en la decodificación y verificación.). */
private fun base64SizeHint(encoded: String): Long = (encoded.length / 4 * 3).toLong()

/** Recopile todos los archivos normales en el directorio de la lista blanca(Ruta relativa a filesDir → File),La clasificación por ruta garantiza la estabilidad de las exportaciones. */
private fun collectFiles(filesDir: File): List<Pair<String, File>> {
    val out = mutableListOf<Pair<String, File>>()
    BackupBinaryFiles.allowedPrefixes.forEach { prefix ->
        collectInto(File(filesDir, prefix.removeSuffix("/")), prefix, out, 0)
    }
    return out.sortedBy { it.first }
}

private fun collectInto(dir: File, prefix: String, out: MutableList<Pair<String, File>>, depth: Int) {
    if (depth > MAX_DEPTH) return
    val children = dir.listFiles()?.sortedBy { it.name } ?: return
    children.forEach { child ->
        val relative = prefix + child.name
        when {
            child.isDirectory -> collectInto(child, "$relative/", out, depth + 1)
            child.isFile -> out += relative to child
        }
    }
}

private fun logSkipped(action: String, stats: BackupBinaryFiles.SkipStats) {
    if (stats.skippedFiles == 0) return
    Logger.w(
        BINARY_TAG,
        "$action saltar ${stats.skippedFiles} archivos de directorio(total ${stats.skippedBytes} bytes):" +
            "Más allá de un solo archivo ${BackupBinaryFiles.MAX_FILE_BYTES / 1024 / 1024}MB / " +
            "total del directorio ${BackupBinaryFiles.MAX_DIR_BYTES / 1024 / 1024}MB Límite superior, ruta ilegal o error de lectura,El contenido de la copia de seguridad está incompleto",
    )
}
