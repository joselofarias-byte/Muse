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
import java.time.Instant

/**
 * v12 (T2-2): Prueba de recuperación de memoria dependiente del tiempo de ejecución.
 * searchRelevantFacts Recuperar con FTS mediante consulta,y filtrar por alcance + espacio,
 * Prevenir ayuda cruzada/Cadena de memoria a través del espacio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchRelevantFactsTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.factDao()
        store = FactStore(dao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `recalls relevant facts by query`() = runTest {
        store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        store.add(FactStore.Fact(fact = "El usuario se está preparando actualmente para moverse."))
        store.add(FactStore.Fact(fact = "El usuario enviará el primer borrador del artículo la próxima semana."))

        val hits = store.searchRelevantFacts("cafe", limit = 5)
        assertEquals("Datos sobre el café que conviene recordar", 1, hits.size)
        assertTrue(hits[0].fact.contains("cafe"))
    }

    @Test
    fun `filters by scope and space`() = runTest {
        store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."), scope = "main", spaceId = "default")
        store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."), scope = "assistant-1", spaceId = "default")
        store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."), scope = "main", spaceId = "work")

        val mainHits = store.searchRelevantFacts("cafe", scope = "main", spaceId = "default", limit = 5)
        assertEquals("Recuperar solo el espacio principal/predeterminado", 1, mainHits.size)

        val workHits = store.searchRelevantFacts("cafe", scope = "main", spaceId = "work", limit = 5)
        assertEquals("Recuperar solo el espacio principal/de trabajo", 1, workHits.size)
    }

    @Test
    fun `relevant search filters scope before applying result limit`() = runTest {
        repeat(30) { index ->
            store.add(
                FactStore.Fact(fact = "Otros datos sobre el asistente del café $index"),
                scope = "assistant-$index",
                spaceId = "default",
            )
        }
        store.add(
            FactStore.Fact(fact = "Preferencias de café del asistente actual"),
            scope = "main",
            spaceId = "default",
        )

        val hits = store.searchRelevantFacts("cafe", scope = "main", spaceId = "default", limit = 1)

        assertEquals(1, hits.size)
        assertEquals("Preferencias de café del asistente actual", hits.single().fact)
    }

    @Test
    fun `scoped full text never returns another space`() = runTest {
        store.add(FactStore.Fact(fact = "Preferencias de café en el espacio de trabajo"), scope = "main", spaceId = "work")
        store.add(FactStore.Fact(fact = "Preferencias de café en los espacios habitables."), scope = "main", spaceId = "life")
        store.add(FactStore.Fact(fact = "Preferencias de café en otros asistentes"), scope = "assistant-1", spaceId = "work")

        val hits = store.searchFullTextScoped("cafe", scope = "main", spaceId = "work", limit = 10)

        assertEquals(1, hits.size)
        assertEquals("Preferencias de café en el espacio de trabajo", hits.single().fact)
        assertEquals("main", hits.single().scope)
        assertEquals("work", hits.single().spaceId)
    }

    @Test
    fun `scoped tag search never returns another scope or space`() = runTest {
        store.add(FactStore.Fact(fact = "hechos de la etiqueta del trabajo", tags = listOf("preference")), scope = "main", spaceId = "work")
        store.add(FactStore.Fact(fact = "hechos de la etiqueta de vida", tags = listOf("preference")), scope = "main", spaceId = "life")
        store.add(FactStore.Fact(fact = "Otros datos sobre etiquetas de ayuda", tags = listOf("preference")), scope = "assistant-1", spaceId = "work")

        val hits = store.searchByTagsScoped(
            queryTags = listOf("preference"),
            scope = "main",
            spaceId = "work",
            limit = 10,
        )

        assertEquals(1, hits.size)
        assertEquals("hechos de la etiqueta del trabajo", hits.single().fact)
        assertEquals("main", hits.single().scope)
        assertEquals("work", hits.single().spaceId)
    }

    @Test
    fun `empty query returns empty`() = runTest {
        store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        assertTrue(store.searchRelevantFacts("").isEmpty())
        assertTrue(store.searchRelevantFacts("   ").isEmpty())
    }

    @Test
    fun `recall keeps its size when many candidates are suppressed`() = runTest {
        // v17: La memoria de salida todavía ocupa la ventana candidata de FTS,Como resultado, los retiros no se pueden reducir.
        // Los 14 artículos que se almacenaron primero han sido liberados.,Presione Rowid para ordenar en la ventana frontal.
        val suppressedIds = (1..14).map { index ->
            dao.insert(
                FactEntity(
                    fact = "Recuerdos de café que han aparecido $index",
                    tags = "[]",
                    createdAt = Instant.now().toString(),
                    importance = 0,
                    suppressedAt = Instant.now().toString(),
                ),
            )
        }.toSet()
        repeat(10) { index ->
            dao.insert(
                FactEntity(
                    fact = "Los recuerdos del café aún se recuerdan $index",
                    tags = "[]",
                    createdAt = Instant.now().toString(),
                    importance = 0,
                ),
            )
        }
        store.rebuildFtsIndex()

        val hits = store.searchRelevantFacts("cafe", limit = 5)

        assertEquals("El número de retiros no debe reducirse al ingresar al mercado.", 5, hits.size)
        assertTrue("Los resultados de la recuperación no deben incluir recuerdos que hayan aparecido.", hits.none { it.id in suppressedIds })
    }
}
