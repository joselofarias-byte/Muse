package io.zer0.muse.transformer

/**
 * Asignación del presupuesto contextual.
 *
 * No todas las ventanas de un modelo están dedicadas al historial de conversaciones. Primero se deben deducir al menos cuatro yuanes:
 *
 *  - **Aviso del sistema**:persona, índice de habilidades, conexión entre sesiones, etc., a medida que aumentan las funciones, puede alcanzar fácilmente decenas de miles de tokens;
 *  - **Definición de herramienta**: Cada solicitud debe traer un esquema completo. Cuantas más herramientas haya, más aterrador será esto;
 *  - **Reserva de salida**: El modelo debe poder terminar de escribir esta respuesta. Si no es suficiente, se truncará;
 *  - **margen de seguridad**: El estimador es una aproximación (cl100k La codificación es sólo una aproximación a otros modelos),
 *    Si no dejas ningún margen verás "no se supera, pero sí se supera".
 *
 * La antigua implementación daba al presupuesto histórico una proporción fija de la ventana, tratando estos cuatro bloques como constantes. Avisos del sistema y definiciones de herramientas
 * son exactamente los dos términos que son menos constantes —— A medida que aumenta el número de herramientas, el mismo fragmento de historial puede superar una ventana u otra en dos sesiones.
 * No se excede, y la proporción fija no tiene sentido en esto.
 */
internal object ContextBudget {

    /** Límite inferior del margen de seguridad (token). */
    private const val MIN_SAFETY_TOKENS = 1_024

    /** Margen de seguridad como proporción de la ventana. */
    private const val SAFETY_RATIO = 0.05

    /** Límite superior de reserva de salida: no importa cuán grande sea la ventana, no es necesario escupir tanto a la vez. */
    private const val MAX_OUTPUT_RESERVE = 16_384

    /** Límite inferior de reserva de salida: por debajo de este número, las respuestas se truncarán significativamente. */
    private const val MIN_OUTPUT_RESERVE = 4_096

    /** El presupuesto histórico es la proporción mínima de la ventana. */
    private const val MIN_HISTORY_RATIO = 0.2

    /**
     * Resultados del desglose del presupuesto.
     *
     * La cantidad intermedia se retiene para explicar "por qué esta ronda de presión/Sin presión"——
     * Si solo proporciona un número final, no habrá forma de revisarlo en línea si lo considera incorrecto.
     */
    data class Breakdown(
        val contextWindow: Int,
        val systemTokens: Int,
        val toolTokens: Int,
        val outputReserve: Int,
        val safetyTokens: Int,
        val historyBudget: Int,
    )

    /**
     * Calcule el presupuesto histórico disponible por ventana y ocupación conocida.
     *
     * @param contextWindow Ventana de contexto del modelo (token). Pase desconocido 0, vaya directo al fondo.
     * @param systemTokens El sistema avisa que está ocupado; si se desconoce, pase 0.
     * @param toolTokens Ocupación de la definición de herramientas; pase 0 si se desconoce.
     * @param maxOutputTokens Modelo de producción máxima; presione cuando sea desconocido o 0 [MIN_OUTPUT_RESERVE].
     * @param fallbackBudget Presupuestar cuando se desconoce la ventana.
     */
    fun compute(
        contextWindow: Int,
        systemTokens: Int = 0,
        toolTokens: Int = 0,
        maxOutputTokens: Int = 0,
        fallbackBudget: Int = DEFAULT_FALLBACK_BUDGET,
    ): Breakdown {
        if (contextWindow <= 0) {
            return Breakdown(
                contextWindow = 0,
                systemTokens = systemTokens,
                toolTokens = toolTokens,
                outputReserve = 0,
                safetyTokens = 0,
                historyBudget = fallbackBudget,
            )
        }
        val outputReserve = outputReserveFor(maxOutputTokens)
        val safety = maxOf(MIN_SAFETY_TOKENS, (contextWindow * SAFETY_RATIO).toInt())
        val raw = contextWindow - systemTokens - toolTokens - outputReserve - safety
        // Protección de límite inferior: en una sesión con una cantidad particularmente grande de definiciones de herramientas, las deducciones estrictas llevarán el presupuesto histórico a 0.
        // Entonces, cada ronda desencadena compresión, compresión o sobreimpulso. Es mejor mantener la proporción en el fondo que caer en un ciclo sin fin.
        val floor = (contextWindow * MIN_HISTORY_RATIO).toInt()
        return Breakdown(
            contextWindow = contextWindow,
            systemTokens = systemTokens,
            toolTokens = toolTokens,
            outputReserve = outputReserve,
            safetyTokens = safety,
            historyBudget = raw.coerceAtLeast(floor),
        )
    }

    /** Reserva de salida: siga el límite superior de la salida del modelo, pero manténgase dentro de un rango razonable. */
    fun outputReserveFor(maxOutputTokens: Int): Int = when {
        maxOutputTokens <= 0 -> MIN_OUTPUT_RESERVE
        maxOutputTokens < MIN_OUTPUT_RESERVE -> MIN_OUTPUT_RESERVE
        maxOutputTokens > MAX_OUTPUT_RESERVE -> MAX_OUTPUT_RESERVE
        else -> maxOutputTokens
    }

    /** Un presupuesto de bolsillo cuando se desconoce la ventana (token). */
    const val DEFAULT_FALLBACK_BUDGET = 12_000
}
