package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Almacenamiento en caché de texto en minúsculas de puntuaciones de experiencia.
 *
 * En cada ronda del segmento del banco de experiencias, todas las experiencias deben calificarse de acuerdo con el problema actual. El método es `title + content` Después de que todo el párrafo esté en minúsculas, realice un escaneo de subcadenas.
 * Las entradas son acumuladas por los usuarios y pueden ser muy largas. —— Reasignar una copia completa en minúscula de cada experiencia en cada ronda es pura duplicación de esfuerzos.
 *
 * Aquí hay dos cosas bloqueadas:
 *  1. mismo `id + updatedAt` Pulsa el mismo texto en minúsculas y la entrada se edita (updatedAt debe recalcularse después del cambio);
 *  2. La capacidad tiene un límite superior y no se convertirá en una caché ilimitada.
 */
class ExperienceTextCacheTest {

    @Test
    fun `same_version_hits_the_same_lowered_text`() {
        val cache = ExperienceTextCache()
        val key = cache.key("exp-1", 1000L)

        assertNull("Debes perderte la primera vez", cache.get(key))
        cache.put(key, "Texto del título")
        assertEquals("Texto del título", cache.get(key))
        assertEquals(1, cache.hits)
        assertEquals(1, cache.misses)
    }

    @Test
    fun `editing_an_entry_invalidates_by_updated_at`() {
        val cache = ExperienceTextCache()
        val before = cache.key("exp-1", 1000L)
        cache.put(before, "contenido antiguo")

        val after = cache.key("exp-1", 2000L)
        assertNull("updatedAt Los cambios deben tratarse como nuevas entradas.", cache.get(after))
        assertEquals("La copia antigua aún es legible (no afecta a otras llamadas)", "contenido antiguo", cache.get(before))
    }

    @Test
    fun `cache_is_bounded`() {
        val cache = ExperienceTextCache(maxEntries = 3)
        repeat(6) { index -> cache.put(cache.key("exp-$index", 1L), "contenido $index") }
        assertEquals(3, cache.size())
        assertNull("Se deben eliminar las entradas que no se hayan utilizado durante más tiempo.", cache.get(cache.key("exp-0", 1L)))
        assertEquals("contenido 5", cache.get(cache.key("exp-5", 1L)))
    }

    // ── Cableado: la ruta de puntuación realmente debe pasar por el caché ────────────────────────────

    private fun assemblerSource(): String =
        File("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt").readText()

    @Test
    fun `experience_relevance_uses_the_cache`() {
        val source = assemblerSource()
        val start = source.indexOf("private fun experienceRelevance(")
        require(start >= 0) { "no encontrado experienceRelevance" }
        val end = source.indexOf("private fun", start + 10).let { if (it < 0) source.length else it }
        val body = source.substring(start, end)

        assertTrue(
            "La puntuación debe almacenarse en caché para obtener texto en minúsculas",
            body.contains("experienceTextCache.get("),
        )
        assertTrue(
            "Debe volver a escribir en la caché si falla",
            body.contains("experienceTextCache.put("),
        )
        assertTrue(
            "La función de puntuación ya no debería utilizar minúsculas directamente (entonces el almacenamiento en caché es inútil)",
            !body.contains("(exp.title + \" \" + exp.content).lowercase()\n"),
        )
    }

    @Test
    fun `experience_tokenizer_regex_is_a_constant`() {
        val source = assemblerSource()
        val tokenizeStart = source.indexOf("private fun tokenizeExperience(")
        require(tokenizeStart >= 0) { "no encontrado tokenizeExperience" }
        val tokenizeEnd = source.indexOf("}", tokenizeStart).let { if (it < 0) source.length else it }
        val tokenizeBody = source.substring(tokenizeStart, tokenizeEnd)

        assertTrue(
            "La segmentación de palabras debe reutilizar la regularización a nivel de instancia y no debe compilarse en el sitio en cada ronda.",
            tokenizeBody.contains("experienceTokenRegex"),
        )
        assertTrue(
            "La escena no debe aparecer en la función de segmentación de palabras. Regex(...) estructura",
            !tokenizeBody.contains("Regex("),
        )
    }
}
