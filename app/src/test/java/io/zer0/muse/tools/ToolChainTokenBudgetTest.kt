package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cadena de herramientas"reconstrucción local"Prueba de juicio para el número de elementos retenidos.
 *
 * Antecedentes: la cadena de herramientas no puede descartar silenciosamente los resultados históricos de las API de usuario debido a la aplicación de un límite superior personalizado.
 * Cuando se conoce la ventana del modelo, el presupuesto se ajusta simbólicamente; cuando se desconoce la ventana, se conserva la cadena de herramientas completa.
 */
class ToolChainTokenBudgetTest {

    @Test
    fun `unknown model budget does not discard earlier tool messages`() {
        assertEquals(100, toolChainTailToKeep(chainSize = 100, chainTokens = 0, budgetTokens = 0))
    }

    @Test
    fun `empty chain keeps nothing to keep`() {
        assertEquals(0, toolChainTailToKeep(chainSize = 0, chainTokens = 0, budgetTokens = 100_000))
        assertEquals(0, toolChainTailToKeep(chainSize = -3, chainTokens = 0, budgetTokens = 100_000))
    }

    @Test
    fun `without a budget the complete chain is preserved`() {
        assertEquals(5, toolChainTailToKeep(chainSize = 5, chainTokens = 0, budgetTokens = 0))
        assertEquals(
            100,
            toolChainTailToKeep(chainSize = 100, chainTokens = 0, budgetTokens = -1),
        )
    }

    @Test
    fun `chain under half the budget keeps the complete tail`() {
        // El presupuesto es de 100K y la cadena de herramientas solo usa 40K (< 50K)→ Sin ajuste debido al token
        assertEquals(
            100,
            toolChainTailToKeep(chainSize = 100, chainTokens = 40_000, budgetTokens = 100_000),
        )
    }

    @Test
    fun `oversized chain is tightened by the token budget`() {
        // presupuesto 100K → presupuesto de cola 50K → Cada entrada se calcula en 50 tokens y se puede reservar un máximo de 1000 entradas;
        // La cadena en sí sólo tiene 400, por lo que quedan todos (el número máximo de 200? Ver Aseveración: lo que sea menor)
        val kept = toolChainTailToKeep(chainSize = 400, chainTokens = 90_000, budgetTokens = 100_000)
        assertTrue("No debe exceder la longitud de la cadena existente.", kept <= 400)
        assertTrue("Se debe conservar al menos uno.", kept >= 1)
    }

    @Test
    fun `very large single result still keeps one message`() {
        // Un solo resultado consumirá toda la ventana: prefiero conservar solo 1 resultado que devolverlo. 0 ——
        // Perderlos todos hará que el modelo sea completamente invisible."¿Qué pasó en esa llamada de hace un momento?"
        val kept = toolChainTailToKeep(chainSize = 3, chainTokens = 500_000, budgetTokens = 100_000)
        assertEquals(1, kept)
    }

    @Test
    fun `tighter budget keeps fewer messages than the complete chain`() {
        // 1000 cadenas, en total 60K token → Promedio de 60 fichas/artículo.
        // Mantenerlo intacto cuando el presupuesto sea suficiente;
        // Cuando el presupuesto es muy ajustado (el presupuesto final es de 600 fichas), sólo pueden quedar 10 artículos. —— Debe estar debajo de la cadena completa,
        // De lo contrario, significa que el juicio simbólico no es efectivo.
        val roomy = toolChainTailToKeep(chainSize = 1_000, chainTokens = 60_000, budgetTokens = 200_000)
        val tight = toolChainTailToKeep(chainSize = 1_000, chainTokens = 60_000, budgetTokens = 1_200)
        assertEquals("Mantener la cadena completa cuando el presupuesto sea suficiente", 1_000, roomy)
        assertTrue("Una vez ajustado el presupuesto, debería ser menor que la cadena completa. De hecho, $tight", tight < roomy)
        assertEquals("Presupuesto de cola 600/cada uno 60 → 10 Artículo", 10, tight)
    }

    @Test
    fun `result never exceeds the available chain`() {
        listOf(1, 5, 30, 31, 100).forEach { size ->
            listOf(0, 1_000, 100_000).forEach { budget ->
                val kept = toolChainTailToKeep(size, chainTokens = 10_000, budgetTokens = budget)
                assertTrue("El número de artículos retenidos no excederá la longitud de la cadena (size=$size budget=$budget)", kept <= size)
            }
        }
    }
}
