package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuoteUtilsTest {

    @Test
    fun buildQuotedContent_roundTripsThroughParser() {
        val content = buildQuotedContent("Noticia original citada", "esta es mi respuesta")

        val (quote, body) = parseQuotedContent(content)

        assertEquals("Noticia original citada", quote)
        assertEquals("esta es mi respuesta", body)
        assertNull(parseQuotedContent("noticias generales").first)
        assertEquals("noticias generales", parseQuotedContent("noticias generales").second)
    }

    @Test
    fun buildQuotedContent_multilineQuoteKeepsAllLines() {
        val content = buildQuotedContent("primera linea\nsegunda linea", "Texto")

        val (quote, body) = parseQuotedContent(content)

        assertEquals("primera linea\nsegunda linea", quote)
        assertEquals("Texto", body)
    }

    @Test
    fun buildQuotedContent_emptyQuoteReturnsBodyOnly() {
        assertEquals("Sólo texto", buildQuotedContent("", "Sólo texto"))
    }
}
