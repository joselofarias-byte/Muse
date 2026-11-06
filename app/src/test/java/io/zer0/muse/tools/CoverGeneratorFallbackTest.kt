package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R-TEST-18: CoverGenerator La salida LLM vacía se degrada a instrucciones de dibujo fijas.
 */
class CoverGeneratorFallbackTest {

    @Test
    fun `blank output falls back to fixed directive`() {
        val prompt = CoverGenerator.resolveCoverPrompt("   ", "Mis documentos")
        assertEquals("Minimal modern banner cover for: Mis documentos, no text, 16:9 aspect ratio", prompt)
    }

    @Test
    fun `non blank output is trimmed and kept`() {
        assertEquals("a vivid banner", CoverGenerator.resolveCoverPrompt("  a vivid banner  ", "Mis documentos"))
    }
}
