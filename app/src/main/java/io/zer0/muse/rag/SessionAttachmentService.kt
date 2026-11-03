package io.zer0.muse.rag

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Servicio de indexación de archivos adjuntos de sesión.
 *
 * El proceso de selección de documentos de chat activa la indexación,El proceso de eliminación de sesión desencadena la limpieza;Recupere el almacenamiento de fragmentos RAG unificados multiplexados.
 *
 * Responsabilidades:
 *  - Fragmentar e incrustar automáticamente en un índice temporal cuando se agregan archivos adjuntos(Reutilizar [RagService.indexDocument])
 *  - Mantener la máquina de estado del índice de archivos adjuntos:[SessionAttachmentStatus]
 *  - Actualización de devolución de llamada de progreso UI(Pase [attachmentsBySession] StateFlow)
 *  - Limpiar índice después de finalizar la sesión(Eliminar el registro correspondiente al docId en la tabla de fragmentos)
 *
 * diseño:
 *  - Almacenamiento de índice:Reutilizar la tabla KnowledgeChunk(Por prefijo docId "session-attach-" distinguir)
 *  - almacenamiento estatal:memoria StateFlow(Agrupar por ID de sesión),Evite cambios en el esquema de la base de datos
 *  - concurrencia:Tarea de indexación única por sesión(Igual que el ID de sesión serial),Evite incrustar conflictos por lotes
 *  - limpiar:Llamado cuando termina la sesión. [dropSessionAttachments] Eliminar registros fragmentados
 *
 * Integración de búsqueda:
 *  - kb_search Al recuperar herramientas,RagService.retrieve Los archivos adjuntos de la conversación se incluyen automáticamente chunk
 *    (Porque el prefijo docId no afecta la consulta de la tabla fragmentada,A menos que se filtre explícitamente por KB)
 *  - Si se requiere cuarentena,Se puede filtrar al recuperar isSessionAttachmentDocId
 */
