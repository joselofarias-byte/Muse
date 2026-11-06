package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "scheduled_tasks",
    indices = [
        // M-SC1: Anular getDueTasks WHERE enabled AND next_run_at <= ? ORDER BY next_run_at Consulta
        Index(value = ["enabled", "next_run_at"]),
    ],
)
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val prompt: String = "",
    @ColumnInfo(name = "assistant_id", defaultValue = "default") val assistantId: String = "default",
    @ColumnInfo(defaultValue = "daily") val interval: String = "daily", // once / hourly / daily / weekly / cron
    @ColumnInfo(name = "cron_expr", defaultValue = "") val cronExpr: String = "",
    @ColumnInfo(name = "enabled", defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(name = "next_run_at", defaultValue = "0") val nextRunAt: Long = 0,
    @ColumnInfo(name = "last_run_at", defaultValue = "0") val lastRunAt: Long = 0,
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    /**
     * v1.134 P2-1: sesión privada ID(Modo de agregación de sesiones).
     *
     * Cadena vacía significa no habilitado;Cree una sesión privada y escriba en este campo en la primera ejecución,Reutilizar la misma sesión para ejecuciones posteriores.,
     * Evite tareas diarias de larga duración que generen una nueva sesión todos los días.
     */
    @ColumnInfo(name = "dedicated_session_id", defaultValue = "") val dedicatedSessionId: String = "",
    /**
     * v1.137: Condiciones de automatización JSON(representación de cadena vacía always).
     * apoyo:always / network_available / time_range / contains / quick_note_exists.
     */
    @ColumnInfo(name = "condition_json", defaultValue = "") val conditionJson: String = "",
    /**
     * v1.137: tipo de acción(ai_prompt / create_quick_note / call_tool / notify).
     * Predeterminado ai_prompt,Mantenga la compatibilidad con versiones anteriores de tareas programadas.
     */
    @ColumnInfo(name = "action_type", defaultValue = "ai_prompt") val actionType: String = "ai_prompt",
    /**
     * v1.137: Configuración de acción JSON(Los parámetros específicos varían con action_type).
     */
    @ColumnInfo(name = "action_config_json", defaultValue = "") val actionConfigJson: String = "",
    /**
     * v1.137: Lista de ID de tareas encadenadas JSON. Después de que esta tarea se ejecute con éxito,Estas tareas serán next_run_at
     * Establecer la hora actual,Activa tareas de cadena posteriores que se ejecutarán en la siguiente ronda de sondeo.
     */
    @ColumnInfo(name = "next_task_ids_json", defaultValue = "") val nextTaskIdsJson: String = "",
    /**
     * v1.137: tarea de los padres ID(Utilizado para el seguimiento de tareas en cadena.).
     */
    @ColumnInfo(name = "parent_task_id", defaultValue = "") val parentTaskId: String = "",
    /**
     * v1.0.17: Fuente de creación de tareas("user"=Usuario creado a través de la interfaz de usuario, "assistant"=Asistentes creados a través de herramientas.).
     */
    @ColumnInfo(name = "created_by", defaultValue = "user") val createdBy: String = "user",
    /**
     * v1.0.17: Número actual de reintentos(Incremento después del fallo de ejecución,Restablecer después del éxito o alcanzar el límite superior 0).
     */
    @ColumnInfo(name = "retry_count", defaultValue = "0") val retryCount: Int = 0,
    /**
     * v1.0.17: Número máximo de reintentos(No vuelvas a intentarlo después de alcanzar,Continúe normalmente hasta el siguiente horario planificado.).
     */
    @ColumnInfo(name = "max_retries", defaultValue = "3") val maxRetries: Int = 3,
)
