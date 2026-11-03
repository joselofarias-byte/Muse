package io.zer0.muse.ui.common.animation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enviar cálculos geométricos para vuelo.
 *
 * Esta lógica solo tiene un resultado visual en la máquina real y no hay forma de saber si es correcta o incorrecta, por lo que fijé la parte verificable en la prueba:
 * El punto de aterrizaje debe estar dentro de la lista, el ancho está sujeto al límite superior y la entrada no válida se abandonará directamente (es mejor no volar que volar a la posición incorrecta).
 */
class SendFlightTest {

    private val list = Rect(left = 0f, top = 200f, right = 1080f, bottom = 2200f)
    private val input = Rect(left = 40f, top = 2100f, right = 1040f, bottom = 2200f)

    @Test
    fun `blank text produces no flight`() {
        assertNull(computeSendFlight("   ", input, list))
    }

    @Test
    fun `unmeasured rects produce no flight`() {
        assertNull(computeSendFlight("hi", Rect.Zero, list))
        assertNull(computeSendFlight("hi", input, Rect.Zero))
    }

    @Test
    fun `target sits inside the list`() {
        val flight = computeSendFlight("hello", input, list)
        assertNotNull(flight)
        val to = requireNotNull(flight).to
        assertTrue("La parte superior del punto de caída no debe ser más alta que la parte superior de la lista.", to.top >= list.top)
        assertTrue("La parte inferior del punto de caída no debe ser inferior a la parte inferior de la lista.", to.bottom <= list.bottom)
        assertTrue("El lado derecho del punto de caída no debe exceder la lista.", to.right <= list.right)
        assertTrue("El lado izquierdo del punto de aterrizaje no debe ser negativo.", to.left >= list.left)
    }

    @Test
    fun `target width respects the bubble ratio`() {
        val flight = requireNotNull(computeSendFlight("hello", input, list))
        val maxWidth = list.width * 0.72f
        assertTrue("El ancho del punto de caída no debe exceder el límite superior de la burbuja.", flight.to.width <= maxWidth + 0.5f)
        assertTrue("El ancho del punto de caída debe ser mayor que cero", flight.to.width > 0f)
    }

    @Test
    fun `flight keeps the text and both endpoints`() {
        val flight = requireNotNull(computeSendFlight("hello", input, list))
        assertEquals("hello", flight.text)
        assertEquals(input, flight.from)
    }
}
