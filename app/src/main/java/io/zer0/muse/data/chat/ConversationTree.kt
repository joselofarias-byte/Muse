package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import kotlin.uuid.Uuid

/**
 * P0 Modelo de árbol de diálogo (v2: La versión de la pregunta de cada usuario está vinculada de forma independiente a la respuesta del asistente).
 *
 * Estructura:
 * ```
 * Grupo de preguntas UserNode(variantGroupId igual)
 *   ├─ Versión de usuario 1/N (UserVariant)
 *   │    └─ Grupo de respuesta del asistente (múltiples versiones de respuestas generadas por reintentos)
 *   ├─ Versión de usuario 2/N (UserVariant, editor/Resultado del reintento)
 *   │    └─ Grupos de respuesta de asistente separados
 *   └─ ...
 * ```
 *
 * Invariante:
 * - Al cambiar de versión de usuario, los subárboles de asistente se intercambian entre sí sin contaminarse entre sí.
 * - Reintento del asistente = Se agrega una nueva variante de asistente al último grupo de asistentes en la versión de usuario actual.
 * - Editar mensaje de usuario = Mantenga la versión anterior, cree una nueva versión de usuario y coloque un grupo de marcador de posición de asistente vacío.
 * - Persistencia: La versión de usuario consta de [UIMessage.variantGroupId/variantIndex/variantCount] expresar;
 *   Mensaje del asistente [UIMessage.parentGroupId] Apunta al ID del mensaje de la versión del usuario (compatible con datos antiguos montados por ID de grupo).
 */
