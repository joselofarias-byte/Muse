package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Limpiar caché de resultados.
 *
 * Bloquee dos cosas: el mismo contenido debe llegar al objeto original la segunda vez que ingresa (de lo contrario, significa que no hay caché),
 * Y el límite de entrada está vigente (el caché no puede crecer hasta convertirse en memoria ilimitada).
 */
class SanitizedHtmlCacheTest {

    @Before
    fun setUp() {
        SanitizedHtmlCache.clear()
    }

    @Test
    fun `same_content_returns_same_instance`() {
        val code = "<div onclick=\"go()\">hello</div>"
        val first = SanitizedHtmlCache.get(code)
        val second = SanitizedHtmlCache.get(code)
        assertSame("El mismo contenido debe llegar al caché.", first, second)
        assertEquals(1, SanitizedHtmlCache.size())
    }

    @Test
    fun `different_content_is_sanitized_separately`() {
        val a = SanitizedHtmlCache.get("<div>a</div>")
        val b = SanitizedHtmlCache.get("<div>b</div>")
        assertNotSame(a, b)
        assertEquals(2, SanitizedHtmlCache.size())
    }

    @Test
    fun `cache_is_bounded`() {
        repeat(40) { index ->
            SanitizedHtmlCache.get("<div id=\"n$index\">x</div>")
        }
        assertTrue(
            "En la práctica, las entradas de caché deben tener un límite ${SanitizedHtmlCache.size()}",
            SanitizedHtmlCache.size() <= 12,
        )
    }

    @Test
    fun `cached_result_equals_direct_sanitize`() {
        val code = "<iframe src=\"https://evil\"></iframe><form action=\"/x\"></form><div>ok</div>"
        val cached = SanitizedHtmlCache.get(code)
        assertEquals(sanitizeHtml(code), cached)
        assertTrue("iframe debe ser aclarado", !cached.contains("iframe"))
        assertTrue("form debe ser aclarado", !cached.contains("<form"))
    }

    @Test
    fun `oversized_content_bypasses_cache`() {
        val huge = "<div>" + "x".repeat(70_000) + "</div>"
        val result = SanitizedHtmlCache.get(huge)
        assertEquals(0, SanitizedHtmlCache.size())
        assertTrue(result.contains("xxxx"))
    }
}
