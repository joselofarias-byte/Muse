package io.zer0.muse.data.quicknote

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.0.17: Grabe rápidamente la persistencia de la sala(Almacenamiento de archivos JSON alternativo).
 *
 * Agregue la etiqueta eliminada/eliminada_at para implementar la función de papelera de reciclaje:
 *  - deleted=0: Grabación normal(observeActive Consulta)
 *  - deleted=1: papelera de reciclaje(observeTrash Consulta,deleted_at < before será limpiado por cleanOldTrash)
 *
 * tags Columna pasada [QuickNoteConverters] El TypeConverter pone List<String> serializado a
 * almacenamiento de cadenas separadas por comas(Prácticas de proyecto:Las tablas existentes, como asistentes.tagsJson, usan columnas JSON.,
 * Separe con comas aquí para simplificar la recuperación de etiquetas.,LIEK '%tag%' puede golpear).
 *
 * índice:
 *  - (deleted, updated_at): Anular observeActive/observeTrash WHERE + ORDER BY
 *  - (pinned, updated_at): Portada ORDER BY pinned DESC, updated_at DESC
 *
 * v1.0.18 Mejora rápida del registro(9 artículo):
 *  - folder: Clasificación/carpeta,Una cadena vacía indica no clasificado
 *  - contentType: tipo de contenido plain / markdown
 *  - attachmentsJson: Matriz JSON de rutas de archivos adjuntos de imágenes(Una cadena vacía indica que no hay archivos adjuntos)
 *  - reminderAt: marca de tiempo de recordatorio(0 No indica ningún recordatorio)
 *  - encrypted: Ya sea para cifrar
 *  - encryptedContent: Texto cifrado de contenido cifrado(encrypted=true el contenido se deja en blanco)
 */
@Entity(
    tableName = "quick_notes",
    indices = [
        Index(value = ["deleted", "updated_at"]),
        Index(value = ["pinned", "updated_at"]),
        Index(value = ["folder"]),
    ],
)
@kotlinx.serialization.Serializable
data class QuickNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val tags: List<String>,
    @ColumnInfo(name = "pinned", defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(name = "deleted", defaultValue = "0") val deleted: Boolean = false,
    @ColumnInfo(name = "deleted_at", defaultValue = "0") val deletedAt: Long = 0,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // v1.0.18: Clasificación/carpeta
    @ColumnInfo(name = "folder", defaultValue = "") val folder: String = "",
    // v1.0.18: Soporte de texto enriquecido(plain / markdown)
    @ColumnInfo(name = "content_type", defaultValue = "plain") val contentType: String = "plain",
    // v1.0.18: Matriz JSON de ruta de archivo adjunto de imagen(Como por ejemplo '["path1","path2"]')
    @ColumnInfo(name = "attachments_json", defaultValue = "") val attachmentsJson: String = "",
    // v1.0.18: marca de tiempo de recordatorio(0 = Sin recordatorio)
    @ColumnInfo(name = "reminder_at", defaultValue = "0") val reminderAt: Long = 0,
    // v1.0.18: token cifrado
    @ColumnInfo(name = "encrypted", defaultValue = "0") val encrypted: Boolean = false,
    // v1.0.18: Texto cifrado de contenido cifrado(encrypted=true almacenamiento de tiempo,content dejar en blanco)
    @ColumnInfo(name = "encrypted_content", defaultValue = "") val encryptedContent: String = "",
)
