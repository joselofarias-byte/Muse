package io.zer0.muse.ui.markdown

import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseMarkdownRhythm
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E3 escenario 5 · Calibración del diseño del contenido del chat largo:Markdown Función pura de ritmo vertical de respuesta larga [markdownBlockGap] Prueba única.
 *
 * Verificar solo"¿Cuánto espacio se debe dejar entre bloques?"semántica(Párrafo/Título/Lista/Bloque de estructura),No hay renderizado involucrado.
 */
class MarkdownRhythmTest {

    private val paragraph = MarkdownBlock.Paragraph("Texto")
    private val heading = MarkdownBlock.Heading(2, "Subtítulo")
    private val code = MarkdownBlock.CodeBlock("kotlin", "val x = 1")
    private val table = MarkdownBlock.Table(listOf("a", "b"), listOf(listOf("1", "2")))
    private val quote = MarkdownBlock.Quote("Cotización")
    private val listA = MarkdownBlock.ListItem(ordered = false, index = 0, text = "uno")
    private val listB = MarkdownBlock.ListItem(ordered = false, index = 0, text = "dos")

    @Test
    fun first_block_has_no_leading_gap() {
        assertEquals(0.dp, markdownBlockGap(prev = null, current = paragraph, blankBetween = false))
    }

    @Test
    fun soft_line_break_without_blank_line_stays_flush() {
        assertEquals(0.dp, markdownBlockGap(paragraph, paragraph, blankBetween = false))
    }

    @Test
    fun blank_line_separates_paragraphs() {
        assertEquals(
            MuseMarkdownRhythm.paragraphGap,
            markdownBlockGap(paragraph, paragraph, blankBetween = true),
        )
    }

    @Test
    fun heading_has_more_space_above_than_below() {
        assertEquals(
            MuseMarkdownRhythm.headingSpaceAbove,
            markdownBlockGap(paragraph, heading, blankBetween = true),
        )
        assertEquals(
            MuseMarkdownRhythm.headingSpaceBelow,
            markdownBlockGap(heading, paragraph, blankBetween = false),
        )
        // Toma títulos adyacentes"título anterior"archivo,No colapsar en un pequeño espacio en blanco debajo del título.
        assertEquals(
            MuseMarkdownRhythm.headingSpaceAbove,
            markdownBlockGap(heading, heading, blankBetween = false),
        )
    }

    @Test
    fun consecutive_list_items_stay_compact_even_when_source_has_blank_lines() {
        assertEquals(
            MuseMarkdownRhythm.listItemGap,
            markdownBlockGap(listA, listB, blankBetween = true),
        )
    }

    @Test
    fun structural_blocks_get_the_block_gap_on_both_sides() {
        assertEquals(MuseMarkdownRhythm.blockGap, markdownBlockGap(paragraph, code, blankBetween = true))
        assertEquals(MuseMarkdownRhythm.blockGap, markdownBlockGap(code, paragraph, blankBetween = true))
        assertEquals(MuseMarkdownRhythm.blockGap, markdownBlockGap(paragraph, table, blankBetween = true))
        assertEquals(MuseMarkdownRhythm.blockGap, markdownBlockGap(quote, paragraph, blankBetween = true))
    }

    @Test
    fun block_gap_wins_over_paragraph_gap() {
        // Los bloques estructurales deben dejarse en blanco de acuerdo con el espacio entre bloques, incluso si están cerca del texto principal.(No cubierto por el párrafoGap)
        assertEquals(MuseMarkdownRhythm.blockGap, markdownBlockGap(paragraph, quote, blankBetween = true))
        // Cuando no hay ningún bloque de estructura,Cae al nivel de párrafo. paragraphGap
        assertEquals(MuseMarkdownRhythm.paragraphGap, markdownBlockGap(paragraph, listA, blankBetween = true))
    }
}
