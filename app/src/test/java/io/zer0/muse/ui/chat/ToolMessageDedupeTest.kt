package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCallInfo
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * El producto se renderiza y deduplica repetidamente.
 *
 * Comentarios del usuario "Se muestran dos tarjetas dentro de una tarjeta y en ese lugar": aparecerán dos mensajes en la rueda de herramientas ——
 * Un mensaje asistente cuelga la tarjeta de tareas (la tarjeta se ha dibujado en el paso), además de un mensaje en pantalla para cada herramienta.
 * (También vende productos directamente). Si no elimina los duplicados, verá las copias superior e inferior de la misma tarjeta.
 */
class ToolMessageDedupeTest {

    private fun assistant(id: Uuid = Uuid.random()) = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = "",
    )

    private fun toolMessage(parentId: Uuid, toolName: String = "show_card") = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "",
        parentMessageId = parentId.toString(),
        toolCallInfo = ToolCallInfo(
            toolName = toolName,
            arguments = """{"title":"card"}""",
            result = "Card rendered",
            isSuccess = true,
        ),
    )

    @Test
    fun `tool message under a message with task card is covered`() {
        val owner = assistant()
        val tool = toolMessage(owner.id)

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, tool),
            taskCardOwnerIds = setOf(owner.id.toString()),
        )

        assertEquals(setOf(tool.id.toString()), covered)
    }

    @Test
    fun `tool message under a message without task card is not covered`() {
        val owner = assistant()
        val tool = toolMessage(owner.id)

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, tool),
            taskCardOwnerIds = emptySet(),
        )

        assertTrue("Sin tarjetas de tareas, sin fuentes duplicadas,Los productos no deben ocultarse.", covered.isEmpty())
    }

    @Test
    fun `tool message whose parent has no task card stays visible`() {
        val cardOwner = assistant()
        val otherOwner = assistant()
        val toolUnderOther = toolMessage(otherOwner.id)

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(cardOwner, otherOwner, toolUnderOther),
            taskCardOwnerIds = setOf(cardOwner.id.toString()),
        )

        assertTrue("El producto debe conservarse cuando el mensaje principal no tenga tarjeta de tarea", covered.isEmpty())
    }

    @Test
    fun `tool message without parent linkage is never covered`() {
        val orphan = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "",
            toolCallInfo = ToolCallInfo(
                toolName = "show_card",
                arguments = "{}",
                result = "ok",
                isSuccess = true,
            ),
        )

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(orphan),
            taskCardOwnerIds = setOf(orphan.id.toString()),
        )

        assertTrue("Sin una asociación de padres, no hay manera de determinar la duplicación.,Mantenlo de forma conservadora", covered.isEmpty())
    }

    @Test
    fun `messages without tool info are never covered`() {
        val owner = assistant()
        val plain = UIMessage(
            role = MessageRole.ASSISTANT,
            content = "respuesta normal",
            parentMessageId = owner.id.toString(),
        )

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, plain),
            taskCardOwnerIds = setOf(owner.id.toString()),
        )

        assertTrue(covered.isEmpty())
    }

    @Test
    fun `multiple tool messages under the same task card are all covered`() {
        val owner = assistant()
        val first = toolMessage(owner.id, "show_card")
        val second = toolMessage(owner.id, "generate_image")

        val covered = toolMessageIdsCoveredByTaskCards(
            messages = listOf(owner, first, second),
            taskCardOwnerIds = setOf(owner.id.toString()),
        )

        assertEquals(setOf(first.id.toString(), second.id.toString()), covered)
    }
}
