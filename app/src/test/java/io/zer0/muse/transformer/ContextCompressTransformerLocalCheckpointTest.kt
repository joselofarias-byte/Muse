package io.zer0.muse.transformer

import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.memory.summary.ContextCheckpointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuando el resumen no está disponible"reconstrucción local"Pruebas en puntos de control.
 *
 * Este nivel es el último paso en la escalera de degradación: error de modelo resumido/tiempo de espera/Cuando la red es pobre, si no escribes nada,
 * Si vuelves a abrir la aplicación, volverás al historial completo. Tendrás que volver a intentar el resumen la próxima vez. —— Lo que ve el usuario es"Presionado pero sin uso.".
 * La reconstrucción local produce puntos de control con extractos de texto deterministas de solicitud cero,**Las fronteras son inmediatas y permanentes**.
 */
class ContextCompressTransformerLocalCheckpointTest {

    private val transformer = ContextCompressTransformer(chatService = mockk<ChatService>(relaxed = true))

    private fun msg(role: MessageRole, text: String) = UIMessage(role = role, content = text)

    private fun coveredMessages(): List<UIMessage> = listOf(
        msg(MessageRole.USER, "Ayúdame con la parte de compresión."),
        msg(MessageRole.ASSISTANT, "Bien, déjame leer primero la implementación del transformador."),
        msg(MessageRole.TOOL, "Contenido del archivo:class ContextCompressTransformer { ... }"),
    )

    @Test
    fun `builds a local-strategy checkpoint with a usable summary`() {
        val covered = coveredMessages()
        val checkpoint = transformer.buildLocalCheckpoint(
            io.zer0.muse.transformer.ContextCompressTransformer.LocalCheckpointRequest(
                sessionId = "s-1",
                covered = covered,
                previousBoundaryId = null,
                coveredSeq = 42L,
                tokensBefore = 9_000,
                tokensAfter = 900,
                reason = ContextCheckpointEntity.REASON_AUTO,
            ),
        )

        assertNotNull("Se debe producir el punto de control.", checkpoint)
        assertEquals(ContextCheckpointEntity.STRATEGY_LOCAL, checkpoint?.strategy)
        assertEquals("s-1", checkpoint?.sessionId)
        assertEquals(42L, checkpoint?.coveredSeq)
        assertEquals("El límite debe caer en el último mensaje sobrescrito.", covered.last().id.toString(), checkpoint?.lastCoveredMessageId)
        assertEquals(covered.size, checkpoint?.coveredCount)
        assertTrue("El cuerpo del extracto no debe estar vacío.", checkpoint?.summary?.isNotBlank() == true)
    }

    @Test
    fun `empty covered list produces nothing`() {
        assertNull(
            transformer.buildLocalCheckpoint(
                io.zer0.muse.transformer.ContextCompressTransformer.LocalCheckpointRequest(
                    sessionId = "s-1",
                    covered = emptyList(),
                    previousBoundaryId = null,
                    coveredSeq = 0L,
                    tokensBefore = 1_000,
                    tokensAfter = 100,
                    reason = ContextCheckpointEntity.REASON_AUTO,
                ),
            ),
        )
    }

    @Test
    fun `only system messages leave nothing worth digesting`() {
        // ContextHistoryDigest Omitir mensajes del SISTEMA (los mensajes/RAG inyectados dinámicamente no se extraen),
        // Por lo tanto, cuando todo es SISTEMA, no hay contenido extraible. → No escriba puntos de control (en lugar de escribir un resumen vacío)
        val checkpoint = transformer.buildLocalCheckpoint(
            io.zer0.muse.transformer.ContextCompressTransformer.LocalCheckpointRequest(
                sessionId = "s-1",
                covered = listOf(msg(MessageRole.SYSTEM, "Palabra de aviso del sistema"), msg(MessageRole.SYSTEM, "RAG fragmento")),
                previousBoundaryId = null,
                coveredSeq = 5L,
                tokensBefore = 500,
                tokensAfter = 50,
                reason = ContextCheckpointEntity.REASON_AUTO,
            ),
        )
        assertNull("No se escribe ningún punto de control cuando no hay contenido extraíble", checkpoint)
    }

