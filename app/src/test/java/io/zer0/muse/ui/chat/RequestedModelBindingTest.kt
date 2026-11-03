package io.zer0.muse.ui.chat

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.9: Modelo de prueba vinculante de decisión de tres estados.
 *
 * Invariantes centrales fijadas:**lista vacía ≠ no contiene**.
 * Implementación original `provider.models.none { it.id == modelId }` Siempre en una lista vacía true,
 * La "lista de modelos aún no se ha extraído/no se pudo extraer" se juzga erróneamente como "el modelo no existe" y se degrada a un modelo de portada.
 * (La máquina real reduce así la conversación a GLM-4-9B,La herramienta modelo llama a la malformación.,La generación de la tarjeta falla directamente).
 */
class RequestedModelBindingTest {

    private fun provider(id: String, vararg modelIds: String) = ProviderConfig(
        id = id,
        displayName = id,
        models = modelIds.map { Model(id = it, name = it, providerId = id) },
    )

    // ── 1. La lista está vacía → No juzgado como desaparecido(El núcleo de esta restauración) ──────────────────────────────

    @Test
    fun `empty model list is not treated as missing`() {
        val binding = resolveRequestedModelBinding(
            // models esta vacio:Nunca jalé/no pude jalar
            providers = listOf(provider("agnes")),
            providerId = "agnes",
            modelId = "claude-sonnet-4-5",
        )

        assertFalse("Una lista vacía no se considerará modelo faltante.", binding.modelMissing)
        assertFalse("Una lista vacía no debe desencadenar una degradación", binding.missing)
        assertTrue("Las listas vacías deben marcarse como Lista no lista", binding.listNotReady)
        // Preservar el enlace de la solicitud:Construir marcador de posición por ID de solicitud Model,Déjelo en aguas arriba para su juicio.,En lugar de cambiar a otro modelo.
        assertNotNull("Las vinculaciones de modelos solicitadas aún deben conservarse cuando la lista no esté lista", binding.model)
        assertEquals("claude-sonnet-4-5", binding.model?.id)
        assertEquals("agnes", binding.model?.providerId)
    }

    @Test
    fun `provider missing still reports missing`() {
        val binding = resolveRequestedModelBinding(
            providers = listOf(provider("agnes", "claude-sonnet-4-5")),
            providerId = "ghost",
            modelId = "claude-sonnet-4-5",
        )

        assertTrue(binding.providerMissing)
        assertTrue(binding.missing)
        assertNull(binding.model)
    }

    // ── 2. La lista no está vacía y no contiene → Realmente desaparecido,Debe revisar todos los detalles. ────────────────────────────────

    @Test
    fun `non-empty list without the id is a real miss and falls back`() {
        val binding = resolveRequestedModelBinding(
            providers = listOf(provider("agnes", "glm-4-9b", "gpt-4o-mini")),
            providerId = "agnes",
            modelId = "claude-sonnet-4-5",
        )

        assertTrue("Si la lista no está vacía y no contiene el id, se debe considerar que falta.", binding.modelMissing)
        assertTrue("Si realmente estás desaparecido, debes activar el encubrimiento.", binding.missing)
        assertFalse(binding.listNotReady)
        assertNull("El enlace de solicitud no se debe conservar cuando falta verdadero,Debería dejar que los bolsillos se tomen de la mano", binding.model)
    }

    // ── 3. La lista no está vacía y contiene → golpe preciso ───────────────────────────────────────────

    @Test
    fun `non-empty list containing the id is not missing`() {
        val binding = resolveRequestedModelBinding(
            providers = listOf(provider("agnes", "glm-4-9b", "claude-sonnet-4-5")),
            providerId = "agnes",
            modelId = "claude-sonnet-4-5",
        )

        assertFalse(binding.modelMissing)
        assertFalse(binding.missing)
        assertFalse(binding.listNotReady)
        assertEquals("claude-sonnet-4-5", binding.model?.id)
    }

    // ── 4. frontera:persona que llama no especificada provider/model ─────────────────────────────────

    @Test
    fun `unspecified provider or model does not report missing`() {
        val noProvider = resolveRequestedModelBinding(listOf(provider("agnes")), null, "x")
        assertFalse(noProvider.missing)
        assertNull(noProvider.model)

        val noModel = resolveRequestedModelBinding(listOf(provider("agnes")), "agnes", null)
        assertFalse(noModel.missing)
        assertNull(noModel.model)
    }

    @Test
    fun `provider missing wins over unspecified model`() {
        // Provider Si la configuración desaparece, desaparece.,Informar incluso si la persona que llama no trae el modelId(Consistente con el comportamiento original.).
        val binding = resolveRequestedModelBinding(listOf(provider("agnes", "m")), "ghost", null)
        assertTrue(binding.providerMissing)
        assertTrue(binding.missing)
    }
}
