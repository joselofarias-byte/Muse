package io.zer0.muse.session

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1.7: Active la prueba de la máquina de estado del punto de control mientras se ejecuta la sesión.
 *
 * Requisitos de aceptación de la cubierta:
 * - Al ingresar a la misma sesión varias veces se reutiliza el mismo tiempo de ejecución(ConversationSessionManager.getOrCreateRuntime).
 * - turn Verificar clic GENERATING -> WAITING_TOOL/WAITING_APPROVAL -> GENERATING -> Migración estatal final.
 * - No se permite ninguna migración adicional después del estado final.;Se reabre nuevo turno.
 * - limpieza inactiva cuando el recuento de referencias llega a cero y no se generan tareas;No limpiar cuando haya tareas generadas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRuntimeTest {

    // ── SessionRuntime máquina de estados ─────────────────────────────────────────

    @Test
    fun `beginTurn enters generating and records turn id`() {
        val runtime = SessionRuntime("s1")
        assertEquals(TurnPhase.NOT_STARTED, runtime.phase.value)
        runtime.beginTurn("t1")
        assertEquals("t1", runtime.currentTurnId.get())
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `tool execution cycles waiting tool back to generating`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markWaitingTool("t1")
        assertEquals(TurnPhase.WAITING_TOOL, runtime.phase.value)
        runtime.markResumed("t1")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `approval suspends and resolves back to generating`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markWaitingApproval("t1")
        assertEquals(TurnPhase.WAITING_APPROVAL, runtime.phase.value)
        runtime.markResumed("t1")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `terminal phase is sticky within the same turn`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markFinished(TurnPhase.COMPLETED, "t1")
        assertEquals(TurnPhase.COMPLETED, runtime.phase.value)
        // estado intermedio después del estado final/Las transiciones de estado finales deben ignorarse
        runtime.markWaitingTool("t1")
        runtime.markFinished(TurnPhase.FAILED, "t1")
        runtime.markFinished(TurnPhase.CANCELLED, "t1")
        assertEquals(TurnPhase.COMPLETED, runtime.phase.value)
    }

    @Test
    fun `new turn restarts from generating with fresh turn id`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markFinished(TurnPhase.CANCELLED, "t1")
        assertEquals(TurnPhase.CANCELLED, runtime.phase.value)
        runtime.beginTurn("t2")
        assertEquals("t2", runtime.currentTurnId.get())
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
        // Se borra la bandera de cancelación del nuevo turno.
        assertFalse(runtime.cancelRequested)
    }

    @Test
    fun `beginTurn clears cancelRequested from previous stop`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.requestCancel()
        assertTrue(runtime.cancelRequested)
        runtime.beginTurn("t2")
        assertFalse(runtime.cancelRequested)
    }

    @Test
    fun `mismatched turn id transitions are rejected`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.markFinished(TurnPhase.COMPLETED, "other-turn")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
        runtime.markWaitingTool("other-turn")
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `null or matching turn id transitions are accepted`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        // null = La persona que llama no sabe turn id(Como entrada de aprobación.),liberar
        runtime.markWaitingApproval(null)
        assertEquals(TurnPhase.WAITING_APPROVAL, runtime.phase.value)
        runtime.markResumed(null)
        assertEquals(TurnPhase.GENERATING, runtime.phase.value)
    }

    @Test
    fun `cancel requested is recorded`() {
        val runtime = SessionRuntime("s1")
        runtime.beginTurn("t1")
        runtime.requestCancel()
        assertTrue(runtime.cancelRequested)
    }

    @Test
    fun `resumable marks restored turns`() {
        val runtime = SessionRuntime("s1")
        runtime.markResumable("t1")
        assertEquals(TurnPhase.RESUMABLE, runtime.phase.value)
        assertEquals("t1", runtime.currentTurnId.get())
    }

    // ── ConversationSessionManager Reutilización del tiempo de ejecución y limpieza inactiva ─────────────

    @Test
    fun `same session reuses same runtime instance across acquire and release`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.acquire("s1")
        val first = manager.getOrCreateRuntime("s1")
        manager.release("s1")
        manager.acquire("s1")
        val second = manager.getOrCreateRuntime("s1")
        // Varias entradas con el mismo ID de sesión deben reutilizar la misma instancia de tiempo de ejecución.
        assertEquals(first, second)
        // release Después de la adquisición, el recuento de referencias vuelve a 1
        assertEquals(1, manager.getOrCreateRuntime("s1").refCount.get())
    }

    @Test
    fun `setGenerationJob creates runtime for never acquired session`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        assertNull(manager.runtime("group:chat-1"))
        val job = launch { delay(10_000) }
        manager.setGenerationJob("group:chat-1", job)
        // La identificación generada por el chat grupal no tomó la ruta de adquisición.,También debes ingresar a la gestión del tiempo de ejecución.
        assertTrue(manager.runtime("group:chat-1") != null)
        assertEquals(job, manager.runtime("group:chat-1")?.generationJob)
        job.cancelAndJoin()
    }

    @Test
    fun `setGenerationJob cancels previous job for same session`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val oldJob = launch { delay(10_000) }
        manager.setGenerationJob("s1", oldJob)
        val newJob = launch { delay(10_000) }
        manager.setGenerationJob("s1", newJob)
        assertTrue(oldJob.isCancelled)
        assertFalse(newJob.isCancelled)
        newJob.cancelAndJoin()
    }

    @Test
    fun `late completion of replaced job does not clear the new job`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        // Utilice NonCancellable para finalizar tareas antiguas y simular escenarios reales(La persistencia de las rutas de interrupción se ralentiza completion),
        // Haga que la devolución de llamada de finalización se ejecute después del registro del nuevo trabajo.
        val oldJob = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                delay(10_000)
            } finally {
                withContext(NonCancellable) { delay(100) }
            }
        }
        manager.setGenerationJob("s1", oldJob)
        val newJob = launch(start = CoroutineStart.UNDISPATCHED) { delay(60_000) }
        // setGenerationJob Cancelar tareas antiguas;Entran tareas antiguas cancelling,Aún no completado
        manager.setGenerationJob("s1", newJob)
        // Avanzando el tiempo virtual:Antigua tarea finalmente completada,Desencadenantes tardíos de invokeOnCompletion
        advanceTimeBy(10_200)
        // La devolución de llamada anterior no debe borrar la nueva referencia de trabajo.(De lo contrario, cancelGeneration no es válido,idle reaper Limpiar por error)
        assertTrue(manager.runtime("s1")?.generationJob === newJob)
        // No debe borrarse al estar inactivo cuando se ejecuta(Aún quedan nuevas tareas pendientes)
        advanceTimeBy(10_000)
        assertTrue(manager.runtime("s1") != null)
        newJob.cancelAndJoin()
        oldJob.join()
    }

    @Test
    fun `idle reaper removes runtime after timeout when no refs and no job`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.acquire("s1")
        manager.release("s1")
        // El tiempo virtual avanza más allá del tiempo de inactividad(5s)+ margen
        advanceTimeBy(10_000)
        assertNull(manager.runtime("s1"))
    }

    @Test
    fun `idle reaper keeps runtime while generation job is attached`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.acquire("s1")
        manager.release("s1")
        val job = launch { delay(60_000) }
        manager.setGenerationJob("s1", job)
        advanceTimeBy(10_000)
        // No limpiar cuando haya tareas de generación.
        assertTrue(manager.runtime("s1") != null)
        job.cancelAndJoin()
        // job Activar la programación inactiva después de la finalización,Luego adelanta el tiempo virtual para limpiar.
        advanceTimeBy(10_000)
        assertNull(manager.runtime("s1"))
    }

    @Test
    fun `beginTurn convenience entry creates runtime and starts turn`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        manager.beginTurn("s1", "t1")
        val runtime = manager.runtime("s1")
        assertTrue(runtime != null)
        assertEquals(TurnPhase.GENERATING, runtime?.phase?.value)
        assertEquals("t1", runtime?.currentTurnId?.get())
    }

    @Test
    fun `cancelGeneration records cancelRequested and cancels job`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val job = launch { delay(10_000) }
        manager.setGenerationJob("s1", job)
        manager.cancelGeneration("s1")
        withTimeout(2_000) {
            while (!job.isCancelled) delay(10)
        }
        assertTrue(manager.runtime("s1")?.cancelRequested == true)
    }

    @Test
    fun `cancelGeneration for one session does not affect another`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val jobA = launch { delay(60_000) }
        val jobB = launch { delay(60_000) }
        manager.setGenerationJob("sA", jobA)
        manager.setGenerationJob("sB", jobB)

        manager.cancelGeneration("sA")

        assertTrue(jobA.isCancelled)
        assertFalse(jobB.isCancelled)
        assertTrue(manager.runtime("sA")?.cancelRequested == true)
        assertTrue(manager.runtime("sB")?.cancelRequested == false)

        jobB.cancelAndJoin()
        jobA.join()
    }

    @Test
    fun `clearAll cancels all jobs and drops runtimes`() = runTest {
        val manager = ConversationSessionManager(backgroundScope)
        val job = launch { delay(10_000) }
        manager.setGenerationJob("s1", job)
        manager.acquire("s2")
        manager.clearAll()
        assertTrue(job.isCancelled)
        assertNull(manager.runtime("s1"))
        assertNull(manager.runtime("s2"))
    }
}
