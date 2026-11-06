package io.zer0.muse.ui.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.7: Las palabras solicitadas para la traducción por lotes deben cumplir con la lista de terminología y el estilo personalizado coherente con el artículo individual.
 *
 * Volver al fondo:buildTranslationPrompt(Un solo elemento) Pase siempre glosario/estilos personalizados,
 * buildBatchPrompt(Lote) Ninguno se transmite —— El glosario establecido por el usuario sólo tiene efecto en un único artículo.
 * Ignore todo el párrafo por lotes. esto pertenece a"Sólo la mitad de los dos caminos para la misma función están conectados"Productos semiacabados típicos.
 */
class TranslatePromptGlossaryTest {

    private val glossary = mapOf("API" to "API", "GPU" to "procesador de gráficos")

    @Test
    fun `batch prompt includes glossary like single prompt does`() {
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("Use the API", "The GPU is fast"),
            targetLanguage = "chino",
            sourceLanguage = "ingles",
            style = "Universales",
            glossary = glossary,
        )

        assertTrue("Las palabras del mensaje por lotes deben tener un glosario", batch.contains("Consulte el siguiente glosario para la traducción."))
        assertTrue("Debe contener mapeo API", batch.contains("API→API"))
        assertTrue("Debe contener mapeo de GPU", batch.contains("GPU→procesador de gráficos"))

        // Cada camino debe recorrerse por igual y los dos caminos no pueden separarse.
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "Use the API",
            targetLanguage = "chino",
            sourceLanguage = "ingles",
            glossary = glossary,
        )
        assertTrue("Una sola palabra clave debe ir acompañada de un glosario.", single.contains("Consulte el siguiente glosario para la traducción."))
    }

    @Test
    fun `empty glossary adds no instruction to either prompt`() {
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("hello"),
            targetLanguage = "chino",
            sourceLanguage = "ingles",
            style = "Universales",
        )
        val single = TranslateViewModel.buildTranslationPrompt(
            text = "hello",
            targetLanguage = "chino",
            sourceLanguage = "ingles",
        )
        assertFalse(batch.contains("Consulte el siguiente glosario para la traducción."))
        assertFalse(single.contains("Consulte el siguiente glosario para la traducción."))
    }

    @Test
    fun `batch prompt honours custom style`() {
        val custom = TranslateViewModel.CustomStyle(
            name = "Documentación técnica",
            prompt = "La terminología mantiene una traducción común en la industria.,Las abreviaturas no están traducidas.",
        )
        val batch = TranslateViewModel.buildBatchPrompt(
            texts = listOf("the API"),
            targetLanguage = "chino",
            sourceLanguage = "ingles",
            style = "Documentación técnica",
            customStyles = listOf(custom),
        )
        assertTrue("Las directivas de estilo personalizadas deben usarse en lotes", batch.contains("La terminología mantiene una traducción común en la industria.,Las abreviaturas no están traducidas."))
    }
}
