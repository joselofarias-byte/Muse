package io.zer0.muse.ui.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import java.util.Locale

/**
 * Phase 5-D: Entrada de voz helper(system Intent fallback).
 *
 * v1.49: Vosk Se ha eliminado el reconocimiento sin conexión.(Reducir el tamaño del APK),Cuando este archivo se actualiza sin clave API
 * El principal camino alternativo.ChatScreen En el modo SISTEMA, mantenga presionado el micrófono y luego suéltelo.
 * [createIntent] + [parseResult] Iniciar el reconocimiento de voz del sistema.
 *
 * opciones de diseño: Llame al reconocedor de voz del sistema con la intención ACTION_RECOGNIZE_SPEECH,
 * No se requiere ningún tercero SDK,No es necesario gestionar usted mismo el ciclo de vida de la devolución de llamada de SpeechRecognizer.,
 * Compose Simplemente use recuerdaLauncherForActivityResult para iniciarlo.
 *
 * limitaciones:
 *  - Depende del sistema/Google Servicios de reconocimiento de voz aplicados.(Es posible que falte la ROM nacional)
 *  - Identificación en línea(dependencia de la red)
 *  - Identificar un párrafo a la vez(No apto para dictado continuo)
 *
 * valor de retorno: texto reconocido(Toma el primer candidato.),null Indica ningún resultado o cancelado.
 */
object SpeechInput {

    /** Construya una intención de reconocimiento de voz.prompt Cuadro de diálogo Solicitar copia para reconocimiento,La persona que llama pasa la cadena localizada. */
    fun createIntent(prompt: String = ""): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // L-SP4 Reparación: Originalmente codificado "zh-CN",Ahora sigue el idioma del sistema.(Locale.getDefault().toLanguageTag Devuelve etiquetas BCP-47 como zh-CN/en-US)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        if (prompt.isNotEmpty()) {
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
    }

    /**
     * Comprueba si el dispositivo tiene una actividad que pueda manejar la intención de reconocimiento de voz.
     *
     * Es posible que las ROM domésticas o los dispositivos sin aplicaciones de Google no tengan un reconocedor de voz disponible,
     * El inicio directo generará ActivityNotFoundException. Este método debe llamarse para determinar antes de comenzar.,
     * Proporcionar al usuario un mensaje Toast cuando no esté disponible,Evite accidentes.
     */
    fun isAvailable(context: Context): Boolean = createIntent().resolveActivity(context.packageManager) != null

    /** Obtenga el texto reconocido del resultado de la actividad. */
    fun parseResult(resultCode: Int, data: Bundle?): String? {
        if (resultCode != android.app.Activity.RESULT_OK) return null
        val list = data?.getStringArrayList(RecognizerIntent.EXTRA_RESULTS) ?: return null
        return list.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
