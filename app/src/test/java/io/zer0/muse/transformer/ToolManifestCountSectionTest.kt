package io.zer0.muse.transformer

import io.zer0.muse.tools.ToolCounts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La copia de conteo en el encabezado del índice de la herramienta de palabras clave: solo informa \"se puede llamar en esta ronda\" y ya no asusta al modelo con el número total de registros. */
class ToolManifestCountSectionTest {

    private val counts = ToolCounts(callableInSchema = 12, sessionVisible = 20, registeredTotal = 33)
    private val section =
        SystemPromptAssembler.toolManifestCountSection(
            counts,
            mapOf("built-in" to 3, "file" to 9),
        )

    @Test
    fun `reports the callable count`() {
        assertTrue("Debe aparecer \"Se puede convocar esta ronda\"", section.contains("Se puede llamar a esta ronda."))
        assertTrue(section.contains("Hay un total de 12 herramientas que se pueden invocar en esta ronda."))
    }

    @Test
    fun `does not claim the registered total as callable`() {
        assertFalse("La palabra \"registrado\" ya no debería aparecer", section.contains("Registrado"))
        assertFalse("El número total de registros (33) se informa como un número llamable", section.contains("33"))
    }

    @Test
    fun `built-in count comes from the same selection as the total`() {
        assertTrue("La distribución de clasificación debe ser homóloga a N", section.contains("built-in 3"))
        assertTrue("La distribución de categorías requiere el nombre de la categoría", section.contains("file 9"))
    }

    @Test
    fun `explains the find_tools tier separately`() {
        assertTrue("Cabe señalar que find_tools tiene un calibre mayor.", section.contains("find_tools Puede enumerar 20"))
        assertFalse("No se puede confundir 20 con \"se puede llamar en esta ronda\" en un solo número", section.contains("Hay un total de 20 herramientas que se pueden invocar en esta ronda."))
    }
}
