package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Phase 2: Prueba de lógica pura de vista previa de enlace(Extracción de dominio + nuevos valores predeterminados del campo de estado).
 */
class LinkPreviewCardTest {

    @Test
    fun `linkDomain Quitar el prefijo www`() {
        assertEquals("example.com", linkDomain("https://www.example.com/a/b?c=1"))
    }

    @Test
    fun `linkDomain Mantenga el subdominio y elimine el puerto.`() {
        assertEquals("sub.example.com", linkDomain("https://sub.example.com:8443/path"))
    }

    @Test
    fun `linkDomain Si el análisis falla, regrese al original URL`() {
        assertEquals("not a url", linkDomain("not a url"))
    }

    @Test
    fun `Los datos predeterminados no se cargan ni fallan`() {
        val data = LinkPreviewData(url = "https://example.com")
        assertFalse(data.isLoading)
        assertFalse(data.isFailed)
    }
}
