package io.zer0.muse.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Un mensaje activo que notifica al receptor sobre el botón "Esta vez no".
 *
 * Solo accesible dentro de la aplicación: usar [androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED] registro dinámico,
 * La transmisión la envía el PendingIntent de la propia aplicación y no hay motivo ni necesidad de que otras aplicaciones la llamen.
 *
 * La acción solo hace una cosa: marcar este mensaje activo como DESMITIDO en la cuenta de resultados (el usuario dijo explícitamente "este mensaje no debe enviarse"),
 * Por cierto, guarda las notificaciones. No cambie la configuración ni suspenda rondas posteriores —— Ser poco halagador no significa callarse.
 */
class ProactiveDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val outcomeId = ProactiveDismissProtocol.dismissedOutcomeId(
            action = intent.action,
            extra = intent.getStringExtra(ProactiveDismissProtocol.EXTRA_OUTCOME_ID),
        ) ?: return

        // Al hacer clic en el botón no se cerrará automáticamente la notificación (autoCancel Simplemente haga clic en el cuerpo de la notificación) y recójalo manualmente aquí.
        val notifId = intent.getIntExtra(ProactiveDismissProtocol.EXTRA_NOTIFICATION_ID, 0)
        if (notifId != 0) {
            resultOf { NotificationManagerCompat.from(context).cancel(notifId) }
                .onError { msg, _ -> Logger.w(TAG, "No se pudo cerrar la notificación: $msg") }
        }

        val scheduler = resultOf { GlobalContext.get().get<ProactiveScheduler>() }.getOrNull()
        if (scheduler == null) {
            // Cuando la dependencia no está lista (el proceso se reinicia a mitad de camino), es mejor perder esta declaración que realizar E/S en el hilo principal.
            Logger.w(TAG, "Se recibió \"No utilizado esta vez\", pero el programador no está listo, por lo que esta vez se ignorará.")
            return
        }
        // onReceive Sólo hay un límite de tiempo de regreso de unos diez segundos. Deje la biblioteca en el ámbito del nivel de aplicación. No cuelgues la retransmisión aquí.
        val scope = resultOf { GlobalContext.get().get<CoroutineScope>() }.getOrNull() ?: return
        scope.launch { scheduler.onProactiveDismissed(outcomeId) }
    }

    private companion object {
        const val TAG = "ProactiveDismiss"
    }
}
