package io.zer0.memory.observe

import java.util.concurrent.ConcurrentHashMap

/**
 * D3-P3: Retroceso de falla a nivel de paso.
 *
 * Las fallas continuas desencadenan un retroceso exponencial: Tras el enésimo fracaso consecutivo,en base * 2^(n-1)(Tapado [maxBackoffMs])
 * Omita este paso y ejecute dentro de la ventana.,protegerse contra"Reintentos infinitos por tics de hora LLM". Autorizado en caso de éxito.
 *
 * por qué es necesario: daily pipeline El tick cada hora reintentará el paso repetidamente después de que falle.,LLM clase reprobada
 * (Limitación de corriente/tiempo de espera) La llamada puede continuar ardiendo bajo;Backoff cambia la cadencia de reintento de"Reintentos sin sentido cada hora"converge a
 * "30min → 1h → 2h → … tapado 6h",6 Garantía de límite horario"Reparar y restaurar"(El usuario cambia la configuración/Después de restaurar la red
 * Vuelva a intentarlo automáticamente con éxito en un plazo máximo de 6 horas).
 *
 * Sólo estado de memoria: Reinicio del proceso claro(Reiniciar=nuevo contexto,Un solo reintento es inofensivo).
 * con [FailureClassifier] desacoplamiento: Todas las llegadas [recordFailure] un verdadero fracaso(no cancelación)Retiro unificado.
 */
class MemoryStepBackoff(
    private val baseMs: Long = DEFAULT_BASE_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private data class State(val consecutiveFailures: Int, val nextAllowedAt: Long)

    private val states = ConcurrentHashMap<String, State>()

    /** Si este paso actualmente está permitido ejecutarse(No dentro de la ventana de retroceso). */
    fun shouldRun(stepKey: String): Boolean {
        val s = states[stepKey] ?: return true
        return clock() >= s.nextAllowedAt
    }

    /** Registrar un fracaso,empujar la ventana de retroceso(crecimiento exponencial,tapado [maxBackoffMs]). */
    fun recordFailure(stepKey: String) {
        val now = clock()
        states.compute(stepKey) { _, prev ->
            val n = (prev?.consecutiveFailures ?: 0) + 1
            // El desplazamiento se limita a 8(×256)Prevención de derrames internos,Reunificación y limitación
            val backoff = (baseMs shl (n - 1).coerceAtMost(8)).coerceAtMost(maxBackoffMs)
            State(n, now + backoff)
        }
    }

    /** Registra un éxito,Borrar retroceso. */
    fun recordSuccess(stepKey: String) {
        states.remove(stepKey)
    }

    /** Número actual de fallos consecutivos(observación/para probar). */
    fun failureCount(stepKey: String): Int = states[stepKey]?.consecutiveFailures ?: 0

    /** ¿Cuántos milisegundos quedan hasta la siguiente ejecución permitida?(observación/para probar;No al retroceder 0). */
    fun remainingBackoffMs(stepKey: String): Long = ((states[stepKey]?.nextAllowedAt ?: 0L) - clock()).coerceAtLeast(0L)

    companion object {
        /** Retiro básico de 30 minutos. */
        const val DEFAULT_BASE_MS = 30L * 60 * 1000

        /** Limitado a 6 horas(garantia"Reparar y restaurar"último reintento). */
        const val DEFAULT_MAX_MS = 6L * 60 * 60 * 1000
    }
}