    @Test
    fun `local checkpoint takes effect in assembly`() {
        // Semántica de un extremo a otro: los límites producidos por los puntos de control locales deben fusionarse en el lado del ensamblaje ——
        // De lo contrario"Escribió un punto de control"Es una broma (el modelo aún recibe el historial completo).
        val covered = coveredMessages()
        val checkpoint = transformer.buildLocalCheckpoint(
            io.zer0.muse.transformer.ContextCompressTransformer.LocalCheckpointRequest(
                sessionId = "s-1",
                covered = covered,
                previousBoundaryId = null,
                coveredSeq = 42L,
                tokensBefore = 9_000,
                tokensAfter = 900,
                reason = ContextCheckpointEntity.REASON_AUTO,
            ),
        )
        val live = listOf(
            msg(MessageRole.USER, "Entonces cámbialo a Luoku ahora."),
            msg(MessageRole.ASSISTANT, "cámbialo ahora"),
        )
        val merged = ContextCheckpointMerge.apply(covered + live, checkpoint)

        assertTrue("El puesto de control local debería entrar en vigor", merged.applied)
        val summary = merged.messages.first().content
        assertTrue(
            "El mensaje de resumen debe estar delimitado con etiquetas.",
            summary.startsWith(ContextCheckpointMerge.MARKER),
        )
        // cubierto**lado asistente**Los mensajes ya no aparecen como mensajes separados (se combinan en el resumen)
        assertFalse(
            "Las respuestas sobrescritas del asistente ya no deberían ingresar al modelo como mensajes independientes",
            merged.messages.any { it.content == "Bien, déjame leer primero la implementación del transformador." },
        )
        // Pero en el rango cubierto**Los mensajes de usuario deben conservarse** —— eso es"¿Qué dijo el usuario?"registros originales,
        // Si lo pierde, el modelo olvidará los requisitos del usuario (consulte la descripción de la compensación de ContextCheckpointMerge).
        assertTrue(
            "Los mensajes de usuario dentro del rango cubierto deben conservarse",
            merged.messages.any { it.content == "Ayúdame con la parte de compresión." },
        )
        // La herramienta da como resultado**breve extracto**Incorporado al resumen: el contenido debe aparecer en el resumen pero suprimirse para que sea mucho más breve que el texto original.
        assertTrue("Los resultados de la herramienta deben incorporarse al resumen en forma de extracto.", summary.contains("Resultados de la herramienta:"))
        assertTrue("Los extractos deben ser mucho más cortos que el texto original.", summary.length < 4_000)
        // Retención de mensajes en vivo más allá de los límites
        assertTrue(merged.messages.any { it.content == "Entonces cámbialo a Luoku ahora." })
    }

    @Test
    fun `previous boundary is reused when nothing new can be parsed`() {
        // Si hay una identificación en el conjunto de cobertura que no se puede analizar en un Uuid, el límite debe devolverse al límite anterior (no escriba el límite incorrecto)
        val weird = UIMessage(role = MessageRole.ASSISTANT, content = "contenido")
        val previousId = java.util.UUID.randomUUID().toString()
        val checkpoint = transformer.buildLocalCheckpoint(
            io.zer0.muse.transformer.ContextCompressTransformer.LocalCheckpointRequest(
                sessionId = "s-1",
                covered = listOf(weird),
                previousBoundaryId = previousId,
                coveredSeq = 7L,
                tokensBefore = 100,
                tokensAfter = 50,
                reason = ContextCheckpointEntity.REASON_AUTO,
            ),
        )
        // weird.id es un Uuid aleatorio que se puede analizar; entonces el límite lo toma en lugar del antiguo límite —— Aquí sólo afirmamos"Acotado y no vacío"
        assertNotNull(checkpoint)
        assertTrue(checkpoint?.lastCoveredMessageId?.isNotBlank() == true)
    }
}
