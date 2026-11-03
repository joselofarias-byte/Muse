package io.zer0.muse.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.schedule.AutomationConfig
import io.zer0.muse.data.schedule.AutomationConfig.toAction
import io.zer0.muse.data.schedule.AutomationConfig.toCondition
import io.zer0.muse.data.schedule.AutomationConfig.toIdsList
import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 17: Ejecutor de tareas programadas.
 *
 * Iniciar cuando se inicia la aplicación,Comprueba cada 60 segundos para ver si hay tareas programadas caducadas.
 * Las tareas caducadas se ejecutarán realmente prompt(Llamar a AI + sesión de escritura),notificación emergente(Haga clic para saltar a la tarea correspondiente).
 * Utilice encuestas de rutina en lugar de AlarmManager,Evite las restricciones de fondo de Android.
 *
 * P1-7: Insertar un registro de ejecución después de ejecutar la tarea.(success/failed),Para que la interfaz de usuario muestre el historial de ejecución.
 *
 * Implementar realmente la transformación(prensa [ProactiveMessageRunner]):
 *  - Analizar la configuración del asistente por task.assistantId,Utilice su systemPrompt como mensaje del sistema
 *  - llamar [ChatService.completeText](sin transmisión)ejecutar task.prompt
 *  - Escriba mensajes de usuario y respuestas de IA en una sesión dedicada(Por título task.name)
 *  - replySummary Guardar texto de respuesta de IA(la mayoría 10,000 palabra);En caso de error, errorMessage registra la excepción real.
 *  - El contenido de las notificaciones utiliza IA para responder al resumen
 *  - [executeTask] expuesto al mundo exterior,suministro UI"Ejecutar inmediatamente"Llamada de depuración del botón
 *
 * @param dao tareas programadas DAO(Contiene el método de combinación @Transaction)
 * @param chatService Ejecución de LLM de llamadas sin transmisión task.prompt
 * @param sessionRepository Escriba indicaciones de usuario y respuestas de IA a la sesión.
 * @param assistantRepository Obtenga la configuración del asistente mediante task.assistantId(systemPrompt / temperature / maxTokens)
 * @param context Solicitud Context
 * @param appScope App Alcance de la rutina global
 */
