package io.zer0.muse.perf

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [MessagePaginator] Prueba única de lógica pura.
 *
 * es"Modo de rendimiento"La implementación subyacente de la paginación a nivel de memoria ChatScreen(Utilizado por ChatScreen),
 * Anteriormente la cobertura era nula. Paginación desde**cola(Lo último)**Empezar a sumar,Este es el punto más común para escribir incorrectamente.
 */
class MessagePaginatorTest {

    @Test
    fun `empty list emits a single empty page`() = runTest {
        val pages = MessagePaginator.createFlow(emptyList(), pageSize = 10).toList()
        assertEquals(1, pages.size)
        assertEquals(emptyList<String>(), pages[0])
    }

    @Test
    fun `list shorter than page size emits one page covering all`() = runTest {
        val pages = MessagePaginator.createFlow(listOf("a", "b", "c"), pageSize = 10).toList()
        assertEquals(1, pages.size)
        assertEquals(listOf("a", "b", "c"), pages[0])
    }

    @Test
    fun `pages accumulate from the tail`() = runTest {
        val ids = (1..5).map { "m$it" }
        val pages = MessagePaginator.createFlow(ids, pageSize = 2).toList()
        assertEquals(3, pages.size)
        assertEquals("La primera pantalla deberían ser las dos últimas.", listOf("m4", "m5"), pages[0])
        assertEquals(listOf("m2", "m3", "m4", "m5"), pages[1])
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), pages[2])
    }

    @Test
    fun `exact multiple of page size emits exactly the expected pages`() = runTest {
        val pages = MessagePaginator.createFlow(listOf("a", "b", "c", "d"), pageSize = 2).toList()
        assertEquals(2, pages.size)
        assertEquals(listOf("c", "d"), pages[0])
        assertEquals(listOf("a", "b", "c", "d"), pages[1])
    }

    @Test
    fun `cache key is stable and differs by id or content`() {
        val k1 = MessagePaginator.contentCacheKey("id1", "hello")
        assertEquals("La misma identificación + contenido debería obtener el mismo key", k1, MessagePaginator.contentCacheKey("id1", "hello"))
        assertNotEquals("id Las diferentes claves deberían ser diferentes", k1, MessagePaginator.contentCacheKey("id2", "hello"))
        assertNotEquals("content Las diferentes claves deberían ser diferentes", k1, MessagePaginator.contentCacheKey("id1", "world"))
    }
}
