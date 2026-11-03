package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truncamiento consciente de la estructura de las vistas previas de tarjetas de colección (auditoría §11-9).
 *
 * La implementación original fue `body.take(280)`, cortar la valla de código abierta hará que todo el contenido posterior
 * se representan en bloques de código; cortar `**` / `[N]` La mitad de la marca se escapará por el medio.
 */
class MarkdownPreviewTruncationTest {

    private fun fences(text: String): Int = Regex("```").findAll(text).count()

    @Test
    fun `short text is returned untouched without ellipsis`() {
        val body = "una frase muy corta"

        assertEquals(body, MarkdownPreviewTruncation.preview(body))
    }

    @Test
    fun `long text ends with ellipsis and stays within limit plus ellipsis`() {
        val body = (1..80).joinToString(" ") { "No.${it}Todo el contenido está aquí." }

        val preview = MarkdownPreviewTruncation.preview(body, limit = 120)

        assertTrue("El texto extra largo debe tener puntos suspensivos.", preview.endsWith(MarkdownPreviewTruncation.ELLIPSIS))
        assertTrue(
            "La duración de la vista previa no puede exceder significativamente el límite superior: ${preview.length}",
            preview.length <= 120 + MarkdownPreviewTruncation.ELLIPSIS.length,
        )
    }

    @Test
    fun `unclosed code fence is dropped instead of leaking into following text`() {
        // La valla se abre dentro de los 280 caracteres y se cierra más allá de los 280 caracteres. —— El antiguo truncamiento a nivel de personaje dejaría a un jugador solitario ```
        val filler = "a".repeat(200)
        val body = "$filler\n\n```kotlin\n" + "val x = 1\n".repeat(40) + "```\n"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 280)

        assertEquals("Las vallas sin cerrar no se pueden dejar en la vista previa", 0, fences(preview))
    }

    @Test
    fun `closed code fence inside the window is preserved as a whole block`() {
        val code = "```\ncode line\n```"
        // Todo el bloque de código está dentro de la ventana y el texto largo que sigue excede la ventana y se descarta. —— Como resultado, las vallas deben estar emparejadas.
        val body = "$code\n\n" + "Hay mucho más por venir".repeat(40)

        val preview = MarkdownPreviewTruncation.preview(body, limit = 200)

        assertEquals("Las vallas emparejadas deben conservarse enteras.", 2, fences(preview))
        assertTrue("El contenido del bloque de código debe mantenerse intacto.", preview.contains("code line"))
    }

    @Test
    fun `truncation does not split a bold marker`() {
        val body = "x".repeat(276) + "**centrarse**"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 280)

        assertFalse("no puedo poner ** Cortar y permanecer en el texto.", preview.dropLast(1).endsWith("*"))
    }

    @Test
    fun `truncation does not split a citation bracket`() {
        val body = "x".repeat(278) + "[12] finalizando"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 280)

        assertFalse("no puedo poner [N] Cortar por la mitad y dejarlo en el texto.", preview.contains("[1" + MarkdownPreviewTruncation.ELLIPSIS))
    }

    @Test
    fun `truncates on block boundary instead of mid paragraph`() {
        val blockOne = "primer párrafo".repeat(5) // 70 caracteres, dentro del límite de 80
        val blockTwo = "Segundo párrafo".repeat(20)
        val body = "$blockOne\n\n$blockTwo"

        val preview = MarkdownPreviewTruncation.preview(body, limit = 80)

        assertTrue("El primer párrafo debe dejarse intacto.", preview.startsWith(blockOne))
        assertFalse("La segunda sección no debe cortarse por la mitad y rellenarse por dentro.", preview.contains(blockTwo.take(5)))
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals("", MarkdownPreviewTruncation.preview(""))
    }
}
