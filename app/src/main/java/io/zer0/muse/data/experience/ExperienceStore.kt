package io.zer0.muse.data.experience

import android.content.Context
import io.zer0.muse.data.AtomicFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Almacenamiento de experiencias basado en archivos.
 *
 * Modelo de divulgación progresiva:
 *  - experience.md = index (category + description + path)
 *  - experience/{category}.md = category files (numbered list)
 *
 * Indexado por rebuildIndex() Generado automáticamente,No es necesario escribir a mano.
 * Funciona con ExperienceRepository basado en salas,Implementar almacenamiento dual.
 */
class ExperienceStore(private val context: Context) {

    companion object {
        private const val TAG = "ExperienceStore"
        private const val EXPERIENCE_DIR = "experience"
        private const val INDEX_FILE = "experience.md"
    }

    private val experienceDir: File get() = File(context.filesDir, EXPERIENCE_DIR).also { it.mkdirs() }
    private val indexPath: File get() = File(context.filesDir, INDEX_FILE)

    /** Escanee el directorio de experiencias para reconstruir los archivos de índice. */
    suspend fun rebuildIndex() = withContext(Dispatchers.IO) {
        val dir = experienceDir
        val docs = listExperienceDocuments(dir)
        if (docs.isEmpty()) {
            AtomicFileStore.writeText(indexPath, "")
            return@withContext
        }

        val blocks = docs.mapNotNull { doc ->
            val entries = doc.body.lines()
                .filter { it.trim().matches(Regex("^\\d+\\.\\s.*")) }
                .map { it.replace(Regex("^\\d+\\.\\s*"), "").trim() }
            if (entries.isEmpty()) return@mapNotNull null

            val snippets = entries.map { if (it.length > 20) it.take(20) + "..." else it }
            var desc = snippets.joinToString("; ")
            if (desc.length > 120) desc = desc.take(117) + "..."

            "# ${doc.title} (${entries.size} entries)\n$desc\n-> experience/${doc.file}"
        }

        AtomicFileStore.writeText(indexPath, blocks.joinToString("\n\n") + "\n")
    }

    /** Registre las entradas en el archivo de clasificación y reconstruya el índice. */
    suspend fun recordEntry(category: String, content: String): RecordResult = withContext(Dispatchers.IO) {
        val safeCategory = normalizeCategory(category)
            ?: return@withContext RecordResult(false, "invalid category")
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return@withContext RecordResult(false, "empty content")

        val dir = experienceDir
        val existingDoc = findDocument(dir, safeCategory)
        val file = existingDoc?.let { File(dir, it.file) } ?: File(dir, buildFileName(safeCategory))
        val existing = if (file.exists()) file.readText() else ""

        // Eliminar duplicados
        if (existing.contains(trimmed)) {
            return@withContext RecordResult(false, "duplicate")
        }

        val lines = existing.lines().filter { it.trim().matches(Regex("^\\d+\\.\\s.*")) }
        val nextNum = lines.size + 1
        val newLine = "$nextNum. $trimmed"

        val updated = if (existing.trimEnd().isNotEmpty()) {
            existing.trimEnd() + "\n" + newLine + "\n"
        } else {
            newLine + "\n"
        }

        val header = "<!-- experience-title: $safeCategory -->\n"
        AtomicFileStore.writeText(file, header + updated)
        rebuildIndex()
        RecordResult(true, category = safeCategory)
    }

    /** Obtener contenido del índice(Para uso mediante llamada sin parámetros de recuerda_experiencia). */
    suspend fun getIndex(): String = withContext(Dispatchers.IO) {
        if (indexPath.exists()) indexPath.readText() else ""
    }

    /** Obtenga el contenido de la categoría especificada. */
    suspend fun getCategory(category: String): CategoryContent? = withContext(Dispatchers.IO) {
        val doc = findDocument(experienceDir, category)
        if (doc != null) CategoryContent(doc.title, doc.body) else null
    }

    data class RecordResult(val added: Boolean, val reason: String? = null, val category: String? = null)
    data class CategoryContent(val title: String, val body: String)
    private data class ExperienceDoc(val file: String, val title: String, val body: String)

    private fun normalizeCategory(raw: String): String? {
        val title = raw.trim()
        if (title.isEmpty()) return null
        if (title.contains('/') || title.contains('\\') || title.contains("..")) return null
        if (title == "." || title == "..") return null
        return title
    }

    private fun buildFileName(category: String): String {
        val stem = category.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(48)
        val hash = category.hashCode().toString(16).take(8)
        return "${stem.ifEmpty { "experience" }}-$hash.md"
    }

    private fun listExperienceDocuments(dir: File): List<ExperienceDoc> {
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { f -> f.extension == "md" } ?: return emptyList()
        return files.sortedBy { it.name }.map { file ->
            val fallbackTitle = file.nameWithoutExtension
            parseDocument(file.readText(), fallbackTitle)
        }
    }

    private fun parseDocument(content: String, fallbackTitle: String): ExperienceDoc {
        val lines = content.lines()
        val firstLine = lines.firstOrNull()
        val title = if (firstLine?.startsWith("<!-- experience-title:") == true) {
            firstLine.removePrefix("<!-- experience-title:").removeSuffix("-->").trim()
        } else {
            fallbackTitle
        }
        val body = if (firstLine?.startsWith("<!-- experience-title:") == true) {
            lines.drop(1).joinToString("\n").trimStart().trimEnd()
        } else {
            content.trimEnd()
        }
        return ExperienceDoc(
            // Reparación de auditoría (5.4): file Generar con buildFileName(slug-hash.md),
            // Consistente con el nombre del archivo escrito por recordEntry. La implementación original es pura. slug,ruta en el índice
            // No coincide con el nombre del archivo del disco,rebuildIndex Todas las entradas posteriores apuntan a archivos que no existen.
            file = buildFileName(title),
            title = title,
            body = body,
        )
    }

    private fun findDocument(dir: File, category: String): ExperienceDoc? {
        return listExperienceDocuments(dir).find {
            it.title.equals(category, ignoreCase = true)
        }
    }
}
