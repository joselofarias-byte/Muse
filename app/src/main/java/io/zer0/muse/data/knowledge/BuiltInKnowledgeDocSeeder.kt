package io.zer0.muse.data.knowledge

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Seeds the hidden assistant-facing documentation used by knowledge_search.
 *
 * Startup kicks this off in the application scope, but the search tool can also
 * await the same idempotent operation so the first user request cannot race it.
 *
 * v2.4.x: Además de los metadatos iniciales, se necesitan más [ensureIndexed] Complemento de indexación vectorial para documentos internos.
 * Anteriormente, devdoc solo escribía Knowledge_docs y no creaba fragmentos.`knowledge_search(include_internal=true)`
 * La ruta de prioridad del vector en realidad no es válida para los documentos integrados y solo puede recurrir a la palabra clave LIKE.
 */
class BuiltInKnowledgeDocSeeder(
    private val context: Context,
    private val dao: KnowledgeDocDao,
    /** v2.4.x: Uso de indexación de documentos internos (opcional; el valor predeterminado es solo inicial y sin indexación). */
    private val ragService: RagService? = null,
    /** v2.4.x: Obtenga la configuración RAG actual (devolver nulo significa que aún no está disponible, omita el índice). */
    private val ragConfigProvider: (suspend () -> RagConfig?)? = null,
) {
    private val seedMutex = Mutex()
    private val indexMutex = Mutex()
    private var seeded = false
    private var indexed = false

    /**
     * Semilla idempotente: los documentos cuyo contenido no ha cambiado y han sido indexados se omiten directamente para evitar reescribir y perder metadatos del índice cada vez.
     */
    suspend fun ensureSeeded() = seedMutex.withLock {
        if (seeded) return@withLock
        val names =
            context.assets.list(ASSET_DIR)
                ?.filter { it.endsWith(".md", ignoreCase = true) }
                .orEmpty()
        if (names.isEmpty()) {
            Logger.w(TAG, "El directorio de documentación de la función incorporada está vacío, omita seed")
            return@withLock
        }
        val existing = resultOf { dao.getInternalDocs() }.getOrNull().orEmpty().associateBy { it.id }
        val now = System.currentTimeMillis()
        var failed = false
        names.forEach { name ->
            val seedResult = resultOf {
                val content =
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        input.bufferedReader().readText()
                    }
                val id = "devdoc-" + name.substringBeforeLast(".")
                val hash = RagService.computeContentHash(content)
                val previous = existing[id]
                // El contenido no ha cambiado y hay un índice: mantenga el status quo (sin reescribir, mantenga chunk_count/embedding_model)
                if (previous == null || previous.contentHash != hash || previous.chunkCount <= 0) {
                    val title =
                        content.lineSequence()
                            .firstOrNull { it.startsWith("#") }
                            ?.removePrefix("#")
                            ?.trim()
                            ?.ifBlank { name.substringBeforeLast(".") }
                            ?: name.substringBeforeLast(".")
                    dao.upsert(
                        KnowledgeDocEntity(
                            id = id,
                            title = title,
                            content = content,
                            filePath = "assets/$ASSET_DIR/$name",
                            fileType = "devdoc",
                            isInternal = true,
                            createdAt = previous?.createdAt ?: now,
                            updatedAt = now,
                            chunkCount = 0,
                            embeddingModel = "",
                            contentHash = hash,
                        ),
                    )
                }
            }
            seedResult.onError { msg, error ->
                failed = true
                Logger.w(TAG, "seed Error en la documentación de la función integrada: $name: $msg", error)
            }
        }
        if (!failed) seeded = true
    }

    /**
     * v2.4.x: Indexación vectorial complementaria para documentos internos (incremental, idempotente, silenciosa en caso de fallo).
     *
     * - Solo maneja los no indexados (chunkCount <= 0) o documentos con hashes de contenido inconsistentes;
     * - embedding No disponible/En caso de error, la palabra clave LIKE se conserva y no afecta la usabilidad de la recuperación;
     * - Llamado por el proceso de inicio en segundo plano sin bloquear [ensureSeeded] grupo de espera.
     */
    suspend fun ensureIndexed() = indexMutex.withLock {
        if (indexed) return@withLock
        val rag = ragService ?: return@withLock
        val provider = ragConfigProvider ?: return@withLock
        val config = resultOf { provider() }.getOrNull() ?: return@withLock
        val docs = resultOf { dao.getInternalDocs() }.getOrNull().orEmpty()
        val pending = docs.filter { doc -> needsIndexing(doc, config) }
        if (pending.isEmpty()) {
            indexed = true
            return@withLock
        }
        var failed = false
        var embeddingUnusable = false
        pending.forEach { doc ->
            // Una vez que se confirma que la inserción en sí no está disponible (un error de configuración), la documentación posterior no tiene por qué chocar contra la misma pared.
            if (embeddingUnusable) return@forEach
            val chunks =
                resultOf { rag.indexDocument(doc.id, doc.content, config) }
                    .onError { msg, error ->
                        failed = true
                        if (looksLikeEmbeddingConfigError(msg)) {
                            embeddingUnusable = true
                            Logger.w(
                                TAG,
                                "Indexación interna de documentos cancelada:embedding Actualmente no disponible($msg)." +
                                    "Verifique la fuente de inserción en la configuración de RAG——Si se selecciona \"Nube\"," +
                                    "Debe especificar un proveedor que realmente proporcione la interfaz de incorporación;" +
                                    "Los proveedores actuales de modelos de conversación alternativos generalmente no tienen este punto final.",
                            )
                        } else {
                            Logger.w(TAG, "Error en el índice del documento interno: ${doc.id}: $msg", error)
                        }
                    }
                    .getOrNull()
            if (chunks != null && chunks > 0) {
                dao.upsert(
                    doc.copy(
                        chunkCount = chunks,
                        embeddingModel = RagConfig.embeddingModelKey(config),
                        contentHash = RagService.computeContentHash(doc.content),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } else if (chunks != null) {
                failed = true
                Logger.w(TAG, "El bloque de documentos interno está vacío: ${doc.id}")
            }
        }
        if (!failed) indexed = true
    }

    /**
     * Determinar si la falla pertenece a "embedding La configuración no está disponible".
     *
     * Mire sólo los casos en los que el servidor se niega explícitamente (404 / El modelo no existe/el punto final no existe);
     * tiempo de espera de la red,5xx Este tipo de error temporal no está incluido en esta lista y aun así deberías volver a intentarlo documento por documento.
     */
    private fun looksLikeEmbeddingConfigError(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("404") ||
            m.contains("not found") ||
            m.contains("no such") ||
            m.contains("model_not_found") ||
            m.contains("invalid_endpoint")
    }

    /**
     * Si es necesario (re)indexar un solo documento.
     *
     * v2.6.0 (AUD-RAG-F1): Se deben comparar las huellas dactilares del modelo incrustado —— Antes solo miraba
     * chunkCount/contentHash, los vectores nuevos y antiguos después de cambiar el modelo de incrustación (especialmente la misma dimensión)
     * Si se mezclan en el mismo documento, la calidad de la recuperación se deteriorará silenciosamente.
     */
    private fun needsIndexing(doc: KnowledgeDocEntity, config: RagConfig): Boolean {
        val fingerprintMatches =
            doc.embeddingModel.isNotBlank() &&
                doc.embeddingModel == RagConfig.embeddingModelKey(config)
        val contentMatches =
            doc.contentHash.isNotBlank() &&
                doc.contentHash == RagService.computeContentHash(doc.content)
        return doc.chunkCount <= 0 || !contentMatches || !fingerprintMatches
    }

    private companion object {
        const val ASSET_DIR = "devdocs"
        const val TAG = "BuiltInKnowledgeDocSeeder"
    }
}
