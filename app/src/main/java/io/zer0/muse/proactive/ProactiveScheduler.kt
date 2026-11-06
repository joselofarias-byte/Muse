package io.zer0.muse.proactive

import io.zer0.common.Logger
import io.zer0.muse.data.proactive.OutcomeState
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Programador de mensajes proactivo (reconstruido).
 *
 * con el viejo [io.zer0.muse.schedule.ProactiveMessageRunner] La diferencia fundamental es**El reloj maestro ha sido cambiado.**:
 * Las encuestas de implementación anteriores se basaban en "X minutos desde la última vez" y la nueva implementación se basa en "¿Hay algo en la cuenta que haya llegado al punto?".
 * No hay ningún tick fijo en el proceso. Después de cada ronda, puedes determinar cuándo mirarla la próxima vez y luego retrasarla hasta ese punto.
 *
 * Esta clase sólo es responsable del ciclo de vida y la entrada externa; ¿Cómo ejecutar la primera ronda específicamente? [ProactiveCycleRunner] adentro.
 */
class ProactiveScheduler(
    private val stores: ProactiveStores,
    private val cycle: ProactiveCycleRunner,
    private val appScope: CoroutineScope,
) {

    companion object {
        private const val TAG = "ProactiveSched"

        /** El intervalo de revisión cuando hay algún problema con la cuenta. */
        private const val CHECK_WITH_WORK_MS = 20 * 60 * 1000L

        /** El intervalo de revisión cuando no hay nada en la cuenta. Es más tranquilo y de todos modos no tengo nada que decir. */
        private const val CHECK_IDLE_MS = 60 * 60 * 1000L

        /**
         * El intervalo de reintento cuando esta ronda está ocupada por otras fuentes de activación.
         *
         * Sólo se utiliza para "compensar esta ronda" y no forma parte del ritmo de programación: simplemente espere hasta que termine la ronda anterior y luego vuelva a verla.
         * En ese momento, volverá a calcular cuánto tiempo debe dormir según el libro mayor.
         */
        private const val CHECK_RETRY_MS = 5 * 60 * 1000L

        const val TRIGGER_STARTUP = "startup"
        const val TRIGGER_TIMER = "timer"
        const val TRIGGER_WORKER = "worker"
        const val TRIGGER_RESUME = "resume"
        const val TRIGGER_MANUAL = "manual"
    }

    private var job: Job? = null

    /** La conclusión de la última inspección se puede proporcionar para obtener comentarios honestos mediante el botón \"Prueba\" en la página de configuración. */
    @Volatile
    var lastOutcome: String? = null
        private set

    /**
     * ¿Ya hay una ronda de inspecciones en marcha?
     *
     * Expuesto al mundo exterior, el Trabajador hace una predicción barata: cuando el sistema lo despierte, si descubre que el proceso
     * Si solo hay una ronda en ejecución, no es necesario analizar las dependencias y leer las configuraciones nuevamente. verdadera exclusión mutua
     * todavía en [ProactiveCycleRunner.execute] adentro —— Esto simplemente ahorra un despertar innecesario.
     */
    val isCycleInFlight: Boolean get() = cycle.isInFlight

    // ══════════════════════════════════════════════════════════════
    // ciclo de vida
    // ══════════════════════════════════════════════════════════════

    /**
     * Inicie el sondeo en proceso.
     *
     * No es un tick fijo: al final de cada ronda, la próxima vez se calcula en función del estado del libro mayor.
     * Si tienes algo que ver con tu cuenta, revísala cada 20 minutos; Si no tienes nada que hacer, revísalo una vez cada hora. —— Es inútil mirarlo cuando de todos modos no pasa nada.
     *
     * La excepción no se descarta: se trata de un bucle en segundo plano y, al ejecutarlo, se finalizará todo el trabajo de forma silenciosa.
     * Y nadie tomará la iniciativa de informar este tipo de falla como "ya no se envían mensajes activos".
     */
    fun start() {
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            Logger.i(TAG, "La programación activa de mensajes ha comenzado.")
            while (isActive) {
                // Utilice runCycle en lugar de Cycle.execute: las cuatro fuentes de activación deben caer en la misma entrada.
                // De lo contrario, el carril bici no podrá obtener el último resultado y también será fácil pasarlo por alto durante el próximo cambio.
                val outcome = runCatching { runCycle(TRIGGER_TIMER, force = false) }
                    .onFailure { e -> Logger.w(TAG, "Anormalidad de inspección: ${e.message}") }
                    .getOrNull()
                // Esta ronda estuvo ocupada por otras fuentes de activación y no se pudo ejecutar, por lo que se desconoce el estado del libro mayor. ——
                // Si presionas "Nada" y duermes una hora, perderás una oportunidad en vano. Tendrás que esperar un poco para volver a verlo.
                delay(
                    when {
                        outcome == null -> CHECK_IDLE_MS
                        outcome.alreadyRunning -> CHECK_RETRY_MS
                        outcome.ledgerNotEmpty -> CHECK_WITH_WORK_MS
                        else -> CHECK_IDLE_MS
                    },
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        Logger.i(TAG, "El envío de mensajes activos se ha detenido")
    }

    // ══════════════════════════════════════════════════════════════
    // entrada exterior
    // ══════════════════════════════════════════════════════════════

    /** Corre una ronda (Worker / Regrese a la página de configuración/recepción para realizar la prueba (vaya aquí). */
    suspend fun runCycle(trigger: String, force: Boolean = false): ProactiveCycleRunner.Outcome {
        val outcome = cycle.execute(trigger, force)
        lastOutcome = outcome.detail
        return outcome
    }

    /**
     * Se llama una vez cuando el usuario envía activamente un mensaje.
     *
     * Haga dos cosas: verifique si se trata de una respuesta al mensaje no solicitado más reciente (escriba RESPUESTA),
     * Y el monto de la compensación (vino a explicarme que la relación se está estrechando).
     *
     * Anormalidad tragada durante todo el proceso: se trata de un desvío en la ruta de envío del chat. Registre un error de retroalimentación.
     * No debería influir en los usuarios para que envíen sus palabras.
     */
    suspend fun onUserSpoke(sessionId: String) {
        runCatching {
            val now = System.currentTimeMillis()
            val pending = stores.outcomes.findPendingInSession(sessionId)
            if (pending != null) {
                stores.outcomes.resolve(pending.id, OutcomeState.REPLIED, now)
                Logger.i(TAG, "El usuario respondió al mensaje proactivo y se actualizó el nivel de intimidad.")
            }
        }.onFailure { e -> Logger.w(TAG, "Falló la grabación de comentarios: ${e.message}") }
    }

    /**
     * El usuario ha abierto una conversación: el mensaje activo más reciente de la conversación que aún no haya sido respondido se registrará como leído.
     *
     * Solo mira la más reciente y no regreses a escanear el historial: la acción del usuario de abrir una sesión solo puede significar “vio esta”.
     * Y marcar los anteriores como ABIERTOS equivale a crear de la nada un montón de comentarios positivos "en los que se hace clic".
     *
     * El portal de notificaciones, el pequeño teléfono celular y el chat principal convergen en la ruta de cambio de sesión.
     * Por lo tanto, el pulsador de llamada sólo necesita ser descolgado allí una vez.
     */
    suspend fun onSessionOpened(sessionId: String) {
        runCatching {
            val pending = stores.outcomes.findPendingInSession(sessionId) ?: return@runCatching
            stores.outcomes.resolve(pending.id, OutcomeState.OPENED, System.currentTimeMillis())
        }.onFailure { e -> Logger.w(TAG, "No se pudo registrar la lectura del mensaje activo: ${e.message}") }
    }

    /**
     * El usuario hizo clic en "Esta vez no" en la notificación.
     *
     * A diferencia de "tachar la notificación", esta es una afirmación clara: esto no es lo que quiere ver.
     * Sólo afecta a este registro (DISMISSED), sin cambiar la configuración ni pausar rondas posteriores.
     */
    suspend fun onProactiveDismissed(outcomeId: String) {
        runCatching {
            stores.outcomes.resolve(outcomeId, OutcomeState.DISMISSED, System.currentTimeMillis())
            Logger.i(TAG, "Los usuarios marcan los mensajes proactivos como \"no utilizados esta vez\"")
        }.onFailure { e -> Logger.w(TAG, "No se pudo grabar \"No se usó esta vez\": ${e.message}") }
    }

    /**
     * El usuario dijo "No me molestes últimamente".
     *
     * La primera pregunta de las cuatro preguntas durante el período de reflexión se intercepta directamente, pero el libro mayor se acumula como de costumbre. ——
     * Todavía se recuerdan cosas, pero no se habla de ellas.
     */
    suspend fun startCooldown(assistantId: String, days: Int = 7): String {
        stores.relations.startCooldown(assistantId, System.currentTimeMillis(), days)
        Logger.i(TAG, "El usuario solicita refrigeración $days dia")
        return "Bien, esto $days Dios, no tomaré la iniciativa de encontrarte. Llámame en cualquier momento si necesitas algo."
    }
}
