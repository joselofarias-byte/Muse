package io.zer0.muse.tools.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import io.zer0.common.Logger
import io.zer0.common.resultOf

/**
 * Entrada de programación unificada para despertadores recordatorios.
 *
 * herramienta de recordatorio (`schedule_reminder`) se comparte aquí con el recordatorio en la página de registro rápido para evitar tres cosas AlarmManager
 * Llame a cada implementación y desviación de comportamiento.
 *
 * Estrategia de programación:
 *  - Se utiliza cuando tienes permisos de alarma precisos. `setExactAndAllowWhileIdle`, disparar a tiempo;
 *  - Bajar de categoría a `setAndAllowWhileIdle`(no es exacto, puede retrasarse varios minutos), se garantiza que "sonará",
 *    En lugar de fallar directa y silenciosamente, desechar el recordatorio encargado por el usuario como antes.
 *  - Cuando el sistema rechaza la programación (lanza una excepción), devuelve falso y pasa el motivo del error. [ReminderEntry.scheduleError] Márcalo en la entrada.
 */
object ReminderAlarmScheduler {

    private const val TAG = "ReminderAlarmScheduler"

    /** Resultado de programación único. */
    data class ScheduleResult(
        val scheduled: Boolean,
        /** Si está utilizando un despertador preciso (false La representación se ha rebajado a imprecisa y puede retrasarse). */
        val exact: Boolean,
        /** ¿Necesita guiar a los usuarios para que activen el permiso \"Despertador y recordatorio\"? */
        val needsExactPermission: Boolean,
        /** Una razón legible por humanos en caso de fracaso, nula en caso de éxito. */
        val error: String? = null,
    )

    /** Si actualmente tiene el permiso preciso para el despertador (API < 31 considerado siempre disponible). */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /**
     * Vaya a la página de autorización "Despertador y recordatorio" del sistema para permitir a los usuarios habilitar manualmente permisos precisos de despertador.
     *
     * prioridad `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`;Si algunas ROM no tienen esta página, volverán a la página de detalles de la aplicación.
     * Asegúrese de que los usuarios siempre puedan llegar a un lugar donde puedan cambiar los permisos.
     *
     * @return Si una página del sistema se abrió correctamente
     */
    fun requestExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val pkgUri = android.net.Uri.fromParts("package", context.packageName, null)
        val candidates = listOf(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = resultOf { context.startActivity(intent) }
                .onError { msg, _ -> Logger.w(TAG, "No se pudo abrir la página de configuración de alarma precisa: $msg") }
                .isSuccess
            if (ok) return true
        }
        return false
    }

    /** PendingIntent para recordatorio de activación de empalme (con [ReminderAlarmReceiver] acuerdo). */
    private fun buildPendingIntent(
        context: Context,
        id: String,
        title: String,
        message: String,
        targetType: String,
        targetId: String,
    ): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(ReminderAlarmReceiver.EXTRA_ID, id)
            putExtra(ReminderAlarmReceiver.EXTRA_TITLE, title)
            putExtra(ReminderAlarmReceiver.EXTRA_MESSAGE, message)
            putExtra(ReminderAlarmReceiver.EXTRA_TARGET_TYPE, targetType)
            putExtra(ReminderAlarmReceiver.EXTRA_TARGET_ID, targetId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }

    /**
     * Programe un recordatorio.
     *
     * @param isCatchUp Escena de reedición (ha pasado el tiempo del recordatorio): Agregar "(suplemento)”y aún así ir con precisión/Degradar el mismo conjunto de lógica.
     */
    fun schedule(
        context: Context,
        id: String,
        title: String,
        message: String,
        triggerAtMillis: Long,
        targetType: String = ReminderAlarmReceiver.TARGET_HOME,
        targetId: String = "",
    ): ScheduleResult {
        val exactAvailable = canScheduleExact(context)
        return resultOf {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildPendingIntent(context, id, title, message, targetType, targetId)
            if (exactAvailable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            } else {
                // Sin permiso de alarma preciso: cambie a una programación no precisa, asegurando que el recordatorio "sonará", pero puede llegar con unos minutos de retraso.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                }
            }
            ScheduleResult(
                scheduled = true,
                exact = exactAvailable,
                needsExactPermission = !exactAvailable,
            )
        }.onError { msg, _ -> Logger.w(TAG, "Error en el recordatorio de programación: id=$id, $msg") }
            .getOrNull()
            ?: ScheduleResult(
                scheduled = false,
                exact = false,
                needsExactPermission = !exactAvailable,
                error = "alarm_schedule_failed",
            )
    }

    /** Cancela la alarma para un recordatorio (no afecta el PendingIntent de otros elementos). */
    fun cancel(context: Context, id: String) {
        resultOf {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderAlarmReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
            am.cancel(pi)
            pi.cancel()
        }.onError { msg, _ -> Logger.w(TAG, "No se pudo cancelar el despertador recordatorio: id=$id, $msg") }
    }
}
