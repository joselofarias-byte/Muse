package io.zer0.muse.ui.markdown

import io.zer0.muse.ui.markdown.CodeHighlighter.DiffLineKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E6: CodeHighlighter diff Pruebas unitarias de clasificación de filas.
 *
 * classifyDiffLine Para funciones puras internas,Se puede acceder directamente a las pruebas del mismo módulo.;
 * highlight/highlightDiff es @Composable/privado,Cubierto por la capa de renderizado.
 */
class CodeHighlighterTest {

    @Test
    fun `hunk header lines classify as HUNK_HEADER`() {
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("@@ -1,3 +1,4 @@"))
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("+++ b/src/Main.kt"))
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("--- a/src/Main.kt"))
        // Encabezado de fragmento simplificado sin segmento de número de línea
        assertEquals(DiffLineKind.HUNK_HEADER, CodeHighlighter.classifyDiffLine("@@"))
    }

    @Test
    fun `added and removed lines classify correctly`() {
        assertEquals(DiffLineKind.ADDED, CodeHighlighter.classifyDiffLine("+val x = 1"))
        assertEquals(DiffLineKind.REMOVED, CodeHighlighter.classifyDiffLine("-val x = 1"))
    }

    @Test
    fun `double plus prefix is ADDED not HUNK_HEADER`() {
        // "++ foo" comienza con + pero no es +++,debe ser clasificado como ADDED(+++ La determinación precede a la decisión.)
        assertEquals(DiffLineKind.ADDED, CodeHighlighter.classifyDiffLine("++ foo"))
        assertEquals(DiffLineKind.REMOVED, CodeHighlighter.classifyDiffLine("-- foo"))
    }

    @Test
    fun `context and empty lines classify correctly`() {
        assertEquals(DiffLineKind.CONTEXT, CodeHighlighter.classifyDiffLine(" context line"))
        assertEquals(DiffLineKind.CONTEXT, CodeHighlighter.classifyDiffLine("val keep = true"))
        assertEquals(DiffLineKind.EMPTY, CodeHighlighter.classifyDiffLine(""))
    }

    @Test
    fun `no diff marker in middle of line is CONTEXT`() {
        assertEquals(DiffLineKind.CONTEXT, CodeHighlighter.classifyDiffLine("val s = \"-not-a-diff\""))
    }
}
