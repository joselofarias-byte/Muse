package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D6 Número 2: [FactStore.findSameEntityMultiAssertionGroups] prueba.
 *
 * Portada:
 *  - Misma entidad ≥3 diferentes afirmaciones → Devolver la entidad
 *  - umbral insuficiente → no regresar;El umbral se puede reducir
 *  - Las aserciones duplicadas para la misma entidad cuentan después de la deduplicación(No exagerado)
 *  - Diferentes entidades se agrupan en grupos.,No mezclar grupos
 *  - entity_key Los datos históricos vacíos no participan.
 *  - Los hechos fijados no cuentan
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SameEntityAssertionsTest {

    private lateinit var db: FactDb
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        store = FactStore(db.factDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seed(prefix: String = "zhangsan") {
        store.add(FactStore.Fact(fact = "$prefix prefiere el café americano", entityKey = prefix))
        store.add(FactStore.Fact(fact = "$prefix nada en la piscina municipal", entityKey = prefix))
        store.add(FactStore.Fact(fact = "$prefix corre por el parque cada mañana", entityKey = prefix))
    }

    @Test
    fun `entity with three distinct assertions is returned`() = runTest {
        seed()
        val groups = store.findSameEntityMultiAssertionGroups("main", "default", minAssertions = 3)

        assertEquals(1, groups.size)
        assertEquals("zhangsan", groups[0].entityKey)
        assertEquals(3, groups[0].assertions.size)
    }

    @Test
    fun `entity below threshold is not returned but lower threshold finds it`() = runTest {
        store.add(FactStore.Fact(fact = "juanperez prefiere el café de la mañana", entityKey = "juanperez"))
        store.add(FactStore.Fact(fact = "juanperez corre por la costanera", entityKey = "juanperez"))

        assertTrue("2 Artículo < umbral 3,no debe ser devuelto", store.findSameEntityMultiAssertionGroups("main", "default", 3).isEmpty())
        assertEquals("El umbral reducido a 2 debería alcanzarse", 1, store.findSameEntityMultiAssertionGroups("main", "default", 2).size)
    }

    @Test
    fun `duplicate assertions are deduplicated before counting`() = runTest {
        // Evite la escritura y la deduplicación e inserte directamente dos líneas con la misma entidad y texto,Simular datos sucios existentes
        val now = java.time.Instant.now().toString()
        db.factDao().insert(FactEntity(fact = "zhangsan prefiere el café", createdAt = now, entityKey = "zhangsan"))
        db.factDao().insert(FactEntity(fact = "zhangsan prefiere el café", createdAt = now, entityKey = "zhangsan"))
        store.add(FactStore.Fact(fact = "zhangsan nada en la pileta", entityKey = "zhangsan"))

        // Después de la deduplicación, solo hay dos afirmaciones diferentes. < umbral 3
        assertTrue(store.findSameEntityMultiAssertionGroups("main", "default", 3).isEmpty())
        val groups = store.findSameEntityMultiAssertionGroups("main", "default", 2)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].assertions.size)
    }

    @Test
    fun `distinct entities form separate groups`() = runTest {
        seed("zhangsan")
        seed("juanperez")

        val groups = store.findSameEntityMultiAssertionGroups("main", "default", 3)

        assertEquals(2, groups.size)
        assertEquals(setOf("zhangsan", "juanperez"), groups.map { it.entityKey }.toSet())
    }

    @Test
    fun `rows without entity key are ignored`() = runTest {
        val now = java.time.Instant.now().toString()
        db.factDao().insert(FactEntity(fact = "como el cafe", createdAt = now))
        db.factDao().insert(FactEntity(fact = "aprendiendo a nadar", createdAt = now))
        db.factDao().insert(FactEntity(fact = "como correr", createdAt = now))

        assertTrue(store.findSameEntityMultiAssertionGroups("main", "default", 2).isEmpty())
    }

    @Test
    fun `pinned facts are excluded`() = runTest {
        seed("zhangsan")
        val pinned = store.getByScopeAndSpace("main", "default").first()
        store.setPinned(pinned.id, true)

        // Después de fijarlo en la parte superior,A esta entidad solo le quedan 2 afirmaciones para sugerir < umbral 3
        assertTrue(store.findSameEntityMultiAssertionGroups("main", "default", 3).isEmpty())
    }
}
