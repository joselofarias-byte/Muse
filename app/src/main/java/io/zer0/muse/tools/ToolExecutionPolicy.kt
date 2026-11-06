package io.zer0.muse.tools

/**
 * M3.2: Presupuesto de ejecución de herramientas unificadas/Detener la estrategia.
 *
 * Juzgue el presupuesto repartido por ToolOrchestrator(Límite de ronda, parada anticipada por fallos consecutivos, sin progreso
 * Detección de firma)Cerrado como objeto de estrategia inyectable y comprobable.;y complete las dimensiones del presupuesto que faltaban anteriormente.:
 * Número total de llamadas, tiempo total invertido, tamaño de salida única, huella digital de llamadas repetidas continuas.
 *
 * M3.1 Correspondencia de la máquina de estados:
 * ```
 * REQUEST(El modelo emite tool_call)
 *   -> APPROVAL(host.requestToolApproval,enlace existente)
 *   -> EXECUTE(beforeExecute Ejecutar después del lanzamiento;Si el presupuesto alcanza, el cortocircuito es STOP)
 *   -> PERSIST(persistToolRoundIncrementally,enlace existente)
 *   -> CONTINUE(Próxima ronda de renovación de LLM)/ STOP(cualquier impacto presupuestario,Motivo de terminación del transporte)
 * ```
 *
 * Seguridad del hilo:Usar secuencialmente dentro de un solo turno(Ciclo de herramienta como secuencia/paralelismo controlado),desbloqueado;
 * En escenarios de varios turnos, se crea una nueva instancia de estrategia para cada turno.
 */
