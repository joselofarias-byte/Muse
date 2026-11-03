package io.zer0.muse.ui.taskcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: Estado final agregado a la tarjeta de tarea.(CANCELLED / TIMED_OUT)Prueba de orientación.
 *
 * Bloquear:
 *  - El nuevo estado es el estado final.,Permite que finalice la tarjeta de tareas.;
 *  - Puede volver a intentarlo después del tiempo de espera. Si cancela, no se le pedirá que vuelva a intentarlo.;
 *  - El progreso todavía sólo se cuenta SUCCESS(La semántica existente permanece sin cambios.).
 */
class TaskCardTerminalStatusTest {

    private fun step(id: String, status: TaskStepStatus) = TaskStep(id = id, title = id, status = status)

    @Test
    fun `TIMED_OUT y CANCELADO son ambos estados finales`() {
        assertTrue(TaskStepStatus.SUCCESS.isTerminal)
        assertTrue(TaskStepStatus.FAILED.isTerminal)
        assertTrue(TaskStepStatus.TIMED_OUT.isTerminal)
        assertTrue(TaskStepStatus.CANCELLED.isTerminal)
        assertFalse(TaskStepStatus.PENDING.isTerminal)
        assertFalse(TaskStepStatus.RUNNING.isTerminal)
    }

    @Test
    fun `TIMED_OUT Se puede volver a intentar, pero CANCELADO no solicita activamente volver a intentarlo`() {
        assertTrue(TaskStepStatus.FAILED.isRetryable)
        assertTrue(TaskStepStatus.TIMED_OUT.isRetryable)
        assertFalse(TaskStepStatus.CANCELLED.isRetryable)
        assertFalse(TaskStepStatus.SUCCESS.isRetryable)
        assertFalse(TaskStepStatus.PENDING.isRetryable)
    }

    @Test
    fun `La tarjeta de tarea con el paso de tiempo de espera alcanza el estado final,Los tiempos de espera no están incluidos en el progreso`() {
        val card = TaskCardData(
            id = "c1",
            title = "t",
            steps = listOf(
                step("s0", TaskStepStatus.SUCCESS),
                step("s1", TaskStepStatus.TIMED_OUT),
            ),
        )
        assertTrue(card.isAllDone)
        assertTrue(card.hasFailedSteps)
        assertEquals(1, card.timedOutSteps)
        assertEquals(0, card.cancelledSteps)
        assertEquals(0.5f, card.progress, 0.001f)
    }

    @Test
    fun `Una tarjeta de tarea con un paso de cancelación llega al estado final y no tiene pasos de reintento`() {
        val card = TaskCardData(
            id = "c2",
            title = "t",
            steps = listOf(
                step("s0", TaskStepStatus.CANCELLED),
                step("s1", TaskStepStatus.CANCELLED),
            ),
        )
        assertTrue(card.isAllDone)
        assertFalse(card.hasFailedSteps)
        assertEquals(2, card.cancelledSteps)
        assertEquals(0, card.timedOutSteps)
        assertEquals(0f, card.progress, 0.001f)
    }

    @Test
    fun `Los pasos en ejecución no son estados finales`() {
        val card = TaskCardData(
            id = "c3",
            title = "t",
            steps = listOf(
                step("s0", TaskStepStatus.SUCCESS),
                step("s1", TaskStepStatus.RUNNING),
            ),
        )
        assertFalse(card.isAllDone)
    }

    @Test
    fun `Agregar nuevos valores a una enumeración no cambia el número de secuencia de los valores existentes`() {
        // persistencia/Barandilla de compatibilidad:El orden de los valores de enumeración existentes debe permanecer sin cambios.,Sólo se pueden agregar nuevos valores.
        assertEquals(0, TaskStepStatus.PENDING.ordinal)
        assertEquals(1, TaskStepStatus.RUNNING.ordinal)
        assertEquals(2, TaskStepStatus.SUCCESS.ordinal)
        assertEquals(3, TaskStepStatus.FAILED.ordinal)
        assertEquals(4, TaskStepStatus.CANCELLED.ordinal)
        assertEquals(5, TaskStepStatus.TIMED_OUT.ordinal)
    }
}
