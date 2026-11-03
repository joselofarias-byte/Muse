package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * P1-3b Deslocalización:TTS Implementación de herramientas.
 * Registrado en ToolRegistry por TtsToolsRegistrar.
 */
class TtsToolsImpl(private val context: Context) {

    suspend fun speak(args: Map<String, String>): String {
        val text = args["text"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.tool_url_missing)
        val language = args["language"]
        val rate = args["rate"]?.toFloatOrNull()?.coerceIn(0.25f, 4.0f) ?: 1.0f
        return resultOf {
            val result = speakWithTts(text, language, rate)
            if (result) {
                context.getString(R.string.tool_speak_text_success, text.take(50))
            } else {
                context.getString(R.string.tool_speak_text_failed)
            }
        }.onError { msg, _ -> Logger.w("TtsTools", "TTS falló: $msg") }
            .getOrNull() ?: context.getString(R.string.tool_speak_text_failed)
    }

    private suspend fun speakWithTts(text: String, language: String?, rate: Float): Boolean {
        return suspendCancellableCoroutine { cont ->
            var tts: android.speech.tts.TextToSpeech? = null
            // P2-16: Entrada de lanzamiento unificada — Cada instancia se utiliza después(Leer en voz alta completado/falló/Cancelar)debe ser shutdown,
            // De lo contrario, cada llamada de herramienta pierde uno TextToSpeech(Vincular procesos y recursos del motor).
            val release: () -> Unit = {
                val engine = tts
                tts = null
                engine?.runCatching { shutdown() }
            }
            tts = android.speech.tts.TextToSpeech(context) { status ->
                if (status != android.speech.tts.TextToSpeech.SUCCESS) {
                    release()
                    if (cont.isActive) cont.resume(false) { _, _, _ -> }
                    return@TextToSpeech
                }
                try {
                    tts?.setSpeechRate(rate)
                    if (!language.isNullOrBlank()) {
                        val locale = java.util.Locale.forLanguageTag(language)
                        val langResult = tts?.setLanguage(locale)
                        if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA ||
                            langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            release()
                            if (cont.isActive) cont.resume(false) { _, _, _ -> }
                            return@TextToSpeech
                        }
                    }
                    val utteranceId = "tts_tool_${System.currentTimeMillis()}_${text.hashCode()}"
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        val params = android.os.Bundle()
                        tts?.setOnUtteranceProgressListener(
                            object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(uttId: String?) {}

                                @Deprecated("Deprecated in Java")
                                override fun onError(uttId: String?) {
                                    release()
                                }

                                override fun onError(uttId: String?, errorCode: Int) {
                                    if (uttId == utteranceId) release()
                                }

                                override fun onDone(uttId: String?) {
                                    if (uttId == utteranceId) release()
                                }
                            },
                        )
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                    } else {
                        @Suppress("DEPRECATION")
                        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null)
                        // pre-L No se puede completar mediante el reconocimiento de devolución de llamada de expresión,Cubierta de lanzamiento retrasado
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(release, 3_000L)
                    }
                    cont.resume(true) { _, _, _ -> }
                } catch (e: Exception) {
                    Logger.w("TtsTools", "TTS excepción de lectura: ${e.message}")
                    release()
                    if (cont.isActive) cont.resume(false) { _, _, _ -> }
                }
            }
            cont.invokeOnCancellation {
                release()
            }
        }
    }
}
