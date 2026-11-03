package io.zer0.memory.reflection

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
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
 * v12 (T3-1/3-2/3-3): Prueba de tarea de reflexión de la memoria.
 *  - Rellenar claves de entidad históricas(entity_key Datos de inventario que son nulos)
 *  - Fusionar duplicados con las mismas entidades(Valores"3 artículos con el mismo nombre"limpio)
 *  - Detección de contradicciones(Afirmación contraria a la entidad)
 *  - Repetir confirmación de promoción(Múltiples hechos sobre la misma entidad → mayor importancia)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MemoryReflectionRunnerTest {

    private lateinit var db: FactDb
    private lateinit var store: FactStore
    private lateinit var runner: MemoryReflectionRunner

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        store = FactStore(db.factDao(), db)
        runner = MemoryReflectionRunner(store)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `reflection routes to the resolver store for non-default scope`() = runTest {
        // v2.6.2(auditoría de memoria): La reflexión para ámbitos no predeterminados debe recaer en la biblioteca auxiliar correspondiente, no en la biblioteca predeterminada.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val otherDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        val otherStore = FactStore(otherDb.factDao(), otherDb, assistantId = "assistant-b")
        try {
            val now = java.time.Instant.now().toString()
            otherDb.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "[NAME_1]Me gusta la fotografía", scope = "assistant-b", createdAt = now))

            var resolverScope: String? = null
            val routed = MemoryReflectionRunner(
                factStore = store,
                storeResolver = { scope, _ ->
                    resolverScope = scope
                    otherStore
                },
            )
            val result = routed.runReflection(scope = "assistant-b", spaceId = "default")

            assertEquals("assistant-b", resolverScope)
            assertTrue("Las claves de entidad deben completarse en la biblioteca auxiliar secundaria", result.backfilled >= 1)
        } finally {
            otherDb.close()
        }
    }

    @Test
    fun `reflection backfills entity keys and merges duplicates`() = runTest {
        // Simular datos bursátiles v11(entityKey=null): mask Los marcadores de posición preservan la distinción de entidades,Seguro para rellenar
        val now = java.time.Instant.now().toString()
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "[NAME_1]Me gusta la fotografía", createdAt = now))
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "Al profesor Zhang San le gusta la fotografía", createdAt = now))

        val result = runner.runReflection()

        assertTrue("Los datos del marcador de posición deben rellenar las claves de entidad", result.backfilled >= 1)
        // Se deben fusionar dos marcadores de posición con claves y hechos.(La entidadKey es consistente después del reabastecimiento → fusionar)
        store.add(FactStore.Fact(fact = "[NAME_1]Me gusta la fotografía", entityKey = "[NAME_1]"))
        val all = store.getByScopeAndSpace("main", "default")
        // [NAME_1] dos(Valores+nueva escritura)fusionado en 1,Los nombres chinos deben mantenerse de forma conservadora
        val name1Count = all.count { it.fact.contains("NAME_1") }
        assertEquals("Los marcadores de posición y entidades duplicados deben fusionarse en uno solo", 1, name1Count)
    }

    @Test
    fun `reflection merges same entity duplicates in legacy data`() = runTest {
        // Inserte directamente duplicados existentes con entidadKey(Simular datos históricos rellenados)
        val now = java.time.Instant.now().toString()
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "[NAME_1]Me gusta la fotografía", createdAt = now, entityKey = "[NAME_1]"))
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "Al profesor Zhang San le gusta la fotografía", createdAt = now, entityKey = "[NAME_1]"))

        val result = runner.runReflection()

        assertEquals("1 duplicado debe fusionarse", 1, result.merged)
        assertEquals("Solo queda 1 elemento después de fusionarlo", 1, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `reflection leaves unbackfillable chinese names untouched`() = runTest {
        val now = java.time.Instant.now().toString()
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "A Zhang San le gusta la fotografía", createdAt = now))
        db.factDao().insert(io.zer0.memory.fact.FactEntity(fact = "Al profesor Zhang San le gusta la fotografía", createdAt = now))

        runner.runReflection()

        // Cuando el nombre chino no tenga un LLM, será conservador y no se repondrá ni se fusionará por error.(Ning Le es bueno)
        assertEquals("Las acciones de nombres chinos están reservadas de forma conservadora", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `reflection detects contradictions without deleting`() = runTest {
        store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", entityKey = "Zhang San"))
        store.add(FactStore.Fact(fact = "Zhang San odia tomar café", entityKey = "Zhang San"))

        val contradictions = store.detectContradictions("main", "default")

        assertEquals("Se debe detectar 1 par de contradicciones.", 1, contradictions.size)
        // Los conflictos no se eliminan automáticamente
        assertEquals("Siguen existiendo hechos contradictorios", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `reflection promotes repeated entity facts`() = runTest {
        // 3 hechos diferentes sobre la misma entidad → Repetir confirmación,mayor importancia
        store.add(FactStore.Fact(fact = "Li Si está aprendiendo a nadar.", entityKey = "Juan Pérez"))
        store.add(FactStore.Fact(fact = "Li Si está aprendiendo a tocar la guitarra.", entityKey = "Juan Pérez"))
        store.add(FactStore.Fact(fact = "A Li Si le gusta correr", entityKey = "Juan Pérez"))

        val promoted = store.promoteRepeatedFacts("main", "default", minConfirmations = 2)

        assertTrue("Debe ser ascendido a al menos 1", promoted >= 1)
        val all = store.getByScopeAndSpace("main", "default")
        assertTrue("Existe después de la promoción. importance ≥ 1 el hecho", all.any { it.importance >= 1 })
    }

    @Test
    fun `reflection does not merge different facts of same entity`() = runTest {
        store.add(FactStore.Fact(fact = "A Zhang San le gusta tomar café.", entityKey = "Zhang San"))
        store.add(FactStore.Fact(fact = "A Zhang San le gusta correr", entityKey = "Zhang San"))

        runner.runReflection()

        assertEquals("No se combinan diferentes hechos.", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `single entity fact does not promote`() = runTest {
        store.add(FactStore.Fact(fact = "A Wang Wu le gusta la fotografía.", entityKey = "Wang Wu"))

        val promoted = store.promoteRepeatedFacts("main", "default", minConfirmations = 2)
        assertEquals("No se promoverá un solo hecho", 0, promoted)
    }

    @Test
    fun `reflection reports same entity multi assertion suggestions`() = runTest {
        store.add(FactStore.Fact(fact = "A Zhao Liu le gusta el café", entityKey = "Zhao Liu"))
        store.add(FactStore.Fact(fact = "Zhao Liu está aprendiendo a nadar.", entityKey = "Zhao Liu"))
        store.add(FactStore.Fact(fact = "A Zhao Liu le gusta correr", entityKey = "Zhao Liu"))

        val result = runner.runReflection()

        assertEquals("Se debe informar 1 entidad de afirmación múltiple de la misma entidad", 1, result.mergeSuggestions)
        // Sólo se recomienda no fusionar:Quedan tres afirmaciones diferentes
        assertEquals("Sin fusión automática,3 hechos reservados", 3, store.getByScopeAndSpace("main", "default").size)
    }
}
