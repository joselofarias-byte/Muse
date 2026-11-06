package io.zer0.muse.automation.vdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.muse.vdproto.VdContract

/**
 * v2.2.1 pantalla virtual:Receptor mano a mano Binder del lado del servidor(exported=true).
 *
 * El proceso del servidor se ejecuta como uid de shell.,Transmisión vía AMS(Directo a este paquete de aplicación)Enviar carpeta.
 * Solo se acepta si la acción coincide tanto con el nombre del paquete de host como con el nombre del paquete de host.,Prevenir la falsificación por parte de terceros.
 */
class VirtualDisplayBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VdContract.ACTION_BINDER) return
        val host = intent.getStringExtra(VdContract.EXTRA_HOST_PACKAGE)
        if (host != context.packageName) {
            Logger.w(TAG, "Ignorar transmisiones de carpetas de pantalla virtuales de fuentes no coincidentes: host=$host")
            return
        }
        val binder = intent.extras?.getBinder(VdContract.EXTRA_BINDER) ?: return
        val handoffToken = intent.getStringExtra(VdContract.EXTRA_HANDOFF_TOKEN)
        if (!VirtualDisplayBinderRegistry.accept(binder, handoffToken)) {
            Logger.w(TAG, "Ignorar transmisiones de carpetas de pantalla virtuales con tokens no coincidentes")
            return
        }
        Logger.i(TAG, "La carpeta del servidor de pantalla virtual está en su lugar.")
    }

    companion object {
        private const val TAG = "VdBinderReceiver"
    }
}
