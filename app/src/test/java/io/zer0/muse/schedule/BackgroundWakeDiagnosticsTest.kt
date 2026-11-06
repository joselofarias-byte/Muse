package io.zer0.muse.schedule

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba puramente lógica para el diagnóstico de activación en segundo plano:Mapeo de enumeración de estados, ensamblaje de entradas y ensamblaje de instantáneas.
 *
 * Ninguna de estas funciones toca la instancia de WorkManager.,Entonces puede ejecutarse en JVM pura.(no depende de Robolectric).
 * `WorkInfo.State` En sí misma es sólo una enumeración.,Simplemente tome su nombre y fije la "tabla de mapeo" a la enumeración real.
 */
class BackgroundWakeDiagnosticsTest {

    private fun snapshot(
        state: WorkInfo.State,
        nextRunAtMillis: Long? = null,
        runAttemptCount: Int = 0,
        stopReason: WakeStopReason = WakeStopReason.NOT_STOPPED,
        constrained: Boolean = false,
    ) = WakeWorkSnapshot(
        uuid = "uuid-${state.name}",
        stateName = state.name,
        nextRunAtMillis = nextRunAtMillis,
        runAttemptCount = runAttemptCount,
        stopReason = stopReason,
        constrained = constrained,
    )

    @Test
    fun `every WorkInfo State maps to a distinct wake state`() {
        val expected = mapOf(
            WorkInfo.State.ENQUEUED to WakeState.ENQUEUED,
            WorkInfo.State.RUNNING to WakeState.RUNNING,
            WorkInfo.State.BLOCKED to WakeState.BLOCKED,
            WorkInfo.State.SUCCEEDED to WakeState.SUCCEEDED,
            WorkInfo.State.FAILED to WakeState.FAILED,
            WorkInfo.State.CANCELLED to WakeState.CANCELLED,
        )
        // WorkInfo.State Cada valor de debe tener un mapeo.,Al agregar un nuevo estado del sistema, esto fallará primero.
        assertEquals(WorkInfo.State.values().toSet(), expected.keys)
        expected.forEach { (systemState, wakeState) ->
            assertEquals(
                "Estado del sistema ${systemState.name} mapeo",
                wakeState,
                BackgroundWakeDiagnostics.mapWakeState(systemState.name),
            )
        }
    }

    @Test
    fun `unknown and missing state names fall back instead of throwing`() {
        // null = Esta tarea no se puede encontrar en el sistema.(No registrado),Distinguirlo de "encontrarlo pero no reconocerlo"
        assertEquals(WakeState.NOT_REGISTERED, BackgroundWakeDiagnostics.mapWakeState(null))
        assertEquals(WakeState.UNKNOWN, BackgroundWakeDiagnostics.mapWakeState("SOMETHING_NEW"))
        assertEquals(WakeState.UNKNOWN, BackgroundWakeDiagnostics.mapWakeState("  "))
        // No distingue entre mayúsculas y minúsculas:Cuando la forma de serialización cambia en el lado del sistema, la pieza completa no caerá UNKNOWN
        assertEquals(WakeState.ENQUEUED, BackgroundWakeDiagnostics.mapWakeState("enqueued"))
    }

