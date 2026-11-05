package io.zer0.muse.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeGlassStopsTest {
    private fun cfg(strength: Float = 0.5f) =
        LiquidGlassConfig(style = GlassStyle.FROST, strength = strength, mode = LiquidGlassConfig.MODE_FROST)

    private val darkBase = Color(0xFF0F0D13)
    private val lightBase = Color(0xFFF7F2FA)

    @Test
    fun `dark surfaces remain dark at every stop`() {
        for (strength in listOf(0.2f, 0.5f, 1f)) {
            for (base in listOf(darkBase, Color(0xFF141218))) {
                val stops = fakeGlassStops(base, cfg(strength))
                assertEquals(3, stops.size)
                stops.forEach { (_, color) -> assertTrue(color.luminance() < 0.25f) }
            }
        }
    }

    @Test
    fun `dark glass is deeper at the bottom`() {
        val stops = fakeGlassStops(darkBase, cfg())
        assertTrue(stops.first().second.luminance() > stops.last().second.luminance())
    }

    @Test
    fun `light glass preserves the original white lift`() {
        val stops = fakeGlassStops(lightBase, cfg())
        assertEquals(3, stops.size)
        stops.forEach { (_, color) -> assertTrue(color.luminance() >= lightBase.luminance() - 0.001f) }
    }

    @Test
    fun `stronger dark glass has a deeper bottom`() {
        val weak = fakeGlassStops(darkBase, cfg(0.1f)).last().second.luminance()
        val strong = fakeGlassStops(darkBase, cfg(1f)).last().second.luminance()
        assertTrue(strong <= weak)
    }

    @Test
    fun `top highlight stays subtle`() {
        val top = fakeGlassStops(darkBase, cfg(1f)).first().second.luminance()
        assertTrue(top - darkBase.luminance() < 0.08f)
    }
}
