package io.zer0.muse.data.todo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.tools.TodoTool
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Prueba de persistencia de tareas pendientes de la sesión.
 *
 * Autoexamen auxiliar 2.17 Artículo: "No existe un mecanismo de circuito cerrado para las tareas pendientes entre sesiones…Falta un "Libro mayor de temas sin terminar".
 * Anteriormente, la lista de tareas pendientes solo estaba en `TodoTool.sessionTodos` En la memoria, la sesión se borrará cuando se corte y se perderá cuando se reinicie.
 *
 * Aquí se destacan tres cosas: la escritura se puede volver a leer, las sesiones cruzadas no interfieren entre sí,hasOpenItems Cambios con el estado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionTodoRepositoryTest {

    private lateinit var database: MuseDb
    private lateinit var repository: SessionTodoRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
        repository = SessionTodoRepository(database.sessionTodoDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun list(vararg items: TodoTool.TodoItem) = TodoTool.TodoList(items.toList())

    private fun item(content: String, status: String) = TodoTool.TodoItem(content = content, status = status)

    @Test
    fun `saved list can be read back`() = runTest {
        repository.save("s1", list(item("escribir plan", "pending"), item("Enviar correo electrónico", "completed")))

        val loaded = repository.load("s1")

        assertEquals(2, loaded.todos.size)
        assertEquals("escribir plan", loaded.todos[0].content)
        assertEquals("completed", loaded.todos[1].status)
    }

    @Test
    fun `sessions do not see each other's todos`() = runTest {
        repository.save("s1", list(item("A cosa", "pending")))
        repository.save("s2", list(item("B cosa", "pending")))

        assertEquals("A cosa", repository.load("s1").todos.single().content)
        assertEquals("B cosa", repository.load("s2").todos.single().content)
    }

    @Test
    fun `loading an unknown session yields an empty list`() = runTest {
        assertTrue(repository.load("never-used").todos.isEmpty())
    }

    @Test
    fun `open items are reported across sessions`() = runTest {
        repository.save("s1", list(item("No terminado", "pending")))
        repository.save("s2", list(item("hecho", "completed")))

        val open = repository.sessionsWithOpenItems()

        assertEquals(listOf("s1"), open.map { it.sessionId })
        assertEquals(1, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `in progress counts as open`() = runTest {
        repository.save("s1", list(item("haciendo", "in_progress")))

        assertEquals(1, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `completing everything clears the open flag`() = runTest {
        repository.save("s1", list(item("hecho", "completed")))

        assertEquals(0, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `saving replaces the whole list`() = runTest {
        repository.save("s1", list(item("viejo", "pending")))
        repository.save("s1", list(item("nuevo", "pending")))

        val loaded = repository.load("s1")

        assertEquals(1, loaded.todos.size)
        assertEquals("nuevo", loaded.todos.single().content)
    }

    @Test
    fun `saving keeps the original createdAt`() = runTest {
        repository.save("s1", list(item("a", "pending")))
        val first = database.sessionTodoDao().getBySession("s1")!!
        // Haga que la segunda escritura sea significativamente más tarde que la primera.
        Thread.sleep(5)
        repository.save("s1", list(item("b", "pending")))
        val second = database.sessionTodoDao().getBySession("s1")!!

        assertEquals("La recreación de una tarea pendiente de la misma sesión no debería actualizar el tiempo de creación", first.createdAt, second.createdAt)
        assertTrue(second.updatedAt >= first.updatedAt)
    }

    @Test
    fun `unknown status is treated as open`() = runTest {
        repository.save("s1", list(item("El estado está mal escrito.", "done")))

        assertEquals("El estado desconocido no puede considerarse completado", 1, repository.countSessionsWithOpenItems())
    }

    @Test
    fun `deleting a session removes its todos`() = runTest {
        repository.save("s1", list(item("a", "pending")))
        repository.delete("s1")

        assertNull(database.sessionTodoDao().getBySession("s1"))
        assertFalse(repository.load("s1").todos.isNotEmpty())
    }

    @Test
    fun `blank session id is ignored instead of writing a junk row`() = runTest {
        repository.save("", list(item("a", "pending")))

        assertEquals(0, database.sessionTodoDao().countSessionsWithOpenItems())
    }

    @Test
    fun `corrupted json does not crash and does not delete the row`() = runTest {
        database.sessionTodoDao().upsert(
            SessionTodoEntity(
                id = "x",
                sessionId = "s-bad",
                todosJson = "{not valid json",
                hasOpenItems = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val loaded = repository.load("s-bad")

        assertTrue("Los fallos de análisis deberían volver a una lista vacía en lugar de bloquearse", loaded.todos.isEmpty())
        assertTrue("El registro original no se puede eliminar debido a un error de análisis.", database.sessionTodoDao().getBySession("s-bad") != null)
    }
}
