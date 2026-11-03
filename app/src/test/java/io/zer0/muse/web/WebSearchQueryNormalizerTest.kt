package io.zer0.muse.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchQueryNormalizerTest {
    @Test
    fun `removes malformed smart quotes and outer ascii quotes`() {
        assertEquals(
            "ornith1.5-35ba3b modelo OR model",
            WebSearchQueryNormalizer.normalize("\"\"ornith1.5-35ba3b”modelo OR model\""),
        )
    }

    @Test
    fun `keeps a normal query unchanged`() {
        assertEquals("DeepSeek V4 Flash modelo", WebSearchQueryNormalizer.normalize("DeepSeek V4 Flash modelo"))
    }

    @Test
    fun `collapses invisible characters and whitespace`() {
        assertEquals("OpenAI model", WebSearchQueryNormalizer.normalize("  OpenAI\u200B   model  "))
    }

    @Test
    fun `removes unmatched quote`() {
        assertEquals("ornith model", WebSearchQueryNormalizer.normalize("ornith \"model"))
    }

    @Test
    fun `refine keeps short keyword queries unchanged`() {
        assertEquals("DeepSeek V4 Flash modelo", WebSearchQueryNormalizer.refine("DeepSeek V4 Flash modelo"))
    }

    @Test
    fun `refine strips conversational lead-in from long queries`() {
        assertEquals(
            "¿Podrías ayudarme a ver alguna buena recomendación de películas",
            WebSearchQueryNormalizer.refine("¿Podrías ayudarme a ver alguna buena recomendación de películas de ciencia ficción recientemente?"),
        )
    }

    @Test
    fun `refine strips trailing modal particle`() {
        assertEquals(
            "¿Cuánto tarda el tren de alta velocidad de Pekín a Shanghái?",
            WebSearchQueryNormalizer.refine("¿Cuánto tarda el tren de alta velocidad de Pekín a Shanghái? gracias"),
        )
    }

    @Test
    fun `refine is idempotent`() {
        val once = WebSearchQueryNormalizer.refine("Ayúdenme a buscar el ranking de ventas de vehículos de nuevas energías en 2026.")
        assertEquals(once, WebSearchQueryNormalizer.refine(once))
    }

    @Test
    fun `refine truncates over long query at word boundary`() {
        val long = "por favor" + " keyword".repeat(20).trim()
        val refined = WebSearchQueryNormalizer.refine(long)
        assert(refined.length <= 64) { "refined too long: ${refined.length}" }
    }
}
