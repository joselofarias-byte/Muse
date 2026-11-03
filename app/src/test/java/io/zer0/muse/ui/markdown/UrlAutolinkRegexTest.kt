package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.97 Etapa 2: URL_AUTOLINK_REGEX Pruebas de casos de uso de límites.
 *
 * Valide URL de texto sin formato e identifique automáticamente coincidencias regulares/comportamiento excluyente:
 *  - Coincidencia completa de URL http/https estándar
 *  - URL Contiene cadena de consulta/punto de anclaje/ruta de coincidencia completa
 *  - URL seguido de blanco/comillas/paréntesis/tiempo de puntuación chino,La puntuación no está incluida en el partido.
 *  - Texto sin formato, ninguna URL no coincide
 *  - Varias URL coinciden de forma independiente
 *  - El protocolo http/https no en minúsculas no coincide(comportamiento esperado,Las URL en mayúsculas están representadas por markdown [text](url) Proceso)
 *  - ftp/mailto Espere a que los protocolos que no sean http no coincidan
 *
 * Descripción: Puntuación china al final.(. ,,;:!?』』)Excluido por limpieza secundaria en el sitio de la llamada.
 */
class UrlAutolinkRegexTest {

    /** Extraiga todos los resultados de coincidencias de URL en el texto como una lista de cadenas(Fácil de afirmar). */
    private fun urls(input: String): List<String> = URL_AUTOLINK_REGEX.findAll(input).map { it.value }.toList()

    // ── partido estándar ──

    @Test
    fun `standard https url matches fully`() {
        assertEquals(listOf("https://example.com"), urls("https://example.com"))
    }

    @Test
    fun `standard http url matches fully`() {
        assertEquals(listOf("http://example.com"), urls("http://example.com"))
    }

    @Test
    fun `url with path matches fully`() {
        assertEquals(
            listOf("https://example.com/path/to/resource"),
            urls("https://example.com/path/to/resource"),
        )
    }

    @Test
    fun `url with query string matches fully`() {
        assertEquals(
            listOf("https://example.com/search?q=kotlin&p=1"),
            urls("https://example.com/search?q=kotlin&p=1"),
        )
    }

    @Test
    fun `url with anchor matches fully`() {
        assertEquals(
            listOf("https://example.com/guide#section-2"),
            urls("https://example.com/guide#section-2"),
        )
    }

    @Test
    fun `url with port matches fully`() {
        assertEquals(
            listOf("http://localhost:8080/api/v1"),
            urls("http://localhost:8080/api/v1"),
        )
    }

    // ── Exclusión del separador final ──

    @Test
    fun `url followed by space excludes space`() {
        assertEquals(
            listOf("https://example.com"),
            urls("see https://example.com for more"),
        )
    }

    @Test
    fun `url followed by closing paren excludes paren`() {
        assertEquals(
            listOf("https://example.com"),
            urls("(see https://example.com)"),
        )
    }

    @Test
    fun `url followed by closing bracket excludes bracket`() {
        assertEquals(
            listOf("https://example.com"),
            urls("[link: https://example.com]"),
        )
    }

    @Test
    fun `url followed by double quote excludes quote`() {
        assertEquals(
            listOf("https://example.com"),
            urls("href=\"https://example.com\""),
        )
    }

    @Test
    fun `url followed by chinese period excludes period`() {
        // Los períodos chinos deben excluirse.(Limpiado dos veces por el sitio de llamadas)
        assertEquals(
            listOf("https://example.com"),
            urls("Ver https://example.com。。"),
        )
    }

    @Test
    fun `url followed by chinese comma excludes comma`() {
        assertEquals(
            listOf("https://example.com"),
            urls("visita https://example.com,se puede ver"),
        )
    }

    @Test
    fun `url followed by chinese semicolon excludes it`() {
        assertEquals(
            listOf("https://example.com"),
            urls("URL https://example.com;Recuerda recolectar"),
        )
    }

    @Test
    fun `url followed by chinese fullwidth exclamation excludes it`() {
        assertEquals(
            listOf("https://example.com"),
            urls("mira https://example.com!impresionante"),
        )
    }

    @Test
    fun `url followed by chinese right corner quote excludes it`() {
        // "" Se deben excluir las comillas de la esquina derecha.
        assertEquals(
            listOf("https://example.com"),
            urls("\"Sitio web oficial: https://example.com""),
        )
    }

    @Test
    fun `url followed by ascii period includes period`() {
        // ASCII período `.` Válido dentro de la URL(example.com),La regularidad no excluye,final de la frase `.` será incluido en el partido.
        // La parte que llama es responsable de la limpieza secundaria de la puntuación al final de las frases.,
        // Aquí solo se realiza el reconocimiento de texto plano,Mantenga las clases de personajes simples.
        assertEquals(
            listOf("https://example.com."),
            urls("Visit https://example.com."),
        )
    }

    @Test
    fun `url followed by ascii comma excludes comma`() {
        assertEquals(
            listOf("https://example.com"),
            urls("Visit https://example.com, and explore."),
        )
    }

    // ── Varias URL frente a ninguna URL ──

    @Test
    fun `multiple urls match independently`() {
        assertEquals(
            listOf("https://a.com", "https://b.com"),
            urls("first https://a.com then https://b.com end"),
        )
    }

    @Test
    fun `plain text without url produces no matches`() {
        assertTrue(urls("hello world, Este es un texto normal.").isEmpty())
    }

    @Test
    fun `text mentioning protocol without scheme does not match`() {
        // "example.com" Sin discrepancia de prefijo http/https(Evite la identificación errónea)
        assertTrue(urls("visit example.com sometime").isEmpty())
    }

    // ── comportamiento inesperado(borrar no coincide)──

    @Test
    fun `uppercase https does not match`() {
        // Normalmente distingue entre mayúsculas y minúsculas,HTTPS en mayúsculas no coincide(comportamiento esperado)
        assertTrue(urls("HTTPS://EXAMPLE.COM").isEmpty())
    }

    @Test
    fun `ftp protocol does not match`() {
        assertTrue(urls("ftp://files.example.com").isEmpty())
    }

    @Test
    fun `mailto protocol does not match`() {
        assertTrue(urls("mailto:user@example.com").isEmpty())
    }

    @Test
    fun `url embedded in chinese text matches only the url`() {
        // URL incrustada en una oración china: hay espacios antes y después de la URL,La coincidencia regular comienza desde https,Termina con el siguiente espacio.
        // El resto de la oración china no afecta el reconocimiento de URL.
        val matches = urls("Puede visitar https://example.com/path para obtener más información.")
        assertEquals(1, matches.size)
        assertEquals("https://example.com/path", matches[0])
    }
}