@Suppress("TooManyFunctions")
class ToolExecutionPolicy(
    private val limits: ToolExecutionLimits = ToolExecutionLimits(),
    /** rondas máximas iniciales;con ToolOrchestrator.DEFAULT_MAX_TOOL_ROUNDS(10)Alineación. */
    initialMaxRounds: Int = 10,
) {
    /** Rondas máximas dinámicas(task_plan Ampliable tras generación). El tiempo de ejecución consiste en [updateMaxRounds] Mantenimiento. */
    var maxRounds: Int = initialMaxRounds
        private set

    /**
     * v2.6.7: El número de fallos acumulados por nombre de herramienta en esta ronda.
     *
     * Póngalo aquí en lugar de agregar nuevos parámetros de función.:executeSingleToolCall Ya 10 parámetros,
     * Agregar uno más creará una nueva deuda LongParameterList. El objeto de la estrategia es originalmente "el estado de ejecución de esta ronda".,
     * El número de fracasos, naturalmente, le pertenece.
     */
    private val toolFailureCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Registrar un error y devolver el número acumulado de veces(empezar desde 1). */
    fun recordToolFailure(toolName: String): Int = toolFailureCounts.merge(toolName, 1, Int::plus) ?: 1

    fun updateMaxRounds(newMax: Int) {
        maxRounds = newMax
    }

    /** M3.2: Motivo de la rescisión después de alcanzar el presupuesto(Error de redacción y uso del registro de diagnóstico). */
    enum class StopReason {
        /** Número máximo de rondas alcanzado(Semántica original de maxRounds,Línea inferior de defensa). */
        MAX_ROUNDS,

        /** El número acumulado de llamadas a herramientas en un solo turno excede el límite. */
        MAX_TOTAL_CALLS,

        /** El número de fallos consecutivos supera el límite.(Consistente con la parada anticipada existente). */
        CONSECUTIVE_FAILURES,

        /** turn El tiempo total invertido superó el límite. */
        TIME_BUDGET_EXHAUSTED,

        /** mismo (Nombre de la herramienta+parámetros) Las llamadas repetidas continuas superan el límite(Llame a la huella digital de la tormenta). */
        REPEATED_IDENTICAL_CALL,

        /** P2-7: La salida acumulada de caracteres de una herramienta de un solo giro excede el límite.(Tormenta de reabastecimiento de resultados). */
        MAX_TOTAL_OUTPUT_CHARS,
    }

    /** Decisión de liberación de llamada única. */
    data class Decision(
        val allowed: Boolean,
        val reason: StopReason? = null,
        /** M3.3: Resumen de auditoría estructurado cuando se alcanza el presupuesto(Para registros de diagnóstico). */
        val detail: String = "",
    )

    private var totalCalls = 0
    private var consecutiveFailures = 0

    /** turn marca de tiempo de inicio(La prueba se construye en relación con esto. nowMs). */
    val startedAtMs = System.currentTimeMillis()
    private var lastFingerprint: String? = null
    private var lastFingerprintRepeatCount = 0

    /** Número acumulado actual de llamadas(Para observación). */
    val executedCalls: Int get() = totalCalls

    // Optional turn-level output guard; the default limit is effectively unlimited.
    private var outputCharsAccum = 0L

    /** El número acumulativo de caracteres generados por la herramienta del turno actual.(presupuesto/Para observación). */
    val totalOutputChars: Long get() = outputCharsAccum

    /** Accumulate the number of characters actually returned to the conversation. */
    fun recordOutputChars(chars: Int) {
        if (chars > 0) {
            outputCharsAccum =
                if (Long.MAX_VALUE - outputCharsAccum < chars) {
                    Long.MAX_VALUE
                } else {
                    outputCharsAccum + chars
                }
        }
    }

    // M3.3: turn estadísticas de resultados de nivel(no presupuestario,Para el valor de la instantánea ToolLoopResult). Grabado por ToolOrchestrator,
    // Evite que Orchestrator mantenga dos conjuntos de recuentos de variables totalToolCallCount/totalCharCount.
    private var emittedToolCalls = 0
    private var streamedChars = 0

    /** El modelo emite(Después de limpiar con Sanitizer)El número total de llamadas a herramientas. */
    val emittedToolCallCount: Int get() = emittedToolCalls

    /** Número acumulado de caracteres de salida en streaming(Respuesta final con rueda sin herramientas.). */
    val streamedCharCount: Int get() = streamedChars

    fun recordEmittedToolCalls(count: Int) {
        emittedToolCalls += count
    }

    fun recordStreamedChars(count: Int) {
        streamedChars += count
    }

    /** recuento de fallos consecutivos(Para que los usuarios vean la copia del motivo de la terminación). */
    val consecutiveFailuresCount: Int get() = consecutiveFailures

    /** v2.6.7 fix (AUDIT F13): Umbral de parada anticipada(Proporcionar reconocimiento de error“doble calibre”Dejalo claro). */
    val consecutiveFailureAbortThreshold: Int get() = limits.maxConsecutiveFailures

    /** Si los fallos consecutivos han alcanzado el umbral de parada anticipada(Alternativas repartidas por ToolOrchestrator shouldAbortToolLoop). */
    fun shouldAbortOnConsecutiveFailures(): Boolean = consecutiveFailures >= limits.maxConsecutiveFailures

    // Nivel redondo sin detección de progreso(sustituto ToolOrchestrator.noProgressRounds + previousToolCallSignature):
    // Cuando las rondas continuas de maxNoProgressRounds de LLM devuelven la misma firma de llamada de herramienta, se determina que está bloqueada.
    private var lastRoundSignature: String? = null
    private var noProgressRounds = 0

    /** Nivel de ronda sin recuento de progreso(Para que los usuarios vean la copia del motivo de la terminación). */
    val noProgressRoundsCount: Int get() = noProgressRounds

    fun checkRoundProgress(roundSignature: String): Decision {
        if (roundSignature.isEmpty()) {
            lastRoundSignature = ""
        }
        val isRepeat = roundSignature.isNotEmpty() && roundSignature == lastRoundSignature
        if (!isRepeat) {
            lastRoundSignature = roundSignature
        }
        noProgressRounds = if (isRepeat) noProgressRounds + 1 else 0
        return if (noProgressRounds >= limits.maxNoProgressRounds) {
            blocked(
                StopReason.REPEATED_IDENTICAL_CALL,
                "consecutive identical tool-call rounds=$noProgressRounds",
            )
        } else {
            Decision(allowed = true)
        }
    }

    /**
     * Libere la inspección antes de una sola llamada de herramienta. Si se afecta algún presupuesto, se rechazará la ejecución.
     * (La persona que llama devuelve el motivo del rechazo al modelo como un resultado de herramienta sintetizado.,No implementa herramientas reales.).
     *
     * @param toolName Nombre de la herramienta
     * @param argumentsJson Parámetros de herramienta texto original(Materias primas para huellas dactilares.,Sin análisis)
     */
    fun beforeExecute(toolName: String, argumentsJson: String): Decision {
        val fingerprint = fingerprint(toolName, argumentsJson)
        val repeatCount = if (fingerprint == lastFingerprint) lastFingerprintRepeatCount else 0
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        val budgetMs = limits.totalBudgetMs
        val violation =
            listOf(
                blocked(StopReason.MAX_TOTAL_CALLS, "totalCalls=$totalCalls max=${limits.maxTotalCalls}")
                    .takeIf { totalCalls >= limits.maxTotalCalls },
                blocked(StopReason.CONSECUTIVE_FAILURES, "consecutiveFailures=$consecutiveFailures")
                    .takeIf { consecutiveFailures >= limits.maxConsecutiveFailures },
                blocked(
                    StopReason.REPEATED_IDENTICAL_CALL,
                    // M3.3: Las huellas dactilares sólo se introducen en el registro,No repetir parámetros completos(Evite parámetros grandes/Actualización de parámetros sensibles)
                    "tool=$toolName fingerprint=$fingerprint repeats=$repeatCount",
                ).takeIf { repeatCount + 1 > limits.maxConsecutiveIdenticalCalls },
                blocked(StopReason.TIME_BUDGET_EXHAUSTED, "elapsedMs=$elapsedMs budget=$budgetMs")
                    .takeIf { budgetMs != null && elapsedMs > budgetMs },
            ).firstOrNull { it != null }
        return violation ?: Decision(allowed = true)
    }

    /** Una construcción conveniente para decisiones que afectan el presupuesto. */
    private fun blocked(reason: StopReason, detail: String): Decision = Decision(allowed = false, reason = reason, detail = detail)

    /**
     * Liquidación de llamadas:Recuentos de actualizaciones, combos fallidos y huellas dactilares duplicadas.
     *
     * @param toolName Nombre de la herramienta
     * @param argumentsJson Texto de parámetro(Consistente con beforeExecute)
     * @param success Si la herramienta se ejecutó exitosamente(Aprobación rechazada/La interceptación presupuestaria no es un fracaso.,No contado)
     */
    fun afterExecute(toolName: String, argumentsJson: String, success: Boolean) {
        totalCalls++
        if (success) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
        }
        val fingerprint = fingerprint(toolName, argumentsJson)
        if (fingerprint == lastFingerprint) {
            lastFingerprintRepeatCount++
        } else {
            lastFingerprint = fingerprint
            lastFingerprintRepeatCount = 1
        }
    }

    /**
     * turn Verificación del presupuesto que requiere mucho tiempo(Llamado al comienzo de cada ciclo.).
     * null Indica que no hay ningún presupuesto de tiempo configurado.(Desactivado de forma predeterminada,Compatible con tareas largas existentes).
     */
    fun isTimeBudgetExhausted(nowMs: Long = System.currentTimeMillis()): Boolean {
        val budget = limits.totalBudgetMs ?: return false
        return nowMs - startedAtMs > budget
    }

    /**
     * P2-7: Verificación del presupuesto restante al inicio de cada ciclo.(Todas las dimensiones de nivel de turno excepto rondas).
     *
     * con [beforeExecute](antes de una sola llamada)la diferencia:Este método está en"Antes de que se emita una nueva ronda de solicitudes de LLM"llamar,
     * Si algún artículo se agota, se dará por terminado anticipadamente.,Evite desperdiciar otro viaje de ida y vuelta al modelo que seguramente será interceptado.
     * El límite de ronda está a cargo de la condición while de ToolOrchestrator,No repetido dentro de este método.
     *
     * @param nowMs hora actual(Se pueden inyectar pruebas)
     * @return liberar,o llevar el motivo de terminación del primer golpe
     */
    fun checkRoundBudget(nowMs: Long = System.currentTimeMillis()): Decision {
        val elapsedMs = nowMs - startedAtMs
        val budgetMs = limits.totalBudgetMs
        return listOf(
            blocked(StopReason.MAX_TOTAL_CALLS, "totalCalls=$totalCalls max=${limits.maxTotalCalls}")
                .takeIf { totalCalls >= limits.maxTotalCalls },
            blocked(
                StopReason.MAX_TOTAL_OUTPUT_CHARS,
                "totalOutputChars=$outputCharsAccum max=${limits.maxTotalOutputChars}",
            ).takeIf { outputCharsAccum >= limits.maxTotalOutputChars },
            blocked(StopReason.CONSECUTIVE_FAILURES, "consecutiveFailures=$consecutiveFailures")
                .takeIf { consecutiveFailures >= limits.maxConsecutiveFailures },
            blocked(StopReason.TIME_BUDGET_EXHAUSTED, "elapsedMs=$elapsedMs budget=$budgetMs")
                .takeIf { budgetMs != null && elapsedMs > budgetMs },
        ).firstOrNull { it != null } ?: Decision(allowed = true)
    }

    /**
     * Optional output guard for callers that explicitly configure [ToolExecutionLimits.maxOutputChars].
     * The default limit is Int.MAX_VALUE; large results are normally file-backed before this point.
     *
     * @return Pair(delivered text, whether an explicitly configured limit truncated it)
     */
    fun clampOutput(output: String): Pair<String, Boolean> {
        if (output.length <= limits.maxOutputChars) return output to false
        val head = output.take(limits.maxOutputChars)
        return head + "\n\n[La salida está truncada: originales ${output.length} personaje, límite superior ${limits.maxOutputChars}]" to true
    }

    /**
     * Llamar repetidamente a la huella digital:Nombre de la herramienta + SHA-256 del texto original del parámetro.
     * El texto original del parámetro no se analiza. —— La estabilidad de las huellas dactilares está determinada por"El mismo modelo emite repetidamente la misma llamada"garantia,
     * Las diferencias en el orden de las claves se tratan como llamadas diferentes(conservador,Vuelva a intentarlo legalmente sin sufrir lesiones accidentales).
     */
    internal fun fingerprint(toolName: String, argumentsJson: String): String {
        val digest =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest((toolName + "\u0000" + argumentsJson).toByteArray())
        // Cada byte se trata como sin firmar.:%02x El formateo directo de Byte firmará y extenderá los bytes negativos a
        // "ffffffXX"(8 personaje),otra vez take(16) Después del truncamiento, la entropía de la huella digital colapsa a ~8bit,causa
        // Se juzgaron erróneamente diferentes llamadas de herramientas como"Repetir la misma llamada". primero toInt() and 0xff Vuelva a formatear.
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
    }
}

