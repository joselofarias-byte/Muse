package io.zer0.muse.schedule

import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.schedule.TaskIdName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Compensación para corredor:Cómo contar las rondas planificadas perdidas, dónde registrarlas y cuántas carreras de recuperación se requieren.
 *
 * acuerdo clave:Programación avanzada después de la ejecución de la tarea.**Anclando el último momento planeado**(antes de la ejecución next_run_at),
 * En lugar de empezar desde "ahora" —— Basado en ahora,La ronda perdida se omite permanentemente,
 * Y cada vez que llego tarde, presiono un poco más la próxima vez.,El reloj de pared sigue a la deriva.
 */
class ScheduledTaskMissedCatchUpTest {

    private val hour = 3_600_000L
    private val day = 86_400_000L

    // ── Avance del punto de anclaje ──────────────────────────────────────────────────────

    @Test
    fun `one missed round advances from last planned time not from now`() {
        // Tareas por horas,último planificado 09:00,El dispositivo se despierta a las 10:30:10:00 Esa ronda se perdió.
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + hour + 30 * 60_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("hourly", "", scheduledAt, now)

        assertEquals("La próxima hora sigue siendo la hora prevista originalmente.,No \"ahora\"+1 Horas", scheduledAt + 2 * hour, advance.nextRunAt)
        assertTrue("Contando desde ahora obtendremos 11:30,La descripción no está anclada", advance.nextRunAt != now + hour)
        assertEquals("10:00 esa ronda se saltó,acreditado 1 vez missed", 1, advance.missedCount)
    }

    @Test
    fun `five missed rounds run once and record four missed`() {
        // tareas diarias,último planificado T,Equipo T+4 genio levantado:T..T+4d Han pasado un total de 5 momentos previstos,
        // Esta vez solo compensaré la última ejecución.(T+4d),Las 4 rondas anteriores no se repiten, solo cuentan.
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 4 * day

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)

