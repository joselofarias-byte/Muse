package io.zer0.muse.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bloquee la coherencia de las instrucciones para traducción única y por lotes.
 *
 * Causa: Al conectar la traducción por lotes al glosario, inicialmente copié la forma antigua de escribir por lotes (estilo incorporado +
 * Superposición de estilo personalizado), mientras que una sola traducción es "sobrescritura de visitas personalizadas incorporada". La misma opción de estilo está disponible en
 * De ambos caminos surgen distintos estilos de traducción —— Este tipo de desacuerdo no provocará que se informe un error de ninguna de las partes y solo puede solucionarse mediante afirmaciones.
 */
class TranslatePromptStylePrecedenceTest {

    private val custom = TranslateViewModel.CustomStyle(name = "estilo antiguo", prompt = "Traducir al chino clásico")

    private fun countWhere(prompt: String, needle: String): Int = prompt.lineSequence().count { it.contains(needle) }

    @Test
    fun `custom style overrides builtin style in single prompt`() {
        val prompt = TranslateViewModel.buildTranslationPrompt(
            text = "hello",
            targetLanguage = "chino",
            style = "estilo antiguo",
            customStyles = listOf(custom),
        )
        assertTrue(prompt.contains("Traducir al chino clásico"))
        // Las instrucciones integradas no deben aparecer al mismo tiempo.
        assertFalse(prompt.contains("Utilice expresiones comunes, precisas y naturales."))
    }

    @Test
    fun `batch prompt obeys custom style the same way as single`() {
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("hello"),
            targetLanguage = "chino",
            sourceLanguage = "ingles",
            style = "estilo antiguo",
            customStyles = listOf(custom),
        )
        assertTrue("El lote debe usar un estilo personalizado", batch.contains("Traducir al chino clásico"))
        assertFalse(
            "Los estilos integrados y los estilos personalizados no se deben superponer en lotes (se debe mantener la misma prioridad que los elementos individuales)",
            batch.contains("Utilice expresiones comunes, precisas y naturales."),
        )
    }

    @Test
    fun `unknown style falls back to builtin in both prompts`() {
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "hello",
            targetLanguage = "chino",
            style = "academico",
        )
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("hello"),
            targetLanguage = "chino",
            sourceLanguage = "ingles",
            style = "academico",
        )
        assertTrue(single.contains("Utiliza expresiones académicas, formales y rigurosas."))
        assertTrue(batch.contains("Utiliza expresiones académicas, formales y rigurosas."))
    }

    @Test
    fun `glossary line appears exactly once in each prompt`() {
        val glossary = mapOf("API" to "API")
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "use API",
            targetLanguage = "chino",
            glossary = glossary,
        )
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("use API"),
            targetLanguage = "chino",
            sourceLanguage = "ingles",
            style = "Universales",
            glossary = glossary,
        )
        // Después de extraer el asistente compartido, las filas del glosario solo pueden provenir de un lugar; Si hay duplicados en el futuro, fallarán aquí primero.
        assertEquals(1, countWhere(single, "Consulte el siguiente glosario para la traducción."))
        assertEquals(1, countWhere(batch, "Consulte el siguiente glosario para la traducción."))
        assertEquals(1, countWhere(single, "API→API"))
        assertEquals(1, countWhere(batch, "API→API"))
    }
}
