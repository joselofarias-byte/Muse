package io.zer0.muse.rag

import io.zer0.muse.data.knowledge.KnowledgeDocEntity

/**
 * v1.0.47 P7-2: Estado del índice de archivos adjuntos a nivel de sesión.
 *
 * Describe el ciclo de vida del índice de un archivo adjunto de sesión.:
 *  - [QUEUED]:   Se unió al equipo,Esperando fragmentación
 *  - [CHUNKING]: fragmentación
 *  - [EMBEDDING]: Generando vectores
 *  - [READY]:    Indexación completada,Recuperable por kb_search
 *  - [FAILED]:   Error en el índice(content esta vacio/embedding Fracaso, etc)
 *  - [DROPPED]:  Sesión finalizada y borrada
 */
enum class SessionAttachmentStatus {
    QUEUED,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED,
    DROPPED,
}

/**
 * v1.0.47 P7-2: Clase de datos de índice de adjuntos a nivel de sesión.
 *
 * Cuando se agregan archivos adjuntos de sesión,Fragmento automático + incrustar en índice temporal,Permite que AI recupere el contenido del archivo adjunto a través de kb_search.
 * y base de conocimiento global(KnowledgeBase)la diferencia:
 *  - Sesión de enlace de ciclo de vida:Retención o limpieza opcional al final de la sesión.([DROPPED])
 *  - docId prefijo "session-{sessionId}-":Diferenciarse de los documentos globales de KB,Evite las búsquedas globales contaminantes
 *  - Escribir sincrónicamente filas de metadatos de KnowledgeDoc con fragmentación de KnowledgeChunk,Garantizar un reabastecimiento de recuperación seguro
 *
 * @property id Registro de adjunto ID(solo)
 * @property sessionId Perteneciente a la sesión ID
 * @property docId Documentos indexados ID(prefijo "session-{sessionId}-",Se utiliza para la asociación de tablas fragmentadas.)
 * @property name Nombre para mostrar del archivo adjunto(nombre de archivo)
 * @property status estado del índice
 * @property totalChunks Número total de bloques(READY post-llenado)
 * @property embeddedChunks Número de bloques indexados(seguimiento del progreso)
 * @property errorMessage Razón del fracaso(FAILED llenar cuando)
 * @property createdAt Crear marca de tiempo
 */
data class SessionAttachment(
    val id: String,
    val sessionId: String,
    val docId: String,
    val name: String,
    val status: SessionAttachmentStatus,
    val totalChunks: Int = 0,
    val embeddedChunks: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** porcentaje de progreso(0-100),0 cuando no se inició o falló. */
    val progressPercent: Int
        get() = if (totalChunks == 0) 0 else (embeddedChunks * 100 / totalChunks).coerceIn(0, 100)

    companion object {
        /** Prefijo de documento adjunto de sesión,Se utiliza para distinguir la KB global de los archivos adjuntos de sesión en la tabla de fragmentos. */
        const val DOC_ID_PREFIX = "session-attach-"

        /** Genere el docId del archivo adjunto de la sesión. */
        fun buildDocId(sessionId: String, attachmentId: String): String = "$DOC_ID_PREFIX$sessionId-$attachmentId"

        /** Determine si el docId pertenece al archivo adjunto de la sesión. */
        fun isSessionAttachmentDocId(docId: String): Boolean = docId.startsWith(DOC_ID_PREFIX)

        /** Analizado desde docId sessionId(Se utiliza para limpiar al final de la sesión.). */
        fun extractSessionIdFromDocId(docId: String): String? {
            if (!isSessionAttachmentDocId(docId)) return null
            val rest = docId.removePrefix(DOC_ID_PREFIX)
            return rest.substringBefore('-', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
        }
    }
}

/** Asignar archivos adjuntos de sesión a filas de metadatos RAG,Permite rellenar los resultados de búsqueda de forma segura a través de documentos. */
internal fun SessionAttachment.toKnowledgeDoc(
    content: String,
    chunkCount: Int = 0,
    embeddingModel: String = "",
    contentHash: String = "",
): KnowledgeDocEntity = KnowledgeDocEntity(
    id = docId,
    title = name,
    content = content,
    fileType = "session_attachment",
    chunkCount = chunkCount,
    embeddingModel = embeddingModel,
    contentHash = contentHash,
)
