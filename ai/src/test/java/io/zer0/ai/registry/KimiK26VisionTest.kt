package io.zer0.ai.registry

import io.zer0.ai.core.ModelAbility
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.53: Kimi K2.6 Pruebas de regresión de reconocimiento multimodal.
 *
 * fondo:KnownModels Kimi-k2.6 marcado como texto sin formato(TEXT_IN),causa:
 *  - Incluso si la estación de tránsito declara multimodal,También se sobrescribe en texto plano mediante la lógica de corrección de errores v1.137.
 *  - Las imágenes son sólo para ayuda visual.,No puedo alisar el cabello
 * Reparación:ModelRegistry Se agregó la regla precisa KIMI_K2_6 + KnownModels cambió VISION_IN.
 */
class KimiK26VisionTest {

    @Test
    fun `kimi-k2_6 resolves vision input modality`() {
        val modalities = ModelRegistry.resolveInputModalities("kimi-k2.6")
        assertTrue("kimi-k2.6 Debería admitir la entrada de imágenes,real=$modalities", "image" in modalities)
    }

    @Test
    fun `kimi-k2_6 keeps tool and reasoning abilities`() {
        val abilities = ModelRegistry.resolveAbilities("kimi-k2.6")
        assertTrue("debe contener TOOL", abilities.contains(ModelAbility.TOOL))
        assertTrue("debe contener REASONING", abilities.contains(ModelAbility.REASONING))
    }

    @Test
    fun `kimi-k2 stays text-only`() {
        // kimi-k2(no 2.6)No incluye visuales — Las reglas precisas no deben confundirse
        val modalities = ModelRegistry.resolveInputModalities("kimi-k2")
        assertTrue("kimi-k2 Debe ser texto plano,real=$modalities", "image" !in modalities)
    }

    @Test
    fun `kimi-k2_5 stays text-only`() {
        val modalities = ModelRegistry.resolveInputModalities("kimi-k2.5")
        assertTrue("kimi-k2.5 Debe ser texto plano,real=$modalities", "image" !in modalities)
    }

    @Test
    fun `prefixed kimi-k2_6 still resolves vision`() {
        // estación de transferencia/Sitio de agregación con prefijo ID(Como por ejemplo opencode/kimi-k2.6)
        val modalities = ModelRegistry.resolveInputModalities("opencode/kimi-k2.6")
        assertTrue("con prefijo kimi-k2.6 debería ser compatible image,real=$modalities", "image" in modalities)
    }
}
