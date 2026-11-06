package io.zer0.muse.ui.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * B8-05: Fish Audio Prueba de normalización de direcciones del servicio de clonación de voz.
 *
 * v2.6.1 (#3): El punto final clonado es de un dominio diferente al del punto final TTS —— clonar https://api.fish.audio/model,
 * TTS Utilice el sufijo /v1 que trae la configuración TTS https://api.fish.audio/v1/tts。,
 * De lo contrario, se solicitará /v1/voices, que no existe (la causa principal del error informado por los usuarios).
 */
class FishAudioVoiceCloningProviderTest {

    @Test
    fun stripsTtsV1SuffixLeftFromTtsConfig() {
        assertEquals(
            "https://api.fish.audio",
            FishAudioVoiceCloningProvider.normalizeEndpoint("  https://api.fish.audio/v1/  "),
        )
    }

    @Test
    fun keepsHostWithoutSuffix() {
        assertEquals(
            "https://api.fish.audio",
            FishAudioVoiceCloningProvider.normalizeEndpoint("https://api.fish.audio/"),
        )
    }

    @Test
    fun fallsBackToDefaultEndpointWhenBlank() {
        assertEquals(
            FishAudioVoiceCloningProvider.DEFAULT_ENDPOINT,
            FishAudioVoiceCloningProvider.normalizeEndpoint("   "),
        )
        assertEquals("https://api.fish.audio", FishAudioVoiceCloningProvider.DEFAULT_ENDPOINT)
    }
}
