package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * v2.6.15: assistant El sello del modelo de salida del mensaje.
 *
 * Protector del mismo modelo para reproducción de bloques de pensamiento (ProviderPayloadNormalizer.stripCrossModelReasoning) sólo reconocer
 * `UIMessage.modelId`. Si no escribe este campo al generar un enlace, el guardia nunca obtendrá la información de origen. —— Solia ser
 * "El campo existe pero nunca se le asigna un valor", lo que provoca que la guardia quede inactiva. Esta prueba destaca dos puntos de escritura:
 *  - Enviar un mensaje de marcador de posición (ChatGenerationController.enqueueSend);
 *  - Mensaje del asistente reconstruido por Toolwheel y respuesta final (ChatToolLoopRunner.streamRound).
 */
class ChatAssistantModelStampTest {

    private fun source(relativePath: String): String = listOf(
        Path.of("src/main/java/$relativePath"),
        Path.of("app/src/main/java/$relativePath"),
    )
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("$relativePath not found")

    // ── Reglas de valor ──

    @Test
    fun `round model wins over the stamp already on the message`() {
        val existing = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "enqueue-time-model")
        val round = Model(id = "round-actual-model", providerId = "test")

        assertEquals("round-actual-model", roundAssistantModelId(round, existing))
    }

    @Test
    fun `existing stamp is kept when round model is unknown`() {
        val existing = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "enqueue-time-model")

        assertEquals("enqueue-time-model", roundAssistantModelId(null, existing))
    }

    @Test
    fun `blank values are treated as absent`() {
        val blankRound = Model(id = "   ", providerId = "test")
        val blankStamp = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "")
        val stamped = UIMessage(role = MessageRole.ASSISTANT, content = "a", modelId = "enqueue-time-model")

        assertEquals("enqueue-time-model", roundAssistantModelId(blankRound, stamped))
        assertNull(roundAssistantModelId(blankRound, blankStamp))
        assertNull(roundAssistantModelId(null, null))
    }

    // ── Cableado de punto de escritura ──

    @Test
    fun `send placeholder carries the producing model`() {
        val text = source("io/zer0/muse/ui/chat/ChatGenerationController.kt")
        val enqueueBody = text.substringAfter("fun enqueueSend(")
            .substringBefore("suspend fun consumeSendRequest")

        assertTrue("enqueueSend No se puede encontrar al enviar un mensaje de marcador de posición", enqueueBody.contains("val assistantMsg ="))
        assertTrue(
            "El mensaje de marcador de posición debe enviarse con modelId; de lo contrario, el mismo guardia del modelo no podrá obtener la fuente.",
            enqueueBody.substringAfter("val assistantMsg =")
                .substringBefore("val outboxId")
                .contains("modelId = accessor.snapshot.selectedModelId"),
        )
    }

    @Test
    fun `tool round rebuild and final answer carry the producing model`() {
        val text = source("io/zer0/muse/ui/chat/ChatToolLoopRunner.kt")

        assertTrue(
            "El mensaje del asistente para la reconstrucción de la rueda de herramientas debe traer modelId",
            text.contains("modelId = roundAssistantModelId(roundModel, finalizedAssistant)"),
        )
        assertTrue(
            "La respuesta final también debe presentarse modelId",
            text.contains("modelId = roundAssistantModelId(roundModel, finalAssistant)"),
        )
    }
}
