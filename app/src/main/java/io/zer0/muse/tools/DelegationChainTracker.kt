package io.zer0.muse.tools

import io.zer0.muse.ui.taskcard.DelegationNodeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * v1.201: Rastreador de enlaces delegados.
 *
 * Mantener el estado del enlace de todas las solicitudes delegadas dentro de la sesión actual,Para visualización de UI en tiempo real.
 * Un requestId corresponde a un nodo de enlace(Puede contener subResultados para formar una estructura de árbol.).
 *
 * v1.202 Expandir:
 *  - [onDelegationStarted] apoyo parentRequestId(Predeterminado null),Cuando no esté vacío, pon el nuevo nodo.
 *    Insertar recursivamente en los subnodos del nodo principal como nodo secundario(en lugar de la parte superior _chains),
 *    Habilite la estructura de árbol del flujo de trabajo del equipo para que se presente en la interfaz de usuario.
 *  - Nuevo [setSubNodes] / [updateSubNode],Para que TeamWorkflowExecutor se complete/Actualizar nodos secundarios.
 */
class DelegationChainTracker {

    /** nodo de delegación única(Corresponde a una llamada de delegado_agente). */
    data class ChainNode(
        val requestId: String,
        val parentRequestId: String?,
        val task: String,
        val targetType: String, // "assistant" | "team"
        val targetId: String,
        val targetName: String,
        val status: DelegationNodeStatus,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val errorMessage: String? = null,
        val resultPreview: String? = null, // Vista previa de resultados(primeras 200 palabras)
        val subNodes: List<ChainNode> = emptyList(),
    )

    private val _chains = MutableStateFlow<Map<String, ChainNode>>(emptyMap())
    val chains: StateFlow<Map<String, ChainNode>> = _chains.asStateFlow()

    /**
     * Comienza la delegación récord.
     *
     * @param parentRequestId delegación de padres id(Pasado cuando los miembros del flujo de trabajo del equipo ejecutan);Cuando es nulo, como nodo superior
     */
    fun onDelegationStarted(
        requestId: String,
        parentRequestId: String? = null,
        task: String,
        targetType: String,
        targetId: String,
        targetName: String,
    ) {
        val node = ChainNode(
            requestId = requestId,
            parentRequestId = parentRequestId,
            task = task,
            targetType = targetType,
            targetId = targetId,
            targetName = targetName,
            status = DelegationNodeStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
        )
        _chains.update { current ->
            if (parentRequestId == null) {
                // nodo superior:poner directamente _chains
                current + (requestId to node)
            } else {
                // nodo hijo:Encuentre recursivamente el nodo padre y únase a él subNodes(No poner la capa superior. _chains)
                insertSubNode(current, parentRequestId, node) ?: current
            }
        }
    }

    /** Delegación récord completada. */
    fun onDelegationFinished(
        requestId: String,
        success: Boolean,
        resultText: String,
        error: String? = null,
        subResults: List<DelegationContract.DelegationResult> = emptyList(),
    ) {
        _chains.update { current ->
            updateNode(current, requestId) { node ->
                node.copy(
                    status = if (success) {
                        DelegationNodeStatus.COMPLETED
                    } else {
                        DelegationNodeStatus.FAILED
                    },
                    finishedAt = System.currentTimeMillis(),
                    errorMessage = error,
                    resultPreview = resultText.take(200),
                )
            } ?: current
        }
    }

    /**
     * Llenar nodos secundarios(Resultados para cada miembro del flujo de trabajo del equipo.).
     *
     * Encontrado en _cadenas [requestId] Nodo correspondiente,establecer su subNodes,Actualizaciones atómicas de StateFlow.
     */
    fun setSubNodes(requestId: String, subNodes: List<ChainNode>) {
        _chains.update { current ->
            updateNode(current, requestId) { node ->
                node.copy(subNodes = subNodes)
            } ?: current
        }
    }

