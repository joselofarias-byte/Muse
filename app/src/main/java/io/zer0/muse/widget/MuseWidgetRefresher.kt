package io.zer0.muse.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import io.zer0.common.Logger

/**
 * v2.6.7 fix (AUDIT F12): Entrada de actualización activa para widgets de escritorio.
 *
 * Antecedentes:Glance de `provideGlance` Solo cuando se agrega por primera vez, se completa la configuración y se transmite el sistema APPWIDGET_UPDATE
 * ejecución del tiempo.`muse_chat_widget_info.xml` No establecido `updatePeriodMillis`, no hay lugar en todo el almacén
 * si `MuseChatWidget` llamar `update/updateAll` —— Entonces, la vista previa del componente se detuvo en el momento de agregar el contenido durante mucho tiempo.
 * No importa cuánto hablemos después de eso, no cambiará.
 *
 * Aquí se proporciona una entrada unificada: una vez completada la generación, los dos widgets se pueden volver a representar llamándolos una vez.
 * Solo falla al iniciar sesión: la actualización del componente no puede afectar el enlace principal de la conversación.
 */
object MuseWidgetRefresher {

    private const val TAG = "MuseWidgetRefresher"

    /** Actualiza ambos widgets de Vistazo (Conversación + Acceso directo). Varias instancias se actualizan juntas. */
    suspend fun refreshAll(context: Context) {
        // updateAll Es una extensión de GlanceAppWidget y atraviesa internamente todas las instancias del widget.
        runCatching { MuseChatWidget().updateAll(context) }
            .onFailure { Logger.w(TAG, "No se pudo actualizar el widget de conversación: ${it.message}") }
        runCatching { MuseQuickWidget().updateAll(context) }
            .onFailure { Logger.w(TAG, "No se pudo actualizar el widget de acceso directo: ${it.message}") }
    }

    /** Actualiza solo el widget de conversación (se usa cuando cambia el mensaje). */
    suspend fun refreshChatWidget(context: Context) {
        runCatching { MuseChatWidget().updateAll(context) }
            .onFailure { Logger.w(TAG, "No se pudo actualizar el widget de conversación: ${it.message}") }
    }

    /** Para diagnóstico: el número actual de instancias de widget agregadas. */
    suspend fun instanceCount(context: Context): Int = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(MuseChatWidget::class.java).size
    }.getOrDefault(0)
}
