package io.zer0.muse.data.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.x: Prueba única de coincidencia tolerante a errores de nombres de categorías de paquetes de emoticones — preciso/normalización/editar distancia/solo contiene/Se abandonó la ambigüedad.
 */
class StickerCategoryMatchTest {
    private val cats = listOf("feliz", "Sin palabras", "comodidad", "gato gato", "infeliz")

    @Test
    fun `exact match`() {
        assertEquals("feliz", matchStickerCategory(cats, "feliz"))
    }

    @Test
    fun `normalized match ignores case and whitespace`() {
        assertEquals("feliz", matchStickerCategory(cats, " feliz "))
        assertEquals("Happy", matchStickerCategory(listOf("Happy", "Sad"), "happy"))
        assertEquals("gato gato", matchStickerCategory(cats, "gato gato"))
    }

    @Test
    fun `edit distance one with same length`() {
        // Sustitución de un carácter:feliz → felis
        assertEquals("feliz", matchStickerCategory(cats, "felis"))
    }

    @Test
    fun `containing shorter category does not match`() {
        // "feliz" con "infeliz" Solo una palabra de diferencia pero significados opuestos.:El reverso incluye discapacitados,rechazar coincidencia
        assertNull(matchStickerCategory(listOf("infeliz"), "feliz"))
    }

    @Test
    fun `suffix-typed name matches shorter category`() {
        // El modelo debería alargar el nombre de la categoría.(dirección:name contiene candidate) → partido
        assertEquals("feliz", matchStickerCategory(listOf("feliz"), "tan feliz"))
    }

    @Test
    fun `ambiguous containment returns null`() {
        // Contiene múltiples categorías al mismo tiempo. → Ambigüedad,rendirse
        assertNull(matchStickerCategory(listOf("feliz", "pistachos"), "fruta de pistacho"))
    }

    @Test
    fun `no match returns null`() {
        assertNull(matchStickerCategory(cats, "enojado"))
        assertNull(matchStickerCategory(emptyList(), "feliz"))
        assertNull(matchStickerCategory(cats, "   "))
    }

    @Test
    fun `levenshtein sanity`() {
        assertEquals(0, levenshteinDistance("feliz", "feliz"))
        assertEquals(1, levenshteinDistance("feliz", "felis"))
        assertEquals(1, levenshteinDistance("happy", "happi"))
        assertEquals(3, levenshteinDistance("kitten", "sitting"))
        assertEquals(2, levenshteinDistance("", "ab"))
    }
}
