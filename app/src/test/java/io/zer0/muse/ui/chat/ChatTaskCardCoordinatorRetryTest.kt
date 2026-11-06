package io.zer0.muse.ui.chat

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import io.zer0.muse.ui.taskcard.TaskCardData
import io.zer0.muse.ui.taskcard.TaskCardPhase
import io.zer0.muse.ui.taskcard.TaskStep
import io.zer0.muse.ui.taskcard.TaskStepStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: Prueba de confiabilidad para reintentos fallidos de pasos de la tarjeta de tareas.
 *
 * Portada:
 *  - Reintentar reescritura del tiempo de espera [TaskStepStatus.TIMED_OUT](No más para siempre CORRER / Mal escrito FAILED);
 *  - Cancelar propagación:paso escribir de nuevo [TaskStepStatus.CANCELLED] no tragar CancellationException;
 *  - éxito/Si falla, se volverá a escribir según la semántica original.;
 *  - TIMED_OUT puede ser "ALL_FAILED" Inténtalo de nuevo,Y CANCELADO no está en el rango de reintentos.
 */
class ChatTaskCardCoordinatorRetryTest {

    private val registry: ToolRegistry = mockk(relaxed = true)

    private fun stubToolRegistered(name: String = "calculator") {
        every { registry.listTools() } returns listOf(
            ToolRegistry.ToolDef(
                name = name,
                description = "phase3 test tool",
                parameters = emptyMap(),
                // Entrada de marcador de posición pura (solo utilizada por la herramienta a la que pertenece el paso de análisis) y no realiza ninguna operación.
                riskLevel = ToolRiskLevel.SAFE,
            ),
        )
    }

    private fun cardWith(vararg statuses: Pair<String, TaskStepStatus>) = TaskCardData(
        id = CARD_ID,
        title = "Tarea",
        phase = TaskCardPhase.DONE,
        steps = statuses.map { (id, status) ->
            TaskStep(id = id, title = "calculator", detail = "{}", status = status, rawArgs = "{}")
        },
    )

    private fun accessorWithCard(card: TaskCardData, scope: CoroutineScope? = null): InMemoryChatStateAccessor {
        val accessor = InMemoryChatStateAccessor(scope = scope)
        accessor.update { it.copy(taskCards = mapOf(CARD_ID to card)) }
        return accessor
    }

    private fun stepOf(accessor: InMemoryChatStateAccessor, stepId: String): TaskStep? =
        accessor.snapshot.taskCards[CARD_ID]?.steps?.firstOrNull { it.id == stepId }

    /** El paso de sondeo y espera entra en el estado final esperado.(Reintentar la ejecución en una rutina independiente). */
    private suspend fun awaitStatus(accessor: InMemoryChatStateAccessor, stepId: String, expected: TaskStepStatus) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (stepOf(accessor, stepId)?.status != expected) {
                delay(10)
            }
        }
    }

    @Test
    fun `retry success writes SUCCESS and finishes the card`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "3" }
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        awaitStatus(accessor, "s0", TaskStepStatus.SUCCESS)

        assertEquals("3", stepOf(accessor, "s0")?.result)
        assertEquals(TaskCardPhase.DONE, accessor.snapshot.taskCards[CARD_ID]?.phase)
    }

    @Test
    fun `retry failure writes FAILED`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { """{"error":"boom"}""" }
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        awaitStatus(accessor, "s0", TaskStepStatus.FAILED)

        assertTrue(accessor.snapshot.taskCards[CARD_ID]?.isExpanded == true)
    }

    @Test
    fun `retry timeout writes TIMED_OUT terminal status`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            delay(5_000)
            "too late"
        }
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 50L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        awaitStatus(accessor, "s0", TaskStepStatus.TIMED_OUT)

        val step = stepOf(accessor, "s0")
        assertNotNull(step)
        assertTrue(step!!.result.startsWith("[tiempo de espera]"))
        assertEquals(TaskCardPhase.DONE, accessor.snapshot.taskCards[CARD_ID]?.phase)
    }

    @Test
    fun `retry cancellation marks step CANCELLED and propagates`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers {
            delay(30_000)
            "never"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.FAILED), scope = scope)
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 60_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        // Primero confirme que el reintento realmente haya comenzado.(RUNNING),Luego cancele todo el alcance.
        awaitStatus(accessor, "s0", TaskStepStatus.RUNNING)
        scope.cancel()

        // Los pasos posteriores a la cancelación deben ser: CANCELLED(en lugar de para siempre RUNNING,Ni FAILED)
        awaitStatus(accessor, "s0", TaskStepStatus.CANCELLED)
        assertTrue(stepOf(accessor, "s0")!!.result.startsWith("[Cancelar]"))
    }

    @Test
    fun `ALL_FAILED retries TIMED_OUT but skips CANCELLED steps`() = runBlocking {
        stubToolRegistered()
        coEvery { registry.executeFromJson("calculator", any()) } coAnswers { "ok" }
        val accessor = accessorWithCard(
            cardWith(
                "s0" to TaskStepStatus.TIMED_OUT,
                "s1" to TaskStepStatus.CANCELLED,
            ),
        )
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "ALL_FAILED")

        awaitStatus(accessor, "s0", TaskStepStatus.SUCCESS)
        // El paso cancelado no está en el conjunto reintentable,quédate como está
        assertEquals(TaskStepStatus.CANCELLED, stepOf(accessor, "s1")?.status)
    }

    @Test
    fun `non retryable status is ignored`() = runBlocking {
        stubToolRegistered()
        val accessor = accessorWithCard(cardWith("s0" to TaskStepStatus.SUCCESS))
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        delay(50)

        assertEquals(TaskStepStatus.SUCCESS, stepOf(accessor, "s0")?.status)
        io.mockk.coVerify(exactly = 0) { registry.executeFromJson(any<String>(), any<String>()) }
    }

    @Test
    fun `high risk tool is not auto retried`() = runBlocking {
        // v2.6.2(Auditoría de herramientas): El reintento automático no debe pasar por alto la aprobación y reenviar directamente herramientas de alto riesgo (send_/delete_/browser_/ui_).
        stubToolRegistered(name = "send_sms")
        coEvery { registry.executeFromJson("send_sms", any()) } coAnswers { "ok" }
        val card = TaskCardData(
            id = CARD_ID,
            title = "Tarea",
            phase = TaskCardPhase.DONE,
            steps = listOf(
                TaskStep(id = "s0", title = "send_sms", detail = "{}", status = TaskStepStatus.FAILED, rawArgs = "{}"),
            ),
        )
        val accessor = InMemoryChatStateAccessor()
        accessor.update { it.copy(taskCards = mapOf(CARD_ID to card)) }
        val coordinator = ChatTaskCardCoordinator(accessor, registry, retryTimeoutMs = 5_000L)

        coordinator.retryFailedStep(CARD_ID, "s0")
        delay(100)

        val step = stepOf(accessor, "s0")
        assertEquals(TaskStepStatus.FAILED, step?.status)
        assertTrue("Se le pedirá que vuelva a intentarlo manualmente.", step!!.result.contains("No lo vuelve a intentar automáticamente"))
        io.mockk.coVerify(exactly = 0) { registry.executeFromJson("send_sms", any<String>()) }
    }

    private companion object {
        const val CARD_ID = "card-1"
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}
