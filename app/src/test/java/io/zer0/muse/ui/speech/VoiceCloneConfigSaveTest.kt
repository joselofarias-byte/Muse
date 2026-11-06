package io.zer0.muse.ui.speech

import io.zer0.muse.data.MediaConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La página Voice Clone guarda las barreras de seguridad configuradas.
 *
 * La clonación es "exitosa" pero la clave no está en la biblioteca. Este es el tipo de fallo silencioso más difícil de comprobar online: el sonido parece haber cambiado.
 * La síntesis en la nube sigue recurriendo al TTS del sistema porque la clave está vacía. Aquí solo probamos la configuración que se colocará en la biblioteca.
 * No abras la página —— Lo único que puede salir mal en la página es el puro cálculo.
 */
class VoiceCloneConfigSaveTest {

    @Test
    fun `La clonación se realiza correctamente y el tono del motor y la clave en el cuadro de entrada se escriben en la configuración`() {
        val saved = MediaConfig(ttsEngine = "system", ttsVoice = "")
            .afterVoiceClone(engine = "elevenlabs", voiceId = "voice-abc", apiKey = "  sk-abc  ")

        assertEquals("P2-15: El motor debe seguir al proveedor utilizado para la clonación.", "elevenlabs", saved.ttsEngine)
        assertEquals("voice-abc", saved.ttsVoice)
        assertEquals("Se debe almacenar la clave en el cuadro de entrada y se deben eliminar los espacios iniciales y finales.", "sk-abc", saved.ttsApiKey)
    }

    @Test
    fun `No complete el formulario manualmente cuando siga a un proveedor key`() {
        val current = MediaConfig(
            ttsEngine = "openai",
            ttsVoice = "alloy",
            ttsApiKey = "from-provider",
            ttsFollowProvider = true,
        )

        val saved = current.afterVoiceClone(engine = "elevenlabs", voiceId = "voice-abc", apiKey = "sk-abc")

        assertEquals("elevenlabs", saved.ttsEngine)
        assertEquals("voice-abc", saved.ttsVoice)
        assertEquals("En el modo de seguimiento, la clave proviene del proveedor y no se puede sobrescribir con un valor ingresado manualmente.", "from-provider", saved.ttsApiKey)
    }

    @Test
    fun `Dejar en blanco cuando se sigue a un proveedor y originalmente no tenía clave`() {
        val saved = MediaConfig(ttsFollowProvider = true)
            .afterVoiceClone(engine = "fish", voiceId = "ref-1", apiKey = "sk-abc")

        assertEquals("", saved.ttsApiKey)
    }
}