        assertEquals("Solo compensa la última ejecución:la próxima vez es T+5d", scheduledAt + 5 * day, advance.nextRunAt)
        assertTrue("No volverá a caducar inmediatamente después del maquillaje.", advance.nextRunAt > now)
        assertEquals("Las 4 rondas restantes se registran como missed", 4, advance.missedCount)
    }

    @Test
    fun `no miss keeps the same schedule as before`() {
        // Ejecutar a tiempo:now == Última hora programada,Es completamente coherente con el calibre de "contar de ahora en adelante".
        val scheduledAt = 1_700_000_000_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("hourly", "", scheduledAt, scheduledAt)

        assertEquals(0, advance.missedCount)
        assertEquals(
            "Así es, la próxima vez será consistente con el calibre original.",
            ScheduledTaskRunner.computeNextRun("hourly", "", scheduledAt),
            advance.nextRunAt,
        )
    }

    @Test
    fun `poll jitter does not drift the phase`() {
        // La encuesta se ejecuta con 60 segundos de retraso.:No perdido,La próxima vez seguirá siendo la hora del plan original.,
        // y "ahora+Ciclo" se diferencia como máximo en un ciclo de sondeo,No flotará hacia atrás ronda tras ronda.
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 60_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)

        assertEquals(0, advance.missedCount)
        assertEquals(scheduledAt + day, advance.nextRunAt)
        assertTrue(
            "La diferencia con el calibre calculado a partir de ahora no es más que un ciclo de votación.",
            ScheduledTaskRunner.computeNextRun("daily", "", now) - advance.nextRunAt <= 60_000L,
        )
    }

    @Test
    fun `once and unknown interval have no next run and no missed count`() {
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 10 * day

        val once = ScheduledTaskRunner.computeNextRunAnchored("once", "", scheduledAt, now)
        assertEquals("once Una vez ejecutado, no habrá próxima vez.", 0L, once.nextRunAt)
        assertEquals(0, once.missedCount)

        val unknown = ScheduledTaskRunner.computeNextRunAnchored("every-other-day", "", scheduledAt, now)
        assertEquals(0L, unknown.nextRunAt)
        assertEquals(0, unknown.missedCount)
    }

    @Test
    fun `manual run before the due time does not shift the schedule`() {
        // Tareas diarias a las 09:00,El próximo plan es mañana. 09:00;El usuario lo ejecuta manualmente a las 12:00 de hoy.
        // La próxima vez debe ser mañana. 09:00(mañana no 12:00)—— Ejecute manualmente una vez más sin cambiar el ritmo.
        val now = 1_700_000_000_000L
        val nextPlanned = now + 21 * hour

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", nextPlanned, now)

        assertEquals("La ejecución manual no avanza en el momento planificado", nextPlanned, advance.nextRunAt)
        assertEquals(0, advance.missedCount)
    }

    @Test
    fun `manual run after the due time keeps the wall clock too`() {
        // La misma tarea pero ha pasado el tiempo previsto.(09:00 Caducidad,12:00 Simplemente haga clic manualmente):Anclando el avance hacia el mañana 09:00,
        // Tampoco "Hoy 12:00 + 1 día",Tampoco es una carrera perdida.(Aún no es un ciclo completo).
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 3 * hour

        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)

        assertEquals(scheduledAt + day, advance.nextRunAt)
        assertEquals(0, advance.missedCount)
    }

    @Test
    fun `missing anchor falls back to from-now arithmetic`() {
        // Los datos antiguos no tienen momentos programados(next_run_at = 0):Según la lógica de la primera ejecución, contando a partir de ahora,No registres lo perdido de la nada.
        val now = 1_700_000_000_000L

        val advance = ScheduledTaskRunner.computeNextRunAnchored("weekly", "", 0L, now)

        assertEquals(now + 7 * day, advance.nextRunAt)
        assertEquals(0, advance.missedCount)
    }

    @Test
    fun `cron anchor counts skipped slots`() {
        // Todos los días a las 9:00 cron,último planificado 8/6 09:00,8/7 10:00 Solo corrí:8/7 09:00 Me perdí ese.
        val yesterdayNine = LocalDateTime.of(2026, 8, 6, 9, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val todayTen = LocalDateTime.of(2026, 8, 7, 10, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        val tomorrowNine = LocalDateTime.of(2026, 8, 8, 9, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val advance = ScheduledTaskRunner.computeNextRunAnchored("cron", "0 9 * * *", yesterdayNine, todayTen)

        assertEquals("cron El reloj de pared seguirá dando la hora la próxima vez.", tomorrowNine, advance.nextRunAt)
        assertEquals("8/7 09:00 Esa celda se registra como missed", 1, advance.missedCount)
    }

    @Test
    fun `cron without a miss is unchanged`() {
        // cron La propia expresión está anclada al reloj de pared.,Cuando se ejecuta a tiempo, los resultados son consistentes con el calibre original.
        val base = LocalDateTime.of(2026, 8, 6, 9, 0).atZone(ZoneId.systemDefault())
            .toInstant().toEpochMilli()

        val advance = ScheduledTaskRunner.computeNextRunAnchored("cron", "0 9 * * *", base, base)

        assertEquals(0, advance.missedCount)
        assertEquals(ScheduledTaskRunner.computeNextRun("cron", "0 9 * * *", base), advance.nextRunAt)
    }

    // ── missed Almacenamiento de registros ────────────────────────────────────────────────

    @Test
    fun `missed record is tagged with its own status`() {
        // La copia está detallada en Schedule_missed_summary de strings.xml(Mismo camino que éxito/fallo/omitido),
        // Aquí solo necesita confirmar que el registro en sí tiene un estado omitido y apunta a la tarea y la hora correctas.
        val now = 1_700_000_000_000L

        val record = ScheduledTaskRunner.missedExecutionEntity("task-1", "summary-from-resource", now)

        assertEquals("missed es un estado independiente,Empatado con éxito/fallido/omitido", "missed", record.status)
        assertEquals("task-1", record.taskId)
        assertEquals(now, record.executedAt)
        assertEquals("summary-from-resource", record.replySummary)
    }

    @Test
    fun `transaction records missed row and advances schedule once`() = runBlocking {
        // Maquillaje y devolución por única vez = 1 Este registro de ejecución + 1 registro de conteo perdido + next_run_at El momento después de volver al punto de anclaje.
        val scheduledAt = 1_700_000_000_000L
        val now = scheduledAt + 4 * day
        val advance = ScheduledTaskRunner.computeNextRunAnchored("daily", "", scheduledAt, now)
        val dao = FakeTaskDao(ScheduledTaskEntity(id = "task-1", name = "periódico diario", nextRunAt = scheduledAt))

        assertTrue("Recibido exitosamente", dao.claimTask("task-1", scheduledAt, now, Long.MAX_VALUE) == 1)
        dao.recordExecutionAndScheduleNext(
            execution = ScheduledTaskExecutionEntity(id = "exec-run", taskId = "task-1", executedAt = now),
            taskId = "task-1",
            nextRunAt = advance.nextRunAt,
            lastRunAt = now,
            retryCount = 0,
            missedExecutions = listOf(
                ScheduledTaskRunner.missedExecutionEntity("task-1", "missed-summary", now),
            ),
        )

        assertEquals("Dos historias de ejecución:Esta ejecución + conteo perdido", 2, dao.executions.size)
        assertEquals("missed", dao.executions.first().status)
        assertEquals("exec-run", dao.executions.last().id)
        assertEquals("next_run_at La próxima vez después de aterrizar en el punto de anclaje.", scheduledAt + 5 * day, dao.task.nextRunAt)
        assertTrue("La tarea permanece activa", dao.task.enabled)
        assertFalse("La ejecución de recuperación no hará que la próxima vez caduque inmediatamente.", dao.task.nextRunAt <= now)
    }

    /**
     * Un DAO falso que solo implementa la ruta de escritura utilizada en esta prueba.
     *
     * `recordExecutionAndScheduleNext` Se utiliza la implementación predeterminada en la interfaz.(@Transaction método de combinación),
     * Lo que se está probando aquí es que la lógica combinatoria:missed Los registros se eliminan junto con el avance next_run_at.
     */
    private class FakeTaskDao(initial: ScheduledTaskEntity) : ScheduledTaskDao {
        var task: ScheduledTaskEntity = initial
        val executions = mutableListOf<ScheduledTaskExecutionEntity>()

        override fun observeAll(): Flow<List<ScheduledTaskEntity>> = flowOf(listOf(task))

        override suspend fun getDueTasks(timeMillis: Long): List<ScheduledTaskEntity> =
            listOf(task).filter { it.enabled && it.nextRunAt <= timeMillis }

        override suspend fun getById(id: String): ScheduledTaskEntity? = task.takeIf { it.id == id }

        override suspend fun earliestNextRunAt(after: Long, sentinel: Long): Long? =
            listOf(task)
                .filter { it.enabled && it.nextRunAt > after && it.nextRunAt != sentinel }
                .minOfOrNull { it.nextRunAt }

        override suspend fun upsert(task: ScheduledTaskEntity) {
            this.task = task
        }

        override suspend fun delete(id: String) {
            task = task.copy(enabled = false)
        }

        override suspend fun claimTask(id: String, expectedNextRunAt: Long, claimedAt: Long, claimNextRun: Long): Int {
            if (task.id != id || task.nextRunAt != expectedNextRunAt) return 0
            task = task.copy(nextRunAt = claimNextRun, lastRunAt = claimedAt, updatedAt = claimedAt)
            return 1
        }

        override suspend fun setEnabled(id: String, enabled: Boolean, now: Long) {
            task = task.copy(enabled = enabled, updatedAt = now)
        }

        override suspend fun updateRunState(id: String, nextRunAt: Long, lastRunAt: Long) {
            task = task.copy(nextRunAt = nextRunAt, lastRunAt = lastRunAt)
        }

        override suspend fun recoverStuckClaim(sentinel: Long, staleBefore: Long, now: Long): Int = 0

        override suspend fun updateDedicatedSessionId(id: String, sessionId: String, now: Long) {
            task = task.copy(dedicatedSessionId = sessionId)
        }

        override suspend fun insertExecution(execution: ScheduledTaskExecutionEntity) {
            executions += execution
        }

        override suspend fun getAll(): List<ScheduledTaskEntity> = listOf(task)

        override suspend fun deleteAll() {
            task = task.copy(enabled = false)
        }

        override suspend fun triggerNextTasks(ids: List<String>, now: Long) = Unit

        override suspend fun updateRetryCount(id: String, retryCount: Int) {
            task = task.copy(retryCount = retryCount)
        }

        override suspend fun listChainCandidates(excludeId: String): List<TaskIdName> = emptyList()
    }
}
