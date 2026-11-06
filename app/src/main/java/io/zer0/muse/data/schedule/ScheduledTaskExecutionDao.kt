package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduledTaskExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(execution: ScheduledTaskExecutionEntity)

    /** El estado final de la ejecución más reciente de cada tarea.(LastResult se utiliza para entregar la lista de tareas.). */
    data class TaskLatestStatus(
        @ColumnInfo(name = "task_id") val taskId: String,
        @ColumnInfo(name = "status") val status: String,
    )

    /**
     * Obtenga el último estado de ejecución de todas las tareas a la vez,No revises artículo por artículo.
     *
     * Puede haber varios registros de ejecución al mismo tiempo.(La ejecución inmediata manual choca con el sondeo),Las subconsultas correlacionadas las sacarán todas;
     * Cuando la persona que llama archiva por taskId, este último sobrescribe al primero.,La diferencia de estatus no afecta la semántica de "más reciente".
     */
    @Query(
        """
        SELECT e.task_id AS task_id, e.status AS status
        FROM scheduled_task_executions e
        WHERE e.executed_at = (
            SELECT MAX(x.executed_at) FROM scheduled_task_executions x WHERE x.task_id = e.task_id
        )
        """,
    )
    suspend fun latestStatusByTask(): List<TaskLatestStatus>

    /** Consultar los últimos 10 registros de ejecución de una tarea.(En orden descendente de tiempo de ejecución.). */
    @Query("SELECT * FROM scheduled_task_executions WHERE task_id = :taskId ORDER BY executed_at DESC LIMIT 10")
    suspend fun queryByTaskId(taskId: String): List<ScheduledTaskExecutionEntity>

    /** U-25: Consultar todo el historial de ejecución de una tarea.(Máximo 100 artículos,suministro"Ver todo"Uso del diálogo). */
    @Query("SELECT * FROM scheduled_task_executions WHERE task_id = :taskId ORDER BY executed_at DESC LIMIT 100")
    suspend fun queryAllByTaskId(taskId: String): List<ScheduledTaskExecutionEntity>

    /** Borrar todo el historial de ejecución de una tarea. */
    @Query("DELETE FROM scheduled_task_executions WHERE task_id = :taskId")
    suspend fun deleteByTaskId(taskId: String)

    @Query("SELECT * FROM scheduled_task_executions")
    suspend fun getAll(): List<ScheduledTaskExecutionEntity>

    @Query("DELETE FROM scheduled_task_executions")
    suspend fun deleteAll()
}
