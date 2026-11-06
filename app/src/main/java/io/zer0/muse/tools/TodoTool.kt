package io.zer0.muse.tools

import io.zer0.common.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * todo_write Herramientas.
 *
 * protocolo alternativo,Máquina de estados de tres estados.
 * Cada llamada reemplaza el trabajo pendiente completo de la sesión.
 *
 * v1.0.92: Nuevo [todosFlow]/[observeTodos] — Se utiliza para que la interfaz de usuario muestre el progreso de las tareas pendientes.
 * v2.6.7 fix (AUDIT F5/F6/F7): En su lugar, el host inyecta la identificación de la sesión; el hecho de no descartar la base de datos ya no es silencioso;
 * Se agregó clearAll / syncFromDatabase para usar en restaurar y cambiar sesiones.
 */
@Suppress("TooManyFunctions") // Es necesario ejecutar la propia familia de herramientas de tareas pendientes./persistencia/precalentar/Limpia varias entradas y combínalas en pedazos.
object TodoTool {

    @Serializable
    data class TodoItem(
        val content: String,
        val activeForm: String = "",
        // pending / in_progress / completed
        val status: String = "pending",
    )

    @Serializable
    data class TodoList(val todos: List<TodoItem> = emptyList())

    // Almacenamiento a nivel de sesión:sessionId -> TodoList(v2.6.7: Instantánea de memoria + almacenamiento de doble capa,Actualiza mientras escribes)
    private val sessionTodos = ConcurrentHashMap<String, TodoList>()

    // v1.0.92: Flujo de instantáneas de tareas pendientes — Publicar el mapa completo después de cada escritura.,UI Esto muestra el progreso en tiempo real.
    private val _todosFlow = MutableStateFlow<Map<String, TodoList>>(emptyMap())

    /** v1.0.92: Flujo completo de instantáneas de tareas pendientes(solo lectura). */
    val todosFlow: StateFlow<Map<String, TodoList>> = _todosFlow.asStateFlow()

    /** v1.0.92: Suscríbete a la lista de tareas pendientes para una sesión específica(Lista vacía cuando no hay contenido). */
    fun observeTodos(sessionId: String): Flow<TodoList> = _todosFlow.map { it[sessionId] ?: TodoList() }

    private fun publish() {
        _todosFlow.value = sessionTodos.toMap()
    }