data class ConversationTree(
    val userNodes: List<UserNode> = emptyList(),
    val selectedUserIndex: Int = 0,
) {

    val selectedUserNode: UserNode?
        get() = userNodes.getOrNull(selectedUserIndex)

    /** El mensaje de usuario seleccionado actualmente. */
    val selectedUserVariant: UIMessage?
        get() = selectedUserNode?.currentVariant?.message

    val lastAssistantNode: AssistantNode?
        get() = selectedUserNode?.currentVariant?.assistantNodes?.lastOrNull()

    val displayMessages: List<UIMessage>
        get() {
            // Múltiples rondas de diálogo que muestran cada ronda en orden.;Varias versiones dentro del mismo grupo de preguntas solo mostrarán la versión seleccionada actualmente.
            return buildList {
                userNodes.forEach { user ->
                    val variant = user.currentVariant ?: return@forEach
                    add(variant.message)
                    variant.assistantNodes.forEach { node -> node.currentVariant?.let { add(it) } }
                }
            }
        }

    /** Todos los mensajes planos para la versión de usuario seleccionada actualmente (incluidas todas las variantes de reintento del asistente), utilizados para conservar ramas durante la reconstrucción del árbol. */
    val selectedVariantFlatMessages: List<UIMessage>
        get() {
            val variant = selectedUserNode?.currentVariant ?: return emptyList()
            return buildList {
                add(variant.message)
                variant.assistantNodes.forEach { node -> node.variants.forEach { add(it) } }
            }
        }

    /** Mensajes planos para todas las versiones de usuario y variantes de reintento del asistente, utilizados para mantener todas las ramas intactas durante la reconstrucción del árbol. */
    val allFlatMessages: List<UIMessage>
        get() = buildList {
            userNodes.forEach { user ->
                user.variants.forEach { variant ->
                    add(variant.message)
                    variant.assistantNodes.forEach { node -> node.variants.forEach { add(it) } }
                }
            }
        }

    /** La información del grupo de sucursales al que pertenece un determinado mensaje se utiliza para el cambio de variantes de representación de la interfaz de usuario. */
    fun branchInfoFor(messageId: Uuid): BranchInfo? {
        userNodes.forEach { user ->
            user.variants.forEach { variant ->
                if (variant.message.id == messageId) {
                    return BranchInfo(
                        groupId = user.currentVariant?.message?.variantGroupId ?: user.groupId,
                        parentGroupId = null,
                        selectIndex = user.selectIndex,
                        branchCount = user.variants.size,
                    )
                }
                variant.assistantNodes.forEach { assistant ->
                    if (assistant.variants.any { it.id == messageId }) {
                        return BranchInfo(
                            groupId = assistant.groupId,
                            parentGroupId = user.currentVariant?.message?.id?.toString()
                                ?: user.currentVariant?.message?.variantGroupId
                                ?: user.groupId,
                            selectIndex = assistant.selectIndex,
                            branchCount = assistant.variants.size,
                        )
                    }
                }
            }
        }
        return null
    }

    /**
     * v1.0.92: Índice completo de información sobre sucursales — Proporciona una ruta de renderizado de alta frecuencia de la interfaz de usuario O(1) Consulta.
     *
     * [branchInfoFor] Una sola consulta es un recorrido de árbol completo.;Cuando se le llama una vez por cuadro por mensaje en la lista de chat
     * costo total O(n²),Vuelva a calcular cada 50 ms durante la transmisión de sesiones largas,Es una de las principales fuentes de retraso.
     *
     * v2.4.6 Refactorizar:El selector de variantes del asistente se cambia a "Espera el último mensaje de toda la ronda de respuestas".
     * La implementación anterior tomó lo mismo. [BranchInfo] Llame al grupo de respuesta**cada artículo**mensaje variante en,y uno
     * Las variantes del asistente pueden contener varios mensajes.(pensar/Herramientas/Texto),Hace que cada mensaje muestre uno
     * conmutador —— Al responder en varios pasos, la flecha aparece debajo del primer elemento y el contenido se superpone y desalinea después de volver a intentarlo.
     * Ahora solo da"El último mensaje del asistente renderizado en esta ronda de respuestas."Asistente para colgar [BranchInfo];
     * El conmutador de versión de usuario todavía se bloquea en los mensajes de usuario(Semántica sin cambios).
     */
    fun buildBranchInfoIndex(): Map<String, BranchInfo> {
        val map = HashMap<String, BranchInfo>()
        userNodes.forEach { user ->
            val userGroupId = user.currentVariant?.message?.variantGroupId ?: user.groupId
            user.variants.forEach { variant ->
                val userParentId = variant.message.id.toString()
                // Cambiador de versión de usuario:Espera los mensajes de los usuarios(Editar/Versión de la pregunta generada al reintentar)
                map.putIfAbsent(
                    userParentId,
                    BranchInfo(
                        groupId = userGroupId,
                        parentGroupId = null,
                        selectIndex = user.selectIndex,
                        branchCount = user.variants.size,
                    ),
                )
                // Conmutador de variante asistente:sólo espera"El último mensaje de esta ronda de respuestas."en.
                // Responder en esta ronda = Todo bajo esta variante de usuario. assistantNodes;
                // Número de variantes"nodo de respuesta"(Nodos con mensajes que no son de herramientas)número de variantes —— Los nodos de herramientas puras no se pueden volver a intentar"Responder".
                // Primero se recupera el último elemento y se recupera la variante actual del nodo de recuperación.(definitivamente será renderizado;Los nodos de herramientas se pueden agregar en tarjetas de grupo),
                // Devuelve la variante actual del último nodo cuando no hay nodos que respondan.
                val replyNode = variant.assistantNodes.lastOrNull { node ->
                    node.variants.any { it.toolCallInfo == null }
                }
                val lastNode = variant.assistantNodes.lastOrNull()
                val anchorNode = replyNode ?: lastNode ?: return@forEach
                val lastMsg = anchorNode.currentVariant ?: return@forEach
                map.putIfAbsent(
                    lastMsg.id.toString(),
                    BranchInfo(
                        groupId = anchorNode.groupId,
                        parentGroupId = userParentId,
                        selectIndex = anchorNode.selectIndex,
                        branchCount = anchorNode.variants.size,
                    ),
                )
            }
        }
        return map
    }

    fun selectUserVariant(userId: String, variantIndex: Int): ConversationTree {
        val index = userNodes.indexOfFirst { it.userId == userId }
        if (index < 0) return this
        val node = userNodes[index]
        val target = node.variants.getOrNull(variantIndex) ?: node.variants.lastOrNull() ?: return this
        return copy(
            userNodes = userNodes.toMutableList().apply {
                this[index] = node.copy(
                    selectIndex = node.variants.indexOfFirst { it.message.id == target.message.id }.coerceAtLeast(0),
                )
            },
            selectedUserIndex = index,
        )
    }

    fun selectAssistantVariant(userGroupId: String, assistantGroupId: String, index: Int): ConversationTree {
        val nodeIndex = userNodes.indexOfFirst { user ->
            val current = user.currentVariant ?: return@indexOfFirst false
            current.message.id.toString() == userGroupId ||
                (current.message.variantGroupId ?: user.groupId) == userGroupId
        }
        if (nodeIndex < 0) return this
        val node = userNodes[nodeIndex]
        val current = node.currentVariant ?: return this

        val updatedVariant = current.copy(
            assistantNodes = current.assistantNodes.map { assistant ->
                if (assistant.groupId == assistantGroupId) assistant.selectVariant(index) else assistant
            },
        )
        return copy(
            userNodes = userNodes.toMutableList().apply {
                this[nodeIndex] = node.copy(
                    variants = node.variants.toMutableList().apply {
                        this[node.selectIndex] = updatedVariant
                    },
                )
            },
        )
    }

    /**
     * v1.0.80 (T-4): Eliminar un solo mensaje del árbol(Árbol de sincronización al eliminar mensajes).
     *
     * Si el árbol no se actualiza después de eliminar el mensaje,reconstruirConversationTree pasará al volver a la sesión
     * mergeRebuildMessages Fusionar mensajes eliminados en el árbol antiguo,resultando en mensajes eliminados"resurrección"
     * (Comentarios de los usuarios:Eliminado y reingresado y todavía está ahí.,Las viejas noticias de repente se vuelven más recientes).
     * Este método toma el mensaje de todas las variantes de usuario./Eliminado de las variantes auxiliares.,Y reorganice el índice de variantes/el recuento de variantes.
     */
    fun removeMessage(messageId: Uuid): ConversationTree {
        var changed = false
        val newNodes = userNodes.mapNotNull { user ->
            val remainingVariants = user.variants.mapNotNull { variant ->
                if (variant.message.id == messageId) {
                    changed = true
                    null
                } else {
                    val newAssistantNodes = variant.assistantNodes.mapNotNull { node ->
                        val remaining = node.variants.filterNot { it.id == messageId }
                        if (remaining.size != node.variants.size) changed = true
                        if (remaining.isEmpty()) {
                            null
                        } else {
                            val reindexedAssistants = remaining.mapIndexed { idx, msg ->
                                if (msg.variantIndex != idx || msg.variantCount != remaining.size) {
                                    changed = true
                                    msg.copy(variantIndex = idx, variantCount = remaining.size)
                                } else {
                                    msg
                                }
                            }
                            node.copy(
                                variants = reindexedAssistants,
                                selectIndex = node.selectIndex.coerceIn(0, reindexedAssistants.lastIndex),
                            )
                        }
                    }
                    if (newAssistantNodes.size != variant.assistantNodes.size) changed = true
                    variant.copy(assistantNodes = newAssistantNodes)
                }
            }
            if (remainingVariants.isEmpty()) {
                changed = true
                null
            } else {
                val reindexed = remainingVariants.mapIndexed { idx, variant ->
                    if (variant.message.variantIndex != idx || variant.message.variantCount != remainingVariants.size) {
                        changed = true
                        variant.copy(
                            message = variant.message.copy(
                                variantIndex = idx,
                                variantCount = remainingVariants.size,
                            ),
                        )
                    } else {
                        variant
                    }
                }
                user.copy(
                    variants = reindexed,
                    selectIndex = user.selectIndex.coerceIn(0, reindexed.lastIndex),
                )
            }
        }
        if (!changed) return this
        if (newNodes.isEmpty()) return ConversationTree()
        val selected = selectedUserIndex.coerceIn(0, newNodes.lastIndex)
        return copy(userNodes = newNodes, selectedUserIndex = selected)
    }

    /** Vuelva a intentar la última respuesta del asistente en la versión de usuario actual y devuelva el nuevo árbol + mensajes nuevos que deben conservarse. */
    fun retryLastAssistant(): TreeUpdate {
        val user = selectedUserNode ?: return TreeUpdate(this, null, null)
        val variant = user.currentVariant ?: return TreeUpdate(this, null, null)
        val userMsg = variant.message
        val userGroupId = userMsg.variantGroupId ?: user.groupId

        if (variant.assistantNodes.isEmpty()) {
            val groupId = "asg_" + Uuid.random()
            // v1.0.85 (T-1): Reenviar el nuevo mensaje con creadoAt"Mensajes de usuario+1ms"en lugar de la hora actual —
            // Usado antes System.currentTimeMillis(),El tiempo del mensaje salta al final después de la retransmisión.,
            // ConversationTree Cuélgalo debajo del último mensaje de usuario.(La primera respuesta se convierte en la última.).
            val newMsg = UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = userMsg.createdAt + 1,
                variantGroupId = groupId,
                variantIndex = 0,
                variantCount = 1,
                parentGroupId = userMsg.id.toString(),
            )
            val updatedVariant = variant.copy(
                assistantNodes = listOf(AssistantNode(groupId, listOf(newMsg), 0)),
            )
            return TreeUpdate(
                tree = replaceCurrentVariant(updatedVariant),
                newMessage = newMsg,
                changedGroupId = groupId,
            )
        }

        // v1.0.92: Sólo reconoce "nodos de respuesta"— Nodos donde existen variantes de mensajes que no son herramientas. Mensaje en pantalla de herramienta
        // (toolCallInfo != null) es un nodo independiente,No es una 'respuesta' reintentable;La implementación anterior toma directamente last(),
        // Cuando se llama a una herramienta, la nueva variante se agregará al "Grupo de herramientas",La tarjeta de herramientas se convierte en "Herramientas/1/2 variante de "Responder".
        val replyIndex = variant.assistantNodes.indexOfLast { node ->
            node.variants.any { it.toolCallInfo == null }
        }
        if (replyIndex < 0) {
            // Esta ronda solo tiene novedades de herramientas(Generar interrupción/detenido):Aún no hay respuesta para volver a intentarlo,
            // Agregar un nuevo grupo de respuesta al final,Vuelva a intentar comenzar de nuevo con "Respuesta vacía".
            val groupId = "asg_" + Uuid.random()
            val anchorAt = variant.assistantNodes.last().currentVariant?.createdAt
                ?: userMsg.createdAt
            val newMsg = UIMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                createdAt = anchorAt + 1,
                variantGroupId = groupId,
                variantIndex = 0,
                variantCount = 1,
                parentGroupId = userMsg.id.toString(),
            )
            val updatedVariant = variant.copy(
                assistantNodes = variant.assistantNodes + AssistantNode(groupId, listOf(newMsg), 0),
            )
            return TreeUpdate(
                tree = replaceCurrentVariant(updatedVariant),
                newMessage = newMsg,
                changedGroupId = groupId,
            )
        }

        val last = variant.assistantNodes[replyIndex]
        val newIndex = last.variants.size
        val newCount = newIndex + 1
        // v1.0.85 (T-1): La nueva variante hereda el primer mensaje del grupo original. createdAt(Mantén tu posición estable),
        // en lugar de la hora actual — De lo contrario, el tiempo de respuesta reenviado salta al final y la posición de visualización se desordena.
        val baseCreatedAt = last.variants.firstOrNull()?.createdAt ?: last.currentVariant?.createdAt ?: userMsg.createdAt
        val newMsg = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = baseCreatedAt + newIndex,
            variantGroupId = last.groupId,
            variantIndex = newIndex,
            variantCount = newCount,
            parentGroupId = userMsg.id.toString(),
        )
        val updatedAssistant = last.copy(
            variants = last.variants.map { it.copy(variantCount = newCount) } + newMsg,
            selectIndex = newIndex,
        )
        val updatedVariant = variant.copy(
            // v1.0.92: Reemplace el nodo de respuesta reintentado por subíndice. Usar antes de reparar dropLast(1) + copiar,
            // Equivalente sólo si el último resulta ser el último elemento de la lista;Cuando el nodo de herramienta se clasifica después del nodo de respuesta,
            // La copia actualizada se agregará al final y el grupo de respuesta original permanecerá en su estado anterior.(Variantes perdidas).
            assistantNodes = variant.assistantNodes.toMutableList().apply {
                this[replyIndex] = updatedAssistant
            },
        )
        return TreeUpdate(
            tree = replaceCurrentVariant(updatedVariant),
            newMessage = newMsg,
            changedGroupId = last.groupId,
        )
    }

    /** Editar mensaje de usuario: mantenga la versión anterior, cree una nueva versión de usuario y coloque un grupo de marcador de posición de asistente vacío. */
    fun editUserMessage(messageId: Uuid, newContent: String): EditUpdate? {
        val nodeIndex = userNodes.indexOfFirst { user ->
            user.variants.any { it.message.id == messageId && it.message.role == MessageRole.USER }
        }
        if (nodeIndex < 0) return null
        val user = userNodes[nodeIndex]

        val userGroupId = user.currentVariant?.message?.variantGroupId ?: user.groupId
        val newIndex = user.variants.size
        val newCount = newIndex + 1
        val now = System.currentTimeMillis()
        // Se requiere createAt para nuevas variantes.**Publica el mensaje original**, no se puede usar ahora:
        // Reconstruir árbol (fromMessages) Ordenado por creado en, usar ahora ordenará este elemento hasta el final de toda la sesión,
        // Entonces "editar el del medio" parece "publicar otro al final"—— Esto es exactamente lo que informaron los usuarios.
        // con la variante auxiliar (retryAssistant) mantener el mismo conjunto de prácticas:base + número de serie.
        val baseCreatedAt = user.currentVariant?.message?.createdAt ?: now
        val newUserMsg = UIMessage(
            role = MessageRole.USER,
            content = newContent,
            createdAt = baseCreatedAt + newIndex,
            variantGroupId = userGroupId,
            variantIndex = newIndex,
            variantCount = newCount,
        )
        val placeholderGroupId = "asg_" + Uuid.random()
        val placeholder = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = newUserMsg.createdAt + 1,
            variantGroupId = placeholderGroupId,
            variantIndex = 0,
            variantCount = 1,
            parentGroupId = newUserMsg.id.toString(),
        )
        val newVariant = UserVariant(
            message = newUserMsg,
            assistantNodes = listOf(AssistantNode(placeholderGroupId, listOf(placeholder), 0)),
        )
        val updatedUser = user.copy(
            variants = user.variants.map { it.copy(message = it.message.copy(variantCount = newCount)) } + newVariant,
            selectIndex = newIndex,
        )
        val newNodes = userNodes.toMutableList().apply { this[nodeIndex] = updatedUser }
        return EditUpdate(
            tree = copy(userNodes = newNodes, selectedUserIndex = nodeIndex),
            newUserMessage = newUserMsg,
            newAssistantPlaceholder = placeholder,
        )
    }

    /**
     * v2.5.3 (P3-1): Editar mensaje del asistente —— Siempre se agregan nodos de nueva versión y las versiones antiguas se conservan permanentemente en la rama.
     *
     * con [retryLastAssistant] La diferencia: reintentar es“Regenerar una variante de respuesta vacía”, el editor es“Utilice contenido escrito por los usuarios.
     * como nueva variante de respuesta”, ambos siguen“No sobrescribir, solo agregar”Según el principio de múltiples sucursales, siempre se pueden rastrear las respuestas antiguas.
     *
     * @return null Indica que no se encuentra el mensaje del asistente de destino; de lo contrario, se devuelve un nuevo árbol + un nuevo mensaje (requiere persistencia).
     */
    fun editAssistantMessage(messageId: Uuid, newContent: String): TreeUpdate? {
        val nodeIndex =
            userNodes.indexOfFirst { user ->
                user.variants.any { v -> v.assistantNodes.any { node -> node.variants.any { it.id == messageId } } }
            }
        val user = userNodes.getOrNull(nodeIndex)
        val variant = user?.currentVariant
        val replyIndex = variant?.assistantNodes?.indexOfFirst { node -> node.variants.any { it.id == messageId } } ?: -1
        val node = variant?.assistantNodes?.getOrNull(replyIndex)
        val original = node?.variants?.firstOrNull { it.id == messageId }
        if (variant == null || node == null || original == null) return null
        val newIndex = node.variants.size
        val newCount = newIndex + 1
        // La nueva versión hereda el original creado en la línea base + índice, manteniendo la posición estable (mismo calibre que la regeneración).
        val baseCreatedAt = node.variants.firstOrNull()?.createdAt ?: original.createdAt
        val edited =
            original.copy(
                id = Uuid.random(),
                content = newContent,
                reasoning = null,
                createdAt = baseCreatedAt + newIndex,
                variantGroupId = node.groupId,
                variantIndex = newIndex,
                variantCount = newCount,
            )
        val updatedNode =
            node.copy(
                variants = node.variants.map { it.copy(variantCount = newCount) } + edited,
                selectIndex = newIndex,
            )
        val updatedVariant =
            variant.copy(
                assistantNodes = variant.assistantNodes.toMutableList().apply { this[replyIndex] = updatedNode },
            )
        return TreeUpdate(
            tree = replaceCurrentVariant(updatedVariant),
            newMessage = edited,
            changedGroupId = node.groupId,
        )
    }

    private fun replaceCurrentVariant(updated: UserVariant): ConversationTree {
        val user = selectedUserNode ?: return this
        return copy(
            userNodes = userNodes.toMutableList().apply {
                val idx = indexOfFirst { it.userId == user.userId }
                if (idx >= 0) {
                    this[idx] = user.copy(
                        variants = user.variants.toMutableList().apply {
                            this[user.selectIndex] = updated
                        },
                    )
                }
            },
        )
    }

    data class BranchInfo(
        val groupId: String,
        val parentGroupId: String?,
        val selectIndex: Int,
        val branchCount: Int,
    )

    data class TreeUpdate(
        val tree: ConversationTree,
        val newMessage: UIMessage?,
        val changedGroupId: String?,
    )

    data class EditUpdate(
        val tree: ConversationTree,
        val newUserMessage: UIMessage?,
        val newAssistantPlaceholder: UIMessage?,
    )

    /** Grupo de preguntas de usuario: se comparten varias versiones de usuario del mismo varianteGroupId“Ubicación de la pregunta”. */
    data class UserNode(
        val userId: String,
        val groupId: String,
        val variants: List<UserVariant>,
        val selectIndex: Int = variants.lastIndex.coerceAtLeast(0),
    ) {
        val currentVariant: UserVariant?
            get() = variants.getOrNull(selectIndex)

        fun selectVariant(index: Int): UserNode = copy(selectIndex = index.coerceIn(0, variants.lastIndex))
    }

    /** Versión de usuario: un mensaje de usuario + su propio grupo de respuesta de asistente. */
    data class UserVariant(
        val message: UIMessage,
        val assistantNodes: List<AssistantNode> = emptyList(),
    )

    /** Grupo de respuesta del asistente: múltiples variaciones de reintento de la misma posición de respuesta. */
    data class AssistantNode(
        val groupId: String,
        val variants: List<UIMessage>,
        val selectIndex: Int = variants.lastIndex.coerceAtLeast(0),
    ) {
        val currentVariant: UIMessage?
            get() = variants.getOrNull(selectIndex)

        fun selectVariant(index: Int): AssistantNode = copy(selectIndex = index.coerceIn(0, variants.lastIndex))
    }

    companion object {
        /**
         * Reconstrucción de un árbol de conversación a partir de una lista de mensajes plana.
         *
         * Compatible con dos tipos de datos:
         * 1. Nuevos datos:assistant cinta de mensaje [UIMessage.parentGroupId] = ID de mensaje de versión de usuario, montaje preciso.
         * 2. Datos antiguos:parentGroupId es el ID del grupo de usuarios y está vinculado a la última versión de usuario del grupo.
         */
        fun build(messages: List<UIMessage>, previous: ConversationTree? = null): ConversationTree {
            if (messages.isEmpty()) return ConversationTree()
            // La reconstrucción del árbol no puede confiar en el orden de inserción de la Lista actual de la persona que llama: puede ser de una combinación de instantáneas anterior,
            // Retroalimentación paginada o transmisión de actualizaciones asincrónicas. Organícelos primero en orden persistente para evitar que los mensajes antiguos se coloquen al principio.
            val orderedMessages = orderConversationMessages(messages)

            val groups = LinkedHashMap<String, MutableList<UIMessage>>()
            val groupOrder = LinkedHashSet<String>()
            orderedMessages.forEach { msg ->
                val gid = msg.variantGroupId ?: msg.id.toString()
                groups.getOrPut(gid) { mutableListOf() }.add(msg)
                groupOrder.add(gid)
            }

            // v1.0.63: Índice de sucursales normalizado/Cuente, corrija el mismo conjunto de índices de variantes duplicados en datos históricos.
            groups.forEach { (_, msgs) ->
                val sorted = msgs.sortedWith(compareBy({ it.variantIndex }, { it.createdAt }))
                val normalized = sorted.mapIndexed { idx, msg ->
                    msg.copy(variantIndex = idx, variantCount = sorted.size)
                }
                msgs.clear()
                msgs.addAll(normalized)
            }

            val userNodes = mutableListOf<UserNode>()
            val userNodeByGroup = HashMap<String, Int>()
            val userVariantById = HashMap<String, UserVariant>()
            var lastUserGroupId: String? = null

            groupOrder.forEach { gid ->
                val groupMsgs = groups.getValue(gid)
                val first = groupMsgs.first()
                when (first.role) {
                    MessageRole.USER -> {
                        val variants = groupMsgs.map { UserVariant(message = it) }
                        if (gid in userNodeByGroup) {
                            val idx = userNodeByGroup.getValue(gid)
                            val existing = userNodes[idx]
                            val merged = mergeUserVariants(existing.variants, variants)
                            userNodes[idx] = existing.copy(variants = merged)
                        } else {
                            userNodes += UserNode(
                                userId = "usn_" + Uuid.random(),
                                groupId = gid,
                                variants = variants,
                                selectIndex = variants.lastIndex.coerceAtLeast(0),
                            )
                            userNodeByGroup[gid] = userNodes.lastIndex
                        }
                        userNodes[userNodeByGroup.getValue(gid)].variants.forEach { variant ->
                            userVariantById[variant.message.id.toString()] = variant
                        }
                        lastUserGroupId = gid
                    }
                    MessageRole.ASSISTANT -> {
                        val parentRef = groupMsgs.firstNotNullOfOrNull { it.parentGroupId }
                        val targetVariant = parentRef?.let { ref ->
                            userVariantById[ref] ?: userNodeByGroup[ref]?.let { idx -> userNodes[idx].variants.lastOrNull() }
                        } ?: lastUserGroupId?.let { gidRef ->
                            userNodeByGroup[gidRef]?.let { idx ->
                                val node = userNodes[idx]
                                // v1.x Reparación: Los mensajes antiguos del asistente de datos sin parentGroupId se atribuyen según el momento de creación ——
                                // espera"La última versión de usuario creada antes de este mensaje del asistente"en.
                                // Anteriormente, recuperar directamente la última versión,El usuario edita el mensaje(Nueva versión V1)después,
                                // Se cuelgan mensajes antiguos del asistente V1,con marcador de posición/Nuevas respuestas empatadas,Se muestra como"Mensaje del asistente dividido en varios mensajes".
                                val groupCreatedAt = groupMsgs.first().createdAt
                                node.variants.lastOrNull { it.message.createdAt <= groupCreatedAt }
                                    ?: node.variants.lastOrNull()
                            }
                        }
                        if (targetVariant == null) return@forEach
                        val messageId = targetVariant.message.id.toString()
                        replaceUserVariant(userNodes, userVariantById, messageId) { variant ->
                            val existingIdx = variant.assistantNodes.indexOfFirst { it.groupId == gid }
                            if (existingIdx >= 0) {
                                variant.copy(
                                    assistantNodes = variant.assistantNodes.toMutableList().apply {
                                        this[existingIdx] = this[existingIdx].copy(
                                            variants = mergeVariants(this[existingIdx].variants, groupMsgs),
                                        )
                                    },
                                )
                            } else {
                                variant.copy(
                                    assistantNodes = variant.assistantNodes + AssistantNode(
                                        groupId = gid,
                                        variants = groupMsgs,
                                        selectIndex = groupMsgs.lastIndex.coerceAtLeast(0),
                                    ),
                                )
                            }
                        }
                    }
                    else -> {
                        val targetVariant = lastUserGroupId?.let { gidRef ->
                            userNodeByGroup[gidRef]?.let { idx -> userNodes[idx].variants.lastOrNull() }
                        } ?: return@forEach
                        val messageId = targetVariant.message.id.toString()
                        replaceUserVariant(userNodes, userVariantById, messageId) { variant ->
                            variant.copy(
                                assistantNodes = variant.assistantNodes + AssistantNode(
                                    groupId = gid,
                                    variants = groupMsgs,
                                    selectIndex = groupMsgs.lastIndex.coerceAtLeast(0),
                                ),
                            )
                        }
                    }
                }
            }

            return restoreSelection(
                ConversationTree(userNodes = userNodes, selectedUserIndex = userNodes.lastIndex.coerceAtLeast(0)),
                previous,
            )
        }

        private fun replaceUserVariant(
            userNodes: MutableList<UserNode>,
            userVariantById: MutableMap<String, UserVariant>,
            messageId: String,
            transform: (UserVariant) -> UserVariant,
        ) {
            for (i in userNodes.indices) {
                val node = userNodes[i]
                val idx = node.variants.indexOfFirst { it.message.id.toString() == messageId }
                if (idx >= 0) {
                    val newVariant = transform(node.variants[idx])
                    userNodes[i] = node.copy(
                        variants = node.variants.toMutableList().apply { this[idx] = newVariant },
                    )
                    userVariantById[messageId] = newVariant
                    return
                }
            }
        }

        private fun mergeUserVariants(existing: List<UserVariant>, incoming: List<UserVariant>): List<UserVariant> {
            val merged = LinkedHashMap<String, UserVariant>()
            (existing + incoming).forEach { variant ->
                merged[variant.message.id.toString()] = variant
            }
            return merged.values.sortedWith(
                compareBy({ it.message.variantIndex }, { it.message.createdAt }),
            )
        }

        private fun mergeVariants(existing: List<UIMessage>, incoming: List<UIMessage>): List<UIMessage> {
            val merged = LinkedHashMap<String, UIMessage>()
            (existing + incoming).forEach { msg -> merged[msg.id.toString()] = msg }
            return merged.values.sortedWith(compareBy({ it.variantIndex }, { it.createdAt }))
        }
    }
}

