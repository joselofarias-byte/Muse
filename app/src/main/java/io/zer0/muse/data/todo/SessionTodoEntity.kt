package io.zer0.muse.data.todo

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Sesión de tareas pendientes (persistencia).
 *
 * ## ¿Por qué necesitamos esta mesa?
 *
 * Punto 2 de autoevaluación del asistente: "No existe un mecanismo de circuito cerrado para las tareas pendientes entre sesiones. Por ejemplo, asuntos de jugadores, asuntos de subcontratación,
 * Después de unos días de secado, o lo olvido o solo pienso en él cuando lo presiono. Lo que falta es un "Libro mayor de temas inacabados" que pueda recordárselo de forma proactiva. "
 *
 * Anteriormente la tarea pendiente sólo existía `TodoTool.sessionTodos`(ConcurrentHashMap en proceso):
 * Todas las sesiones se borran, los procesos se reinician y se pierden y no se pueden ver otras sesiones. Entonces "lo escribí" es una frase vacía.
 *
 * La tabla almacena una fila por ID de sesión (la lista completa en JSON), en lugar de una fila por tarea pendiente:
 * - `todo_write` La semántica es "reemplazo completo". Si guarda por fila, deberá hacer una diferencia cada vez que escriba;
 * - El número de artículos del inventario es pequeño (generalmente < 20),JSON Una columna es suficiente;
 * - No se introduce ninguna nueva relación de uno a muchos y la migración y la reversión son sencillas.
 */
@Serializable
@Entity(
    tableName = "session_todos",
    indices = [Index(value = ["sessionId"], unique = true)],
)
data class SessionTodoEntity(
    @PrimaryKey val id: String,
    /** Perteneciente a la sesión. índice único —— Sólo hay una lista actual por sesión. */
    @ColumnInfo(defaultValue = "") val sessionId: String,
    /** JSON de toda la lista (en el mismo formato de serialización que TodoTool.TodoList). */
    @ColumnInfo(defaultValue = "") val todosJson: String,
    /**
     * ¿Hay algún elemento sin terminar (pending + in_progress > 0).
     *
     * Los campos redundantes eliminan la necesidad de analizar JSON línea por línea para "¿qué conversaciones tienen asuntos pendientes?"
     * Lo mantiene el almacén al escribir y no genera columnas en la base de datos. —— SQLite La columna generada requiere que se determine una expresión,
     * La cantidad de entradas requiere analizar JSON, lo cual no se puede hacer.
     */
    @ColumnInfo(defaultValue = "0") val hasOpenItems: Boolean = false,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

@Dao
interface SessionTodoDao {
    @Upsert
    suspend fun upsert(todo: SessionTodoEntity)

    @Query("SELECT * FROM session_todos WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getBySession(sessionId: String): SessionTodoEntity?

    /** Importe total (para copia de seguridad y exportación). */
    @Query("SELECT * FROM session_todos")
    suspend fun getAll(): List<SessionTodoEntity>

    @Query("SELECT * FROM session_todos WHERE sessionId = :sessionId LIMIT 1")
    fun observeBySession(sessionId: String): Flow<SessionTodoEntity?>

    /** También hay sesiones con elementos inacabados (entrada de consulta para libros de contabilidad entre sesiones). */
    @Query("SELECT * FROM session_todos WHERE hasOpenItems = 1 ORDER BY updatedAt DESC")
    suspend fun getSessionsWithOpenItems(): List<SessionTodoEntity>

    @Query("SELECT * FROM session_todos WHERE hasOpenItems = 1 ORDER BY updatedAt DESC")
    fun observeSessionsWithOpenItems(): Flow<List<SessionTodoEntity>>

    @Query("DELETE FROM session_todos WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** Borrar antes de restaurar la copia de seguridad (mismo calibre que otras tablas). */
    @Query("DELETE FROM session_todos")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM session_todos WHERE hasOpenItems = 1")
    suspend fun countSessionsWithOpenItems(): Int
}
