package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.6.14: Mismo modelo de guardia para la repetición del bloque de pensamiento.
 *
 * Tres contratos están bloqueados:
 *  1. Cuando los bloques de pensamiento histórico provengan de otro modelo, serán eliminados (pero no enviados al modelo actual);
 *  2. Mantener el mismo modelo tal como está (el enlace de reproducción no se ve afectado);
 *  3. Cuando el modelo de salida (datos antiguos) no se registra en el historial, permanece como está y no se realiza ninguna eliminación sin evidencia.
 */
class ReasoningReplayModelGuardTest {

    private fun assistant(
        modelId: String?,
        reasoning: String? = "thinking text",
        signature: String? = null,
        encrypted: String? = null,
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        content = "answer",
        reasoning = reasoning,
        thinkingSignature = signature,
        thinkingEncryptedContent = encrypted,
        modelId = modelId,
    )

    private fun normalize(messages: List<UIMessage>, modelId: String): List<UIMessage> =
        ProviderPayloadNormalizer.normalizeMessages(messages, Model(id = modelId, providerId = "test"))

    @Test
    fun `cross model thinking is stripped`() {
        val history = assistant(modelId = "claude-sonnet-4-5", signature = "sig-claude")
        val result = normalize(listOf(history), "deepseek-v4").single()

        assertNull("El razonamiento no debe exportarse entre modelos.", result.reasoning)
        assertNull("La firma no debe enviarse al cruzar modelos.", result.thinkingSignature)
        assertNull("encrypted_content no debe enviarse al cruzar modelos", result.thinkingEncryptedContent)
        assertEquals("El texto debe dejarse intacto.", "answer", result.content)
        assertEquals("Retención de registros del modelo de producción (para UI/solución de problemas)", "claude-sonnet-4-5", result.modelId)
    }

    @Test
    fun `same model thinking is kept for replay`() {
        val history = assistant(modelId = "claude-sonnet-4-5", signature = "sig-claude")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("thinking text", result.reasoning)
        assertEquals("sig-claude", result.thinkingSignature)
    }

    @Test
    fun `history without recorded model is left untouched`() {
        val history = assistant(modelId = null, signature = "sig-legacy")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("Sin extracción cuando se desconoce la fuente", "thinking text", result.reasoning)
        assertEquals("sig-legacy", result.thinkingSignature)
    }

    @Test
    fun `model id comparison tolerates channel label and case`() {
        val history = assistant(modelId = "[ais]Claude-Sonnet-4-5")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("Tratado como el mismo modelo después de la normalización.", "thinking text", result.reasoning)
    }

    @Test
    fun `aggregator prefix still compares as same model`() {
        val history = assistant(modelId = "openrouter/anthropic/claude-sonnet-4-5")
        val result = normalize(listOf(history), "claude-sonnet-4-5").single()

        assertEquals("Quita el prefijo y mantén el mismo modelo, manteniendo la reproducción.", "thinking text", result.reasoning)
    }

    @Test
    fun `cross model assistant without reasoning payload is untouched`() {
        val plain = UIMessage(role = MessageRole.ASSISTANT, content = "answer", modelId = "gpt-5")
        val result = normalize(listOf(plain), "deepseek-v4").single()

        assertEquals(plain, result)
    }

    @Test
    fun `input list is not mutated`() {
        val history = assistant(modelId = "claude-sonnet-4-5", signature = "sig-claude")
        val input = listOf(history)
        normalize(input, "deepseek-v4")

        assertEquals("thinking text", history.reasoning)
        assertEquals("sig-claude", history.thinkingSignature)
    }
}