    @Test
    fun `empty work info list produces a not-registered entry instead of crashing`() {
        val entries = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.SCHEDULED_TASK,
            mapOf(ScheduledTaskWorker.UNIQUE_WORK_NAME to emptyList()),
        )
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(WakeState.NOT_REGISTERED, entry.state)
        assertEquals(ScheduledTaskWorker.UNIQUE_WORK_NAME, entry.uniqueName)
        assertNull(entry.nextRunAtMillis)
        assertEquals(0, entry.historyCount)
    }

    @Test
    fun `failed query is reported as unknown rather than not-registered`() {
        val entries = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.PROACTIVE,
            mapOf(io.zer0.muse.proactive.ProactiveWorker.UNIQUE_WORK_NAME to null),
        )
        assertEquals(WakeState.UNKNOWN, entries.single().state)
    }

    @Test
    fun `no candidate at all yields no entry`() {
        assertTrue(BackgroundWakeDiagnostics.toEntries(WakeWorkKind.PROACTIVE, emptyMap()).isEmpty())
    }

    @Test
    fun `current entry prefers live state over finished history`() {
        val snapshots = listOf(
            snapshot(WorkInfo.State.SUCCEEDED),
            snapshot(WorkInfo.State.ENQUEUED, nextRunAtMillis = 1_700_000_000_000L),
        )
        val entry = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.SCHEDULED_TASK,
            mapOf(ScheduledTaskWorker.UNIQUE_WORK_NAME to snapshots),
        ).single()
        assertEquals(WakeState.ENQUEUED, entry.state)
        assertEquals(1_700_000_000_000L, entry.nextRunAtMillis)
        // Se conserva el número de entradas históricas.,El usuario sólo sabe cuántas veces ha sido reconstruida esta obra única
        assertEquals(2, entry.historyCount)
    }

    @Test
    fun `finished-only chain reports the last finished state`() {
        // WorkManager Presione Enqueue para regresar a la cadena histórica de la misma obra única.,El ultimo es el actual.
        val snapshots = listOf(
            snapshot(WorkInfo.State.SUCCEEDED),
            snapshot(WorkInfo.State.FAILED, runAttemptCount = 3, stopReason = WakeStopReason.TIMEOUT),
        )
        val entry = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.SCHEDULED_TASK,
            mapOf(ScheduledTaskWorker.UNIQUE_WORK_NAME to snapshots),
        ).single()
        assertEquals(WakeState.FAILED, entry.state)
        assertEquals(3, entry.runAttemptCount)
        assertEquals(WakeStopReason.TIMEOUT, entry.stopReason)
        // El estado final no incluye el siguiente tiempo de ejecución.,Evite mostrar valores centinela como Long.MAX_VALUE como marcas de tiempo
        assertNull(entry.nextRunAtMillis)
    }

    @Test
    fun `daily summary only reports slots that exist in the system queue`() {
        val entries = BackgroundWakeDiagnostics.toEntries(
            WakeWorkKind.DAILY_SUMMARY,
            mapOf(
                DailySummaryWorker.uniqueWorkName(9, 0) to listOf(snapshot(WorkInfo.State.ENQUEUED, 1L)),
                DailySummaryWorker.uniqueWorkName(21, 0) to emptyList(),
            ),
        )
        assertEquals(1, entries.size)
        assertEquals(DailySummaryWorker.uniqueWorkName(9, 0), entries.single().uniqueName)
    }

    @Test
    fun `candidate names are non-empty for every kind`() {
        WakeWorkKind.values().forEach { kind ->
            assertTrue("$kind Ningún nombre de trabajo único del candidato", BackgroundWakeDiagnostics.candidateWorkNames(kind).isNotEmpty())
        }
        // El período de resumen diario se puede configurar a cualquier hora.,Los candidatos deben tener 24 años.
        assertEquals(24, BackgroundWakeDiagnostics.candidateWorkNames(WakeWorkKind.DAILY_SUMMARY).size)
    }

    @Test
    fun `snapshot text keeps state and names machine readable`() {
        val report = WakeDiagnostics(
            entries = listOf(
                WakeWorkEntry(
                    kind = WakeWorkKind.SCHEDULED_TASK,
                    uniqueName = ScheduledTaskWorker.UNIQUE_WORK_NAME,
                    state = WakeState.ENQUEUED,
                    nextRunAtMillis = 1_700_000_000_000L,
                    runAttemptCount = 2,
                    stopReason = WakeStopReason.APP_STANDBY,
                    constrained = true,
                    historyCount = 1,
                ),
            ),
            backgroundWorkEnabled = true,
            batteryOptimizationExempt = false,
            exportedAtMillis = 1_700_000_000_000L,
            appVersion = "2.6.10 (2610)",
            androidVersion = "15 (API 35)",
            device = "Google Pixel 7",
        )
        val text = BackgroundWakeDiagnostics.formatSnapshot(report)
        assertTrue(text.contains("background_work_enabled=true"))
        assertTrue(text.contains("battery_optimization_exempt=false"))
        assertTrue(text.contains("work_count=1"))
        assertTrue(text.contains("${ScheduledTaskWorker.UNIQUE_WORK_NAME} | kind=SCHEDULED_TASK | state=ENQUEUED"))
        assertTrue(text.contains("attempts=2"))
        assertTrue(text.contains("stop_reason=APP_STANDBY"))
    }

    @Test
    fun `snapshot marks a missing next run as none instead of a sentinel`() {
        // estado final/Las tareas no registradas no tienen tiempo de ejecución siguiente. Las instantáneas no deben contener 0 o Long.MAX_VALUE:
        // Las personas que lean las instantáneas las interpretarán como marcas de tiempo reales.
        val report = WakeDiagnostics(
            entries = listOf(
                WakeWorkEntry(
                    kind = WakeWorkKind.SCHEDULED_TASK,
                    uniqueName = ScheduledTaskWorker.UNIQUE_WORK_NAME,
                    state = WakeState.NOT_REGISTERED,
                    nextRunAtMillis = null,
                    runAttemptCount = 0,
                    stopReason = WakeStopReason.NOT_STOPPED,
                    constrained = false,
                    historyCount = 0,
                ),
            ),
            backgroundWorkEnabled = true,
            batteryOptimizationExempt = true,
            exportedAtMillis = 1_700_000_000_000L,
            appVersion = "2.6.10 (2610)",
            androidVersion = "15 (API 35)",
            device = "Google Pixel 7",
        )
        val text = BackgroundWakeDiagnostics.formatSnapshot(report)
        assertTrue("Los valores faltantes deben escribirse como next_run=none", text.contains("next_run=none"))
        assertFalse("Long.MAX_VALUE no se puede generar como marca de tiempo", text.contains(Long.MAX_VALUE.toString()))
        assertFalse("0 no se puede generar como marca de tiempo", text.contains("next_run=0\n"))
    }

    @Test
    fun `snapshot text of an empty report still renders`() {
        val report = WakeDiagnostics(
            entries = emptyList(),
            backgroundWorkEnabled = null,
            batteryOptimizationExempt = true,
            exportedAtMillis = 0L,
            appVersion = "0 (0)",
            androidVersion = "0 (API 0)",
            device = "unknown",
            error = "WorkManager not initialized",
        )
        val text = BackgroundWakeDiagnostics.formatSnapshot(report)
        assertTrue(text.contains("work_count=0"))
        assertTrue(text.contains("background_work_enabled=unknown"))
        assertTrue(text.contains("work_manager_error=WorkManager not initialized"))
    }
}
