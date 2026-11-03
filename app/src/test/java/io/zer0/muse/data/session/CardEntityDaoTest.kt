package io.zer0.muse.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.card.CardEntity
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
 * [io.zer0.muse.data.card.CardEntityDao] prueba de comportamiento (MuseDb 118).
 *
 * Las pruebas de migración sólo pueden demostrar que "las tablas y los índices están creados correctamente", pero no pueden probar la semántica SQL de DAO;
 * Aquí usamos la biblioteca de memoria para poner las especificaciones. §3.2 Tres semánticas concretas:
 *  - Las tarjetas Dock se leen en orden ascendente mediante dockSlot (Pager el orden es estable);
 *  - Las tarjetas de desacoplamiento no se ingresarán en la lista de acoplamiento y puede haber varias tarjetas (el índice único no come NULL);
 *  - Borrar dockSlot solo cancela el acoplamiento y la fila sigue ahí (arrástrala hacia atrás sin eliminar la tarjeta).
 *
 * Se coloca debajo del paquete data/session para estar en el mismo directorio que la prueba de migración de MuseDb y reutilizar el mismo método de ensamblaje de la biblioteca de memoria.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardEntityDaoTest {

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

    private fun card(id: String, originMessageId: String? = null, dockSlot: Int? = null, createdAt: Long = 1_000L) = CardEntity(
        id = id,
        legacyCardId = "c_$id",
        originSessionId = "s1",
        originMessageId = originMessageId,
        title = "Título $id",
        code = "<div>$id</div>",
        isSvg = false,
        dataJson = """{"v":1}""",
        dockSlot = dockSlot,
        dockedAt = dockSlot?.let { 2_000L },
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    @Test
    fun upsertThenReadByIdAndLegacyId() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("id-1"))

        val byId = dao.getById("id-1")
        assertEquals("Título id-1", byId?.title)
        assertEquals("""{"v":1}""", byId?.dataJson)
        assertEquals("El antiguo c_xxx debería poder realizar una verificación inversa de la misma tarjeta.", "id-1", dao.getByLegacyCardId("c_id-1")?.id)
        assertNull("Identificación inexistente devuelta null", dao.getById("nope"))
    }

    @Test
    fun upsertOverwritesExistingRow() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("id-1", originMessageId = "m1"))
        dao.upsert(card("id-1", originMessageId = "m1").copy(title = "título cambiado", updatedAt = 9_000L))

        assertEquals("La misma inserción de identificación debería sobrescribirse en lugar de insertar dos filas", 1, dao.getByOriginMessageId("m1").size)
        assertEquals("título cambiado", dao.getById("id-1")?.title)
    }

    @Test
    fun dockedCardsAreOrderedBySlotAndExcludeInlineOnes() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a", dockSlot = 2, createdAt = 1_000L))
        dao.upsert(card("b", dockSlot = 0, createdAt = 2_000L))
        dao.upsert(card("c", dockSlot = 1, createdAt = 3_000L))
        // Dos tarjetas desacopladas: los índices únicos no deben permitir que entren en conflicto entre sí
        dao.upsert(card("d", createdAt = 4_000L))
        dao.upsert(card("e", createdAt = 5_000L))

        assertEquals(
            "Las tarjetas acopladas se clasifican en orden ascendente según dockSlot y las tarjetas desacopladas no aparecen.",
            listOf("b", "c", "a"),
            dao.getDockedCards().map { it.id },
        )
    }

    @Test
    fun updateAndClearDockSlotKeepsRow() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a"))

        assertEquals("Arrastrar: escribir ranura", 1, dao.updateDockSlot("a", 0, 5_000L, 5_000L))
        assertEquals(listOf("a"), dao.getDockedCards().map { it.id })
        assertEquals(5_000L, dao.getById("a")?.dockedAt)

        assertEquals("Arrastrar hacia atrás: borrar ranura", 1, dao.clearDockSlot("a", 6_000L))
        val after = dao.getById("a")
        assertTrue("Arrastrarla hacia atrás no elimina la tarjeta, la fila aún debe estar allí", after != null)
        assertNull("La ranura debe volver a NULL", after?.dockSlot)
        assertNull("También se debe aclarar la hora de atraque.", after?.dockedAt)
        assertTrue("La lista de muelles ya no debería contenerlo.", dao.getDockedCards().isEmpty())
        assertEquals("El contenido no debe cambiarse mediante acciones de arrastre", "<div>a</div>", after?.code)
    }

    @Test
    fun cardsAreReadableByOriginMessage() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a", originMessageId = "m1", createdAt = 1_000L))
        dao.upsert(card("b", originMessageId = "m1", createdAt = 2_000L))
        dao.upsert(card("c", originMessageId = "m2", createdAt = 3_000L))

        assertEquals(
            "Un mensaje puede contener varias tarjetas, ordenadas de forma estable por hora de creación",
            listOf("a", "b"),
            dao.getByOriginMessageId("m1").map { it.id },
        )
        assertTrue("No se deben mezclar tarjetas con otros mensajes.", dao.getByOriginMessageId("m3").isEmpty())
    }

    // ── v2.6.9: Todas las interfaces necesarias en el lado de respaldo ──

    @Test
    fun getAllReturnsEveryRowIncludingInlineAndDocked() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("docked", dockSlot = 0))
        dao.upsert(card("inline-a"))
        dao.upsert(card("inline-b"))

        assertEquals("Consulta completa no puede faltar desacoplar tarjetas", 3, dao.getAll().size)
        assertEquals(
            "getAll Debe ser consistente con el conteo.(Restaurar la reconciliación depende de esto último)",
            dao.getAll().size,
            dao.count(),
        )
    }

    @Test
    fun getAllPreservesEveryColumnWithoutHandWrittenMapping() = runTest {
        // Así es la guardia que sustituye al antiguo test de reflexión:Leer y escribir el mapeo generado por Room,Cuando se agregan entidades
        // No es necesario sincronizar ninguna lista de columnas en el lado de la copia de seguridad. Aquí afirmación campo por campo,Asegúrese de que ninguna columna se elimine silenciosamente.
        val dao = database.cardEntityDao()
        val rich = card("rich", originMessageId = "m-rich", dockSlot = 1, createdAt = 4_000L).copy(
            isSvg = true,
            dataJson = """{"rows":[1,2,3]}""",
            updatedAt = 9_000L,
        )
        dao.upsert(rich)

        assertEquals("Los viajes de ida y vuelta fila por fila deben realizarse como están.", rich, dao.getAll().single())
    }

    @Test
    fun deleteAllClearsTableButKeepsSchemaUsable() = runTest {
        val dao = database.cardEntityDao()
        dao.upsert(card("a", dockSlot = 0))
        dao.upsert(card("b"))

        dao.deleteAll()

        assertEquals("No deberían quedar filas después de borrar", 0, dao.count())
        assertTrue(dao.getAll().isEmpty())
        assertTrue("Después de la limpieza, la suscripción al área de atraque también debería estar vacía.", dao.getDockedCards().isEmpty())

        // Después de borrarlo, aún se puede escribir normalmente.(El proceso de recuperación consiste en borrar los datos primero y luego escribir en lotes.)
        dao.insertAll(listOf(card("fresh", dockSlot = 0)))
        assertEquals(listOf("fresh"), dao.getDockedCards().map { it.id })
    }

    @Test
    fun insertAllReplacesConflictingPrimaryKey() = runTest {
        val dao = database.cardEntityDao()
        dao.insertAll(listOf(card("same").copy(title = "título antiguo")))
        dao.insertAll(listOf(card("same").copy(title = "nuevo título")))

        assertEquals("La escritura por lotes con la misma clave principal es un reemplazo en lugar de un informe de errores", 1, dao.count())
        assertEquals("nuevo título", dao.getById("same")?.title)
    }

    @Test
    fun insertAllWithEmptyListIsNoOp() = runTest {
        // copia de seguridad vacía(sin tarjeta)No hay error debido a una lista vacía durante la recuperación
        database.cardEntityDao().insertAll(emptyList())

        assertEquals(0, database.cardEntityDao().count())
    }
}
