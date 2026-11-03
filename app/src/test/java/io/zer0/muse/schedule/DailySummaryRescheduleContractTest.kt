package io.zer0.muse.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrato de devolución del cronograma resumido diario.
 *
 * Estos dos contratos cubren la interfaz entre la tarea única persistente de WorkManager y la autorrenovación del Trabajador:
 *  - Después de modificar el período, las antiguas tareas por horas no pueden permanecer en el WorkManager para siempre;
 *  - Worker Después de descubrir que el usuario eliminó su hora, no podrá reprogramar las tareas anteriores para el día siguiente.
 *
 * Aquí primero utilizamos el contrato de código fuente para fijar los límites de comportamiento y evitar que las pruebas unitarias tengan que iniciar Android WorkManager;
 * Persistencia del WorkManager real/La reconstrucción del proceso aún requiere la aceptación posterior de la instrumentación.
 */
class DailySummaryRescheduleContractTest {

    private val source: String by lazy {
        File("src/main/java/io/zer0/muse/schedule/DailySummaryWorker.kt").readText()
    }
    private val settingsSource: String by lazy {
        File("src/main/java/io/zer0/muse/ui/settings/AgentSettingsPage.kt").readText()
    }

    @Test
    fun `rescheduling cleans hour-scoped unique work instead of only the base name`() {
        assertTrue(
            "scheduleNext must clean the per-hour unique work names when settings change",
            source.contains("cancelUniqueWork(uniqueWorkName("),
        )
    }

    @Test
    fun `worker does not renew a slot that is no longer configured`() {
        val start = source.indexOf("if (!shouldContinueForConfiguredSlot(slotHour, configuredSlots))")
        val end = source.indexOf("// Worker Comparte el mismo servicio con la compensación frontal de la página de inicio", start)
        check(start >= 0 && end > start) { "DailySummaryWorker stale-slot branch moved unexpectedly" }
        val staleBranch = source.substring(start, end)
        assertFalse(
            "an obsolete slot must stop without scheduling itself for tomorrow",
            staleBranch.contains("scheduleNextSlot("),
        )
    }

    @Test
    fun `worker keeps the current slot scheduled when slot settings cannot be read`() {
        val start = source.indexOf("if (configuredSlotsResult.isError)")
        val end = source.indexOf("val configuredSlots = slotsAfterRead(", start)
        check(start >= 0 && end > start) { "DailySummaryWorker slot-read failure branch moved unexpectedly" }
        val failureBranch = source.substring(start, end)
        assertTrue(
            "a transient settings read failure must re-enqueue the current slot",
            failureBranch.contains("scheduleNextSlot(applicationContext, slotHour, slotMinute"),
        )
    }

    @Test
    fun `scheduleNext reads slots before cancelling legacy work`() {
        val read = source.indexOf("val configuredSlots = resolvedConfiguredSlots()")
        val cancel = source.indexOf("cancelUniqueWork(UNIQUE_WORK_NAME)")
        assertTrue("settings must be read before legacy work is cancelled", read >= 0 && cancel > read)
    }

    @Test
    fun `settings save immediately re-registers daily summary work`() {
        val save = settingsSource.indexOf("settings.saveDailySummarySlots")
        val next = settingsSource.indexOf("DailySummaryWorker.scheduleNext(context)", save)
        assertTrue("settings save must be followed by schedule re-registration", save >= 0 && next > save)
    }
}