/**
 * Al reconstruir el árbol, combine todas las ramas del árbol antiguo con la pantalla plana actual para garantizar que no se pierdan los mensajes nuevos y que se vuelvan a intentar los mensajes antiguos./Las ramas de edición se conservan.
 *
 * @param summarizedIds v2.3.2 (D): Mensajes que han sido anulados por resúmenes contextuales id —— Estos textos originales ya no se fusionan del antiguo árbol,
 *        De lo contrario"Resumen + texto original"permanecerá en contexto al mismo tiempo(token cuenta doble), la compresión no es nada.
 *        (persona que llama desde `io.zer0.muse.transformer.CompressionSummaryStore` tomar;Conjunto vacío predeterminado = Comportamiento antiguo.)
 */
fun mergeRebuildMessages(tree: ConversationTree, current: List<UIMessage>, summarizedIds: Set<String> = emptySet()): List<UIMessage> {
    // Snapshot Sólo se guardan las selecciones de sucursales.SnapshotStore Para restaurar la estructura del árbol, se construirá createdAt=0,
    // content Nodos de usuario virtuales con identificaciones aleatorias y vacías; Estos nodos no deben mezclarse con mensajes reales, de lo contrario serán
    // Organizado en la parte superior de la lista, el rendimiento es el siguiente“Las viejas noticias pasan al frente”.
    val persistedTreeMessages =
        tree.allFlatMessages.filter { it.createdAt > 0L && it.id.toString() !in summarizedIds }
    if (persistedTreeMessages.isEmpty()) return orderConversationMessages(current)
    val merged = linkedMapOf<String, UIMessage>()
    // El árbol antiguo sólo es responsable de complementar las ramas faltantes de la lista actual; el contenido con la misma identificación debe ser ganado por la lista actual.
    // De lo contrario, la respuesta final transmitida será sobrescrita por la instantánea del árbol anterior, lo que provocará que el texto parpadee y luego desaparezca.
    val source = persistedTreeMessages + current
    source.forEach { message -> merged[message.id.toString()] = message }
    return orderConversationMessages(merged.values.toList())
}

