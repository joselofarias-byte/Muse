package io.zer0.muse.tools

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.memory.compile.CompiledSectionHit
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * search_memory El resumen de la compilación retrocede.
 *
 * Cobertura:facts Cuando la tabla esté completamente vacía, retrocederá y marcará la fuente.facts No habrá respaldo cuando haya un acierto, y la copia del resultado vacío original se mantendrá cuando no haya una entrada de respaldo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchMemoryToolCompiledFallbackTest {

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

    private fun context(): ToolExecutionContext = ToolExecutionContext(scope = "main", spaceId = "default")

    @Test
    fun `compiled summary answers when the facts table has nothing`() = runBlocking {
        var seenScope: String? = null
        var seenSpace: String? = null
        val result = SearchMemoryTool.execute(
            mapOf("query" to "Senderismo Ziqi"),
            store,
            context(),
            SearchMemoryTool.CompiledSummarySearch { _, scope, spaceId, _ ->
                seenScope = scope
                seenSpace = spaceId
                listOf(CompiledSectionHit("today", "Ziqi fue de excursión a las montañas hoy."))
            },
        )

        assertEquals("main", seenScope)
        assertEquals("default", seenSpace)
        assertTrue("Los resultados deben indicar que la fuente es un resumen compilado.: $result", result.contains("compilar resumen"))
        assertTrue("Los resultados deben ir acompañados del resumen original.: $result", result.contains("Ziqi fue de excursión a las montañas hoy."))
    }

    @Test
    fun `fact hits keep the compiled summary out of the result`() = runBlocking {
        store.add(FactStore.Fact(fact = "A Ziqi le gusta el senderismo"), "main", "default")

        val result = SearchMemoryTool.execute(
            mapOf("query" to "Ziqi"),
            store,
            context(),
            SearchMemoryTool.CompiledSummarySearch { _, _, _, _ ->
                listOf(CompiledSectionHit("today", "Párrafo resumen que no debería aparecer"))
            },
        )

        assertTrue("Las entradas de hechos deben devolverse: $result", result.contains("Found 1 memory fact"))
        assertFalse("El resumen no debe retroceder cuando los hechos llegan: $result", result.contains("compilar resumen"))
        assertFalse(result.contains("Párrafo resumen que no debería aparecer"))
    }

    @Test
    fun `without a summary reader the empty answer stays unchanged`() = runBlocking {
        val result = SearchMemoryTool.execute(mapOf("query" to "Senderismo Ziqi"), store, context())

        assertEquals("No matching memories found.", result)
    }
}
