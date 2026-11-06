package io.zer0.muse.tools

import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Orquestador de flujo de trabajo.
 *
 * Simplifique la orquestación:Paralelo/canalización/secuencial,Basado en corrutinas de Kotlin.
 */
class WorkflowOrchestrator {

    sealed class Step {
        data class Task(val name: String, val action: suspend () -> String) : Step()
        data class Parallel(val tasks: List<Step>) : Step()
        data class Pipeline(val steps: List<Step>) : Step()
    }

    data class Result(
        val outputs: Map<String, String>,
        val durationMs: Long,
        val errors: List<String> = emptyList(),
        /** v1.0.53 Phase 3: Si se deben omitir algunos nodos debido al presupuesto de token agotado. */
        val budgetExhausted: Boolean = false,
    )

    /**
     * Siga la lista de pasos programada.
     *
     * v1.0.53 Phase 3: Límite máximo del presupuesto de tokens de soporte.
     *  - [budgetTokens] Cuando no es nulo,Compruebe si el presupuesto está agotado antes de que se ejecute cada nodo,Si está agotado, omítalo y registre un error.
     *  - Nota:[Step.Task] La acción es una caja negra.,Este orquestador no puede acumular automáticamente el consumo de tokens.;
     *    Para un control preciso,La persona que llama debe capturar el exterior mediante un cierre dentro de la acción. [AgentTokenBudget] Instancias accumulate,
     *    luego pasa [budgetTokens] Pasar en el mismo límite superior.,Este método creará una instancia de presupuesto independiente para la verificación a nivel de marco.
     *
     * @param budgetTokens token límite presupuestario;null=Sin restricciones.
     */
    suspend fun execute(steps: List<Step>, budgetTokens: Int? = null): Result = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val outputs = mutableMapOf<String, String>()
        val errors = mutableListOf<String>()
        val budget = AgentTokenBudget.of(budgetTokens)
        var budgetExhausted = false
        for (step in steps) {
            // v1.0.53 Phase 3: Si se agota el presupuesto, se omitirán los nodos restantes.
            if (budget?.isExhausted == true) {
                budgetExhausted = true
                errors.add("Se omite el nodo:token presupuesto agotado(restante ${budget.remaining})")
                continue
            }
            executeStep(step, outputs, errors)
        }
        Result(
            outputs = outputs.toMap(),
            durationMs = System.currentTimeMillis() - startMs,
            errors = errors.toList(),
            budgetExhausted = budgetExhausted,
        )
    }

    private suspend fun executeStep(step: Step, outputs: MutableMap<String, String>, errors: MutableList<String>) {
        when (step) {
            is Step.Task -> {
                try {
                    outputs[step.name] = step.action()
                    Logger.d(TAG, "Task '${step.name}' completed")
                } catch (e: Exception) {
                    errors.add("Task '${step.name}' failed: ${e.message}")
                    Logger.w(TAG, "Task '${step.name}' failed", e)
                }
            }
            is Step.Parallel -> {
                coroutineScope {
                    step.tasks.map { child -> async { executeStep(child, outputs, errors) } }.awaitAll()
                }
            }
            is Step.Pipeline -> {
                for (child in step.steps) executeStep(child, outputs, errors)
            }
        }
    }

    class Builder {
        internal val steps = mutableListOf<Step>()
        fun task(name: String, action: suspend () -> String) {
            steps.add(Step.Task(name, action))
        }
        fun parallel(block: Builder.() -> Unit) {
            val inner = Builder()
            inner.block()
            steps.add(Step.Parallel(inner.steps.toList()))
        }
        fun pipeline(block: Builder.() -> Unit) {
            val inner = Builder()
            inner.block()
            steps.add(Step.Pipeline(inner.steps.toList()))
        }
    }

    companion object {
        private const val TAG = "WorkflowOrchestrator"
        fun build(block: Builder.() -> Unit): List<Step> = Builder().apply(block).steps.toList()
    }
}
