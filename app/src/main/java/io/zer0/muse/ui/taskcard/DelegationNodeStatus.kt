package io.zer0.muse.ui.taskcard

/** Delegar el estado del nodo. */
enum class DelegationNodeStatus {
    RUNNING, // ejecutando
    COMPLETED, // Completado
    FAILED, // falló
    TIMEOUT, // tiempo de espera
    CANCELLED, // Cancelado
    PAUSED, // suspendido(Espere la confirmación del usuario)
}
