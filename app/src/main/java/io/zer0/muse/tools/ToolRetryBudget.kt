package io.zer0.muse.tools

/**
 * Copia de descripción del presupuesto de reintento de la herramienta.
 *
 * ## por qué es necesario
 *
 * Ítem 15 de autoevaluación del asistente: "No se visualiza el presupuesto de reintento en caso de falla. Soy muy conservador después de una falla.
 * De hecho, es seguro que algunos de ellos vuelvan a intentarlo”.
 *
 * Situación actual: la palabra indicadora solo dice "La falla solo debe corregirse una vez según el mensaje de error". El modelo no sabe cuántas veces ha fallado.
 * Todavía quedan algunas posibilidades. Por lo tanto, tienden a darse por vencidos después del primer fallo e informar de los problemas solucionables directamente a los usuarios.
 *
 * Aquí, "Cuántas veces / Cuántas veces quedan / ¿Debemos continuar" se cuenta como una línea clara, junto con la clasificación de fallas?
 * Adjunto al recibo. El límite superior del presupuesto es consistente con el "máximo 1 vez" en la palabra clave y no existe un conjunto de criterios separado.
 */
object ToolRetryBudget {

    /**
     * El límite superior de reintentos para una sola herramienta en esta ronda.
     *
     * Es coherente con la palabra clave "El error solo debe corregirse una vez según el mensaje de error": después del primer error, puede volver a intentarlo.
     * Es decir, la misma herramienta falla como máximo. [MAX_RETRIES] + 1 veces.
     */
    const val MAX_RETRIES = 1

    /**
     * Generar estado de cuenta presupuestario.
     *
     * @param toolName Nombre de la herramienta (indique al modelo para qué herramienta es este presupuesto)
     * @param attempt Este es el número de fallas (comenzando desde 1)
     * @param retryable Reintentación dada por la clasificación de fallas.
     */
    fun describe(toolName: String, attempt: Int, retryable: Boolean): String {
        val used = attempt.coerceAtLeast(1)
        val remaining = (MAX_RETRIES - (used - 1)).coerceAtLeast(0)
        return when {
            !retryable ->
                "[Reintentar presupuesto] $toolName No. $used Esto falló; este error no se puede volver a intentar (consulte el tipo anterior) y no vuelva a llamar a la misma herramienta."
            remaining > 0 ->
                "[Reintentar presupuesto] $toolName No. $used falló; restante $remaining reintentar oportunidades —— " +
                    "Asegúrate de poder corregirlo y vuelve a intentarlo. No repitas el mismo fracaso."
            else ->
                "[Reintentar presupuesto] $toolName No. $used fallido; oportunidades de reintento agotadas —— " +
                    "No vuelvas a llamar a la misma herramienta, cuéntale al usuario el verdadero motivo y lo que has probado."
        }
    }

    /**
     * v2.6.7 fix (AUDIT F13): Combine los dos calibres de "presupuesto de esta herramienta" y "detención anticipada después de fallos consecutivos a lo largo de la ronda" en la misma frase.
     *
     * La implementación anterior solo analizaba el recuento de herramientas individuales: los modelos se probaron secuencialmente web_search → web_fetch → read_file Cuando cada uno falla una vez,
     * Cada vez que el recibo dice "Queda 1 oportunidad para volver a intentarlo", y `ToolExecutionPolicy` El umbral de falla continua de
     * (maxConsecutiveFailures = 3) ya ha condenado a muerte el ciclo —— Las promesas presupuestarias son inconsistentes con las decisiones reales.
     *
     * @param consecutiveFailures Número de fallas consecutivas de herramientas cruzadas en esta ronda hasta la fecha
     * @param abortThreshold Umbral de parada anticipada
     */
    fun describeWithRoundBudget(
        toolName: String,
        attempt: Int,
        retryable: Boolean,
        consecutiveFailures: Int,
        abortThreshold: Int,
    ): String {
        val perTool = describe(toolName, attempt, retryable)
        if (abortThreshold <= 0) return perTool
        val left = (abortThreshold - consecutiveFailures).coerceAtLeast(0)
        val roundLine = if (left <= 0) {
            "[presupuesto redondo] Esta ronda ha fracasado continuamente. $consecutiveFailures veces (límite superior $abortThreshold), no se ejecutarán más herramientas."
        } else {
            "[presupuesto redondo] Esta ronda ha fracasado continuamente. $consecutiveFailures/$abortThreshold veces, falló de nuevo $left Esto terminará la ronda."
        }
        return "$perTool\n$roundLine"
    }
}
