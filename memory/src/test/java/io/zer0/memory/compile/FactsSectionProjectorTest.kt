package io.zer0.memory.compile

import io.zer0.memory.fact.FactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D3-P2: FACTS Pruebas de proyectores deterministas de segmento. */
class FactsSectionProjectorTest {

    private fun fact(
        text: String,
        importance: Int = 0,
        pinnedAt: String? = null,
        createdAt: String = "2026-01-01T00:00:00Z",
        lastConfirmedAt: String? = null,
        id: Long = 0,
        supersedesId: Long? = null,
    ) = FactStore.Fact(
        id = id,
        fact = text,
        importance = importance,
        pinnedAt = pinnedAt,
        createdAt = createdAt,
        lastConfirmedAt = lastConfirmedAt,
        supersedesId = supersedesId,
    )

    @Test
    fun `empty input returns blank`() {
        assertEquals("", FactsSectionProjector.project(emptyList()))
    }

    @Test
    fun `renders one fact per line without bullet prefix`() {
        val out = FactsSectionProjector.project(listOf(fact("Me gusta la fotografía"), fact("Vivir en Chengdú")))
        val lines = out.lines()
        assertEquals(2, lines.size)
        assertTrue("No debe haber ningún prefijo de viñeta o título.", lines.none { it.startsWith("-") || it.startsWith("#") })
    }

    @Test
    fun `pinned first then importance then recency`() {
        val out = FactsSectionProjector.project(
            listOf(
                fact("ordinario", importance = 0, createdAt = "2026-03-01T00:00:00Z"),
                fact("importante", importance = 2, createdAt = "2026-01-01T00:00:00Z"),
                fact("fijar hacia arriba", importance = 0, pinnedAt = "2026-02-01T00:00:00Z"),
            ),
        )
        val lines = out.lines()
        assertEquals("fijar hacia arriba", lines[0])
        assertEquals("importante", lines[1])
        assertEquals("ordinario", lines[2])
    }

    @Test
    fun `respects maxItems`() {
        val facts = (1..10).map { fact("hechos$it") }
        val out = FactsSectionProjector.project(facts, FactsSectionProjector.Config(maxItems = 3))
        assertEquals(3, out.lines().size)
    }

    @Test
    fun `respects maxChars and truncates long items`() {
        val facts = (1..5).map { fact("No.$it Artículo" + "muy largo".repeat(50)) }
        val out = FactsSectionProjector.project(
            facts,
            FactsSectionProjector.Config(maxItems = 10, maxChars = 100, maxItemChars = 30),
        )
        assertTrue("Debería estar limitado por maxChars, real ${out.length}", out.length <= 102)
        assertTrue("Las entradas demasiado largas deben truncarse con puntos suspensivos.", out.contains("…"))
    }

    @Test
    fun `blank facts are skipped`() {
        val out = FactsSectionProjector.project(listOf(fact("  "), fact("válido")))
        assertEquals("válido", out)
    }

    /** v18: Los viejos recuerdos señalados por reemplaza no se incluyen en la sección HECHOS. */
    @Test
    fun `superseded fact is not projected`() {
        val out = FactsSectionProjector.project(
            listOf(
                fact("A Zhang San le gusta tomar café.", id = 1),
                fact("Zhang San odia tomar café", id = 2, supersedesId = 1),
            ),
        )
        assertEquals("Zhang San odia tomar café", out)
    }

    /** v18: Cuando la persona que señala no está en la entrada de proyección(Si ha sido eliminado),Los viejos recuerdos regresan al contexto. */
    @Test
    fun `old fact returns when the superseding row is gone`() {
        val out = FactsSectionProjector.project(listOf(fact("A Zhang San le gusta tomar café.", id = 1)))
        assertEquals("A Zhang San le gusta tomar café.", out)
    }
}
