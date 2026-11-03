package io.zer0.muse.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La herramienta vuelve a intentar la prueba de la descripción del presupuesto.
 *
 * Ítem 15 de autoevaluación del asistente: "No se visualiza el presupuesto de reintento en caso de falla. Soy muy conservador después de una falla.
 * De hecho, es seguro que algunos de ellos vuelvan a intentarlo”.
 *
 * Aquí se señalan tres situaciones: todavía hay una posibilidad / agotado / no deberías volver a intentarlo en primer lugar.
 */
class ToolRetryBudgetTest {

    @Test
    fun `first retryable failure says how many chances remain`() {
        val line = ToolRetryBudget.describe("web_fetch", attempt = 1, retryable = true)

        assertTrue("¿Cuántas veces debería decírtelo?", line.contains("1er fracaso"))
        assertTrue("Dar oportunidades restantes", line.contains("Queda 1 oportunidad de reintento"))
    }

    @Test
    fun `exhausted budget tells the model to stop calling the same tool`() {
        val line = ToolRetryBudget.describe("web_fetch", attempt = 2, retryable = true)

        assertTrue(line.contains("Oportunidades para volver a intentarlo agotadas"))
        assertTrue("Deja claro que parar", line.contains("No vuelvas a llamar a la misma herramienta"))
    }

    @Test
    fun `non-retryable failure says so regardless of remaining budget`() {
        val line = ToolRetryBudget.describe("screen_tap", attempt = 1, retryable = false)

        assertTrue("No se pueden dar las oportunidades restantes cuando no es posible volver a intentarlo.", !line.contains("izquierda"))
        assertTrue(line.contains("Este error no se puede volver a intentar."))
    }

    @Test
    fun `tool name appears so the model knows which budget this is`() {
        val line = ToolRetryBudget.describe("generate_image", attempt = 1, retryable = true)

        assertTrue(line.contains("generate_image"))
    }

    @Test
    fun `attempt beyond the budget is clamped instead of going negative`() {
        val line = ToolRetryBudget.describe("x", attempt = 9, retryable = true)

        assertTrue("No se permiten números negativos.", !line.contains("-"))
        assertTrue(line.contains("agotado"))
    }

    @Test
    fun `budget matches the prompt rule of one correction`() {
        // El mensaje dice "Si falla, simplemente presione el mensaje de error para corregirlo una vez".= Dar 1 vez más después del primer fallo.
        assertTrue("El límite superior debe ser coherente con la palabra calibre.", ToolRetryBudget.MAX_RETRIES == 1)
    }
}
