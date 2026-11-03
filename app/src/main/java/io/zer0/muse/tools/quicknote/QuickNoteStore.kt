package io.zer0.muse.tools.quicknote

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * v1.136: registro rápido(notas ligeras)Almacenamiento.
 *
 * Los datos se guardan como un archivo JSON en el directorio privado de la aplicación.,Admite títulos, texto, etiquetas y pines.
 * Se puede acceder a los modelos a través de ToolRegistry quick_note_* Lectura, escritura y mantenimiento de herramientas.
 *
 * v1.0.17: Room Después de la migración,Esta clase se utiliza sólo como:
 *  - Fuente de migración([migrateToRoom] Importar datos JSON antiguos Room)
 *  - Formato compatible(ToolRegistry usado internamente QuickNote → formato de texto)
 *  - Leer todos los datos históricos(Si la migración falla, puede restaurarla manualmente)
 *
 * La verdadera persistencia de lectura y escritura es proporcionada por [QuickNoteDao] / [QuickNoteEntity] asumir,UI Pase
 * [io.zer0.muse.ui.quicknotes.QuickNotesViewModel] Observe el flujo de la habitación.
 */
class QuickNoteStore(private val context: Context) {

    private val file by lazy { java.io.File(context.filesDir, "quicknotes/notes.json") }

    /** B5-06: El último mensaje de corrupción de archivos(Se puede borrar después del consumo mediante la interfaz de usuario.). */
    @Volatile
    private var lastError: String? = null

    /** Consume el último recordatorio de daños.(UI Llamado después de la visualización). */
    fun consumeError(): String? {
        val e = lastError
        lastError = null
        return e
    }

    /** Listar registros,Opcional por palabra clave/Filtrado de etiquetas,Los registros fijados son lo primero. */
    fun list(keyword: String? = null, tag: String? = null, limit: Int = 50): List<QuickNote> {
        var all = readAll().sortedWith(compareByDescending<QuickNote> { it.pinned }.thenByDescending { it.updatedAtMillis })
        if (!keyword.isNullOrBlank()) {
            val kw = keyword.lowercase()
            all = all.filter {
                it.title.lowercase().contains(kw) || it.content.lowercase().contains(kw) || it.tags.any { t -> t.lowercase().contains(kw) }
            }
        }
        if (!tag.isNullOrBlank()) {
            all = all.filter { it.tags.any { t -> t.equals(tag, ignoreCase = true) } }
        }
        return all.take(limit)
    }

    /** Historial de búsqueda(Comportamiento consistente con el filtrado de palabras clave de lista). */
    fun search(keyword: String, limit: Int = 20): List<QuickNote> = list(keyword = keyword, limit = limit)

    /** Obtenga un registro único basado en la identificación. */
    fun get(id: String): QuickNote? = readAll().find { it.id == id }

