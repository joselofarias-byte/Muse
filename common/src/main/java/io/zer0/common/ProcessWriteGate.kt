package io.zer0.common

/**
 * B-7: Control de escritura a nivel de proceso — Copia de seguridad y restauración(Reemplazo de nivel de archivo DB)Durante este período se suspende la escritura en segundo plano de alta frecuencia.
 *
 * fondo: restoreFromAutoBackup Cerrará los tres singletons de Room y reemplazará los archivos de la biblioteca con instantáneas.,
 * Durante este período, si MemoryTicker/SchedledTaskRunner/copia de seguridad automática y otras corrutinas continúan escribiendo la biblioteca,
 * Puede ocurrir una pérdida por escritura en tránsito o la nueva WAL de la biblioteca puede ser inconsistente con los archivos adjuntos eliminados.
 *
 * Uso: Cuando comienza el proceso de recuperación. [begin] y lo hará [restoring] conjunto,despues del final [end] restablecer;
 * Cada entrada de escritura de alta frecuencia(Marca de memoria, sondeo de tareas, copia de seguridad automática)Verifique antes de escribir la biblioteca. [restoring],
 * Cuando es verdadero, esta escritura se omite y se registra. Sólo afecta"Saltar una escritura",No bloquea la interfaz de usuario.
 */
object ProcessWriteGate {
    /** Si se encuentra en la ventana de reemplazo a nivel de archivo de la base de datos(Copia de seguridad y restauración). Visible globalmente dentro del proceso. */
    @Volatile
    var restoring: Boolean = false
        private set

    /** Ingrese a la ventana de recuperación. Devuelve verdadero para indicar una entrada exitosa(es decir, no previamente en recuperación). */
    fun begin(): Boolean {
        return synchronized(this) {
            if (restoring) {
                false
            } else {
                restoring = true
                true
            }
        }
    }

    /** Salga de la ventana de recuperación. */
    fun end() {
        synchronized(this) {
            restoring = false
        }
    }
}
