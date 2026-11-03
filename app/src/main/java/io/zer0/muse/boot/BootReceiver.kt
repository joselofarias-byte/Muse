package io.zer0.muse.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity

/**
 * v0.32: El receptor se inicia automáticamente al arrancar.
 *
 * mecanismo de trabajo:
 *  - AndroidManifest en [BootReceiver] de `android:enabled` El valor predeterminado es false,
 *    El sistema no le distribuye transmisiones BOOT_COMPLETED.
 *  - Usuario en "Configuración" → Memorias y Notificaciones → Cuando el "Inicio automático al arrancar" está activado,[MuseApp] llamar
 *    [PackageManager.setComponentEnabledSetting] Cortar el componente a ENABLED,
 *    BOOT_COMPLETED no se entregará hasta que el sistema se encienda la próxima vez.
 *  - Comenzar después de recibir la transmisión [MainActivity](entrada principal),No levantes directamente Service
 *    (muse de memory ticker / scheduled task runner Todo asumido por MuseApp.onCreate).
 *
 * seguro:
 *  - Escuchar solo BOOT_COMPLETED(transmisión a nivel del sistema,Requiere permiso RECEIVE_BOOT_COMPLETED)
 *  - No acepte terceros Intent(Garantizado por el atributo de permiso del manifiesto.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Logger.i(TAG, "BOOT_COMPLETED received, launching MainActivity")
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // M3-1: Usar en su lugar resultOf{}
        resultOf { context.startActivity(launchIntent) }
            .onError { msg, _ -> Logger.w(TAG, "launch MainActivity failed: $msg") }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
