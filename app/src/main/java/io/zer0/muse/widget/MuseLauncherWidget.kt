package io.zer0.muse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F-25: Widget de inicio de escritorio(Versión mínima disponible).
 *
 * mostrar "abierto Muse" Botón + etiqueta de hora actual del sistema;Al hacer clic en toda la tarjeta o el botón, se accederá al enlace profundo. muse://chat
 * levantarse [MainActivity] Vaya directamente a la página de conversación. Utilice el diseño mínimo compatible con RemoteViews,No se introducen dependencias adicionales.
 */
class MuseLauncherWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, appWidgetId))
        }
    }

    private fun buildViews(context: Context, appWidgetId: Int): RemoteViews {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return RemoteViews(context.packageName, R.layout.widget_muse_launcher).apply {
            setTextViewText(R.id.widget_muse_time, time)
            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse("muse://chat"))
                .setComponent(ComponentName(context, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context,
                appWidgetId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setOnClickPendingIntent(R.id.widget_muse_root, pending)
            setOnClickPendingIntent(R.id.widget_muse_open, pending)
        }
    }
}
