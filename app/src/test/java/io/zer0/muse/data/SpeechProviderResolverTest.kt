package io.zer0.muse.data

import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.asr.AsrConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS / ASRReglas de análisis "Seguir la configuración del proveedor".
 *
 * El contrato del analizador es "el comportamiento cuando se apaga el interruptor es exactamente el mismo que en la versión anterior",Entonces cada rama de reversión afirma tanto la fuente,
 * También afirma que lo que se obtiene después de revertir es, de hecho, el valor en la configuración manual. —— Simplemente afirmar que no colapsará significa que no hay prueba.
 */
class SpeechProviderResolverTest {

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

    private fun media(
        follow: Boolean,
        providerId: String = "p1",
        modelId: String = "tts-1",
    ): MediaConfig = MediaConfig(
        ttsEngine = "openai",
        ttsApiKey = "sk-manual",
        ttsModel = "manual-model",
        ttsEndpoint = "https://manual.example.com/v1",
        ttsFollowProvider = follow,
        ttsProviderId = providerId,
        ttsModelId = modelId,
    )

    private fun asr(
        follow: Boolean,
        providerId: String = "p1",
        modelId: String = "tts-1",
    ): AsrConfig = AsrConfig(
        provider = io.zer0.muse.asr.AsrProviderType.OPENAI_WHISPER,
        apiKey = "sk-manual",
        model = "manual-model",
        baseUrl = "https://manual.example.com/v1",
        followProvider = follow,
        providerId = providerId,
        modelId = modelId,
    )

    @Test
    fun followOffWithCompleteManualConfigUsesManual() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = false), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals("https://manual.example.com/v1", resolved.baseUrl)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("manual-model", resolved.model)
        assertNull("Apagar el interruptor no es una reversión,No se debe dar ninguna razón", resolved.fallbackReason)
    }

    @Test
    fun followOnWithEnabledProviderAndModelUsesProvider() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true), listOf(provider))

        assertEquals(SpeechEndpointSource.PROVIDER, resolved.source)
        assertEquals("https://relay.example.com/v1", resolved.baseUrl)
        assertEquals("sk-provider", resolved.apiKey)
        assertEquals("tts-1", resolved.model)
        assertNull(resolved.fallbackReason)
    }

    @Test
    fun followOnWithDeletedProviderFallsBackToManual() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true, providerId = "gone"), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals(SpeechProviderResolver.REASON_PROVIDER_MISSING, resolved.fallbackReason)
        assertEquals("https://manual.example.com/v1", resolved.baseUrl)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("manual-model", resolved.model)
    }

    @Test
    fun followOnWithDisabledProviderFallsBackToManual() {
        val disabled = provider.copy(enabled = false)
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true), listOf(disabled))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals(SpeechProviderResolver.REASON_PROVIDER_DISABLED, resolved.fallbackReason)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("manual-model", resolved.model)
    }

    @Test
    fun followOnWithoutModelFallsBackToManual() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = true, modelId = "  "), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals(SpeechProviderResolver.REASON_MODEL_MISSING, resolved.fallbackReason)
        assertEquals("manual-model", resolved.model)
    }

    @Test
    fun followOffWinsEvenWhenProviderExists() {
        val resolved = SpeechProviderResolver.resolveMedia(media(follow = false), listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals("sk-manual", resolved.apiKey)
        assertNull(resolved.fallbackReason)
    }

    @Test
    fun asrResolvesProviderEndpointAndKeepsOtherFields() {
        val config = asr(follow = true)
        val resolved = SpeechProviderResolver.resolveAsr(config, listOf(provider))
        val applied = config.withResolvedEndpoint(resolved)

        assertEquals(SpeechEndpointSource.PROVIDER, resolved.source)
        assertEquals("sk-provider", applied.apiKey)
        assertEquals("https://relay.example.com/v1", applied.baseUrl)
        assertEquals("tts-1", applied.model)
        // Los campos sin credenciales no se ven afectados
        assertEquals(config.sampleRate, applied.sampleRate)
        assertEquals(config.provider, applied.provider)
    }

    @Test
    fun asrFallbackKeepsManualValuesUntouched() {
        val config = asr(follow = true, providerId = "gone")
        val resolved = SpeechProviderResolver.resolveAsr(config, listOf(provider))
        val applied = config.withResolvedEndpoint(resolved)

        assertEquals(SpeechProviderResolver.REASON_PROVIDER_MISSING, resolved.fallbackReason)
        assertEquals("Heredar el objeto de configuración original,Los valores manuales no se sobrescriben", config, applied)
    }

    @Test
    fun asrDashScopeIgnoresFollowSwitchEntirely() {
        // DashScope Vaya al punto final wss incorporado:Incluso si el interruptor está encendido y el proveedor ha sido eliminado,Tampoco puedo solicitar reembolso(De lo contrario, aparecerán falsas alarmas.)
        val config = AsrConfig(
            provider = io.zer0.muse.asr.AsrProviderType.DASHSCOPE,
            apiKey = "sk-manual",
            model = "paraformer-realtime-v2",
            followProvider = true,
            providerId = "gone",
            modelId = "",
        )
        val resolved = SpeechProviderResolver.resolveAsr(config, listOf(provider))

        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertNull(resolved.fallbackReason)
        assertEquals("sk-manual", resolved.apiKey)
        assertEquals("paraformer-realtime-v2", resolved.model)
    }

    @Test
    fun legacyMediaConfigJsonWithoutNewFieldsDeserializesWithDefaults() {
        val legacyJson = """
            {
              "ttsSpeechRate": 1.2,
              "ttsEngine": "openai",
              "ttsApiKey": "sk-legacy",
              "ttsModel": "gpt-4o-mini-tts",
              "ttsVoice": "alloy",
              "ttsEndpoint": "https://legacy.example.com/v1"
            }
        """.trimIndent()

        val decoded = Json.decodeFromString(MediaConfig.serializer(), legacyJson)

        assertEquals(false, decoded.ttsFollowProvider)
        assertEquals("", decoded.ttsProviderId)
        assertEquals("", decoded.ttsModelId)
        // Los campos antiguos siguen siendo válidos.
        assertEquals("sk-legacy", decoded.ttsApiKey)
        assertEquals("https://legacy.example.com/v1", decoded.ttsEndpoint)
        assertEquals(1.2f, decoded.ttsSpeechRate, 0f)
        // Cuando el interruptor está apagado, los resultados del análisis deben ser consistentes con la versión anterior.
        val resolved = SpeechProviderResolver.resolveMedia(decoded, listOf(provider))
        assertEquals(SpeechEndpointSource.MANUAL, resolved.source)
        assertEquals("https://legacy.example.com/v1", resolved.baseUrl)
        assertTrue(resolved.fallbackReason == null)
    }
}
