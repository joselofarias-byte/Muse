// v2.3.1: Este archivo es el contexto del lado del modelo./Texto informativo(Redacción sin interfaz de usuario),Migrado desde ui/chat session/,
// Rango de escaneo con check_hardcoded_cjk(ui/ La redacción de textos publicitarios)Sea consistente.
package io.zer0.muse.session

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.TokenEstimator

/**
 * v2.x Importar calentamiento:Política de contexto de primera ronda para sesiones de restauración de copia de seguridad/importación externa.
 *
 * motivación:Las sesiones de importación suelen contener un largo historial.,Y el contexto predeterminado presiona"Número de artículos"Cultivo
 * (Assistant.contextMessageSize / limitContextWithContext,Predeterminado 20 artículos)——Casi toda su larga historia ha sido
 * cortar,La modelo casi sufrió amnesia en la primera ronda. La estrategia de calentamiento toma la primera ronda de"Número de tiras a cortar"Reemplazar con"token Adaptación del presupuesto":
 *
 * 1. Prioridad de cantidad total —— Traiga tanta historia como sea posible dentro de su presupuesto,Al exceder, comenzar desde el más antiguo.(Mantenga al menos el último);
 * 2. solo maquillarse una vez —— Marcado por SessionEntity.warmupPending,Borrado después de una primera ronda de respuesta exitosa
 *    (ver SessionRepository.clearWarmupPending / ChatGenerationController.finalizeResponse);
 * 3. protegerse contra"Reclamar" —— Viene con información del sistema.,Tenga en cuenta que el historial se importa desde fuera y que otros modelos generan las respuestas del asistente.,
 *    Solicitud para responder solo a los últimos mensajes del usuario(ver [briefingMessage]);
 * 4. Las imágenes se miden por el texto. —— Las estimaciones presupuestarias se reutilizan directamente [TokenEstimator] Calibre(Incluye aprox. por imagen 1000 token),
 *    No se realizará ningún juicio visual.
 */
object WarmupHistory {
    /**
     * token Presupuesto como proporción de la ventana de contexto del modelo.
     *
     * Sólo se utiliza como una estimación aproximada cuando "no se pueden conocer las indicaciones del sistema ni el uso de la definición de herramientas".
     * Cuando la persona que llama puede proporcionar estos dos elementos, se debe cambiar a [compressTokenBudgetFor] parámetros completos.
     */
    const val BUDGET_RATIO = 0.6

    /** contextMaxTokens Un presupuesto para tiempos desconocidos(token). */
    const val FALLBACK_BUDGET_TOKENS = io.zer0.muse.transformer.ContextBudget.DEFAULT_FALLBACK_BUDGET

    /** Trunca el resultado.[truncated]=true Indica que se ha descartado la parte más antigua de la historia. */
    data class TrimResult(val history: List<UIMessage>, val truncated: Boolean)

    /** Calcule el presupuesto histórico del token según la ventana de contexto del modelo (sin deducción por ocupación conocida, solo una estimación aproximada). */
    fun budgetTokensFor(contextMaxTokens: Int): Int = if (contextMaxTokens > 0) {
        (contextMaxTokens * BUDGET_RATIO).toInt()
    } else {
        FALLBACK_BUDGET_TOKENS
    }

    /**
     * El presupuesto del token que desencadena la compresión del contexto.
     *
     * En lugar de dar palmaditas en la cabeza en proporción, aquí primero deducimos la ocupación conocida: indicaciones del sistema, definiciones de herramientas, reservas de salida y márgenes de seguridad.
     * Ninguno de estos tres elementos es constante.——Una herramienta más agregará cientos de tokens y el sistema indica que las funciones aumentarán a medida que aumente la función.
     * El ratio fijo les es completamente indiferente, por lo que el mismo fragmento de historial puede exceder una ventana y no exceder la otra en diferentes sesiones.
     *
     * @param contextMaxTokens Ventana de contexto del modelo;<=0 Es hora de hacer una inmersión profunda.
     * @param systemTokens El sistema avisa que está ocupado; si no se puede dar, pasará 0.
     * @param toolTokens Ocupación de la definición de herramientas; si no está disponible, pase 0.
     * @param maxOutputTokens Modelo de producción máxima;<=0 El cálculo se basa en la reserva mínima de producción.
     */
    fun compressTokenBudgetFor(
        contextMaxTokens: Int,
        systemTokens: Int = 0,
        toolTokens: Int = 0,
        maxOutputTokens: Int = 0,
    ): Int = io.zer0.muse.transformer.ContextBudget.compute(
        contextWindow = contextMaxTokens,
        systemTokens = systemTokens,
        toolTokens = toolTokens,
        maxOutputTokens = maxOutputTokens,
        fallbackBudget = FALLBACK_BUDGET_TOKENS,
    ).historyBudget

    /** Compatible con personas mayores/key nombre;El valor de retorno ya es token,No más presupuestos para personajes. */
    @Deprecated("Use compressTokenBudgetFor; the legacy name is retained for persisted context keys.")
    fun compressCharBudgetFor(contextMaxTokens: Int): Int = compressTokenBudgetFor(contextMaxTokens)

    /**
     * Acumulado del más reciente al más antiguo token,Si excede el presupuesto, será truncado.
     *
     * Conserva al menos el último(incluso si individualmente excede el presupuesto,Evite la historia vacía);
     * [estimate] Inyectable,Conveniente para pruebas individuales(Ir por defecto [TokenEstimator],Incluye medición de aproximadamente 1000 tokens por imagen.).
     */
    fun trimToBudget(
        history: List<UIMessage>,
        budgetTokens: Int,
        estimate: (UIMessage) -> Int = { TokenEstimator.estimate(listOf(it)) },
    ): TrimResult {
        if (history.isEmpty()) return TrimResult(history, false)
        var accumulated = 0
        var cutIndex = history.size
        for (i in history.indices.reversed()) {
            val tokens = estimate(history[i]).coerceAtLeast(1)
            if (accumulated > 0 && accumulated + tokens > budgetTokens) break
            accumulated += tokens
            cutIndex = i
        }
        return if (cutIndex <= 0) {
            TrimResult(history, false)
        } else {
            TrimResult(history.subList(cutIndex, history.size).toList(), true)
        }
    }

    /** Sesión informativa del sistema de primera ronda:Historial de importación antimodelo"Reclamar"para tu propia expresión. */
    fun briefingMessage(truncated: Boolean): UIMessage = UIMessage(
        role = MessageRole.SYSTEM,
        content = buildString {
            append("[Descripción del historial de importación] Los primeros mensajes de esta conversación se importaron desde una plataforma externa,Donde las respuestas del asistente son generadas por otros modelos de IA.")
            append("Responda directamente a los últimos mensajes de los usuarios.,No resuma, vuelva a contar ni vuelva a responder mensajes antiguos de la historia.,")
            append("Tampoco tomes como tuyas las respuestas del asistente en el historial.")
            if (truncated) append("(Historia más larga,La parte más reciente se ha retenido aquí por presupuesto contextual.)")
        },
    )
}
