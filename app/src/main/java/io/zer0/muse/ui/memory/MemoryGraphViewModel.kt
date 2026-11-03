@file:Suppress("TooManyFunctions")

package io.zer0.muse.ui.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.zer0.memory.ai.MemoryLinkDao
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modelo de vista de constelación de memoria.
 *
 * Fase 2: leer datos de relaciones de memoria reales de FactStore y MemoryLinkDao.
 * Etapa 3: Admitir edición, eliminación y fijación de nodos;Confirmar y eliminar relaciones.
 * Etapa 4: Apoyar la generación y el ahorro de candidatos de agrupación.
 */
class MemoryGraphViewModel(
    application: Application,
    private val factStore: FactStore,
    private val memoryLinkDao: MemoryLinkDao,
    private val factDbProvider: FactDbProvider? = null,
    /** v2.6.2(comentarios de los usuarios): Generador de bordes complementario (para la reconstrucción manual de asociaciones). */
    private val edgeBuilder: io.zer0.memory.ai.MemoryEdgeBuilder? = null,
    /** v2.6.2: Proveedor de vectores vecinos semánticos. */
    private val semanticProvider: io.zer0.memory.ai.MemoryEdgeBuilder.SemanticProvider? = null,
) : AndroidViewModel(application) {

    data class GraphState(
        val nodes: List<MemoryGraphNode> = emptyList(),
        val edges: List<MemoryGraphEdge> = emptyList(),
        val clusters: List<MemoryGraphCluster> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private fun storeForScope(scope: String?): FactStore = if (scope.isNullOrBlank() || scope == "main") {
        factStore
    } else {
        factDbProvider?.getFactStore(scope) ?: factStore
    }

    private fun linksForScope(scope: String?): MemoryLinkDao = if (scope.isNullOrBlank() || scope == "main") {
        memoryLinkDao
    } else {
        factDbProvider?.getFactDb(scope)?.memoryLinkDao() ?: memoryLinkDao
    }

    private val _state = MutableStateFlow(GraphState(isLoading = true))
    val state: StateFlow<GraphState> = _state.asStateFlow()

    fun load(scope: String?, spaceId: String) {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val scopedStore = storeForScope(scope)
                val scopedLinks = linksForScope(scope)
                val facts = withContext(Dispatchers.IO) {
                    if (scope == null) scopedStore.getBySpace(spaceId) else scopedStore.getByScopeAndSpace(scope, spaceId)
                }
                val links = withContext(Dispatchers.IO) {
                    if (scope == null) scopedLinks.listBySpace(spaceId) else scopedLinks.listBySpaceAndScope(spaceId, scope)
                }
                val factIdsInLinks = links.flatMapTo(mutableSetOf()) { setOf(it.sourceFactId, it.targetFactId) }
                // Los hechos ordinarios también deben ser visibles; relaciones/importancia/Quedarse en la cima solo se utiliza para priorizar.
                val nodeFacts = selectGraphFacts(facts, factIdsInLinks, MAX_NODES)
                val nodeIdSet = nodeFacts.map { it.id }.toSet()
                val nodeEdges = links.filter { it.sourceFactId in nodeIdSet && it.targetFactId in nodeIdSet }.take(MAX_EDGES)

                val nodes = nodeFacts.map { fact ->
                    MemoryGraphNode(
                        factId = fact.id,
                        title = fact.fact.take(NODE_TITLE_MAX_LENGTH),
                        category = fact.category,
                        importance = fact.importance,
                        confidence = fact.confidence,
                        isPinned = fact.pinnedAt != null,
                        isExpired = false,
                    )
                }
                val edges = nodeEdges.map { link ->
                    MemoryGraphEdge(
                        id = link.id,
                        sourceFactId = link.sourceFactId,
                        targetFactId = link.targetFactId,
                        relationType = link.linkType,
                        weight = link.weight,
                        sourceTitle = link.sourceTitle,
                        targetTitle = link.targetTitle,
                    )
                }
                // Etapa 4: agrupación automática(Agrupación preliminar por categoría)
                val clusters = generateClusters(nodes)
                _state.value = GraphState(nodes = nodes, edges = edges, clusters = clusters, isLoading = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = GraphState(isLoading = false, error = e.message ?: "load failed")
            }
        }
    }

    // ── Fase 3: Operaciones de nodo ──
    fun deleteNode(factId: Long, scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val store = storeForScope(scope)
                if (scope == null) {
                    store.delete(factId)
                } else {
                    store.delete(factId, store.assistantId, scope, spaceId)
                }
            }
            load(scope, spaceId)
        }
    }

    /**
     * v2.6.11: Obtenga el texto completo de los hechos. Nodos de constelación con títulos solo truncados ([NODE_TITLE_MAX_LENGTH]),
     * Debe obtener el texto completo antes de editarlo; de lo contrario, al guardarlo, se escribirán permanentemente los datos extensos en las docenas de palabras del título.
     */
    fun loadFactContent(factId: Long, scope: String?, onLoaded: (String?) -> Unit) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { storeForScope(scope).getById(factId)?.fact }
            onLoaded(content)
        }
    }

    fun editNode(factId: Long, newContent: String, scope: String?, spaceId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val store = storeForScope(scope)
                val current = store.getById(factId)?.fact
                // v2.6.11: Barandilla inferior —— Si el contenido entrante resulta ser el título truncado del texto completo existente, significa que la persona que llama
                // Se muestra el título en lugar del texto completo. Si se deja caer en la biblioteca, los hechos extensos se truncarán, por lo que simplemente me niego a escribirlos aquí.
                val looksTruncated =
                    current != null &&
                        current.length > NODE_TITLE_MAX_LENGTH &&
                        newContent == current.take(NODE_TITLE_MAX_LENGTH)
                if (looksTruncated) false else store.update(factId, newContent, scope)
            }
            if (saved) load(scope, spaceId)
            onResult(saved)
        }
    }

    fun togglePin(factId: Long, scope: String? = null, spaceId: String = "default") {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val scopedStore = storeForScope(scope)
                val fact = scopedStore.getById(factId) ?: return@withContext
                // v2.6.2(auditoría de memoria): Ruta según alcance/Espacio actual —— De lo contrario, la página de constelación muestra el subasistente/
                // Cuando se utilizan otros nodos espaciales, la parte superior se cambiará al hecho con la misma ID en la biblioteca predeterminada.
                if (fact.scope != (scope ?: "main") || fact.spaceId != spaceId) return@withContext
                scopedStore.setPinned(factId, fact.pinnedAt == null)
            }
            _state.value = _state.value.copy(
                nodes = _state.value.nodes.map { if (it.factId == factId) it.copy(isPinned = !it.isPinned) else it },
            )
        }
    }

    // ── Etapa 3: Operaciones de relación ──
    fun deleteEdge(edgeId: Long, scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { linksForScope(scope).deleteById(edgeId) }
            load(scope, spaceId)
        }
    }

    /**
     * v2.6.2(comentarios de los usuarios): Cree manualmente un borde (el usuario hace clic en la página de la constelación para conectar dos nodos).
     *
     * @param sourceFactId hechos del punto de partida
     * @param targetFactId hecho del punto final
     * @param relationType tipo de relación (related_to / causes / part_of / contradicts)
     */
    fun addManualEdge(sourceFactId: Long, targetFactId: Long, relationType: String, scope: String?, spaceId: String) {
        if (sourceFactId == targetFactId) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val scopedLinks = linksForScope(scope)
                // Deduplicación no dirigida: omita si ya existe el mismo borde opuesto
                val existing = scopedLinks.listBySpaceAndScope(spaceId, scope ?: "main")
                val dup = existing.any {
                    (it.sourceFactId == sourceFactId && it.targetFactId == targetFactId) ||
                        (it.sourceFactId == targetFactId && it.targetFactId == sourceFactId)
                }
                if (dup) return@withContext
                val scopedStore = storeForScope(scope)
                val src = scopedStore.getById(sourceFactId)
                val tgt = scopedStore.getById(targetFactId)
                scopedLinks.insert(
                    io.zer0.memory.ai.MemoryLinkEntity(
                        sourceFactId = sourceFactId,
                        targetFactId = targetFactId,
                        sourceTitle = src?.fact?.take(60) ?: sourceFactId.toString(),
                        targetTitle = tgt?.fact?.take(60) ?: targetFactId.toString(),
                        linkType = relationType,
                        weight = 0.7f,
                        spaceId = spaceId,
                        scope = scope ?: "main",
                        createdAt = java.time.Instant.now().toString(),
                    ),
                )
            }
            load(scope, spaceId)
        }
    }

    /**
     * v2.6.2(comentarios de los usuarios): Reconstruir manualmente la asociación —— Ejecute un relleno para todos los hechos en el alcance+espacio actual.
     * Para panel de configuración de constelaciones“Restablecer la asociación ahora”llamada de botón.
     */
    fun rebuildEdges(scope: String?, spaceId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val scopedStore = storeForScope(scope)
                val all = if (scope == null) scopedStore.getBySpace(spaceId) else scopedStore.getByScopeAndSpace(scope, spaceId)
                if (all.isEmpty()) return@withContext
                // todos los hechos como“nuevos hechos”Ejecute una vez para complementar los bordes (se omitirán los bordes existentes).
                edgeBuilder?.buildEdges(
                    newFacts = all,
                    existingFacts = all,
                    scope = scope ?: "main",
                    spaceId = spaceId,
                    semanticProvider = semanticProvider,
                )
            }
            load(scope, spaceId)
        }
    }

    fun confirmEdge(edgeId: Long, scope: String? = null, spaceId: String = "default") {
        viewModelScope.launch {
            // Actualmente MemoryLinkEntity no tiene ningún campo confirmado por el usuario,Utilice temporalmente el peso para aumentar la confirmación de la simulación
            // v2.6.2(auditoría de memoria): Ruta según alcance/Espacio actual —— De lo contrario, cuando otro espacio haga clic para confirmar
            // Si no se puede encontrar el borde, o el ID de borde es el mismo, el mapa predeterminado se cambiará por error.
            val scopedLinks = linksForScope(scope)
            val edges = scopedLinks.listBySpaceAndScope(spaceId, scope ?: "main")
            val edge = edges.find { it.id == edgeId } ?: return@launch
            val updated = edge.copy(weight = (edge.weight + 0.2f).coerceAtMost(1.0f))
            withContext(Dispatchers.IO) { scopedLinks.insert(updated) }
            // Actualización parcial
            _state.value = _state.value.copy(
                edges = _state.value.edges.map { if (it.id == edgeId) it.copy(weight = updated.weight) else it },
            )
        }
    }

    // ── Etapa 4: agrupación ──
    private fun generateClusters(nodes: List<MemoryGraphNode>): List<MemoryGraphCluster> {
        // primera edición:Agrupar por categoría,Mismo grupo >=2 nodos cluster
        return nodes.groupBy { it.category }
            .filter { it.value.size >= 2 }
            .map { (category, groupNodes) ->
                MemoryGraphCluster(
                    id = "auto_$category",
                    name = category,
                    nodeIds = groupNodes.map { it.factId }.toSet(),
                )
            }
    }

    companion object {
        private const val MAX_NODES = 500
        private const val MAX_EDGES = 1000
        private const val NODE_TITLE_MAX_LENGTH = 24
    }
}

data class MemoryGraphNode(
    val factId: Long,
    val title: String,
    val category: String,
    val importance: Int,
    val confidence: Float,
    val isPinned: Boolean,
    val isExpired: Boolean,
)

data class MemoryGraphEdge(
    val id: Long,
    val sourceFactId: Long,
    val targetFactId: Long,
    val relationType: String,
    val weight: Float,
    val sourceTitle: String,
    val targetTitle: String,
)

/**
 * Etapa 4: agrupación de memoria(constelación).
 */
data class MemoryGraphCluster(
    val id: String,
    val name: String,
    val nodeIds: Set<Long>,
)
