package io.zer0.muse.perf

import io.zer0.muse.ui.markdown.MarkdownBlock
import io.zer0.muse.ui.markdown.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Casos de uso de Edge para el almacenamiento en caché de renderizado.
 *
 * Esté atento a tres cosas:
 *  - Markdown sin transmisión llega directamente al caché cuando el mismo texto se ingresa en la composición por segunda vez.(No más análisis);
 *  - Cuando el caché excede el límite superior, se eliminará el elemento no utilizado más antiguo.,en lugar de un crecimiento infinito;
 *  - data-URI La imagen ya no se volverá a decodificar cuando llegue al caché.
 *
 * Los cachés son todos singleton a nivel de proceso.,Borre antes de que comience cada caso de uso.
 */
class RenderCachesTest {

    @Before
    fun resetCaches() {
        MarkdownParseCache.clear()
        DataUriImageCache.clear()
    }

    /** Función analítica para contar.:Omita el caché y vaya directamente al análisis real,Se utiliza para contar cuántas veces se analizó realmente. */
    private fun countingParser(counter: () -> Unit): (String) -> List<MarkdownBlock> = {
        counter()
        parseMarkdown(it)
    }

    // ── Markdown analizar caché ──

    @Test
    fun `same content parsed only once`() {
        var parseCount = 0
        val parse = countingParser { parseCount++ }

        val first = MarkdownParseCache.getOrParse("msg-1", "## Título\nTexto", parse)
        val second = MarkdownParseCache.getOrParse("msg-1", "## Título\nTexto", parse)

        assertEquals("La segunda solicitud llega al caché.,sin reparar", 1, parseCount)
        assertSame("Devuelve el mismo resultado de análisis cuando se golpea", first, second)
        assertTrue("El resultado analizado no debe estar vacío.", first.isNotEmpty())
    }

    @Test
    fun `different content parsed separately`() {
        var parseCount = 0
        val parse = countingParser { parseCount++ }

        MarkdownParseCache.getOrParse("msg-1", "primer párrafo", parse)
        MarkdownParseCache.getOrParse("msg-1", "Segundo párrafo", parse)
        MarkdownParseCache.getOrParse("msg-1", "primer párrafo", parse)

        assertEquals("Cada uno de los dos párrafos de contenido se analiza una vez.,tercer golpe", 2, parseCount)
    }

    @Test
    fun `cache key includes message id and content hash`() {
        val base = MarkdownParseCache.keyOf("msg-1", "el mismo texto")

        assertNotEquals("Diferentes mensajes con el mismo contenido no comparten espacios", base, MarkdownParseCache.keyOf("msg-2", "el mismo texto"))
        assertNotEquals("Los mismos cambios en el contenido del mensaje deben reemplazarse. key", base, MarkdownParseCache.keyOf("msg-1", "Texto corregido"))
        assertEquals(
            "Degenera en contenido cuando no hay identificación de mensaje hash,Contenido similar tiene la misma clave",
            MarkdownParseCache.keyOf(null, "el mismo texto"),
            MarkdownParseCache.keyOf(null, "el mismo texto"),
        )
        assertNotEquals(
            "Cuando no hay identificación, aún es necesario distinguir diferentes contenidos.",
            MarkdownParseCache.keyOf(null, "un"),
            MarkdownParseCache.keyOf(null, "b"),
        )
    }

    @Test
    fun `eldest entry evicted when over capacity`() {
        val parse: (String) -> List<MarkdownBlock> = { emptyList() }
        repeat(MARKDOWN_CACHE_MAX_ENTRIES) { index ->
            MarkdownParseCache.getOrParse(null, "contenido-$index", parse)
        }
        assertEquals(MARKDOWN_CACHE_MAX_ENTRIES, MarkdownParseCache.size())

        val newest = "contenido-$MARKDOWN_CACHE_MAX_ENTRIES"
        MarkdownParseCache.getOrParse(null, newest, parse)

        assertEquals("Una vez que la capacidad alcance el límite superior, la cantidad de elementos ya no aumentará.", MARKDOWN_CACHE_MAX_ENTRIES, MarkdownParseCache.size())
        assertFalse(
            "Se debe eliminar la entrada escrita más antigua.",
            MarkdownParseCache.containsKey(MarkdownParseCache.keyOf(null, "contenido-0")),
        )
        assertTrue(
            "Se debe conservar la entrada escrita más reciente.",
            MarkdownParseCache.containsKey(MarkdownParseCache.keyOf(null, newest)),
        )
    }

    @Test
    fun `oversized content not cached`() {
        var parseCount = 0
        val parse: (String) -> List<MarkdownBlock> = {
            parseCount++
            emptyList()
        }
        val huge = "x".repeat(MARKDOWN_CACHE_MAX_CHARS + 1)

        MarkdownParseCache.getOrParse(null, huge, parse)
        MarkdownParseCache.getOrParse(null, huge, parse)

        assertEquals("El texto muy largo no se almacenará en caché,analizado ambas veces", 2, parseCount)
        assertEquals("El texto extralargo no ocupa espacios de caché", 0, MarkdownParseCache.size())
    }

    // ── Universales LRU ──

    @Test
    fun `lru evicts least recently used`() {
        val cache = BoundedLruCache<String, String>(2) { 1 }
        cache.put("a", "A")
        cache.put("b", "B")
        assertNotNull("visita a,Empujarlo a usado recientemente", cache.get("a"))

        cache.put("c", "C")

        assertNotNull("a recién visitado,debe ser retenido", cache.get("a"))
        assertFalse("b El que lleva más tiempo sin usarse,debe ser eliminado", cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
    }

    @Test
    fun `lru evicts by size not only by count`() {
        val cache = BoundedLruCache<String, String>(10) { it.length }
        cache.put("k1", "aaaa")
        cache.put("k2", "bbbb")
        assertEquals(8, cache.totalSize())

        cache.put("k3", "cccc")

        assertEquals("Los límites en bytes están vigentes.", 8, cache.totalSize())
        assertEquals(2, cache.size())
    }

    // ── data-URI Caché de imagen ──

    @Test
    fun `image cache hit does not decode again`() {
        val cache = BoundedLruCache<String, String>(1024) { it.length }
        var decodeCount = 0

        val first = cachedDecode(cache, "img-1") {
            decodeCount++
            "decoded-bitmap"
        }
        val second = cachedDecode(cache, "img-1") {
            decodeCount++
            "decoded-bitmap"
        }

        assertEquals("No se debe volver a decodificar al acceder al caché", 1, decodeCount)
        assertSame(first, second)
    }

    @Test
    fun `image cache key distinguishes different data uris`() {
        val a = DataUriImageCache.keyOf("data:image/png;base64,AAAA")
        val b = DataUriImageCache.keyOf("data:image/png;base64,BBBB")

        assertNotEquals(a, b)
        assertEquals(a, DataUriImageCache.keyOf("data:image/png;base64,AAAA"))
    }
}
