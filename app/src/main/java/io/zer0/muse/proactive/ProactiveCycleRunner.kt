package io.zer0.muse.proactive

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.ProcessWriteGate
import io.zer0.muse.data.ProactiveMessageConfig
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.ProactiveRelationEntity
import io.zer0.muse.data.proactive.ProactiveTimingEngine
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.schedule.GenerationGate
import io.zer0.muse.schedule.readScheduleWorkEnabledOrFalse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ejecute una ronda de inspección proactiva de mensajes.
 *
 * La forma es "preparación". → juicio → generar → Liberar", cada uno de los cuatro pasos se convierte en una función:
 *
 * ```
 * prepare  Control Maestro/Interruptor/Asistente/Relación/Limpieza/Contabilidad   —— Todo local, costo cero
 * decide   Juicio de cuatro preguntas (ver ProactiveTimingEngine)
 * produce  Una convocatoria de LLM, que permite skip
 * publish  Cayó en la biblioteca → marca → Monto de la deducción → registrar el resultado → Notificación
 * ```
 *
 * Sólo producir cuesta dinero. Por tanto, el coste de una inspección cuando "el libro mayor está vacío" es cercano a cero. ——
 * Esta es la mayor diferencia con la implementación anterior: la implementación anterior podría desencadenar dos llamadas de LLM por encuesta.
 */
