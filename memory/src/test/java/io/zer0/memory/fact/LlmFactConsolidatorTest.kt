package io.zer0.memory.fact

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.llm.MemoryLlmClient
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.0.92: LLM Consolidador de memoria (LlmFactConsolidator) prueba.
 *
 * Portada:
 *  - Clústeres similares se fusionan en uno(keeper Mantener, borrar el resto);
 *  - modelo explícito __SKIP__ → no fusionar;
 *  - Se sospecha que el modelo se genera tal cual(número de residuo)→ no fusionar;
 *  - anomalía del modelo → Rebaja silenciosa,Los datos se mantienen sin cambios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LlmFactConsolidatorTest {

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

    /** controlable fake LLM — Registrar el número de llamadas,Volver por defecto. */
    private class FakeLlm(private val response: String) : MemoryLlmClient {
        var calls = 0
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String {
            calls++
            return response
        }
    }

    private suspend fun seedSimilarPair() {
        // usar"el prefijo contiene"pares similares,Garantizado para ingresar al grupo de findSimilarGroups(La capa de algoritmo puede determinar),
        // Las pruebas se centran en el comportamiento del integrador en sí.,Valores marginales que no dependen de umbrales de bigram.
        store.add(FactStore.Fact(fact = "A los usuarios les gusta comer manzanas"))
        store.add(FactStore.Fact(fact = "A los usuarios les gusta comer manzanas y plátanos."))
    }

    @Test
    fun mergesSimilarGroupIntoOneFact() = runTest {
        seedSimilarPair()
        val llm = FakeLlm("A los usuarios les gusta comer manzanas y plátanos.")
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(1, merged)
        assertEquals("Sólo debería quedar uno", 1, store.getByScopeAndSpace("main", "default").size)
        assertEquals("A los usuarios les gusta comer manzanas y plátanos.", store.getByScopeAndSpace("main", "default").first().fact)
    }

    @Test
    fun skipMarkerMeansNoMerge() = runTest {
        seedSimilarPair()
        val llm = FakeLlm("__SKIP__")
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(0, merged)
        assertEquals("los datos permanecen sin cambios", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun numberedRawOutputMeansNoMerge() = runTest {
        seedSimilarPair()
        val llm = FakeLlm("1. A los usuarios les gusta comer manzanas\n2. A los usuarios les gusta comer manzanas y plátanos.")
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(0, merged)
        assertEquals(2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun `mergeIntoKeeper is atomic and cleans derived rows`() = runTest {
        // v2.6.2(auditoría de memoria): keeper Las actualizaciones + eliminaciones de miembros están en la misma transacción; FTS/enlaces/revisiones se borran sincrónicamente cuando se eliminan miembros.
        val id1 = store.add(FactStore.Fact(fact = "A los usuarios les gusta comer manzanas"))
        val id2 = store.add(FactStore.Fact(fact = "A los usuarios les gusta comer manzanas y plátanos."))
        val idMissing = 999_999L

        val removed = store.mergeIntoKeeper(
            keeperId = id1,
            mergedContent = "A los usuarios les gusta comer manzanas y plátanos.",
            removableIds = listOf(id2, idMissing),
            scope = "main",
            spaceId = "default",
        )

        assertEquals("Eliminar solo los miembros existentes", 1, removed)
        assertEquals("keeper Reservado y contenido actualizado", 1, store.getByScopeAndSpace("main", "default").size)
        assertEquals("A los usuarios les gusta comer manzanas y plátanos.", store.getByScopeAndSpace("main", "default").first().fact)
    }

    @Test
    fun `mergeIntoKeeper does nothing when keeper ownership mismatches`() = runTest {
        val id1 = store.add(FactStore.Fact(fact = "la memoria de a"))
        val id2 = store.add(FactStore.Fact(fact = "la memoria de b"))
        val removed = store.mergeIntoKeeper(
            keeperId = id1,
            mergedContent = "Contenido fusionado",
            removableIds = listOf(id2),
            scope = "other_scope",
            spaceId = "default",
        )
        assertEquals("No se realizarán cambios si la atribución no coincide", -1, removed)
        assertEquals("Se conservan ambos hechos originales.", 2, store.getByScopeAndSpace("main", "default").size)
    }

    @Test
    fun llmFailureDegradesSilently() = runTest {
        seedSimilarPair()
        val llm = object : MemoryLlmClient {
            override suspend fun callText(
                systemPrompt: String,
                userContent: String,
                model: Model?,
                temperature: Float,
                maxTokens: Int,
                timeoutMs: Long,
            ): String = throw RuntimeException("LLM timeout")
        }
        val consolidator = LlmFactConsolidator(llm)

        val merged = consolidator.consolidate(store, "main", "default")

        assertEquals(0, merged)
        assertEquals("Los datos permanecen sin cambios en caso de falla", 2, store.getByScopeAndSpace("main", "default").size)
    }
}
