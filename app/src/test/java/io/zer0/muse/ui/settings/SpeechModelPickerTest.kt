package io.zer0.muse.ui.settings

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.asr.AsrConfig
import io.zer0.muse.asr.AsrProviderType
import io.zer0.muse.data.MediaConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selección del modelo de voz (TTS / ASR) candidatos y reglas de redacción.
 *
 * Es muy probable que dos cosas estén mal, y cada una de ellas es correcta: si el candidato es del proveedor y si el candidato es modelo o modelId después de la selección.
 * Además, los valores completados manualmente en la configuración anterior deben sobrevivir fuera de los candidatos. —— Al abrir la página de configuración no se puede borrar el modelo del usuario.
 */
class SpeechModelPickerTest {

    private val provider = ProviderConfig(
        id = "p1",
        displayName = "Relay",
        baseUrl = "https://relay.example.com/v1",
        apiKey = "sk-provider",
        models = listOf(
            Model(id = "tts-1", name = "TTS 1", providerId = "p1"),
            Model(id = "tts-2", name = "TTS 2", providerId = "p1"),
        ),
    )

    private val emptyProvider = ProviderConfig(
        id = "p2",
        displayName = "Proveedor vacío",
        models = emptyList(),
    )

    private val disabledProvider = ProviderConfig(
        id = "p3",
        displayName = "Discapacitado",
        enabled = false,
        models = listOf(Model(id = "m-disabled", name = "Desactivar modelo", providerId = "p3")),
    )

    // ── lista de candidatos ────────────────────────────────────────────────────────

    @Test
    fun `Si el proveedor tiene modelos, se enumeran todos y el pedido permanece sin cambios`() {
        val options = SpeechModelPicker.options(provider)

        assertEquals(
            "Los candidatos deben provenir de la lista de modelos del proveedor.",
            listOf("tts-1" to "TTS 1", "tts-2" to "TTS 2"),
            options.map { it.id to it.name },
        )
    }

    @Test
    fun `Si el proveedor no tiene modelo, déjelo en blanco`() {
        assertTrue("Los proveedores vacíos no deberían crear candidatos de la nada", SpeechModelPicker.options(emptyProvider).isEmpty())
        assertTrue("Si no hay ningún proveedor, la selección estará vacía.", SpeechModelPicker.options(null).isEmpty())
    }

    @Test
    fun `Los proveedores candidatos eligen preferentemente el especificado en la configuración`() {
        val picked = SpeechModelPicker.candidateProvider(listOf(disabledProvider, provider), "p1")

        assertEquals("p1", picked?.id)
    }

    @Test
    fun `Cuando no se especifica ningún proveedor, se toma el primero habilitado`() {
        val picked = SpeechModelPicker.candidateProvider(listOf(disabledProvider, provider), "")

        assertEquals("Los proveedores desactivados no se pueden utilizar como candidatos predeterminados", "p1", picked?.id)
    }

    @Test
    fun `Un modelo que especifica que el proveedor se utilizará incluso si está desactivado`() {
        val picked = SpeechModelPicker.candidateProvider(listOf(provider, disabledProvider), "p3")

        assertEquals("Es posible que el usuario esté a punto de volver al modelo de este proveedor y no pueda cambiar silenciosamente al candidato.", "p3", picked?.id)
    }

    // ── Compatible con valores antiguos ────────────────────────────────────────────────────────

    @Test
    fun `Cuando el valor completado manualmente no se encuentra entre los candidatos, se muestra como personalizado y no se borra`() {
        val selection = SpeechModelPicker.selection(
            currentId = "legacy-model",
            options = SpeechModelPicker.options(provider),
        )

        assertTrue("El valor completado manualmente de la configuración anterior debe estar en el estado personalizado. De hecho,: $selection", selection is SpeechModelSelection.Custom)
        assertEquals("legacy-model", (selection as SpeechModelSelection.Custom).value)
    }

    @Test
    fun `Muestra el nombre del modelo del proveedor cuando el valor está en el candidato`() {
        val selection = SpeechModelPicker.selection("tts-2", SpeechModelPicker.options(provider))

        assertEquals(
            "TTS 2",
            (selection as SpeechModelSelection.FromProvider).model.name,
        )
    }

    @Test
    fun `Valor vacío significa no seleccionado`() {
        assertEquals(
            SpeechModelSelection.Empty,
            SpeechModelPicker.selection("   ", SpeechModelPicker.options(provider)),
        )
    }

    // ── campo de escritura ────────────────────────────────────────────────────────

    @Test
    fun `TTS El modo manual escribe ttsModel y no se mueve ttsModelId`() {
        val saved = SpeechModelPicker.applyToTts(
            config = MediaConfig(ttsEngine = "openai", ttsModel = "", ttsModelId = "", ttsFollowProvider = false),
            modelId = "tts-1",
        )

        assertEquals("tts-1", saved.ttsModel)
        assertEquals("", saved.ttsModelId)
    }

    @Test
    fun `TTS Sigue el patrón y escribe ttsModelId sin moverte ttsModel`() {
        val saved = SpeechModelPicker.applyToTts(
            config = MediaConfig(
                ttsEngine = "openai",
                ttsModel = "manual-model",
                ttsModelId = "",
                ttsFollowProvider = true,
            ),
            modelId = "tts-2",
        )

        assertEquals("tts-2", saved.ttsModelId)
        assertEquals("El valor en el modo manual debe conservarse y seguirá utilizándose si el interruptor está apagado.", "manual-model", saved.ttsModel)
    }

    @Test
    fun `ASR Escritura en modo manual model`() {
        val saved = SpeechModelPicker.applyToAsr(
            config = AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                model = "whisper-1",
                modelId = "old-id",
                followProvider = false,
            ),
            modelId = "whisper-large-v3",
        )

        assertEquals("whisper-large-v3", saved.model)
        assertEquals("old-id", saved.modelId)
    }

    @Test
    fun `ASR seguir el patrón escribir modelId`() {
        val saved = SpeechModelPicker.applyToAsr(
            config = AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                model = "whisper-1",
                modelId = "",
                followProvider = true,
                providerId = "p1",
            ),
            modelId = "tts-1",
        )

        assertEquals("tts-1", saved.modelId)
        assertEquals("whisper-1", saved.model)
    }

    @Test
    fun `ASR DashScope Incluso si lo sigues, todavía lo escribes model`() {
        val config = AsrConfig(
            provider = AsrProviderType.DASHSCOPE,
            model = "paraformer-realtime-v2",
            modelId = "",
            followProvider = true,
            providerId = "p1",
        )

        assertTrue("DashScope Utilice el punto final integrado y el interruptor de seguimiento no tendrá efecto.", !SpeechModelPicker.asrFollowActive(config))
        assertEquals("paraformer-v2", SpeechModelPicker.applyToAsr(config, "paraformer-v2").model)
        assertEquals("", SpeechModelPicker.applyToAsr(config, "paraformer-v2").modelId)
    }

    @Test
    fun `Las escrituras personalizadas siguen surtiendo efecto cuando no hay ningún proveedor`() {
        assertNull(SpeechModelPicker.candidateProvider(emptyList(), "p1"))

        val saved = SpeechModelPicker.applyToTts(MediaConfig(ttsEngine = "openai"), "my-model")

        assertEquals("my-model", saved.ttsModel)
    }
}
