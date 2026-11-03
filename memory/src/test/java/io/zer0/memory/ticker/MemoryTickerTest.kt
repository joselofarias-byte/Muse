package io.zer0.memory.ticker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.common.Logger
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.deep.DeepMemoryProcessor
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.DailyStateDao
import io.zer0.memory.summary.DailyStateEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.SessionSummaryDao
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.memory.summary.SessionSummaryManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2.3.3: [MemoryTicker] Pruebas unitarias.
 *
 * Cubrir la lógica de programación central:
 *  - lógica de guardia: stop / isMemoryEnabled=false Todas las API públicas regresan inmediatamente no-op
 *  - estado de salud: inicial [getHealthStatus] Contiene los 6 pasos con valores predeterminados.
 *  - start/stop ciclo de vida: start idempotente,stop Puedes reiniciar después(v1.78 reparado bug)
 *  - turn counting: Rondas acumulativas,Las tareas en segundo plano no se activarán si no se alcanza el umbral.
 *
 * descripción del diseño:
 *  - usa la verdad [SessionSummaryManager] / [MemoryCompiler] / [DeepMemoryProcessor] Ejemplo +
 *    fake Room DAO(Devolver datos vacíos)+ fake [MemoryLlmClient](El resultado de arrojar un error fue tragado).
 *  - Porque el DAO falso regresa vacío list/session,todos compile* / rolling / deep línea de montaje
 *    Estará allí"Entrada vacía"rama early return,en realidad no será llamado LLM,Por lo tanto, un error de lanzamiento de LLM falso no afectará la prueba.
 *  - No probar directamente los pasos específicos del proceso diario.,Porque la canalización diaria implica una gran cantidad de operaciones de IO asincrónicas,
 *    Dificultad para sincronizar completamente dentro de TestScope,Esta parte de la cobertura se deja para posteriores pruebas de integración.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryTickerTest {

    private lateinit var fakeDao: FakeSessionSummaryDao
    private lateinit var fakeCompiledDao: FakeCompiledSectionDao
    private lateinit var fakeDailyDao: FakeDailyStateDao
    private lateinit var fakeLlm: FakeMemoryLlmClient
    private lateinit var summaryManager: SessionSummaryManager
    private lateinit var compiler: MemoryCompiler
    private lateinit var deepProcessor: DeepMemoryProcessor
    private lateinit var factDbProvider: FactDbProvider

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        // inyectar Main dispatcher(Esta prueba se basa principalmente en la programación interna de runTest., setMain solo para evitar MemoryTicker
        // Excepción alternativa al utilizar Dispatchers.Main para algunas rutas internas)
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        factDbProvider = FactDbProvider(context)

        fakeDao = FakeSessionSummaryDao()
        fakeCompiledDao = FakeCompiledSectionDao()
        fakeDailyDao = FakeDailyStateDao()
        fakeLlm = FakeMemoryLlmClient()

        summaryManager = SessionSummaryManager(fakeDao, fakeLlm)
        // fileWriter = null dejar compileDaily/rollDailyWindow/assembleWeekFromDaily ir"Ninguno fileWriter"rama
        compiler = MemoryCompiler(fakeCompiledDao, fakeLlm, fileWriter = null)
        deepProcessor = DeepMemoryProcessor(factDbProvider, fakeLlm)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        factDbProvider.releaseAll()
    }

    // ──────────────────────────────────────────────
    //  lógica de guardia: stop / isMemoryEnabled=false Todas las API regresan temprano cuando
    // ──────────────────────────────────────────────

    @Test
    fun `notifyTurn when stopped does not launch background task`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.stop()
        advanceUntilIdle()
        val initialChildCount = coroutineContext[Job]?.children?.count() ?: 0

        ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        val afterChildCount = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop Después de notificar a Turn, no se debe iniciar una nueva tarea en segundo plano.", initialChildCount, afterChildCount)
    }

    @Test
    fun `notifyTurn when memory disabled does not launch background task`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { false })
        val initialChildCount = coroutineContext[Job]?.children?.count() ?: 0

        ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        val afterChildCount = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("disabled Cuando notifyTurn no debe iniciarse una nueva tarea en segundo plano", initialChildCount, afterChildCount)
    }

    @Test
    fun `notifySessionEnd when stopped returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.stop()
        advanceUntilIdle()

        // stop bloqueado notifyTurn, Entonces turnCounts está vacío → notifySessionEnd ir "count == 0" Regresar temprano
        val job = ticker.notifySessionEnd(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("stop Después de notificarSessionEnd, la devolución debe ser no-op Job(Completa ahora)", job.isCompleted)
    }

    @Test
    fun `notifySessionEnd when memory disabled returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { false })

        val job = ticker.notifySessionEnd(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("disabled cuando notifySessionEnd debería completarse inmediatamente", job.isCompleted)
    }

    @Test
    fun `flushSession when stopped returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.stop()
        advanceUntilIdle()

        val job = ticker.flushSession(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("stop FlushSession debería completarse inmediatamente después", job.isCompleted)
    }

    @Test
    fun `flushSession when memory disabled returns immediately completed job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { false })

        val job = ticker.flushSession(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        assertTrue("disabled FlushSession debería completarse inmediatamente.", job.isCompleted)
    }

    // ──────────────────────────────────────────────
    //  Consulta de estado de salud
    // ──────────────────────────────────────────────

    @Test
    fun `getHealthStatus initial state contains all steps with default values`() {
        // No es necesario runTest, Las consultas de estado son sincrónicas; scope solo para construccion Ticker
        val scope = TestScope(testDispatcher)
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })
        val health = ticker.getHealthStatus()

        // STEP_KEYS = rollingSummary + 5 un daily step = 6 un
        assertEquals("Debe contener 6 step", MemoryTicker.STEP_KEYS.size, health.size)

        MemoryTicker.STEP_KEYS.forEach { key ->
            assertTrue("debe contener step: $key", health.containsKey(key))
            val stepHealth = health[key]
            assertNotNull(stepHealth)
            assertEquals("El failCount inicial debe ser 0", 0, stepHealth!!.failCount)
            assertEquals("El último éxito inicial debe ser null", null, stepHealth.lastSuccessAt)
            assertEquals("El lastErrorAt inicial debe ser null", null, stepHealth.lastErrorAt)
            assertEquals("El último mensaje de error inicial debe ser null", null, stepHealth.lastErrorMsg)
        }
    }

    @Test
    fun `healthFlow initial value matches getHealthStatus after v1_0_51 fix`() {
        // v1.0.51: _healthFlow inicializado a _health.toMap(),con getHealthStatus() Sea consistente.
        val scope = TestScope(testDispatcher)
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })

        // healthFlow Inicialmente contiene 6 step (v1.0.51 Después de la reparación ya no está vacío. map)
        assertEquals("healthFlow Inicialmente debe contener 6 step", 6, ticker.healthFlow.value.size)

        // getHealthStatus También hay 6 step,Ambos son consistentes
        assertEquals("getHealthStatus debería haber 6 step", 6, ticker.getHealthStatus().size)
        assertEquals(
            "healthFlow Inicialmente debe ser coherente con getHealthStatus",
            ticker.getHealthStatus().keys,
            ticker.healthFlow.value.keys,
        )
    }

    // ──────────────────────────────────────────────
    //  start/stop ciclo de vida
    // ──────────────────────────────────────────────

    @Test
    fun `start is idempotent - second call does not create additional timer job`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })

        ticker.start()
        advanceUntilIdle()
        val childCountAfterFirstStart = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("start() 1 debe ser creado timer job", 1, childCountAfterFirstStart)

        ticker.start()
        advanceUntilIdle()
        val childCountAfterSecondStart = coroutineContext[Job]?.children?.count() ?: 0

        assertEquals("secundaria start() no debe ser creado de nuevo timer job", 1, childCountAfterSecondStart)

        ticker.stop()
        advanceUntilIdle()
    }

    @Test
    fun `ticker can be restarted after stop`() = runTest(testDispatcher) {
        // v1.78: Reparación stop() no se puede reiniciar después bug — start() restablecer cuando _stopped=false
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })

        ticker.start()
        advanceUntilIdle()
        val childCountAfterStart = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals(1, childCountAfterStart)

        ticker.stop()
        advanceUntilIdle()
        val childCountAfterStop = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop() Después de que el trabajo del temporizador debería haber finalizado", 0, childCountAfterStop)

        // Reiniciar
        ticker.start()
        advanceUntilIdle()
        val childCountAfterRestart = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop Deberías poder reiniciar después(No cree un nuevo trabajo de temporizador como bug)", 1, childCountAfterRestart)

        ticker.stop()
    }

    @Test
    fun `stop sets stopped flag preventing further notifyTurn from launching tasks`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        ticker.start()
        advanceUntilIdle()

        ticker.stop()
        advanceUntilIdle()

        // En este punto notifyTurn debería ser detenido por el guardia _stopped,No iniciar nuevas tareas
        val childCountBefore = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop No debe haber antecedentes timer", 0, childCountBefore)

        ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        advanceUntilIdle()

        val childCountAfter = coroutineContext[Job]?.children?.count() ?: 0
        assertEquals("stop No se deben iniciar nuevas tareas después de notificarTurn", 0, childCountAfter)
    }

    // ──────────────────────────────────────────────
    //  turn counting
    // ──────────────────────────────────────────────

    @Test
    fun `notifyTurn accumulates count under threshold without launching summary task`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })
        // Nota:no llamado start(),Evite que el trabajo del temporizador interfiera con las estadísticas de childCount

        // Notificado 9 veces(TURNS_PER_SUMMARY - 1),No alcanzar el umbral
        repeat(MemoryTicker.TURNS_PER_SUMMARY - 1) {
            ticker.notifyTurn(sessionId = "s1", messages = emptyList(), model = null)
        }
        advanceUntilIdle()

        // Porque el DAO falso regresa vacío, doDaily Se activará en checkDailyJob pero se agotará rápidamente.(datos vacíos)
        // Pero deepMemory/compileDaily, etc. fileWriter=null / Las sesiones vacías serán más rápidas return
        // verificación de clave: fakeLlm.callTextCount debería ser 0(RollingSummary no se activa porque no se alcanza el umbral)
        assertEquals(
            "No se debe llamar cuando no se ha alcanzado el umbral TURNS_PER_SUMMARY LLM",
            0,
            fakeLlm.callTextCount,
        )

        // cleanup
        ticker.stop()
        advanceUntilIdle()
    }

    @Test
    fun `notifyTurn at threshold triggers rollingSummary without throwing`() = runTest(testDispatcher) {
        val ticker = newTicker(scope = this, isMemoryEnabled = { true })

        // Notificar al umbral,gatillo rollingSummary
        repeat(MemoryTicker.TURNS_PER_SUMMARY) {
            ticker.notifyTurn(sessionId = "s2", messages = emptyList(), model = null)
        }
        advanceUntilIdle()

        // Porque los mensajes falsos están vacíos.,convText esta vacio,rollingSummary Regresará temprano sin ajuste LLM
        // compileToday También activado por doCompileTodayAndAssemble, sessions cuando esta vacio early return
        // verificar:Activar incluso si se alcanza el umbral,Un cuadro de diálogo vacío no debería generar una excepción y en realidad no debería llamarse LLM(callTextCount=0)
        assertEquals(
            "Cuando se alcanza el umbral pero el diálogo está vacío,rollingSummary ¿Debería el retorno anticipado no ajustarse? LLM",
            0,
            fakeLlm.callTextCount,
        )

        ticker.stop()
        advanceUntilIdle()
    }

    @Test
    fun `notifySessionEnd keeps the captured space when runtime space changes`() = runTest(testDispatcher) {
        val validSummary = """
            ### hechos importantes
            - El usuario establece una preferencia en la prueba.
            ### que paso
            - Los usuarios describieron esta preferencia durante las sesiones de prueba.
        """.trimIndent()
        fakeLlm.response = validSummary
        var runtimeSpace = "runtime-space"
        val ticker = MemoryTicker(
            summaryManager = summaryManager,
            compiler = compiler,
            deepProcessor = deepProcessor,
            dailyStateDao = fakeDailyDao,
            getResetAt = { null },
            isMemoryEnabled = { true },
            scope = this,
            runtimeContext = MemoryRuntimeContext(
                getConfig = { MemoryConfig() },
                getCurrentAssistantId = { "assistant-a" },
                getCurrentSpaceId = { runtimeSpace },
            ),
        )
        val messages = listOf(
            io.zer0.ai.core.UIMessage(
                role = io.zer0.ai.core.MessageRole.USER,
                content = "Por favor recuerda esta preferencia",
            ),
        )

        ticker.notifyTurn(
            sessionId = "space-capture-session",
            messages = messages,
            model = null,
            assistantId = "assistant-a",
            spaceId = "captured-space",
        )
        runtimeSpace = "new-runtime-space"

        val job = ticker.notifySessionEnd(
            sessionId = "space-capture-session",
            messages = messages,
            model = null,
            assistantId = "assistant-a",
        )
        job.join()

        assertEquals("captured-space", fakeDao.lastUpserted?.spaceId)
        ticker.stop()
    }

    @Test
    fun `daily state is keyed per target so switching space does not skip compile`() = runTest(testDispatcher) {
        // v2.6.2(auditoría de memoria): botón de progreso diario scope|space rama.
        // Implementación antigua arreglada key='default' + soltero _lastDailyJobDate —— del que se ha corrido Space A
        // Después de cambiar al Espacio B,B La compilación diaria se omitirá para el día actual.
        var runtimeSpace = "space-a"
        val ticker = MemoryTicker(
            summaryManager = summaryManager,
            compiler = compiler,
            deepProcessor = deepProcessor,
            dailyStateDao = fakeDailyDao,
            getResetAt = { null },
            isMemoryEnabled = { true },
            scope = this,
            runtimeContext = MemoryRuntimeContext(
                getConfig = { MemoryConfig() },
                getCurrentAssistantId = { "default" },
                getCurrentSpaceId = { runtimeSpace },
            ),
        )

        ticker.tick(model = null)
        advanceUntilIdle()
        val keyAfterA = fakeDailyDao.lastUpsertedKey

        runtimeSpace = "space-b"
        ticker.tick(model = null)
        advanceUntilIdle()
        val keyAfterB = fakeDailyDao.lastUpsertedKey

        assertTrue("Space A El progreso con el Espacio B debe almacenarse en líneas separadas", keyAfterA != null && keyAfterA != keyAfterB)
        assertTrue("target key Debe contener una bandera espacial", keyAfterA!!.endsWith("space-a") && keyAfterB!!.endsWith("space-b"))
        ticker.stop()
    }

    @Test
    fun `compiled memory uses the assistant-specific tombstones when scope is explicit`() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val assistantId = "ticker-scope-${System.nanoTime()}"
        val childStore = factDbProvider.getFactStore(assistantId)
        val fact = "deleted child-only preference"
        val factId = childStore.add(FactStore.Fact(fact = fact), scope = assistantId, spaceId = "work")
        assertTrue(childStore.delete(factId, assistantId, assistantId, "work"))

        val scopedMemoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            scopedMemoryDb.scopedCompiledSectionDao().upsert(
                ScopedCompiledSectionEntity(
                    sectionKey = MemoryCompiler.Section.FACTS.key,
                    scope = assistantId,
                    spaceId = "work",
                    content = fact,
                    updatedAt = java.time.Instant.now().toString(),
                ),
            )
            val scopedCompiler = MemoryCompiler(
                sectionDao = fakeCompiledDao,
                llmClient = fakeLlm,
                fileWriter = null,
                factStore = null,
                scopedSectionDao = scopedMemoryDb.scopedCompiledSectionDao(),
                factDbProvider = factDbProvider,
            )
            val ticker = MemoryTicker(
                summaryManager = summaryManager,
                compiler = scopedCompiler,
                deepProcessor = deepProcessor,
                dailyStateDao = fakeDailyDao,
                getResetAt = { null },
                isMemoryEnabled = { true },
                scope = this,
                runtimeContext = MemoryRuntimeContext(
                    getConfig = { MemoryConfig() },
                    getCurrentAssistantId = { "default" },
                    getCurrentSpaceId = { "default" },
                ),
                factStore = factDbProvider.getFactStore("default"),
            )

            val compiled = ticker.readCompiledMemoryMarkdown(scope = assistantId, spaceId = "work")

            assertFalse("a child assistant's tombstone must filter its own compiled facts", compiled.contains(fact))
        } finally {
            scopedMemoryDb.close()
            factDbProvider.release(assistantId)
            context.deleteDatabase("facts_$assistantId.db")
        }
    }

    // ──────────────────────────────────────────────
    //  start/stop Estado de la competencia:stop Llegó antes del cierre start
    // ──────────────────────────────────────────────

    @Test
    fun `start during stop is queued and exactly one timer survives`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })
        val warnings = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previousSink = Logger.sink
        Logger.sink = { level, tag, msg, _ ->
            if (level == "WARN" && tag == "MemoryTicker") warnings += msg
        }
        try {
            ticker.start()
            assertEquals("start Sólo se debe construir un temporizador job", 1, activeChildren(scope))

            // dejar stop() Realmente atrapado en "esperar a que finalice la tarea en segundo plano":LLM La llamada se cuelga en la puerta y no responde a la cancelación.
            val gate = CompletableDeferred<Unit>()
            fakeLlm.hangGate = gate
            ticker.flushSession(
                sessionId = "stop-window",
                messages = listOf(
                    io.zer0.ai.core.UIMessage(
                        role = io.zer0.ai.core.MessageRole.USER,
                        content = "Recuerda que estoy haciendo una prueba de carrera del programador de memoria.",
                    ),
                ),
                model = null,
            )
            awaitTrue { fakeLlm.callTextCount > 0 }

            val stopper = launch(Dispatchers.Default) { ticker.stop() }
            awaitTrue { ticker.timerPhaseName == "STOPPING" }
            assertFalse("El trabajo de cronometraje debería haberse cancelado durante el proceso de detención.", ticker.isTimerRunning())

            // La implementación anterior regresa directamente aquí y luego se detiene para vaciar el trabajo. → suspendido permanentemente
            ticker.start()
            assertEquals("No debes reiniciar en secreto mientras haces cola", "STOPPING", ticker.timerPhaseName)
            assertFalse(ticker.isTimerRunning())
            assertTrue(
                "\"Quiero que se ejecute pero no se está ejecutando\" debe salir de WARN, de hecho: $warnings",
                warnings.any { it.contains("cola") },
            )

            gate.complete(Unit)
            stopper.join()

            assertTrue("stop Deberá complementarse el inicio recibido durante este período.", ticker.isTimerRunning())
            assertEquals("Al final solo hay un momento job", 1, activeChildren(scope))
        } finally {
            Logger.sink = previousSink
            fakeLlm.hangGate = null
            ticker.stop()
            scope.cancel()
        }
    }

    @Test
    fun `stop right after start leaves no timer and no leaked job`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })
        try {
            ticker.start()
            assertEquals(1, activeChildren(scope))

            ticker.stop()

            assertFalse("stop El temporizador de publicación no debería estar funcionando.", ticker.isTimerRunning())
            assertEquals("stop No debería quedar tiempo restante después job", 0, activeChildren(scope))
            assertEquals("STOPPED", ticker.timerPhaseName)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `double start keeps one timer and double stop is harmless`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })
        try {
            ticker.start()
            ticker.start()
            assertEquals("Sólo debería haber un cronometraje para dos salidas consecutivas. job", 1, activeChildren(scope))
            assertTrue(ticker.isTimerRunning())

            // No se deben lanzar dos paradas simultáneas y el trabajo no se puede dejar afuera.
            val first = launch(Dispatchers.Default) { ticker.stop() }
            val second = launch(Dispatchers.Default) { ticker.stop() }
            first.join()
            second.join()

            assertFalse(ticker.isTimerRunning())
            assertEquals(0, activeChildren(scope))

            // Después de detenerse y detenerse nuevamente, no será arrojado ni revivido.
            ticker.stop()
            assertEquals("STOPPED", ticker.timerPhaseName)
            assertFalse(ticker.isTimerRunning())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `start on a dead scope warns instead of silently doing nothing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Simule que el alcance a nivel de proceso ha sido cancelado (por ejemplo, la aplicación está saliendo)
        scope.cancel()
        val ticker = newTicker(scope = scope, isMemoryEnabled = { true })
        val warnings = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previousSink = Logger.sink
        Logger.sink = { level, tag, msg, _ ->
            if (level == "WARN" && tag == "MemoryTicker") warnings += msg
        }
        try {
            ticker.start()

            assertFalse("scope Es imposible tener un cronómetro funcionando cuando estás muerto.", ticker.isTimerRunning())
            assertTrue(
                "Se debe emitir WARN cuando se inicia la solicitud pero al final no hay ningún temporizador. De hecho,: $warnings",
                warnings.any { it.contains("no pudo comenzar") },
            )
        } finally {
            Logger.sink = previousSink
        }
    }

    // ──────────────────────────────────────────────
    //  helpers
    // ──────────────────────────────────────────────

    /** El número de subtrabajos activos en un ámbito independiente se utiliza para afirmar \"cuántos trabajos de sincronización se están ejecutando\". */
    private fun activeChildren(scope: CoroutineScope): Int =
        scope.coroutineContext[Job]?.children?.count { it.isActive } ?: 0

    /** sondeo en tiempo real (runBlocking El retraso interior es la verdadera espera). */
    private suspend fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(5)
        }
    }

    private fun newTicker(scope: CoroutineScope, isMemoryEnabled: () -> Boolean): MemoryTicker {
        return MemoryTicker(
            summaryManager = summaryManager,
            compiler = compiler,
            deepProcessor = deepProcessor,
            dailyStateDao = fakeDailyDao,
            getResetAt = { null },
            isMemoryEnabled = isMemoryEnabled,
            scope = scope,
            runtimeContext = MemoryRuntimeContext(getConfig = { MemoryConfig() }),
        )
    }
}

// ──────────────────────────────────────────────
//  Fakes
// ──────────────────────────────────────────────

/** Fake [SessionSummaryDao]: Todo devuelve datos vacíos,Registre el número de llamadas para afirmarlo fácilmente. */
private class FakeSessionSummaryDao : SessionSummaryDao {
    @Volatile var lastUpserted: SessionSummaryEntity? = null
    override suspend fun upsert(entity: SessionSummaryEntity) {
        lastUpserted = entity
    }
    override suspend fun get(sessionId: String): SessionSummaryEntity? = null
    override suspend fun getAll(): List<SessionSummaryEntity> = emptyList()
    override suspend fun getInRange(
        startISO: String,
        endISO: String,
        since: String?,
        assistantId: String?,
        spaceId: String?,
    ): List<SessionSummaryEntity> = emptyList()
    override suspend fun getDirty(): List<SessionSummaryEntity> = emptyList()
    override suspend fun markProcessed(sessionId: String, now: String) {}
    override suspend fun deleteAll() {}
    override suspend fun deleteById(sessionId: String) {}
    // Cuando se elimina el asistente, se debe borrar el resumen bajo su nombre; no hay filas en los datos falsos vacíos que puedan eliminarse.
    override suspend fun deleteByAssistant(assistantId: String): Int = 0
}

/** Fake [CompiledSectionDao]: Almacenamiento de mapas de memoria,Implementación vacía. */
private class FakeCompiledSectionDao : CompiledSectionDao {
    private val store = mutableMapOf<String, CompiledSectionEntity>()
    override suspend fun upsert(entity: CompiledSectionEntity) {
        store[entity.sectionKey] = entity
    }
    override suspend fun get(key: String): CompiledSectionEntity? = store[key]
    override suspend fun getAll(): List<CompiledSectionEntity> = store.values.toList()
    override suspend fun updateContent(key: String, content: String, fingerprint: String?, now: String) {
        store[key] = CompiledSectionEntity(sectionKey = key, content = content, fingerprint = fingerprint, updatedAt = now)
    }
    override suspend fun clearAll(now: String) {
        store.keys.toList().forEach { k ->
            store[k] = CompiledSectionEntity(sectionKey = k, content = "", fingerprint = null, updatedAt = now)
        }
    }
    override suspend fun clearByKey(key: String, now: String) {
        store[key] = CompiledSectionEntity(sectionKey = key, content = "", fingerprint = null, updatedAt = now)
    }
    override suspend fun deleteAll() {
        store.clear()
    }
}

/** Fake [DailyStateDao]: Devuelve null para permitir que la canalización diaria se trate como"Continuar corriendo sin puntos de interrupción". */
private class FakeDailyStateDao : DailyStateDao {
    private val byKey = java.util.concurrent.ConcurrentHashMap<String, DailyStateEntity>()

    @Volatile var lastUpsertedKey: String? = null
    override suspend fun upsert(entity: DailyStateEntity) {
        byKey[entity.key] = entity
        lastUpsertedKey = entity.key
    }
    override suspend fun get(key: String): DailyStateEntity? = byKey[key]
    override suspend fun getAll(): List<DailyStateEntity> = byKey.values.toList()
    override suspend fun deleteAll() {
        byKey.clear()
    }
}

/** Fake [MemoryLlmClient]: Registrar el número de llamadas,Devolver cadena vacía(Incluso si se traga el error, el proceso no se verá afectado.). */
private class FakeMemoryLlmClient : MemoryLlmClient {
    @Volatile var callTextCount: Int = 0

    /**
     * Cuando no es nulo, callText se bloquea aquí y no responde a la cancelación. —— solía hacer "stop Ventana "Aún atascado esperando tareas en segundo plano".
     */
    @Volatile var hangGate: CompletableDeferred<Unit>? = null

    @Volatile var response: String = ""
    override suspend fun callText(
        systemPrompt: String,
        userContent: String,
        model: Model?,
        temperature: Float,
        maxTokens: Int,
        timeoutMs: Long,
    ): String {
        callTextCount++
        // Devuelve una cadena vacía y deja ir MemoryCompiler. "result esta vacio → SKIPPED" rama
        // De esta manera, doCompileTodayAndAssemble/doDaily se puede completar rápidamente
        hangGate?.let { gate -> withContext(NonCancellable) { gate.await() } }
        return response
    }
}
