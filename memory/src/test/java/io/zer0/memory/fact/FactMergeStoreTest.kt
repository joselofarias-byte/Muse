package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v19Prueba de capa de almacenamiento "Fusionar varias tiras en una".
 *
 * Portada:
 *  - mergeFacts: Se implementa el nuevo hecho, la fila de origen apunta a la nueva fila y solo se recupera la nueva fila, la lista/Las búsquedas explícitas aún ven las líneas fuente
 *  - metadata reglas de herencia: máxima importancia,tags Eliminar duplicados,scope/space heredar
 *  - unmergeFacts: Los nuevos hechos desaparecen, los puntos se borran y se restablece el recuerdo.
 *  - frontera: Lista vacía/una sola fila/la identificación no existe/el texto está vacío/la propiedad no coincide/las filas fusionadas ya no participarán en la fusión
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactMergeStoreTest {

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
    fun `mergeFacts creates aggregate fact and points every source at it`() = runTest {
        val coffee = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        val office = store.add(FactStore.Fact(fact = "La oficina de usuarios está en Wangjing. SOHO"))
        val cat = store.add(FactStore.Fact(fact = "El usuario tiene un gato llamado Tuanzi."))

        val mergedText = "Al usuario le gusta tomar café americano; su oficina está en Wangjing SOHO; tiene un gato llamado Tuanzi."
        val mergedId = store.mergeFacts(listOf(coffee, office, cat), mergedText)

        assertNotNull("La fusión debería devolver nuevos hechos id", mergedId)
        val merged = store.getById(mergedId!!)!!
        assertEquals(mergedText, merged.fact)
        assertNull("El producto de fusión en sí ya no apunta a otras filas", merged.mergedIntoId)

        // Cada línea fuente apunta a una nueva línea.
        listOf(coffee, office, cat).forEach { id ->
            assertEquals("fila de origen $id debería apuntar a la nueva línea fusionada", mergedId, store.getById(id)!!.mergedIntoId)
        }

        // Verificar miembros agregados,Para la visualización de la interfaz de usuario "fusionada a partir de 3 elementos"
        assertEquals(listOf(coffee, office, cat), store.getMergedChildren(mergedId).map { it.id })

        // Recuperar automáticamente solo las líneas nuevas fusionadas
        val recall = store.searchRelevantFacts("americano", limit = 8)
        assertEquals("La recuperación automática solo debería mantener las líneas nuevas fusionadas", listOf(mergedId), recall.map { it.id })

        // Las listas y búsquedas explícitas aún ven filas de origen fusionadas(Los usuarios deben poder comprobar lo que han fusionado.)
        val listed = store.getByScopeAndSpace("main", "default")
        assertTrue("La fila de origen aún debería estar en la lista.", listed.map { it.id }.containsAll(listOf(coffee, office, cat)))
        val explicit = store.searchFullTextScoped("americano", scope = "main", spaceId = "default", limit = 8)
        assertTrue("Una búsqueda explícita debería ver las filas de origen fusionadas", explicit.any { it.id == coffee })
    }

    @Test
    fun `merged sources leave projection input but stay in lists`() = runTest {
        val a = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        val b = store.add(FactStore.Fact(fact = "La oficina de usuarios está en Wangjing. SOHO"))

        val mergedId = store.mergeFacts(listOf(a, b), "A los usuarios les gusta beber café americano; la oficina está en Wangjing SOHO")!!

        // Compilar calibre de acceso a proyección(includeSuppressed=false)Solo quedan las nuevas filas fusionadas
        assertEquals(
            listOf(mergedId),
            store.getByScopeAndSpace("main", "default", includeSuppressed = false).map { it.id },
        )
        assertEquals(listOf(mergedId), store.getByScope("main", includeSuppressed = false).map { it.id })
        assertEquals(listOf(mergedId), store.getBySpace("default", includeSuppressed = false).map { it.id })
        // lista explícita(Parámetros predeterminados)los cuatro estan ahi
        assertEquals(3, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `mergeFacts inherits highest importance and deduplicated tags`() = runTest {
        val first = dao.insert(
            FactEntity(
                fact = "El usuario es alérgico a la penicilina.",
                tags = """["salud","Alergia"]""",
                createdAt = "2026-01-01T00:00:00Z",
                importance = 2,
                category = "medical",
                confidence = 0.8f,
                source = "inferred",
                time = "2026-01-01T00:00",
            ),
        )
        val second = dao.insert(
            FactEntity(
                fact = "El usuario prefiere una dieta ligera.",
                tags = """["salud","dieta"]""",
                createdAt = "2026-01-02T00:00:00Z",
                importance = 1,
                category = "general",
                confidence = 0.9f,
                source = "user_explicit",
            ),
        )

        val mergedId = store.mergeFacts(listOf(first, second), "El usuario es alérgico a la penicilina y prefiere una dieta ligera.")!!
        val merged = store.getById(mergedId)!!

        assertEquals("Se debe tomar el más alto de la fuente. importance", 2, merged.importance)
        assertEquals("tags Deben fusionarse para eliminar duplicados y mantener el orden de primera aparición.", listOf("salud", "Alergia", "dieta"), merged.tags)
        assertEquals("category Tome la primera fuente no general.", "medical", merged.category)
        assertEquals("confidence la fuente más alta", 0.9f, merged.confidence, 0.001f)
        assertEquals("Si hay user_explicit en la fuente, es user_explicit", "user_explicit", merged.source)
        assertEquals("time Obtener el primer no vacío", "2026-01-01T00:00", merged.time)
        assertEquals("scope/space Heredar rango seleccionado", "main", merged.scope)
        assertEquals("default", merged.spaceId)
    }

    @Test
    fun `unmergeFacts removes aggregate and brings sources back to recall`() = runTest {
        val a = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        val b = store.add(FactStore.Fact(fact = "La oficina de usuarios está en Wangjing. SOHO"))
        val mergedId = store.mergeFacts(listOf(a, b), "A los usuarios les gusta beber café americano; la oficina está en Wangjing SOHO")!!

        assertTrue("Deshacer la fusión debería tener éxito", store.unmergeFacts(mergedId))

        assertNull("El producto fusionado debería desaparecer de la biblioteca.", store.getById(mergedId))
        assertTrue("La colección de miembros agregados debe estar vacía", store.getMergedChildren(mergedId).isEmpty())
        listOf(a, b).forEach { id ->
            val fact = store.getById(id)!!
            assertNull("fila de origen $id El puntero de combinación debe estar borrado.", fact.mergedIntoId)
        }
        // recuperación de recuperación: La fila de origen vuelve a entrar en la recuperación automática.,El texto combinado ya no aparece
        val recall = store.searchRelevantFacts("americano", limit = 8)
        assertTrue("La fila de origen debería volver a ingresar a la recuperación automática.", recall.any { it.id == a })
        assertFalse("El texto combinado revocado ya no debería recuperarse", recall.any { it.id == mergedId })
        assertEquals(
            setOf(a, b),
            store.getByScopeAndSpace("main", "default", includeSuppressed = false).map { it.id }.toSet(),
        )
    }

    @Test
    fun `mergeFacts can be undone for a single source`() = runTest {
        val only = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        val mergedId = store.mergeFacts(listOf(only), "Al usuario le gusta beber americano (mañana)")!!

        assertEquals(mergedId, store.getById(only)!!.mergedIntoId)
        assertEquals(listOf(mergedId), store.searchRelevantFacts("americano", limit = 8).map { it.id })

        assertTrue(store.unmergeFacts(mergedId))
        assertNull(store.getById(only)!!.mergedIntoId)
        assertTrue(store.searchRelevantFacts("americano", limit = 8).any { it.id == only })
    }

    @Test
    fun `mergeFacts ignores empty input blank text and unknown ids`() = runTest {
        val id = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        val before = store.size()

        assertNull("Una lista vacía no debería producir nuevos hechos", store.mergeFacts(emptyList(), "Fusionar texto"))
        assertNull("El texto vacío no debería producir nuevos hechos.", store.mergeFacts(listOf(id), "   "))
        assertNull("id No se deben crear nuevos hechos cuando ninguno de ellos existe.", store.mergeFacts(listOf(999_999L), "Fusionar texto"))
        assertEquals("La entrada de límites no debe cambiar ninguna línea", before, store.size())
        assertNull("Las líneas fuente no deben marcarse", store.getById(id)!!.mergedIntoId)
    }

    @Test
    fun `mergeFacts skips sources outside the requested scope and already merged rows`() = runTest {
        val mainFact = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."), scope = "main")
        val otherScope = store.add(FactStore.Fact(fact = "La oficina de usuarios está en Wangjing. SOHO"), scope = "assistant-A")

        assertNull(
            "Si todas las líneas seleccionadas caen en otros ámbitos, no se realizarán cambios.",
            store.mergeFacts(listOf(otherScope), "Fusionar texto", scope = "main"),
        )
        assertNull("Las filas con propiedad inconsistente no deben marcarse", store.getById(otherScope)!!.mergedIntoId)

        // Selección mixta: Solo combine líneas con el mismo alcance + mismo espacio que la fuente principal
        val second = store.add(FactStore.Fact(fact = "El usuario tiene un gato llamado Tuanzi."), scope = "main")
        val mergedId = store.mergeFacts(listOf(mainFact, otherScope, second), "Fusionar texto", scope = "main")!!
        assertEquals(listOf(mainFact, second), store.getMergedChildren(mergedId).map { it.id })
        assertNull("Las filas de ámbito cruzado no se incluyen en la combinación", store.getById(otherScope)!!.mergedIntoId)

        // Las filas ya fusionadas ya no participarán en la segunda fusión(Evite señalar en cadena)
        val third = store.add(FactStore.Fact(fact = "A los usuarios les gusta escalar montañas los fines de semana."), scope = "main")
        val secondMerge = store.mergeFacts(listOf(mainFact, third), "Fusionar texto dos", scope = "main")!!
        assertEquals("Se deben omitir las filas fusionadas", listOf(third), store.getMergedChildren(secondMerge).map { it.id })
        assertEquals("Las filas fusionadas aún apuntan al primer producto fusionado", mergedId, store.getById(mainFact)!!.mergedIntoId)
    }
}
