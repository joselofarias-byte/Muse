package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `displayContent` Los límites de la caché de resultados.
 *
 * La única responsabilidad del caché es “no lavar el mismo texto una segunda vez”, por lo que se deben clavar cuatro cosas a la vez:
 *  - No se volverá a calcular al acertar y se devolverá el mismo resultado;
 *  - Si el texto cambia, se debe cambiar el resultado. El último texto no debe considerarse como el resultado actual;
 *  - Cuando se supere el límite superior, se eliminará el extremo que lleve más tiempo sin utilizarse, y la capacidad será constante;
 *  - Salida y sin almacenamiento en caché `stripForDisplay` Consistente byte a byte.
 */
class DisplayContentCacheTest {

    private val cardStateTail = "${InternalMarkupSanitizer.CARD_STATE_OPEN}{\"cardId\":\"c1\"}${InternalMarkupSanitizer.CARD_STATE_CLOSE}"

    private fun countingCompute(counter: () -> Unit): (String) -> String = {
        counter()
        InternalMarkupSanitizer.stripForDisplay(it)
    }

    @Test
    fun `second call hits the cache instead of recomputing`() {
        var computeCount = 0
        val cache = DisplayContentCache(maxEntries = 8)
        val compute = countingCompute { computeCount++ }
        val raw = "<think>pensamiento interno</think>Texto$cardStateTail"

        val first = cache.getOrCompute(raw, compute)
        val second = cache.getOrCompute(raw, compute)

        assertEquals("El mismo párrafo de texto sólo debe lavarse una vez", 1, computeCount)
        assertSame("Se debe devolver el mismo resultado al golpear", first, second)
        assertEquals("Texto", first)
    }

    @Test
    fun `changed content never returns the stale result`() {
        var computeCount = 0
        val cache = DisplayContentCache(maxEntries = 8)
        val compute = countingCompute { computeCount++ }

        val before = cache.getOrCompute("<think>pensamiento interno</think>texto antiguo", compute)
        val after = cache.getOrCompute("<think>pensamiento interno</think>Nuevo texto", compute)

        assertEquals("texto antiguo", before)
        assertEquals("El cálculo debe volver a calcularse después de que cambie el texto y no se puede devolver el último resultado.", "Nuevo texto", after)
        assertNotEquals(before, after)
        assertEquals(2, computeCount)
    }

    @Test
    fun `oldest entry is evicted when capacity is exceeded`() {
        val cache = DisplayContentCache(maxEntries = 2)
        val compute: (String) -> String = { it }

        cache.getOrCompute("un", compute)
        cache.getOrCompute("b", compute)
        // Visite A y empújelo hasta el extremo utilizado más recientemente; En este momento, B no se ha utilizado durante más tiempo.
        cache.getOrCompute("un", compute)
        cache.getOrCompute("c", compute)

        assertEquals("Una vez que la capacidad alcance el límite superior, la cantidad de elementos ya no aumentará.", 2, cache.size())
        assertTrue("Las entradas a las que se acaba de acceder deben conservarse", cache.containsKey("un"))
        assertFalse("Se deben eliminar las entradas que no se hayan utilizado durante más tiempo.", cache.containsKey("b"))
        assertTrue(cache.containsKey("c"))
    }

    @Test
    fun `oversized content is computed but not cached`() {
        val cache = DisplayContentCache(maxEntries = 8)
        val huge = "palabra".repeat(DISPLAY_CONTENT_CACHE_MAX_CHARS + 1)

        assertEquals(huge, cache.getOrCompute(huge) { it })
        assertEquals("El texto extralargo no ocupa espacios de caché", 0, cache.size())
        assertFalse(cache.containsKey(huge))
    }

    @Test
    fun `displayContent output is byte identical to stripForDisplay`() {
        val samples = listOf(
            "",
            "   \n  ",
            "Texto normal",
            "<think>pensamiento interno</think>Texto",
            "<mood>de buen humor</mood><mod>Prepárate para responder</mod>Texto",
            "Texto$cardStateTail",
            "en\n\n\n\nabajo",
            "[glow]brillante[/glow]Texto",
            "List<String> con [enlace](https://a.b)",
        )
        for (sample in samples) {
            val expected = InternalMarkupSanitizer.stripForDisplay(sample)
            assertEquals(
                "El almacenamiento en caché no debe alterar la salida: <$sample>",
                expected,
                InternalMarkupSanitizer.displayContent(sample),
            )
            // La segunda vez que reviso el caché, el resultado sigue siendo exactamente el mismo.
            assertEquals(
                "La salida no debe cambiar después del acceso al caché: <$sample>",
                expected,
                InternalMarkupSanitizer.displayContent(sample),
            )
        }
    }

    @Test
    fun `editing a message shows the new text instead of the cached one`() {
        assertEquals("texto antiguo", InternalMarkupSanitizer.displayContent("<think>pensamiento interno</think>texto antiguo"))
        assertEquals("Nuevo texto", InternalMarkupSanitizer.displayContent("<think>pensamiento interno</think>Nuevo texto"))
        assertEquals("Texto", InternalMarkupSanitizer.displayContent("Texto$cardStateTail"))
    }
}
