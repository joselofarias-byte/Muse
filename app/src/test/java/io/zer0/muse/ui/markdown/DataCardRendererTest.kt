package io.zer0.muse.ui.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2: DataCardRenderer Prueba de regresión logística pura.
 *
 * Anular la protección de división por cero de datos vacíos para histogramas,y la leyenda de las donas/La invariante de seleccionar colores en el mismo orden de sectores.
 */
class DataCardRendererTest {

    private val accent = Color(0xFF4CAF50)
    private val info = Color(0xFF2196F3)

    @Test
    fun `barWidthFor Los datos vacíos devuelven 0 y no generan una excepción de división por cero`() {
        assertEquals(0f, barWidthFor(canvasWidth = 300f, barCount = 0, gap = 6f), 0.001f)
    }

    @Test
    fun `barWidthFor Los datos regulares se dividen uniformemente por el ancho disponible`() {
        // (330 - 6 * 2) / 3 = 106
        assertEquals(106f, barWidthFor(canvasWidth = 330f, barCount = 3, gap = 6f), 0.001f)
    }

    @Test
    fun `barWidthFor Cuando el lienzo es demasiado estrecho, se reduce a 0 en lugar de a un número negativo`() {
        assertEquals(0f, barWidthFor(canvasWidth = 10f, barCount = 10, gap = 6f), 0.001f)
    }

    @Test
    fun `barWidthFor Una sola columna llena el lienzo`() {
        assertEquals(200f, barWidthFor(canvasWidth = 200f, barCount = 1, gap = 6f), 0.001f)
    }

    @Test
    fun `donut Los colores de la leyenda están en el mismo orden que los colores del sector`() {
        val palette = donutSectorPalette(accent, info)
        for (index in 0..7) {
            assertEquals(
                "index=$index El color de la leyenda debe ser igual al mismo color del sector de secuencia.",
                sectorColor(palette, index),
                legendSwatchColor(cardType = "donut", index = index, accent = accent, donutPalette = palette),
            )
        }
    }

    @Test
    fun `donut El primer elemento de la paleta es el color de acento y los colores se reciclan`() {
        val palette = donutSectorPalette(accent, info)
        assertEquals(5, palette.size)
        assertEquals(accent, sectorColor(palette, 0))
        assertEquals(accent, sectorColor(palette, palette.size))
        assertEquals(info, sectorColor(palette, palette.size - 1))
    }

    @Test
    fun `Las leyendas de los gráficos que no son de anillos usan colores de acento de manera uniforme`() {
        val palette = donutSectorPalette(accent, info)
        assertEquals(accent, legendSwatchColor(cardType = "bar", index = 0, accent = accent, donutPalette = palette))
        assertEquals(accent, legendSwatchColor(cardType = "line", index = 3, accent = accent, donutPalette = palette))
    }
}
