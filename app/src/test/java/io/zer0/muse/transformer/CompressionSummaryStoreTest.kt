package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CompressionSummaryStore] con [reusableSummary] Prueba única.
 *
 * Cobertura 2026-09-29 Revisar los resultados:
 *  - C"El resumen se recalcula en cada ronda y no quedará en el inventario"→ La marca de agua en proceso elimina la necesidad de una segunda llamada de LLM para el mismo historial después de una recarga de sesión
 *  - D"La reconstrucción del árbol restaura los mensajes comprimidos"→ Watermark proporciona identificadores cubiertos para el filtrado de reconstrucción de árboles(ver ConversationTreeOrderingTest)
 */
class CompressionSummaryStoreTest {
    private fun msg(content: String): UIMessage = UIMessage(role = MessageRole.USER, content = content)

    @Before
    fun setUp() {
        CompressionSummaryStore.clearAllForTest()
    }

    @After
    fun tearDown() {
        CompressionSummaryStore.clearAllForTest()
    }

    @Test
    fun `Después de la grabación, se puede volver a leer por sesión.`() {
        CompressionSummaryStore.remember("s1", setOf("a", "b"), "ResumenA")
        val entry = CompressionSummaryStore.entry("s1")
        assertNotNull(entry)
        assertEquals("ResumenA", entry!!.summary)
        assertEquals(setOf("a", "b"), entry.coveredIds)
        assertNull(CompressionSummaryStore.entry("s2"))
    }

    @Test
    fun `No inicie sesión cuando la identificación de la sesión o el resumen estén vacíos`() {
        CompressionSummaryStore.remember(null, setOf("a"), "Resumen")
        CompressionSummaryStore.remember("", setOf("a"), "Resumen")
        CompressionSummaryStore.remember("s1", setOf("a"), "   ")
        CompressionSummaryStore.remember("s1", emptySet(), "Resumen")
        assertNull(CompressionSummaryStore.entry("s1"))
    }

    @Test
    fun `clear Borrar solo sesiones específicas`() {
        CompressionSummaryStore.remember("s1", setOf("a"), "Resumen1")
        CompressionSummaryStore.remember("s2", setOf("b"), "Resumen2")
        CompressionSummaryStore.clear("s1")
        assertNull(CompressionSummaryStore.entry("s1"))
        assertNotNull(CompressionSummaryStore.entry("s2"))
    }

    @Test
    fun `Desalojar la sesión de escritura más antigua cuando se supere la capacidad`() {
        repeat(33) { i -> CompressionSummaryStore.remember("s$i", setOf("id$i"), "Resumen$i") }
        assertNull("La sesión más antigua debe eliminarse.", CompressionSummaryStore.entry("s0"))
        assertNotNull("Se deben conservar las sesiones escritas más recientes.", CompressionSummaryStore.entry("s32"))
    }

    @Test
    fun `Reutilice el resumen de caché cuando el rango de compresión esté completamente cubierto`() {
        val m1 = msg("m1")
        val m2 = msg("m2")
        val entry =
            CompressionSummaryStore.Entry(
                summary = "ResumenA",
                coveredIds = setOf(m1.id.toString(), m2.id.toString(), "Otros"),
            )
        assertEquals("ResumenA", reusableSummary(entry, listOf(m1, m2)))
    }

    @Test
    fun `No reutilizar cuando cubra solo una parte del área.(De lo contrario, la historia se perderá.)`() {
        val m1 = msg("m1")
        val m2 = msg("m2")
        val entry = CompressionSummaryStore.Entry(summary = "ResumenA", coveredIds = setOf(m1.id.toString()))
        assertNull(reusableSummary(entry, listOf(m1, m2)))
    }

    @Test
    fun `No se puede reutilizar cuando no hay caché o el intervalo a comprimir está vacío`() {
        assertNull(reusableSummary(null, listOf(msg("m1"))))
        val entry = CompressionSummaryStore.Entry(summary = "ResumenA", coveredIds = setOf("x"))
        assertNull(reusableSummary(entry, emptyList()))
    }

    @Test
    fun `Determinación de reutilización mediante comparación de ID de mensaje en lugar de contenido`() {
        val same = msg("Mismo contenido")
        val entry = CompressionSummaryStore.Entry(summary = "ResumenA", coveredIds = setOf(same.id.toString()))
        assertTrue(reusableSummary(entry, listOf(same)) != null)
        // El contenido es el mismo pero cambiado. id(Por ejemplo, mensajes del mismo texto reimportados)→ No cuenta como cobertura,debe resumirse
        assertNull(reusableSummary(entry, listOf(msg("Mismo contenido"))))
    }
}
