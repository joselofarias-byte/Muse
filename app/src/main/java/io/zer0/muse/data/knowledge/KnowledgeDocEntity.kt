package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "knowledge_docs",
    indices = [
        Index(value = ["updated_at"]),
        // v1.133: Múltiples bases de conocimiento — Acelerar por índice kb_id"Listar documentos en KB"
        Index(value = ["kb_id"]),
        // v1.133: actualización incremental — Acelerar por índice content_hash"Saltar con hash"
        Index(value = ["content_hash"]),
    ],
)
data class KnowledgeDocEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(name = "file_path", defaultValue = "") val filePath: String = "",
    @ColumnInfo(name = "file_type", defaultValue = "") val fileType: String = "", // pdf/txt/md
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /** v1.54: Número de bloques(0 significa que aún no ha sido indexado,>0 Indica que ha sido fragmentado y generado. embedding). */
    @ColumnInfo(name = "chunk_count", defaultValue = "0") val chunkCount: Int = 0,
    /** v1.54: El nombre del modelo de incrustación utilizado al indexar(Cadena vacía significa no indexada). */
    @ColumnInfo(name = "embedding_model", defaultValue = "") val embeddingModel: String = "",

    // ── v1.133 Agregar nuevo campo(Múltiples bases de conocimiento/actualizaciones incrementales/filtrado de metadatos)──

    /**
     * v1.133: El ID de la base de conocimientos a la que pertenece. cadena vacía o "default" Representa la biblioteca predeterminada.(compatible con versiones anteriores).
     * MIGRATION_38_39 Completará el kb_id de todos los documentos existentes como "default".
     */
    @ColumnInfo(name = "kb_id", defaultValue = "default") val kbId: String = "default",

    /**
     * v1.133: Marcado de documentos internos(Documentos de desarrollo preestablecidos devdoc, etc.).true no participa en la recuperación de la base de datos del usuario,
     * Solo lo utiliza la llamada interna SkillExecutor Knowledge_Search.
     * sustituto original `fileType="devdoc"` + `docId.startsWith("devdoc-")` Doble filtrado codificado.
     */
    @ColumnInfo(name = "is_internal", defaultValue = "0") val isInternal: Boolean = false,

    /**
     * v1.133: hash de contenido(SHA-256 of content),Se utiliza para juzgar actualizaciones incrementales.
     * Al importar documentos con el mismo nombre, si los hashes son consistentes, se omitirá la reindexación.,Si es diferente, se activará la reindexación.
     */
    @ColumnInfo(name = "content_hash", defaultValue = "") val contentHash: String = "",

    /**
     * v1.133: Metadatos JSON(source/page/section/tags espera),Se utiliza para el filtrado de metadatos.
     * Ejemplo:{"source":"upload","page":12,"section":"Capítulo 2","tags":["ley","contrato"]}
     */
    @ColumnInfo(name = "metadata_json", defaultValue = "{}") val metadataJson: String = "{}",
)
