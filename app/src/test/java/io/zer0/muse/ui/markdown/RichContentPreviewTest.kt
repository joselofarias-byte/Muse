package io.zer0.muse.ui.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2: RichContentCard Vista previa en pantalla completa de la prueba de cobertura del idioma.
 *
 * Bloquear dos cosas:
 *  - html/svg Conservar las capacidades de vista previa de navegación originales;
 *  - chart/mermaid También debe haber una ruta de vista previa en pantalla completa.(Depender del script de activos locales,Ir a pantalla completa dentro de la tarjeta WebView).
 */
class RichContentPreviewTest {

    @Test
    fun `html Admite vista previa en pantalla completa con svg`() {
        assertTrue(richContentSupportsPreview("html"))
        assertTrue(richContentSupportsPreview("svg"))
    }

    @Test
    fun `chart Admite vista previa en pantalla completa con sirena`() {
        assertTrue(richContentSupportsPreview("chart"))
        assertTrue(richContentSupportsPreview("mermaid"))
    }

    @Test
    fun `Tolerancia entre mayúsculas y minúsculas y espacios en blanco`() {
        assertTrue(richContentSupportsPreview(" Mermaid "))
    }

    @Test
    fun `El lenguaje de código normal no admite vista previa`() {
        assertFalse(richContentSupportsPreview("kotlin"))
        assertFalse(richContentSupportsPreview(""))
    }

    @Test
    fun `chart Con sirena, acceda a la vista previa local en la tarjeta en lugar de la vista previa de navegación`() {
        assertTrue(RICH_LOCAL_PREVIEW_LANGUAGES.contains("chart"))
        assertTrue(RICH_LOCAL_PREVIEW_LANGUAGES.contains("mermaid"))
        assertFalse(RICH_LOCAL_PREVIEW_LANGUAGES.contains("html"))
        assertFalse(RICH_LOCAL_PREVIEW_LANGUAGES.contains("svg"))
    }
}
