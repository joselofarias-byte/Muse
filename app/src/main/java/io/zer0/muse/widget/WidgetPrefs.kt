package io.zer0.muse.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * El widget conserva el ayudante seleccionado por el usuario por instancia.
 *
 * Clave con appWidgetId: se pueden colocar varios widgets en el mismo escritorio,Cada uno elige a su propio asistente.
 * para leer [MuseChatWidget]/[MuseQuickWidget] adentro `provideGlance` Lo tengo GlanceId
 * Cambie el appWidgetId y recupérelo por instancia,No utilice la aproximación de "tomar la primera instancia".
 */
object WidgetPrefs {
    private const val PREFS_NAME = "muse_widget_prefs"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveChatWidgetAssistant(context: Context, appWidgetId: Int, assistantId: String) {
        prefs(context).edit().putString("chat_widget_assistant_$appWidgetId", assistantId).apply()
    }

    fun getChatWidgetAssistant(context: Context, appWidgetId: Int): String {
        return prefs(context).getString("chat_widget_assistant_$appWidgetId", "default") ?: "default"
    }

    fun saveQuickWidgetAssistant(context: Context, appWidgetId: Int, assistantId: String) {
        prefs(context).edit().putString("quick_widget_assistant_$appWidgetId", assistantId).apply()
    }

    fun getQuickWidgetAssistant(context: Context, appWidgetId: Int): String {
        return prefs(context).getString("quick_widget_assistant_$appWidgetId", "default") ?: "default"
    }
}
