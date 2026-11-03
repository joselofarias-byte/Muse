package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una prueba para la recomendación de clasificación de emoji.
 *
 * Artículo 16 del autoexamen del asistente: "El nombre de la categoría del paquete de emoticonos debe coincidir estrictamente con la lista. Si adivina mal, no será válido. Y no hay
 * "¿Qué es adecuado para este contexto?" Recomendaciones.
 *
 * Aquí se clavan dos cosas:
 *  1. Descripción general de la clasificación con comparación contextual (mapeo directo del sentimiento conversacional a la clasificación)
 *  2. Cuando el nombre de la categoría se adivina incorrectamente, se devuelve una lista de candidatos en lugar de "vacía".
 *
 * Prueba de función pura: no se construye ningún repositorio ni se toca ningún disco.
 */
class StickerCategoryGuideTest {

    private fun guide(cats: List<String>) = SkillMediaToolsImpl.buildCategoryGuide(cats)

    @Test
    fun `known moods are mapped to matching categories`() {
        val out = guide(listOf("feliz", "triste", "escultura de arena"))

        assertTrue(out.contains("feliz/celebrar"))
        assertTrue(out.contains("triste/abajo"))
        assertTrue(out.contains("divertido/escultura de arena"))
    }

    @Test
    fun `unmatched categories are listed as judge-by-name instead of being dropped`() {
        val out = guide(listOf("feliz", "ilustración de gato", "memes de trabajo"))

        assertTrue("Las categorías que sean irreconocibles deben enumerarse y no pueden descartarse silenciosamente.", out.contains("El resto de categorías se pueden juzgar por su nombre."))
        assertTrue(out.contains("ilustración de gato"))
        assertTrue(out.contains("memes de trabajo"))
    }

    @Test
    fun `no matching category yields empty guide rather than a misleading one`() {
        assertEquals("No hagas sugerencias con calzador cuando no conoces a nadie.", "", guide(listOf("Categoría A", "Categoría B")))
    }

    @Test
    fun `english category names are matched case insensitively`() {
        val out = guide(listOf("Happy", "SAD"))

        assertTrue(out.contains("feliz/celebrar"))
        assertTrue(out.contains("triste/abajo"))
    }

    @Test
    fun `a category matching multiple moods appears under each`() {
        val out = guide(listOf("feliz sanación"))

        assertTrue("Contiene \"feliz\" y \"curación\" al mismo tiempo, ambos deberían aparecer", out.contains("feliz/celebrar"))
        assertTrue(out.contains("comodidad/curar"))
    }

    @Test
    fun `empty category list yields empty guide`() {
        assertEquals("", guide(emptyList()))
    }
}