class SessionAttachmentService(
    private val ragService: RagService,
    private val settingsRepository: io.zer0.muse.data.SettingsRepository,
    private val docDao: KnowledgeDocDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** estado de la memoria:sessionId → Lista de archivos adjuntos(Seguridad de concurrencia). */
    private val sessionAttachments = ConcurrentHashMap<String, MutableList<SessionAttachment>>()

    /** Tarea de indexación en segundo plano para cada archivo adjunto, cancelar y esperar a que finalice al eliminar una sesión. */
    private val activeJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Maneje la condición de carrera entre la eliminación y la indexación en segundo plano para evitar que los metadatos del documento se escriban nuevamente después de cancelar la ventana. */
    private val droppedAttachmentIds = ConcurrentHashMap.newKeySet<String>()

    /** Flujo de estado observable:Todos los archivos adjuntos de la sesión(lista plana). */
    private val _attachmentsFlow = MutableStateFlow<List<SessionAttachment>>(emptyList())
    val attachmentsFlow: StateFlow<List<SessionAttachment>> = _attachmentsFlow.asStateFlow()

    /** Obtenga la lista de archivos adjuntos para la sesión especificada. */
    fun attachmentsBySession(sessionId: String): List<SessionAttachment> = sessionAttachments[sessionId]?.toList() ?: emptyList()

    /**
     * Indexar un archivo adjunto de sesión.
     *
     * proceso:
     *  1. Generar adjuntoId y docId(prefijo "session-attach-")
     *  2. Estado QUEUED → CHUNKING → EMBEDDING → READY
     *  3. Reutilizar [RagService.indexDocument] fragmentación completa + embedding
     *  4. La devolución de llamada de progreso actualiza el estado de la memoria,gatillo StateFlow emit
     *
     * @param sessionId sesión ID
     * @param name Nombre para mostrar del archivo adjunto(nombre de archivo)
     * @param content Contenido del texto adjunto(Analizado por DocumentParser)
     * @return attachmentId(Se puede utilizar para consultas de estado posteriores.)
     */
    fun indexSessionAttachment(sessionId: String, name: String, content: String): String {
        val attachmentId = UUID.randomUUID().toString()
        val docId = SessionAttachment.buildDocId(sessionId, attachmentId)
        val attachment = SessionAttachment(
            id = attachmentId,
            sessionId = sessionId,
            docId = docId,
            name = name,
            status = SessionAttachmentStatus.QUEUED,
        )
        // Agregar estado de memoria
        sessionAttachments.compute(sessionId) { _, list ->
            (list ?: mutableListOf()).apply { add(attachment) }
        }
        emitState()

        // Índice de ejecución asincrónica
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                updateAttachment(attachmentId, sessionId) {
                    it.copy(status = SessionAttachmentStatus.CHUNKING)
                }
                val config = settingsRepository.ragConfigFlow.first()
                currentCoroutineContext().ensureActive()
                updateAttachment(attachmentId, sessionId) {
                    it.copy(status = SessionAttachmentStatus.EMBEDDING)
                }
                // RAG Relleno seguro para recuperación con Knowledge_docs como fuente veraz de metadatos;
                // Los fragmentos de solo escritura se cerrarán y descartarán durante la fase applyIsInternal.
                docDao.upsert(attachment.toKnowledgeDoc(content))
                currentCoroutineContext().ensureActive()
                val chunkCount = ragService.indexDocument(
                    docId = docId,
                    content = content,
                    ragConfig = config,
                    onProgress = { current, total ->
                        updateAttachment(attachmentId, sessionId) {
                            it.copy(embeddedChunks = current, totalChunks = total)
                        }
                    },
                )
                currentCoroutineContext().ensureActive()
                if (attachmentId in droppedAttachmentIds) return@launch
                if (chunkCount == 0) {
                    updateAttachment(attachmentId, sessionId) {
                        it.copy(status = SessionAttachmentStatus.FAILED, errorMessage = "El contenido está vacío o la fragmentación falló")
                    }
                    Logger.w(TAG, "Error en el índice de datos adjuntos de sesión:El contenido está vacío. | sessionId=$sessionId | name=$name")
                } else {
                    updateAttachment(attachmentId, sessionId) {
                        it.copy(
                            status = SessionAttachmentStatus.READY,
                            totalChunks = chunkCount,
                            embeddedChunks = chunkCount,
                        )
                    }
                    docDao.upsert(
                        attachment.toKnowledgeDoc(
                            content = content,
                            chunkCount = chunkCount,
                            embeddingModel = RagConfig.embeddingModelKey(config),
                            contentHash = RagService.computeContentHash(content),
                        ),
                    )
                    Logger.i(TAG, "Indexación de archivos adjuntos de sesión completada | sessionId=$sessionId | name=$name | chunks=$chunkCount")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Eliminar sesión/Los archivos adjuntos se cancelan normalmente y las tareas eliminadas no deben reescribirse como FALLIDAS.
                throw e
            } catch (e: Throwable) {
                updateAttachment(attachmentId, sessionId) {
                    it.copy(status = SessionAttachmentStatus.FAILED, errorMessage = e.message)
                }
                Logger.e(TAG, "Excepción del índice de datos adjuntos de sesión | sessionId=$sessionId | name=$name", e)
            } finally {
                activeJobs.remove(attachmentId)
            }
        }
        activeJobs[attachmentId] = job
        job.start()
        return attachmentId
    }

    /**
     * Limpia todos los índices de archivos adjuntos para la sesión especificada.
     *
     * Llamado cuando termina la sesión.,Eliminar el registro correspondiente al docId en la tabla de fragmentos,Liberar almacenamiento.
     * Después de la limpieza, el estado del accesorio se establece en [SessionAttachmentStatus.DROPPED].
     *
     * @param sessionId sesión ID
     * @param keepIndex Si se debe mantener el índice(Limpieza falsa predeterminada)
     */
    suspend fun dropSessionAttachments(sessionId: String, keepIndex: Boolean = false) {
        val attachments = sessionAttachments[sessionId] ?: return
        attachments.forEach { droppedAttachmentIds += it.id }
        attachments.mapNotNull { activeJobs[it.id] }.forEach { job ->
            job.cancelAndJoin()
        }
        attachments.forEach { activeJobs.remove(it.id) }
        if (!keepIndex) {
            for (attachment in attachments) {
                runCatching {
                    // QUEUED/CHUNKING También limpie: la tarea puede haber escrito fragmentos parciales o metadatos del documento.
                    ragService.deleteDocIndex(attachment.docId)
                    docDao.delete(attachment.docId)
                }.onFailure { Logger.w(TAG, "No se pudo eliminar el índice del archivo adjunto | docId=${attachment.docId}", it) }
            }
        }
        sessionAttachments.remove(sessionId)
        attachments.forEach { droppedAttachmentIds.remove(it.id) }
        emitState()
        Logger.i(TAG, "Se limpiaron los archivos adjuntos de la sesión | sessionId=$sessionId | keepIndex=$keepIndex | count=${attachments.size}")
    }

    /** Actualizar el estado del archivo adjunto especificado(memoria). */
    private fun updateAttachment(attachmentId: String, sessionId: String, transform: (SessionAttachment) -> SessionAttachment) {
        sessionAttachments[sessionId]?.let { list ->
            synchronized(list) {
                val idx = list.indexOfFirst { it.id == attachmentId }
                if (idx >= 0) {
                    list[idx] = transform(list[idx])
                }
            }
        }
        emitState()
    }

    /** gatillo StateFlow emit(Aplanar todos los archivos adjuntos de la sesión). */
    private fun emitState() {
        _attachmentsFlow.update {
            sessionAttachments.values.flatten().toList()
        }
    }

    companion object {
        private const val TAG = "SessionAttachmentSvc"
    }
}