class ScheduledTaskRunner(
    private val dao: ScheduledTaskDao,
    private val chatService: ChatService,
    private val sessionRepository: SessionRepository,
    private val assistantRepository: AssistantRepository,
    private val context: Context,
    private val appScope: CoroutineScope,
    private val pendingMessageManager: io.zer0.muse.data.schedule.PendingMessageManager? = null,
    private val toolRegistry: ToolRegistry? = null,
    // v1.0.17: Usar en su lugar Room DAO(Reemplazo para el almacenamiento JSON QuickNoteStore)
    private val quickNoteDao: QuickNoteDao? = null,
) {
    private var job: Job? = null

    @Volatile
    private var scheduleWorkEnabledCache: Boolean? = null

    @Volatile
    private var lastStuckRecoveryAt: Long = 0L

    /**
     * Avance de punto fijo([computeNextRunAnchored])resultado.
     *
     * - [nextRunAt]: próximo tiempo de ejecución;<=0 Indica que no hay próxima ejecución(once / Cron ilegal / desconocido interval)
     * - [missedCount]: Esta vez no habrá carreras de recuperación, solo se registrará el número histórico de rondas planificadas perdidas.(0 = No te lo pierdas)
     */
    internal data class ScheduleAdvance(val nextRunAt: Long, val missedCount: Int)

    companion object {
        const val CHANNEL_ID = "scheduled_tasks"
        const val NOTIFICATION_ID_BASE = 2000
        private const val TAG = "ScheduledTask"

        /**
         * R-TEST-11: calcular [now] Próxima hora de ejecución después(función pura,Sin operación DB).
         *
         * La única implementación de intervalos de tareas programadas.:Herramientas para construir/Cambiar tareas,UI Aquí se ajustan la vista previa y la primera ejecución después de cambiar el intervalo.
         * (Una vez ejecutada la tarea, se avanza el cronograma. [computeNextRunAnchored]),No reescriba la tabla de intervalos en ningún otro lugar.,
         * Evite "el mismo intervalo calcula diferentes tiempos en diferentes entradas".
         *
         * - hourly / daily / weekly: now + intervalo fijo
         * - cron: usar [CronExpression] analizar;Expresión vacía o error de análisis → 0
         * - once / desconocido interval: 0
         *
         * Regresar <=0 Indica "no hay próxima ejecución",En lugar de "Ejecutar de nuevo ahora mismo":
         * [ScheduledTaskDao.recordExecutionAndScheduleNext] ver <=0 es decir, desactivar tareas,
         * La tarea única sale de la programación después de ejecutarse.,No será descontado para la segunda ejecución.
         * Al crear una tarea, pregunté sobre el "primer tiempo de ejecución".,eso es para [initialNextRunAt].
         *
         * Nota:Este es el calibre de "contar de ahora en adelante",Sólo apto para entradas que no tienen momentos de planificación histórica.(Crear tareas, cambiar intervalos,
         * UI Vista previa). Se utiliza para avanzar en la programación después de la ejecución de la tarea. [computeNextRunAnchored],Anclar el último momento planificado.
         */
        internal fun computeNextRun(interval: String, cronExpr: String, now: Long): Long {
            if (interval == "cron") return nextCronRunAfter(cronExpr, now)
            val period = fixedPeriodMillis(interval) ?: return 0L
            return now + period
        }

        /**
         * La duración del período del intervalo de período fijo.(milisegundos);cron / once / El intervalo desconocido devuelve nulo.
         *
         * Sólo hay una copia de la tabla de intervalos.:[computeNextRun] con [computeNextRunAnchored] compartir,
         * Evite "usar dos series de ciclos para el mismo intervalo en dos calibres".
         */
        private fun fixedPeriodMillis(interval: String): Long? = when (interval) {
            "hourly" -> 3_600_000L
            "daily" -> 86_400_000L
            "weekly" -> 604_800_000L
            else -> null
        }

        /**
         * cron próximo tiempo de ejecución(función pura). El valor de una expresión vacía/fallo de análisis/no coincide es el mismo que el calibre original.
         */
        private fun nextCronRunAfter(cronExpr: String, after: Long): Long {
            if (cronExpr.isBlank()) return 0L
            return resultOf { CronExpression.parse(cronExpr).nextRunAfter(after) }
                .onError { msg, t -> Logger.w(TAG, "Invalid cron expr: ${t?.message ?: msg}") }
                .getOrNull() ?: 0L
        }

        /**
         * Anclar el último momento planificado para adelantar la programación(función pura,Sin operación DB).
         *
         * ¿Por qué no podemos contar siempre desde ahora?:Las tareas suelen ejecutarse decenas de segundos u horas después de su vencimiento.(nerviosismo en las encuestas,Doze,
         * El dispositivo se apaga y el proceso finaliza.). Usar ahora como base impulsará la próxima vez a "ahora + un ciclo" cada vez,Me perdí eso
         * Una ronda se salta permanentemente sin dejar rastro,Y cada vez que me despierto vuelvo a flotar,El reloj de pared sigue yendo hacia atrás.
         *
         * Usar aquí en su lugar [scheduledAt](Última hora planificada,Es decir, antes de la ejecución. next_run_at) punto de anclaje:
         * La próxima vez = scheduledAt + Periodo entero múltiple,Todavía está en el "ritmo planificado original".
         * [missedCount] = Esta vez no hay un número planificado de rondas para las carreras de recuperación. = El número de momentos planificados que han transcurrido desde el punto de anclaje.
         *
         * estrategia de maquillaje:Solo compensa la última ejecución(Esta ejecución es ese momento.),No habrá carreras de recuperación en rondas anteriores, solo recuerda missed ——
         * Ejecute docenas de rondas de solicitudes de red/escriba archivos a la vez,Peor que perderse una carrera. porque [nextRunAt] Definitivamente > now,
         * Luego de esta ejecución, la tarea no será reclamada nuevamente dentro del mismo segundo.
         *
         * Calibre inferior:
         * - [scheduledAt] <= 0(Los datos antiguos no tienen ancla):devolver [computeNextRun] "a partir de ahora",no cuentes missed
         * - [scheduledAt] > now(La tarea aún no ha vencido,Un escenario típico es que el usuario haga clic en "Ejecutar ahora".,También podría ser la hora del sistema.
         *   llamado de vuelta):La próxima vez será el momento planeado.,Ni siquiera una vez —— No puedes ejecutarlo manualmente una vez más.
         *   A partir de ahora, se cambiará permanentemente a las 09:00 todos los días. 12:00
         * - once / Intervalo desconocido: no hay próxima ejecución(0),no cuentes missed,Consistente con la semántica original.
         * - El número de ciclos excede [MAX_MISSED_SCAN]:prensa [MAX_MISSED_SCAN] Recuerda el límite superior y reinicia desde ahora.,
         *   Evite relojes anormales(como ancla en 1970)Cuente tanto como desbordado o inactivo millones de veces
         */
        internal fun computeNextRunAnchored(
            interval: String,
            cronExpr: String,
            scheduledAt: Long,
            now: Long,
        ): ScheduleAdvance {
            val period = fixedPeriodMillis(interval)
            return when {
                period != null -> anchoredFixedPeriod(period, scheduledAt, now)
                interval == "cron" -> advanceCron(cronExpr, scheduledAt, now)
                else -> ScheduleAdvance(0L, 0)
            }
        }

        /** periodo fijo(hourly / daily / weekly)empuje de anclaje:La próxima vez = Punto de anclaje + múltiplo entero de período. */
        private fun anchoredFixedPeriod(period: Long, scheduledAt: Long, now: Long): ScheduleAdvance {
            if (scheduledAt <= 0L) return ScheduleAdvance(now + period, 0)
            // Aún no caducado(Manual "Ejecutar ahora"/ Reloj retrasado):El tiempo planeado nunca se movió ni una vez.
            if (scheduledAt > now) return ScheduleAdvance(scheduledAt, 0)
            val periods = (now - scheduledAt) / period
            return if (periods > MAX_MISSED_SCAN.toLong()) {
                ScheduleAdvance(now + period, MAX_MISSED_SCAN)
            } else {
                ScheduleAdvance(scheduledAt + (periods + 1) * period, periods.toInt())
            }
        }

        /**
         * cron empuje de anclaje:de [scheduledAt] Avanza un cuadrado a la vez,Cuente algunos momentos de planificación que se omitieron.
         *
         * cron La propia expresión ha anclado el reloj de pared.(Como por ejemplo "Todos los días a las 9:00"),Así que no hay ningún resultado obsoleto con
         * [computeNextRun] consistente;Lo que se hace adicionalmente aquí es contar "cuántos espacios se han saltado".
         */
        private fun advanceCron(cronExpr: String, scheduledAt: Long, now: Long): ScheduleAdvance {
            if (cronExpr.isBlank()) return ScheduleAdvance(0L, 0)
            val expr = resultOf { CronExpression.parse(cronExpr) }
                .onError { msg, t -> Logger.w(TAG, "Invalid cron expr: ${t?.message ?: msg}") }
                .getOrNull() ?: return ScheduleAdvance(0L, 0)
            if (scheduledAt <= 0L) return ScheduleAdvance(expr.nextRunAfter(now), 0)
            return when {
                // Aún no caducado(cron Ejecución manual inmediata de tareas.):Mantenga el siguiente cuadrado original
                scheduledAt > now -> ScheduleAdvance(scheduledAt, 0)
                else -> scanSkippedCronSlots(expr, scheduledAt, now)
            }
        }

        /** Avance desde el punto de anclaje hasta [now] después,Devuelve la siguiente hora planificada y el número de celdas omitidas en el medio. */
        private fun scanSkippedCronSlots(expr: CronExpression, scheduledAt: Long, now: Long): ScheduleAdvance {
            var cursor = scheduledAt
            var missed = 0
            while (missed < MAX_MISSED_SCAN) {
                val candidate = expr.nextRunAfter(cursor)
                // sin avanzar(Contiene NO_MATCH)o ha cruzado ahora: convergió a esta red
                if (candidate <= cursor || candidate > now) return ScheduleAdvance(candidate, missed)
                missed++
                cursor = candidate
            }
            return ScheduleAdvance(expr.nextRunAfter(now), MAX_MISSED_SCAN)
        }

        /**
         * Construya un registro de ejecución de "Me perdí estas rondas y no recuperé las carreras".
         *
         * Escribe solo uno en lugar de uno por ronda.:Una tarea de una hora generará más de 70 elementos si se cierra durante tres días.,Haz la historia de la ejecución real.
         * desvanecerse. El texto lo pasa la persona que llama.(La redacción está en strings.xml. schedule_missed_summary,con
         * success / failed / skipped consistente),Para el estado [STATUS_MISSED],No se agregan nuevas columnas de base de datos.
         *
         * @param now El momento de esta carrera de maquillaje.
         */
        internal fun missedExecutionEntity(
            taskId: String,
            replySummary: String,
            now: Long,
        ): ScheduledTaskExecutionEntity = ScheduledTaskExecutionEntity(
            id = "exec-${java.util.UUID.randomUUID()}",
            taskId = taskId,
            executedAt = now,
            status = STATUS_MISSED,
            replySummary = replySummary,
            errorMessage = "",
        )

        /**
         * Nueva tarea/primer tiempo de ejecución después de cambiar el intervalo(función pura).
         *
         * con [computeNextRun] Lo único que queda es "no la próxima vez".:[computeNextRun] Devolver 0 Sí
         * Etiqueta "Nunca más" desactivada([ScheduledTaskDao.recordExecutionAndScheduleNext] ver
         * <=0 solo setEnabled(false)),No puedo llevarlo al almacén.;Tareas de construcción/Al cambiar el intervalo, la tarea única aún no se ha ejecutado.,debe hacerse lo antes posible
         * correr una vez,Entonces está bien now + [POLL_INTERVAL_MS].
         *
         * ¿Por qué el resultado final es 60 segundos en lugar de ahora? La programación depende de [POLL_INTERVAL_MS] sondeo + getDueTasks(next_run_at <= now),
         * Completar ahora hará que la tarea se analice la próxima vez.(0–60s,Lo peor es el salto inmediatamente después.)ser recibido y ejecutado——El usuario hizo clic
         * Cuadro de diálogo de salida "Crear",En decenas de segundos, podrá ver que la tarea se ha completado y hay un historial de ejecución adicional. Deje un búfer para los ciclos de sondeo.
         * Movimientos de intervalo de clase de período [computeNextRun] valor positivo,No afectado por este encubrimiento.
         */
        internal fun initialNextRunAt(interval: String, cronExpr: String, now: Long): Long =
            computeNextRun(interval, cronExpr, now).takeIf { it > 0 } ?: (now + POLL_INTERVAL_MS)

        private const val POLL_INTERVAL_MS = 60_000L // revisa cada minuto

        /**
         * La mayor cantidad de mosaicos perdidos por el avance del ancla.
         *
         * extraño normalmente(Apagado durante horas o días)Lejos de llegar a este nivel;Cualquier cosa más allá sólo puede ser una excepción ancla.
         * (Si el reloj se pone en hora 1970),En este momento, cuente según el límite superior y reinicie desde ahora,Sin ralentí de un cuadro a otro.
         */
        private const val MAX_MISSED_SCAN = 1_000

        /** El valor de estado de \"Me perdí esta ronda y no recuperé la carrera\" en el historial de ejecución.(con success / failed / skipped yuxtaponer). */
        internal const val STATUS_MISSED = "missed"

        // Mostrar el texto después de expandir el historial de ejecución;Limite el límite superior para evitar anomalías en la salida de la herramienta que inflen la base de datos infinitamente.
        private const val EXECUTION_DETAIL_MAX_LEN = 10_000

        /**
         * B-12: Sentencia de cobro preventivo(función pura,Para pruebas unitarias llamar directamente).
         *
         * [dao.claimTask] Devuelve el número de filas afectadas.:1 = Este ejecutor recibió exitosamente;0 = Primero ha sido recogido por otros ejecutores.
         * Número de líneas > 0 Se determina que la colección es exitosa.;numero negativo/Los valores inesperados se manejan como fallas.(Saltar de forma conservadora,Evite la duplicación).
         */
        internal fun claimTaskSucceeded(rowsAffected: Int): Boolean = rowsAffected > 0

    /**
     * El tiempo de espera para la siguiente ronda de inspección (función pura, conveniente para pruebas únicas).
     *
     * - No hay tareas debidas → [POLL_INTERVAL_MS](Mantenga un latido del corazón de un latido por minuto)
     * - Hay tareas vencidas y han caducado → [MIN_POLL_DELAY_MS](No esté inactivo, pero tampoco llegue a 0)
     * - El tiempo de vencimiento está en [MIN_POLL_DELAY_MS] con [POLL_INTERVAL_MS] entre → Me quede dormido en ese momento
     * - La fecha de caducidad está más lejos. → [POLL_INTERVAL_MS](**Quédate despierto más tiempo**: Las tareas recientes recién creadas no se pueden arrastrar)
     */
    internal fun computePollDelay(
        now: Long,
        nextDueAt: Long?,
        pollInterval: Long = POLL_INTERVAL_MS,
        minDelay: Long = MIN_POLL_DELAY_MS,
    ): Long {
        if (nextDueAt == null) return pollInterval
        val untilDue = nextDueAt - now
        return when {
            untilDue <= 0L -> minDelay
            untilDue >= pollInterval -> pollInterval
            else -> untilDue.coerceAtLeast(minDelay)
        }
    }

        /** Tiempo de espera de llamada de IA de tarea única(milisegundos). */
        private const val LLM_TIMEOUT_MS = 60_000L

        /**
         * v2.6.8: Una tarea programada puede ejecutar hasta varias rondas de llamadas a herramientas a la vez.
         *
         * La tarea programada se ejecuta en segundo plano y nadie está mirando; 3 rondas son suficientes para cubrirlo.“comprobar notas → tiempo de pantalla → Resumen”Este tipo de requisito de cadena,
         * También puede evitar que el modelo caiga en un bucle infinito de herramientas y agote el crédito.
         */
        private const val MAX_TOOL_ROUNDS = 3

        /** v1.0.17: Profundidad máxima de recursividad de tareas encadenadas,Evite bucles infinitos. */
        private const val MAX_CHAIN_DEPTH = 10

        /** v1.0.17: Reintentar límite de retroceso(milisegundos,5 minutos). */
        private const val RETRY_BACKOFF_MAX_MS = 300_000L

        /** v1.0.17: Reintentar el tamaño del paso de retroceso(milisegundos,incrementos de 1 minuto). */
        private const val RETRY_BACKOFF_STEP_MS = 60_000L

        /**
         * B-12: El valor centinela Next_run_at avanzó temporalmente después de recibirlo(futuro lejano,≈146 miles de millones de años después).
         *
         * recibir(CAS)Cuando next_run_at se empuja temporalmente a este valor para eliminar"Caducidad"identidad,Cualquier escaneo de vencimiento durante la ejecución/
         * No hubo suerte en conseguirlo de nuevo.;Finaliza la ejecución [executeTask] Se sobrescribirá con el tiempo real de próxima ejecución.
         */
        private const val CLAIM_NEXT_RUN_SENTINEL = Long.MAX_VALUE

        /**
         * B-26: Umbral de vigilancia centinela —— claim Si aún se detiene en el valor centinela después de exceder este tiempo, se considerará como"atascado"(Después de recibir
         * recordExecutionAndScheduleNext Reversión de fallas de transacción),Permita que se restablezca next_run_at.
         * Una sola ejecución de IA tiene [LLM_TIMEOUT_MS](60s)límite superior,5 Los minutos superan con creces el tiempo empleado en un solo viaje.,No habrá daños accidentales a las tareas en ejecución.
         */
        private const val CLAIM_STUCK_TIMEOUT_MS = 5 * 60 * 1000L

    /** Intervalo mínimo de exploración del organismo guardián Sentinel; con [CLAIM_STUCK_TIMEOUT_MS] De la misma magnitud. */
    private const val STUCK_RECOVERY_INTERVAL_MS = CLAIM_STUCK_TIMEOUT_MS

    /** El límite inferior de la hibernación adaptativa: no permanezca inactivo cuando el tiempo de vencimiento haya pasado (o sea inminente). */
    private const val MIN_POLL_DELAY_MS = 1_000L
    }

    private fun restoreBlocksScheduledWrites(taskId: String, operation: String): Boolean {
        if (!io.zer0.common.ProcessWriteGate.restoring) return false
        Logger.w(TAG, "Copia de seguridad y restauración en curso, tarea programada omitida $taskId de${operation}escribir")
        return true
    }

    fun start() {
        createNotificationChannel()
        job?.cancel()
        job = appScope.launch(GlobalCoroutineExceptionHandler) {
            Logger.i(TAG, "Runner started")
            // El conmutador solo se suscribe una vez: anteriormente requería una búsqueda de GlobalContext + DataStore leía cada tick.
            launch { observeScheduleWorkEnabled() }
            while (isActive) {
                tickOnce()
                delay(nextPollDelay())
            }
        }
    }

    /**
     * Suscríbase al interruptor "Control maestro de programación en segundo plano" y escriba [scheduleWorkEnabledCache].
     *
     * El motivo para suscribirse solo una vez: el sondeo se realiza una vez por minuto y los cambios de interruptor son eventos de baja frecuencia —— Ve cada minuto DataStore
     * Leerlo una vez (y hacer una búsqueda de Koin primero) es una pérdida de dinero. La misma semántica se mantiene después de suscribirse: el interruptor se apaga,
     * Se omitirá la siguiente ronda de inspección.
     */
    private suspend fun observeScheduleWorkEnabled() {
        val settings = resultOf {
            org.koin.core.context.GlobalContext.get().get<io.zer0.muse.data.SettingsRepository>()
        }.getOrNull() ?: return
        resultOf { settings.scheduleWorkEnabledFlow.collect { enabled -> scheduleWorkEnabledCache = enabled } }
    }

    /**
     * El tiempo de espera para la siguiente ronda de inspección.
     *
     * Si hay un tiempo de caducidad claro, duerma hasta ese momento (límite inferior [MIN_POLL_DELAY_MS], el límite superior sigue siendo [POLL_INTERVAL_MS]).
     * **Solo se permiten menores de 60 años, ya no se permite**: Al crear una nueva tarea que se ejecutará en 5 segundos, el sondeo fijo de 60 segundos también esperará hasta 60 segundos.
     * Dormir más se convierte en una regresión conductual.
     */
    private suspend fun nextPollDelay(): Long {
        val now = System.currentTimeMillis()
        val nextDueAt = resultOf { dao.earliestNextRunAt(now, CLAIM_NEXT_RUN_SENTINEL) }.getOrNull()
        return computePollDelay(now = now, nextDueAt = nextDueAt)
    }

    /**
     * v1.104 P3: Escanee todas las tareas pendientes a la vez y ejecútelas.
     *
     * de [start] extracción del bucle de sondeo,suministro:
     *  - El sondeo de rutina se realiza cada minuto.(camino original)
     *  - [ScheduledTaskWorker] Se llama una vez cuando WorkManager inicia el proceso de la aplicación después de finalizarlo.
     *
     * Sin bucle delay,La persona que llama decide con qué frecuencia se le llama. Todas las excepciones se detectan y registran.,No será arrojado hacia arriba
     * (CancellationException excepción,Asegúrese de que la corrutina se pueda cancelar normalmente.).
     */
    private suspend fun tickOnce() {
        try {
            // B-25: Control maestro de programación backend — Saltarse toda la inspección al cerrar(Leer el valor de la caché del almacén de datos,bajo costo)
            if (!isScheduleWorkEnabled()) {
                Logger.i(TAG, "Se ha cerrado el control maestro de programación en segundo plano.,Saltar la inspección de tareas programadas")
                return
            }
            // B-7: Copia de seguridad y restauración(Reemplazo de nivel de archivo DB)Saltar inspección de tareas dentro de la ventana,Evite pérdidas de escritura.
            if (io.zer0.common.ProcessWriteGate.restoring) {
                Logger.i(TAG, "Restauración de copia de seguridad en curso,Saltar la inspección de tareas programadas")
                return
            }
            val now = System.currentTimeMillis()
            // B-26: perro guardián centinela — Después de reciclar y recolectar, la transacción falló y se quedó estancada en el valor centinela.(CLAIM_NEXT_RUN_SENTINEL)
            // tarea,Restablezca next_run_at que no se haya liberado después del tiempo de espera para now,Dejemos que esta ronda de getDueTasks vuelva a ponerlo en ejecución debida.
            // El propio dispositivo de vigilancia Sentinel tiene un umbral de tiempo de espera de 5 minutos y no es necesario escanear cada minuto: un ciclo cada 5 minutos es suficiente.
            try {
                if (now - lastStuckRecoveryAt >= STUCK_RECOVERY_INTERVAL_MS) {
                    lastStuckRecoveryAt = now
                    recoverStuckClaimNow(now)
                }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Logger.w(TAG, "Falló la recuperación del perro guardián Sentinel: ${e.message}")
            }
            val dueTasks = dao.getDueTasks(now)
            dueTasks.forEach { task ->
                resultOf { executeTask(task) }
                    .onError { msg, t -> Logger.w(TAG, "Task ${task.id} execute error: ${t?.message ?: msg}") }
            }
            // Phase 3 3E: Consultar y enviar mensajes programados
            pendingMessageManager?.drainDueMessages()?.forEach { pm ->
                resultOf { deliverPendingMessage(pm) }
                    .onError { msg, t -> Logger.w(TAG, "Pending message ${pm.id} delivery error: ${t?.message ?: msg}") }
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Poll error: ${e.message}")
        }
    }

    /**
     * B-25: Lea el interruptor de control maestro de programación en segundo plano.
     *
     * ScheduledTaskRunner No inyectado SettingsRepository(Mantenga estables las firmas del módulo Koin),aquí
     * Analizado por GlobalContext;Koin desaparecido/Devuelve falso de forma conservadora cuando la lectura falla para evitar que sea ilegible
     * Continuar ejecutando tareas programadas sin el conocimiento del usuario.
     */
    private suspend fun isScheduleWorkEnabled(): Boolean {
        // Si ya hay una instantánea en el proceso, úsela directamente (por [observeScheduleWorkEnabled] mantenimiento).
        // Worker levantar,collector Antes de comenzar a correr, accederá a la lectura en tiempo real a continuación.
        scheduleWorkEnabledCache?.let { return it }
        val settings = resultOf {
            org.koin.core.context.GlobalContext.get().get<io.zer0.muse.data.SettingsRepository>()
        }.getOrNull()
        return scheduleWorkEnabledFromRepository(
            available = settings != null,
            value = settings?.let {
                resultOf { it.scheduleWorkEnabledFlow.first() }.getOrNull()
            },
        )
    }

    /**
     * B-26: perro guardián centinela —— La liberación se atasca mientras recibe el centinela.(CLAIM_NEXT_RUN_SENTINEL)tareas atrasadas.
     *
     * [executeTask] Después de recibir si [dao.recordExecutionAndScheduleNext] Reversión de fallas de transacción,next_run_at
     * se detendrá en el valor centinela(futuro lejano),Cualquier escaneo de vencimiento posterior no alcanzará el objetivo. → La tarea se pierde permanentemente.
     * poner aquí"Se ha excedido el valor centinela y actualizado_at [CLAIM_STUCK_TIMEOUT_MS]"La tarea se restablece a now,
     * Reejecutado por esta ronda de votación.
     *
     * Evitar reinicios accidentales:Recibir el Sentinel es el [executeTask] La única fuente de eliminación de identidad caducada;Ejecución única de IA
     * si [LLM_TIMEOUT_MS](60s)límite superior,5 El umbral de minutos es mucho mayor que el tiempo necesario para una sola ejecución.,Sin lesiones accidentales
     * "ejecutando"tarea(Ejecutar update_at es muy nuevo,no se considerará caducado).
     * Restablecer solo las tareas que aún estén habilitadas,Evite resucitar tareas que el usuario haya desactivado manualmente.
     */
    private suspend fun recoverStuckClaimNow(now: Long) {
        val recovered = dao.recoverStuckClaim(
            sentinel = CLAIM_NEXT_RUN_SENTINEL,
            staleBefore = now - CLAIM_STUCK_TIMEOUT_MS,
            now = now,
        )
        if (recovered > 0) {
            Logger.w(TAG, "B-26 perro guardián centinela: reciclar $recovered misiones estancadas en el valor centinela,Restablecer a ahora reprogramado")
        }
    }

    /**
     * v1.104 P3: suministro [ScheduledTaskWorker] Se llamó una entrada de escaneo única.
     *
     * Worker Cuando el proceso es iniciado por WorkManager,Es posible que el canal de notificación aún no se haya creado(nuevo proceso),Entonces aquí hay una llamada idempotente una vez.
     * Luego delégalo en [tickOnce] Complete el escaneo real.
     */
    suspend fun tickOnceForWorker() {
        createNotificationChannel()
        tickOnce()
    }

    /**
     * v1.137: Ejecute una única tarea automatizada.
     *
     * proceso:
     *  0. Cobro preventivo CAS(ver B-12):Alineación atómica previa a la ejecución next_run_at(Contiene defensa centinela ABA),
     *     Si falla, ha sido cobrado./Eliminar,Saltar sin efectos secundarios.
     *  1. Evaluación condition(Disparador condicional)
     *  2. Ejecutar la acción correspondiente según action_type(ai_prompt / create_quick_note / call_tool / notify)
     *  3. Activar tareas encadenadas después de una ejecución exitosa(next_task_ids)
     *  4. Historial de ejecución de registros atómicos + avance del siguiente tiempo de ejecución
     *
     * v1.0.17: Nuevo [chainDepth] Parámetro utilizado para el límite de profundidad de recursividad de tareas en cadena;
     *  Reintentar con retroceso exponencial cuando falla la ejecución(retry_count < max_retries incremento de tiempo,Reiniciar después de alcanzar el límite superior).
     *
     * B-12: pasado antes de ejecutar la acción [ScheduledTaskDao.claimTask] Hacer cobro preventivo,
     *  Sondeo Garantizado / Trabajador / Manual"Ejecutar inmediatamente" / La multiplexación encadenada de diferentes procesos no ejecutará repetidamente la misma tarea debida.
     *
     * Si algún enlace falla, se registrará un registro. failed execution,No se lanza ninguna excepción.
     */
    suspend fun executeTask(task: ScheduledTaskEntity, chainDepth: Int = 0) {
        // v1.0.17: Límite de profundidad de la tarea en cadena,Prevenir la recursividad infinita
        if (chainDepth >= MAX_CHAIN_DEPTH) {
            Logger.w(TAG, "Chain depth limit reached ($MAX_CHAIN_DEPTH), skip task ${task.id}")
            return
        }
        if (restoreBlocksScheduledWrites(task.id, "Ejecución de tareas")) return

        val now = System.currentTimeMillis()
        // B-12: Cobro preventivo — Hacer átomos antes de ejecutar una acción. CAS,Prevenir encuestas(tickOnce)con Worker(tickOnceForWorker)
        // La ejecución simultánea de la misma tarea pendiente da como resultado llamadas de IA/notificaciones duplicadas.CAS Valor esperado usando DB actual next_run_at(en lugar de la persona que llama
        // Instantánea),Compatible con el manual de interfaz de usuario"Ejecutar inmediatamente"La entidad vencida pasó;Al recibirlo, avance next_run_at para eliminar al centinela del futuro lejano."Caducidad"
        // identidad,Los ejecutores posteriores fallarán si presionan el valor de vencimiento original CAS. El fin de la ejecución se restablece a verdadero mediante recordExecutionAndScheduleNext
        // Próxima hora de ejecución. Confíe en una única ACTUALIZACIÓN atómica de ScheduledTaskDao.claimTask para lograr seguridad entre procesos.
        val current = try {
            dao.getById(task.id)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Task ${task.id} load failed: ${e.message}")
            null
        }
        // La tarea fue eliminada antes de la ejecución.,No se puede recolectar,saltar(Evite también escribir registros de ejecución para tareas eliminadas.)
        if (current == null) {
            Logger.w(TAG, "Task ${task.id} not found, skip")
            return
        }
        // B-12(ABA defensa):Si el next_run_at actual ya está recibiendo centinela,Indica que otro ejecutor está ejecutando esta tarea.
        // (Recibido y aún no reiniciado),Nunca vuelvas a presionar el valor centinela en este momento CAS(Golpeará y ejecutará repetidamente),Simplemente sáltatelo.
        // Los valores centinela no se generan mediante programación normal(computeNextRun El límite superior es el nivel semanal./retirarse;cron No hay partido para ir
        // CronExpression.NO_MATCH = MAX-1,Diferenciarse de recibir Sentinel MAX,ver C-06),La única fuente es recibir acciones.
        if (current.nextRunAt == CLAIM_NEXT_RUN_SENTINEL) {
            Logger.w(TAG, "Task ${task.id} is currently being executed by another executer, skip")
            return
        }

        val claimedRows = try {
            dao.claimTask(task.id, current.nextRunAt, now, CLAIM_NEXT_RUN_SENTINEL)
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Task ${task.id} claim failed: ${e.message}")
            0
        }
        if (!claimTaskSucceeded(claimedRows)) {
            Logger.w(TAG, "Task ${task.id} already claimed by another executer, skip")
            return
        }
        // El restablecimiento puede comenzar después del cobro del CAS. El modelo ya no se llama en este momento./Herramientas para evitar escribir resultados en el conjunto de datos que se va a reemplazar;
        // La recopilación de centinelas la retoma el organismo de control o la siguiente ronda de programación después de la recuperación.
        if (io.zer0.common.ProcessWriteGate.restoring ||
            restoreBlocksScheduledWrites(task.id, "acción de tarea")
        ) {
            return
        }

        var status = "success"
        var replySummary = ""
        var errorMessage = ""

        try {
            // 1. evaluación de condición
            val condition = task.conditionJson.toCondition()
            if (!evaluateCondition(task, condition)) {
                status = "skipped"
                replySummary = context.getString(R.string.schedule_condition_not_met)
                // Avanzar aunque no se cumplan las condiciones schedule,Evite tareas estancadas
            } else {
                // 2. realizar acción
                if (restoreBlocksScheduledWrites(task.id, "acción de tarea")) return
                val action = AutomationConfig.Action(
                    type = task.actionType,
                    config = task.actionConfigJson.toAction().config,
                )
                val output = executeAction(task, action)
                replySummary = output.take(EXECUTION_DETAIL_MAX_LEN)
                // 3. Tareas de cadena de activación
                triggerChainTasks(task, chainDepth)
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            Logger.w(TAG, "Task ${task.id} execute failed: ${e.message}")
            status = "failed"
            errorMessage = (e.message ?: e.javaClass.simpleName).take(EXECUTION_DETAIL_MAX_LEN)
        }

        // 4. Historial de ejecución de registros atómicos + avance del siguiente tiempo de ejecución
        val execution = ScheduledTaskExecutionEntity(
            id = "exec-${java.util.UUID.randomUUID()}",
            taskId = task.id,
            executedAt = System.currentTimeMillis(),
            status = status,
            replySummary = replySummary,
            errorMessage = errorMessage,
        )
        // v1.0.17: Estrategia de reintento — Reintentar con retroceso exponencial en caso de falla,éxito/Reiniciar cuando se alcanza el límite superior retryCount
        val isFailed = status == "failed"
        val willRetry = isFailed && task.retryCount < task.maxRetries
        val newRetryCount = if (willRetry) task.retryCount + 1 else 0
        // Compensación de ronda perdida(punto de anclaje = en DB antes de recibir next_run_at,Es decir, el momento previsto en el que debería haber huido.).
        // Intente nuevamente retirarse y no participar en el avance del anclaje.,tampoco lo recuerdo missed —— El fracaso ya tiene un historial fallido.
        val advance = if (willRetry) {
            // retroceso exponencial: retryCount * 60s,máximo 5 minutos
            val backoff = minOf(newRetryCount.toLong() * RETRY_BACKOFF_STEP_MS, RETRY_BACKOFF_MAX_MS)
            ScheduleAdvance(now + backoff, 0)
        } else {
            computeNextRunAnchored(task.interval, task.cronExpr, current.nextRunAt, now)
        }
        // Sólo las tareas habilitadas se registran como perdidas: la ejecución manual de tareas deshabilitadas no cuenta como "perdidas",Ese es solo el usuario que desactiva la programación.
        val missedExecutions = if (advance.missedCount > 0 && current.enabled) {
            listOf(buildMissedExecution(task.id, advance.missedCount, current.nextRunAt, execution.executedAt))
        } else {
            emptyList()
        }
        if (restoreBlocksScheduledWrites(task.id, "Registro de ejecución de tareas")) return
        resultOf {
            // Registro de ejecución + registro perdido + avance next_run_at en la misma transacción:
            // O están todos ahí,O no aquí,No habrá ningún "perdido pero la programación no se adelantó" que provocará un conteo repetido en la siguiente ronda.
            dao.recordExecutionAndScheduleNext(
                execution = execution,
                taskId = task.id,
                nextRunAt = advance.nextRunAt,
                lastRunAt = now,
                retryCount = newRetryCount,
                missedExecutions = missedExecutions,
            )
        }.onError { msg, t -> Logger.w(TAG, "Record execution+scheduleNext failed: ${t?.message ?: msg}") }
    }

    /**
     * Construir un registro de ejecución perdida,El texto va strings.xml([R.string.schedule_missed_summary]).
     *
     * con success / failed / skipped mismo camino:Un resumen del historial de ejecución habría sido context.getString
     * deletreado([ScheduledTaskExecutionEntity.replySummary] Guarda texto localizado),
     * Así que perdido también se escribe aquí,No escriba caracteres chinos en el código.
     *
     * @param scheduledAt Última hora planificada(missed Contando puntos de anclaje)
     * @param now El momento de esta carrera de maquillaje.
     */
    private fun buildMissedExecution(
        taskId: String,
        missedCount: Int,
        scheduledAt: Long,
        now: Long,
    ): ScheduledTaskExecutionEntity {
        val gapHours = ((now - scheduledAt) / 3_600_000L).coerceAtLeast(0L)
        val plannedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(scheduledAt))
        return missedExecutionEntity(
            taskId = taskId,
            replySummary = context.getString(R.string.schedule_missed_summary, missedCount, plannedAt, gapHours),
            now = now,
        )
    }

    /**
     * v1.137: Evaluar las condiciones de automatización.
     *
     * v1.0.17: Se cambió a suspender para adaptarse a la consulta de suspensión de Room DAO(quick_note_exists Condiciones).
     */
    private suspend fun evaluateCondition(task: ScheduledTaskEntity, condition: AutomationConfig.Condition): Boolean {
        return when (condition.type) {
            AutomationConfig.Condition.ALWAYS -> true
            AutomationConfig.Condition.NETWORK_AVAILABLE -> isNetworkAvailable()
            AutomationConfig.Condition.TIME_RANGE -> {
                val cfg = condition.config
                val start = cfg["startHour"]?.toString()?.trim('"')?.toIntOrNull() ?: 0
                val end = cfg["endHour"]?.toString()?.trim('"')?.toIntOrNull() ?: 23
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                hour in start..end
            }
            AutomationConfig.Condition.QUICK_NOTE_EXISTS -> {
                val cfg = condition.config
                val tag = cfg["tag"]?.toString()?.trim('"')
                val keyword = cfg["keyword"]?.toString()?.trim('"')
                val dao = quickNoteDao ?: return false
                // DAO search excluido deleted=1,keyword/tag Sin filtrado cuando es nulo
                dao.search(keyword, tag, limit = 1).isNotEmpty()
            }
            AutomationConfig.Condition.CONTAINS -> {
                val cfg = condition.config
                val keyword = cfg["keyword"]?.toString()?.trim('"') ?: ""
                keyword.isNotBlank() && task.prompt.contains(keyword, ignoreCase = true)
            }
            AutomationConfig.Condition.BATTERY_LEVEL -> {
                val minLevel = condition.config["minLevel"]?.toString()?.toIntOrNull() ?: 20
                val bm = context.getSystemService(BatteryManager::class.java)
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                level >= minLevel
            }
            AutomationConfig.Condition.CHARGING -> {
                val mustCharging = condition.config["mustCharging"]?.toString()?.toBoolean() ?: true
                if (mustCharging) {
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    // Al pasar nulo, el receptor simplemente consulta el valor actual de la transmisión fija del sistema, sin la participación de ningún remitente externo.
                    // Entonces NOT_EXPORTED está arreglado; pero la bandera no se puede omitir,ContextCompat Cuando vea 0, se lanzará una excepción directamente.
                    val batteryStatus = ContextCompat.registerReceiver(
                        context,
                        null,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                } else {
                    true
                }
            }
            else -> true
        }
    }

    /**
     * v1.137: Realizar acciones específicas por tipo de acción,Devuelve un resumen de los resultados.
     */
    private suspend fun executeAction(task: ScheduledTaskEntity, action: AutomationConfig.Action): String {
        return when (action.type) {
            AutomationConfig.Action.AI_PROMPT -> executeAiPrompt(task)
            AutomationConfig.Action.CREATE_QUICK_NOTE -> executeCreateQuickNote(action)
            AutomationConfig.Action.CALL_TOOL -> executeCallTool(task, action)
            AutomationConfig.Action.NOTIFY -> executeNotify(task, action)
            else -> executeAiPrompt(task)
        }
    }

    private suspend fun executeAiPrompt(task: ScheduledTaskEntity): String {
        val assistant = resolveAssistant(task.assistantId)
        val messages = buildMessages(assistant, task.prompt).toMutableList()
        // v2.6.8(comentarios de los usuarios): Anteriormente, las tareas programadas no tenían ninguna herramienta.
        //
        // Cuando la implementación anterior llama a chatService.completeText**No se pasó ningún parámetro de herramientas**, el modelo no tiene ninguna herramienta,
        // Entonces lo que ve el usuario“La fuente de datos no está disponible para esta ronda.: quick_note_list / screen_time / clima”No es que el modelo sea demasiado vago para adaptarse.
        // Pero no tiene una sola herramienta. redacción de documentos“Las tareas programadas reutilizan el mismo registro de herramientas”, la implementación es inconsistente con la documentación.
        //
        // Aquí se agrega el ciclo de ensamblaje de herramientas + llamada de herramientas de múltiples rondas, que es consistente con el diálogo principal:
        //  - Enviar solo herramientas de solo lectura (README/NORMAL riesgo), el fondo desatendido no puede realizar acciones de ALTO riesgo
        //  - Como máximo MAX_TOOL_ROUNDS rondas para evitar bucles infinitos
        //  - En cada ronda, los resultados de la herramienta se retroalimentan en mensajes de la herramienta y se requiere otra respuesta.
        val toolDefs = resolveScheduledToolDefinitions(assistant)
        var toolRounds = 0
        var completion: io.zer0.ai.core.ChatCompletion? = null
        while (toolRounds <= MAX_TOOL_ROUNDS) {
            val current = withTimeoutOrNull(LLM_TIMEOUT_MS) {
                chatService.completeText(
                    messages = messages,
                    temperature = assistant.temperature,
                    maxTokens = assistant.maxTokens,
                    tools = toolDefs.takeIf { it.isNotEmpty() },
                )
            } ?: error(context.getString(R.string.schedule_err_ai_timeout, LLM_TIMEOUT_MS / 1000))
            completion = current
            val calls = current.toolCalls
            if (calls.isNullOrEmpty() || toolDefs.isEmpty()) break
            if (toolRounds >= MAX_TOOL_ROUNDS) {
                Logger.w(TAG, "El número de rondas de la herramienta de tareas programadas ha alcanzado el límite superior $MAX_TOOL_ROUNDS, deja de llamar")
                break
            }
            toolRounds++
            // Complete la ronda actual de llamadas a herramientas del asistente nuevamente en el historial, luego ejecútelas una por una y rellene los resultados.
            messages.add(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    content = current.text.orEmpty(),
                    toolCalls = calls,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            calls.forEach { call ->
                val output = executeScheduledTool(call)
                messages.add(
                    UIMessage(
                        role = MessageRole.TOOL,
                        content = output,
                        toolCallId = call.id,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        val finalCompletion = completion
            ?: error(context.getString(R.string.schedule_err_ai_timeout, LLM_TIMEOUT_MS / 1000))
        // v1.0.74 fix: despegar <think> etiqueta de inferencia,Evitar que el contenido del pensamiento se mezcle con las respuestas a las tareas.
        val reply = io.zer0.muse.transformer.stripThinkTags(finalCompletion.text)
        if (restoreBlocksScheduledWrites(task.id, "sesión cronometrada")) return ""
        val now = System.currentTimeMillis()
        val sessionId = if (task.dedicatedSessionId.isNotBlank()) {
            task.dedicatedSessionId
        } else {
            val newSessionId = sessionRepository.createSession(assistant.id)
            sessionRepository.renameSession(
                newSessionId,
                task.name.ifBlank { context.getString(R.string.schedule_default_session_name) },
            )
            resultOf { dao.updateDedicatedSessionId(task.id, newSessionId) }
                .onError { msg, t -> Logger.w(TAG, "updateDedicatedSessionId failed: ${t?.message ?: msg}") }
            newSessionId
        }
        sessionRepository.appendMessage(
            sessionId = sessionId,
            message = UIMessage(role = MessageRole.USER, content = task.prompt, createdAt = now),
        )
        sessionRepository.appendMessage(
            sessionId = sessionId,
            message = UIMessage(role = MessageRole.ASSISTANT, content = reply, createdAt = System.currentTimeMillis()),
        )
        showNotification(
            task.name,
            reply,
            MuseNotificationTarget.ScheduledTask(task.id),
        )
        return reply
    }

    /**
     * v2.6.8: Una colección de herramientas disponibles para tareas programadas.
     *
     * Herramienta de archivos de solo lectura (SAFE / NORMAL). Las acciones de alto riesgo no se pueden realizar durante la ejecución desatendida en segundo plano.
     * Aunque esté marcado en la configuración del asistente no se enviará.——Esta es una diferencia intencional en el diálogo con el Señor.
     * La disponibilidad de la herramienta también está sujeta a la lista blanca de toolIdsJson del asistente.
     */
    private fun resolveScheduledToolDefinitions(assistant: AssistantEntity): List<ToolDefinition> {
        val registry = toolRegistry ?: return emptyList()
        return try {
            val enabledIds = if (assistant.toolIdsJson.isBlank() || assistant.toolIdsJson == "[]") {
                null
            } else {
                AppJson.decodeFromString<List<String>>(assistant.toolIdsJson)
            }
            registry.listToolsAsToolDefinitions(enabledIds)
                .filter { def ->
                    val risk = runCatching {
                        io.zer0.muse.tools.ToolPermissionResolver.riskLevelFor(def.name)
                    }.getOrDefault(io.zer0.muse.tools.ToolRiskLevel.NORMAL)
                    risk != io.zer0.muse.tools.ToolRiskLevel.HIGH
                }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "No se pudieron ensamblar las herramientas de tareas programadas. No se proporcionan herramientas para esta ronda.: ${t.message}")
            emptyList()
        }
    }

    /** v2.6.8: Ejecute una única llamada a la herramienta que devuelva el texto rellenado al modelo. */
    private suspend fun executeScheduledTool(call: io.zer0.ai.core.ToolCall): String {
        val registry = toolRegistry ?: return "[La herramienta no está disponible] ToolRegistry no inicializado"
        val risk = runCatching {
            io.zer0.muse.tools.ToolPermissionResolver.riskLevelFor(call.name)
        }.getOrDefault(io.zer0.muse.tools.ToolRiskLevel.HIGH)
        if (risk == io.zer0.muse.tools.ToolRiskLevel.HIGH) {
            Logger.w(TAG, "Herramienta de ALTO riesgo para omitir tareas programadas: ${call.name}")
            return "[saltar] ${call.name} Es una herramienta de alto riesgo y no está autorizada la ejecución en segundo plano de tareas programadas."
        }
        return try {
            // ToolCall.arguments es un texto JSON,ToolRegistry.execute necesitar Map<String, String>
            val argsJson = AppJson.parseToJsonElement(call.arguments) as? kotlinx.serialization.json.JsonObject
            val argsMap: Map<String, String> = argsJson
                ?.mapValues { (_, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
                }
                .orEmpty()
            val outcome = registry.execute(call.name, argsMap)
            if (outcome.isError) {
                "[falló] ${call.name}: ${outcome.content}"
            } else {
                outcome.content
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.w(TAG, "Herramienta de tareas programadas ${call.name} excepción de ejecución: ${t.message}")
            "[anormal] ${call.name}: ${t.message ?: t::class.simpleName}"
        }
    }

    private suspend fun executeCreateQuickNote(action: AutomationConfig.Action): String {
        val cfg = action.config
        val title = cfg["title"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("create_quick_note desaparecido title")
        val content = cfg["content"]?.toString()?.trim('"') ?: ""
        val tags = cfg["tags"]?.toString()?.trim('"')?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val dao = quickNoteDao ?: throw IllegalStateException("QuickNoteDao no inicializado")
        val id = java.util.UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.w(TAG, "Restauración de copia de seguridad en curso, omitiendo la escritura taquigráfica programada")
            return ""
        }
        dao.upsert(
            QuickNoteEntity(
                id = id,
                title = title,
                content = content,
                tags = tags,
                pinned = false,
                deleted = false,
                deletedAt = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return context.getString(R.string.tool_quick_note_added, id, title)
    }

    private suspend fun executeCallTool(task: ScheduledTaskEntity, action: AutomationConfig.Action): String {
        val cfg = action.config
        val toolId = cfg["toolId"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("call_tool desaparecido toolId")
        val paramsElement = cfg["params"]
        val paramsJson = when (paramsElement) {
            is JsonObject -> AppJson.encodeToString(JsonObject.serializer(), paramsElement)
            else -> paramsElement?.toString()?.trim('"') ?: "{}"
        }
        val registry = toolRegistry ?: throw IllegalStateException("ToolRegistry no inicializado")
        if (restoreBlocksScheduledWrites(task.id, "herramientas de sincronización")) return ""
        // v1.0.17: La acción call_tool de la tarea programada aumenta la aprobación del riesgo,Solución de riesgo de seguridad para omitir ToolPermissionResolver
        // Las tareas programadas se ejecutan en segundo plano sin interacción del usuario,No se puede realizar la aprobación de permisos a nivel de sesión,Por lo tanto, la herramienta de ALTO riesgo se intercepta directamente aquí.
        // P0-3: La evaluación de riesgos va ToolPermissionResolver.riskLevelFor(Tabla explícita + fuente única de verdad para la inferencia de prefijos),
        // Ya no dependa del valor de la cuenta de registro —— Los valores registrados están desconectados de las tablas explícitas.(Por ejemplo, send_email se registró como NORMAL)tiempo
        // evitará esta interceptación,Debe basarse en el analizador.
        if (io.zer0.muse.tools.ToolPermissionResolver.riskLevelFor(toolId) == io.zer0.muse.tools.ToolRiskLevel.HIGH) {
            Logger.w(TAG, "call_tool skipped HIGH risk tool '$toolId' in scheduled task '${task.name}'")
            return "Evite las herramientas de alto riesgo: $toolId"
        }
        return io.zer0.muse.tools.ToolRouteExecutionGuard(registry)
            .executeFromJson(toolId, paramsJson)
    }

    private fun executeNotify(task: ScheduledTaskEntity, action: AutomationConfig.Action): String {
        val cfg = action.config
        val title = cfg["title"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() } ?: "Muse"
        val message = cfg["message"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("notify desaparecido message")
        if (restoreBlocksScheduledWrites(task.id, "Notificación periódica")) return ""
        showNotification(title, message, MuseNotificationTarget.ScheduledTask(task.id))
        return message
    }

    /**
     * v1.137: Activar tareas en cadena.
     *
     * v1.0.17: Cambiar a ejecución sincrónica — Actualice primero cada tarea posterior next_run_at,luego directamente executeTask,
     * No más esperas a la próxima ronda electoral de los años 60. Cada tarea de la cadena es independiente. try-catch,Un solo fracaso no afecta a los demás.
     * [chainDepth] pasar por executeTask,alcanzar [MAX_CHAIN_DEPTH] Detener la recursividad.
     */
    private suspend fun triggerChainTasks(task: ScheduledTaskEntity, chainDepth: Int = 0) {
        val nextIds = task.nextTaskIdsJson.toIdsList()
        if (nextIds.isEmpty()) return
        for (nextId in nextIds) {
            try {
                dao.triggerNextTasks(listOf(nextId))
                val nextTask = dao.getById(nextId) ?: continue
                executeTask(nextTask, chainDepth + 1)
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                Logger.w(TAG, "Chain task $nextId failed: ${e.message}")
            }
        }
    }

    /**
     * Compruebe si el dispositivo tiene actualmente una conexión de red disponible.
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            capabilities != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Resolver la configuración del asistente por AssistantId.
     * - assistantId está vacío o "default" → tomar id="default" asistente,Sé el primero en dar el paso
     * - Otros id → coincidencia exacta;Si no lo encuentra, retroceda default,Sé el primero en dar el paso
     * - Ningún ayudante → Lanzar excepción(por [executeTask] registro de captura failed)
     */
    private suspend fun resolveAssistant(assistantId: String): AssistantEntity {
        val assistants = assistantRepository.observeAll.first()
        val id = assistantId.trim()
        if (id.isBlank() || id == "default") {
            return assistants.firstOrNull { it.id == "default" }
                ?: assistants.firstOrNull()
                ?: error(context.getString(R.string.schedule_err_no_assistant))
        }
        return assistants.firstOrNull { it.id == id }
            ?: assistants.firstOrNull { it.id == "default" }
            ?: assistants.firstOrNull()
            ?: error(context.getString(R.string.schedule_err_no_assistant))
    }

    /** Construya una lista de mensajes para llamar a AI:asistente systemPrompt(tiempo no vacío) + mensaje de usuario. */
    private fun buildMessages(assistant: AssistantEntity, prompt: String): List<UIMessage> {
        val list = mutableListOf<UIMessage>()
        if (assistant.systemPrompt.isNotBlank()) {
            list.add(UIMessage(role = MessageRole.SYSTEM, content = assistant.systemPrompt))
        }
        list.add(UIMessage(role = MessageRole.USER, content = prompt))
        return list
    }

    fun stop() {
        job?.cancel()
        job = null
        Logger.i(TAG, "Runner stopped")
    }

    /**
     * Phase 3 3E: Enviar mensajes programados — Escriba el mensaje escrito previamente del usuario en la sesión y muestre la notificación.
     */
    private suspend fun deliverPendingMessage(pm: io.zer0.muse.data.schedule.PendingMessage) {
        if (io.zer0.common.ProcessWriteGate.restoring ||
            restoreBlocksScheduledWrites(pm.id, "Mensaje a enviar")
        ) {
            return
        }
        val now = System.currentTimeMillis()
        try {
            // Escribir mensaje en la sesión de destino
            sessionRepository.appendMessage(
                sessionId = pm.sessionId,
                message = UIMessage(role = MessageRole.USER, content = pm.content, createdAt = now),
            )
            // Notificación emergente
            showNotification(
                "Scheduled Message",
                pm.content,
                MuseNotificationTarget.Session(pm.sessionId),
            )
            Logger.i(TAG, "Delivered pending message ${pm.id} to session ${pm.sessionId}")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to deliver pending message ${pm.id}: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.schedule_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.schedule_channel_desc) }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private val notificationId = AtomicInteger(NOTIFICATION_ID_BASE)

    /**
     * v1.64: Notificación emergente de vencimiento de tarea programada — Haga clic para ir directamente a la tarea correspondiente y expandir el historial de ejecución.
     * usar muse://scheduled-task/{taskId} deep link,Navegue hasta la ruta de tareas especificada después de que ShareIntentHandler la analice.
     */
    private fun showNotification(title: String, content: String, target: MuseNotificationTarget) {
        val notificationManager = MuseNotificationManager(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(notificationManager.buildMainActivityPendingIntent(target))
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId.getAndIncrement(), notification)
    }
}
