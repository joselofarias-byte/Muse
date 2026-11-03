package io.zer0.muse.web.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-20261011-A2A-PROTOCOL(.audit REPORT_DOCS_TESTS F2 / REPORT_SECURITY F2 / REPORT_AGENT F5):
 *
 * A2A Es la entrada externa insignia de la versión 2.5.8, pero el protocolo y el libro de tareas anteriores no se probaron en absoluto y tenían tres fallas de implementación:
 *  1) GetTask / CancelTask poner JSON-RPC **sobre id** Cuando se lee la identificación de la tarea (la especificación requiere params.id)——
 *     Un cliente A2A normal nunca podrá recuperar la tarea;
 *  2) CancelTask Ignorar taskId siempre chatViewModel.stop(), lo que puede interrumpir la generación que se está ejecutando en la recepción del usuario;
 *  3) El estado de la tarea no está diferenciado."Tareas desconocidas para este controlador"con"Tareas conocidas", incapaz de realizar la verificación de propiedad.
 *
 * Esta prueba bloquea la semántica de propiedad y estado del libro de tareas (lógica pura, no depende de Android/ViewModel).
 */
class A2aTaskRegistryTest {

    private fun task(id: String, state: String) = A2aTask(id = id, state = state)

    @Test
    fun `registry returns the recorded task by id`() {
        val registry = A2aTaskRegistry()
        registry.record(task("t1", A2aTaskState.WORKING))
        assertEquals(A2aTaskState.WORKING, registry.get("t1")?.state)
    }

    @Test
    fun `unknown task is not owned and returns null`() {
        val registry = A2aTaskRegistry()
        assertNull(registry.get("does-not-exist"))
        assertFalse(
            "Las tareas no registradas no se pueden reclamar,De lo contrario, CancelTask ​​detendrá accidentalmente la generación del propio usuario.",
            registry.isOwned("does-not-exist"),
        )
    }

    @Test
    fun `recorded task is owned`() {
        val registry = A2aTaskRegistry()
        registry.record(task("t1", A2aTaskState.WORKING))
        assertTrue(registry.isOwned("t1"))
    }

    @Test
    fun `registry is bounded and evicts the oldest`() {
        val registry = A2aTaskRegistry(maxEntries = 3)
        registry.record(task("t1", A2aTaskState.COMPLETED))
        registry.record(task("t2", A2aTaskState.COMPLETED))
        registry.record(task("t3", A2aTaskState.COMPLETED))
        registry.record(task("t4", A2aTaskState.COMPLETED))
        assertNull("La tarea más antigua debería ser desalojada.,Evite el crecimiento ilimitado del libro de tareas", registry.get("t1"))
        assertTrue(registry.isOwned("t4"))
        assertEquals(3, registry.size)
    }

    @Test
    fun `recording the same id twice updates in place`() {
        val registry = A2aTaskRegistry()
        registry.record(task("t1", A2aTaskState.WORKING))
        registry.record(task("t1", A2aTaskState.COMPLETED))
        assertEquals(A2aTaskState.COMPLETED, registry.get("t1")?.state)
        assertEquals(1, registry.size)
    }

    @Test
    fun `only working tasks are cancelable`() {
        val registry = A2aTaskRegistry()
        registry.record(task("done", A2aTaskState.COMPLETED))
        registry.record(task("running", A2aTaskState.WORKING))
        assertFalse("Las tareas completadas ya no deberían provocar la detención", registry.isCancelable("done"))
        assertTrue(registry.isCancelable("running"))
        assertFalse("Las tareas desconocidas no se pueden cancelar", registry.isCancelable("unknown"))
    }
}
