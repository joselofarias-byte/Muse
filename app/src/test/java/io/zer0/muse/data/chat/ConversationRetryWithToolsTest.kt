package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * v1.0.92: Reintentar el comportamiento del árbol cuando estén involucradas llamadas a herramientas (Pruebas de regresión).
 *
 * fondo: Mensaje en pantalla de herramienta ([UIMessage.toolCallInfo] != null) es un nodo independiente en el árbol,
 * versión antigua `retryLastAssistant` Tómalo directamente `assistantNodes.last()`,Cuando se llama a una herramienta,
 * Nueva variante enchufada"conjunto de herramientas"(Las tarjetas de herramientas se convierten en herramientas/Responder 1/2 variante de);Solo reconocido después de la reparación."nodo de respuesta".
 */
class ConversationRetryWithToolsTest {

    private fun user(content: String, at: Long) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.USER,
        content = content,
        createdAt = at,
    )

    private fun assistant(
        content: String,
        at: Long,
        group: String? = null,
        index: Int = 0,
        count: Int = 1,
        parent: String? = null,
        toolName: String? = null,
    ) = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        content = content,
        createdAt = at,
        variantGroupId = group,
        variantIndex = index,
        variantCount = count,
        parentGroupId = parent,
        toolCallInfo = toolName?.let { ToolCallInfo(it, "{}", "resultado", true) },
    )

    @Test
    fun retry_withTrailingToolNodes_targetsReplyNode_notToolNode() {
        val t0 = 1_700_000_000_000L
        val u = user("Consulta la hora y el clima para mí.", t0)
        val a0 = assistant("", t0 + 1)
        val t1 = assistant("", t0 + 100, toolName = "get_current_time")
        val t2 = assistant("", t0 + 200, toolName = "get_weather")
        val a0final = a0.copy(content = "ahora12punto,esta soleado hoy")

        val tree = ConversationTree.build(listOf(u, a0final, t1, t2))
        val update = tree.retryLastAssistant()

        // Las nuevas variantes deben estar dentro"grupo de respuesta"(= a0 grupo),en lugar de un conjunto de herramientas.
        assertEquals(a0.id.toString(), update.newMessage?.variantGroupId)
        val variant = update.tree.userNodes.first().currentVariant!!
        // Número de variantes del nodo de respuesta 2,El número de variantes de nodos de herramientas sigue siendo 1.
        val replyNode = variant.assistantNodes.first { it.groupId == a0.id.toString() }
        assertEquals(2, replyNode.variants.size)
        val toolNodes = variant.assistantNodes.filter { it.groupId != a0.id.toString() }
        assertEquals(2, toolNodes.size)
        assertTrue("Los nodos de herramientas no se deben agregar a las variantes.", toolNodes.all { it.variants.size == 1 })
        // La nueva variante se recorta según la selección.
        assertEquals(1, replyNode.selectIndex)
    }

    @Test
    fun retry_onlyToolNodes_createsFreshReplyGroupAtEnd() {
        val t0 = 1_700_000_000_000L
        val u = user("compruébalo por mí", t0)
        val t1 = assistant("", t0 + 100, toolName = "get_current_time")
        val t2 = assistant("", t0 + 200, toolName = "get_weather")

        val tree = ConversationTree.build(listOf(u, t1, t2))
        val update = tree.retryLastAssistant()

        val newGroup = update.newMessage?.variantGroupId
        assertNotEquals(t1.variantGroupId ?: t1.id.toString(), newGroup)
        assertNotEquals(t2.id.toString(), newGroup)
        val variant = update.tree.userNodes.first().currentVariant!!
        // Al final se añaden nuevos grupos de respuesta.
        assertEquals(newGroup, variant.assistantNodes.last().groupId)
        assertEquals(1, variant.assistantNodes.last().variants.size)
        assertEquals("", variant.assistantNodes.last().currentVariant?.content)
    }
}
