package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.memory.summary.ContextCheckpointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba semántica de la fusión de puntos de control de sesión.
 *
 * El modo de falla en este nivel está muy oculto: si la combinación es incorrecta, no se informará ningún error, pero el modelo será"Olvidé lo que dijo el usuario."o"visto repetidamente
 * contenido suprimido". Así que aquí vamos bloqueando uno por uno: qué se debe conservar, qué se puede suprimir, cómo localizar los límites y qué hacer con las entradas anormales.
 */
class ContextCheckpointMergeTest {

    private fun msg(role: MessageRole, text: String) = UIMessage(role = role, content = text)

    private fun checkpoint(boundaryId: String, summary: String = "Reparación de compresión", coveredSeq: Long = 42L) = ContextCheckpointEntity(
        sessionId = "s-1",
        coveredSeq = coveredSeq,
        lastCoveredMessageId = boundaryId,
        coveredCount = 3,
        summary = summary,
        tokensBefore = 9_000,
        tokensAfter = 900,
        updatedAt = 1_700_000_000_000L,
    )

    @Test
    fun `compresses assistant side before boundary and keeps everything after`() {
        val sys = msg(MessageRole.SYSTEM, "tu eres Muse")
        val user1 = msg(MessageRole.USER, "Ayúdame a comprobar la compresión.")
        val assistant1 = msg(MessageRole.ASSISTANT, "Bien, déjame leer el código primero.……(muy largo)")
        val boundary = msg(MessageRole.ASSISTANT, "Después de leerlo, comprímalo en un transformador.")
        val user2 = msg(MessageRole.USER, "Luego cámbialo a Luoku")
        val assistant2 = msg(MessageRole.ASSISTANT, "cámbialo ahora")

        val result = ContextCheckpointMerge.apply(
            listOf(sys, user1, assistant1, boundary, user2, assistant2),
            checkpoint(boundary.id.toString()),
        )

        assertTrue(result.applied)
        // resumen primero
        assertTrue(result.messages.first().content.startsWith(ContextCheckpointMerge.MARKER))
        assertTrue(result.messages.first().content.contains("Reparación de compresión"))
        // Los mensajes de usuario y los prefijos del sistema deben conservarse (esto es"¿Qué dijo el usuario?"registro original)
        assertEquals(setOf("tu eres Muse", "Ayúdame a comprobar la compresión.", "Luego cámbialo a Luoku", "cámbialo ahora"), retainedContents(result))
        // El contenido de longitud del asistente antes de que el límite se reemplace por un resumen
        assertFalse(result.messages.any { it.content == assistant1.content })
        assertFalse(result.messages.any { it.content == boundary.content })
    }

    @Test
    fun `keeps user message even when it sits before the boundary`() {
        val user1 = msg(MessageRole.USER, "Recuerde: límite presupuestario 500")
        val assistant1 = msg(MessageRole.ASSISTANT, "Anota")
        val boundary = msg(MessageRole.ASSISTANT, "continuar")
        val result = ContextCheckpointMerge.apply(
            listOf(user1, assistant1, boundary),
            checkpoint(boundary.id.toString()),
        )
        assertTrue(result.applied)
        assertTrue(result.messages.any { it.content == "Recuerde: límite presupuestario 500" })
    }

    @Test
    fun `boundary not found means no merge`() {
        val history = listOf(msg(MessageRole.USER, "a"), msg(MessageRole.ASSISTANT, "b"))
        val result = ContextCheckpointMerge.apply(history, checkpoint("missing-id"))
        assertFalse(result.applied)
        assertEquals(history, result.messages)
    }

    @Test
    fun `partial paged history can apply checkpoint by covered sequence`() {
        val system = msg(MessageRole.SYSTEM, "sistema")
        val user = msg(MessageRole.USER, "Problemas actuales")
        val assistantAfter =
            msg(MessageRole.ASSISTANT, "Responder después de la frontera").copy(seq = 50, commitSeq = 2)
        val toolAfter =
            msg(MessageRole.TOOL, "Resultados de la herramienta después de los límites").copy(seq = 51, commitSeq = 1)

        val result =
            ContextCheckpointMerge.apply(
                listOf(system, user, assistantAfter, toolAfter),
                checkpoint(boundaryId = "older-message", coveredSeq = 42L),
            )

        assertTrue("Se deben seguir utilizando resúmenes de puntos de control persistentes cuando las ventanas de paginación no tienen límites", result.applied)
        assertTrue(result.messages.first().content.startsWith(ContextCheckpointMerge.MARKER))
        assertTrue(result.messages.any { it.content == "Problemas actuales" })
        assertTrue(result.messages.any { it.content == "Responder después de la frontera" })
        assertTrue(result.messages.any { it.content == "Resultados de la herramienta después de los límites" })
    }

