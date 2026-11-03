package io.zer0.muse.tools

import io.zer0.ai.core.UsageTokens

/**
 * v1.0.53 Phase 3: Libro mayor de presupuesto de tokens de subagente.
 *
 * fuente de acumulación:[ChatService.completeText] regresó [io.zer0.ai.core.ChatCompletion.usageTokens].
 *  - usageTokens cuando es nulo(Provider No devuelto usage)Saltar acumulación,No juzgues mal el agotamiento
 *  - total = promptTokens + completionTokens(Incluido reasoning tokens,No agregue repetidamente)
 *
 * Escenarios de uso:
 *  - [SubagentRunner] Después de cada ronda de texto completo accumulate,Cuando se agota, el ciclo de la herramienta se fuerza a finalizar y el ciclo de la herramienta ingresa al resumen.
 *  - [SkillExecutor.delegateAgent] camino A(Delegación Subasistente)Acumular entre recursiones(Pasar instancia de presupuesto a través de ThreadLocal)
 *  - [WorkflowOrchestrator] Puntos de control de límite máximo a nivel de marco
 *
 * Seguridad del hilo:Esta clase no se sincroniza — Diseñado para uso secuencial dentro de una sola rutina.
 * El uso compartido entre rutinas requiere que la persona que llama se empaquete y sincronice por sí misma(Por ejemplo, las corrutinas en segundo plano sin bloqueo crean budget).
 *
 * @param limitTokens límite presupuestario(prompt + completion total).null No indica ninguna restricción(por método de fábrica [of] Proceso).
 */
class AgentTokenBudget private constructor(
    private val limitTokens: Int,
) {
    companion object {
        /** Presupuesto de token predeterminado del subagente(60k,Alinear documento 6.4 Predeterminado). */
        const val DEFAULT_SUBAGENT_BUDGET = 60_000

        /**
         * Cree una instancia de presupuesto.
         * @param limitTokens límite superior;null regresar cuando null(No indica ninguna restricción).
         */
        fun of(limitTokens: Int?): AgentTokenBudget? = limitTokens?.takeIf { it > 0 }?.let { AgentTokenBudget(it) }
    }

    private var spent = 0

    /** presupuesto restante(nunca menos que 0). */
    val remaining: Int get() = (limitTokens - spent).coerceAtLeast(0)

    /** ¿Se ha agotado el presupuesto? */
    val isExhausted: Boolean get() = spent >= limitTokens

    /** Número de tokens consumidos(para diagnóstico). */
    val spentTokens: Int get() = spent

    /**
     * Intenta retener una suma token(Se utiliza para comprobaciones de límite máximo antes del inicio del nodo.).
     * @return false Indica exceder el límite después de la retención,Rechazar esta llamada;true Indica que la retención fue exitosa.
     */
    fun trySpend(tokens: Int): Boolean {
        if (tokens <= 0) return true
        if (spent + tokens > limitTokens) return false
        spent += tokens
        return true
    }

    /**
     * de [ChatCompletion.usageTokens] Acumular consumo real.
     * - usage cuando es nulo(Provider No devuelto)Saltar acumulación,Solo devuelve si está agotado actualmente.
     *
     * @return true Indica que todavía queda algo después de la acumulación.;false Indica que se ha agotado tras la acumulación.(La persona que llama debe ingresar la ruta de resumen.).
     */
    fun accumulate(usage: UsageTokens?): Boolean {
        if (usage == null) return !isExhausted
        spent += usage.total
        return !isExhausted
    }
}
