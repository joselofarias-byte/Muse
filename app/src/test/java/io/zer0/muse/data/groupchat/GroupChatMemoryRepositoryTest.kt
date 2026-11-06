package io.zer0.muse.data.groupchat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Biblioteca de sala mínima para pruebas:Sólo se incluyen hojas de memoria de chat grupal. */
@Database(
    entities = [GroupChatMemoryEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class TestMuseDb : RoomDatabase() {
    abstract fun groupChatMemoryDao(): GroupChatMemoryDao
}

/**
 * v1.0.72: Prueba de almacén de memoria de chat grupal.
 *
 * Cubre las nuevas capacidades v1.0.72(centro de memoria"chat grupal"Tab capa de datos):
 *  - getAll: Consulta de memoria de todos los chats grupales.(pantalla del centro de memoria)
 *  - deleteById: Eliminar un solo elemento(Eliminar un solo elemento en el centro de memoria)
 *  - deleteAll: Limpiar con un clic(Borrar todo el centro de memoria)
 *  - Devoluciones originales saveSummary / getByAssistant(Redacción de resumen de respuesta + lectura de inyección rápida)
 *
 * Usando la biblioteca de Room en memoria,No depende de archivos DB reales,CI Puede funcionar de forma estable.
 *
 * C-31 Evaluación:[TestMuseDb] Sólo definido en este documento,Pruebe la reutilización de otras salas de todo el almacén para producción MuseDb/FactDb/MemoryDb
 * (in-memory),No se copia la misma responsabilidad entre archivos. Baja repetibilidad,No vale la pena construir infraestructura de accesorios de prueba.,Mantenga las clases internas dentro de los archivos.
 * Consulte el informe de auditoría en profundidad C-31 para obtener instrucciones de revisión.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupChatMemoryRepositoryTest {

    private lateinit var db: androidx.room.RoomDatabase
    private lateinit var dao: GroupChatMemoryDao
    private lateinit var repo: GroupChatMemoryRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestMuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = (db as TestMuseDb).groupChatMemoryDao()
        repo = GroupChatMemoryRepository(dao)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun saveThenGetByAssistantReturnsRecent() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "En el chat grupal \"Grupo de prueba\",asistenteA Responder:hola")
        repo.saveSummary("chat1", "assistantB", "En el chat grupal \"Grupo de prueba\",asistenteB Responder:yo soy")

        val forA = repo.getByAssistant("assistantA", limit = 10)
        assertEquals(1, forA.size)
        assertTrue(forA[0].summary.contains("asistenteA"))

        val forB = repo.getByAssistant("assistantB", limit = 10)
        assertEquals(1, forB.size)
    }

    @Test
    fun getAllReturnsAllMemoriesAcrossChatsAndAssistants() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "Resumen1")
        repo.saveSummary("chat1", "assistantB", "Resumen2")
        repo.saveSummary("chat2", "assistantA", "Resumen3")

        val all = repo.getAll()
        assertEquals(3, all.size)
        // Orden descendente por tiempo:último primero
        assertTrue(all[0].createdAt >= all[1].createdAt)
        assertTrue(all[1].createdAt >= all[2].createdAt)
    }

    @Test
    fun deleteByIdRemovesSingleMemory() = runBlocking {
        // B-18: saveSummary Igual que la ventana predeterminada de 30 minutos. (chat, assistant) Eliminar duplicados,Reunión de segunda convocatoria
        // Saltar inserción y volver al primer elemento — Esta prueba verifica la semántica de eliminación.,pasar explícitamente dedupWindowMs=0 Deshabilitar la deduplicación
        val m1 = repo.saveSummary("chat1", "assistantA", "resumen para eliminar", dedupWindowMs = 0)
        repo.saveSummary("chat1", "assistantA", "resumen retenido", dedupWindowMs = 0)

        repo.deleteById(m1.id)

        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals("resumen retenido", all[0].summary)
    }

    @Test
    fun deleteAllClearsEverything() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "Resumen1")
        repo.saveSummary("chat2", "assistantB", "Resumen2")

        repo.deleteAll()

        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun deleteByGroupChatClearsOnlyThatChat() = runBlocking {
        repo.saveSummary("chat1", "assistantA", "grupo1de")
        repo.saveSummary("chat2", "assistantB", "grupo2de")

        repo.deleteByGroupChat("chat1")

        val all = repo.getAll()
        assertEquals(1, all.size)
        assertEquals("grupo2de", all[0].summary)
    }
}
