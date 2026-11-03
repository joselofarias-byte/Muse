package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * P1-7: Entidad del historial de ejecución de tareas programadas.
 *
 * Insertar un registro después de cada ejecución de tarea programada,Se utiliza para mostrar el historial de ejecución y los motivos de falla en la interfaz de usuario.
 * - [id] clave primaria,cuerda(UUID),Ejecuciones múltiples garantizadas(Incluso el mismo milisegundo)Sin conflicto
 * - [taskId] Tareas programadas asociadas id(corresponder scheduled_tasks.id)
 * - [executedAt] Marca de tiempo de ejecución
 * - [status] Estado de ejecución: "success" / "failed"
 * - [replySummary] AI Responder contenido(la mayoría 10,000 palabra),Cadena vacía en caso de error
 * - [errorMessage] Mensaje de error en caso de falla(Cadena vacía en caso de éxito)
 */
@Entity(
    tableName = "scheduled_task_executions",
    indices = [Index(value = ["task_id"])],
)
@Serializable
data class ScheduledTaskExecutionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "executed_at") val executedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "success") val status: String = "success", // success / failed
    @ColumnInfo(name = "reply_summary", defaultValue = "") val replySummary: String = "",
    @ColumnInfo(name = "error_message", defaultValue = "") val errorMessage: String = "",
)
