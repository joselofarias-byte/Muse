package io.zer0.muse.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Análisis de respuestas y empalme de puntos finales para transcriptores únicos.
 *
 * Dos funciones puras están fijadas por separado.:Sin campo de texto,text esta en blanco,Se considera “no escuché con claridad”——
 * Complete una cadena vacía en el cuadro de entrada,El usuario simplemente pensará que el micrófono está roto.
 */
class OneShotAsrTranscriberTest {

    @Test
    fun `Devuelto cuando no hay ningún campo de texto en la respuesta null`() {
        assertNull(parseOneShotTranscription("""{"duration":1.2,"task":"transcribe"}"""))
    }

    @Test
    fun `text Devuelve cuando todos están en blanco null`() {
        assertNull(parseOneShotTranscription("""{"text":"   \n\t "}"""))
    }

    @Test
    fun `text Devuelve cuando es una cadena vacía null`() {
        assertNull(parseOneShotTranscription("""{"text":""}"""))
    }

    @Test
    fun `text Devuelve cuando JSON es nulo null`() {
        assertNull(parseOneShotTranscription("""{"text":null}"""))
    }

    @Test
    fun `text Devuelve si no es una cadena null`() {
        assertNull(parseOneShotTranscription("""{"text":42}"""))
    }

    @Test
    fun `Devuelto cuando la respuesta no es JSON null`() {
        assertNull(parseOneShotTranscription("<html><body>502 Bad Gateway</body></html>"))
    }

    @Test
    fun `Devolver cuerpo de respuesta vacío null`() {
        assertNull(parseOneShotTranscription(""))
    }

    @Test
    fun `La respuesta normal es eliminar el texto con espacios en blanco al principio y al final`() {
        assertEquals("Habrá una reunión a las tres de la tarde.", parseOneShotTranscription("""{"text":"  Habrá una reunión a las tres de la tarde. \n"}"""))
    }

    @Test
    fun `Los campos adicionales no afectan el análisis`() {
        assertEquals("bueno", parseOneShotTranscription("""{"text":"bueno","language":"zh","segments":[]}"""))
    }

    @Test
    fun `La baseUrl asignada a v1 no duplica el segmento ortográfico`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            oneShotTranscriptionUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            oneShotTranscriptionUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun `El nombre de dominio simple se complementa con v1 y la ruta del punto final`() {
        assertEquals(
            "https://asr.example.com/v1/audio/transcriptions",
            oneShotTranscriptionUrl("https://asr.example.com"),
        )
    }

    @Test
    fun `Úselo como está cuando pegue directamente el punto final completo`() {
        assertEquals(
            "https://asr.example.com/audio/transcriptions",
            oneShotTranscriptionUrl("https://asr.example.com/audio/transcriptions"),
        )
    }

    @Test
    fun `Se considera que solo son compatibles los motores con llave compatibles con Whisper`() {
        assertTrue(AsrConfig(provider = AsrProviderType.OPENAI_WHISPER, apiKey = "sk-x").supportsFileTranscription())
        assertTrue(AsrConfig(provider = AsrProviderType.AGNES, apiKey = "sk-x").supportsFileTranscription())
        assertFalse("Incluso la solicitud no se puede enviar sin completar la clave.", AsrConfig(provider = AsrProviderType.OPENAI_WHISPER).supportsFileTranscription())
    }

    @Test
    fun `Los motores de streaming y asincrónicos no admiten el párrafo completo, incluso si tienen una clave`() {
        val unsupported = listOf(
            AsrProviderType.SYSTEM,
            AsrProviderType.DASHSCOPE,
            AsrProviderType.DASHSCOPE_FILE,
            AsrProviderType.STEP,
            AsrProviderType.OPENAI_REALTIME,
        )

        unsupported.forEach { provider ->
            assertFalse(
                "$provider Sin punto final /audio/transcripciones,no debe considerarse como un apoyo",
                AsrConfig(provider = provider, apiKey = "sk-x").supportsFileTranscription(),
            )
        }
    }

    @Test
    fun `baseUrl Si no es http, se considerará no compatible`() {
        assertFalse(
            AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                apiKey = "sk-x",
                baseUrl = "wss://asr.example.com/v1",
            ).supportsFileTranscription(),
        )
        assertTrue(
            AsrConfig(
                provider = AsrProviderType.OPENAI_WHISPER,
                apiKey = "sk-x",
                baseUrl = "https://asr.example.com/v1",
            ).supportsFileTranscription(),
        )
    }
}