/** La única regla de clasificación de memoria para los mensajes de sesión debe ser coherente con MessageDao/MessageProjector. */
fun orderConversationMessages(messages: List<UIMessage>): List<UIMessage> {
    fun UIMessage.stableOrder(): Long? = when {
        commitSeq > 0L -> commitSeq
        seq > 0L -> seq
        else -> null
    }

    // antigua importación/Es posible que las instantáneas antiguas no tengan ninguna secuencia. En este momento, es posible que no se confíe en createAt,
    // No se permite la reorganización incondicional; el orden pasado por la base de datos es el único orden disponible.
    if (messages.none { it.stableOrder() != null }) return messages

    // v2.3.2: Cambiar a"Primero calcule una clave para cada mensaje.,Ordenar por una sola clave" —— Comparación por pares de implementación original,si"Un lado está ordenado y el otro está desordenado."
    // un par regresó createdAt,Tres mensajes pueden formar un círculo.(Medición real A(seq=10,createdAt=3000),B(seq=0,createdAt=1000),
    // C(seq=20,createdAt=100) se obtendrá al mismo tiempo A>C,A<B,B<C),TimSort posiblemente vender
    // "Comparison method violates its general contract!" O dar una secuencia inestable.
    // gobernante:Los mensajes ordenados ocupan un número par de posiciones según la clasificación secuencial(seq Prioridad,consistente con lo existente);
    //       Botón de mensaje desordenado"createdAt El número de mensajes ordenados antes"Ocupa un número impar de dígitos(Aún inserte el período de tiempo correcto según el tiempo.).
    // Las claves se calculan una vez antes de ordenar,Por lo tanto el comparador es estrictamente orden total(transitivo, antisimétrico, reflexivo),No más dependencia del orden de comparación.
    val ordered = messages.filter { it.stableOrder() != null }.sortedBy { it.stableOrder() }
    val unordered = messages.filter { it.stableOrder() == null }
    val keys = HashMap<String, Long>(messages.size)
    ordered.forEachIndexed { index, message -> keys[message.id.toString()] = index * 2L }
    val orderedTimes = ordered.map { it.createdAt }.sorted()
    unordered.forEach { message ->
        val found = orderedTimes.binarySearch { it.compareTo(message.createdAt) }
        val earlierCount = if (found >= 0) found else -(found + 1)
        keys[message.id.toString()] = earlierCount * 2L - 1
    }
    return messages.sortedWith(
        compareBy({ keys[it.id.toString()] ?: Long.MAX_VALUE }, { it.createdAt }, { it.id.toString() }),
    )
}

