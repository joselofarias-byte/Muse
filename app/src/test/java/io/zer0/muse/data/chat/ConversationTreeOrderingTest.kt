package io.zer0.muse.data.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationTreeOrderingTest {
    @Test
    fun `snapshot placeholder is never merged into real messages`() {
        val realUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "verdadera pregunta",
            createdAt = 100L,
            seq = 1L,
        )
        val realAssistant = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = "verdadera respuesta",
            createdAt = 101L,
            seq = 2L,
            parentGroupId = realUser.id.toString(),
            variantGroupId = "assistant-group",
        )
        val snapshotPlaceholder = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "",
            createdAt = 0L,
            variantGroupId = "old-group",
        )

        val merged = mergeRebuildMessages(
            ConversationTree.build(listOf(snapshotPlaceholder)),
            listOf(realAssistant, realUser),
        )

        assertEquals(listOf(realUser.id, realAssistant.id), merged.map { it.id })
    }

    @Test
    fun `message ordering uses stable sequence before createdAt`() {
        val first = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            content = "primera ronda",
            createdAt = 9_999L,
            seq = 1L,
        )
        val second = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = "Artículo 2",
            createdAt = 1L,
            seq = 2L,
        )

        assertEquals(
            listOf(first.id, second.id),
            orderConversationMessages(listOf(second, first)).map { it.id },
        )
    }

    @Test
    fun `Al mezclar seq y createAt, la clasificación sigue siendo orden total(Regresar-bucle comparador)`() {
        // Revisión de las tríadas formadoras de anillos descubiertas.:La antigua implementación también derivaría A>C(prensa seq),A<B,B<C(prensa createdAt)
        val a =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "A",
                createdAt = 3_000L,
                seq = 10L,
            )
        val b =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "B(Mensajes antiguos desordenados)",
                createdAt = 1_000L,
                seq = 0L,
            )
        val c =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "C",
                createdAt = 100L,
                seq = 20L,
            )

        // no debe ser arrojado "Comparison method violates its general contract!"
        val first = orderConversationMessages(listOf(a, b, c)).map { it.id }
        // Los mensajes ordenados aún presionan seq(A antes de C),Los mensajes desordenados se insertan nuevamente en el período de tiempo según creado en(B La hora es anterior a A y posterior a C)
        assertEquals(listOf(a.id, b.id, c.id), first)
        // El resultado es independiente del orden de entrada.(Criterio de orden total)
        assertEquals(first, orderConversationMessages(listOf(c, b, a)).map { it.id })
        assertEquals(first, orderConversationMessages(listOf(b, c, a)).map { it.id })
    }

    @Test
    fun `Los mensajes antiguos desordenados se insertan nuevamente en el período de tiempo de acuerdo con createAt en lugar de colocarse en un extremo`() {
        val m1 =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "m1",
                createdAt = 10L,
                seq = 1L,
            )
        val m2 =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "m2",
                createdAt = 20L,
                seq = 2L,
            )
        val legacy =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "legacy",
                createdAt = 15L,
                seq = 0L,
            )

        assertEquals(
            listOf(m1.id, legacy.id, m2.id),
            orderConversationMessages(listOf(m2, legacy, m1)).map { it.id },
        )
    }

    @Test
    fun `El texto original resumido ya no se fusiona con el árbol anterior`() {
        val user =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "vieja pregunta",
                createdAt = 1L,
                seq = 1L,
            )
        val assistant =
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "Antigua respuesta",
                createdAt = 2L,
                seq = 2L,
                parentGroupId = user.id.toString(),
                variantGroupId = "assistant-group",
            )
        val tree = ConversationTree.build(listOf(user, assistant))

        // viejo comportamiento(No pasar colección sumaria):El texto original del árbol antiguo se fusionará nuevamente.
        assertEquals("El texto original debe fusionarse nuevamente con el comportamiento anterior.", 2, mergeRebuildMessages(tree, emptyList()).size)
        // El texto original que ha sido cubierto por el resumen no puede ser revivido.,De lo contrario"Resumen + texto original"La coexistencia conduce a una doble contabilización de tokens
        val filtered =
            mergeRebuildMessages(
                tree,
                emptyList(),
                setOf(user.id.toString(), assistant.id.toString()),
            )
        assertEquals("El texto original que ha sido resumido no puede resucitar.", 0, filtered.size)
        // Cuando sólo se cubre parte de la,El que no esté cubierto deberá conservarse igualmente.
        val partial = mergeRebuildMessages(tree, emptyList(), setOf(user.id.toString()))
        assertEquals("Los mensajes descubiertos aún deben volver a fusionarse", listOf(assistant.id), partial.map { it.id })
    }

    @Test
    fun `editing a middle user message keeps the branch in place after rebuild`() {
        // Comentarios de los usuarios: después de editar un mensaje en el medio, la nueva rama se ejecutó hasta el final.
        // Aquí hay un recorrido por el vínculo real: Construyendo un árbol → Editar ronda 2 → Reconstruya el árbol con displayMessages.
        fun turn(index: Int): Pair<UIMessage, UIMessage> {
            val user = UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                content = "pregunta $index",
                createdAt = index * 10L,
                seq = index * 2L,
                variantGroupId = "ug-$index",
            )
            val assistant = UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                content = "responder $index",
                createdAt = index * 10L + 1,
                seq = index * 2L + 1,
                variantGroupId = "ag-$index",
                parentGroupId = user.id.toString(),
            )
            return user to assistant
        }

        val (u1, a1) = turn(1)
        val (u2, a2) = turn(2)
        val (u3, a3) = turn(3)
        val tree = ConversationTree.build(listOf(u1, a1, u2, a2, u3, a3))

        val edit = tree.editUserMessage(u2.id, "Pregunta 2 (modificada)")
        assertNotNull("La edición debería tener éxito", edit)
        val shown = edit!!.tree.displayMessages

        // 1) En la proyección del árbol, la nueva variante todavía se encuentra en la posición de la ronda 2.
        assertEquals(
            listOf("pregunta 1", "Pregunta 2 (modificada)", "pregunta 3"),
            shown.filter { it.role == MessageRole.USER }.map { it.content },
        )

        // 2) Utilice esta proyección para reconstruir el árbol (reconstruir después de simular la transmisión) y el orden no se puede cambiar.
        val rebuilt = ConversationTree.build(shown)
        assertEquals(
            listOf("pregunta 1", "Pregunta 2 (modificada)", "pregunta 3"),
            rebuilt.displayMessages.filter { it.role == MessageRole.USER }.map { it.content },
        )
    }
}
