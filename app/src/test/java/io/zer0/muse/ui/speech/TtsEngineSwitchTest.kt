package io.zer0.muse.ui.speech

import io.zer0.muse.data.MediaConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manejo de identificación de tono al cambiar de motor TTS.
 *
 * Sólo se cambia el motor pero no se cambia el sonido. Lo que obtiene el upstream es el motor anterior. id —— Debe ser 4xx, y luego el enlace completo.
 * Sistema de respaldo silencioso TTS. La clave aquí es "eliminarlo si no pertenece al nuevo motor, conservarlo si sí".
 */
class TtsEngineSwitchTest {

    @Test
    fun `Cuando los sonidos antiguos no pertenecen al nuevo motor, se borrarán y se indicarán.`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = "alloy"),
            newEngine = "dashscope",
        )

        assertEquals("dashscope", switched.config.ttsEngine)
        assertEquals("openai El sonido no se puede dejar debajo del tablero.", "", switched.config.ttsVoice)
        assertTrue("Una vez borrado, se le pedirá al usuario que vuelva a seleccionar.", switched.voiceReset)
    }

    @Test
    fun `Si el nuevo motor también tiene un sonido con el mismo nombre, se conservará.`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "dashscope", ttsVoice = "longxiaochun"),
            newEngine = "qwen",
        )

        assertEquals("qwen", switched.config.ttsEngine)
        assertEquals("qwen Lista de sonidos compartida con dashscope, la selección debe conservarse", "longxiaochun", switched.config.ttsVoice)
        assertFalse("No debería avisar si no se ha borrado.", switched.voiceReset)
    }

    @Test
    fun `Si no puede obtener la nueva colección de sonidos del motor, también se borrará.`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = "alloy"),
            newEngine = "elevenlabs",
        )

        assertEquals("elevenlabs", switched.config.ttsEngine)
        assertEquals("No hay forma de verificar el motor que pone la lista de tonos en línea y preferiría permitir que el usuario la vuelva a seleccionar.", "", switched.config.ttsVoice)
        assertTrue(switched.voiceReset)
    }

    @Test
    fun `No se da ningún aviso cuando el tono está originalmente vacío.`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = ""),
            newEngine = "elevenlabs",
        )

        assertEquals("", switched.config.ttsVoice)
        assertFalse("En primer lugar, no se seleccionó ningún tono, por lo que no cuenta como restablecido.", switched.voiceReset)
    }

    @Test
    fun `Cambie al TTS del sistema sin pasar a la selección de tono de nube`() {
        val switched = TtsEngineSwitch.apply(
            MediaConfig(ttsEngine = "openai", ttsVoice = "alloy"),
            newEngine = "system",
        )

        assertEquals("system", switched.config.ttsEngine)
        assertEquals("El motor del sistema no lee ttsVoice. Borrarlo solo obligará al usuario a volver a seleccionar en vano.", "alloy", switched.config.ttsVoice)
        assertFalse(switched.voiceReset)
    }

    @Test
    fun `Cambiar al motor actual sin cambiar ningún campo`() {
        val current = MediaConfig(ttsEngine = "openai", ttsVoice = "alloy")

        val switched = TtsEngineSwitch.apply(current, newEngine = "openai")

        assertEquals(current, switched.config)
        assertFalse(switched.voiceReset)
    }
}
