package io.zer0.muse.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Revisión de corrección (2.0) Prueba de soporte:
 * - B-33: resolve/fail Concurrencia sin perder entradas(CAS Actualizaciones atómicas)
 * - A-15: abort Después de resolver/fallar rechaza la recarga + cancelar cancela el registro Job
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeferredResultStoreTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── A-15: abort Semántica ────────────────────────────────────────────

    @Test
    fun `abort Rechazar después de resolver`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.abort("t1")
        store.resolve("t1", "resultado")

        assertEquals(0, store.consumeCompleted("s1").size)
        assertEquals(DeferredResultStore.TaskStatus.ABORTED, store.getTask("t1")?.status)
    }

    @Test
    fun `abort Rechazar la recarga después de fallar`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.abort("t1")
        store.fail("t1", "err")

        assertEquals(0, store.consumeCompleted("s1").size)
        assertEquals(DeferredResultStore.TaskStatus.ABORTED, store.getTask("t1")?.status)
    }

    @Test
    fun `abort Cancelar fondo de registro Job`() = runTest {
        val store = DeferredResultStore()
        var cancelled = false
        val job = Job()
        job.invokeOnCompletion { cancelled = true }
        store.attachJob("t1", job)
        store.abort("t1")

        assertTrue("abort Backstage que debe ser cancelado Job", cancelled)
    }

    @Test
    fun `La tarea no se aborta resolver recarga normal`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.resolve("t1", "resultado")

        val consumed = store.consumeCompleted("s1")
        assertEquals(1, consumed.size)
        assertEquals("resultado", consumed[0].result)
        assertEquals(DeferredResultStore.TaskStatus.RESOLVED, consumed[0].status)
    }

    // ── B-33: Atomicidad de concurrencia ────────────────────────────────────────────

    @Test
    fun `Resuelva simultáneamente múltiples tareas sin perder entradas`() = runTest {
        val store = DeferredResultStore()
        repeat(50) { i -> store.defer("t$i", "s1", null, null, "task$i") }

        // Simular la finalización simultánea de múltiples subtareas:Todos en sus respectivas corrutinas. resolve
        val jobs = (0 until 50).map { i ->
            launch(Dispatchers.IO) { store.resolve("t$i", "result$i") }
        }
        jobs.forEach { it.join() }

        val consumed = store.consumeCompleted("s1")
        assertEquals("Todas las entradas deben devolverse después de una resolución simultánea", 50, consumed.size)
        assertEquals(
            "El contenido del resultado no debe perderse.",
            (0 until 50).map { "result$it" }.toSet(),
            consumed.map { it.result }.toSet(),
        )
    }

    @Test
    fun `Las tareas abortadas no aparecen cuando la resolución concurrente compite con la cancelación`() = runTest {
        val store = DeferredResultStore()
        repeat(20) { i -> store.defer("t$i", "s1", null, null, "task$i") }

        val jobs = (0 until 20).map { i ->
            launch(Dispatchers.IO) {
                if (i % 2 == 0) {
                    store.abort("t$i")
                } else {
                    store.resolve("t$i", "r$i")
                }
            }
        }
        jobs.forEach { it.join() }

        val consumed = store.consumeCompleted("s1")
        // Solo tareas impares(no abortado)Se debe recargar
        assertEquals(10, consumed.size)
        assertTrue(consumed.all { it.status == DeferredResultStore.TaskStatus.RESOLVED })
        assertFalse(consumed.any { it.taskId.removePrefix("t").toInt() % 2 == 0 })
    }

    @Test
    fun `consumeCompleted Sin residuos después del consumo`() = runTest {
        val store = DeferredResultStore()
        store.defer("t1", "s1", null, null, "task")
        store.resolve("t1", "r")
        store.consumeCompleted("s1")
        assertEquals(0, store.consumeCompleted("s1").size)
    }
}
