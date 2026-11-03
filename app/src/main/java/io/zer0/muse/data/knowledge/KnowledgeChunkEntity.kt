package io.zer0.muse.data.knowledge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * v1.54: Entidades fragmentadas de la base de conocimientos(RAG unidad de recuperación de vectores).
 *
 * Al importar documentos por [io.zer0.muse.rag.TextChunker] Dividir en múltiples chunk,Cada fragmento genera un vector de incrustación y lo guarda en esta tabla.
 *
 * v1.133 Transformación:
 *  - Nuevo [embeddingBlob] columna(ByteArray,FloatArray directo toByteArray),sustituto [embedding] JSON cadena.
 *    Se da prioridad a las nuevas escrituras. BLOB,viejo [embedding] JSON Columnas reservadas para compatibilidad de lectura con datos antiguos(MIGRATION_38_39 Sin migración de datos).
 *    Orden de lectura:embeddingBlob no nulo primero,De lo contrario, el respaldo analiza el JSON incrustado.
 *  - Nuevo [metadataJson] columna(page/section/tags metadatos,para filtrar).
 *
 * @param id clave primaria,Formato "chunk-{docId}-{chunkIndex}"
 * @param docId asociado KnowledgeDocEntity.id
 * @param content Texto fragmentado
 * @param embedding JSON cadena de matriz de punto flotante(datos antiguos,La escritura está en desuso,Sólo compatible con lectura)
 * @param embeddingBlob BLOB matriz flotante(nuevos datos,FloatArray directo toByteArray,5× acelerar)
 * @param chunkIndex El número de serie del bloque en el documento original.(0-based)
 * @param tokenCount Número aproximado de fichas
 * @param metadataJson Metadatos JSON(page/section/tags espera)
 * @param createdAt Crear marca de tiempo
 */
@Serializable
@Entity(
    tableName = "knowledge_chunks",
    indices = [
        Index(value = ["doc_id"]),
        Index(value = ["doc_id", "chunk_index"], unique = true),
    ],
)
data class KnowledgeChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    /**
     * almacenamiento empotrado antiguo:JSON cadena de matriz de punto flotante(Como por ejemplo "[0.012, -0.034, ...]").
     * v1.133 sólo se utiliza por compatibilidad con la lectura de datos antiguos,Las nuevas escrituras siempre desaparecerán. [embeddingBlob].
     */
    @ColumnInfo(name = "embedding", defaultValue = "") val embedding: String = "",
    /**
     * v1.133: Nuevo almacenamiento integrado — BLOB Matriz de punto flotante.
     * por FloatArray.toByteArray() conseguir,al leer FloatArray(size) { ByteBuffer.wrap(blob).float }.array().
     * En comparación con el análisis JSON de aprox. 5× acelerar,Y el volumen se reduce a la mitad.
     * null Indica que aún no se ha generado embedding,o de datos JSON heredados(recurrir al momento de leer [embedding] columna).
     */
    @ColumnInfo(name = "embedding_blob", defaultValue = "NULL") val embeddingBlob: ByteArray? = null,
    @ColumnInfo(name = "chunk_index", defaultValue = "0") val chunkIndex: Int = 0,
    @ColumnInfo(name = "token_count", defaultValue = "0") val tokenCount: Int = 0,
    /** v1.133: Metadatos JSON(page/section/tags espera),para filtrar. */
    @ColumnInfo(name = "metadata_json", defaultValue = "{}") val metadataJson: String = "{}",
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as KnowledgeChunkEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
