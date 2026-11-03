package io.zer0.muse.ui.speech

import io.zer0.muse.data.MediaConfig

/**
 * El producto de un interruptor de motor.
 *
 * @param config Nueva configuración que se incluirá en la biblioteca (el motor ha sido reemplazado por uno nuevo)
 * @param voiceReset Los sonidos antiguos no pertenecen al motor nuevo y han sido eliminados. —— UI Esto indicará "El tono se ha restablecido, seleccione nuevamente".
 */
data class TtsEngineSwitchResult(
    val config: MediaConfig,
    val voiceReset: Boolean,
)

/**
 * Sincronice los ID de parches al cambiar de motor TTS.
 *
 * Solo cambio [MediaConfig.ttsEngine] Se dejará una copia del ID de sonido del motor anterior: el upstream analizará este ID de acuerdo con el nuevo motor.
 * Lo que obtienes es 4xx, y luego todo el enlace vuelve silenciosamente al TTS del sistema. Por lo tanto, se verifica una vez al cambiar.
 * Si no pertenece al nuevo motor, déjelo en blanco (cada implementación de síntesis usará su propio timbre predeterminado cuando encuentre un timbre vacío y no habrá 4xx).
 */
object TtsEngineSwitch {

    /** La identificación del motor del sistema TTS (ver [MediaConfig.ttsEngine] valor predeterminado). */
    private const val SYSTEM_ENGINE = "system"

    fun apply(config: MediaConfig, newEngine: String): TtsEngineSwitchResult {
        // Cambiar al mismo motor no cuenta; el cambio al sistema tampoco se mueve. ttsVoice —— La voz del sistema va a ttsVoiceName,
        // ttsVoice No participa en absoluto en la síntesis bajo el motor del sistema. Borrarlo solo permitirá al usuario volver a la nube y volver a seleccionarla una vez.
        if (newEngine == config.ttsEngine || newEngine == SYSTEM_ENGINE) {
            return TtsEngineSwitchResult(config.copy(ttsEngine = newEngine), voiceReset = false)
        }
        val voice = config.ttsVoice
        // Si no puede obtener la colección de sonidos del nuevo motor (ElevenLabs/MiniMax, sonidos clonados que deben extraerse de Internet), no podrá verificarlo.
        // Trátelo como "no pertenece": es mejor dejar que el usuario vuelva a seleccionar que enviar una identificación que debe ser 4xx.
        val newVoiceIds = CloudTtsService.builtInVoiceIds(newEngine)
        val reset = voice.isNotBlank() && (newVoiceIds == null || voice !in newVoiceIds)
        return TtsEngineSwitchResult(
            config = config.copy(ttsEngine = newEngine, ttsVoice = if (reset) "" else voice),
            voiceReset = reset,
        )
    }
}
