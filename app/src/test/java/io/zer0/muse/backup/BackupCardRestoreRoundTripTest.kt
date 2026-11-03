package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.data.card.CardEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.8: Estado de tarjeta acoplada**Devolución y ida y vuelta.**verificar.
 *
 * La prueba anterior sólo puede probar que "los campos no se pierden" y "existe esa línea de llamada en el código fuente", pero no puede probar que "la línea realmente está escrita en la biblioteca".
 * Tampoco prueba que el índice único de dockSlot no tropiece en la secuencia de recuperación. Ejecute aquí usando la biblioteca de memoria de la habitación real:
 *
 *  exportar (cardEntityDao().getAll() → toRecord → inDockOrder)
 *    → borrar + reescritura (replaceCardEntities, simular frente de recuperación)
 *    → Afirmar número de línea, contenido campo por campo, orden de atraque
 *
 * con `applyBackupInternal` / `applyNdJsonStreaming` Se utiliza el mismo conjunto de funciones, así que allá vamos,
 * Simplemente pase la tarjeta como parte de las dos rutas de recuperación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupCardRestoreRoundTripTest {

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

    private fun card(id: String, dockSlot: Int? = null, createdAt: Long = 1_000L, title: String = "Título $id") = CardEntity(
        id = id,
        legacyCardId = "c_$id",
        originSessionId = "s1",
        originMessageId = "m-$id",
        title = title,
        code = "<div>$id</div>",
        isSvg = false,
        dataJson = """{"v":"$id"}""",
        dockSlot = dockSlot,
        dockedAt = dockSlot?.let { 2_000L },
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    /**
     * Lado de exportación de la réplica: lectura completa → Formato de conversión → Secuenciado por orden de atraque.
     *
     * envuelto en `withTransaction` adentro —— cierto `buildBackup` / `writeNdJson` Así se ajusta.
     * v2.6.9 Utilice DAO para leer y escribir.Room La llamada se enviará a la conexión de la transacción (ya no tocando directamente
     * `openHelper.writableDatabase`), esta afirmación seguirá verificando la disponibilidad dentro de la transacción.
     */
    private suspend fun exportCards(): List<CardEntityRecord> = database.withTransaction {
        database.cardEntityDao().getAll().map { it.toRecord() }.inDockOrder()
    }

    /** Lado de recuperación de forja: Ranura de verificación → Borrar → Escritura reescrita (dentro de la transacción de recuperación, igual que applyBackupInternal). */
    private suspend fun restoreCards(records: List<CardEntityRecord>) = database.withTransaction {
        database.replaceCardEntities(records)
    }

    @Test
    fun `docked card layout survives export and restore`() = runTest {
        val cards = listOf(
            card("slot-2", dockSlot = 2, createdAt = 1_000L),
            card("slot-0", dockSlot = 0, createdAt = 2_000L),
            card("slot-1", dockSlot = 1, createdAt = 3_000L),
        )
        cards.forEach { database.cardEntityDao().upsert(it) }

        restoreCards(exportCards())

        assertEquals("El número de filas debe ser consistente.", 3, database.cardEntityDao().count())
        assertEquals(
            "Se debe mantener el orden de atraque(La ranura es la única fuente de pedido para el buscapersonas del muelle.)",
            listOf("slot-0", "slot-1", "slot-2"),
            database.cardEntityDao().getDockedCards().map { it.id },
        )
        assertEquals(
            "El contenido campo por campo debe ser coherente(Si se pierde el contenido, la tarjeta está vacía.)",
            cards.sortedBy { it.id },
            database.cardEntityDao().getAll().sortedBy { it.id },
        )
    }

    @Test
    fun `inline cards are preserved alongside docked ones`() = runTest {
        val docked = card("docked", dockSlot = 0, createdAt = 1_000L)
        val inlineA = card("inline-a", dockSlot = null, createdAt = 2_000L)
        val inlineB = card("inline-b", dockSlot = null, createdAt = 3_000L)
        listOf(docked, inlineA, inlineB).forEach { database.cardEntityDao().upsert(it) }

        restoreCards(exportCards())

        assertEquals("También se deben conservar las tarjetas de desacoplamiento.", 3, database.cardEntityDao().count())
        assertEquals(
            "Las tarjetas no estacionadas no se pueden introducir en el área de conexión",
            listOf("docked"),
            database.cardEntityDao().getDockedCards().map { it.id },
        )
        assertNull("La ranura para una tarjeta desacoplada aún debe estar null", database.cardEntityDao().getById("inline-a")?.dockSlot)
        assertEquals("El contenido de la tarjeta de desacoplamiento también debe estar en", "<div>inline-b</div>", database.cardEntityDao().getById("inline-b")?.code)
    }

    @Test
    fun `restore replaces stale rows instead of merging`() = runTest {
        database.cardEntityDao().upsert(card("stale", dockSlot = 5, createdAt = 100L))
        val incoming = listOf(card("fresh", dockSlot = 0, createdAt = 200L))

        restoreCards(incoming.map { it.toRecord() })

        assertEquals("La restauración debe reemplazar la totalidad,No quedan tarjetas antiguas", 1, database.cardEntityDao().count())
        assertNull("Las tarjetas antiguas deben borrarse.", database.cardEntityDao().getById("stale"))
        assertEquals(listOf("fresh"), database.cardEntityDao().getDockedCards().map { it.id })
    }

    @Test
    fun `duplicate dock slots in backup fail the restore`() = runTest {
        val duplicated = listOf(card("a", dockSlot = 0), card("b", dockSlot = 0))

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { restoreCards(duplicated.map { it.toRecord() }) }
        }
        assertEquals("Si no se realiza la verificación, se revertirá toda la transacción de recuperación y no quedará medio juego de tarjetas en la biblioteca.", 0, database.cardEntityDao().count())
    }

    @Test
    fun `restored cards notify observers so the dock refreshes`() = runTest {
        // Restaurar DAO (Room generado INSERT / DELETE), debe confirmar que aún activa la notificación de invalidación a nivel de tabla de Room,
        // De lo contrario, la tarjeta anterior antes de la restauración seguirá mostrándose en el área de acoplamiento una vez completada la restauración.
        val before = database.cardEntityDao().observeDockedCards().first()
        assertTrue("prefijo:Sin tarjeta de conexión antes de la restauración", before.isEmpty())

        restoreCards(listOf(card("fresh", dockSlot = 0).toRecord()))

        assertEquals(
            "La suscripción al Dock debe poder ver la nueva tarjeta después de la restauración",
            listOf("fresh"),
            database.cardEntityDao().observeDockedCards().first().map { it.id },
        )
    }

    @Test
    fun `card data binding survives restore`() = runTest {
        // dataJson Son los datos vinculantes de show_card/update_card_data. Si se pierde la tarjeta, el contenido no se puede reproducir.
        val rich = card("rich", dockSlot = 1).copy(
            dataJson = """{"rows":[1,2,3],"title":"mesa"}""",
            title = "tarjeta de datos",
            code = "<svg></svg>",
            isSvg = true,
        )
        database.cardEntityDao().upsert(rich)

        restoreCards(exportCards())

        val restored = database.cardEntityDao().getById("rich")
        assertEquals("""{"rows":[1,2,3],"title":"mesa"}""", restored?.dataJson)
        assertEquals("tarjeta de datos", restored?.title)
        assertEquals("<svg></svg>", restored?.code)
        assertEquals("SVG Se deben conservar las banderas de las sucursales.", true, restored?.isSvg)
    }
}
