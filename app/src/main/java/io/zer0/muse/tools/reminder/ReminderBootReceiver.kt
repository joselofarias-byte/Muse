package io.zer0.muse.tools.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restablecer la alarma recordatoria después de reiniciar/actualizar la aplicación.
 *
 * Por qué lo necesitas:AlarmManager Todos los despertadores en la cola se perderán después de reiniciar el dispositivo y las aplicaciones se sobrescribirán e instalarán (actualizarán).
 * también se perderá más tarde. Si no se realiza ningún reordenamiento,[ReminderStore] Todavía hay elementos "no activados" y el usuario piensa que el recordatorio todavía está ahí.
 * En realidad nunca suena —— Esta es la forma más sutil de caída del cabello.
 *
 * No tiene nada que ver con el interruptor "Inicio automático al iniciar": el recordatorio establecido por el usuario no debe perderse debido al "Inicio automático sin iniciar", por lo que esto Receiver
 * Está habilitado de forma predeterminada en el manifiesto y no sigue el inicio automático.
 *
 * Solo reorganizar, no reproducir Actividad, no enviar mensajes activos:
 *  - Los recordatorios futuros se reprograman en AlarmManager;
 *  - Los recordatorios que se hayan perdido se eliminarán después de recibir una notificación (consulte [ReminderStore.rescheduleAll]).
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return
        Logger.i(TAG, "recibido $action, comienza a reprogramar recordatorios")
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // Utilice resultOf en su lugar catch (Exception): Nadie detecta la excepción lanzada en el receptor de transmisión.
                // y catch (Exception) Se tragará la cancelación de la corrutina (CancellationException También excepción).
                resultOf { ReminderStore(appContext).rescheduleAll() }
                    .onSuccess { summary ->
                        Logger.i(
                            TAG,
                            "Reordenamiento completado: éxito=${summary.rescheduled}, reedición=${summary.catchUpNotified}, " +
                                "falló=${summary.failed}, Permisos precisos=${summary.exact}",
                        )
                    }
                    .onError { msg, t -> Logger.e(TAG, "No se pudo reprogramar el recordatorio: $msg", t) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ReminderBootReceiver"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
