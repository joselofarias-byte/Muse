package io.zer0.muse.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-UI-F13: La eliminación de una sesión debe borrar simultáneamente la instantánea del trabajo pendiente de sesión de TodoTool.
 *
 * TodoTool.sessionTodos es un singleton ConcurrentHashMap,La implementación original está solo en execute() escribir,
 * getTodos/clearSession leer,Y se llama a clearSession con la posición completa 0 → cada usado todo_write
 * Cada sesión mantiene una copia de TodoList en la memoria.,Y las conversaciones eliminadas aún se pueden leer a través de getTodos.
 */
class SessionTodoCleanupTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/ui/chat/ChatSessionController.kt").readText()

    @Test
    fun `delete session clears session todos`() {
        val src = source()
        val start = src.indexOf("fun deleteSession(")
        assertTrue("no encontrado deleteSession", start >= 0)
        val body = src.substring(start, (start + 3000).coerceAtMost(src.length))
        assertTrue(
            "Eliminar una sesión debe limpiar la instantánea de tareas pendientes de TodoTool",
            body.contains("TodoTool.clearSession(sessionId)"),
        )
    }
}
