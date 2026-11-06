package io.zer0.muse.crash

/**
 * ANR Determinar la lógica central —— cálculo puro,no depende de Looper/Handler,Conveniente para pruebas individuales.
 *
 * Puntos de diseño(2026-09-29 revisión):
 *  - **ticket método de conteo**:El perro guardián entrega uno al hilo principal en cada ronda. ping,ping Aumente el recuento en 1 cuando se ejecute.
 *    La base para el juicio es"¿Avanza el conteo?",en lugar de"¿Cuánto tiempo ha pasado desde la última respuesta?" —— Este último está en
 *    El hilo de vigilancia está suspendido.(congelación del proceso/Profundidad Doze)O cuando muera de hambre, ese tiempo se calculará mal como el bloqueo del hilo principal.
 *  - **detección de congelación**:Si el tiempo real de esta ronda es mucho más largo que el intervalo esperado,(overshoot exceder [suspendToleranceMs]),
 *    Demuestra que el propio perro guardián no se despertó a tiempo,El hilo principal no se puede determinar durante este tiempo. —— Restablecer directamente la línea base,No reportar ANR.
 *    esto es exactamente 2026-09-27~29 cinco porciones anr_*.txt Causas de los falsos positivos:App Backstage está congelado por el sistema. → Regrese a la recepción para descongelar,
 *    La primera ronda de inspección registra toda la duración del backend como"El hilo principal no responde"(más largo 133s),La pila recopilada solo restaura el trabajo normal cuando se restaura el primer plano.
 *  - **Eliminar duplicados**:El mismo bloqueo solo se informa una vez,El hilo principal se vuelve a armar después de restaurar los latidos del corazón.
 *
 * @param timeoutMs Umbral de decisión:Si el hilo principal continúa sin responder durante más de este tiempo, será juzgado. ANR
 * @param expectedIntervalMs Intervalo de ronda esperado del organismo guardián(entrega ping → espera → comprobar)
 * @param suspendToleranceMs overshoot Tolerancia:El consumo de tiempo real superó con creces el intervalo esperado,Es decir, el proceso se considera suspendido.
 */
internal class AnrDetector(
    private val timeoutMs: Long,
    private val expectedIntervalMs: Long,
    private val suspendToleranceMs: Long,
) {
    /** Conclusión de la prueba de una sola ronda. */
    sealed interface Decision {
        /** juicio ANR(Primer informe de bloqueo en este segmento);[silenceMs] Es el tiempo de bloqueo acumulado. */
        data class Blocked(val silenceMs: Long) : Decision

        /** Todavía bloqueando,Pero este párrafo ya ha sido reportado.(Eliminar duplicados). */
        object Suppressed : Decision

        /** El latido del corazón del hilo principal es normal. */
        object Healthy : Decision

        /** El propio organismo de control está suspendido(congelación del proceso/Profundidad Doze):No se emitirá ningún juicio en esta ronda.,La línea base se ha restablecido. */
        object Suspended : Decision
    }

    /** Último recuento de ping observado(-1 = Aún no se ha establecido una línea de base). */
    private var lastPongCount: Long = -1L

    /** La hora de inicio de este bloqueo.(elapsedRealtime);null = Actualmente sin bloqueo. */
    private var blockedSinceMs: Long? = null

    /** Si se ha informado del bloqueo en este segmento. */
    private var reported: Boolean = false

    /**
     * Procese una ronda de pruebas.
     *
     * @param nowMs Consulta el tiempo de esta ronda.(SystemClock.elapsedRealtime)
     * @param postedAtMs La hora de entrega del ping en esta ronda.(Lo mismo ocurre con el reloj)
     * @param pongCount Número de pings realizados por el hilo principal(monótonamente creciente)
     */
    fun onCheck(nowMs: Long, postedAtMs: Long, pongCount: Long): Decision {
        val overshootMs = nowMs - postedAtMs - expectedIntervalMs
        val suspended = overshootMs > suspendToleranceMs
        val alive = pongCount > lastPongCount

        if (suspended || alive) {
            // Se suspende el organismo de control,O el hilo principal tiene un latido → Reducción a cero de la línea de base(El tiempo durante el período de congelación no cuenta para el bloqueo.)
            lastPongCount = pongCount
            blockedSinceMs = null
            reported = false
        } else if (blockedSinceMs == null) {
            // Confirmar bloqueo de entrada:de"El tiempo de entrega del ping que no se ejecutó esta vez."A partir de
            blockedSinceMs = postedAtMs
        }

        val silenceMs = blockedSinceMs?.let { nowMs - it } ?: 0L
        val decision =
            when {
                suspended -> Decision.Suspended
                alive -> Decision.Healthy
                silenceMs >= timeoutMs && !reported -> {
                    reported = true
                    Decision.Blocked(silenceMs)
                }
                silenceMs >= timeoutMs -> Decision.Suppressed
                else -> Decision.Healthy
            }
        return decision
    }
}
