package io.zer0.muse.ui.taskcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: AgentPlan Pruebas unitarias de clase de datos.
 *
 * Validar propiedades calculadas:completedSteps / failedSteps / progress / isAllDone.
 */
class AgentPlanTest {

    @Test
    fun `El progreso del paso vacío es 0 y isAllDone es false`() {
        val plan = AgentPlan(id = "p1", title = "test", steps = emptyList())
        assertEquals(0, plan.totalSteps)
        assertEquals(0, plan.completedSteps)
        assertEquals(0f, plan.progress, 0.001f)
        assertFalse(plan.isAllDone)
    }

    @Test
    fun `Todo el progreso PENDIENTE es 0`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "pasos1", status = AgentPlanStepStatus.PENDING),
                AgentPlanStep(id = "s1", title = "pasos2", status = AgentPlanStepStatus.PENDING),
                AgentPlanStep(id = "s2", title = "pasos3", status = AgentPlanStepStatus.PENDING),
            ),
        )
        assertEquals(3, plan.totalSteps)
        assertEquals(0, plan.completedSteps)
        assertEquals(0f, plan.progress, 0.001f)
        assertFalse(plan.isAllDone)
    }

    @Test
    fun `Progreso parcialmente completo correcto`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "pasos1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "pasos2", status = AgentPlanStepStatus.IN_PROGRESS),
                AgentPlanStep(id = "s2", title = "pasos3", status = AgentPlanStepStatus.PENDING),
            ),
        )
        assertEquals(1, plan.completedSteps)
        assertEquals(1, plan.inProgressSteps)
        assertEquals(0.333f, plan.progress, 0.01f)
        assertFalse(plan.isAllDone)
    }

    @Test
    fun `Todo hecho está. Todo hecho está. true`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "pasos1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "pasos2", status = AgentPlanStepStatus.DONE),
            ),
        )
        assertEquals(2, plan.completedSteps)
        assertEquals(1f, plan.progress, 0.001f)
        assertTrue(plan.isAllDone)
        assertEquals(0, plan.failedSteps)
    }

    @Test
    fun `Hay pasos fallidos y isAllDone todavía está true`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "pasos1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "pasos2", status = AgentPlanStepStatus.FAILED),
            ),
        )
        assertTrue(plan.isAllDone)
        assertEquals(1, plan.failedSteps)
        assertEquals(1, plan.completedSteps)
        assertTrue(plan.failedSteps > 0)
    }

    @Test
    fun `SKIPPED No se considera una culminación, ni tampoco un fracaso.`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "pasos1", status = AgentPlanStepStatus.DONE),
                AgentPlanStep(id = "s1", title = "pasos2", status = AgentPlanStepStatus.SKIPPED),
            ),
        )
        assertEquals(1, plan.completedSteps)
        assertEquals(0, plan.failedSteps)
        // SKIPPED Sin contar el ÉXITO, sin contar FAILED,Así debería ser AllDone true(DONE o FAILED)
        assertTrue(plan.isAllDone)
    }

    // ===== Phase 3: Agregar estado final CANCELLED / TIMED_OUT =====

    @Test
    fun `CANCELLED y TIMED_OUT también son estados finales,Los planes no duran para siempre`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "pasos1", status = AgentPlanStepStatus.CANCELLED),
                AgentPlanStep(id = "s1", title = "pasos2", status = AgentPlanStepStatus.TIMED_OUT),
            ),
        )
        assertTrue(plan.isAllDone)
        assertFalse(plan.isAllSucceeded)
        assertEquals(0, plan.completedSteps)
    }

    @Test
    fun `TIMED_OUT Incluido en el resumen de fallas,CANCELLED No contado`() {
        val plan = AgentPlan(
            id = "p1",
            title = "test",
            steps = listOf(
                AgentPlanStep(id = "s0", title = "pasos1", status = AgentPlanStepStatus.FAILED),
                AgentPlanStep(id = "s1", title = "pasos2", status = AgentPlanStepStatus.TIMED_OUT),
                AgentPlanStep(id = "s2", title = "pasos3", status = AgentPlanStepStatus.CANCELLED),
                AgentPlanStep(id = "s3", title = "pasos4", status = AgentPlanStepStatus.DONE),
            ),
        )
        assertEquals(2, plan.failedSteps)
        assertEquals(1, plan.completedSteps)
    }

    @Test
    fun `Cada estado tiene recursos de redacción independientes y distintos de cero.`() {
        val labelResIds = AgentPlanStepStatus.values().map { it.labelRes }
        assertEquals(labelResIds.size, labelResIds.distinct().size)
        assertTrue(labelResIds.all { it != 0 })
    }
}
