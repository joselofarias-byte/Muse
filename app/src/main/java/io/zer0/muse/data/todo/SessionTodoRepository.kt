package io.zer0.muse.data.todo

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.tools.TodoTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Almacenamiento persistente de las tareas pendientes de la sesión.
 *
 * en `TodoTool` Haga tres cosas con Room: lista de serialización, mantenimiento `hasOpenItems`,
 * Consulte las "conversaciones que tienen asuntos pendientes" y proporcione recordatorios entre sesiones a la capa superior.
 */
class SessionTodoRepository(
    private val dao: SessionTodoDao,
) {

    /** Escribe la lista completa de una sesión (semántica de reemplazo, consistente con todo_write). */
    suspend fun save(sessionId: String, list: TodoTool.TodoList) {
        if (sessionId.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = dao.getBySession(sessionId)
        val entity = SessionTodoEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            todosJson = AppJson.encodeToString(TodoTool.TodoList.serializer(), list),
            hasOpenItems = hasOpenItems(list),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(entity)
    }

    /** Leer la lista de una sesión; si no hay ningún registro o el análisis falla, se devuelve una lista vacía. */
    suspend fun load(sessionId: String): TodoTool.TodoList {
        val row = dao.getBySession(sessionId) ?: return TodoTool.TodoList()
        return decode(row.todosJson)
    }

    /** Suscríbete a la lista de una sesión. */
    fun observe(sessionId: String): Flow<TodoTool.TodoList> = dao.observeBySession(sessionId).map { row ->
        row?.let { decode(it.todosJson) } ?: TodoTool.TodoList()
    }

    /** Sesiones con partidas pendientes (libro mayor de sesiones cruzadas). */
    suspend fun sessionsWithOpenItems(): List<SessionTodoEntity> = dao.getSessionsWithOpenItems()

    /** También hay un recuento de sesiones de elementos sin terminar para el mensaje de apertura. */
    suspend fun countSessionsWithOpenItems(): Int = dao.countSessionsWithOpenItems()

    /** Cuando se elimina una sesión, se elimina su acumulación. */
    suspend fun delete(sessionId: String) {
        dao.deleteBySession(sessionId)
    }

    /**
     * ¿Hay elementos sin terminar?
     *
     * Sólo se reconoce pendiente/en_progreso; el estado desconocido se trata como "Sin terminar" ——
     * Es mejor que te lo recuerden una vez más que marcar en silencio las cosas inacabadas como hechas.
     */
    private fun hasOpenItems(list: TodoTool.TodoList): Boolean = list.todos.any { it.status != "completed" }

    private fun decode(json: String): TodoTool.TodoList {
        if (json.isBlank()) return TodoTool.TodoList()
        return runCatching {
            AppJson.decodeFromString(TodoTool.TodoList.serializer(), json)
        }.onFailure {
            // El error de análisis no se puede tratar como "sin tareas pendientes" y tragarlo en silencio: registrar, devolver una lista vacía pero no eliminar la biblioteca.
            // La siguiente escritura lo sobrescribirá.
            Logger.w("SessionTodoRepo", "Error en el análisis JSON de tareas pendientes, se conserva el registro original: ${it.message}")
        }.getOrDefault(TodoTool.TodoList())
    }
}
