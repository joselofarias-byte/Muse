package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workspace_edit Pruebas principales de determinación: tres tipos de copia previa al rechazo, recuento de superposiciones, número de línea de diferenciación a nivel de línea.
 *
 * Esta clase solo ejecuta lógica pura, no toca el sistema de archivos y no es compatible con Robolectric; consulte WorkspaceEditToolTest para conocer el comportamiento de caída del disco.
 */
class WorkspaceEditPlanTest {

    private fun plan(original: String, oldString: String, newString: String): EditPlan =
        WorkspaceTool.planEdit(original, oldString, newString, "note.txt")

    private fun rejectionMessage(plan: EditPlan): String {
        assertTrue("debe ser rechazado, real: $plan", plan is EditPlan.Rejected)
        return (plan as EditPlan.Rejected).message
    }

    @Test
    fun `Si el fragmento aparece 0 veces, será rechazado y se indicará que no se puede encontrar.`() {
        val message = rejectionMessage(plan("primera linea\nsegunda linea\n", "fragmento que no existe", "nuevo contenido"))
        assertTrue(message, message.contains("No se puede encontrar el fragmento."))
        assertTrue(message, message.contains("Archivo sin cambios"))
    }

    @Test
    fun `Cuando un fragmento aparece varias veces, rechácelo e informe el número de apariciones.`() {
        val message = rejectionMessage(plan("x\ntarget\ny\ntarget\n", "target", "ok"))
        assertTrue(message, message.contains("Aparece 2 veces"))
        assertTrue(message, message.contains("solo"))
        // Rechace explícitamente el método de corrección silenciosa de "reemplazar solo la primera posición"
        assertTrue(message, message.contains("No reemplazará solo el primer lugar."))
    }

    @Test
    fun `Rechazar si el contenido antiguo y el nuevo son iguales`() {
        val message = rejectionMessage(plan("a\nb\n", "b", "b"))
        assertTrue(message, message.contains("El contenido antiguo y el nuevo son iguales."))
    }

    @Test
    fun `La cadena_antigua vacía se rechaza directamente en lugar de insertarse.`() {
        val message = rejectionMessage(plan("abc", "", "x"))
        assertTrue(message, message.contains("old_string no puede estar vacío"))
    }

    @Test
    fun `CRLF Si la notación LF no se encuentra en el archivo, indique la diferencia en los saltos de línea.`() {
        val message = rejectionMessage(plan("a\r\nb\r\nc\r\n", "a\nb", "x"))
        assertTrue(message, message.contains("CRLF"))
    }

    @Test
    fun `Las ocurrencias superpuestas también se cuentan como múltiples veces.`() {
        assertEquals(2, WorkspaceTool.countOccurrences("aaa", "aa"))
        assertEquals(1, WorkspaceTool.countOccurrences("abc", "abc"))
        assertEquals(0, WorkspaceTool.countOccurrences("abc", "z"))
        assertEquals(0, WorkspaceTool.countOccurrences("abc", ""))
    }

    @Test
    fun `El reemplazo de fragmentos únicos solo cambia la posición del objetivo`() {
        val applied = plan("a\ntarget\nz\n", "target", "done") as EditPlan.Applied
        assertEquals("a\ndone\nz\n", applied.updated)
    }

    @Test
    fun `diff Informar la primera línea modificada y el número de líneas reemplazadas`() {
        val original = (1..10).joinToString("\n") { "line$it" } + "\n"
        val updated = original.replace("line5", "LINE5\nanexar")
        val diff = WorkspaceTool.diffLines(original, updated)

        assertEquals(5, diff.firstChangedLine)
        // 1 línea del texto original ha sido reemplazada por 2 líneas
        assertEquals(1, diff.oldLineCount)
        assertEquals(2, diff.newLineCount)
        // Línea de contexto 3: líneas 2 a 8 del texto original, líneas 2 a 9 del texto nuevo
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -2,7 +2,8 @@"))
        assertTrue(diff.hunk, diff.hunk.contains("\n-line5\n"))
        assertTrue(diff.hunk, diff.hunk.contains("\n+LINE5\n+anexar\n"))
        // Líneas de contexto sin modificar con espacios principales.
        assertTrue(diff.hunk, diff.hunk.contains("\n line4\n"))
    }

    @Test
    fun `Los cambios en el encabezado del archivo comienzan en la línea 1 y no tienen contexto anterior.`() {
        val diff = WorkspaceTool.diffLines("head\nb\nc\n", "HEAD\nb\nc\n")
        assertEquals(1, diff.firstChangedLine)
        assertEquals(1, diff.oldLineCount)
        assertEquals(1, diff.newLineCount)
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -1,4 +1,4 @@"))
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -1,4 +1,4 @@\n-head\n+HEAD\n b\n"))
    }

    @Test
    fun `Los cambios al final del archivo no dejan contexto fuera de límites`() {
        val diff = WorkspaceTool.diffLines("a\nb\ntail\n", "a\nb\nTAIL\n")
        assertEquals(3, diff.firstChangedLine)
        assertTrue(diff.hunk, diff.hunk.startsWith("@@ -1,4 +1,4 @@"))
        assertTrue(diff.hunk, diff.hunk.endsWith("-tail\n+TAIL"))
    }
}
