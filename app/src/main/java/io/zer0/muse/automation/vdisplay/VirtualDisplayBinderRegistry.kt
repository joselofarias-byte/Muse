package io.zer0.muse.automation.vdisplay

import android.os.IBinder

/**
 * v2.2.1 pantalla virtual:Registro de Binder del lado del servidor.
 *
 * Servidor(shell uid proceso)Enviar la carpeta a la aplicación principal mediante transmisión
 * [VirtualDisplayBinderReceiver],El extremo receptor escribe este registro.;
 * [VirtualDisplayServerManager] Obtenga carpeta desde aquí para determinar la supervivencia y la reutilización.
 */
object VirtualDisplayBinderRegistry {
    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var expectedHandoffToken: String? = null

    /** Recibe nuevo de la mano binder(O borrar cuando haya caducado). */
    fun update(value: IBinder?) {
        binder = value
    }

    fun setExpectedHandoffToken(token: String) {
        expectedHandoffToken = token.takeIf { it.isNotBlank() }
    }

    fun clearHandoffToken() {
        expectedHandoffToken = null
    }

    /** Accept only a binder that proves knowledge of the current launch token. */
    fun accept(value: IBinder, handoffToken: String?): Boolean {
        val expected = expectedHandoffToken ?: return false
        if (handoffToken.isNullOrBlank() || handoffToken != expected) return false
        binder = value
        return true
    }

    /** actual binder(binder Devuelve nulo cuando está muerto y lo borra convenientemente). */
    fun current(): IBinder? {
        val value = binder ?: return null
        if (!value.isBinderAlive) {
            binder = null
            return null
        }
        return value
    }
}
