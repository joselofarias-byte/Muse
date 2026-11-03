package io.zer0.muse.data.schedule

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * v1.137: Proyección ligera al seleccionar tareas encadenadas.
 */
data class TaskIdName(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
)

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY next_run_at ASC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND next_run_at <= :timeMillis ORDER BY next_run_at ASC LIMIT 10")
    suspend fun getDueTasks(timeMillis: Long): List<ScheduledTaskEntity>

    /**
     * next_run_at de la tarea más reciente que aún debe realizarse (se utiliza para impulsar el intervalo de sondeo a"justo a tiempo"en lugar de unos 60 fijos).
     *
     * excluir [sentinel](Consigue el centinela = Long.MAX_VALUE): eso es"Procesamiento"valor del marcador de posición,
     * Usarlo como hora de vencimiento nunca calculará la hora de despertarse.
     */
    @Query("SELECT MIN(next_run_at) FROM scheduled_tasks WHERE enabled = 1 AND next_run_at > :after AND next_run_at != :sentinel")
    suspend fun earliestNextRunAt(after: Long, sentinel: Long): Long?

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * B-12: Recopilar preventivamente las tareas pendientes(CAS,compare-and-set).
     *
     * Solo si next_run_at de la tarea sigue siendo igual al valor esperado leído por la persona que llama(Eso es todavía"No cobrado a su vencimiento")tiempo,
     * Luego el átomo next_run_at avanza a [claimNextRun](centinela,futuro lejano)para eliminarlo"Caducidad"identidad,
     * y escribe [claimedAt] como marca de colección,Devuelve el número de filas afectadas.
     * (1 = Este ejecutor recibió exitosamente;0 = Ya recogido por otros ejecutores primero.,debe omitirse).
     *
     * clave:Debe ser promovido simultáneamente. next_run_at(en lugar de simplemente escribir last_run_at),De lo contrario, los ejecutores B concurrentes
     * CAS todavía veo next_run_at == El valor de vencimiento original se recopila repetidamente.,Provoca llamadas/notificaciones de IA duplicadas.
     * Después de una recopilación exitosa, se llamará al proceso de ejecución al final. [recordExecutionAndScheduleNext] poner next_run_at
     * Restablecer al siguiente tiempo de ejecución real.next_run_at Período centinela temporal del futuro lejano,cualquier escaneo debido
     * ([getDueTasks])No alcanzará el objetivo si se recoge nuevamente.,Implementar intraproceso y entre procesos.(Worker)Seguridad de concurrencia.
     *
     * No contener deliberadamente restricciones habilitadas:sondeo/Worker Aprobado [getDueTasks] filtrar enabled=1;
     * UI "Ejecutar inmediatamente"Permitir la activación manual de tareas deshabilitadas una vez,Por lo tanto, no se habilita ninguna revisión al recibir.
     *
     * peligro:Si ocurre una excepción durante la ejecución, no se ejecuta el reinicio final.(Solo escenario de error de escritura de base de datos),La misión se detendrá en el valor centinela.
     * ya no dispara —— En comparación con la ejecución repetida,Una única pausa es la opción más segura(Prefiero perderlo una vez que repetir los efectos secundarios.).
     *
     * @param id Tarea id
     * @param expectedNextRunAt Instantánea Next_run_at leída por la persona que llama desde la base de datos(CAS valor esperado)
     * @param claimedAt Obtener marca de tiempo,Escribe last_run_at con updated_at
     * @param claimNextRun El valor centinela Next_run_at avanza temporalmente al recibir(futuro lejano),Pasado por la persona que llama
     */
    @Query(
        "UPDATE scheduled_tasks SET next_run_at = :claimNextRun, last_run_at = :claimedAt, updated_at = :claimedAt " +
            "WHERE id = :id AND next_run_at = :expectedNextRunAt",
    )
    suspend fun claimTask(id: String, expectedNextRunAt: Long, claimedAt: Long, claimNextRun: Long): Int

    @Query("UPDATE scheduled_tasks SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE scheduled_tasks SET next_run_at = :nextRunAt, last_run_at = :lastRunAt, updated_at = :lastRunAt WHERE id = :id")
    suspend fun updateRunState(id: String, nextRunAt: Long, lastRunAt: Long)

    /**
     * B-26: perro guardián centinela —— reciclar"Después de recibir la transacción, ésta falló y se atascó mientras recibía el valor centinela."tareas atrasadas.
     *
     * recibir([claimTask])Avanzará temporalmente next_run_at al centinela del futuro lejano para su eliminación."Caducidad"identidad;si después
     * [recordExecutionAndScheduleNext] Reversión de fallas de transacción,next_run_at Deténgase siempre en el valor centinela,escaneo de vencimiento
     * Nunca puedo golpear. poner aquí"next_run_at=Sentinel y update_at han superado el umbral"reinicio de tarea next_run_at
     * para now(y refrescar updated_at),Se reincorporará a la ejecución del vencimiento en la siguiente ronda de votación.
     *
     * Evitar reinicios accidentales:
     *  - updated_at < staleBefore filtrar:El update_at de la tarea que se está ejecutando es la hora que se acaba de recibir.,No superó el umbral
     *  - enabled = 1 filtrar:Evite resucitar tareas que el usuario haya desactivado manualmente
     *
     * @param sentinel Obtener valor centinela(Generalmente Long.MAX_VALUE)
     * @param staleBefore Marca de tiempo de determinación de vencimiento(now - umbral de vigilancia),Reciclar solo tareas no publicadas anteriormente
     * @param now Marca de tiempo de destino para restablecer
     * @return Número de filas afectadas(Número de tareas recicladas)
     */
    @Query(
        "UPDATE scheduled_tasks SET next_run_at = :now, updated_at = :now " +
            "WHERE next_run_at = :sentinel AND enabled = 1 AND updated_at < :staleBefore",
    )
    suspend fun recoverStuckClaim(sentinel: Long, staleBefore: Long, now: Long): Int

    /**
     * v1.134 P2-1: Sesión dedicada para tareas de actualización. ID(Modo de agregación de sesiones).
     *
     * Este método se llama después de crear una sesión dedicada en la primera ejecución para lograr persistencia.,Las ejecuciones posteriores reutilizan la misma sesión.
     */
    @Query("UPDATE scheduled_tasks SET dedicated_session_id = :sessionId, updated_at = :now WHERE id = :id")
    suspend fun updateDedicatedSessionId(id: String, sessionId: String, now: Long = System.currentTimeMillis())

    /**
     * H-SC1: Registre atómicamente el historial de ejecución y actualice el siguiente tiempo de ejecución.
     *
     * Coloque la inserción de ejecución y la actualización next_run_at/last_run_at en la misma transacción,
     * Evite ejecuciones repetidas causadas por escribir solo el historial pero no avanzar next_run_at después de un bloqueo.
     *
     * @param execution historial de ejecución
     * @param taskId Tarea id
     * @param nextRunAt Marca de tiempo de próxima ejecución;<=0 Indica que no habrá próxima vez(once/ilegal cron),La tarea estará deshabilitada
     * @param lastRunAt Esta marca de tiempo de ejecución(escribir last_run_at)
     * @param missedExecutions Esta vez no se realiza ninguna recuperación y solo se registra un historial perdido.(Calculado al fondear y avanzar);
     *   y registros de ejecución,next_run_at misma transacción escribir
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecution(execution: ScheduledTaskExecutionEntity)

    @Query("SELECT * FROM scheduled_tasks")
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Query("DELETE FROM scheduled_tasks")
    suspend fun deleteAll()

    /**
     * v1.137: Establecer el próximo tiempo de ejecución de un grupo de tareas en ahora,Activa tareas encadenadas que se ejecutarán en la siguiente ronda de votación.
     */
    @Query("UPDATE scheduled_tasks SET next_run_at = :now, updated_at = :now WHERE id IN (:ids)")
    suspend fun triggerNextTasks(ids: List<String>, now: Long = System.currentTimeMillis())

    /**
     * v1.0.17: Número de reintentos para actualizar la tarea(Estrategia de reintento de fallo de ejecución).
     */
    @Query("UPDATE scheduled_tasks SET retry_count = :retryCount WHERE id = :id")
    suspend fun updateRetryCount(id: String, retryCount: Int)

    /**
     * v1.137: La consulta se puede utilizar como candidata para tareas posteriores en la cadena.(Excluir la tarea actual en sí).
     */
    @Query("SELECT id, name FROM scheduled_tasks WHERE id != :excludeId ORDER BY name ASC")
    suspend fun listChainCandidates(excludeId: String): List<TaskIdName>

    @Transaction
    suspend fun recordExecutionAndScheduleNext(
        execution: ScheduledTaskExecutionEntity,
        taskId: String,
        nextRunAt: Long,
        lastRunAt: Long,
        retryCount: Int,
        missedExecutions: List<ScheduledTaskExecutionEntity> = emptyList(),
    ) {
        // Las rondas perdidas se eliminarán primero.:Avanzar la misma transacción que next_run_at,Se garantiza que no habrá ningún “faltado pero no adelantado el horario”.
        // Resultando en un conteo repetido para la siguiente ronda.
        missedExecutions.forEach { insertExecution(it) }
        insertExecution(execution)
        if (nextRunAt > 0) {
            updateRunState(taskId, nextRunAt, lastRunAt)
        } else {
            // No hay tiempo de próxima ejecución(once / ilegal cron),Deshabilite las tareas para evitar que se activen repetidamente cada minuto
            setEnabled(taskId, false, lastRunAt)
        }
        updateRetryCount(taskId, retryCount)
    }
}
