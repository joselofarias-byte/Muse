package io.zer0.muse.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.x: Prueba única del analizador de etiquetas de emoticones — marcado completo/múltiples etiquetas/transmisión de la mitad/valla de código/Tolerancia a fallos.
 */
class StickerMarkupTest {
    // ── findMarkers ─────────────────────────────────────────────────────

    @Test
    fun `finds single complete marker`() {
        val markers = StickerMarkup.findMarkers("hoy es tan bueno [[sticker:feliz]]")
        assertEquals(1, markers.size)
        assertEquals("feliz", markers[0].category)
    }

    @Test
    fun `finds multiple markers in order`() {
        val markers = StickerMarkup.findMarkers("a [[sticker:feliz]] b [[sticker:Sin palabras]] c")
        assertEquals(listOf("feliz", "Sin palabras"), markers.map { it.category })
    }

    @Test
    fun `case insensitive and spaces trimmed`() {
        val markers = StickerMarkup.findMarkers("[[STICKER: feliz ]]")
        assertEquals("feliz", markers.single().category)
    }

    @Test
    fun `skips markers inside code fence`() {
        val text = "```\n[[sticker:feliz]]\n```\n[[sticker:Sin palabras]]"
        val markers = StickerMarkup.findMarkers(text)
        assertEquals(listOf("Sin palabras"), markers.map { it.category })
    }

    @Test
    fun `does not match non sticker double brackets`() {
        assertTrue(StickerMarkup.findMarkers("[[wiki link]]").isEmpty())
    }

    @Test
    fun `marker positions are correct`() {
        val text = "Hola [[sticker:feliz]] fin"
        val marker = StickerMarkup.findMarkers(text).single()
        assertEquals(text.substring(marker.start, marker.end), "[[sticker:feliz]]")
    }

    // ── findOpenMarkerStart ─────────────────────────────────────────────

    @Test
    fun `detects unclosed marker while streaming`() {
        assertTrue(StickerMarkup.findOpenMarkerStart("OK [[sticker:abierto") >= 0)
        assertTrue(StickerMarkup.findOpenMarkerStart("OK [[st") >= 0)
        assertTrue(StickerMarkup.findOpenMarkerStart("OK [[") >= 0)
        assertTrue(StickerMarkup.findOpenMarkerStart("OK [[sticker:") >= 0)
    }

    @Test
    fun `closed marker is not reported open`() {
        assertEquals(-1, StickerMarkup.findOpenMarkerStart("OK [[sticker:feliz]] si"))
    }

    @Test
    fun `unrelated double bracket is not swallowed`() {
        assertEquals(-1, StickerMarkup.findOpenMarkerStart("mira esto [[foo"))
        assertEquals(-1, StickerMarkup.findOpenMarkerStart("Texto normal sin corchetes"))
    }

    // ── split ───────────────────────────────────────────────────────────

    @Test
    fun `split returns single text segment when no marker`() {
        val segs = StickerMarkup.split("respuesta normal")
        assertEquals(1, segs.size)
        assertTrue(segs[0] is StickerMarkup.Segment.Text)
    }

    @Test
    fun `split produces text and sticker segments`() {
        val segs = StickerMarkup.split("hoy es tan bueno [[sticker:feliz]]")
        assertEquals(2, segs.size)
        assertEquals("hoy es tan bueno", (segs[0] as StickerMarkup.Segment.Text).text)
        assertEquals("feliz", (segs[1] as StickerMarkup.Segment.Sticker).category)
    }

    @Test
    fun `split swallows unclosed marker tail`() {
        val segs = StickerMarkup.split("OK [[sticker:abierto")
        assertEquals(1, segs.size)
        assertEquals("OK", (segs[0] as StickerMarkup.Segment.Text).text)
    }

    @Test
    fun `split handles marker in the middle`() {
        val segs = StickerMarkup.split("antes [[sticker:Sin palabras]] después")
        assertEquals(3, segs.size)
        assertEquals("antes", (segs[0] as StickerMarkup.Segment.Text).text)
        assertEquals("Sin palabras", (segs[1] as StickerMarkup.Segment.Sticker).category)
        assertEquals("después", (segs[2] as StickerMarkup.Segment.Text).text)
    }

    @Test
    fun `split handles adjacent markers`() {
        val segs = StickerMarkup.split("[[sticker:feliz]][[sticker:Sin palabras]]")
        assertEquals(2, segs.size)
        assertEquals("feliz", (segs[0] as StickerMarkup.Segment.Sticker).category)
        assertEquals("Sin palabras", (segs[1] as StickerMarkup.Segment.Sticker).category)
    }

    @Test
    fun `split drops marker-only blank text segments`() {
        val segs = StickerMarkup.split("[[sticker:feliz]]")
        assertEquals(1, segs.size)
        assertTrue(segs[0] is StickerMarkup.Segment.Sticker)
    }

    @Test
    fun `split keeps paragraph text around marker`() {
        val segs = StickerMarkup.split("Primer párrafo.\n\n[[sticker:feliz]]\n\nSegundo párrafo.")
        assertEquals(3, segs.size)
        assertEquals("Primer párrafo.", (segs[0] as StickerMarkup.Segment.Text).text)
        assertEquals("Segundo párrafo.", (segs[2] as StickerMarkup.Segment.Text).text)
    }
}