    /**
     * Añade un registro.
     *
     * @return grabar id
     */
    fun add(title: String, content: String, tags: List<String>): String {
        val now = System.currentTimeMillis()
        val note = QuickNote(
            id = java.util.UUID.randomUUID().toString(),
            title = title,
            content = content,
            tags = tags,
            pinned = false,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        save(readAll().toMutableList().apply { add(note) })
        return note.id
    }

    /** Actualice el registro especificado. */
    fun update(id: String, title: String?, content: String?, tags: List<String>?): Boolean {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = all[idx]
        all[idx] = old.copy(
            title = title ?: old.title,
            content = content ?: old.content,
            tags = tags ?: old.tags,
            updatedAtMillis = System.currentTimeMillis(),
        )
        save(all)
        return true
    }

    /** Eliminar registros. */
    fun remove(id: String): Boolean {
        val all = readAll().toMutableList()
        val removed = all.removeIf { it.id == id }
        if (removed) save(all)
        return removed
    }

    /** Alternar estado fijado. */
    fun setPinned(id: String, pinned: Boolean): Boolean {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return false
        all[idx] = all[idx].copy(pinned = pinned, updatedAtMillis = System.currentTimeMillis())
        save(all)
        return true
    }

    /**
     * v1.0.17: Migre datos JSON antiguos a Room de una sola vez.
     *
     * tiempo de llamada:App Iniciado por [io.zer0.muse.MuseApp] Pasar el indicador SharedPreferences
     * `quick_notes_migrated` El control se ejecuta sólo una vez.(Garantía idempotente):
     *  - Usuarios que actualizan a v1.0.17 por primera vez:La bandera es false → Ejecutar migración → conjunto true
     *  - Usuarios migrados:La bandera es true → saltar
     *  - Nuevo usuario:JSON El archivo no existe,readAll() Regresar emptyList,Migración vacía después de la instalación true
     *
     * Idempotencia:[QuickNoteDao.upsert] usar OnConflictStrategy.REPLACE,Incluso si te llaman repetidamente
     * También anular con id como clave principal,No se generarán registros duplicados. Pero para evitar atravesar el archivo JSON cada vez que se inicia,
     * Todavía depende de la persona que llama garantizar la ejecución solo una vez usando el indicador SharedPreferences.
     *
     * Los archivos JSON no se eliminan una vez completada la migración,Mantener como copia de seguridad local(Los usuarios pueden limpiar manualmente).
     *
     * @param dao Room objeto de acceso a datos
     * @return Número de registros migrados(0 Indica que no hay datos o el archivo no existe)
     */
    suspend fun migrateToRoom(dao: QuickNoteDao): Int {
        val notes = readAll()
        if (notes.isEmpty()) {
            Logger.i(TAG, "migrateToRoom: JSON El archivo no tiene datos,Saltar migración")
            return 0
        }
        var migrated = 0
        notes.forEach { n ->
            dao.upsert(
                QuickNoteEntity(
                    id = n.id,
                    title = n.title,
                    content = n.content,
                    tags = n.tags,
                    pinned = n.pinned,
                    // Antiguo JSON sin concepto de papelera de reciclaje,Después de la migración, todos los registros serán normales.
                    deleted = false,
                    deletedAt = 0,
                    createdAt = n.createdAtMillis,
                    updatedAt = n.updatedAtMillis,
                ),
            )
            migrated++
        }
        Logger.i(TAG, "migrateToRoom: Migrado exitosamente $migrated Registros rápidos para Room")
        return migrated
    }

    private fun readAll(): List<QuickNote> {
        return try {
            if (!file.exists()) return emptyList()
            AppJson.decodeFromString(ListSerializer(QuickNote.serializer()), file.readText())
        } catch (e: Exception) {
            backupCorruptFile()
            lastError = "Registre rápidamente los daños en los archivos, haga una copia de seguridad del archivo original y reconstrúyalo"
            Logger.e(TAG, "No se pudo leer el registro rápido, se realizó una copia de seguridad del archivo incorrecto: ${e.message}", e)
            emptyList()
        }
    }

    /** B5-06: Los archivos corruptos se respaldan como .bak-<Marca de tiempo>,Evite perderse la escena en silencio. */
    private fun backupCorruptFile() {
        runCatching {
            if (file.exists()) {
                val backup = java.io.File(file.parentFile, "${file.name}.bak-${System.currentTimeMillis()}")
                file.copyTo(backup, overwrite = true)
                Logger.w(TAG, "Se ha realizado una copia de seguridad de los archivos corruptos en ${backup.absolutePath}")
            }
        }.onFailure { Logger.w(TAG, "Error en la copia de seguridad de archivos dañados: ${it.message}") }
    }

    private fun save(list: List<QuickNote>) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(ListSerializer(QuickNote.serializer()), list))
        } catch (e: Exception) {
            Logger.w(TAG, "No se pudo guardar el registro rápido: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "QuickNoteStore"
    }
}

@Serializable
data class QuickNote(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("content") val content: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("pinned") val pinned: Boolean,
    @SerialName("created_at") val createdAtMillis: Long,
    @SerialName("updated_at") val updatedAtMillis: Long,
)
