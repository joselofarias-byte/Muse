package io.zer0.memory.observe

import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * D3-P1: Corredor de pasos unificado de canalización de memoria.
 *
 * poner [io.zer0.memory.ticker.MemoryTicker] pasos existentes(rollingSummary / compileDaily /
 * compileToday / rollDailyWindow / compileFacts / deepMemory espera)Envuelto en la misma capa,Colección unificada:
 *  - etapa([MemoryStage]), tecla de paso, fuente de activación
 *  - Consumo de tiempo, éxito/Resultado del fracaso
 *  - Clasificación de fallas([FailureKind],Sólo iniciar sesión)
 *  - Recuento acumulado por paso(total/successes/failures)
 *
 * y simultáneamente reportar al sistema de salud existente([onStepSuccess]/[onStepFailure] → MemoryTicker de
 * markSuccess/markFailure → healthFlow),No construyas un nuevo sistema de salud.
 *
 * contrato conductual(Garantizar el mismo valor antes y después del embalaje):
 *  - [CancellationException] Vuelva a lanzar como está,Nunca te tragues la señal de cancelación de rutina.;
 *  - Las devoluciones de llamadas de salud y la escritura de registros son ambas. [runCatching] proteger,La observación en sí nunca cambia el flujo de control.;
 *  - [HealthMode.NONE] Ningún contacto con el sistema de salud.(para rodarClase de resumen"Éxito pero sin cambios
 *    no debe considerarse un éxito"pasos especiales para usar,La persona que llama informa de su salud por su cuenta.).
 */
class MemoryStepRunner(
    /** Pasos para realizar informes de salud exitosos;null Indica no informar(Un entorno sin un sistema sano). */
    private val onStepSuccess: ((String) -> Unit)? = null,
    /** Informes de estado de pasos fallidos;null Dijo que no informara. */
    private val onStepFailure: ((String, Throwable) -> Unit)? = null,
    /** Registro de operaciones de tubería;null Indica deshabilitar la observación.(prueba/Sin entorno filesDir). */
    private val pipelineLog: PipelineLog? = null,
    /** reloj,Facilita la inyección de prueba. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** Modelo de informes de salud. */
    enum class HealthMode {
        /** éxito/Las fallas se reportan al sistema de salud establecido. */
        AUTO,

        /** No informar,a discreción de la persona que llama(Pasos con semántica especial para cambios.). */
        NONE,
    }

    /** Resultados de una sola ejecución. */
    sealed interface StepResult<out T> {
        data class Success<T>(val value: T, val durationMs: Long) : StepResult<T>
        data class Failure(val error: Throwable, val kind: FailureKind, val durationMs: Long) : StepResult<Nothing>
    }

    private val counters = ConcurrentHashMap<String, StepCounters>()

    /** Leer el recuento acumulado de un paso.(Devuelve cero si no está presente). */
    fun countersOf(stepKey: String): StepCounters = counters[stepKey] ?: StepCounters()

    /** Instantánea de todos los recuentos de pasos. */
    fun snapshotCounters(): Map<String, StepCounters> = counters.toMap()

    /**
     * D3-P1: grabar directamente"éxito"(No incluido block).
     *
     * Para uso de personas que llaman a estructuras try/catch existentes——La persona que llama mantiene su propio flujo de control detallado,
     * Tomar prestado únicamente el recuento acumulado y los registros de canalización del corredor;Los informes de salud los maneja la propia persona que llama.
     */
    fun recordSuccess(
        stepKey: String,
        durationMs: Long,
        trigger: String,
        scope: String? = null,
        spaceId: String? = null,
        detail: String? = null,
    ) {
        val c = bump(stepKey, success = true)
        runCatching {
            pipelineLog?.appendStep(
                stage = MemoryStage.ofStep(stepKey), step = stepKey, trigger = trigger, ok = true,
                durationMs = durationMs, failureKind = null, error = null,
                scope = scope, spaceId = spaceId, detail = detail, counters = c,
            )
        }
    }

    /**
     * D3-P1: grabar directamente"falló"(No incluido block);Registros solo de clasificación de fallas,El flujo de control no cambia.
     */
    fun recordFailure(
        stepKey: String,
        error: Throwable,
        durationMs: Long,
        trigger: String,
        scope: String? = null,
        spaceId: String? = null,
    ) {
        val c = bump(stepKey, success = false)
        val kind = FailureClassifier.classify(error)
        runCatching {
            pipelineLog?.appendStep(
                stage = MemoryStage.ofStep(stepKey), step = stepKey, trigger = trigger, ok = false,
                durationMs = durationMs, failureKind = kind, error = error,
                scope = scope, spaceId = spaceId, detail = null, counters = c,
            )
        }
    }

    /**
     * Corre un paso.
     *
     * @param stepKey clave de paso(Alineado con MemoryTicker.STEP_KEYS)
     * @param stage etapa(Sólo etiqueta)
     * @param trigger fuente de disparo
     * @param scope alcance de la memoria(Disponible)
     * @param spaceId espacio de memoria(Disponible)
     * @param health Modo de informe de salud(Predeterminado AUTO)
     * @param detailOf Instrucciones adicionales derivadas de resultados exitosos.(Como RollingSummary's changed/unchanged)
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun <T> run(
        stepKey: String,
        stage: MemoryStage,
        trigger: String,
        scope: String? = null,
        spaceId: String? = null,
        health: HealthMode = HealthMode.AUTO,
        detailOf: (T) -> String? = { null },
        block: suspend () -> T,
    ): StepResult<T> {
        val start = clock()
        return try {
            val value = block()
            val duration = clock() - start
            val c = bump(stepKey, success = true)
            if (health == HealthMode.AUTO) runCatching { onStepSuccess?.invoke(stepKey) }
            val detail = runCatching { detailOf(value) }.getOrNull()
            runCatching {
                pipelineLog?.appendStep(
                    stage = stage, step = stepKey, trigger = trigger, ok = true, durationMs = duration,
                    failureKind = null, error = null, scope = scope, spaceId = spaceId,
                    detail = detail, counters = c,
                )
            }
            StepResult.Success(value, duration)
        } catch (e: CancellationException) {
            // La cancelación de rutina es el estado final.,Vuelva a lanzar como está(No registrado como error de paso)
            throw e
        } catch (e: Throwable) {
            val duration = clock() - start
            val kind = FailureClassifier.classify(e)
            val c = bump(stepKey, success = false)
            if (health == HealthMode.AUTO) runCatching { onStepFailure?.invoke(stepKey, e) }
            runCatching {
                pipelineLog?.appendStep(
                    stage = stage, step = stepKey, trigger = trigger, ok = false, durationMs = duration,
                    failureKind = kind, error = e, scope = scope, spaceId = spaceId,
                    detail = null, counters = c,
                )
            }
            StepResult.Failure(e, kind, duration)
        }
    }

    private fun bump(stepKey: String, success: Boolean): StepCounters = counters.compute(stepKey) { _, prev ->
        val base = prev ?: StepCounters()
        base.copy(
            total = base.total + 1,
            successes = base.successes + if (success) 1 else 0,
            failures = base.failures + if (success) 0 else 1,
        )
    } ?: StepCounters()
}

/** Recuento acumulativo de un solo paso. */
data class StepCounters(
    val total: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
)
