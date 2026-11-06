package io.zer0.memory.ticker

import io.zer0.ai.core.Model
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.budget.LlmBudget
import io.zer0.memory.compile.MemoryCompileTarget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.deep.DeepMemoryProcessor
import io.zer0.memory.summary.DailyStateDao
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.SessionSummaryManager
import io.zer0.memory.time.TimeContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Si el resumen continuo debe actualizarse en esta ronda.
 *
 * La función pura se extrae para que pueda bloquearse directamente mediante una única prueba:**El intervalo de resumen debe dividirse uniformemente por el intervalo de compilación.**,
 * De lo contrario"Compilar primero y luego resumir"El antiguo resumen de la ronda anterior se utilizará para compilar la memoria de hoy.
 */
internal fun shouldRollSummary(turnCount: Int): Boolean = turnCount > 0 && turnCount % MemoryTicker.TURNS_PER_SUMMARY == 0

/** Si la memoria de hoy debería compilarse y ensamblarse en esta ronda. Los intervalos son más amplios (las escrituras son más intensas con llamadas de modelos grandes). */
internal fun shouldCompileToday(turnCount: Int): Boolean = turnCount > 0 && turnCount % MemoryTicker.TURNS_PER_COMPILE == 0

/**
 * Programador de memoria.
 *
 * Estrategia de activación:
 *  - cada [TURNS_PER_SUMMARY] rueda: rollingSummary
 *  - cada [TURNS_PER_COMPILE] rueda: Realice compileToday + ensamblado adicional (las escrituras con llamadas a modelos grandes son más intensas y tienen intervalos más amplios)
 *  - session fin: final rollingSummary + compileToday + assemble
 *  - cambio de fecha: compileToday → compileWeek → compileLongterm → compileFacts → assemble → deepMemory
 *
 * Diferencias de diseño:
 *  - Sin concepto de ruta de archivo de sesión(muse Utilice sessionId para comprobar directamente Room)
 *  - Ninguno cache snapshot reflection(muse Sin observación de caché inmediata)
 *  - Sin modo de hechos editables(muse ir para siempre legacy)
 *  - No hay observables de ID de agente/dir-agente
 *  - Reemplazar con corrutinas de Kotlin setInterval,Job rastrear sustituciones _activeJobs Set
 *
 * Seguridad del hilo:
 *  - [_summaryInProgress] / [_activeJobs] usar [Mutex] proteger
 *  - [_health] protegido con sincronizado(Lectura y escritura de memoria pura.,región crítica muy corta)
 *  - daily pipeline usar [_dailyRunning] (AtomicBoolean.compareAndSet) Anti-reentrada
 */