    @Test
    fun `blank boundary id means no merge`() {
        val history = listOf(msg(MessageRole.USER, "a"), msg(MessageRole.ASSISTANT, "b"))
        assertFalse(ContextCheckpointMerge.apply(history, checkpoint("")).applied)
    }

    @Test
    fun `null checkpoint and empty history are no-ops`() {
        val history = listOf(msg(MessageRole.USER, "a"))
        assertFalse(ContextCheckpointMerge.apply(history, null).applied)
        assertFalse(ContextCheckpointMerge.apply(emptyList(), checkpoint("x")).applied)
    }

    @Test
    fun `does not insert a second summary when one is already present`() {
        val existing = msg(MessageRole.SYSTEM, "${ContextCheckpointMerge.MARKER} Resumen de conversaciones históricas (contenido anterior condensado)\n\nviejo resumen")
        val boundary = msg(MessageRole.ASSISTANT, "frontera")
        val user = msg(MessageRole.USER, "nueva pregunta")
        val history = listOf(existing, boundary, user)

        val result = ContextCheckpointMerge.apply(history, checkpoint(boundary.id.toString()))
        assertFalse("Cuando ya exista un resumen, no se deberá insertar repetidamente.", result.applied)
        assertEquals(history, result.messages)
    }

    @Test
    fun `last message as boundary keeps the summary only`() {
        val boundary = msg(MessageRole.ASSISTANT, "último artículo")
        val result = ContextCheckpointMerge.apply(listOf(boundary), checkpoint(boundary.id.toString()))
        assertTrue(result.applied)
        assertEquals(1, result.messages.size)
        assertTrue(result.messages.first().content.startsWith(ContextCheckpointMerge.MARKER))
    }

    @Test
    fun `summary content is trimmed and carries the marker for the divider`() {
        val boundary = msg(MessageRole.ASSISTANT, "x")
        val result = ContextCheckpointMerge.apply(
            listOf(boundary),
            checkpoint(boundary.id.toString(), summary = "   Resumen con espacios en blanco iniciales y finales   "),
        )
        val content = result.messages.first().content
        assertTrue(content.contains("Resumen con espacios en blanco iniciales y finales"))
        assertFalse("Los espacios en blanco innecesarios no deben ponerse en contexto", content.endsWith("   "))
    }

    @Test
    fun `covered ids match exactly what apply would replace`() {
        val sys = msg(MessageRole.SYSTEM, "sistema")
        val user1 = msg(MessageRole.USER, "pregunta")
        val assistant1 = msg(MessageRole.ASSISTANT, "respuesta muy larga")
        val boundary = msg(MessageRole.ASSISTANT, "frontera")
        val user2 = msg(MessageRole.USER, "Seguimiento")
        val history = listOf(sys, user1, assistant1, boundary, user2)
        val cp = checkpoint(boundary.id.toString())

        val covered = ContextCheckpointMerge.coveredMessageIds(history, cp)
        assertEquals(setOf(assistant1.id.toString(), boundary.id.toString()), covered)

        // El contenido realmente reemplazado por apply debe ser consistente: los mensajes de usuario y los prefijos del sistema no se reemplazan ni se cuentan"contexto guardado"
        val applied = ContextCheckpointMerge.apply(history, cp)
        val keptIds = applied.messages.map { it.id.toString() }.toSet()
        covered.forEach { id ->
            assertFalse("Los mensajes marcados como sobrescritos no deben permanecer en el resultado combinado: $id", id in keptIds)
        }
    }

    @Test
    fun `covered ids are empty when boundary is missing or blank`() {
        val history = listOf(msg(MessageRole.ASSISTANT, "x"))
        assertTrue(ContextCheckpointMerge.coveredMessageIds(history, null).isEmpty())
        assertTrue(ContextCheckpointMerge.coveredMessageIds(history, checkpoint("")).isEmpty())
        assertTrue(ContextCheckpointMerge.coveredMessageIds(history, checkpoint("nope")).isEmpty())
    }

    private fun retainedContents(result: ContextCheckpointMerge.Result): Set<String> =
        result.messages.filterNot { it.content.startsWith(ContextCheckpointMerge.MARKER) }
            .mapTo(mutableSetOf()) { it.content }
}
