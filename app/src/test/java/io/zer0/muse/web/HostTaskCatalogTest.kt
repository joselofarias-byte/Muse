package io.zer0.muse.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.schedule.ScheduledTaskEntity
import io.zer0.muse.data.schedule.ScheduledTaskExecutionEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comando automatizado de solo lectura: True Room + True DAO, verifique que "tanto las tareas habilitadas como las deshabilitadas estén presentes" y "las tareas deshabilitadas no se proporcionarán la próxima vez".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HostTaskCatalogTest {

    private lateinit var database: MuseDb
    private lateinit var source: DaoHostTaskSource

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        source = DaoHostTaskSource(
            taskDao = database.scheduledTaskDao(),
            executionDao = database.scheduledTaskExecutionDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedTask(
        id: String,
        name: String,
        cron: String,
        enabled: Boolean,
        nextRunAt: Long,
        lastRunAt: Long = 0L,
    ) {
        database.scheduledTaskDao().upsert(
            ScheduledTaskEntity(
                id = id,
                name = name,
                cronExpr = cron,
                enabled = enabled,
                nextRunAt = nextRunAt,
                lastRunAt = lastRunAt,
                createdAt = 1L,
                updatedAt = 2L,
            ),
        )
    }

    private suspend fun seedExecution(id: String, taskId: String, executedAt: Long, status: String) {
        database.scheduledTaskExecutionDao().insert(
            ScheduledTaskExecutionEntity(id = id, taskId = taskId, executedAt = executedAt, status = status),
        )
    }

    @Test
    fun `tasks list keeps disabled tasks and drops their next run time`() = runTest {
        seedTask(
            id = "task-on",
            name = "sesión informativa matutina",
            cron = "0  9 * * *",
            enabled = true,
            nextRunAt = 5_000L,
            lastRunAt = 4_000L,
        )
        // La tarea desactivada aún conserva next_run_at antes de la desactivación en la biblioteca y el recibo debe hacer clic en ella.
        seedTask(
            id = "task-off",
            name = "informe semanal",
            cron = "0 20 * * 5",
            enabled = false,
            nextRunAt = 9_000L,
        )

        val event = HostTaskReads.listEvent(source.tasks(), requestId = "req-tasks")

        assertEquals(HostTaskReads.TYPE_LIST, event.type)
        assertEquals("req-tasks", event.requestId)
        assertNull(event.error)
        assertEquals(2, event.tasks.size)

        val enabledTask = event.tasks.first { it.id == "task-on" }
        assertEquals("sesión informativa matutina", enabledTask.name)
        // cron Envíalo como está sin normalización (mantén los espacios dobles juntos).
        assertEquals("0  9 * * *", enabledTask.cron)
        assertEquals(true, enabledTask.enabled)
        assertEquals(5_000L, enabledTask.nextRunAt)
        assertEquals(4_000L, enabledTask.lastRunAt)

        val disabledTask = event.tasks.first { it.id == "task-off" }
        assertEquals("informe semanal", disabledTask.name)
        assertEquals("0 20 * * 5", disabledTask.cron)
        assertEquals(false, disabledTask.enabled)
        assertNull(disabledTask.nextRunAt)
        assertNull(disabledTask.lastRunAt)
        assertNull(disabledTask.lastResult)
    }

    @Test
    fun `tasks list reports the latest execution status as last result`() = runTest {
        seedTask(id = "task", name = "Inspección", cron = "*/30 * * * *", enabled = true, nextRunAt = 7_000L, lastRunAt = 6_000L)
        seedExecution(id = "exec-old", taskId = "task", executedAt = 1_000L, status = "success")
        seedExecution(id = "exec-new", taskId = "task", executedAt = 3_000L, status = "failed")

        val task = source.tasks().single()

        assertEquals("failed", task.lastResult)
        assertNotNull(task.nextRunAt)
        assertEquals(6_000L, task.lastRunAt)
    }

    @Test
    fun `tasks list is an empty array when nothing is scheduled`() = runTest {
        val event = HostTaskReads.listEvent(source.tasks(), requestId = "req-empty")

        assertEquals(HostTaskReads.TYPE_LIST, event.type)
        assertNull(event.error)
        assertEquals(0, event.tasks.size)
    }
}
