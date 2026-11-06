package io.zer0.muse.tools.resource

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: Biblioteca de recursos locales.
 *
 * Se utiliza para guardar, recuperar y eliminar fragmentos de recursos de texto.(Como palabras clave de uso común, contenido de referencia, notas, etc.),
 * Los datos se almacenan en el directorio privado de la aplicación en forma de archivos JSON.
 */
class ResourceLibraryStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "resources/library.json") }

    @Volatile
    private var lastError: String? = null

    /** Consume el último recordatorio de daños.(UI Llamado después de la visualización). */
    fun consumeError(): String? {
        val e = lastError
        lastError = null
        return e
    }

    /** enumerar recursos,Admite filtrado por palabras clave. */
    fun list(keyword: String? = null, limit: Int = 20): List<ResourceItem> {
        var all = readAll()
        if (!keyword.isNullOrBlank()) {
            val kw = keyword.lowercase()
            all = all.filter {
                it.title.lowercase().contains(kw) ||
                    it.content.lowercase().contains(kw) ||
                    it.tags.any { tag -> tag.lowercase().contains(kw) }
            }
        }
        return all.take(limit)
    }

    /** Obtenga un único recurso basado en id. */
    fun get(id: String): ResourceItem? {
        return readAll().find { it.id == id }
    }

    /**
     * Añade recursos.
     *
     * @return Recursos id
     */
    fun add(title: String, content: String, tags: List<String>): String {
        val now = System.currentTimeMillis()
        val item = ResourceItem(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            content = content,
            tags = tags,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        val all = readAll().toMutableList().apply { add(item) }
        writeAll(all)
        return item.id
    }

    /** Actualice el recurso especificado. */
    fun update(id: String, title: String?, content: String?, tags: List<String>?): Boolean {
        val all = readAll().toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val old = all[index]
        all[index] = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            tags = tags ?: old.tags,
            updatedAtMillis = System.currentTimeMillis(),
        )
        writeAll(all)
        return true
    }

    /** Eliminar el recurso especificado. */
    fun remove(id: String): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) writeAll(all)
        return removed
    }

    private fun readAll(): List<ResourceItem> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(ResourceItem.serializer()), file.readText())
        } catch (e: Exception) {
            backupCorruptFile()
            lastError = "El archivo de la biblioteca de recursos está dañado. Se realizó una copia de seguridad y se reconstruyó el archivo original."
            Logger.e(TAG, "No se pudo leer la biblioteca de recursos, se hicieron copias de seguridad de los archivos defectuosos: ${e.message}", e)
            emptyList()
        }
    }

    private fun backupCorruptFile() {
        runCatching {
            if (file.exists()) {
                val backup = java.io.File(file.parentFile, "${file.name}.bak-${System.currentTimeMillis()}")
                file.copyTo(backup, overwrite = true)
                Logger.w(TAG, "Se ha realizado una copia de seguridad de los archivos corruptos en ${backup.absolutePath}")
            }
        }.onFailure { Logger.w(TAG, "Error en la copia de seguridad de archivos dañados: ${it.message}") }
    }

    private fun writeAll(list: List<ResourceItem>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(ResourceItem.serializer()), list))
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo escribir en la biblioteca de recursos: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ResourceLibraryStore"
    }
}

@Serializable
data class ResourceItem(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("created_at") val createdAtMillis: Long,
    @SerialName("updated_at") val updatedAtMillis: Long,
)
