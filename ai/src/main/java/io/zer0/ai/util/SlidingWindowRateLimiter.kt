package io.zer0.ai.util

import kotlinx.coroutines.delay

/**
 * P1-3: Limitador de ventana corredera.
 *
 * en [windowMs] Máximo permitido dentro de la ventana de tiempo [maxRequests] solicitudes.
 * [acquire] Cuelgue y espere hasta que la ventana se abra cuando se exceda el límite,en lugar de rechazarlo directamente ——
 * Rutas de conversación más amigables para los usuarios(El usuario espera unos segundos más.,en lugar de ver"Limitación de corriente"error).
 *
 * darse cuenta:usar [ArrayDeque] Registre la marca de tiempo de cada solicitud dentro de la ventana.
 *  - acquire Primero limpie la marca de tiempo vencida(antes que now - windowMs)
 *  - Si la longitud de la cola < [maxRequests],Agregar la marca de tiempo actual y regresar
 *  - De lo contrario, calcule el tiempo de espera necesario para que la marca de tiempo más temprana salga de la ventana.,[delay] Vuelve a intentarlo más tarde
 *
 * Seguridad del hilo:Toda la lectura y escritura están en [lock] dentro del bloque de sincronización,ArrayDeque Operación extremadamente rápida(Nivel de microsegundo),
 * La contención de bloqueo se puede ignorar. Adecuado para niveles de RPM(docenas de veces por segundo acquire)escena.
 *
 * con [io.zer0.ai.util.KeyRoulette] relación:
 *  - KeyRoulette si"Reacción"Estrategia:429 Cambiar al siguiente después de regresar key
 *  - SlidingWindowRateLimiter si"Activo"Estrategia:Control de tarifas antes de emitir la solicitud.
 *  - Los dos se complementan,Se puede utilizar en combinación
 *
 * @param maxRequests Número máximo de solicitudes dentro de la ventana,debe > 0
 * @param windowMs tamaño de ventana(milisegundos),Predeterminado 60 segundos(RPM)
 * @param clock fuente de reloj,Inyectable para pruebas
 */
class SlidingWindowRateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxRequests > 0) { "maxRequests must be > 0, got $maxRequests" }
        require(windowMs > 0) { "windowMs must be > 0, got $windowMs" }
    }

    /** Solicitar marca de tiempo dentro de la ventana(monótonamente creciente,La cabeza es la más antigua.). */
    private val timestamps = ArrayDeque<Long>()
    private val lock = Any()

    /**
     * Obtenga un permiso de solicitud. Cuando se exceda el límite, espere hasta que haya espacio disponible.
     *
     * Semántica de cancelación:en [delay] Si la corrutina se cancela durante el período de espera,se extenderá normalmente CancellationException,
     * No consumirá licencia(La marca de tiempo no se agregó correctamente a esta adquisición.).
     */
    suspend fun acquire() {
        while (true) {
            val waitMs = synchronized(lock) {
                val now = clock()
                val cutoff = now - windowMs
                // Limpiar marcas de tiempo caducadas(La cabeza es la más antigua.)
                while (timestamps.isNotEmpty() && timestamps.first() <= cutoff) {
                    timestamps.removeFirst()
                }
                if (timestamps.size < maxRequests) {
                    // hay espacio,Agregar y devolver el valor centinela para indicar que se ha obtenido
                    timestamps.addLast(now)
                    ACQUIRED
                } else {
                    // por encima del límite,Calcule el tiempo de espera para que la primera marca de tiempo salga de la ventana
                    val oldest = timestamps.first()
                    (oldest + windowMs - now).coerceAtLeast(1L)
                }
            }
            if (waitMs == ACQUIRED) return
            delay(waitMs)
        }
    }

    /** El número de solicitudes registradas en la ventana actual.(Se utiliza principalmente para pruebas y monitoreo.). */
    fun currentCount(): Int = synchronized(lock) {
        val now = clock()
        val cutoff = now - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() <= cutoff) {
            timestamps.removeFirst()
        }
        timestamps.size
    }

    private companion object {
        /** acquire Valor centinela devuelto en caso de éxito(Los números negativos no se pueden utilizar como tiempos de espera.). */
        const val ACQUIRED = -1L
    }
}
