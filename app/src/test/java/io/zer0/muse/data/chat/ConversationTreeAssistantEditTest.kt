package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v2.5.3 (P3-1): Editar mensaje del asistente = Se agregan nodos de nuevas versiones y las versiones antiguas se conservan de forma permanente.
 */
class ConversationTreeAssistantEditTest {

    private fun userAndReply(): Triple<ConversationTree, UIMessage, UIMessage> {
        val userId = Uuid.random()
        val replyGroup = "asg_reply"
        val user =
            UIMessage(
                id = userId,
                role = MessageRole.USER,
                content = "Hola",
                createdAt = 100L,
                variantGroupId = "usg_1",
            )
        val reply =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "respuesta original",
                createdAt = 101L,
                variantGroupId = replyGroup,
                parentGroupId = userId.toString(),
            )
        val tree =
            ConversationTree.build(
                listOf(
                    user,
                    reply.copy(variantGroupId = replyGroup, variantIndex = 0, variantCount = 1),
                ),
            )
        return Triple(tree, user, reply)
    }

    @Test
    fun `editing assistant appends a new variant and keeps the old one`() {
        val (tree, _, reply) = userAndReply()
        val update = tree.editAssistantMessage(reply.id, "Respuesta editada")
        assertTrue("La edición debería devolver el nuevo árbol.", update?.newMessage != null)
        val newMsg = update!!.newMessage!!
        assertNotEquals("La nueva versión debe utilizar una nueva ID y no puede sobrescribir la versión anterior.", reply.id, newMsg.id)
        assertEquals("Respuesta editada", newMsg.content)
        // La versión anterior todavía está en el árbol.
        val allAssistantContents =
            update.tree.userNodes
                .flatMap { it.variants }
                .flatMap { it.assistantNodes }
                .flatMap { it.variants }
                .map { it.content }
        assertTrue("Se deben conservar las versiones anteriores.: $allAssistantContents", allAssistantContents.contains("respuesta original"))
        assertTrue("Debería existir una nueva versión.: $allAssistantContents", allAssistantContents.contains("Respuesta editada"))
    }

    @Test
    fun `editing selects the new variant as current`() {
        val (tree, _, reply) = userAndReply()
        val update = tree.editAssistantMessage(reply.id, "Respuesta editada")!!
        val displayContents = update.tree.displayMessages.map { it.content }
        assertTrue("La pantalla actual debe ser la nueva versión.: $displayContents", displayContents.contains("Respuesta editada"))
    }

    @Test
    fun `editing unknown message returns null`() {
        val (tree, _, _) = userAndReply()
        assertNull(tree.editAssistantMessage(Uuid.random(), "x"))
    }
}