@Suppress("LongParameterList")
class MemoryTicker(
    private val summaryManager: SessionSummaryManager,
    private val compiler: MemoryCompiler,
    private val deepProcessor: DeepMemoryProcessor,
    private val dailyStateDao: DailyStateDao,
    /** Marca de agua de restablecimiento de memoria(ISO),null Indica no restablecer. */
    private val getResetAt: () -> String?,
    /** Interruptor maestro de memoria. Se omite toda la canalización cuando se devuelve false. */
    private val isMemoryEnabled: () -> Boolean = { true },
    /** Alcance de rutina propio del programador. Generalmente inyectado en el ámbito de aplicación por DI. */
    private val scope: CoroutineScope,
    /** v1.x: Reutilizar todos los días(Fusionar hechos casi duplicados);null Saltar el paso de deduplicación(prueba/compatibles). */
    private val factStore: io.zer0.memory.fact.FactStore? = null,
    /**
     * v12 (T3-1): tarea de reflexión de la memoria — Clasificación diaria(Rellenar claves de entidad/fusionar duplicados/Detección de contradicciones/promoción).
     * null Salta el paso de reflexión(prueba/compatibles);El fallo no bloquea el proceso principal.
     */
    private val reflectionRunner: io.zer0.memory.reflection.MemoryReflectionRunner? = null,
    /**
     * v1.0.92: LLM Consolidador de memoria — Después de eliminar duplicados de las reglas diarias,Entregar grupos similares semánticamente repetidos al modelo grande
     * fusionar("Integración de modelo regular").null salto de tiempo(prueba/compatibles);El fallo no bloquea el proceso principal.
     */
    private val factConsolidator: io.zer0.memory.fact.LlmFactConsolidator? = null,
    /**
     * v0.32: Configuración avanzada del sistema de memoria(memory.* Configuración).
     *
     * usar `() -> MemoryConfig` cierre en lugar de directo [MemoryConfig] valor,Porque la configuración la establece el usuario en la página de configuración
     * Ajustar en tiempo real(escribir DataStore),El programador debería obtener el último valor cada vez que lee,En lugar de almacenar en caché una copia durante la construcción.
     * Valor predeterminado `{ MemoryConfig() }` Asegúrese de que las llamadas antiguas no sean destruidas(compatible con versiones anteriores).
     *
     * A través de propiedades públicas [config] Expuesto a la lectura externa.;Al mismo tiempo, se pasa de forma transparente a los métodos posteriores que realmente lo utilizan.:
     *  - [tokenBudget] → [LlmBudget.truncateToTokenBudget],en [readCompiledMemoryMarkdown] cultivo interior
     *  - [compileThreshold] / [baseImportance] / [decayPerDay] / [forgetSpeed] →
     *    [MemoryCompiler.compileFacts] con [DeepMemoryProcessor.processDirtySessions]
     *    cálculo de fracción de hecho/desintegración dentro
     */
    private val runtimeContext: MemoryRuntimeContext = MemoryRuntimeContext(),
    /**
     * D3-P1: Registro de operaciones de tubería(capa de observación).null Sólo hacer informes de salud.(prueba/No se pasa el entorno filesDir).
     * Inyectado por el ensamblaje del lado de la aplicación,posición de colocación `filesDir/memory/pipeline_log.jsonl`.
     */
    private val pipelineLog: io.zer0.memory.observe.PipelineLog? = null,
    /** Todos los asistentes FactStore,Proporciona deduplicación diaria entre fragmentos/Integración semántica. */
    private val getFactStoresForMaintenance: suspend () -> List<io.zer0.memory.fact.FactStore> = { emptyList() },
    /**
     * v2.6.2(auditoría de memoria): Analice el FactStore del asistente correspondiente según el objetivo de compilación (scope=="main" o vacío → biblioteca predeterminada).
     * Se utiliza para la conciliación diaria de hechos de canalización para evitar subasistentes/Espacio no predeterminado Proyecta el hecho de la biblioteca predeterminada en la ranura actual.
     */
    private val resolveFactStore: ((io.zer0.memory.compile.MemoryCompileTarget) -> io.zer0.memory.fact.FactStore?)? = null,
) {

    /** v2.6.2: Analiza el FactStore correspondiente al objetivo y vuelve al valor predeterminado cuando no se inyecta ningún solucionador. [factStore]. */
    private fun factStoreForTarget(target: io.zer0.memory.compile.MemoryCompileTarget): io.zer0.memory.fact.FactStore? =
        resolveFactStore?.invoke(target) ?: factStore

    /** actualmente en vigor [MemoryConfig](El cierre se relee en cada acceso.,Asegúrese de obtener la configuración de usuario más reciente). */
    val config: MemoryConfig get() = runtimeContext.getConfig()

    companion object {
        const val TURNS_PER_SUMMARY = 5

        /**
         * Compile la memoria de hoy + frecuencia de montaje (número de rondas).
         *
         * con [TURNS_PER_SUMMARY] Separado: los resúmenes continuos se actualizan cada 5 rondas (los usuarios informaron que 10 rondas era demasiado tarde y los resúmenes se pasaban por alto fácilmente)
         * lo que acaba de pasar), y"Compilar hoy + ensamblar"Las escrituras involucradas son significativamente más intensas con llamadas de modelos grandes, manteniendo 10 rondas a la vez.
         * Ambos deben estar satisfechos [TURNS_PER_SUMMARY] divisible [TURNS_PER_COMPILE], de lo contrario, la compilación se ejecutará antes del resumen,
         * Se utiliza un resumen que aún no ha sido actualizado.
         */
        const val TURNS_PER_COMPILE = 10
        const val DAILY_CHECK_INTERVAL_MS = 60L * 60 * 1000 // 1 horas
        private const val TAG = "MemoryTicker"

        /**
         * A-19: asistente principal id — Tubería de compilación(today/facts)Sólo se incluye el resumen del asistente principal.
         * Consistente con la identificación fija de AssistantRepository.ensureDefaultExists;
         * vacío/fila de datos antigua(assistant_id='')También retenido en la capa DAO,Compatible con datos históricos.
         */
        const val MAIN_ASSISTANT_ID = "default"

        /** Versión del esquema de canalización diaria. Mejorado cuando cambia la estructura de pasos.,Invalide los puntos de interrupción antiguos y vuelva a calcularlos todos a la vez. */
        const val DAILY_STATE_SCHEMA_VERSION = 3

        /** 5 pasos al día(dependencia del orden: compileDaily debe preceder compileToday,rollDailyWindow Se ha colocado dependencia diaria). */
        val DAILY_STEP_KEYS = listOf(
            "compileDaily",
            "compileToday",
            "rollDailyWindow",
            "compileFacts",
            "deepMemory",
        )

        /** Los 6 pasos, incluido el balanceoResumen(health Para informar). */
        val STEP_KEYS = listOf("rollingSummary") + DAILY_STEP_KEYS
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val stepsSerializer = MapSerializer(String.serializer(), String.serializer())

    // ──────────────────────────────────────────────
    //  Estado
    // ──────────────────────────────────────────────

    /** session → Rondas acumulativas(Desde el último lanzamientoResumen).v1.78: Cambie ConcurrentHashMap para evitar excepciones de modificaciones simultáneas. */
    private val _turnCounts = ConcurrentHashMap<String, Int>()

    /**
     * La última notificación de conversación de cada sesión corresponde al asistente/Instantánea espacial.
     *
     * Al cambiar de sesión [notifySessionEnd] En realidad, puede ejecutarse después de que el contexto de tiempo de ejecución se haya cambiado a un nuevo espacio;
     * Guarde el destino cuando se complete el envío aquí para evitar que el resumen continuo de la sesión anterior se inserte en el nuevo espacio.
     */
    private val _sessionNotificationTargets = ConcurrentHashMap<String, MemoryCompileTarget>()

    /** Corriendo rodandoResumen session id(Prevenir el reingreso). */
    private val _summaryInProgress = mutableSetOf<String>()
    private val _summaryInProgressLock = Mutex()

    /**
     * v1.0.51: compileToday bloqueo mutex — serialize Escribiendo a la sección HOY.
     *
     * notifyTurn y notifySessionEnd pueden activarse simultáneamente doCompileTodayAndAssemble,
     * Ambos llaman a compiler.compileToday para escribir la misma línea de registros de Room(TODAY section),
     * No bloquear dará lugar a una cobertura de escritura de carrera de huellas dactilares + desperdicio de llamadas dobles de LLM.
     */
    private val _compileTodayLock = Mutex()

    /**
     * Revisión de corrección (B-6): FACTS mutex de compilación de segmentos — Serialize escribe en la sección HECHOS.
     *
     * daily pipeline Se llamarán ambas cadenas de step4 y forceCompileNow compiler.compileFacts
     * escritura directa FACTS(Leer datos anteriores + LLM Fusionar + Escribir),Cuando no hay exclusión mutua, es last-writer-wins,concurrencia
     * El escritor posterior sobrescribe el producto anterior. Utilice aquí aislamiento de bloqueo independiente,con HOY [_compileTodayLock] ortogonal.
     *
     * Restricciones de orden de bloqueo(Anti-punto muerto): TODAY No lleves la cerradura dentro de la cerradura.,No hay ningún candado HOY en este candado. —
     * doDaily de step0/1(TODAY) con step4(FACTS) Son dos secciones críticas ejecutadas secuencialmente.,
     * forceCompileNow También HECHOS primero y luego TODAY,Ambos nunca están anidados al mismo tiempo.,No hay riesgo de estancamiento.
     */
    private val _compileFactsLock = Mutex()

    /**
     * v1.0.50: Límite de simultaneidad global — Limite el número de resumenes continuos en curso simultáneamente.
     *
     * Límite de simultaneidad 3, alineado con DeepMemoryProcessor (alineación interna preservada). La implementación original solo elimina duplicados según el ID de sesión.,Cambia rápidamente N conversaciones
     * Hará que N RollingSummary se ejecute en paralelo,Superpuesto a la canalización diaria deepMemory(Ya Semaphore(3)),
     * 13 Se pueden acumular una docena de bombas de solicitud de texto completo en cuestión de segundos. API,Activar la limitación actual o incluso la prohibición.
     *
     * Cap 3: Alineado con DeepMemoryProcessor.MAX_CONCURRENT,Evite recordar tareas en segundo plano debido a la sobrecarga de la cuota de API para las sesiones de los usuarios.
     */
    private val _rollingSummaryConcurrency = Semaphore(3)

    /** Colección de trabajos en segundo plano(stop unidad de tiempo await). */
    private val _activeJobs = mutableSetOf<Job>()
    private val _activeJobsLock = Any()

    // v1.78 (H7): cambiar AtomicBoolean,Utilice compareAndSet para eliminar las condiciones de carrera de verificar y actuar
    private val _dailyRunning = AtomicBoolean(false)

    @Volatile private var _lastDailyJobDate: String? = null

    /** v2.6.2(auditoría de memoria): Grabar por separado por objetivo“Hoy se ha completado el diario.”—— De lo contrario, después de cortar Espacio/Asistente
     * El diario del nuevo objetivo nunca se compilará ese día.key = "scope|space". */
    private val _lastDailyJobDateByTarget = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile private var _stopped = false
    private var _timerJob: Job? = null

    /**
     * Estado del ciclo de vida del temporizador.
     *
     * debe poner [TimerPhase.STOPPING] con [TimerPhase.STOPPED] separar:stop() Para esperar a que se esté ejecutando una tarea en segundo plano
     * Refinamiento(más largo 30s),Durante este tiempo, el trabajo de cronometraje se canceló, pero la "parada" aún no ha finalizado. La implementación anterior tenía solo una
     * `_timerJob != null` juez,Confundir esta ventana con "aún en ejecución",entonces start() directo return,
     * posteriormente stop() Dejar trabajo en blanco —— La verificación de memoria diaria está suspendida permanentemente.,No hay una palabra en el registro.
     */
    private enum class TimerPhase { STOPPED, RUNNING, STOPPING }

    /** proteger [timerPhase] / [_timerJob] / [startQueuedDuringStop] conversión. */
    private val timerLock = Any()
    private var timerPhase = TimerPhase.STOPPED

    /** stop Durante el período de espera, recibí inicio: debe iniciarse después de la parada.,No se puede tragar. */
    private var startQueuedDuringStop = false

    // ──────────────────────────────────────────────
    //  estado de salud
    // ──────────────────────────────────────────────

    // v1.78 (M3): cambio de campo val,Pase copy() Crea una nueva instancia y asigna un valor.,Visibilidad garantizada y semántica inmutable.
    data class StepHealth(
        val lastSuccessAt: String? = null,
        val lastErrorAt: String? = null,
        val lastErrorMsg: String? = null,
        val failCount: Int = 0,
    )

    private val _health = STEP_KEYS.associateWith { StepHealth() }.toMutableMap()
    private val _healthLock = Any()

    // v1.0.51: healthFlow Inicializado en la instantánea _health actual,en lugar de vacío map,
    // Los suscriptores pueden ver el estado de salud inicial de todos los pasos cuando leen por primera vez.(en lugar de esperar por primera vez publishHealth)
    private val _healthFlow = MutableStateFlow<Map<String, StepHealth>>(_health.toMap())
    val healthFlow: StateFlow<Map<String, StepHealth>> = _healthFlow.asStateFlow()

    /**
     * v1.0.51: Progreso de la migración de la memoria de stock — Informes en tiempo real del resumen de la sesión del historial cuando se inicia la ejecución de recuperación por primera vez después de la actualización.
     *
     * null Indica que no se está migrando(gratis);No nulo significa en ejecución o recién terminado(UI Mostrar en consecuencia/Ocultar barra de progreso).
     * [done]=true La interfaz de usuario de publicación oculta automáticamente la barra de progreso,No ingresará al proceso de migración la próxima vez que lo inicie.(La bandera persiste).
     */
    data class BackfillProgress(
        val total: Int,
        val processed: Int,
        val succeeded: Int,
        val failed: Int,
        val currentSessionId: String?,
        val done: Boolean,
    )

    private val _backfillProgressFlow = MutableStateFlow<BackfillProgress?>(null)
    val backfillProgressFlow: StateFlow<BackfillProgress?> = _backfillProgressFlow.asStateFlow()

    /**
     * v1.0.51: per-step Firma de deduplicación de error — Reemplaza el single global original _lastErrorSig.
     *
     * La implementación original utiliza un único global sig,step A El éxito en el paso B después del fracaso borrará incorrectamente los pasos A sig,
     * Como resultado, el paso A no se deduplicará si falla la próxima vez.(Actualizar la pantalla),Y la atribución del registro de recuperación del paso B es incorrecta.
     * Después de cambiar al mapa por paso,Pasos de recuperación/Elimine duplicados sin interferir entre sí.
     */
    private val _errorSigs = ConcurrentHashMap<String, String>()

    // ── D3-P1: Observación de tuberías(Cada paso lleva tiempo/gatillo/Clasificación de fallas → pipeline_log) ──────────────
    /** Punto de inicio de un solo paso(stepKey → Hora de inicio/fuente de disparo). */
    private val _stepRunMeta = ConcurrentHashMap<String, StepRunMeta>()

    /** Paso ejecutar metadatos(D3-P1). */
    private data class StepRunMeta(val startMs: Long, val trigger: String, val scope: String?)

    /** D3-P1: Corredor de pasos unificado(Recuento acumulado + registro de canalización);Los informes de estado todavía utilizan el markSuccess/markFailure existente. */
    private val stepRunner = io.zer0.memory.observe.MemoryStepRunner(
        onStepSuccess = null,
        onStepFailure = null,
        pipelineLog = pipelineLog,
    )

    /** D3-P3: Retroceso de falla a nivel de paso(daily Convergencia de reintento infinito de ticks). */
    private val stepBackoff = io.zer0.memory.observe.MemoryStepBackoff()

    /**
     * D3-P1: Marcar el inicio del paso(Proporciona el tiempo de cálculo de markSuccess/markFailure y la fuente de activación).
     * Los pasos no marcados se grabarán con 0 tiempo transcurrido. + "unknown" Fuente,No afecta la corrección.
     */
    private fun markStepStart(stepKey: String, trigger: String, scope: String? = null) {
        _stepRunMeta[stepKey] = StepRunMeta(System.currentTimeMillis(), trigger, scope)
    }

    /** D3-P1: Los resultados del paso se informan a la capa de observación.(contar + pipeline_log);Independientemente del éxito o el fracaso, estos metadatos de inicio se consumirán. */
    private fun recordStepOutcome(stepKey: String, error: Throwable?) {
        val meta = _stepRunMeta.remove(stepKey)
        val duration = meta?.let { System.currentTimeMillis() - it.startMs } ?: 0L
        val trigger = meta?.trigger ?: "unknown"
        val scope = meta?.scope
        if (error == null) {
            stepBackoff.recordSuccess(stepKey)
            stepRunner.recordSuccess(stepKey, duration, trigger, scope = scope)
        } else {
            stepBackoff.recordFailure(stepKey)
            stepRunner.recordFailure(stepKey, error, duration, trigger, scope = scope)
        }
    }

    /** D3-P3: ¿Se puede ejecutar el paso?(checkpoint No completado y no en la ventana de recuperación). */
    private fun canRunStep(stepKey: String, completed: Map<String, String>): Boolean {
        if (stepKey in completed) return false
        if (!stepBackoff.shouldRun(stepKey)) {
            Logger.d(TAG, "pasos $stepKey En la ventana de retirada del fracaso,Saltate esta ronda(fracasos consecutivos ${stepBackoff.failureCount(stepKey)} veces)")
            return false
        }
        return true
    }

    private fun markSuccess(stepKey: String) {
        recordStepOutcome(stepKey, null)
        synchronized(_healthLock) {
            val h = _health[stepKey] ?: return
            _health[stepKey] = h.copy(
                lastSuccessAt = Instant.now().toString(),
                lastErrorAt = null,
                lastErrorMsg = null,
                failCount = 0,
            )
            publishHealth()
        }
    }

    private fun markFailure(stepKey: String, err: Throwable) {
        recordStepOutcome(stepKey, err)
        synchronized(_healthLock) {
            val h = _health[stepKey] ?: return
            _health[stepKey] = h.copy(
                lastErrorAt = Instant.now().toString(),
                lastErrorMsg = err.message ?: err.toString(),
                failCount = h.failCount + 1,
            )
            publishHealth()
        }
    }

    private fun publishHealth() {
        _healthFlow.value = _health.toMap()
    }

    private fun logStepError(label: String, err: Throwable) {
        val msg = err.message ?: err.toString()
        val sig = "$label|$msg"
        // v1.0.51: per-step Eliminar duplicados — La misma causa raíz del mismo paso solo se escribe una vez. warn
        if (_errorSigs[label] == sig) {
            Logger.d(TAG, "$label (dup suppressed): $msg")
            return
        }
        _errorSigs[label] = sig
        Logger.w(TAG, "$label falló: $msg", err)
    }

    private fun markStepRecovered(label: String) {
        val prev = _errorSigs.remove(label) ?: return
        Logger.i(TAG, "$label volver a la normalidad(antes: $prev)")
    }

    /** Devolver una instantánea de salud para cada paso(copia profunda,llamada en espera segura). */
    fun getHealthStatus(): Map<String, StepHealth> = synchronized(_healthLock) {
        _health.mapValues { it.value.copy() }
    }

    // ──────────────────────────────────────────────
    //  Job Seguimiento
    // ──────────────────────────────────────────────

    private fun trackJob(job: Job) {
        synchronized(_activeJobsLock) { _activeJobs.add(job) }
        job.invokeOnCompletion { synchronized(_activeJobsLock) { _activeJobs.remove(job) } }
    }

    /** en [scope] donde iniciar una tarea rastreada de disparar y olvidar. */
    private fun launchTracked(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(context = Dispatchers.IO, block = block)
        trackJob(job)
        return job
    }

    // ──────────────────────────────────────────────
    //  Daily state persistencia
    // ──────────────────────────────────────────────

    private data class DailyContext(
        val logicalDate: String,
        val resetAt: String?,
        val factsMode: String = "legacy",
        /** v2.6.2(auditoría de memoria): El progreso diario se almacena mediante la clave de sucursal de destino. */
        val stateKey: String = "default",
    )

    private suspend fun currentContext(): DailyContext {
        val zone = TimeContext.resolveTimeZone(TimeContext.DEFAULT_TIMEZONE)
        val logicalDate = TimeContext.logicalDayFor(Instant.now(), zone).logicalDate.toString()
        val target = currentCompileTarget()
        val stateKey = "${target.normalizedScope}|${target.normalizedSpaceId}"
        return DailyContext(
            logicalDate = logicalDate,
            resetAt = getResetAt(),
            factsMode = "legacy|${target.normalizedScope}|${target.normalizedSpaceId}",
            stateKey = stateKey,
        )
    }

    private fun normalizeResetAt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toString() }.getOrNull()
    }

    // v1.78 (H5): El ajuste de las llamadas suspendidas DAO se debe realizar con resultOf,evitar tragar CancellationException
    private suspend fun readDailyState(context: DailyContext): DailyStateEntity? = resultOf {
        dailyStateDao.get(context.stateKey)
    }.getOrNull()

    private suspend fun writeDailyState(context: DailyContext, completedSteps: Map<String, String>, dailyCompletedAt: String?) {
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.d(TAG, "daily state: La recuperación de la copia de seguridad está en curso, se omite la escritura del punto de control")
            return
        }
        val entity = DailyStateEntity(
            key = context.stateKey,
            schemaVersion = DAILY_STATE_SCHEMA_VERSION,
            logicalDate = context.logicalDate,
            resetAt = context.resetAt,
            factsMode = context.factsMode,
            completedSteps = json.encodeToString(stepsSerializer, completedSteps),
            dailyCompletedAt = dailyCompletedAt,
            updatedAt = Instant.now().toString(),
        )
        // v1.78 (H5): El ajuste de las llamadas suspendidas DAO se debe realizar con resultOf,evitar tragar CancellationException
        resultOf { dailyStateDao.upsert(entity) }.onError { msg, t ->
            Logger.w(TAG, "daily state write failed: $msg", t)
        }
    }

    /**
     * Lea los pasos completados durante el día desde Room.
     * context Devuelve vacío si no hay coincidencia map(equivalente a"restablecer el progreso").
     */
    private suspend fun restoreDailyProgress(context: DailyContext): Map<String, String> {
        val state = readDailyState(context) ?: return emptyMap()
        if (state.schemaVersion != DAILY_STATE_SCHEMA_VERSION) {
            Logger.i(TAG, "daily state schema no hay coincidencia(${state.schemaVersion} != $DAILY_STATE_SCHEMA_VERSION),restablecer el progreso")
            return emptyMap()
        }
        if (state.logicalDate != context.logicalDate) return emptyMap()
        if (normalizeResetAt(state.resetAt) != context.resetAt) return emptyMap()
        if (state.factsMode != context.factsMode) return emptyMap()
        return runCatching {
            json.decodeFromString(stepsSerializer, state.completedSteps)
        }.getOrElse {
            Logger.w(TAG, "restoreDailyProgress: completedSteps JSON El análisis falló,restablecer el progreso: ${it.message}")
            emptyMap()
        }
    }

    // ──────────────────────────────────────────────
    //  interno: rollingSummary
    // ──────────────────────────────────────────────

    /**
     * @return true Indica que RollingSummary es exitoso y tiene cambios.(session Resumen actualizado,Adecuado para la extracción inmediata de hechos.)
     */
    private suspend fun doRollingSummary(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String,
        timeZone: String,
        trigger: String,
        assistantId: String = "",
        spaceId: String? = null,
    ): Boolean {
        // Protección de reentrada: La misma sesión no se ejecuta dos veces al mismo tiempo. rolling
        _summaryInProgressLock.withLock {
            if (sessionId in _summaryInProgress) return false
            _summaryInProgress.add(sessionId)
        }
        try {
            markStepStart("rollingSummary", trigger)
            // v1.0.50: Límite de simultaneidad global — Incluso cambie rápidamente entre múltiples sesiones,Solo hay 3 RollingSummary ejecutándose al mismo tiempo.
            val result = _rollingSummaryConcurrency.withPermit {
                summaryManager.rollingSummary(
                    sessionId,
                    messages,
                    model,
                    locale,
                    timeZone,
                    SessionSummaryManager.SummaryOwnership(
                        assistantId = assistantId,
                        spaceId = spaceId?.ifBlank { null }
                            ?: runtimeContext.getCurrentSpaceId().ifBlank { "default" },
                    ),
                )
            }
            // v1.0.50: Diferenciar registros según los cambios,evitar"LLM Volver vacío → changed=false → Golpeado por error updated"encubrir la culpa
            if (result.changed) {
                Logger.d(TAG, "rolling summary updated: ${sessionId.take(8)}… (trigger=$trigger, ${result.summary.length} chars)")
                markSuccess("rollingSummary")
                markStepRecovered("resumen de desplazamiento")
                return true
            } else {
                // changed=false puede ser: Conversación vacía/LLM devuelve vacío/el incremento no tiene contenido nuevo
                Logger.d(TAG, "rolling summary unchanged: ${sessionId.take(8)}… (trigger=$trigger, summary=${result.summary.length} chars)")
                // No marques el fracaso — Diálogo vacío/Ningún incremento es normal;El verdadero fracaso del LLM tomará la rama de captura
                return false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markFailure("rollingSummary", e)
            logStepError("resumen de desplazamiento ($sessionId)", e)
            return false
        } finally {
            _summaryInProgressLock.withLock { _summaryInProgress.remove(sessionId) }
        }
    }

    // ──────────────────────────────────────────────
    //  interno: compileToday + assemble
    // ──────────────────────────────────────────────

    private suspend fun currentCompileTarget(): MemoryCompileTarget {
        // v2.6.2(auditoría de memoria): scope debe normalizarse —— asistente principal assistantId="default"
        // La convención de alcance principal de hechos correspondiente es "main", la implementación anterior usa AssistantId directamente como alcance.
        // Como resultado, el producto de compilación diaria cae en scope="default" ranura, y al leer "main" No está bien.
        val assistantId = runtimeContext.getCurrentAssistantId()
        return MemoryCompileTarget(
            assistantId = assistantId,
            scope = assistantId.takeIf { it != MAIN_ASSISTANT_ID } ?: "main",
            spaceId = runtimeContext.getCurrentSpaceId(),
        )
    }

    private fun captureNotificationTarget(sessionId: String, assistantId: String, spaceId: String?) {
        if (assistantId.isBlank() && spaceId.isNullOrBlank()) return
        _sessionNotificationTargets[sessionId] =
            notificationCompileTarget(
                current = MemoryCompileTarget(),
                assistantId = assistantId,
                spaceId = spaceId,
            )
    }

    private suspend fun resolveSessionNotificationTarget(
        captured: MemoryCompileTarget?,
        assistantId: String,
        spaceId: String?,
    ): MemoryCompileTarget {
        val current = captured ?: currentCompileTarget()
        return notificationCompileTarget(
            current = current,
            assistantId = assistantId.ifBlank { captured?.assistantId.orEmpty() },
            spaceId = spaceId ?: captured?.normalizedSpaceId,
        )
    }

    private suspend fun doCompileTodayAndAssemble(
        model: Model?,
        locale: String,
        timeZone: String,
        targetOverride: MemoryCompileTarget? = null,
    ) {
        if (!stepBackoff.shouldRun("compileToday")) return
        try {
            markStepStart("compileToday", "turn")
            val target = targetOverride ?: currentCompileTarget()
            // v1.0.51: serialize compileToday llamar,Evite la condición de carrera de la sección notifyTurn/notifySessionEnd concurrente HOY
            _compileTodayLock.withLock {
                // A-19: Compile solo el resumen del asistente principal.,No se permite acceder a los resúmenes de sesiones del subasistente a través de multiplataforma"hoy"segmento
                compiler.compileToday(summaryManager, model, locale, timeZone, target = target)
            }
            // assemble en musa es perezoso:readCompiledMemoryMarkdown Llamado en tiempo real por ChatService
            // La escritura de archivos no se activa explícitamente aquí,Notificar solo health
            markSuccess("compileToday")
            markStepRecovered("compileToday")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            markFailure("compileToday", e)
            logStepError("compileToday", e)
        }
    }

    // ──────────────────────────────────────────────
    //  interno: daily pipeline(Cinta transportadora rodante de día,5 cinturón para caminar checkpoint)
    // ──────────────────────────────────────────────

    private suspend fun doDaily(model: Model?, locale: String, timeZone: String) {
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.d(TAG, "daily: La copia de seguridad y la restauración están en curso, omita esta canalización de memoria.")
            return
        }
        // v1.78 (H7): Atomizar con compareAndSet check-then-act,Eliminar condiciones de carrera
        if (!_dailyRunning.compareAndSet(false, true)) return
        try {
            val context = currentContext()
            val target = currentCompileTarget()
            var completed = restoreDailyProgress(context)
            // v1.0.53: Verificación de invalidación de progreso — daily_state Registro completo pero las secciones compiladas están vacías
            // (La antigua semántica updateContent UPDATE descarta silenciosamente las escrituras en tablas vacías),Restablezca el progreso para volver a ejecutar la compilación.
            if (completed.isNotEmpty() && !compiler.hasAnyCompiledSection()) {
                Logger.w(TAG, "tareas diarias: checkpoint existe pero las secciones_compiladas están vacías(Escritura de la versión antigua bug),Restablecer el progreso y ejecutar de nuevo")
                completed = emptyMap()
            }
            Logger.i(TAG, "Comienza la misión diaria (${context.logicalDate})")
            var hasFailed = false

            // Revisión de corrección (B-5): Step 0 + Step 1 La sección completa está incluida en _compileTodayLock.
            // doCompileTodayAndAssemble(Activado simultáneamente por notifyTurn)La sección HOY se escribirá con esta sección.
            // Si no en la misma cerradura,En el primer turno entre días, doCompileTodayAndAssemble se puede leer en el Paso 0
            // El borrador de ayer se sobrescribió simultáneamente como contenido de un nuevo día.,Hace que ayerDraft lea el borrador reescrito ayer.
            // daily perdido. Bloquear toda la sección(Leer borrador + compilar diariamente + actualizar compilar hoy)Asegúrate de leer antes de continuar.
            // Escribe HOY átomo.
            _compileTodayLock.withLock {
                // Step 0: compileDaily——Destilar el ayer que se ha convertido en memory/daily/{date}.md.
                // Debe ejecutarse antes de compilar hoy:compileDaily leer"La versión final de ayer, el borrador de hoy"Incluso en este momento
                // El contenido que se encuentra en la sección HOY.;compileToday una vez ejecutado primero,El cambio de fecha se restablecerá hoy a
                // Borrador en blanco para un nuevo día,El borrador de ayer ya no se puede leer.
                if (canRunStep("compileDaily", completed)) {
                    try {
                        markStepStart("compileDaily", "daily")
                        val yesterday = runCatching {
                            LocalDate.parse(context.logicalDate).minusDays(1).toString()
                        }.getOrNull()
                        if (yesterday != null) {
                            val yesterdayDraft = compiler.readSection(MemoryCompiler.Section.TODAY, target)
                            // B-09: La ruta de respaldo también se limita al asistente principal.,No se permite la entrada de resúmenes de sub-asistentes. daily → week → longterm cadena
                            compiler.compileDaily(
                                summaryManager,
                                yesterday,
                                yesterdayDraft,
                                model,
                                locale,
                                timeZone,
                                target = target,
                            )
                            completed = completed + ("compileDaily" to Instant.now().toString())
                            writeDailyState(context, completed, null)
                            markSuccess("compileDaily")
                            markStepRecovered("compileDaily")
                        } else {
                            hasFailed = true
                            val err = IllegalArgumentException("No se puede calcular la fecha de ayer")
                            markFailure("compileDaily", err)
                            logStepError("compileDaily", err)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        hasFailed = true
                        markFailure("compileDaily", e)
                        logStepError("compileDaily", e)
                    }
                }

                // Step 1: compileToday(Actualizar después del cambio de fecha today.md)
                if (canRunStep("compileToday", completed)) {
                    try {
                        markStepStart("compileToday", "daily")
                        // A-19: Compile solo el resumen del asistente principal.
                        compiler.compileToday(summaryManager, model, locale, timeZone, target = target)
                        completed = completed + ("compileToday" to Instant.now().toString())
                        writeDailyState(context, completed, null)
                        markSuccess("compileToday")
                        markStepRecovered("compileToday(daily)")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        hasFailed = true
                        markFailure("compileToday", e)
                        logStepError("compileToday(daily)", e)
                    }
                }
            }

            // Step 2: Asamblea Zero LLM del directorio diario/ week.md(Ninguno checkpoint,Operaciones de archivos puros)
            if (io.zer0.common.ProcessWriteGate.restoring) return
            try {
                compiler.assembleWeekFromDaily(target = target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                hasFailed = true
                Logger.w(TAG, "assembleWeekFromDaily falló: ${e.message}", e)
            }

            // Step 3: rollDailyWindow——Doble las entradas diarias extraídas de la ventana de N días a largo plazo y elimine el archivo fuente.
            // La dependencia compileDaily se puso en el mercado ayer.,De lo contrario, el juicio de la ventana omitirá el último día.
            if (io.zer0.common.ProcessWriteGate.restoring) return
            if (canRunStep("rollDailyWindow", completed) && "compileDaily" in completed) {
                try {
                    markStepStart("rollDailyWindow", "daily")
                    compiler.rollDailyWindow(model, locale, context.logicalDate, target)
                    completed = completed + ("rollDailyWindow" to Instant.now().toString())
                    writeDailyState(context, completed, null)
                    markSuccess("rollDailyWindow")
                    markStepRecovered("rollDailyWindow")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hasFailed = true
                    markFailure("rollDailyWindow", e)
                    logStepError("rollDailyWindow", e)
                }
            }

            // Step 4: compileFacts(independiente de step 0-3)
            // Revisión de corrección (B-6): FACTS compilación de segmentos _compileFactsLock,Con fuerzaCompileNow
            // compileFacts mutuamente excluyentes — Ambas cadenas escriben directamente FACTS(Leer datos anteriores + LLM Fusionar + Escribir),desbloqueado
            // ¿Los últimos escritores ganadores sobrescribirán los productos de los demás? Reconciliación también escribe la sección HECHOS,Por lo tanto, la cerradura se mantiene junto con la misma sección crítica.
            if (io.zer0.common.ProcessWriteGate.restoring) return
            if (canRunStep("compileFacts", completed)) {
                _compileFactsLock.withLock {
                    try {
                        markStepStart("compileFacts", "daily")
                        // A-19: Compile solo el resumen del asistente principal.
                        compiler.compileFacts(summaryManager, model, locale, runtimeContext.getConfig(), target = target)
                        // v12 (T2-1): Conciliación entre productos compilados y tabla de hechos. — Ediciones del usuario en la página de memoria/Después de fusionar los hechos,
                        // El producto está sincronizado con el valor de representación de los hechos.,La próxima inyección ya no tendrá la representación anterior. El fallo no afecta el proceso principal.
                        // v2.6.2(auditoría de memoria): Las lecturas y escrituras de reconciliación utilizan lo mismo target —— La implementación anterior lee el factStore predeterminado,
                        // No pases objetivo, sub-asistente/Los espacios no predeterminados proyectarán los datos de la biblioteca predeterminada en el espacio implícito actual.
                        val targetStore = factStoreForTarget(target) ?: factStore
                        targetStore?.let { fs ->
                            resultOf { fs.getByScopeAndSpace(target.scope, target.spaceId) }
                                .onSuccess { facts ->
                                    if (facts.isNotEmpty()) {
                                        resultOf { compiler.reconcileFactsSectionWithStore(facts, target) }
                                            .onError { msg, t -> Logger.w(TAG, "facts Falló la conciliación del producto: ${t?.message ?: msg}") }
                                    }
                                }
                                .onError { msg, t -> Logger.w(TAG, "facts La lectura de la tabla falló(Conciliación omitida): ${t?.message ?: msg}") }
                        }
                        completed = completed + ("compileFacts" to Instant.now().toString())
                        writeDailyState(context, completed, null)
                        markSuccess("compileFacts")
                        markStepRecovered("compileFacts")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        hasFailed = true
                        markFailure("compileFacts", e)
                        logStepError("compileFacts", e)
                    }
                }
            }

            // Step 5: deepMemory(independiente,actualizar FactStore)
            if (io.zer0.common.ProcessWriteGate.restoring) return
            if (canRunStep("deepMemory", completed)) {
                try {
                    markStepStart("deepMemory", "daily")
                    val result = deepProcessor.processDirtySessions(summaryManager, model, locale, runtimeContext.getConfig())
                    completed = completed + ("deepMemory" to Instant.now().toString())
                    if (result.processed > 0) {
                        Logger.i(TAG, "deep-memory: ${result.processed} session, ${result.factsAdded} nuevos hechos")
                    }
                    writeDailyState(context, completed, null)
                    markSuccess("deepMemory")
                    markStepRecovered("deepMemory")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    hasFailed = true
                    markFailure("deepMemory", e)
                    logStepError("deepMemory", e)
                }
            }

            // La deduplicación de reglas diarias y la integración semántica de LLM cubren todas las bibliotecas asistentes,No solo el FactStore predeterminado.
            val additionalStores = resultOf { getFactStoresForMaintenance() }
                .onError { msg, t -> Logger.w(TAG, "No se pudo leer la biblioteca de memoria del asistente: ${t?.message ?: msg}") }
                .getOrNull()
                .orEmpty()
            val maintenanceStores = (listOfNotNull(factStore) + additionalStores)
                .distinctBy { it.assistantId.ifBlank { "default" } }
            for (store in maintenanceStores) {
                resultOf { store.dedupPassAllSpaces() }
                    .onError { msg, t ->
                        Logger.w(TAG, "Falló la deduplicación de memoria diaria(${store.assistantId}): ${t?.message ?: msg}")
                    }
                    .onSuccess { count ->
                        if (count > 0) Logger.i(TAG, "Deduplicación de memoria diaria(${store.assistantId}): fusionar $count Artículo")
                    }
                factConsolidator?.let { consolidator ->
                    resultOf { consolidator.consolidateAll(store) }
                        .onError { msg, t ->
                            Logger.w(TAG, "Fallo diario de integración de la memoria LLM(${store.assistantId}): ${t?.message ?: msg}")
                        }
                        .onSuccess { count ->
                            if (count > 0) {
                                Logger.i(TAG, "Consolidación de memoria diaria de LLM(${store.assistantId}): fusionar $count grupo")
                            }
                        }
                }
            }

            // v12 (T3-1): reflexión de la memoria — Clasificación diaria(Rellenar claves de entidad/Fusionar duplicados con las mismas entidades/Detección de contradicciones/promoción).
            // Comprobación de plagio al escribir(camino rápido)complementario,Limpiar la repetición de la sedimentación histórica.;El fallo no afecta el proceso principal.
            reflectionRunner?.let { runner ->
                resultOf { runner.runReflection(scope = target.scope, spaceId = target.spaceId) }
                    .onError { msg, t -> Logger.w(TAG, "Fallo diario en la reflexión de la memoria: ${t?.message ?: msg}") }
            }

            if (hasFailed) {
                val done = completed.keys.joinToString(", ")
                Logger.w(TAG, "La misión diaria falló parcialmente,Completado: [$done],Reintentar los pasos no finalizados en el siguiente tick")
            } else {
                _lastDailyJobDate = context.logicalDate
                _lastDailyJobDateByTarget[context.stateKey] = context.logicalDate
                writeDailyState(context, completed, Instant.now().toString())
                Logger.i(TAG, "Tareas diarias completadas")
            }
        } finally {
            // v1.78 (H7): AtomicBoolean usar set(false) liberar
            _dailyRunning.set(false)
        }
    }

    private fun checkDailyJob(model: Model?, locale: String, timeZone: String) {
        if (_stopped) return
        if (!isMemoryEnabled()) return
        launchTracked {
            val context = currentContext()
            if (_lastDailyJobDateByTarget[context.stateKey] != context.logicalDate) doDaily(model, locale, timeZone)
        }
    }

    // ──────────────────────────────────────────────
    //  publico API
    // ──────────────────────────────────────────────

    /**
     * Convocado después de cada ronda de diálogo.(ChatViewModel Después de recibir una respuesta completa del asistente, llame).
     *
     * fire-and-forget: Regresar inmediatamente,rollingSummary + compileToday Ejecutándose en segundo plano.
     *
     * @param sessionId sesión actual id
     * @param messages Historial completo de conversaciones(incluyendo epiciclo user + assistant)
     * @param model modelo para resumen(Cuando es nulo, el cliente LLM selecciona el valor predeterminado.)
     * @param locale Idioma(zh-CN / en-US)
     * @param timeZone zona horaria
     */
    fun notifyTurn(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "es",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
        spaceId: String? = null,
    ) {
        if (_stopped) return
        if (!isMemoryEnabled()) return
        captureNotificationTarget(sessionId, assistantId, spaceId)
        // v1.78: átomo read-modify-write,Evite la pérdida de recuento al cambiar de sesión al mismo tiempo
        // L1: ConcurrentHashMap.merge Se escribirá la clave ausente. defaultValue(1),Y la acumulación nunca volverá null,
        // Por lo tanto, el tiempo de ejecución nunca regresa. null;?: 1 código hasta la muerte,Remoto.merge Declaración de firma V?,usar !! Afirmar que no es nulo para que coincida con la semántica real.
        // Nota:Utilice lambda en su lugar Int::plus,Evite que Int::plus sea analizado bajo la estricta verificación de nulidad de Kotlin Int? receptor
        val count = _turnCounts.merge(sessionId, 1) { a, b -> a + b }!!

        if (shouldRollSummary(count)) {
            launchTracked {
                val target = resolveSessionNotificationTarget(
                    captured = _sessionNotificationTargets[sessionId],
                    assistantId = assistantId,
                    spaceId = spaceId,
                )
                doRollingSummary(
                    sessionId,
                    messages,
                    model,
                    locale,
                    timeZone,
                    "threshold",
                    assistantId = target.assistantId.orEmpty(),
                    spaceId = target.normalizedSpaceId,
                )
                // compilar/El ensamblaje es más pesado que el resumen, vaya a sus propios intervalos: ejecútelo solo si lo golpea al mismo tiempo,
                // Asegúrate de que el resumen utilizado sea el que se acaba de actualizar en esta ronda.
                if (shouldCompileToday(count)) {
                    doCompileTodayAndAssemble(model, locale, timeZone, target)
                }
            }
        }
        checkDailyJob(model, locale, timeZone)
    }

    /**
     * Session Llamado antes de cambiar o deshacerse(final pass).
     *
     * fire-and-forget: Regresar inmediatamente,Ejecute RollingSummary + compileToday en segundo plano.
     *
     * @return detrás del escenario Job,dispose La escena puede await;switch/close Simplemente ignora la escena
     */
    fun notifySessionEnd(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "es",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
        spaceId: String? = null,
    ): Job {
        if (_stopped) return scope.launch { /* no-op */ }
        val capturedTarget = _sessionNotificationTargets.remove(sessionId)
        val count = _turnCounts.remove(sessionId) ?: 0
        if (count == 0) return scope.launch { /* no-op */ }
        if (!isMemoryEnabled()) return scope.launch { /* no-op */ }
        return launchTracked {
            runSessionEndPipeline(
                sessionId = sessionId,
                messages = messages,
                model = model,
                locale = locale,
                timeZone = timeZone,
                capturedTarget = capturedTarget,
                assistantId = assistantId,
                spaceId = spaceId,
            )
        }
    }

    /**
     * Session Al final, lea el historial completo de la capa de persistencia y luego ejecute el resumen final.
     *
     * La interfaz de usuario del chat utiliza ventanas de paginación,No se puede tratar la lista de memoria actual como una sesión completa;proporcionada por la persona que llama
     * provider Ejecutado en el ámbito de aplicación propio del ticker.,No será cancelado por la destrucción de ViewModel.
     */
    fun notifySessionEndFromProvider(
        sessionId: String,
        messagesProvider: suspend () -> List<UIMessage>,
        model: Model?,
        locale: String = "es",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
        spaceId: String? = null,
    ): Job {
        if (_stopped) return scope.launch { /* no-op */ }
        val capturedTarget = _sessionNotificationTargets.remove(sessionId)
        val count = _turnCounts.remove(sessionId) ?: 0
        if (count == 0) return scope.launch { /* no-op */ }
        if (!isMemoryEnabled()) return scope.launch { /* no-op */ }
        return launchTracked {
            val messages = messagesProvider()
            if (messages.size < 2) return@launchTracked
            runSessionEndPipeline(
                sessionId = sessionId,
                messages = messages,
                model = model,
                locale = locale,
                timeZone = timeZone,
                capturedTarget = capturedTarget,
                assistantId = assistantId,
                spaceId = spaceId,
            )
        }
    }

    private suspend fun runSessionEndPipeline(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String,
        timeZone: String,
        capturedTarget: MemoryCompileTarget?,
        assistantId: String,
        spaceId: String?,
    ) {
        try {
            // v1.0.51: rollingSummary Después del éxito y los cambios.,Ejecute la extracción de hechos ahora(no esperes daily pipeline)
            // Después de que el usuario sale de la conversación,Los hechos acaban de decir(Como por ejemplo"Hay examen el próximo miércoles.")Puede entrar inmediatamente FactStore,Disponible en la próxima conversación
            val target = resolveSessionNotificationTarget(capturedTarget, assistantId, spaceId)
            val changed = doRollingSummary(
                sessionId,
                messages,
                model,
                locale,
                timeZone,
                "session_end",
                assistantId = target.assistantId.orEmpty(),
                spaceId = target.normalizedSpaceId,
            )
            doCompileTodayAndAssemble(model, locale, timeZone, target)
            if (changed) {
                // rollingSummary Hay cambios → session convertirse en dirty → Extraiga hechos al instante
                // processSession El estado sucio se comprobará internamente,Reutilizar Semaphore(3) cola
                resultOf {
                    deepProcessor.processSession(
                        sessionId,
                        summaryManager,
                        model,
                        locale,
                        runtimeContext.getConfig(),
                    )
                }.onError { msg, t ->
                    Logger.w(TAG, "notifySessionEnd: processSession falló: $msg", t)
                }
            }
        } catch (e: CancellationException) {
            // v1.78 (H3): La señal de cancelación de rutina debe volver a lanzarse.,De lo contrario, se romperá la semántica de cancelación de la rutina.
            throw e
        } catch (e: Throwable) {
            Logger.w(TAG, "notifySessionEnd Error de fondo: ${e.message}")
        }
    }

    /**
     * Forzar actualización del resumen de la sesión especificada(Asegúrese de que el resumen esté actualizado antes de llamar a funciones como el diario).
     */
    fun flushSession(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "es",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
    ): Job {
        if (_stopped) return scope.launch { /* no-op */ }
        if (!isMemoryEnabled()) return scope.launch { /* no-op */ }
        return launchTracked {
            doRollingSummary(sessionId, messages, model, locale, timeZone, "manual", assistantId)
        }
    }

    /**
     * empezar cada hora daily check timer(Gatillo alternativo,No depende del diálogo del usuario.).
     * El principal desencadenante es [notifyTurn] al final [checkDailyJob].
     *
     * v1.78: start() restablecerá la bandera detenida,stop Se puede reiniciar más tarde.
     *
     * con [stop] Entre está "estado deseado + máquina de estado único",No dos interruptores independientes:
     *  - [TimerPhase.RUNNING]:Retorno idempotente,No más novedades job;
     *  - [TimerPhase.STOPPING]:La parada de descripción está bloqueada esperando a que finalice la tarea en segundo plano. esta vez start **cola**
     *    ([startQueuedDuringStop]),por [finishStop] Recuperar después de parar, en lugar de desechar en silencio;
     *  - En otros casos, construya inmediatamente job;
     *  - Cada vez que "se solicita que se ejecute, pero en realidad no se está ejecutando ningún temporizador", se mostrará ADVERTENCIA. Podrás verlo en el registro la próxima vez.
     */
    fun start() {
        var started = false
        var queuedBehindStop = false
        var revivedDeadTimer = false
        synchronized(timerLock) {
            // Consistente con la implementación anterior:start Significa que la línea de montaje debe funcionar
            _stopped = false
            when {
                timerPhase == TimerPhase.STOPPING -> {
                    startQueuedDuringStop = true
                    queuedBehindStop = true
                }
                timerPhase == TimerPhase.RUNNING && _timerJob?.isActive == true -> Unit
                else -> {
                    // Está DETENIDO o EN FUNCIONAMIENTO cuando llegas aquí, pero el trabajo de sincronización ha muerto.
                    revivedDeadTimer = timerPhase == TimerPhase.RUNNING
                    timerPhase = TimerPhase.RUNNING
                    _timerJob = launchDailyCheckTimer()
                    started = true
                }
            }
        }
        when {
            queuedBehindStop ->
                Logger.w(TAG, "El temporizador de verificación de memoria se está deteniendo.start Ya en cola:Se reiniciará automáticamente una vez completada la parada.")
            started && !isTimerRunning() ->
                Logger.w(TAG, "El temporizador de verificación diaria de la memoria no pudo iniciarse(scope El trabajo cancelado o programado está inactivo), este comienzo está perdido")
            started && revivedDeadTimer ->
                Logger.w(TAG, "El temporizador de verificación diaria de la memoria se ha detenido antes(El trabajo de cronometraje no está activo), este comienzo se reconstruye")
            started -> Logger.i(TAG, "v3 iniciado(turn-based,daily check timer ${DAILY_CHECK_INTERVAL_MS / 60000}min)")
            else -> Logger.d(TAG, "start ignorar:El temporizador de verificación de memoria ya está funcionando")
        }
    }

    /** El cronómetro realmente está corriendo en este momento.(El estado se está ejecutando y el trabajo está activo.). Se utiliza para pruebas y diagnósticos de apagado. */
    internal fun isTimerRunning(): Boolean = synchronized(timerLock) {
        timerPhase == TimerPhase.RUNNING && _timerJob?.isActive == true
    }

    /** Nombre del estado del ciclo de vida del temporizador(Diagnóstico/para probar). */
    internal val timerPhaseName: String get() = synchronized(timerLock) { timerPhase.name }

    /** Cree e inicie el trabajo de cronometraje. La persona que llama debe esperar [timerLock]. */
    private fun launchDailyCheckTimer(): Job = scope.launch(Dispatchers.IO) {
        while (!_stopped) {
            delay(DAILY_CHECK_INTERVAL_MS)
            if (_stopped) break
            checkDailyJob(model = null, locale = "es", timeZone = TimeContext.DEFAULT_TIMEZONE)
        }
    }

    /**
     * Detener programador,Espere a que se completen todas las tareas en segundo plano.
     *
     * v1.78: Agregar tiempo de espera de 30 segundos,Evite que las llamadas LLM estancadas causen stop() Bloqueado permanentemente.
     * Todos los trabajos sin terminar se cancelan por la fuerza después del tiempo de espera.
     *
     * Es seguro ejecutar stop dos veces al mismo tiempo:El primero en presionar al estado. [TimerPhase.STOPPING],El segundo vio lo mismo.
     * El estado sólo será igual a un trabajo y se omitirá el cierre.
     */
    suspend fun stop() {
        val timer = beginStop()
        if (timer != null) {
            timer.cancel()
            try {
                timer.join()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // timer job join La falla no bloquea la limpieza posterior
            }
        }
        stopTrackedJobs()
        finishStop()
    }

    /** stop primer paso(sincronizar):Marque el estado y recupere el trabajo cronometrado para esperar. */
    private fun beginStop(): Job? = synchronized(timerLock) {
        _stopped = true
        when (timerPhase) {
            TimerPhase.STOPPED -> null
            TimerPhase.RUNNING, TimerPhase.STOPPING -> {
                timerPhase = TimerPhase.STOPPING
                _timerJob
            }
        }
    }

    /** Espere a que finalice la tarea en segundo plano en ejecución(Con gorra de los años 30). */
    private suspend fun stopTrackedJobs() {
        val jobs: List<Job> = synchronized(_activeJobsLock) { _activeJobs.toList() }
        if (jobs.isEmpty()) return
        // v1.0.51: Primero cancele todos los trabajos y déjelos comenzar a salir.,Luego use joinAll para esperar en paralelo,
        // Reemplace el original para cada pedido join(5 trabajo cada uno 5s → 25s Serie vs 5s paralelo)
        synchronized(_activeJobsLock) {
            _activeJobs.forEach { it.cancel() }
        }
        // v1.78 (H8): join No hay necesidad runCatching(tragará CancellationException);
        // cancel Los bloques deben usar try/finally,Ejecutar incluso si se agota el tiempo de espera de withTimeoutOrNull
        try {
            withTimeoutOrNull(30_000L) {
                jobs.joinAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // joinAll Una única excepción de trabajo no bloquea la salida general
        }
    }

    /** stop el final(sincronizar):Borre el trabajo y decida si completarlo de acuerdo con la solicitud de la cola. */
    private fun finishStop() {
        var restarted = false
        synchronized(timerLock) {
            if (timerPhase != TimerPhase.STOPPING) return
            _timerJob = null
            if (startQueuedDuringStop) {
                // stop Durante este período, alguien le pidió que huyera.:Comenzar inmediatamente después de parar,No desperdicies la solicitud
                startQueuedDuringStop = false
                timerPhase = TimerPhase.RUNNING
                _stopped = false
                _timerJob = launchDailyCheckTimer()
                restarted = true
            } else {
                timerPhase = TimerPhase.STOPPED
            }
        }
        when {
            restarted && isTimerRunning() ->
                Logger.i(TAG, "Se ha reiniciado el temporizador de comprobación diaria de la memoria.(stop Recibido durante start)")
            restarted ->
                Logger.w(TAG, "El temporizador de verificación diaria de la memoria no se pudo reiniciar(scope Cancelado),stop La solicitud de inicio durante")
            else -> Logger.i(TAG, "El temporizador de verificación de memoria se ha detenido")
        }
    }

    /**
     * Activar manualmente una compilación completa(Utilizado durante la depuración/inicio).
     * Corre primero daily job(Asegúrese de que existan semana/largo plazo/hechos),Luego compila hoy.
     */
    suspend fun tick(model: Model? = null, locale: String = "es", timeZone: String = TimeContext.DEFAULT_TIMEZONE) {
        if (_stopped) return
        // B-7: Copia de seguridad y restauración(Reemplazo de nivel de archivo DB)Saltar compilación de escritura dentro de la ventana,Evite pérdidas de escritura.
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.d(TAG, "tick: Restauración de copia de seguridad en curso,Salta esta compilación de memoria")
            return
        }
        if (!isMemoryEnabled()) return
        val context = currentContext()
        if (_lastDailyJobDateByTarget[context.stateKey] != context.logicalDate) {
            doDaily(model, locale, timeZone)
        }
        doCompileTodayAndAssemble(model, locale, timeZone)
    }

    // ──────────────────────────────────────────────
    //  v1.0.51: Migración de memoria de stock(backfill)
    // ──────────────────────────────────────────────

    /**
     * Obtenga la colección de ID de sesión de todos los resúmenes existentes(summary No vacío).
     * Calcule la diferencia establecida cuando se usa en relleno:allSessions - summarizedSessions = La sesión que debe volverse a ejecutar.
     */
    suspend fun getAllSummarizedSessionIds(): Set<String> = summaryManager.getAllSummaries().map { it.sessionId }.toSet()

    /**
     * v1.0.51: Inventa una carrera para una sola sesión. rollingSummary(Utilizado para la migración de stock.).
     *
     * Reutilizar [doRollingSummary] lógica interna(Contiene protección de reentrada _summaryInProgress + Semaphore(3) Límite actual simultáneo global),
     * trigger="backfill" Conveniente para distinguir los registros. No se lanza ninguna excepción en caso de error.,Regresar false,El conteo fallido lo cuenta la persona que llama.
     *
     * @return true Indica que el resumen ha cambiado.(éxito);false Indica diálogo vacío/LLM Volver vacío/falló
     */
    suspend fun backfillSession(
        sessionId: String,
        messages: List<UIMessage>,
        model: Model?,
        locale: String = "es",
        timeZone: String = TimeContext.DEFAULT_TIMEZONE,
        assistantId: String = "",
    ): Boolean {
        if (_stopped) return false
        if (!isMemoryEnabled()) return false
        return doRollingSummary(sessionId, messages, model, locale, timeZone, "backfill", assistantId)
    }

    /** v1.0.51: Informar del progreso del reabastecimiento(Para visualización de UI en tiempo real). */
    fun reportBackfillProgress(progress: BackfillProgress) {
        _backfillProgressFlow.value = progress
    }

    /** v1.0.51: Borrar el progreso de reabastecimiento(Se llama después de que se completa la migración.,UI La barra de progreso se oculta automáticamente). */
    fun clearBackfillProgress() {
        _backfillProgressFlow.value = null
    }

    /**
     * v0.45: Forzar la compilación inmediata de la memoria.(ignorar daily checkpoint,Llamado antes de la compresión manual.).
     *
     * con [tick] diferencia:
     *  - [tick] Protegido por control diario,Se omitirán los pasos completados durante el día.
     *  - [forceCompileNow] Ejecutar directamente de nuevo compileFacts + deepMemory + compileToday,
     *    usado para"Actualizar memoria y comprimir"escena,Asegúrese de que las últimas conversaciones se reduzcan a hechos antes de comprimir el historial.
     *
     * Los pasos fallidos se registran en health,sin interrumpir el flujo general.
     */
    suspend fun forceCompileNow(model: Model? = null, locale: String = "es", timeZone: String = TimeContext.DEFAULT_TIMEZONE) {
        if (_stopped) return
        if (!isMemoryEnabled()) return
        if (io.zer0.common.ProcessWriteGate.restoring) {
            Logger.d(TAG, "forceCompileNow: La restauración de la copia de seguridad está en progreso, omita esta compilación de memoria")
            return
        }
        // 1. repetición forzada compileFacts(Extraído del resumen de 30 días fact,Ignorar huellas dactilares fuera del caché checkpoint)
        // Revisión de corrección (B-6): incorporar _compileFactsLock,Mutuamente excluyentes con compileFacts de doDaily step4,
        // Evite que dos cadenas escriban simultáneamente el segmento FACTS y que el último escritor gane productos perdedores.
        // v1.78 (H2): La finalización de las llamadas suspendidas debe realizarse con resultOf,evitar tragar CancellationException
        val target = currentCompileTarget()
        _compileFactsLock.withLock {
            markStepStart("compileFacts", "force")
            resultOf {
                compiler.compileFacts(summaryManager, model, locale, runtimeContext.getConfig(), target = target)
            }.onSuccess {
                markSuccess("compileFacts")
                markStepRecovered("compileFacts(force)")
            }.onError { msg, t ->
                if (t != null) {
                    markFailure("compileFacts", t)
                    logStepError("compileFacts(force)", t)
                } else {
                    Logger.w(TAG, "compileFacts(force) falló: $msg")
                }
            }
        }
        // 2. repetición forzada deepMemory(Proceso dirty sessions,Extrae hechos profundos)
        if (io.zer0.common.ProcessWriteGate.restoring) return
        markStepStart("deepMemory", "force")
        resultOf {
            val result = deepProcessor.processDirtySessions(summaryManager, model, locale, runtimeContext.getConfig())
            if (result.processed > 0) {
                Logger.i(TAG, "forceCompileNow: deep-memory ${result.processed} session, ${result.factsAdded} nuevos hechos")
            }
            markSuccess("deepMemory")
            markStepRecovered("deepMemory(force)")
        }.onError { msg, t ->
            if (t != null) {
                markFailure("deepMemory", t)
                logStepError("deepMemory(force)", t)
            } else {
                Logger.w(TAG, "deepMemory(force) falló: $msg")
            }
        }
        // 3. Actualizar la memoria de compilación de hoy(Permita que el sistema utilice el contenido más reciente para la siguiente inyección)
        if (io.zer0.common.ProcessWriteGate.restoring) return
        doCompileTodayAndAssemble(model, locale, timeZone)
    }

    /**
     * Leer el compilado memory.md(Aviso de uso del sistema de inyección).
     * por [io.zer0.ai.ChatService] Llamado antes de enviar,Coloque la memoria a largo plazo en la sección del sistema.
     *
     * v0.32: Acceso [MemoryConfig.tokenBudget] —— usar [LlmBudget.truncateToTokenBudget]
     * Recorte suave al número de token objetivo,Evite los recuerdos largos que desplazan los presupuestos de diálogo(Configurar por memoria.token_budget).
     * El valor predeterminado de 2500 tokens es aproximadamente igual a 10 KB de texto.,Memoria diaria suficiente;Los usuarios pueden comprimir el mensaje ajustándolo más pequeño.
     */
    suspend fun readCompiledMemoryMarkdown(
        locale: String = "es",
        scope: String? = null,
        spaceId: String? = null,
        assistantId: String? = null,
    ): String {
        val currentTarget = currentCompileTarget()
        // In Muse every non-main memory scope belongs to the assistant with that id.
        // Prefer that scope over the background runtime's current assistant (usually `default`);
        // callers with a custom owner can still supply assistantId explicitly.
        val resolvedAssistantId =
            assistantId?.takeIf { it.isNotBlank() }
                ?: scope?.takeIf { it.isNotBlank() && it != "main" }
                ?: currentTarget.assistantId
        val target = currentTarget.copy(
            assistantId = resolvedAssistantId,
            scope = scope?.takeIf { it.isNotBlank() } ?: currentTarget.scope,
            spaceId = spaceId?.takeIf { it.isNotBlank() } ?: currentTarget.spaceId,
        )
        val md = compiler.readCompiledMemoryMarkdown(
            locale = locale,
            scope = target.normalizedScope,
            spaceId = target.normalizedSpaceId,
            target = target,
        )
        val cfg = runtimeContext.getConfig()
        return LlmBudget.truncateToTokenBudget(md, cfg.tokenBudget)
    }
}
