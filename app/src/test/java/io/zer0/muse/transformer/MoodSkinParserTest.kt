package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoodSkinParserTest {

    @Test
    fun extract_moodfxDoesNotTouchMood() {
        val input = """
            <mood>
            Vibe: calma
            </mood>
            <moodfx>rage</moodfx>
            Contenido del texto [glow]centrarse[/glow]
        """.trimIndent()

        val (skin, content) = MoodSkinParser.extract(input)

        assertEquals("rage", skin)
        assertEquals(false, content.contains("<moodfx>"))
        assertEquals(true, content.contains("<mood>"))
        assertEquals("centrarse", MoodSkinParser.stripInlineEffects(content).substringAfter("Contenido del texto ").trim())
    }

    @Test
    fun cleanForExport_removesAllProtocolTags() {
        val input = "<moodfx>desire</moodfx> Hola [huge]mundo[/huge] [red]peligro[/red]"
        val cleaned = MoodSkinParser.cleanForExport(input)
        assertEquals(" Hola mundo peligro", cleaned)
    }

    @Test
    fun extract_ignoresUnknownSkin() {
        val input = "<moodfx>nope</moodfx>Texto"
        val (skin, content) = MoodSkinParser.extract(input)
        assertNull(skin)
        assertEquals("Texto", content.trim())
    }
}
