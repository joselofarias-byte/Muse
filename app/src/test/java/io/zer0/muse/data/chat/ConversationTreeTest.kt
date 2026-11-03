package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTreeTest {

    private fun user(content: String, group: String? = null, index: Int = 0, count: Int = 1, at: Long = System.currentTimeMillis()) =
        UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = content,
            createdAt = at,
            variantGroupId = group,
            variantIndex = index,
            variantCount = count,
        )

    private fun assistant(
        content: String,
        group: String? = null,
        index: Int = 0,
        count: Int = 1,
        parentGroup: String? = null,
        at: Long = System.currentTimeMillis(),
    ) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        content = content,
        createdAt = at,
        variantGroupId = group,
        variantIndex = index,
        variantCount = count,
        parentGroupId = parentGroup,
    )

    @Test
    fun build_createsTwoLevelTree() {
        val u = user("Hola")
        val a = assistant("¡Hola!", group = "ag1", parentGroup = u.id.toString(), at = u.createdAt + 1)

        val tree = ConversationTree.build(listOf(u, a))

        assertEquals(1, tree.userNodes.size)
        assertEquals(u.id, tree.selectedUserVariant?.id)
        assertEquals(1, tree.userNodes.first().currentVariant?.assistantNodes?.size)
        assertEquals(a.id, tree.userNodes.first().currentVariant?.assistantNodes?.first()?.currentVariant?.id)
        assertEquals(listOf(u.id, a.id), tree.displayMessages.map { it.id })
    }

    @Test
    fun build_separateUserNodes_showsAllTurnsInOrder() {
        val u1 = user("Hola")
        val a1 = assistant("responder1", group = "ag1", parentGroup = u1.id.toString(), at = u1.createdAt + 1)
        val u2 = user("Prueba de respuesta de cotización")
        val a2 = assistant("responder2", group = "ag2", parentGroup = u2.id.toString(), at = u2.createdAt + 1)

        val tree = ConversationTree.build(listOf(u1, a1, u2, a2))

        assertEquals(2, tree.userNodes.size)
        assertEquals(
            listOf("Hola", "responder1", "Prueba de respuesta de cotización", "responder2"),
            tree.displayMessages.map { it.content },
        )
        assertEquals("Prueba de respuesta de cotización", tree.selectedUserVariant?.content)
    }

    @Test
    fun build_legacyAssistantWithoutParentGroup_attachesByCreationTime() {
        // Editar escena: Mensajes de usuario u0 -> asistente a1(datos antiguos,Ninguno parentGroupId)
        // Después de editar: Aparece nueva versión de usuario u1, nueva respuesta a2(parentGroupId = u1.id)
        // El antiguo asistente a1 debe colgarse debajo de la versión anterior u0., No porque"Obtener la última versión"Colgar en u1 provoca la división del mensaje
        val u0 = user("escribe un poema", at = 1000L)
        val a1 = assistant("Antigua respuesta", group = "ag1", at = 1001L) // datos antiguos: Ninguno parentGroupId
        val u1 = user("Cambiar a escribir letras", group = u0.id.toString(), index = 1, count = 2, at = 2000L)
        val a2 = assistant("nueva respuesta", group = "ag2", parentGroup = u1.id.toString(), at = 2001L)

        val tree = ConversationTree.build(listOf(u0, a1, u1, a2))

        assertEquals(1, tree.userNodes.size)
        val node = tree.userNodes.first()
        assertEquals(2, node.variants.size)
        // Seleccione la última versión u1
        assertEquals(u1.id, node.currentVariant?.message?.id)
        // u1 Sólo nuevas respuestas a continuación, Las respuestas antiguas no deben incluirse.
        val u1Assistants = node.currentVariant?.assistantNodes?.map { it.currentVariant?.content }
        assertEquals(listOf("nueva respuesta"), u1Assistants)
        // u0 Publicar respuesta anterior
        val u0Assistants = node.variants.first().assistantNodes.map { it.currentVariant?.content }
        assertEquals(listOf("Antigua respuesta"), u0Assistants)
    }

    @Test
    fun rebuild_appendedNewTurn_selectsLatestUserNode() {
        val u1 = user("Hola")
        val a1 = assistant("responder1", group = "ag1", parentGroup = u1.id.toString(), at = u1.createdAt + 1)
        val initial = ConversationTree.build(listOf(u1, a1))

        val u2 = user("Segunda pregunta")
        val placeholder = assistant("", group = "ag2", parentGroup = u2.id.toString(), at = u2.createdAt + 1)
        val rebuilt = ConversationTree.build(listOf(u1, a1, u2, placeholder), initial)

        assertEquals(2, rebuilt.userNodes.size)
        assertEquals("Segunda pregunta", rebuilt.selectedUserVariant?.content)
        assertEquals(
            listOf("Hola", "responder1", "Segunda pregunta", ""),
            rebuilt.displayMessages.map { it.content },
        )
    }

    @Test
    fun retry_appendsVariantToSameAssistantGroup() {
        val u = user("pregunta", group = "ug1")
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 1, parentGroup = u.id.toString(), at = u.createdAt + 1)
        val tree = ConversationTree.build(listOf(u, a1))

        val update = tree.retryLastAssistant()

        assertNotNull(update.newMessage)
        assertEquals("ag1", update.changedGroupId)
        assertEquals(2, update.tree.userNodes.first().currentVariant?.assistantNodes?.first()?.variants?.size)
        assertEquals(1, update.tree.userNodes.first().currentVariant?.assistantNodes?.first()?.selectIndex)
        assertEquals("", update.tree.displayMessages.last().content)
        assertEquals("ag1", update.newMessage?.variantGroupId)
        assertEquals(u.id.toString(), update.newMessage?.parentGroupId)
    }

    @Test
    fun edit_createsNewUserVariantWithFreshReply() {
        val u = user("pregunta original", group = "ug1")
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 1, parentGroup = u.id.toString(), at = u.createdAt + 1)
        val tree = ConversationTree.build(listOf(u, a1))

        val edit = tree.editUserMessage(u.id, "Pregunta revisada")

        assertNotNull(edit)
        assertEquals("ug1", edit?.newUserMessage?.variantGroupId)
        assertEquals(1, edit?.newUserMessage?.variantIndex)
        assertEquals(2, edit?.tree?.userNodes?.first()?.variants?.size)
        assertEquals("Pregunta revisada", edit?.tree?.selectedUserVariant?.content)
        // La nueva versión solo tiene respuestas de marcador de posición y no copia las respuestas de la versión anterior.
        val assistants = edit?.tree?.selectedUserNode?.currentVariant?.assistantNodes.orEmpty()
        assertEquals(1, assistants.size)
        assertEquals("", assistants[0].currentVariant?.content)
        assertEquals(edit?.newUserMessage?.id?.toString(), assistants[0].currentVariant?.parentGroupId)
        // Las versiones anteriores aún conservan sus propias respuestas.
        val oldVariant = edit?.tree?.userNodes?.first()?.variants?.first()
        assertEquals("responder1", oldVariant?.assistantNodes?.first()?.currentVariant?.content)
    }

    @Test
    fun rebuild_restoresTreeFromFlatMessages() {
        val u1 = user("pregunta original", group = "ug1", index = 0, count = 2, at = 100)
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 1, parentGroup = u1.id.toString(), at = 101)
        val u2 = user("Pregunta revisada", group = "ug1", index = 1, count = 2, at = 102)
        val placeholder = assistant("", group = "ag2", index = 0, count = 1, parentGroup = u2.id.toString(), at = 103)

        val tree = ConversationTree.build(listOf(u1, a1, u2, placeholder))

        assertEquals(1, tree.userNodes.size)
        assertEquals(2, tree.userNodes.first().variants.size)
        assertEquals(1, tree.userNodes.first().variants[0].assistantNodes.size)
        assertEquals(1, tree.userNodes.first().variants[1].assistantNodes.size)
        assertEquals("responder1", tree.userNodes.first().variants[0].assistantNodes.first().currentVariant?.content)
        assertEquals("", tree.userNodes.first().variants[1].assistantNodes.first().currentVariant?.content)
        // La última versión del usuario está seleccionada de forma predeterminada.
        assertEquals("Pregunta revisada", tree.displayMessages.first().content)
        assertEquals("", tree.displayMessages.last().content)
    }

    @Test
    fun legacyData_withoutParentGroup_infersParent() {
        val u = user("Pregunta sobre datos antiguos")
        val a = assistant("respuesta de datos antiguos", group = "ag1", at = u.createdAt + 1)

        val tree = ConversationTree.build(listOf(u, a))

        assertEquals(1, tree.userNodes.size)
        assertEquals("respuesta de datos antiguos", tree.userNodes.first().currentVariant?.assistantNodes?.first()?.currentVariant?.content)
        assertEquals(2, tree.displayMessages.size)
    }

    @Test
    fun userVariants_isolateAssistantSubtrees() {
        val g1 = user("hacer una preguntaA", group = "gA", index = 0, count = 2, at = 100)
        val a1 = assistant("responderA-1", group = "agA", index = 0, count = 1, parentGroup = g1.id.toString(), at = 101)
        val g1v2 = user("hacer una preguntaAcambiar", group = "gA", index = 1, count = 2, at = 102)
        val a1v2 = assistant("responderA-2", group = "agB", index = 0, count = 1, parentGroup = g1v2.id.toString(), at = 103)
        val g2 = user("hacer una preguntaB", group = "gB", index = 0, count = 1, at = 104)
        val a2 = assistant("responderB", group = "agC", index = 0, count = 1, parentGroup = g2.id.toString(), at = 105)

        val tree = ConversationTree.build(listOf(g1, a1, g1v2, a1v2, g2, a2))

        assertEquals(2, tree.userNodes.size)
        // La última ronda está seleccionada de forma predeterminada; dentro del mismo grupo de preguntas, solo se muestra la versión actualmente seleccionada del grupo.
        assertEquals("hacer una preguntaB", tree.selectedUserVariant?.content)
        assertEquals(listOf("hacer una preguntaAcambiar", "responderA-2", "hacer una preguntaB", "responderB"), tree.displayMessages.map { it.content })
        // Al volver a la versión anterior,A El grupo cambia a la versión anterior de respuesta,B El grupo se mantiene sin cambios.
        val switched = tree.selectUserVariant(tree.userNodes.first().userId, 0)
        assertEquals("hacer una preguntaA", switched.selectedUserVariant?.content)
        assertEquals(listOf("hacer una preguntaA", "responderA-1", "hacer una preguntaB", "responderB"), switched.displayMessages.map { it.content })
        // hacer una preguntaB y preguntasA No interfieran el uno con el otro
        assertEquals("responderB", tree.userNodes[1].variants.first().assistantNodes.first().currentVariant?.content)
    }

    @Test
    fun selectAssistantVariant_changesOnlyTargetGroup() {
        val u = user("hacer una pregunta", group = "ug1")
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("responder2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)

        val tree = ConversationTree.build(listOf(u, a1, a2))

        assertEquals("responder2", tree.displayMessages.last().content)

        val switched = tree.selectAssistantVariant(u.id.toString(), "ag1", 0)
        assertEquals("responder1", switched.displayMessages.last().content)
    }

    @Test
    fun emptyAndNullSafe() {
        val empty = ConversationTree.build(emptyList())
        assertTrue(empty.displayMessages.isEmpty())
        assertNull(empty.selectedUserNode)
        val tree = ConversationTree()
        val update = tree.retryLastAssistant()
        assertNull(update.newMessage)
        assertNull(update.changedGroupId)
        assertNull(tree.editUserMessage(Uuid.random(), "x"))
    }

    @Test
    fun rebuild_keepsUserVariantSelection() {
        val u1 = user("pregunta original", group = "ug1", index = 0, count = 2, at = 100)
        val u2 = user("Pregunta revisada", group = "ug1", index = 1, count = 2, at = 102)

        val initial = ConversationTree.build(listOf(u1, u2))
        val switched = initial.selectUserVariant(initial.userNodes.first().userId, 0)
        val rebuilt = ConversationTree.build(listOf(u1, u2), switched)

        assertEquals("pregunta original", rebuilt.selectedUserVariant?.content)
        assertEquals("pregunta original", rebuilt.displayMessages.first().content)
    }

    @Test
    fun rebuild_keepsAssistantVariantSelection() {
        val u = user("hacer una pregunta", group = "ug1")
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("responder2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)

        val initial = ConversationTree.build(listOf(u, a1, a2))
        val switched = initial.selectAssistantVariant(u.id.toString(), "ag1", 0)
        val rebuilt = ConversationTree.build(listOf(u, a1, a2), switched)

        assertEquals("responder1", rebuilt.userNodes.first().currentVariant?.assistantNodes?.first()?.currentVariant?.content)
        assertEquals("responder1", rebuilt.displayMessages.last().content)
    }

    @Test
    fun branchInfoFor_findsUserAndAssistantGroups() {
        val u1 = user("hacer una preguntaA", group = "ug1", index = 0, count = 2, at = 100)
        val u2 = user("hacer una preguntaAcambiar", group = "ug1", index = 1, count = 2, at = 102)
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 2, parentGroup = u2.id.toString(), at = 101)
        val a2 = assistant("responder2", group = "ag1", index = 1, count = 2, parentGroup = u2.id.toString(), at = 103)

        val tree = ConversationTree.build(listOf(u1, a1, u2, a2))

        val userInfo = tree.branchInfoFor(u1.id)
        assertEquals("ug1", userInfo?.groupId)
        assertEquals(2, userInfo?.branchCount)

        val assistantInfo = tree.branchInfoFor(a1.id)
        assertEquals("ag1", assistantInfo?.groupId)
        assertEquals(u2.id.toString(), assistantInfo?.parentGroupId)
        assertEquals(2, assistantInfo?.branchCount)
    }

    @Test
    fun branchInfoIndex_uses_each_user_variant_as_assistant_parent() {
        val u1 = user("hacer una preguntaA", group = "ug-index", index = 0, count = 2, at = 100)
        val u2 = user("hacer una preguntaAcambiar", group = "ug-index", index = 1, count = 2, at = 102)
        val a1 = assistant("responderA", group = "ag-index", index = 0, count = 1, parentGroup = u1.id.toString(), at = 101)
        val b1 = assistant("responderB", group = "bg-index", index = 0, count = 1, parentGroup = u2.id.toString(), at = 103)
        val tree = ConversationTree
            .build(listOf(u1, a1, u2, b1))
            .selectUserVariant("ug-index", 0)

        val index = tree.buildBranchInfoIndex()

        assertEquals(u1.id.toString(), index[a1.id.toString()]?.parentGroupId)
        assertEquals(u2.id.toString(), index[b1.id.toString()]?.parentGroupId)
    }

    @Test
    fun build_normalizesDuplicateVariantIndexes() {
        val u = user("hacer una pregunta", group = "ug1")
        val a1 = assistant("responder1", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("responder2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)

        val tree = ConversationTree.build(listOf(u, a1, a2))

        val variants = tree.userNodes.first().currentVariant?.assistantNodes?.first()?.variants.orEmpty()
        assertEquals(listOf(0, 1), variants.map { it.variantIndex })
        assertEquals(2, variants[0].variantCount)
        assertEquals(2, variants[1].variantCount)
    }

    @Test
    fun selectedVariantFlatMessages_containsAllRetryVariants() {
        val u = user("hacer una pregunta", group = "ug1")
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 1, parentGroup = u.id.toString(), at = 101)
        val tree = ConversationTree.build(listOf(u, a1))

        val update = tree.retryLastAssistant()

        val flat = update.tree.selectedVariantFlatMessages
        assertEquals(3, flat.size)
        assertEquals("responder1", flat[1].content)
        assertEquals("", flat[2].content)
    }

    @Test
    fun allFlatMessages_preservesEveryUserVariantAndRetryVariant() {
        val u1 = user("hacer una pregunta1", group = "ug1", index = 0, count = 2, at = 100)
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 1, parentGroup = u1.id.toString(), at = 101)
        val u2 = user("hacer una pregunta2", group = "ug1", index = 1, count = 2, at = 102)
        val placeholder = assistant("", group = "ag2", index = 0, count = 1, parentGroup = u2.id.toString(), at = 103)

        val tree = ConversationTree.build(listOf(u1, a1, u2, placeholder))
        val afterRetry = tree.retryLastAssistant().tree

        val flat = afterRetry.allFlatMessages
        assertEquals(5, flat.size)
        assertEquals(listOf("hacer una pregunta1", "responder1", "hacer una pregunta2", "", ""), flat.map { it.content })
    }

    @Test
    fun editThenRetry_keepsTwoUserVariantsAndTwoAssistantVariants() {
        val u1 = user("pregunta original", group = "ug1", index = 0, count = 1, at = 100)
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 1, parentGroup = u1.id.toString(), at = 101)
        val tree = ConversationTree.build(listOf(u1, a1))

        val edit = tree.editUserMessage(u1.id, "Pregunta revisada") ?: error("edit failed")
        val afterRetry = edit.tree.retryLastAssistant().tree

        val newUser = edit.newUserMessage ?: error("new user missing")
        val placeholder = edit.newAssistantPlaceholder ?: error("placeholder missing")
        assertEquals(2, afterRetry.branchInfoFor(newUser.id)?.branchCount)
        assertEquals(2, afterRetry.branchInfoFor(placeholder.id)?.branchCount)
        assertEquals(2, afterRetry.userNodes.first().currentVariant?.assistantNodes?.first()?.variants?.size)
        assertEquals("Pregunta revisada", afterRetry.selectedUserVariant?.content)
    }

    @Test
    fun mergeRebuildMessages_preservesRetryVariantsAndNewMessages() {
        val u1 = user("hacer una pregunta1", group = "ug1", at = 100)
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 2, parentGroup = u1.id.toString(), at = 101)
        val a2 = assistant("responder2", group = "ag1", index = 1, count = 2, parentGroup = u1.id.toString(), at = 102)
        val tree = ConversationTree.build(listOf(u1, a1, a2))

        // La pantalla actual solo contiene la versión de reintento actualmente seleccionada.,La versión anterior debe conservarse después de la fusión.
        val merged = mergeRebuildMessages(tree, tree.displayMessages)
        assertEquals(listOf("hacer una pregunta1", "responder1", "responder2"), merged.map { it.content })
        val rebuilt = ConversationTree.build(merged, tree)
        assertEquals(2, rebuilt.userNodes.first().currentVariant?.assistantNodes?.first()?.variants?.size)

        // Mensajes de usuario recién agregados/El marcador de posición del asistente no está en el árbol antiguo.,También se deben ingresar los resultados de la fusión.
        val u2 = user("hacer una pregunta2")
        val placeholder = assistant("", group = "ag2", parentGroup = u2.id.toString(), at = u2.createdAt + 1)
        val mergedWithNew = mergeRebuildMessages(tree, tree.displayMessages + u2 + placeholder)
        assertEquals(listOf("hacer una pregunta1", "responder1", "responder2", "hacer una pregunta2", ""), mergedWithNew.map { it.content })
    }

    @Test
    fun mergeRebuildMessages_currentMessageWinsOverStaleTreeSnapshot() {
        val userMessage = user("hacer una pregunta", at = 100)
        val staleAssistant = assistant(
            content = "antigua instantánea intermedia",
            group = "ag1",
            parentGroup = userMessage.id.toString(),
            at = 101,
        )
        val preservedBranch = assistant(
            content = "otra rama",
            group = "ag1",
            index = 1,
            count = 2,
            parentGroup = userMessage.id.toString(),
            at = 102,
        )
        val tree = ConversationTree.build(listOf(userMessage, staleAssistant, preservedBranch))
        val currentAssistant = staleAssistant.copy(content = "respuesta final")

        val merged = mergeRebuildMessages(tree, listOf(userMessage, currentAssistant))

        assertEquals("respuesta final", merged.first { it.id == staleAssistant.id }.content)
        assertTrue(merged.any { it.id == preservedBranch.id })
    }

    @Test
    fun removeMessage_removesAssistantVariantAndReindexes() {
        val u = user("hacer una pregunta", group = "ug1")
        val a1 = assistant("responder1", group = "ag1", index = 0, count = 2, parentGroup = u.id.toString(), at = 101)
        val a2 = assistant("responder2", group = "ag1", index = 1, count = 2, parentGroup = u.id.toString(), at = 102)
        val tree = ConversationTree.build(listOf(u, a1, a2))

        val removed = tree.removeMessage(a2.id)

        val variants = removed.userNodes.first().currentVariant?.assistantNodes?.first()?.variants.orEmpty()
        assertEquals(1, variants.size)
        assertEquals(listOf("responder1"), variants.map { it.content })
        assertEquals(0, variants[0].variantIndex)
        assertEquals(1, variants[0].variantCount)
        assertEquals(listOf("hacer una pregunta", "responder1"), removed.displayMessages.map { it.content })
    }

    @Test
    fun removeMessage_removesUserVariantWithItsAssistants() {
        val u1 = user("hacer una pregunta1", group = "ug1", at = 100)
        val a1 = assistant("responder1", group = "ag1", parentGroup = u1.id.toString(), at = 101)
        val u2 = user("hacer una pregunta2", group = "ug2", at = 102)
        val a2 = assistant("responder2", group = "ag2", parentGroup = u2.id.toString(), at = 103)
        val tree = ConversationTree.build(listOf(u1, a1, u2, a2))

        val removed = tree.removeMessage(u1.id)

        assertEquals(1, removed.userNodes.size)
        assertEquals(listOf("hacer una pregunta2", "responder2"), removed.displayMessages.map { it.content })
    }

    @Test
    fun removeMessage_removesLastUserNode_returnsEmptyTree() {
        val u = user("hacer una pregunta", group = "ug1")
        val a = assistant("responder", group = "ag1", parentGroup = u.id.toString(), at = u.createdAt + 1)
        val tree = ConversationTree.build(listOf(u, a))

        val removed = tree.removeMessage(u.id)

        assertTrue(removed.userNodes.isEmpty())
        assertTrue(removed.displayMessages.isEmpty())
    }

    @Test
    fun removeMessage_unknownId_returnsSameTree() {
        val u = user("hacer una pregunta", group = "ug1")
        val tree = ConversationTree.build(listOf(u))

        val removed = tree.removeMessage(Uuid.random())

        assertEquals(tree, removed)
    }
}
