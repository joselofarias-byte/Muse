package io.zer0.muse.web

import io.zer0.muse.data.schedule.ScheduledTaskDao
import io.zer0.muse.data.schedule.ScheduledTaskExecutionDao
import kotlinx.coroutines.flow.first

/**
 * Comandos automatizados de solo lectura(`tasks.list`)proyección de carga.
 *
 * Sólo hay una lista de tareas programadas.,La página web y la página de tareas programadas de la aplicación ven el mismo lote de filas y la misma clasificación
 * (ver [ScheduledTaskDao.observeAll]),No se abrirán criterios de consulta adicionales.
 */
internal object HostTaskReads {

    const val TYPE_LIST = "tasks.list"

    /** `tasks.list` Acuse de recibo. Se envía una matriz vacía cuando no hay tareas. */
    fun listEvent(tasks: List<HostScheduledTask>, requestId: String?): HostEvent = HostEvent(
        type = TYPE_LIST,
        requestId = requestId,
        tasks = tasks,
    )

    /**
     * El próximo tiempo de ejecución de una tarea.
     *
     * Desactivar una tarea no da la próxima vez:curry `next_run_at` es el valor anterior que quedaba antes de la desactivación,Emitido tal cual
     * La página web muestra "tareas cerradas" como "a qué hora y minutos se ejecutará".
     */
    fun nextRunAt(enabled: Boolean, nextRunAt: Long): Long? = nextRunAt.takeIf { enabled && it > 0 }

    /** Última hora de ejecución;0 significa que nunca fue ejecutado. */
    fun lastRunAt(lastRunAt: Long): Long? = lastRunAt.takeIf { it > 0 }
}

/**
 * Fuente de datos para comandos automatizados de solo lectura.
 *
 * La interfaz de comisión permite que la puerta de enlace reciba recibos como de costumbre, incluso en escenarios donde no hay un almacenamiento de tareas programado.(lista vacía).
 */
internal interface HostTaskSource {

    /** Lista de tareas programadas. */
    suspend fun tasks(): List<HostScheduledTask>

    /** Implementación vacía cuando no está ensamblada:No hay tareas. */
    object Empty : HostTaskSource {
        override suspend fun tasks(): List<HostScheduledTask> = emptyList()
    }
}

/**
 * Lea directamente las fuentes de datos de las dos tablas de tareas programadas.
 *
 * Ejecución de tareas [ScheduledTaskDao.observeAll] una instantánea de(La misma consulta y la misma clasificación que la página de tareas programadas de la aplicación);
 * Obtenga el último estado de ejecución [ScheduledTaskExecutionDao.latestStatusByTask],Tome toda la tabla de una vez en lugar de revisarla elemento por elemento.
 */
internal class DaoHostTaskSource(
    private val taskDao: ScheduledTaskDao,
    private val executionDao: ScheduledTaskExecutionDao,
) : HostTaskSource {

    override suspend fun tasks(): List<HostScheduledTask> {
        val lastResults = executionDao.latestStatusByTask().associate { it.taskId to it.status }
        return taskDao.observeAll().first().map { task ->
            HostScheduledTask(
                id = task.id,
                name = task.name,
                cron = task.cronExpr,
                enabled = task.enabled,
                nextRunAt = HostTaskReads.nextRunAt(task.enabled, task.nextRunAt),
                lastRunAt = HostTaskReads.lastRunAt(task.lastRunAt),
                lastResult = lastResults[task.id],
            )
        }
    }
}
