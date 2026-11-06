package io.zer0.muse.ui.chat

/**
 * v1.x: Interfaz de escritura para anulación de proveedor/modelo a nivel de sesión.
 * ChatViewModel sosteniendo un par @Volatile override map,Reasignado por cada recopilador de flujo;
 * Sólo expuesto aquí"escribir"Operación(ajustes/Borrar),Para que ChatSettingsController se actualice sin depender del host.
 */
interface SessionModelSelectionStore {
    /** Establecer nivel de sesión provider override(null = Borrar,Regresar al default global). */
    fun setProviderOverride(sessionId: String, providerId: String?)

    /** Establecer nivel de sesión model override(null = Borrar,Primer modelo del proveedor alternativo). */
    fun setModelOverride(sessionId: String, modelId: String?)
}
