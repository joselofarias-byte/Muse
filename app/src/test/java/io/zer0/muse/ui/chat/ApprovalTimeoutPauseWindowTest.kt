package io.zer0.muse.ui.chat

import io.zer0.muse.tools.ToolApprovalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cuenta regresiva de aprobación congelada/Regresión temporal restaurada(Todo el reloj virtual runTest,No hay necesidad de esperar realmente).
 *
 * Fijar tres cosas:
 *  1. El tiempo no se mueve durante el período de congelación. —— Haz una pausa de 5 minutos y vuelve.,No habrá un tiempo muerto adicional de la nada.;
 *  2. Reanudar desde la duración restante —— Las piezas que hayan sido retiradas no serán devueltas.,Tampoco se reinicia a 30 segundos.;
 *  3. Procesado externamente durante la congelación(aprobar/Rechazo, aprobación y baja en el registro)Terminar inmediatamente,No quedan corrutinas pendientes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalTimeoutPauseWindowTest {

    @Test
    fun `foreground focused approval still times out after thirty seconds`() = runTest {
        val startedAt = testScheduler.currentTime
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = { ApprovalPause(folded = false, paused = false) },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        assertEquals("Siguen siendo los 30 segundos originales cuando no están congelados.", TIMEOUT_MS, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `paused window consumes no remaining time and resumes with the full remainder`() = runTest {
        var paused = true
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        advanceTimeBy(FIVE_MINUTES_MS)
        runCurrent()
        assertNull("El final del juego no debería ocurrir durante la congelación.", outcome)
        assertTrue("La corrutina aún debería estar esperando durante el período de congelación.", job.isActive)

        val resumedAt = testScheduler.currentTime
        paused = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        // Puede haber exactamente un período de exploración durante el período de congelación alineado durante la recuperación.,Entonces se permite un error en el tamaño del paso;La clave no se restablece a 30 segundos.
        val elapsedAfterResume = testScheduler.currentTime - resumedAt
        assertTrue("Después de la recuperación, no debe ser más corto que el tiempo restante original.($elapsedAfterResume)", elapsedAfterResume >= TIMEOUT_MS)
        assertTrue("Después de la restauración, solo debe quedar el tiempo restante original.,El error no supera una sección de detección.($elapsedAfterResume)", elapsedAfterResume <= TIMEOUT_MS + STEP_MS)
    }

    @Test
    fun `resumed countdown continues from the remaining seconds instead of restarting`() = runTest {
        var paused = false
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        // Aléjese primero en la recepción durante 20 segundos.,Dejar otros 5 minutos,Solo deberían quedar 10 segundos cuando regreses.
        advanceTimeBy(20_000L)
        runCurrent()
        assertNull(outcome)

        paused = true
        advanceTimeBy(FIVE_MINUTES_MS)
        runCurrent()
        assertNull(outcome)

        val resumedAt = testScheduler.currentTime
        paused = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        val elapsedAfterResume = testScheduler.currentTime - resumedAt
        val expectedRemaining = TIMEOUT_MS - 20_000L
        assertTrue("El tiempo restante después de la recuperación no debe ser menor que el tiempo invertido.($elapsedAfterResume)", elapsedAfterResume >= expectedRemaining)
        assertTrue("quedan 10 segundos,No se debe restablecer a 30 segundos.($elapsedAfterResume)", elapsedAfterResume <= expectedRemaining + STEP_MS)
    }

    @Test
    fun `approval resolved elsewhere during the pause settles immediately`() = runTest {
        var paused = true
        val deferred = CompletableDeferred<ToolApprovalState>()
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { deferred.await() } },
                    )
            }
        advanceTimeBy(3 * STEP_MS)
        runCurrent()
        assertNull(outcome)

        deferred.complete(ToolApprovalState.Denied("denied from another path"))
        advanceUntilIdle()
        assertEquals(
            ApprovalWaitOutcome.Resolved(ToolApprovalState.Denied("denied from another path")),
            outcome,
        )
        assertFalse("La corrutina no debe seguir esperando después del procesamiento externo.", job.isActive)
    }

    @Test
    fun `approval dropped from the registry during the pause is abandoned`() = runTest {
        var paused = true
        var registered = true
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = false, paused = paused) },
                        isStillRegistered = { registered },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        advanceTimeBy(3 * STEP_MS)
        runCurrent()
        assertNull(outcome)

        // Sesión eliminada/generación detenida:Esta aprobación ya no existe en el registro.,No habrá ninguna conclusión a la que regresar.
        registered = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.Abandoned, outcome)
        assertFalse("No deberías dejar corrutinas que nunca terminan.", job.isActive)
    }

    @Test
    fun `folded reading freezes without consuming the pause budget`() = runTest {
        var folded = true
        var outcome: ApprovalWaitOutcome? = null
        val job =
            launch {
                outcome =
                    awaitApprovalOutcome(
                        timeoutMs = TIMEOUT_MS,
                        pauseBudgetMs = PAUSE_BUDGET_MS,
                        pauseSignals = { ApprovalPause(folded = folded, paused = folded) },
                        isStillRegistered = { true },
                        awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
                    )
            }
        advanceTimeBy(PAUSE_BUDGET_MS * 2)
        runCurrent()
        assertNull("Plegar y leer es un comportamiento activo del usuario,No sujeto a presupuesto suspendido", outcome)
        assertTrue(job.isActive)

        folded = false
        advanceUntilIdle()
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
    }

    @Test
    fun `unfocused pause stops waiting once its budget is exhausted`() = runTest {
        val startedAt = testScheduler.currentTime
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = { ApprovalPause(folded = false, paused = true) },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals("Si el usuario nunca regresa, aún tienes que terminar el trabajo.", ApprovalWaitOutcome.TimedOut, outcome)
        assertEquals(PAUSE_BUDGET_MS, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `remote session nobody is watching still ends as a denial after the freeze window`() = runTest {
        // A2A / Generación de fondo:La sesión nunca fue mostrada al usuario.(displayedSessionId Todo el proceso no es así),El criterio de congelación siempre es cierto.
        // La ventana de tiempo se puede relajar hasta el límite de congelación.,La conclusión no se puede cambiar.:Al final, todavía se agotó el tiempo y se rechazó.,No liberado, no suspendido.
        val startedAt = testScheduler.currentTime
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = {
                    ApprovalPause(
                        folded = false,
                        paused =
                        shouldPauseApprovalTimeout(
                            foldedPaused = false,
                            displayedSessionId = "session-user-is-watching",
                            sessionId = "session-from-remote-caller",
                            appInForeground = true,
                        ),
                    )
                },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals("Debe rechazarse si nadie lo está revisando.,Es que el rechazo llegó demasiado tarde.", ApprovalWaitOutcome.TimedOut, outcome)
        assertFalse("No termines con la liberación.", outcome is ApprovalWaitOutcome.Resolved)
        assertEquals("Esperando la ventana de congelación,Ni 30 segundos ni para siempre.", PAUSE_BUDGET_MS, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `session left in the background still ends as a denial after the freeze window`() = runTest {
        // misma conversación(El enfoque no ha cambiado),Pero la aplicación pasa a un segundo plano. —— otro camino congelado,Asimismo, sólo amplía la ventana pero no cambia la conclusión.
        val outcome =
            awaitApprovalOutcome(
                timeoutMs = TIMEOUT_MS,
                pauseBudgetMs = PAUSE_BUDGET_MS,
                pauseSignals = {
                    ApprovalPause(
                        folded = false,
                        paused =
                        shouldPauseApprovalTimeout(
                            foldedPaused = false,
                            displayedSessionId = "s1",
                            sessionId = "s1",
                            appInForeground = false,
                        ),
                    )
                },
                isStillRegistered = { true },
                awaitOutcome = { slice -> withTimeoutOrNull(slice) { awaitCancellation() } },
            )
        assertEquals(ApprovalWaitOutcome.TimedOut, outcome)
        assertFalse("No termines con la liberación.", outcome is ApprovalWaitOutcome.Resolved)
    }

    @Test
    fun `backgrounded app freezes the countdown of the focused session`() {
        assertTrue(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s1",
                appInForeground = false,
            ),
        )
    }

    @Test
    fun `foreground focused session keeps counting down`() {
        assertFalse(
            shouldPauseApprovalTimeout(
                foldedPaused = false,
                displayedSessionId = "s1",
                sessionId = "s1",
                appInForeground = true,
            ),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val PAUSE_BUDGET_MS = 10 * 60_000L
        const val FIVE_MINUTES_MS = 5 * 60_000L
        const val STEP_MS = 200L
    }
}
