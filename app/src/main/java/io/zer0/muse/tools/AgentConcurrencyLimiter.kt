package io.zer0.muse.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * v1.0.53: Limitador de concurrencia global de subagente.
 *
 * - Semántica del semáforo:maxConcurrent ejecutar simultáneamente,fuera de linea(espera de encuesta)
 * - backstop:Si la distribución total acumulada excede maxTotal, se rechazará directamente.(Prevenir la pérdida de control)
 * - Singleton global(Inyectado a través de Koin),Todos los portales de delegación comparten la misma cuota
 *   - TeamWorkflowExecutor.executeParallel(Nodo paralelo del equipo)
 *   - SkillExecutor.delegateAgent nonBlocking camino(Delegación asincrónica del subayudante)
 *   - SubagentRunner.run(El agente pasivo bloquea múltiples ciclos de herramientas)
 *
 * Instrucciones de implementación:
 *  - Proteja los recuentos activos/totales generados con Mutex,evitar lost update
 *  - Esperando encuestas(100ms),La versión celular es suficiente.;Si necesita una latencia más baja, puede cambiarla. Channel<Unit> transmisión de tragamonedas
 *  - release() Debe estar bloqueado y disminuido. — La disminución simple perderá las actualizaciones durante el lanzamiento simultáneo,Provoca un error de conteo activo
 */
class AgentConcurrencyLimiter(
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val maxTotal: Int = DEFAULT_MAX_TOTAL,
) {
    companion object {
        /** límite superior 256;Guárdalo en el teléfono celular 8(La simultaneidad del modelo local es limitada.). */
        const val DEFAULT_MAX_CONCURRENT = 8

        /** El límite de distribución acumulativo dentro del ciclo de vida de una sola aplicación.,Prevenir la pérdida de control. */
        const val DEFAULT_MAX_TOTAL = 200

        /** El intervalo de sondeo mientras se espera un espacio. */
        private const val POLL_INTERVAL_MS = 100L
    }

    private val mutex = Mutex()
    private val active = java.util.concurrent.atomic.AtomicInteger(0)
    private var totalSpawned = 0

    /** Recuento activo actual(lectura atómica,para diagnóstico). */
    val activeCount: Int get() = active.get()

    /**
     * ejecutar [block],Si la concurrencia está llena, espere.
     *
     * @throws IllegalStateException Cuando se excede el respaldo maxTotal
     */
    suspend fun <T> run(block: suspend () -> T): T {
        mutex.withLock {
            totalSpawned++
            if (totalSpawned > maxTotal) {
                throw IllegalStateException(
                    "La distribución acumulada del subagente supera el límite superior. $maxTotal(Prevenir la pérdida de control backstop)",
                )
            }
        }
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() {
        // camino rápido:Hay un espacio vacío por primera vez.
        mutex.withLock {
            if (active.get() < maxConcurrent) {
                active.incrementAndGet()
                return
            }
        }
        // camino lento:espera de encuesta
        while (true) {
            delay(POLL_INTERVAL_MS)
            mutex.withLock {
                if (active.get() < maxConcurrent) {
                    active.incrementAndGet()
                    return
                }
            }
        }
    }

    /**
     * Debe estar bloqueado y disminuido.
     *
     * Decremento desnudo durante el lanzamiento simultáneo(active--)Las actualizaciones se perderán,Provoca un error de conteo activo
     * (puede volverse negativo o quedar permanentemente estancado en maxConcurrent,Desactivar el semáforo).
     * operación compuesta(comprobar+aumentar o disminuir)Utilice Mutex para excluirse entre sí,Lea usando AtomicInteger para garantizar la visibilidad.
     */
    private suspend fun release() {
        mutex.withLock {
            if (active.get() > 0) active.decrementAndGet()
        }
    }
}
