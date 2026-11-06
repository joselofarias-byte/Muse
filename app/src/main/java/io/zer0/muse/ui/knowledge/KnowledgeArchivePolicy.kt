package io.zer0.muse.ui.knowledge

/** Formatos de paquetes comprimidos importables y sus esquemas de origen de documentos. */
internal enum class ArchiveFormat(val extension: String, val sourceScheme: String) {
    ZIP("zip", "zip"),
    SEVEN_Z("7z", "7z"),
    RAR("rar", "rar"),
    ;

    companion object {
        fun fromFileName(name: String): ArchiveFormat? {
            val extension = KnowledgeArchivePolicy.extensionOf(name)
            return entries.firstOrNull { it.extension == extension }
        }
    }
}

/** El tipo de entrada de documento en el paquete comprimido. */
internal enum class ArchiveEntryKind { TEXT, PARSED, SKIP }

/**
 * Estrategia de importación de paquetes comprimidos — Categorías de la lista blanca,junk Filtrado, limitación y denominación temporal de archivos.
 *
 * v2.3.1: Se agregó juicio de rechazo para paquetes comprimidos importados desde un solo archivo.([isArchiveFile]),Compartido por la página principal de la base de conocimientos y la página de gestión de la base de conocimientos.;
 * Consulte ImportGuards.kt para detectar binarios que requieren ContentResolver.
 *
 * pura logica(Sin dependencias de Android),Archivos separados para facilitar pruebas únicas;Descomprimir/Realizar el pedido/Proceso de indexación
 * Consulte KnowledgeArchiveImporter.kt.
 */
internal object KnowledgeArchivePolicy {
    /** El número máximo de entradas procesadas en una sola importación. */
    const val MAX_ENTRIES = 500

    /** Límite de descompresión de entrada única(bytes,Para la colocación de clases de análisis);Las entradas que exceden el límite se manejan como fallas. */
    const val MAX_ENTRY_BYTES = 50L * (1L shl 20)

    /** El límite superior del consumo acumulado para una sola importación.(Tamaño de byte,Las entradas de texto se aproximan carácter por carácter.). */
    const val MAX_TOTAL_BYTES = 300L * (1L shl 20)

    /** Requiere un límite de copia temporal del archivo fuente del lector buscable. */
    const val MAX_ARCHIVE_BYTES = 300L * (1L shl 20)

    /** 7z / RAR Límite de memoria para decodificar diccionario. */
    const val MAX_DECODER_MEMORY_KIB = 128 * 1024
    const val MAX_DECODER_MEMORY_BYTES = 128L * 1024 * 1024

    /** Límite de vista previa del texto del documento,El índice todavía utiliza el contenido completo. */
    const val MAX_PREVIEW_CHARS = 500_000

    /** Límite de caracteres de lectura de entrada de texto(acuerdo chars*2≈bytes Calibre);Truncado y marcado más allá de los límites. */
    const val MAX_TEXT_CHARS = 10_000_000

    /** Lista blanca de texto(md/markdown/txt/csv/json/log). */
    private val TEXT_EXTS = setOf("md", "markdown", "txt", "csv", "json", "log")

    /** Lista blanca de clases de análisis(pdf/docx/doc/epub/pptx). */
    private val PARSED_EXTS = setOf("pdf", "docx", "doc", "epub", "pptx")

    /** No se admiten otros formatos de compresión importados como paquetes de base de conocimientos. */
    private val UNSUPPORTED_ARCHIVE_EXTS = setOf("tar", "gz", "tgz", "bz2", "xz")

    /** Si el nombre del archivo tiene un formato de compresión no compatible que no debe importarse como texto. */
    fun isArchiveFile(name: String): Boolean = extensionOf(name) in UNSUPPORTED_ARCHIVE_EXTS

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** Directorios/Archivos ocultos/Bifurcaciones de recursos de macOS, etc. no deben considerarse entradas en la documentación. */
    fun isJunk(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        val base = normalized.substringAfterLast('/')
        val dirOrBlank = normalized.isBlank() || normalized.endsWith('/')
        val macMeta = normalized.startsWith("__MACOSX/", ignoreCase = true)
        val hidden = base.isEmpty() || base.startsWith('.')
        return dirOrBlank || macMeta || hidden
    }

    fun classify(name: String): ArchiveEntryKind {
        val ext = extensionOf(name)
        return when (ext) {
            in TEXT_EXTS -> ArchiveEntryKind.TEXT
            in PARSED_EXTS -> ArchiveEntryKind.PARSED
            else -> ArchiveEntryKind.SKIP
        }
    }

    /** entrada de texto doc.fileType(Igual que el método de importación de un solo archivo). */
    fun textFileType(name: String): String {
        val ext = extensionOf(name)
        return when (ext) {
            "md", "markdown" -> "md"
            "csv" -> "csv"
            "json" -> "json"
            else -> "txt"
        }
    }

    /** Resuelva el doc.fileType de la entrada de clase. */
    fun parsedFileType(name: String): String {
        val ext = extensionOf(name)
        return when (ext) {
            "pdf" -> "pdf"
            "docx", "doc" -> "docx"
            "epub" -> "epub"
            "pptx" -> "pptx"
            else -> "txt"
        }
    }

    /** Nombre de archivo de disco temporal:Preservar la extensión para que la reconozca el analizador.,Eliminar rutas y caracteres ilegales.,Y de duración limitada. */
    fun sanitizeTempName(entryName: String): String {
        val base = entryName.replace('\\', '/').substringAfterLast('/')
        val dot = base.lastIndexOf('.')
        val rawStem = if (dot > 0) base.substring(0, dot) else base
        val stem = rawStem.take(48).replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
        val ext = if (dot > 0) base.substring(dot + 1).take(12) else ""
        return if (ext.isEmpty()) "entry_$stem" else "entry_$stem.$ext"
    }
}
