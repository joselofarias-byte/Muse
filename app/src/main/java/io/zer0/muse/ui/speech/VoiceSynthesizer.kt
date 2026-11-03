package io.zer0.muse.ui.speech

import java.io.File

/**
 * Puerto de síntesis de voz.
 *
 * [TtsManager] Es un singleton y su construcción es para levantar el motor del sistema y la capa de herramientas (`send_voice`) confiar directamente en él haría
 * La prueba única debe efectivamente arrancar el motor. Después de extraer la interfaz, la prueba única puede verificar el flujo de la herramienta inyectando una implementación falsa (similar a
 * [VoiceDurationProbe] La misma razón: mantener los servicios de Android no comprobables fuera del puerto).
 *
 * No hecho a propósito `TtsManager` La interfaz principal de:`TtsManager` En la línea base detekt hay
 * `TooManyFunctions` exento, mientras que las líneas de base coinciden con las firmas por clase —— La paternidad invalidará esta exención.
 */
fun interface VoiceSynthesizer {
    /**
     * poner [text] Sintetizar para escribir audio [outputFile].
     *
     * @param emotionOverride La cobertura emocional de esta síntesis; vacío/Depende de la implementación recurrir al sentimiento configurado de TTS global cuando se deja en blanco.
     *   **Sólo afecta a esta convocatoria.**, no escriba la configuración global —— TtsManager Es una instancia única y el enlace de lectura puede estar ejecutándose al mismo tiempo.
     * @return Uno de tres resultados: éxito del motor preferido/éxito del sonido del sistema de respaldo/fallo total (consulte [TtsSynthResult]).
     *   El respaldo también se considera exitoso (el audio está disponible), pero la persona que llama debe poder dejar en claro que "el tono no es el asignado por el usuario".
     */
    suspend fun synthesizeTo(text: String, outputFile: File, emotionOverride: String?): TtsSynthResult
}
