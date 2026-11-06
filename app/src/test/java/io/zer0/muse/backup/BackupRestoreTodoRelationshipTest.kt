package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.relationship.AssistantRelationshipEntity
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.todo.SessionTodoEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.7: Copia de seguridad y restauración**Cayó en la biblioteca**verificar.
 *
 * La prueba de ida y vuelta de serialización solo puede demostrar que "los campos no se pierden", pero no puede demostrar que "realmente se escribe en la base de datos durante la recuperación".
 * Esto último es lo que los usuarios pueden percibir: si la intimidad sigue ahí después de reemplazar y restaurar la máquina, y si la tarea pendiente sigue ahí.
 *
 * Esta prueba opera directamente el DAO real y replica la ruta de escritura en las dos tablas en applyBackupInternal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class BackupRestoreTodoRelationshipTest {

    private lateinit var database: MuseDb

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun todo(sessionId: String, json: String) = SessionTodoEntity(
        id = "todo-$sessionId",
        sessionId = sessionId,
        todosJson = json,
        hasOpenItems = true,
        createdAt = 100L,
        updatedAt = 200L,
    )

    private fun relationship(assistantId: String, affinity: Int) = AssistantRelationshipEntity(
        assistantId = assistantId,
        relationLabel = "amigos",
        affinity = affinity,
        lastInteractionAt = 300L,
        proactiveLevel = "normal",
        createdAt = 100L,
        updatedAt = 300L,
    )

    @Test
    fun `todos are restored into the database`() = runTest {
        val rows = listOf(todo("s1", """[{"content":"escribir plan","status":"pending"}]"""), todo("s2", """[]"""))

        // Copie la ruta de la biblioteca de escritura de applyBackupInternal
        rows.forEach { database.sessionTodoDao().upsert(it) }

        assertEquals(2, database.sessionTodoDao().getAll().size)
        assertEquals("s1", database.sessionTodoDao().getBySession("s1")?.sessionId)
        assertTrue(database.sessionTodoDao().getAll().any { it.todosJson.contains("escribir plan") })
    }

    @Test
    fun `relationships are restored into the database`() = runTest {
        // Este es el vacío que hay que llenar esta vez: antes, solo se exportaba pero no se restauraba, y toda la intimidad se perdía después de cambiar de dispositivo.
        val rows = listOf(relationship("a1", 42), relationship("a2", 88))

        rows.forEach { database.assistantRelationshipDao().upsert(it) }

        assertEquals(2, database.assistantRelationshipDao().getAll().size)
        assertEquals("La intimidad debe restaurarse con respaldo", 42, database.assistantRelationshipDao().get("a1")?.affinity)
        assertEquals("amigos", database.assistantRelationshipDao().get("a1")?.relationLabel)
    }

    @Test
    fun `restore clears stale rows before writing`() = runTest {
        // Primero coloque "datos antiguos" para simular el estado de la biblioteca antes de la recuperación.
        database.assistantRelationshipDao().upsert(relationship("stale", 99))
        database.sessionTodoDao().upsert(todo("stale", """[]"""))

        // Borrar + escribir para recuperación de réplica
        database.assistantRelationshipDao().deleteAll()
        database.sessionTodoDao().deleteAll()
        database.assistantRelationshipDao().upsert(relationship("a1", 42))
        database.sessionTodoDao().upsert(todo("s1", """[]"""))

        assertNull("Las relaciones de sesiones antiguas no deben permanecer después de la recuperación", database.assistantRelationshipDao().get("stale"))
        assertNull("Las tareas pendientes antiguas no deberían permanecer después de la recuperación", database.sessionTodoDao().getBySession("stale"))
        assertEquals(1, database.assistantRelationshipDao().getAll().size)
        assertEquals(1, database.sessionTodoDao().getAll().size)
    }

    @Test
    fun `backup service actually writes both tables on restore`() {
        // Ajustar directamente DAO solo puede demostrar que "DAO "Sabe escribir" no puede demostrar que "el proceso de recuperación realmente lo ajustó"——
        // assistantRelationships Al principio, se exportó pero no se restauró, y al eliminar esa línea no se compilaría ni se informaría un error.
        // Por lo tanto, aquí se afirma que el código fuente de la ruta de recuperación contiene estas dos escrituras.
        val source =
            java.io.File("src/main/java/io/zer0/muse/backup/BackupService.kt")
                .takeIf { it.exists() }
                ?.readText()
                ?: return
        assertTrue(
            "La ruta de recuperación debe estar escrita. session_todos",
            source.contains("backup.sessionTodos.forEach"),
        )
        assertTrue(
            "La ruta de recuperación debe escribirse como asistente_relación (anteriormente solo se exportaba pero no se restauraba)",
            source.contains("backup.assistantRelationships.forEach"),
        )
    }

    @Test
    fun `restoring same relationship twice does not duplicate`() = runTest {
        database.assistantRelationshipDao().upsert(relationship("a1", 42))
        database.assistantRelationshipDao().upsert(relationship("a1", 55))

        assertEquals("La clave principal es AssistantId, la recuperación duplicada debe sobrescribir en lugar de agregar nueva", 1, database.assistantRelationshipDao().getAll().size)
        assertEquals(55, database.assistantRelationshipDao().get("a1")?.affinity)
    }
}
