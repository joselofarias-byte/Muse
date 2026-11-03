package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.3 (P2-1): La reproducción de inferencias a nivel de modelo reclama pruebas de análisis.
 *
 * El nuevo modelo de serie que cubre "Keep Thinking" también puede declarar la estrategia de reproducción correcta en un sitio de agregación/host desconocido.
 * y los modelos desconocidos no se declaran (dejados a ProviderCompat/comportamiento predeterminado).
 */
class ModelReasoningReplayTest {

    private fun declare(id: String) = ModelReasoningReplayResolver.declare(id)

    @Test
    fun `deepseekDeclaración de modelo de pensamientoreasoning_contenty el campo es obligatorio`() {
        val d = declare("deepseek-v4-flash")
        assertNotNull(d)
        assertEquals(ReasoningCarrier.REASONING_CONTENT, d!!.carrier)
        assertEquals(ReasoningReplayPolicy.REQUIRE_TOOL_CALL, d.policy)
        assertTrue("DeepSeek El campo del modo de pensamiento no se puede omitir y se debe enviar un marcador de posición de cadena vacía.", d.requiresNonEmptyReasoning)
    }

    @Test
    fun `kimiconglmDeclaración del Departamento de Pensamientoreasoning_content`() {
        listOf("kimi-k2.5", "kimi-k3", "glm-z1-air", "glm-4.7", "glm-5").forEach { id ->
            val d = declare(id)
            assertNotNull("$id La repetición debe ser declarada.", d)
            assertEquals(ReasoningCarrier.REASONING_CONTENT, d!!.carrier)
        }
    }

    @Test
    fun `qwencondoubaoTolera los valores predeterminados para que no se requiera ocupación de cadenas vacías`() {
        listOf("qwen3-235b", "qwq-32b", "doubao-1.5-thinking-pro").forEach { id ->
            val d = declare(id)
            assertNotNull("$id La repetición debe ser declarada.", d)
            assertEquals(ReasoningCarrier.REASONING_CONTENT, d!!.carrier)
            assertTrue("$id No se requiere un marcador de posición de cadena vacía", !d.requiresNonEmptyReasoning)
        }
    }

    @Test
    fun `openaiSistema de respuesta yanthropiccongeminiDeclarar los respectivos transportistas`() {
        assertEquals(ReasoningCarrier.REASONING_ITEMS, declare("gpt-5")!!.carrier)
        assertEquals(ReasoningCarrier.THINKING_BLOCKS, declare("claude-sonnet-4-5")!!.carrier)
        assertEquals(ReasoningCarrier.THOUGHT_SIGNATURE, declare("gemini-3-pro")!!.carrier)
    }

    @Test
    fun `El prefijo de la estación de agregación aún se puede identificar después de la normalización`() {
        assertEquals(ReasoningCarrier.REASONING_CONTENT, declare("openrouter/deepseek/deepseek-v4")!!.carrier)
        assertEquals(ReasoningCarrier.REASONING_CONTENT, declare("siliconflow/kimi-k2.5")!!.carrier)
    }

    @Test
    fun `El modelo desconocido no está declarado`() {
        assertNull(declare("some-random-model"))
        assertNull(declare(""))
        assertNull(ModelReasoningReplayResolver.declare(null))
    }

    @Test
    fun `resolveen lo desconocidohostEl resultado final está cubierto por declaraciones a nivel de modelo`() {
        // Host de estación de transferencia desconocido, el modelo es un sistema de pensamiento de búsqueda profunda -> Todavía merece un contrato de repetición
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            "https://unknown-relay.example.com/v1",
            "deepseek-v4-flash",
        )
        val contract = compat.reasoningReplayContract
        assertNotNull("Los hosts desconocidos deben estar cubiertos por declaraciones a nivel de modelo", contract)
        assertEquals(ReasoningCarrier.REASONING_CONTENT, contract!!.carrier)
    }

    @Test
    fun `explícitohostDeclarar no cubierto por coberteras a nivel de modelo`() {
        // El host oficial de Deepseek ha declarado el contrato en la capa de host y no debe reescribirse a nivel de modelo.
        val compat = ProviderCompatRules.resolve(
            ProviderType.OPENAI,
            "https://api.deepseek.com/v1",
            "deepseek-v4-flash",
        )
        assertEquals(ReasoningCarrier.REASONING_CONTENT, compat.reasoningReplayContract!!.carrier)
    }
}
