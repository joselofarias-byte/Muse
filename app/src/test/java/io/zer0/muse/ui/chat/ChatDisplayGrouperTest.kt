package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatDisplayGrouper] prueba única de lógica pura(no depende de Android).
 *
 * Portada:Lista vacía/todos los elementos individuales/todos agregables/elementos individuales agregables pero no agrupados/cabeza y cola y capa intermedia/mapeo de índice.
 */
class ChatDisplayGrouperTest {

    /** Utilice el contenido como identificador estable(Evite depender de experimentos Uuid.parse). */
    private fun msg(marker: String) = UIMessage(role = MessageRole.ASSISTANT, content = marker, createdAt = 1L)

    /** Agregable = content a "tool" comienzo(No relacionado con el negocio,Sólo para pruebas). */
    private val isGroupable: (UIMessage) -> Boolean = { it.content.startsWith("tool") }

    private fun contentsOf(items: List<ChatDisplayItem>): List<String> = items.flatMap { item ->
        when (item) {
            is ChatDisplayItem.Single -> listOf(item.msg.content)
            is ChatDisplayItem.Grouped -> item.msgs.map { it.content }
        }
    }

    @Test
    fun `empty list yields empty result`() {
        assertTrue(ChatDisplayGrouper.group(emptyList(), isGroupable).isEmpty())
    }

    @Test
    fun `all non groupable stay single`() {
        val items = ChatDisplayGrouper.group(listOf(msg("a"), msg("b")), isGroupable)
        assertEquals(2, items.size)
        assertTrue(items.all { it is ChatDisplayItem.Single })
        assertEquals(listOf("a", "b"), contentsOf(items))
    }

    @Test
    fun `single groupable also forms a group`() {
        // MIN_RUN_SIZE Se ha cambiado a 1: cuando solo se ajusta una herramienta en una ronda o solo hay un período de pensamiento,
        // También debería integrarse en tarjetas de proceso en lugar de ser herramientas independientes./Piensa que aparecen bloques.
        val items = ChatDisplayGrouper.group(listOf(msg("tool1")), isGroupable)
        assertEquals(1, items.size)
        val grouped = items.single() as ChatDisplayItem.Grouped
        assertEquals(listOf("tool1"), grouped.msgs.map { it.content })
    }

    @Test
    fun `two or more consecutive groupables form one group`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("tool1"), msg("tool2"), msg("tool3")),
            isGroupable,
        )
        assertEquals(1, items.size)
        val grouped = items.single() as ChatDisplayItem.Grouped
        assertEquals(listOf("tool1", "tool2", "tool3"), grouped.msgs.map { it.content })
    }

    @Test
    fun `groups split on non groupable in between`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("tool1"), msg("tool2"), msg("text1"), msg("tool3"), msg("tool4")),
            isGroupable,
        )
        assertEquals(3, items.size)
        assertTrue(items[0] is ChatDisplayItem.Grouped)
        assertTrue(items[1] is ChatDisplayItem.Single)
        assertTrue(items[2] is ChatDisplayItem.Grouped)
        assertEquals(listOf("tool1", "tool2", "text1", "tool3", "tool4"), contentsOf(items))
    }

    @Test
    fun `leading and trailing singles are preserved`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("text1"), msg("tool1"), msg("tool2"), msg("text2")),
            isGroupable,
        )
        assertEquals(3, items.size)
        assertTrue(items[0] is ChatDisplayItem.Single)
        assertTrue(items[1] is ChatDisplayItem.Grouped)
        assertTrue(items[2] is ChatDisplayItem.Single)
        assertEquals(listOf("text1", "tool1", "tool2", "text2"), contentsOf(items))
    }

    @Test
    fun `index maps every covered message to its item position`() {
        val items = ChatDisplayGrouper.group(
            listOf(msg("text1"), msg("tool1"), msg("tool2"), msg("text2")),
            isGroupable,
        )
        val index = ChatDisplayGrouper.indexByMessageId(items)
        val ids = items.flatMap { it.messageIds }
        assertEquals(4, ids.size)
        // Diseño:[Single(text1), Grouped(tool1,tool2), Single(text2)]
        // dos en el grupo(tool1/tool2)apunta al mismo subíndice del elemento de la lista
        assertEquals(index[ids[1]], index[ids[2]])
        // Línea única y grupo, grupo y última línea individual tienen elementos diferentes
        assertTrue(index[ids[0]] != index[ids[1]])
        assertTrue(index[ids[2]] != index[ids[3]])
        // Dos mensajes en el grupo apuntan al mismo elemento de la lista,y el artículo es de hecho un Grouped
        assertTrue(items[index[ids[1]]!!] is ChatDisplayItem.Grouped)
    }

    @Test
    fun `transparent message does not split the group`() {
        // En una ronda de respuestas, la tarjeta de tarea se cuelga en el mensaje del medio: no participa en la agrupación.
        // Pero los dos procesos antes y después deberían fusionarse en un solo grupo en lugar de dividirse por la mitad.
        val tool1 = msg("tool1")
        val plan = msg("plan")
        val tool2 = msg("tool2")
        val items = ChatDisplayGrouper.group(
            messages = listOf(tool1, plan, tool2),
            isGroupable = isGroupable,
            isTransparent = { it.content == "plan" },
        )
        // Espere: un grupo (tool1 + tool2), agregue un mensaje de tarjeta de tarea independiente.
        assertEquals(2, items.size)
        val grouped = items[0] as ChatDisplayItem.Grouped
        assertEquals(listOf("tool1", "tool2"), grouped.msgs.map { it.content })
        assertEquals("plan", (items[1] as ChatDisplayItem.Single).msg.content)
    }

    @Test
    fun `transparent message keeps its own position`() {
        val tool1 = msg("tool1")
        val plan = msg("plan")
        val tool2 = msg("tool2")
        val items = ChatDisplayGrouper.group(
            messages = listOf(tool1, plan, tool2),
            isGroupable = isGroupable,
            isTransparent = { it.content == "plan" },
        )
        // Las tarjetas de tareas se organizan después del grupo, el orden es estable y no se pierde ningún mensaje.
        assertEquals(listOf("tool1", "tool2", "plan"), contentsOf(items))
    }
}