private fun restoreSelection(tree: ConversationTree, previous: ConversationTree?): ConversationTree {
    if (previous == null || previous.userNodes.isEmpty()) return tree
    val previousUserGroup = previous.selectedUserNode?.let {
        it.currentVariant?.message?.variantGroupId ?: it.groupId
    }
    // Cuando se agrega una nueva ronda de mensajes de usuario,Corta la capa seleccionada a la última ronda.,Asegúrese de que reintentar/continuar chat apunte a mensajes nuevos;
    // Al cambiar de versión dentro del mismo grupo de preguntas, se conservará el índice seleccionado anteriormente.
    val selectedUserIndex = if (tree.userNodes.size > previous.userNodes.size) {
        tree.userNodes.lastIndex
    } else {
        tree.userNodes.indexOfFirst { node ->
            (node.currentVariant?.message?.variantGroupId ?: node.groupId) == previousUserGroup
        }.coerceAtLeast(0)
    }
    val restoredNodes = tree.userNodes.map { user ->
        val prevUser = previous.userNodes.firstOrNull { prev ->
            (prev.currentVariant?.message?.variantGroupId ?: prev.groupId) ==
                (user.currentVariant?.message?.variantGroupId ?: user.groupId)
        }
        val userSelect = prevUser?.selectIndex?.let { index ->
            if (user.variants.isEmpty()) 0 else index.coerceIn(0, user.variants.lastIndex)
        } ?: user.selectIndex
        val selectedVariant = user.variants.getOrNull(userSelect)
        val prevVariant = prevUser?.variants?.getOrNull(prevUser.selectIndex)
        val assistants = selectedVariant?.assistantNodes?.map { assistant ->
            val prevAssistant = prevVariant?.assistantNodes?.firstOrNull { it.groupId == assistant.groupId }
            val assistantSelect = prevAssistant?.selectIndex?.let { index ->
                if (assistant.variants.isEmpty()) 0 else index.coerceIn(0, assistant.variants.lastIndex)
            } ?: assistant.selectIndex
            assistant.copy(selectIndex = assistantSelect)
        } ?: selectedVariant?.assistantNodes ?: emptyList()
        val newVariants = user.variants.toMutableList().apply {
            if (selectedVariant != null && userSelect in indices) {
                this[userSelect] = selectedVariant.copy(assistantNodes = assistants)
            }
        }
        user.copy(selectIndex = userSelect, variants = newVariants)
    }
    return tree.copy(userNodes = restoredNodes, selectedUserIndex = selectedUserIndex)
}