/**
 * M3.2: Ejecutar configuración de protección. Llamada retenida por valor predeterminado/Inténtalo de nuevo/Protección de tiempo, no limita el número de caracteres en el resultado.:
 * El límite de ronda todavía lo administra ComputeMaxRounds de ToolOrchestrator(Esta categoría no tiene límite de repetición en el número de rondas.),
 * llamadas totales/Las huellas dactilares duplicadas están restringidas,El presupuesto de tiempo está desactivado de forma predeterminada.
 */
data class ToolExecutionLimits(
    /**
     * El límite superior de llamadas de herramientas acumulativas en un solo turno.(incluyendo el éxito y el fracaso,No incluye rechazo de aprobación).
     * v2.x: Una vez que se levante el límite de rondas, este artículo se convertirá en una de las principales redes de seguridad.,El valor predeterminado aumenta de 60 a 300;
     * Verdadero bucle infinito causado por huellas dactilares repetidas/Sin progreso/Triple detección continua fallida e intercepción temprana.
     */
    val maxTotalCalls: Int = 300,
    /** Umbral de parada temprana por fallo continuo(con ToolOrchestrator.MAX_CONSECUTIVE_TOOL_FAILURES Alineación). */
    val maxConsecutiveFailures: Int = 3,
    /** mismo (Nombre de la herramienta+parámetros) Límite superior de llamadas repetidas continuas;Interceptado por tiempo N+1. */
    val maxConsecutiveIdenticalCalls: Int = 3,
    /** Sin límite de progreso para rondas:Si N rondas consecutivas de LLM devuelven la misma firma de llamada de herramienta, se determina que está atascada. */
    val maxNoProgressRounds: Int = 2,
    /** turn presupuesto de tiempo total(milisegundos);null Cerca. */
    val totalBudgetMs: Long? = null,
    /**
     * El límite de defensa final para un solo resultado. La ruta de chat principal primero completa la transmisión directa de acuerdo con el presupuesto del contexto o guarda la copia completa,
     * Los resultados de la herramienta ya no se recortan de forma predeterminada debido a una cantidad fija de caracteres.
     */
    val maxOutputChars: Int = Int.MAX_VALUE,
    /** Optional output circuit breaker. Unlimited by default; other loop guards remain active. */
    val maxTotalOutputChars: Long = Long.MAX_VALUE,
)
