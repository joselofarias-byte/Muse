package io.zer0.muse.web

import io.zer0.common.AppJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `memory.list` / `memory.search` prueba de proyección de carga(Paginación, truncamiento, fragmentos, categorías de fuentes.). */
class HostMemoryReadsTest {

    private fun record(
        id: Long,
        content: String = "memoria $id",
        source: String = HostMemorySourceKind.CONVERSATION,
        pinned: Boolean = false,
    ) = HostMemoryRecord(
        id = id.toString(),
        content = content,
        createdAt = "2026-10-31T0$id:00:00Z",
        source = source,
        pinned = pinned,
    )

    /** 1..[count] entrada,Simular identificación de incremento automático. */
    private fun records(count: Int): List<HostMemoryRecord> = (1..count).map { record(it.toLong()) }

    @Test
    fun `list returns thirty entries per page and continues from the cursor`() {
        val all = records(75)

        val first = HostMemoryReads.page(all, cursor = null, limit = HostMemoryReads.DEFAULT_LIMIT)
        assertEquals(30, first.entries.size)
        assertTrue(first.hasMore)
        // Lo más nuevo en stock primero:75 comenzando con el número,46 El número termina.
        assertEquals("75", first.entries.first().id)
        val cursor = first.entries.last().id.toLong()
        assertEquals("46", first.entries.last().id)

        val second = HostMemoryReads.page(all, cursor = cursor, limit = HostMemoryReads.DEFAULT_LIMIT)
        assertEquals(30, second.entries.size)
        assertTrue(second.hasMore)
        assertEquals("45", second.entries.first().id)

        val third = HostMemoryReads.page(all, cursor = second.entries.last().id.toLong(), limit = HostMemoryReads.DEFAULT_LIMIT)
        assertEquals(15, third.entries.size)
        assertFalse("La última página no debe mencionarse y hay otra página.", third.hasMore)
        assertEquals("1", third.entries.last().id)

        // Las tres páginas juntas son exactamente la colección completa.,Sin duplicaciones ni omisiones.
        val ids = (first.entries + second.entries + third.entries).map { it.id }
        assertEquals(75, ids.size)
        assertEquals(75, ids.distinct().size)
    }

    @Test
    fun `list on an empty database answers with an empty array`() {
        val event = HostMemoryReads.listEvent(
            records = emptyList(),
            cursor = null,
            limit = HostMemoryReads.DEFAULT_LIMIT,
            requestId = "req-empty",
        )

        assertEquals(HostMemoryReads.TYPE_LIST, event.type)
        assertEquals("req-empty", event.requestId)
        assertTrue(event.memory.isEmpty())
        assertEquals(false, event.hasMore)
        assertNull(event.error)
    }

    @Test
    fun `limit above the cap is clamped to one hundred`() {
        val page = HostMemoryReads.page(records(150), cursor = null, limit = 500)

        assertEquals(HostMemoryReads.MAX_LIMIT, page.entries.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a cursor that is no longer in the list still continues by id`() {
        // La última entrada en la página anterior fue eliminada.:Ancla no encontrada,También debemos continuar recuperando la identificación anterior.,No puedo volver a la primera página.
        val page = HostMemoryReads.page(records(10), cursor = 4, limit = 3)

        assertEquals(listOf("3", "2", "1"), page.entries.map { it.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun `list truncates long content to two hundred characters`() {
        val long = "Memoria muy larga.".repeat(100)

        val event = HostMemoryReads.listEvent(listOf(record(1, content = long)), null, 30, "req-trunc")

        val content = event.memory.single().content
        assertEquals(HostMemoryReads.CONTENT_LIMIT, content.length)
        assertTrue(long.startsWith(content))
    }

    @Test
    fun `list carries the pinned flag and the source kind`() {
        val event = HostMemoryReads.listEvent(
            records = listOf(
                record(2, source = HostMemorySourceKind.MANUAL, pinned = true),
                record(1, source = HostMemorySourceKind.AUTO),
            ),
            cursor = null,
            limit = 30,
            requestId = "req-flags",
        )

        assertTrue(event.memory.first().pinned)
        assertEquals(HostMemorySourceKind.MANUAL, event.memory.first().source)
        assertFalse(event.memory[1].pinned)
        assertEquals(HostMemorySourceKind.AUTO, event.memory[1].source)
    }

    @Test
    fun `search returns hits with a snippet around the keyword`() {
        val content = "A los usuarios les gusta acudir los fines de semana a librerías de segunda mano en el oeste de la ciudad." + "Hay muchos detalles más adelante.".repeat(20)

        val event = HostMemoryReads.searchEvent(
            query = "segunda mano",
            records = listOf(record(7, content = content, pinned = true)),
            requestId = "req-search",
        )

        assertEquals(HostMemoryReads.TYPE_SEARCH, event.type)
        val hit = event.memoryHits.single()
        assertTrue("El fragmento debe contener la palabra clave.: ${hit.snippet}", hit.snippet.contains("segunda mano"))
        assertTrue(hit.snippet.length <= HostMemoryReads.SNIPPET_LIMIT)
        assertEquals("7", hit.entry.id)
        assertTrue(hit.entry.pinned)
        // La estructura de entrada es la misma que la lista.(Texto truncado a 200 palabras).
        assertTrue(hit.entry.content.length <= HostMemoryReads.CONTENT_LIMIT)
    }

    @Test
    fun `search with no hit returns an empty array`() {
        val event = HostMemoryReads.searchEvent(query = "algo que no existe", records = emptyList(), requestId = "req-miss")

        assertEquals(HostMemoryReads.TYPE_SEARCH, event.type)
        assertTrue(event.memoryHits.isEmpty())
        assertNull(event.error)
    }

    @Test
    fun `source kind follows the session and the fact origin`() {
        assertEquals(HostMemorySourceKind.CONVERSATION, HostMemoryReads.sourceKind("session-1", "inferred"))
        assertEquals(HostMemorySourceKind.MANUAL, HostMemoryReads.sourceKind(null, "user_explicit"))
        assertEquals(HostMemorySourceKind.AUTO, HostMemoryReads.sourceKind(null, "inferred"))
        assertEquals(HostMemorySourceKind.AUTO, HostMemoryReads.sourceKind("  ", "imported"))
    }

    @Test
    fun `new payload fields survive the wire round trip`() {
        val event = HostMemoryReads.listEvent(records(3), cursor = null, limit = 2, requestId = "req-wire")

        val decoded = AppJson.decodeFromString<HostEvent>(AppJson.encodeToString(event))

        assertEquals(event, decoded)
        assertEquals(2, decoded.memory.size)
        assertEquals(true, decoded.hasMore)
    }
}