    fun toolDef() = ToolRegistry.ToolDef(
        name = "todo_write",
        description = "Administrar la lista de tareas pendientes para la sesión actual,Para trabajos de varios pasos." +
            "Divida tareas complejas en subtareas;No es necesario realizar tareas simples que se pueden completar en un solo paso." +
            "Cada llamada reemplazará la lista completa(Reemplazo,No incremental)." +
            "Las sesiones son proporcionadas por el anfitrión.,No inventes tu propia identificación de sesión.",
        parameters = mapOf(
            "todos" to
                "Required. JSON array of todo items: " +
                "[{\"content\":\"...\",\"activeForm\":\"...\"," +
                "\"status\":\"pending|in_progress|completed\"}]",
        ),
        required = setOf("todos"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    fun execute(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.trim()
            ?: return "Error: session_id parameter is required."
        return executeForSession(sessionId, args)
    }

    /**
     * v2.6.7 fix (AUDIT F5): Ejecutado con ID de sesión del host.
     *
     * La implementación anterior requiere que el modelo se pase solo. `session_id`, pero el modelo no tiene una forma confiable de conocer la sesión actual UUID
     * (El sistema solicita no inyectar,`{{session_id}}` Solo se expande en plantillas escritas por el usuario).
     * ahora por `ToolExecutionContext.sessionId` Inyección, el modelo ya no necesita ni debe adivinar.
     */
    fun executeForSession(sessionId: String, args: Map<String, String>): String {
        val scoped = sessionId.trim()
        val todosJson = args["todos"]?.trim().orEmpty()
        val todos = parseTodos(todosJson)
        return when {
            scoped.isEmpty() -> "Error: current session is unavailable."
            todosJson.isEmpty() -> "Error: todos parameter is required."
            todos == null -> "Error: failed to parse todos JSON."
            else -> applyTodos(scoped, todos)
        }
    }

    /** Escribe en la memoria + publica la instantánea y genera un resumen para el modelo. */
    private fun applyTodos(scoped: String, todos: TodoList): String {
        sessionTodos[scoped] = todos
        publish()

        // Resumen de compilación
        val counts = mutableMapOf("pending" to 0, "in_progress" to 0, "completed" to 0)
        for (td in todos.todos) {
            counts[td.status] = (counts[td.status] ?: 0) + 1
        }
        val total = todos.todos.size
        // v1.131: counts por mutableMapOf("pending" to 0, "in_progress" to 0, "completed" to 0)
        // inicialización,key Debe existir. Utilice getValue en su lugar []!! — Lanza NoSuchElementException en caso de error en lugar de NPE,
        // El contrato es más claro.,Y el IDE ya no está marcado en amarillo.
        val inProgressCount = counts.getValue("in_progress")
        val warning = if (inProgressCount > 1) {
            "Warning: multiple in_progress items ($inProgressCount). Convention: only one at a time."
        } else {
            null
        }

        return buildString {
            append("Todo list updated: $total total, ")
            append("${counts["completed"]} completed, ")
            append("$inProgressCount in progress, ")
            append("${counts["pending"]} pending.")
            if (warning != null) append("\n$warning")
        }
    }

    fun getTodos(sessionId: String): TodoList? = sessionTodos[sessionId]

    /**
     * Analiza los todos pasados ​​por el modelo.
     *
     * Compatible con dos formas: matriz desnuda `[{...}]` con objetos envolventes `{"todos":[...]}`.
     * Si falla, se devolverá nulo y la persona que llama dará un recibo de error.
     */
    private fun parseTodos(todosJson: String): TodoList? {
        val wrapped = runCatching {
            val arr = AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(TodoItem.serializer()),
                todosJson,
            )
            TodoList(arr)
        }.getOrNull()
        if (wrapped != null) return wrapped
        return runCatching {
            AppJson.decodeFromString(TodoList.serializer(), todosJson)
        }.getOrNull()
    }

    fun clearSession(sessionId: String) {
        sessionTodos.remove(sessionId)
        publish()
    }

    /**
     * v2.6.7 fix (AUDIT F7): Borre la instantánea de la memoria de todas las sesiones.
     *
     * La copia de seguridad y la recuperación reemplazarán todos los session_todos en la base de datos, pero la interfaz de usuario se suscribe al flujo de memoria;
     * Si no está claro, se seguirá mostrando la lista anterior antes de la restauración.
     */
    fun clearAll() {
        sessionTodos.clear()
        publish()
    }

    /**
     * v2.6.7: Exportación persistente. Inyectado por DI al inicio;null Indica no inyectado(prueba/inicialización temprana),
     * En este punto se degrada a pura memoria.,Comportamiento consistente con la pre-transformación.
     */
    @Volatile
    private var persist: (suspend (sessionId: String, list: TodoList) -> Unit)? = null

    /** v2.6.7: Inyectar implementación de persistencia al inicio(Escribir biblioteca + actualizar hasOpenItems). */
    fun installPersistence(handler: suspend (sessionId: String, list: TodoList) -> Unit) {
        persist = handler
    }

    /** v2.6.7: Calentar instantánea de memoria de la biblioteca(inicio del proceso/Llamado al cambiar de sesión.). */
    fun primeSnapshot(sessionId: String, list: TodoList) {
        sessionTodos[sessionId] = list
        publish()
    }

    /**
     * v2.6.7 fix (AUDIT F7): Reemplace la instantánea de la memoria, incluido el manifiesto vacío, con una copia de la biblioteca.
     *
     * [primeSnapshot] La persona que llama solo se calienta cuando no está vacía, por lo que el hecho de que "la biblioteca ya está vacía"
     * Los valores antiguos en la memoria nunca se pueden sobrescribir. Esto es especialmente obvio después de restaurar una copia de seguridad: la antigua tarea pendiente siempre se bloqueará.
     */
    fun syncFromDatabase(sessionId: String, list: TodoList) {
        if (list.todos.isEmpty()) sessionTodos.remove(sessionId) else sessionTodos[sessionId] = list
        publish()
    }

    /**
     * v2.6.7: Entrada de ejecución con persistencia.
     *
     * con [execute] Sólo hay una diferencia:Después de escribir en la memoria, se coloca en la biblioteca al mismo tiempo. Las dos entradas están separadas para permitir
     * No es necesario construir una base de datos para pruebas puramente funcionales existentes.
     */
    suspend fun executePersisted(args: Map<String, String>): String {
        val sessionId = args["session_id"]?.trim().orEmpty()
        if (sessionId.isEmpty()) return "Error: session_id parameter is required."
        return executePersistedForSession(sessionId, args)
    }

    /**
     * v2.6.7 fix (AUDIT F5/F6): El host inyecta la entrada de persistencia de la identificación de la sesión.
     *
     * con el viejo [executePersisted] Dos diferencias:
     *  1. El host administra la ID de la sesión y no depende de la transmisión del modelo para participar en las adivinanzas;
     *  2. El error de registro ya no se registra solo en el registro —— En el recibo se informaba claramente a la modelo que "este artículo no será retenido".
     *     De lo contrario, el usuario escucha "anotarlo", pero la biblioteca no lo tiene y desaparecerá después de reiniciar.
     */
    suspend fun executePersistedForSession(sessionId: String, args: Map<String, String>): String {
        val result = executeForSession(sessionId, args)
        val list = sessionTodos[sessionId.trim()]
        val handler = persist
        if (result.startsWith("Error:") || list == null || handler == null) return result
        return runCatching { handler(sessionId.trim(), list) }
            .fold(
                onSuccess = { result },
                onFailure = { error ->
                    io.zer0.common.Logger.w("TodoTool", "Error en el inventario de tareas pendientes session=$sessionId: ${error.message}")
                    "$result\n[Nota] Todo no pudo escribir en la base de datos local (${error.message ?: "razón desconocida"})," +
                        "Esta lista solo existe en la memoria actual y se perderá después de reiniciar. Por favor informe a los usuarios."
                },
            )
    }
}