class ProactiveCycleRunner(
    private val stores: ProactiveStores,
    private val runtime: ProactiveRuntime,
    private val loader: ProactiveContextLoader,
    private val composer: ProactiveComposer,
    private val guard: ProactiveCycleGuard,
    /**
     * Envíe la fuente aleatoria de la puerta de probabilidad y regrese `0..99`.
     *
     * El valor predeterminado es verdaderamente aleatorio, y la inyección es para hacer que la "probabilidad" sea mensurable: si a la prueba se le da un valor fijo, se puede determinar de manera determinista.
     * ve a enviar/En lugar de enviar dos ramas, no es necesario realizar una afirmación estadísticamente falsa.
     */
    private val rollPercent: () -> Int = { kotlin.random.Random.nextInt(ProactiveSendProbability.ROLL_BOUND) },
) {

    companion object {
        private const val TAG = "ProactiveCycle"

        /** El tiempo de espera máximo para llamadas de generación. */
        private const val COMPOSE_TIMEOUT_MS = 60_000L
    }

    /** La conclusión de una ronda de inspecciones. */
    data class Outcome(
        val verdict: ProactiveTimingEngine.Verdict?,
        val sent: Boolean,
        /** Si hay asuntos sin procesar en el libro mayor determina el intervalo para la siguiente revisión. */
        val ledgerNotEmpty: Boolean,
        val detail: String,
        /**
         * Si este activador se omitirá directamente porque "ya hay una ronda en ejecución".
         *
         * Separe campos en lugar de permitir que la persona que llama compare [detail]: Lo que se mostrará en la interfaz es redacción publicitaria localizada.
         * y [detail] Es una frase china para el tronco. Los dos no deberían compartir una cuerda.
         */
        val alreadyRunning: Boolean = false,
    )

    /** ¿Se está realizando ya una ronda de inspecciones en este momento? Proporciona a los programadores y trabajadores predicciones económicas. */
    val isInFlight: Boolean get() = guard.isInFlight

    /** producto de la fase de preparación. Si cualquiera de ellos es nulo, la ronda no debería continuar. */
    private data class Prepared(
        val config: ProactiveMessageConfig,
        val assistant: AssistantEntity,
        val sessionId: String,
        val context: ProactiveContextLoader.SessionContext,
        val decision: ProactiveTimingEngine.Decision,
        /**
         * estado civil. El nivel de iniciativa y la cuota están en él, lo que determina el calibre de la apertura durante la fase de generación.
         *
         * No puedo simplemente pasar [decision]: El resultado del juicio es sólo "si se puede enviar", pero "qué se puede decir si se puede enviar"
         * Es otra cuestión, determinada por el nivel seleccionado por el usuario.
         */
        val relation: ProactiveRelationEntity,
    )

    /**
     * Realiza una ronda.
     *
     * Todas las fuentes de activación deben ingresar desde aquí —— Las puertas volantes individuales se encuentran en este piso, no en sus respectivos puntos de llamada.
     * Ponerlo en el punto de llamada significa que cada vez que agrega un disparador, debe recordar emitir un juicio y el que se perdió.
     * Se agregó más tarde. Para cuando alguien descubriera el envío repetido, sería difícil rastrear quién había pasado por alto la puerta.
     *
     * @param force Prueba manual: omita las restricciones de tiempo del interruptor principal y cuatro preguntas, pero**No cruces el libro mayor** ——
     *              Sin preocupación, no se generará contenido y la publicación forzada solo generará palabras vacías.
     */
    suspend fun execute(trigger: String, force: Boolean): Outcome {
        val entry = guard.tryEnter(System.currentTimeMillis())
            ?: return skipped(trigger)
        var sent = false
        return try {
            run(trigger, force, entry).also { sent = it.sent }
        } finally {
            guard.leave(entry, sent)
        }
    }

    /** Adónde va esta ronda cuando ya hay una ronda en ejecución. Mantenga un registro explicando por qué no hizo nada en el camino. */
    private fun skipped(trigger: String): Outcome {
        val detail = "La última ronda de inspecciones aún está en curso ($trigger), salta esta vez"
        Logger.i(TAG, detail)
        return Outcome(
            verdict = null,
            sent = false,
            ledgerNotEmpty = false,
            detail = detail,
            alreadyRunning = true,
        )
    }

    private suspend fun run(trigger: String, force: Boolean, entry: ProactiveCycleGuard.Entry): Outcome =
        when (val prepared = prepare(trigger, force, entry)) {
            is Preparation.Blocked -> prepared.outcome
            is Preparation.Ready -> runPrepared(trigger, prepared.value, force)
        }

    /** Después de obtener los materiales preparados, decida si hablará o no. */
    private suspend fun runPrepared(trigger: String, ready: Prepared, force: Boolean): Outcome {
        val decision = ready.decision
        if (!decision.shouldSend) {
            val detail = ProactiveTimingEngine.describe(decision)
            Logger.i(
                TAG,
                "La inspección no se envía ($trigger): $detail | verdict=${decision.verdict} | " +
                    "interval=${decision.effectiveIntervalMinutes}min | ${skipContext(ready)}",
            )
            return Outcome(decision.verdict, false, hasOpenThoughts(ready), detail)
        }
        // Probabilidad de envío: tira los dados después de haber pasado todas las puertas difíciles. colocado antes de la generación,
        // La ronda que fue abandonada probabilísticamente no debería costar otra llamada modelo.
        // Pruebas manuales (force) No vayas aquí: es una herramienta de resolución de problemas, no una rutina diaria.
        if (!force && !ProactiveSendProbability.allows(ready.config.sendProbability, rollPercent())) {
            val detail = "Saltar según la probabilidad de envío (${ready.config.sendProbability}%)"
            Logger.i(TAG, "La inspección no se envía ($trigger): $detail | verdict=${decision.verdict}")
            return Outcome(decision.verdict, false, hasOpenThoughts(ready), detail)
        }
        return publishResult(trigger, decision, ready)
    }

    /**
     * Omita un sitio estructurado durante una inspección.
     *
     * Simplemente decir "el modelo cree que no hay nada que recoger" no puede explicar el problema. —— No se puede ver lo que es.
     * "Acabo de publicarlo, así que no tengo nada que decir" o "Nadie prestó atención al último post que envié y la modelo seguía hablando al aire".
     * Por lo tanto, anote los dos hechos externos que tenga a mano cuando el modelo emita un juicio: ¿cuánto tiempo ha pasado desde el último mensaje activo?
     * ¿Dónde leyó el usuario? Ambos están alineados con el último estado del lado de la sesión y no participan en ninguna determinación.
     */
    private suspend fun skipContext(ready: Prepared): String {
        val now = System.currentTimeMillis()
        val lastOutcome = runCatching { stores.outcomes.getRecent(ready.assistant.id, 1).firstOrNull() }
            .getOrNull()
        val minutes = lastOutcome?.let { (now - it.sentAt).coerceAtLeast(0L) / 60_000L }
            ?.toString() ?: "Ninguno"
        val readState = runCatching { runtime.sessions.getSessionById(ready.sessionId) }
            .getOrNull()
            ?.let { session ->
                val unread = (session.messageCount - session.lastReadCount).coerceAtLeast(0)
                "unread=$unread,lastRead=${session.lastReadCount}/${session.messageCount}"
            } ?: "Error de lectura"
        val outcomeState = lastOutcome?.state ?: "Ninguno"
        return "Desde el último mensaje activo=${minutes}minutos | Estado de lectura del usuario=$readState | Último mensaje activo=$outcomeState"
    }

    /** ¿Queda algo sin decir en el libro mayor? Solo afecta el intervalo de la próxima revisión y ya no es un requisito previo para poder decirlo. */
    private suspend fun hasOpenThoughts(ready: Prepared): Boolean =
        stores.thoughts.getOpen(ready.assistant.id).isNotEmpty()

    private suspend fun publishResult(
        trigger: String,
        decision: ProactiveTimingEngine.Decision,
        ready: Prepared,
    ): Outcome = when (val produced = produce(ready)) {
        is ProactiveReplyParser.Result.Failed -> {
            Logger.w(TAG, "Error de compilación: ${produced.reason}")
            Outcome(decision.verdict, false, hasOpenThoughts(ready), "Error de compilación:${produced.reason}")
        }
        is ProactiveReplyParser.Result.Skip -> {
            // Este es el criterio del propio modelo. Ve la conversación y no puede ver si se ha leído el mensaje anterior.
            // Al registrar los hechos externos juntos, puedes distinguir entre "nada que decir" y "nadie responderá a lo que dices".
            Logger.i(TAG, "Saltar juicio | reason=${produced.reason} | ${skipContext(ready)}")
            Outcome(
                decision.verdict,
                false,
                hasOpenThoughts(ready),
                "Parece que no hay nada que recoger en este momento (${produced.reason})",
            )
        }
        is ProactiveReplyParser.Result.Message -> {
            Logger.i(
                TAG,
                "Generación de candidatos de mensajes activos | assistantId=${ready.assistant.id} | len=${produced.content.length} | trigger=$trigger",
            )
            val ok = publish(trigger, ready, produced)
            Outcome(
                decision.verdict,
                ok,
                hasOpenThoughts(ready),
                if (ok) "Enviado" else "No se pudo iniciar sesión, inténtalo de nuevo la próxima vez.",
            )
        }

    // ══════════════════════════════════════════════════════════════
    // preparar
    // ══════════════════════════════════════════════════════════════

    private sealed interface Preparation {
        data class Ready(val value: Prepared) : Preparation
        data class Blocked(val outcome: Outcome) : Preparation
    }

    private suspend fun prepare(trigger: String, force: Boolean, entry: ProactiveCycleGuard.Entry): Preparation {
        val gate = gateBlock(force)
        return gate ?: assemble(trigger, entry)
    }

    /**
     * Las primeras tres puertas: Recuperando/El control maestro está cerrado/Los mensajes activos no están habilitados.
     *
     * Es una función separada porque estas tres son condiciones difíciles para "no bajar en absoluto".
     * La lógica de ensamblaje a continuación es "bajar pero es posible que no consigas los materiales". Los dos tipos de fallos se mezclan y no se pueden leer con claridad.
     */
    private suspend fun gateBlock(force: Boolean): Preparation? = when {
        ProcessWriteGate.restoring -> blocked("Copia de seguridad y restauración en curso, omitir")
        !readScheduleWorkEnabledOrFalse { runtime.settings.scheduleWorkEnabledFlow.first() } ->
            blocked("Se ha cerrado el control maestro de programación en segundo plano.")
        !force && !runtime.settings.proactiveMessageConfigFlow.first().enabled ->
            blocked("La mensajería activa no está habilitada")
        else -> null
    }

    /** Reúna los materiales necesarios para una ronda de inspección. Si no puede obtenerlo, se bajará a Bloqueado una vez. */
    private suspend fun assemble(trigger: String, entry: ProactiveCycleGuard.Entry): Preparation {
        val config = runtime.settings.proactiveMessageConfigFlow.first()
        val now = System.currentTimeMillis()

        // El asistente es el requisito previo para todos los pasos siguientes. Si no puede conseguirlo, no es necesario que siga reuniendo materiales.
        val assistant = loader.loadAssistant(config.agentId) ?: return blocked("No hay ayudante disponible")

        // Limpieza de rutina + derivación de nuevas inquietudes, todas las operaciones locales, sin costo.
        // Haga esto incluso cuando los mensajes activos estén desactivados: el libro de contabilidad se acumula como de costumbre y los usuarios tienen algo que decir cuando lo abren.
        stores.thoughts.maintenance(now)
        // Si a nadie le importa después de un día de enviarlo, terminará EXPIRADO en esta ronda. Póngalo en la inspección en lugar de iniciar un nuevo temporizador:
        // La inspección es el único trabajo local que se realiza periódicamente en este enlace. Un Trabajador más significa una tarea más.
        // Es necesario mantener "cuándo ejecutar y en qué proceso".
        val expired = stores.outcomes.expireOverdue(now)
        if (expired > 0) Logger.i(TAG, "Los mensajes activos caducan y finalizan | Número de artículos=$expired")
        loader.deriveThoughts(assistant.id, now)

        val relation = stores.relations.getOrCreate(assistant.id, now)
        val sessionId = loader.resolveSession(assistant)
        val context = sessionId?.let { loader.loadSessionContext(it, assistant.id) }
        val decision = context?.let {
            ProactiveTimingEngine.evaluate(
                ProactiveTimingEngine.Input(
                    now = now,
                    relation = relation,
                    allowedHourStart = config.allowedHourStart,
                    allowedHourEnd = config.allowedHourEnd,
                    allDayAllowed = config.allDayAllowed,
                    lastUserMessageAt = it.lastUserMessageAt,
                    isGenerating = runtime.generations.activeGenerations.value.isNotEmpty(),
                    lastProactiveSentAt = loader.lastSentAt(assistant.id),
                    // El tiempo de prueba de esta ronda ha sido registrado en la puerta, pero la decisión es preguntar cuándo se corrió la "última ronda":
                    // Si usa su propia marca de tiempo en esta ronda para juzgar, usted mismo lo detendrá en cada ronda.
                    lastProactiveAttemptAt = entry.previousAttemptAt,
                    // Tanto el intervalo como la fluctuación provienen de la configuración del usuario; la identificación del asistente participa en el hashing,
                    // No permita que los desplazamientos de diferentes asistentes se superpongan en la misma dirección.
                    pacing = ProactiveTimingEngine.Pacing(
                        intervalMinutes = config.intervalMinutes,
                        randomOffsetMinutes = config.randomOffsetMinutes,
                        assistantId = assistant.id,
                    ),
                    dailyGuaranteeMs = config.dailyGuaranteeHours.coerceIn(0, 72) * 3_600_000L,
                ),
            ).also { d ->
                Logger.d(
                    TAG,
                    "juicio[$trigger]: ${d.verdict} interval=${d.effectiveIntervalMinutes}min " +
                        "guarantee=${d.dailyGuarantee} budget=${d.remainingBudget}",
                )
            }
        }

        return when {
            sessionId == null && trigger == ProactiveScheduler.TRIGGER_MANUAL &&
                !config.agentSessionEnabled && !config.allowPlainChatSession ->
                blocked("没有可用落点:Agent 会话与普通对话都关着")
            sessionId == null || context == null || decision == null -> blocked("没有可用会话")
            else -> Preparation.Ready(
                Prepared(
                    config = config,
                    assistant = assistant,
                    sessionId = sessionId,
                    context = context,
                    decision = decision,
                    relation = relation,
                ),
            )
        }
    }

    private fun blocked(detail: String, verdict: ProactiveTimingEngine.Verdict? = null, ledgerNotEmpty: Boolean = false): Preparation =
        Preparation.Blocked(Outcome(verdict, false, ledgerNotEmpty, detail))

    // ══════════════════════════════════════════════════════════════
    // generar
    // ══════════════════════════════════════════════════════════════

    private suspend fun produce(ready: Prepared): ProactiveReplyParser.Result {
        val intensity = ready.relation.intensityEnum()

        // Los candidatos se filtran por nivel activo en lugar de entregárselos todos al modelo: el archivo silencioso sólo permite "cosas que hay que decir",
        // De vez en cuando, se me recuerda que no se me permite compartir mis propios asuntos y nodos de relación. Enumere los tipos de palabras que no se deben decir en este engranaje como palabras clave.
        // Equivale a darle al modelo una mala opción. —— Puede usarse y luego el usuario descubre que "seleccioné silencio y sigo charlando".
        // Lo mismo se aplica a los tipos que los usuarios tachan repetidamente: la base del juicio es la cuenta de resultados y el umbral de muestra es [ProactiveLearning].
        val ledger = stores.thoughts.getOpen(ready.assistant.id)
        val banned = stores.outcomes.unpopularKinds(
            ready.assistant.id,
            ledger.mapTo(mutableSetOf()) { it.kindEnum() },
        )
        val candidates = ProactiveCandidateFilter.select(ledger, intensity, banned)
        if (banned.isNotEmpty()) {
            Logger.i(TAG, "Filtrado de candidatos | Tipos que ya no son interesantes=${banned.joinToString { it.name }} | izquierda=${candidates.size}")
        }

        // Modo silencioso: no hables sin un motivo específico. Incluso el modelo no está ajustado.
        // Esto no sólo ahorra una llamada, sino que también evita que envíe un saludo bajo la restricción de "sólo hablar de cosas".
        if (intensity.onlyWhenNeeded && candidates.isEmpty() && !ready.decision.dailyGuarantee) {
            return ProactiveReplyParser.Result.Skip("Archivo silencioso: no hay cosas específicas en el libro mayor")
        }

        val route = ProactiveModelPicker.pick(runtime.settings, ready.assistant)
        // Regístrese primero y luego ajuste el modelo: la cuenta de resultados no se escribirá hasta que el mensaje se introduzca en la base de datos, y esta llamada tardará hasta decenas de segundos.
        // Si no se registra, la inspección parecerá "no pasó nada" en la capa de datos durante este período.
        guard.markAttempt(System.currentTimeMillis())
        return withTimeoutOrNull(COMPOSE_TIMEOUT_MS) {
            GenerationGate.withPermit {
                composer.compose(
                    input = ProactiveComposer.Input(
                        assistant = ready.assistant,
                        candidates = candidates,
                        sessionId = ready.sessionId,
                        recentMessages = ready.context.recentMessages,
                        recentProactiveMessages = ready.context.recentProactiveTexts,
                        now = System.currentTimeMillis(),
                        intensity = intensity,
                    ),
                    model = route?.second,
                    providerConfig = route?.first,
                    temperature = ready.config.temperature,
                )
            }
        } ?: ProactiveReplyParser.Result.Failed("Generar tiempo de espera")
    }

    // ══════════════════════════════════════════════════════════════
    // publicar
    // ══════════════════════════════════════════════════════════════

    /**
     * Registro + contabilidad + notificación.
     *
     * El orden es importante:**Primero déjelo en el almacén y luego, si tiene éxito, se deducirá la cuota. La marca ha sido declarada.**.
     * Cuando falla la deserción, no pasa nada. Esta ronda se trata como si no hubiera sucedido. Inténtalo de nuevo la próxima vez. ——
     * Preferiría volver a intentarlo antes que perder el mensaje, y prefiero que me deduzcan el crédito pero no enviarlo.
     */
    private suspend fun publish(trigger: String, ready: Prepared, message: ProactiveReplyParser.Result.Message): Boolean {
        val now = System.currentTimeMillis()
        val written = runCatching {
            runtime.sessions.appendMessage(
                sessionId = ready.sessionId,
                message = UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = message.content,
                    createdAt = now,
                ),
            )
        }.onFailure { e -> Logger.e(TAG, "No se pudo iniciar sesión, ríndete esta vez: ${e.message}", e) }.getOrNull()

        if (written == null) {
            Logger.i(TAG, "Entrega de mensajes activos | sessionId=${ready.sessionId} | messageId=null(Error de escritura) | trigger=$trigger")
            return false
        }

        // El mensaje activo dice "la conversación más reciente del asistente" y es posible que el usuario esté viendo otra conversación.
        // Registre ambos ID al mismo tiempo para distinguir cuando el usuario dice "Hay un mensaje en el registro pero no lo vi"
        // "Habitación equivocada" y "Escrito correctamente pero la página actual no es esa habitación".
        val currentSessionId = runCatching { runtime.settings.getViewedSessionId() }.getOrNull()
        Logger.i(
            TAG,
            "Entrega de mensajes activos | sessionId=${ready.sessionId} | messageId=$written | " +
                "targetSessionId=${ready.sessionId} | currentSessionId=${currentSessionId ?: "Ninguno"}",
        )

        // Esta apertura puede surgir de una determinada preocupación o puede simplemente seguir la conversación.
        // Si es así, márquelo. En caso contrario, no afectará a la contabilidad. —— La preocupación es sólo un acompañamiento.
        val actual = message.thoughtId
            ?.let { id -> stores.thoughts.getOpen(ready.assistant.id).firstOrNull { it.id == id } }
        actual?.let { stores.thoughts.markSurfaced(it.id, now) }

        stores.relations.consume(ready.assistant.id)
        // El ID de la cuenta de resultados siempre debe aparecer en la notificación: el botón "Esta vez no" se basa en él para volver al mismo registro.
        val outcomeId = stores.outcomes.recordSent(
            thought = actual,
            sessionId = ready.sessionId,
            now = now,
            assistantId = ready.assistant.id,
            messageId = written,
        )

        notifyProactive(trigger, ready, message, currentSessionId, outcomeId)
        Logger.i(TAG, "Enviado: thought=${actual?.id ?: "Ninguno(responder la llamada)"} tone=${message.tone}")
        return true
    }

    /**
     * Envía una notificación y escribe "¿se ha enviado?" en el registro.
     *
     * La implementación anterior solo llama sin ningún recibo:[MuseNotificationManager.notifyProactiveMessage]
     * La excepción se traga internamente, por lo que "el mensaje se descarta pero no hay nada en la barra de notificaciones" y no hay rastro en el registro.
     * Dos cosas para agregar aquí: si la llamada en sí se lanza y si el nivel del sistema bloqueará esto (permisos/el canal está cerrado).
     */
    private suspend fun notifyProactive(
        trigger: String,
        ready: Prepared,
        message: ProactiveReplyParser.Result.Message,
        currentSessionId: String?,
        outcomeId: String,
    ) {
        val blocker: String? = runCatching { runtime.notifier.proactiveNotificationBlocker() }.getOrNull()
        val failure = runCatching {
            runtime.notifier.notifyProactiveMessage(
                assistant = ready.assistant,
                preview = message.content,
                target = MuseNotificationTarget.Session(ready.sessionId),
                outcomeId = outcomeId,
            )
        }.exceptionOrNull()
        val posted = failure == null && blocker == null
        val reason = when {
            failure != null -> failure.message ?: failure::class.java.simpleName
            blocker != null -> blocker
            else -> "Ninguno"
        }
        Logger.i(
            TAG,
            "Notificación de mensajes proactiva | posted=$posted | reason=$reason | targetSessionId=${ready.sessionId} | " +
                "currentSessionId=${currentSessionId ?: "Ninguno"} | trigger=$trigger",
        )
    }
}
