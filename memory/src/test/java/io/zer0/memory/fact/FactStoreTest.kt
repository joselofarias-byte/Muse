package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.compile.FactsSectionProjector
import io.zer0.memory.ticker.MemoryConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Phase 2.3.4: [FactStore] Pruebas unitarias.
 *
 * Uso de la base de datos en memoria de Room(Robolectric proporcionar SQLite)Verificar el comportamiento central:
 *  - add / addBatch: Deduplicación y fusión + desensibilización de PII + inferencia de importancia inteligente
 *  - searchFullText: FTS4 Búsqueda de texto completo + retorno de palabra LIKE
 *  - searchByTags: json_each Coincidencia exacta + lógica OR de etiquetas múltiples
 *  - applyDecay: hechos clave(importance=2)nunca decaer,Retención extendida del bono de hit
 *  - scope cuarentena: Los hechos en diferentes ámbitos no se afectan entre sí.
 *
 * descripción del diseño:
 *  - uso `Room.inMemoryDatabaseBuilder` Deje que cada prueba tenga su propia base de datos limpia
 *  - @Before reconstruir db, @After cerrar,Evite la fuga de estado entre pruebas
 *  - @Config(sdk=[33]) Implementación de SQLite usando API 33(apoyo FTS4)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FactStoreTest {

    private lateinit var db: FactDb
    private lateinit var dao: FactDao
    private lateinit var store: FactStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries() // para probar,Evite el cambio de despachador
            .build()
        dao = db.factDao()
        store = FactStore(dao, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `applyFieldUpdates updates content importance category and tags atomically`() = runTest {
        // v2.6.2(auditoría de memoria): Los tres tipos de campos se escriben dentro de la misma transacción para evitar semiactualizaciones.
        val id = store.add(FactStore.Fact(fact = "contenido antiguo", category = "general"))
        val ok = store.applyFieldUpdates(
            id = id,
            scope = "main",
            spaceId = "default",
            content = "nuevo contenido",
            importance = 2,
            category = "work",
            tags = listOf("a", "b"),
        )
        assertTrue(ok)
        val f = store.getById(id)!!
        assertEquals("nuevo contenido", f.fact)
        assertEquals(2, f.importance)
        assertEquals("work", f.category)
        assertEquals(listOf("a", "b"), f.tags)
        // La búsqueda de texto completo puede encontrar contenido nuevo
        assertTrue(store.searchFullText("nuevo contenido").any { it.id == id })
    }

    @Test
    fun `applyFieldUpdates rejects ownership mismatch`() = runTest {
        val id = store.add(FactStore.Fact(fact = "contenido"))
        val ok = store.applyFieldUpdates(
            id = id,
            scope = "other",
            spaceId = "default",
            content = "Modificación ultra vires",
            importance = null,
            category = null,
            tags = null,
        )
        assertFalse(ok)
        assertEquals("contenido", store.getById(id)!!.fact)
    }

    // ──────────────────────────────────────────────
    //  add / getById: Conceptos básicos CRUD
    // ──────────────────────────────────────────────

    @Test
    fun `add single fact assigns auto-increment id and can be retrieved`() = runTest {
        val fact = FactStore.Fact(fact = "a los usuarios les gusta Kotlin", tags = listOf("preference"))
        val id = store.add(fact)

        assertTrue("add Debería devolver un número positivo id", id > 0)
        val retrieved = store.getById(id)
        assertNotNull(retrieved)
        assertEquals("a los usuarios les gusta Kotlin", retrieved!!.fact)
        assertEquals(listOf("preference"), retrieved.tags)
    }

    @Test
    fun `add with PII phone number scrubs to REDACTED`() = runTest {
        val fact = FactStore.Fact(fact = "Mi número de teléfono es 13800138000. Por favor no lo reveles.")
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertFalse("Los números de teléfono deben ser insensibilizados,no debería aparecer de hecho", retrieved.fact.contains("13800138000"))
        assertTrue("La desensibilización debe incluir [REDACTED]", retrieved.fact.contains("[REDACTED]"))
    }

    @Test
    fun `add infers importance 2 for critical keywords`() = runTest {
        // importance=0 entrante, FactStore debe inferirse mediante inferImportancia como 2(clave)
        val fact = FactStore.Fact(fact = "El usuario es alérgico a la penicilina.", importance = 0)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("Contiene'Alergia'debe ser inferido importance=2", 2, retrieved.importance)
    }

    @Test
    fun `add infers importance 1 for important keywords`() = runTest {
        val fact = FactStore.Fact(fact = "A los usuarios les encanta el modo oscuro", importance = 0)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("Contiene'me gusta'debe ser inferido importance=1", 1, retrieved.importance)
    }

    @Test
    fun `add infers importance 0 for ordinary text`() = runTest {
        val fact = FactStore.Fact(fact = "Clima mencionado por el usuario", importance = 0)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("Se debe inferir el texto normal. importance=0", 0, retrieved.importance)
    }

    @Test
    fun `add respects explicit importance when set`() = runTest {
        val fact = FactStore.Fact(fact = "texto normal", importance = 2)
        val id = store.add(fact)

        val retrieved = store.getById(id)!!
        assertEquals("La importancia no debe anularse cuando se establece explícitamente.", 2, retrieved.importance)
    }

    @Test
    fun `add with scope stores fact under given scope`() = runTest {
        val id = store.add(FactStore.Fact(fact = "Memoria del subasistente"), scope = "assistant-123")

        val retrieved = store.getById(id)!!
        assertEquals("assistant-123", retrieved.scope)
    }

    // ──────────────────────────────────────────────
    //  addBatch
    // ──────────────────────────────────────────────

    @Test
    fun `addBatch with empty list returns 0`() = runTest {
        val result = store.addBatch(emptyList())
        assertEquals(0, result)
        assertEquals(0, store.size())
    }

    @Test
    fun `addBatch inserts multiple facts and returns count`() = runTest {
        val entries = listOf(
            FactStore.Fact(fact = "hechos A"),
            FactStore.Fact(fact = "hechos B"),
            FactStore.Fact(fact = "hechos C"),
        )
        val inserted = store.addBatch(entries)

        assertEquals(3, inserted)
        assertEquals(3, store.size())
    }

    @Test
    fun `addBatch merges similar facts instead of duplicating`() = runTest {
        val entries = listOf(
            FactStore.Fact(fact = "El usuario es alérgico a la penicilina."),
            FactStore.Fact(fact = "alérgico a la penicilina"), // Lo mismo después de quitar el tema.,debe ser reconocido como similar
        )
        store.addBatch(entries)

        // Solo se debe conservar 1(Los insertados posteriormente se fusionan con los existentes.)
        assertEquals("Hechos similares deben combinarse en lugar de repetirse", 1, store.size())
    }

    @Test
    fun `addBatch uses same scope for all entries`() = runTest {
        store.addBatch(
            listOf(
                FactStore.Fact(fact = "hechos del subayudante 1"),
                FactStore.Fact(fact = "hechos del subayudante 2"),
            ),
            scope = "assistant-A",
        )

        val scoped = store.getByScope("assistant-A")
        assertEquals(2, scoped.size)
        // Otros ámbitos deben estar vacíos
        assertEquals(0, store.getByScope("main").size)
    }

    // ──────────────────────────────────────────────
    //  searchFullText
    // ──────────────────────────────────────────────

    @Test
    fun `searchFullText with blank query returns empty`() = runTest {
        store.add(FactStore.Fact(fact = "hechos de prueba"))
        assertTrue(store.searchFullText("").isEmpty())
        assertTrue(store.searchFullText("   ").isEmpty())
    }

    @Test
    fun `searchFullText finds fact by chinese keyword`() = runTest {
        store.add(FactStore.Fact(fact = "A los usuarios les encanta el lenguaje de programación Kotlin"))
        store.add(FactStore.Fact(fact = "otro hecho irrelevante"))

        val results = store.searchFullText("Kotlin")
        assertTrue("Se debe encontrar al menos 1 coincidencia.", results.isNotEmpty())
        assertTrue("El resultado debe contener Kotlin", results.any { it.fact.contains("Kotlin") })
    }

    @Test
    fun `searchFullText single chinese char falls back to LIKE`() = runTest {
        // Consulta de una sola palabra COMO reserva(FTS4 2-gram No se puede acertar una sola palabra)
        store.add(FactStore.Fact(fact = "modo oscuro"))

        val results = store.searchFullText("profundo")
        assertTrue("Las consultas de una sola palabra deben realizarse mediante ME GUSTA", results.isNotEmpty())
    }

    // ──────────────────────────────────────────────
    //  searchByTags
    // ──────────────────────────────────────────────

    @Test
    fun `searchByTags with empty tags returns empty`() = runTest {
        store.add(FactStore.Fact(fact = "hechos", tags = listOf("preference")))
        assertTrue(store.searchByTags(emptyList()).isEmpty())
    }

    @Test
    fun `searchByTags matches single tag`() = runTest {
        store.add(FactStore.Fact(fact = "Preferencia", tags = listOf("preference")))
        store.add(FactStore.Fact(fact = "identidad", tags = listOf("identity")))

        val results = store.searchByTags(listOf("preference"))
        assertEquals(1, results.size)
        assertEquals("Preferencia", results[0].fact)
    }

    @Test
    fun `searchByTags OR logic matches multiple tags`() = runTest {
        store.add(FactStore.Fact(fact = "A", tags = listOf("tag1")))
        store.add(FactStore.Fact(fact = "B", tags = listOf("tag2")))
        store.add(FactStore.Fact(fact = "C", tags = listOf("tag3")))

        val results = store.searchByTags(listOf("tag1", "tag2"))
        assertEquals("OR La lógica debe coincidir con 2 elementos.", 2, results.size)
    }

    @Test
    fun `searchByTags returns matchCount for multi-tag facts`() = runTest {
        // Un hecho al mismo tiempo tag1 + tag2 → matchCount=2,debería ocupar el primer lugar
        store.add(FactStore.Fact(fact = "doble etiqueta", tags = listOf("tag1", "tag2")))
        store.add(FactStore.Fact(fact = "etiqueta única", tags = listOf("tag1")))

        val results = store.searchByTags(listOf("tag1", "tag2"))
        assertEquals(2, results.size)
        // matchCount=2 debería ocupar el primer lugar(prensa matchCount DESC)
        assertEquals("doble etiqueta", results[0].fact)
        assertEquals(2, results[0].matchCount)
    }

    // ──────────────────────────────────────────────
    //  applyDecay: apariencia de la partitura(fila reservada) + Los hechos clave nunca decaen
    // ──────────────────────────────────────────────

    @Test
    fun `applyDecay preserves critical facts with importance 2`() = runTest {
        // Inserte un dato clave de hace 100 días directamente a través de DAO
        val oldDate = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "alergia a la penicilina(clave)",
                tags = "[]",
                createdAt = oldDate,
                importance = 2, // clave,nunca decaer
                lastHitAt = null,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("No se debe permitir que aparezcan hechos clave", 0, outOfRecall)
        assertEquals("Los hechos clave aún deberían existir", 1, store.size())
        assertNull("Los hechos clave no deben marcarse", store.getById(id)!!.suppressedAt)
        assertTrue("Los hechos clave aún deben ser recordables", store.searchRelevantFacts("Alergia", limit = 5).any { it.id == id })
    }

    @Test
    fun `applyDecay takes low score facts out of recall but keeps the row`() = runTest {
        // Umbral predeterminado 4,5,λ=0.02:50 hechos ordinarios ≈ 10·e^-1 ≈ 3.68 → por debajo del umbral
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "50Preferencias de café del día anterior",
                tags = "[]",
                createdAt = oldDate,
                importance = 0,
                lastHitAt = null,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("Debe haber al menos 1 aparición.", 1, outOfRecall)
        assertEquals("Se deben conservar las filas.,Decay ya no elimina datos", 1, store.size())
        assertNotNull("Se debe escribir el tiempo de aparición.", store.getById(id)!!.suppressedAt)
        assertTrue("Los datos con puntuaciones bajas ya no deberían recordarse automáticamente", store.searchRelevantFacts("cafe", limit = 5).isEmpty())
        // La página de memoria aún puede incluir este elemento. —— La apariencia simplemente ya no está involucrada en el retiro.
        assertEquals(1, store.getByScope("main").size)
    }

    @Test
    fun `applyDecay keeps recent facts in recall`() = runTest {
        // Inserte datos comunes de hace 5 días(Mucho menos que el tiempo necesario para aparecer.)
        val recentDate = Instant.now().minus(5, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "Preferencias recientes de café",
                tags = "[]",
                createdAt = recentDate,
                importance = 0,
                lastHitAt = null,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("Los hechos recientes no deberían aparecer.", 0, outOfRecall)
        assertNull(store.getById(id)!!.suppressedAt)
        assertTrue("La puntuación debe bajar y estar por encima del umbral.", store.getById(id)!!.score >= MemoryConfig().compileThreshold)
        assertTrue(store.searchRelevantFacts("cafe", limit = 5).any { it.id == id })
    }

    @Test
    fun `applyDecay uses hitBonus to keep hit facts in recall`() = runTest {
        // 50Creado hace días, pero llegó hace 5 días. → El reloj comienza desde el momento del golpe.,La bonificación de golpe ha sido compensada.
        val oldCreated = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val recentHit = Instant.now().minus(5, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(
                fact = "50Creado hace días pero5La preferencia de café más popular.",
                tags = "[]",
                createdAt = oldCreated,
                importance = 0,
                lastHitAt = recentHit,
            ),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig())
        assertEquals("Disfruta el hecho de haber acertado hitBonus,no debería aparecer", 0, outOfRecall)
        assertNull(store.getById(id)!!.suppressedAt)
        assertTrue(store.searchRelevantFacts("cafe", limit = 5).any { it.id == id })
    }

    @Test
    fun `applyDecay persists score so repeated passes do not double decay`() = runTest {
        val recentDate = Instant.now().minus(10, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(fact = "Preferencias de café hace diez días", tags = "[]", createdAt = recentDate, importance = 0),
        )
        store.rebuildFtsIndex()

        store.applyDecay(MemoryConfig())
        val first = store.getById(id)!!.score
        // valor esperado:10·e^(-0.02·10) ≈ 8.19
        assertEquals(8.19f, first, 0.05f)

        store.applyDecay(MemoryConfig())
        val second = store.getById(id)!!.score
        assertEquals("Casi no pasó tiempo en la segunda vuelta,Las puntuaciones no deben descontarse repetidamente", first, second, 0.01f)
    }

    @Test
    fun `applyDecay with scope only affects specified scope`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()

        // main scope: viejos hechos comunes
        val mainId = dao.insert(
            FactEntity(
                fact = "main scope viejos datos del café",
                tags = "[]",
                createdAt = oldDate,
                importance = 0,
                lastHitAt = null,
                scope = "main",
            ),
        )
        // assistant-A scope: los mismos viejos y claros hechos
        val assistantId = dao.insert(
            FactEntity(
                fact = "assistant-A scope viejos datos del café",
                tags = "[]",
                createdAt = oldDate,
                importance = 0,
                lastHitAt = null,
                scope = "assistant-A",
            ),
        )
        store.rebuildFtsIndex()

        // Sólo atenuación main scope
        val outOfRecall = store.applyDecay(MemoryConfig(), scope = "main")
        assertEquals("Solo se debe permitir que aparezca 1 entrada del ámbito principal", 1, outOfRecall)
        assertNotNull(store.getById(mainId)!!.suppressedAt)
        assertNull("Otros ámbitos no deberían verse afectados", store.getById(assistantId)!!.suppressedAt)
        assertEquals("Ambas líneas deben mantenerse", 2, store.size())
    }

    @Test
    fun `applyDecay with scope and space only affects specified space`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val workId = dao.insert(
            FactEntity(fact = "work cafe viejo", tags = "[]", createdAt = oldDate, scope = "main", spaceId = "work"),
        )
        val lifeId = dao.insert(
            FactEntity(fact = "life cafe viejo", tags = "[]", createdAt = oldDate, scope = "main", spaceId = "life"),
        )
        store.rebuildFtsIndex()

        val outOfRecall = store.applyDecay(MemoryConfig(), scope = "main", spaceId = "work")

        assertEquals(1, outOfRecall)
        assertNotNull(store.getById(workId)!!.suppressedAt)
        assertNull(store.getById(lifeId)!!.suppressedAt)
        assertEquals("Ambas líneas deben mantenerse", 2, store.size())
    }

    @Test
    fun `explicit search revives a suppressed fact back into recall`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(fact = "50Preferencias de café del día anterior", tags = "[]", createdAt = oldDate, importance = 0),
        )
        store.rebuildFtsIndex()
        store.applyDecay(MemoryConfig())
        assertNotNull(store.getById(id)!!.suppressedAt)

        // búsqueda explícita(pagina de memoria / search_memory)Aún se pueden acceder a las filas que ya han aparecido.,y tire hacia atrás para recordar
        val explicitHits = store.searchFullTextScoped("cafe", scope = "main", spaceId = "default", limit = 5)
        assertTrue("La recuperación explícita debería poder ver el recuerdo que ha aparecido.", explicitHits.any { it.id == id })

        val revived = store.getById(id)!!
        assertNull("La marca de salida debe borrarse después de la visita.", revived.suppressedAt)
        assertTrue("La puntuación debería volver a superar el umbral.", revived.score >= MemoryConfig().compileThreshold)
        assertTrue("Se debe volver a ingresar la recuperación automática", store.searchRelevantFacts("cafe", limit = 5).any { it.id == id })
    }

    @Test
    fun `re referenced fact is revived by the next decay pass`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val id = dao.insert(
            FactEntity(fact = "A los usuarios les gusta tomar café americano.", tags = "[]", createdAt = oldDate, importance = 0),
        )
        store.rebuildFtsIndex()
        store.applyDecay(MemoryConfig())
        assertNotNull(store.getById(id)!!.suppressedAt)

        // Vuelve a mencionar lo mismo:El lado de escritura se fusiona con las filas existentes.(marcar un golpe),La próxima ronda de decadencia debería traerlo de vuelta.
        store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        val outOfRecall = store.applyDecay(MemoryConfig())

        assertEquals("No es necesario introducir nuevos hechos.", 0, outOfRecall)
        val revived = store.getById(id)!!
        assertNull("Debería volver a recordar después de haber sido nuevamente referenciado.", revived.suppressedAt)
        assertTrue(store.searchRelevantFacts("cafe", limit = 5).any { it.id == id })
    }

    @Test
    fun `read methods keep suppressed facts by default and hide them on demand`() = runTest {
        val kept = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        // v17: Los recuerdos que han aparecido están en la lista./Eliminar duplicados/UI todavía visible en el interior,Sólo se pasa la recuperación del contexto inyectado. includeSuppressed=false
        val suppressed = dao.insert(
            FactEntity(
                fact = "El usuario solía beber moca.",
                tags = "[]",
                createdAt = Instant.now().toString(),
                importance = 0,
                suppressedAt = Instant.now().toString(),
            ),
        )

        val all = store.getByScope("main")
        assertEquals("El valor predeterminado debe incluir la memoria de apariencia.", setOf(kept, suppressed), all.map { it.id }.toSet())

        val recallable = store.getByScope("main", includeSuppressed = false)
        assertEquals("includeSuppressed=false Deben excluirse los recuerdos que ya han aparecido.", listOf(kept), recallable.map { it.id })

        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
        assertEquals(1, store.getByScopeAndSpace("main", "default", includeSuppressed = false).size)
        assertEquals(2, store.getAll("main").size)
        assertEquals(1, store.getAll("main", includeSuppressed = false).size)
        assertEquals(2, store.getBySpace("default").size)
        assertEquals(1, store.getBySpace("default", includeSuppressed = false).size)
    }

    @Test
    fun `explicit cleanup still deletes rows`() = runTest {
        val oldDate = Instant.now().minus(50, ChronoUnit.DAYS).toString()
        val suppressed = dao.insert(
            FactEntity(fact = "Antiguas preferencias cafeteras que han hecho acto de presencia", tags = "[]", createdAt = oldDate, importance = 0),
        )
        store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        store.rebuildFtsIndex()
        store.applyDecay(MemoryConfig())
        assertNotNull(store.getById(suppressed)!!.suppressedAt)

        // Borrar entrada explícitamente(borrar la memoria)Todavía realmente eliminado
        store.clearAll()

        assertEquals(0, store.size())
        assertNull("No deberían quedar líneas después de borrar", store.getById(suppressed))
    }

    // ──────────────────────────────────────────────
    //  scope cuarentena
    // ──────────────────────────────────────────────

    @Test
    fun `getAll with scope filter only returns facts from that scope`() = runTest {
        store.add(FactStore.Fact(fact = "main hechos"), scope = "main")
        store.add(FactStore.Fact(fact = "A hechos"), scope = "assistant-A")
        store.add(FactStore.Fact(fact = "B hechos"), scope = "assistant-B")

        val mainOnly = store.getAll(scope = "main")
        assertEquals(1, mainOnly.size)
        assertEquals("main hechos", mainOnly[0].fact)

        val allScopes = store.getAll(scope = null)
        assertEquals(3, allScopes.size)
    }

    @Test
    fun `add deduplication only merges within same scope`() = runTest {
        val fact = FactStore.Fact(fact = "A los usuarios les encanta el modo oscuro")
        store.add(fact, scope = "main")
        store.add(fact, scope = "assistant-A") // diferente scope,no debe fusionarse

        assertEquals("Los diferentes osciloscopios deben almacenarse de forma independiente", 2, store.size())
        assertEquals(1, store.getByScope("main").size)
        assertEquals(1, store.getByScope("assistant-A").size)
    }

    // ──────────────────────────────────────────────
    //  Otros: size / delete / setImportance / update
    // ──────────────────────────────────────────────

    @Test
    fun `size returns 0 for empty database`() = runTest {
        assertEquals(0, store.size())
    }

    @Test
    fun `delete removes fact and returns true`() = runTest {
        val id = store.add(FactStore.Fact(fact = "Para ser eliminado"))
        assertEquals(1, store.size())

        val success = store.delete(id)
        assertTrue(success)
        assertEquals(0, store.size())
        assertNull(store.getById(id))
    }

    @Test
    fun `delete non-existent id returns false`() = runTest {
        assertFalse(store.delete(99999L))
    }

    @Test
    fun `setImportance updates importance and clamps to 0-2`() = runTest {
        val id = store.add(FactStore.Fact(fact = "prueba", importance = 0))

        assertTrue(store.setImportance(id, 5)) // Fuera de límites se debe sujetar a 2
        assertEquals(2, store.getById(id)!!.importance)

        assertTrue(store.setImportance(id, -3)) // Fuera de límites se debe sujetar a 0
        assertEquals(0, store.getById(id)!!.importance)
    }

    @Test
    fun `update changes fact content`() = runTest {
        val id = store.add(FactStore.Fact(fact = "contenido original"))
        val success = store.update(id, "Contenido actualizado")
        assertTrue(success)
        assertEquals("Contenido actualizado", store.getById(id)!!.fact)
    }

    @Test
    fun `update with empty content returns false`() = runTest {
        val id = store.add(FactStore.Fact(fact = "originales"))
        assertFalse(store.update(id, "   "))
        assertEquals("originales", store.getById(id)!!.fact)
    }

    @Test
    fun `getBySession returns facts for given session`() = runTest {
        store.add(FactStore.Fact(fact = "A", sessionId = "session-1"))
        store.add(FactStore.Fact(fact = "B", sessionId = "session-2"))
        store.add(FactStore.Fact(fact = "C", sessionId = "session-1"))

        val result = store.getBySession("session-1")
        assertEquals(2, result.size)
    }

    @Test
    fun `clearAll empties the database`() = runTest {
        store.add(FactStore.Fact(fact = "A"))
        store.add(FactStore.Fact(fact = "B"))
        assertEquals(2, store.size())

        store.clearAll()
        assertEquals(0, store.size())
    }

    // ──────────────────────────────────────────────
    //  ensureFtsIndexConsistent / rebuildFtsIndex
    // ──────────────────────────────────────────────

    @Test
    fun `ensureFtsIndexConsistent is idempotent`() = runTest {
        store.add(FactStore.Fact(fact = "hechos"))
        // Varias llamadas no deberían tener efectos secundarios
        store.ensureFtsIndexConsistent()
        store.ensureFtsIndexConsistent()
        store.ensureFtsIndexConsistent()
        // todavía se puede encontrar
        assertTrue(store.searchFullText("hechos").isNotEmpty())
    }

    @Test
    fun `rebuildFtsIndex fully rebuilds fts table`() = runTest {
        // Insertar normalmente a través de store.add(FTS Ya construido)
        store.add(FactStore.Fact(fact = "prueba rebuild"))
        // Simular la corrupción del FTS: Después de las limpiezas manuales rebuild
        // Nota: No es necesario ajustar directamente store.rebuildFtsIndex clear, Internamente se limpiará+reconstruirá
        store.rebuildFtsIndex()

        assertTrue(store.searchFullText("rebuild").isNotEmpty())
    }

    // ──────────────────────────────────────────────
    //  v1.x: Deduplicación mejorada(bigram Similares/Protección negativa/Deduplicación completa)
    // ──────────────────────────────────────────────

    @Test
    fun `add does not merge negated opposite facts`() = runTest {
        store.add(FactStore.Fact(fact = "Los usuarios no comen cilantro"))
        store.add(FactStore.Fact(fact = "Usuario come cilantro"))

        assertEquals("opuesto semántico negativo,no debe fusionarse", 2, store.size())
    }

    @Test
    fun `dedupPass merges existing duplicates and skips pinned`() = runTest {
        // Recorrido completo para eliminar duplicados: inserción de texto/diferencia de eliminación de palabras(Candidato LIKE no encontrado,Sólo falta la coincidencia de prefijos)También se puede fusionar
        store.add(FactStore.Fact(fact = "Mañana por la mañana haré el examen CET-4.", time = "2026-08-08T09:00"))
        store.add(FactStore.Fact(fact = "Mañana por la mañana haré el examen CET-4.", time = "2026-08-08T09:00"))
        // Los recuerdos fijados no se incluyen en la combinación
        val pinned = store.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
        dao.updatePinnedAt(pinned, Instant.now().toString())
        store.add(FactStore.Fact(fact = "A los usuarios les encanta beber café americano."))

        val merged = store.dedupPass(scope = "main")

        assertTrue("Debe fusionar al menos 1", merged >= 1)
        // Solo debe quedar 1 elemento en la categoría del examen.
        val all = store.getAll("main")
        assertEquals("Solo debe quedar 1 elemento en la categoría del examen.", 1, all.count { it.fact.contains("Nivel 4") })
        // La memoria fijada permanece(incluso si es similar a otro)
        assertNotNull(store.getById(pinned))
    }

    // ──────────────────────────────────────────────
    //  Revisión de corrección (2.0): Eliminar lápida(B-07 Coincidencia de canal dual/cultivo limitado B-10)
    // ──────────────────────────────────────────────

    @Test
    fun `delete records tombstone and matches exact wording`() = runTest {
        val tombstoneFile = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "tombstone-test-${System.nanoTime()}.json",
        )
        val storeWithTombstones = FactStore(dao, db, tombstoneFile)
        try {
            val id = storeWithTombstones.add(FactStore.Fact(fact = "El usuario es alérgico a la penicilina."))
            storeWithTombstones.delete(id)
            assertFalse("new scoped tombstones must not be appended to the legacy global file", tombstoneFile.exists())

            val tombstones = storeWithTombstones.getTombstones()
            assertEquals("La lápida se debe registrar después de la eliminación", 1, tombstones.size)
            assertTrue(tombstones[0].contains("alergia a la penicilina"))
            // golpe de redacción precisa
            assertTrue(FactStore.matchesTombstone("El usuario es alérgico a la penicilina.", tombstones[0]))
        } finally {
            tombstoneFile.delete()
        }
    }

    @Test
    fun `tombstone matches normalized whitespace and punctuation variants`() = runTest {
        val tombstoneFile = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "tombstone-test-${System.nanoTime()}.json",
        )
        val storeWithTombstones = FactStore(dao, db, tombstoneFile)
        try {
            val id = storeWithTombstones.add(FactStore.Fact(fact = "A los usuarios les gusta tomar café americano."))
            storeWithTombstones.delete(id)
            val tombstones = storeWithTombstones.getTombstones()

            // B-07: El objeto compilado es un resumen reescrito de otro pase de LLM.,Es casi seguro que la redacción es diferente. —
            // Compresión de espacios en blanco + eliminación de puntuación Respuesta de coincidencia de doble canal a patrones de reescritura comunes
            assertTrue(
                "la diferencia de espacios en blanco debería llegar",
                FactStore.matchesTombstone("A los usuarios les gusta tomar café americano.", tombstones[0]),
            )
            assertTrue(
                "Las diferencias de puntuación deberían afectar",
                FactStore.matchesTombstone("A los usuarios les gusta beber café americano.", tombstones[0]),
            )
        } finally {
            tombstoneFile.delete()
        }
    }

    @Test
    fun `tombstone list capped at max entries`() = runTest {
        val tombstoneFile = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "tombstone-test-${System.nanoTime()}.json",
        )
        val storeWithTombstones = FactStore(dao, db, tombstoneFile)
        try {
            // B-10: Las lápidas que sólo se añaden pero no se eliminan se ampliarán a largo plazo.(Y cada ronda de compilación se carga y escanea completamente) —
            // Descartar las entradas más antiguas cuando se excede el límite superior,El tamaño del archivo y la sobrecarga de filtrado están limitados
            val cap = FactStore.TOMBSTONE_MAX_ENTRIES
            repeat(cap + 50) { i ->
                storeWithTombstones.delete(
                    storeWithTombstones.add(FactStore.Fact(fact = "número de hecho de prueba $i")),
                )
            }
            val tombstones = storeWithTombstones.getTombstones()
            assertTrue("El número de lápidas no debe exceder el límite superior.", tombstones.size <= cap)
            assertEquals("Se deben conservar las últimas entradas", "número de hecho de prueba ${cap + 49}", tombstones.last())
        } finally {
            tombstoneFile.delete()
        }
    }

    @Test
    fun `deletion tombstones are isolated by assistant database scope and space`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val otherDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        val legacyFile = java.io.File(context.cacheDir, "legacy-tombstones-${System.nanoTime()}.json")
        legacyFile.writeText("[\"same preference\"]")
        val otherStore = FactStore(
            otherDb.factDao(),
            otherDb,
            tombstoneFile = legacyFile,
            assistantId = "assistant-b",
        )
        try {
            val deletedId = store.add(
                FactStore.Fact(fact = "same preference"),
                scope = "main",
                spaceId = "work",
            )
            store.add(FactStore.Fact(fact = "same preference"), scope = "main", spaceId = "personal")
            store.add(FactStore.Fact(fact = "same preference"), scope = "assistant-a", spaceId = "work")
            otherStore.add(FactStore.Fact(fact = "same preference"), scope = "main", spaceId = "work")

            assertFalse("wrong assistant ownership must refuse delete", store.delete(deletedId, "assistant-b", "main", "work"))
            assertFalse("wrong scope/space must refuse delete", store.delete(deletedId, "default", "main", "personal"))
            assertNotNull(store.getById(deletedId))
            assertTrue(store.delete(deletedId, "default", "main", "work"))

            assertEquals(listOf("same preference"), store.getTombstones("main", "work"))
            assertTrue(store.getTombstones("main", "personal").isEmpty())
            assertTrue(store.getTombstones("assistant-a", "work").isEmpty())
            assertTrue(
                "an active duplicate is exempt from an old shared text tombstone",
                otherStore.getTombstones("main", "work").isEmpty(),
            )
            assertEquals(
                "legacy tombstone still filters stale compiled text when no active exact fact exists",
                listOf("same preference"),
                otherStore.getTombstones("assistant-a", "work"),
            )
        } finally {
            legacyFile.delete()
            otherDb.close()
        }
    }

    @Test
    fun `expired duplicate rows do not mask deletion tombstones`() = runTest {
        val text = "expired preference"
        val expiredAt = Instant.now().minus(1, ChronoUnit.DAYS).toString()
        val originalId = store.add(FactStore.Fact(fact = text, expiresAt = expiredAt))
        assertTrue(store.delete(originalId, "default", "main", "default"))

        store.add(FactStore.Fact(fact = text, expiresAt = expiredAt))

        assertEquals(listOf(text), store.getTombstones("main", "default"))
    }

    @Test
    fun `tombstone insert failure leaves fact FTS and graph edges untouched`() = runTest {
        val id = store.add(FactStore.Fact(fact = "must remain if tombstone fails"))
        db.memoryLinkDao().insert(
            io.zer0.memory.ai.MemoryLinkEntity(
                sourceFactId = id,
                targetFactId = id + 100,
                sourceTitle = "source",
                targetTitle = "target",
                createdAt = Instant.now().toString(),
            ),
        )
        assertEquals(1, dao.countFts())
        assertEquals(1, db.memoryLinkDao().listByFactId(id).size)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_fact_tombstone BEFORE INSERT ON fact_deletion_tombstones " +
                "BEGIN SELECT RAISE(ABORT, 'forced tombstone write failure'); END",
        )

        val result = runCatching { store.delete(id, "default", "main", "default") }

        assertTrue("delete must fail when its durable tombstone cannot be committed", result.isFailure)
        assertNotNull("Room transaction rollback must preserve the source fact", store.getById(id))
        assertEquals("FTS must remain intact", 1, dao.countFts())
        assertEquals("graph edges must remain intact", 1, db.memoryLinkDao().listByFactId(id).size)
        assertTrue("rollback must not leave a tombstone", store.getTombstones("main", "default").isEmpty())
    }

    @Test
    fun `fact row failure rolls back tombstone and prior FTS deletion`() = runTest {
        val id = store.add(FactStore.Fact(fact = "transaction should roll back all artifacts"))
        db.memoryLinkDao().insert(
            io.zer0.memory.ai.MemoryLinkEntity(
                sourceFactId = id,
                targetFactId = id + 200,
                sourceTitle = "source",
                targetTitle = "target",
                createdAt = Instant.now().toString(),
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_fact_delete BEFORE DELETE ON facts " +
                "BEGIN SELECT RAISE(ABORT, 'forced fact row delete failure'); END",
        )

        val result = runCatching { store.delete(id, "default", "main", "default") }

        assertTrue("fact delete trigger must fail the operation", result.isFailure)
        assertNotNull("fact row must roll back", store.getById(id))
        assertEquals("FTS delete must roll back", 1, dao.countFts())
        assertEquals("graph edge delete must roll back", 1, db.memoryLinkDao().listByFactId(id).size)
        assertTrue("inserted tombstone must roll back", store.getTombstones("main", "default").isEmpty())
    }

    @Test
    fun `filterTombstonedLines drops tombstoned lines and keeps others`() {
        val text = "El usuario es alérgico a la penicilina.\nA los usuarios les gusta correr\nEl usuario vive en Beijing."
        val filtered = FactStore.filterTombstonedLines(text, listOf("alergia a la penicilina"))
        assertEquals("A los usuarios les gusta correr\nEl usuario vive en Beijing.", filtered)
    }

    /** v18: Declaraciones explícitas opuestas sobre la misma entidad —— La nueva memoria anota qué memoria antigua reemplaza. */
    @Test
    fun `contradicting user statement records what it supersedes`() = runTest {
        val old = store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        val fresh = store.add(FactStore.Fact(fact = "Zhang San odia tomar café", source = "user_explicit", entityKey = "Zhang San"))

        assertNotEquals("Las afirmaciones contrarias no deben tratarse como fusiones duplicadas.", old, fresh)
        assertEquals("Un nuevo recuerdo debe recordar el antiguo recuerdo al que reemplaza.", old, store.getById(fresh)!!.supersedesId)
        assertNull("Los viejos recuerdos ya no apuntan por sí solos a otras líneas", store.getById(old)!!.supersedesId)
    }

    /**
     * v18: La memoria reemplazada sale del contexto.(Sección de compilación + recuperación automática),Pero toda la línea todavía está en la biblioteca.:
     * Se puede buscar en la página de memoria y se puede realizar una búsqueda explícita.,Puedes arrepentirte eliminando nuevos recuerdos.
     */
    @Test
    fun `superseded fact leaves context but stays visible and returns when the new one is deleted`() = runTest {
        val old = store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        val fresh = store.add(FactStore.Fact(fact = "Zhang San odia tomar café", source = "user_explicit", entityKey = "Zhang San"))
        val oldText = store.getById(old)!!.fact
        val freshText = store.getById(fresh)!!.fact

        // pagina de memoria/Todavía hay dos elementos en la lista.
        assertEquals(
            "Los recuerdos que fueron reemplazados todavía están en la biblioteca.",
            setOf(old, fresh),
            store.getByScopeAndSpace("main", "default").map { it.id }.toSet(),
        )
        // Sólo el nuevo queda en la sección HECHOS producido por compilación.
        assertEquals(freshText, FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")))
        // recuperación automática(<relevant_memory>)Solo quedan nuevos
        assertFalse(
            "La memoria reemplazada no debe recuperarse automáticamente nuevamente",
            store.searchRelevantFacts("cafe", limit = 8).any { it.id == old },
        )
        assertTrue(store.searchRelevantFacts("cafe", limit = 8).any { it.id == fresh })
        // búsqueda explícita(search_memory Herramientas/Búsqueda de páginas de memoria)Todavía se pueden encontrar viejos recuerdos.
        assertTrue(
            "La recuperación explícita debería encontrar la memoria reemplazada.",
            store.searchFullTextScoped("le gusta tomar cafe", scope = "main", spaceId = "default", limit = 5).any { it.id == old },
        )

        // Arrepentimiento del usuario:borrar nueva memoria,Los viejos recuerdos vuelven automáticamente al contexto
        store.delete(fresh)
        assertTrue(
            "Después de que la parte señalante borre la memoria anterior, debe recuperarse",
            store.searchRelevantFacts("cafe", limit = 8).any { it.id == old },
        )
        assertTrue(FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")).contains(oldText))
    }

    /** v18: El usuario cambia de opinión y vuelve a cambiar —— El último reemplazará al del medio.,No se pueden tener dos afirmaciones mutuamente excluyentes. */
    @Test
    fun `flip flopping back supersedes the intervening fact`() = runTest {
        store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        val dislike = store.add(FactStore.Fact(fact = "Zhang San odia tomar café", source = "user_explicit", entityKey = "Zhang San"))
        val likeAgain = store.add(FactStore.Fact(fact = "A Zhang San le encanta tomar café.", source = "user_explicit", entityKey = "Zhang San"))

        assertEquals("La última declaración debería reemplazar a la del medio.", dislike, store.getById(likeAgain)!!.supersedesId)
        assertEquals(
            "Sólo las últimas declaraciones se conservan en el contexto.",
            store.getById(likeAgain)!!.fact,
            FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")),
        )
        assertEquals("Los tres todavía están en la biblioteca.(Se puede comprobar y lamentar)", 3, store.getByScopeAndSpace("main", "default").size)
    }

    /** v18: Los recuerdos de una misma entidad pero no contradictorios no pueden ser sustituidos por un etiquetado incorrecto. */
    @Test
    fun `same entity without contradiction is not marked as superseding`() = runTest {
        val coffee = store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        val city = store.add(FactStore.Fact(fact = "Zhang San vive en Chengdu", source = "user_explicit", entityKey = "Zhang San"))

        assertNotEquals(coffee, city)
        assertNull("Los recuerdos que no sean inconsistentes no deben recordarse supersedes", store.getById(city)!!.supersedesId)
        assertEquals(
            "Ambos deben dejarse en contexto.",
            2,
            FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")).lines().size,
        )
    }

    /** v18: La pareja arbitrada ya no entrará“Por confirmar”Lista de verificación,Aquellos que no hayan sido arbitrados igualmente serán reportados. */
    @Test
    fun `pending contradiction list reflects arbitration`() = runTest {
        // Las afirmaciones contrarias inferidas no participan en el arbitraje(source No user_explicit),Todavía se debe informar
        store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        store.add(FactStore.Fact(fact = "Zhang San odia tomar café", source = "inferred", entityKey = "Zhang San"))
        assertEquals("Los conflictos que no hayan sido arbitrados aún deben informarse", 1, store.detectContradictions("main", "default").size)
    }

    /** v18: Un par que se arbitra en escritura.,Ya no se solicita la confirmación del usuario. */
    @Test
    fun `arbitrated pair leaves the pending contradiction list`() = runTest {
        store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        store.add(FactStore.Fact(fact = "Zhang San odia tomar café", source = "user_explicit", entityKey = "Zhang San"))

        assertTrue(
            "Un par arbitrado ya no debería requerir la confirmación del usuario",
            store.detectContradictions("main", "default").isEmpty(),
        )
    }

    /** v18: El viejo Estado en sí es ambiguo.(Ambos son contradictorios)Sólo marcar pero no eliminar —— Quién es reemplazado queda al criterio humano. */
    @Test
    fun `ambiguous old state is left alone instead of guessing`() = runTest {
        store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        store.add(FactStore.Fact(fact = "A Zhang San le encanta tomar café con leche", source = "user_explicit", entityKey = "Zhang San"))
        val fresh = store.add(FactStore.Fact(fact = "Zhang San odia tomar café", source = "user_explicit", entityKey = "Zhang San"))

        assertNull("Cuando hay más de una contradicción en la memoria antigua, la sustitución no debe designarse arbitrariamente.", store.getById(fresh)!!.supersedesId)
        assertEquals(
            "Los tres permanecen en contexto.",
            3,
            FactsSectionProjector.project(store.getByScopeAndSpace("main", "default")).lines().size,
        )
    }

    /** v18: Declaración explícita de no usuario(inferencia/importar)No basta con revertir los hechos existentes. */
    @Test
    fun `inferred statement never supersedes an explicit fact`() = runTest {
        val old = store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", source = "user_explicit", entityKey = "Zhang San"))
        val inferred = store.add(FactStore.Fact(fact = "Zhang San odia tomar café", source = "inferred", entityKey = "Zhang San"))

        assertNotEquals(old, inferred)
        assertNull("Las afirmaciones inferidas en sentido contrario no reemplazan las declaraciones explícitas.", store.getById(inferred)!!.supersedesId)
    }
}