    /**
     * Actualizar el estado de un solo nodo hijo(Se llama cuando un miembro del flujo de trabajo completa).
     *
     * Buscar nodo padre [parentRequestId] Correspondiente en los subNodos de [childRequestId] nodo,Estado de actualización.
     */
    fun updateSubNode(parentRequestId: String, childRequestId: String, success: Boolean, resultText: String?, error: String?) {
        _chains.update { current ->
            updateNode(current, parentRequestId) { parent ->
                val newSubs = parent.subNodes.map { child ->
                    if (child.requestId == childRequestId) {
                        child.copy(
                            status = if (success) {
                                DelegationNodeStatus.COMPLETED
                            } else {
                                DelegationNodeStatus.FAILED
                            },
                            finishedAt = System.currentTimeMillis(),
                            resultPreview = resultText?.take(200),
                            errorMessage = error,
                        )
                    } else {
                        child
                    }
                }
                parent.copy(subNodes = newSubs)
            } ?: current
        }
    }

    /** enlace claro(Llamado al cambiar de sesión.). */
    fun clear() {
        _chains.value = emptyMap()
    }

    /** Obtener el nodo de enlace superior(parentRequestId == null). */
    fun getRoots(): List<ChainNode> = _chains.value.values
        .filter { it.parentRequestId == null }
        .sortedBy { it.startedAt }

    /**
     * Busque recursivamente el nodo principal y agregue el nodo secundario a sus subnodos.
     * Devuelve nulo para indicar que no se encontró el nodo principal.
     */
    private fun insertSubNode(nodes: Map<String, ChainNode>, parentRequestId: String, child: ChainNode): Map<String, ChainNode>? {
        // gran éxito
        val parent = nodes[parentRequestId]
        if (parent != null) {
            val updatedParent = parent.copy(subNodes = parent.subNodes + child)
            return nodes + (parentRequestId to updatedParent)
        }
        // Buscar recursivamente en subNodos
        var updated = false
        val newMap = nodes.mapValues { (_, node) ->
            val newSubs = insertSubNodeInList(node.subNodes, parentRequestId, child)
            if (newSubs !== node.subNodes) {
                updated = true
                node.copy(subNodes = newSubs)
            } else {
                node
            }
        }
        return if (updated) newMap else null
    }

    /** en [list] Encuentre el nodo principal de forma recursiva e inserte el nodo secundario;Si no se modifica, se devuelve la referencia de la lista original. */
    private fun insertSubNodeInList(list: List<ChainNode>, parentRequestId: String, child: ChainNode): List<ChainNode> {
        var updated = false
        val newList = list.map { node ->
            if (node.requestId == parentRequestId) {
                updated = true
                node.copy(subNodes = node.subNodes + child)
            } else {
                val newSubs = insertSubNodeInList(node.subNodes, parentRequestId, child)
                if (newSubs !== node.subNodes) {
                    updated = true
                    node.copy(subNodes = newSubs)
                } else {
                    node
                }
            }
        }
        return if (updated) newList else list
    }

    /**
     * Busque y actualice nodos de forma recursiva.
     * Devuelve nulo si no se encuentra [requestId] nodo correspondiente.
     */
    private fun updateNode(
        nodes: Map<String, ChainNode>,
        requestId: String,
        transform: (ChainNode) -> ChainNode,
    ): Map<String, ChainNode>? {
        // gran éxito
        val node = nodes[requestId]
        if (node != null) {
            return nodes + (requestId to transform(node))
        }
        // Buscar recursivamente en subNodos
        var updated = false
        val newMap = nodes.mapValues { (_, n) ->
            val newSubs = updateNodeInList(n.subNodes, requestId, transform)
            if (newSubs !== n.subNodes) {
                updated = true
                n.copy(subNodes = newSubs)
            } else {
                n
            }
        }
        return if (updated) newMap else null
    }

    /** en [list] Buscar y actualizar nodos de forma recursiva;Si no se modifica, se devuelve la referencia de la lista original. */
    private fun updateNodeInList(list: List<ChainNode>, requestId: String, transform: (ChainNode) -> ChainNode): List<ChainNode> {
        var updated = false
        val newList = list.map { node ->
            if (node.requestId == requestId) {
                updated = true
                transform(node)
            } else {
                val newSubs = updateNodeInList(node.subNodes, requestId, transform)
                if (newSubs !== node.subNodes) {
                    updated = true
                    node.copy(subNodes = newSubs)
                } else {
                    node
                }
            }
        }
        return if (updated) newList else list
    }
}
