package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * workspace_edit Pruebas de un extremo a otro: Ir [WorkspaceTool.execute] → [WorkspaceManager] enlace completo.
 *
 * Se clavan cuatro cosas: los tres tipos de rechazo previo de su respectivo copywriting y el expediente no ha sido modificado; después de un reemplazo exitoso, el resto del archivo permanece sin cambios palabra por palabra;
 * Fragmentos de líneas cruzadas y encabezados de archivos/Límite final; diferencia de nivel de fila incluida en el recibo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceEditToolTest {

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = WorkspaceManager(context)
    }

    /** Escribe un documento de prueba. */
    private fun seed(path: String, content: String) {
        val result = runBlocking { manager.writeFile(path, content) }
        assertTrue("El dispositivo de escritura falló: $result", result is WorkspaceManager.OpResult.Success)
    }

    /** Vuelva a leer el texto completo del archivo. */
    private fun contentOf(path: String): String =
        runBlocking {
            val result = manager.readFile(path)
            assertTrue("Error de lectura: $result", result is WorkspaceManager.ReadResult.Success)
            (result as WorkspaceManager.ReadResult.Success).content
        }

    /**
     * Una llamada workspace_edit.
     *
     * ir a proposito [WorkspaceTool.execute] Más que una función interna: este vínculo es el que realmente toma el modelo.
     */
    private fun edit(path: String, oldString: String, newString: String): String =
        WorkspaceTool.execute(
            WorkspaceTool.NAME_EDIT,
            mapOf("path" to path, "old_string" to oldString, "new_string" to newString),
            manager,
        )

    @Test
    fun `Rechazar cuando no se encuentra el fragmento y el archivo no se modifica`() {
        seed("a.txt", "primera linea\nsegunda linea\nla tercera linea\n")

        val receipt = edit("a.txt", "fragmento que no existe", "x")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("No se puede encontrar el fragmento."))
        assertEquals("primera linea\nsegunda linea\nla tercera linea\n", contentOf("a.txt"))
    }

    @Test
    fun `Los fragmentos se rechazan si aparecen varias veces y el archivo no se modifica`() {
        val original = "import a\n\nfun first() = 1\n\nfun second() = 1\n"
        seed("b.txt", original)

        val receipt = edit("b.txt", "= 1", "= 2")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("Aparece 2 veces"))
        assertEquals(original, contentOf("b.txt"))
    }

    @Test
    fun `Rechazar si el contenido antiguo y el nuevo son iguales y el archivo no ha sido modificado`() {
        seed("c.txt", "alpha\nbeta\n")

        val receipt = edit("c.txt", "beta", "beta")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("El contenido antiguo y el nuevo son iguales."))
        assertEquals("alpha\nbeta\n", contentOf("c.txt"))
    }

    @Test
    fun `Después del reemplazo normal, el resto del archivo permanece sin cambios palabra por palabra`() {
        val original =
            "# Título\n" +
                "\n" +
                "Primer párrafo: contiene emoji 🎯 con carácter de tabulación\tDE ACUERDO.\n" +
                "  Sangrar una línea por dos espacios  \n" +
                "const value = 1;\n" +
                "const other = 2;\n" +
                "No hay línea en blanco al final"
        seed("d.txt", original)

        val receipt = edit("d.txt", "const value = 1;", "const value = 42;")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals(original.replace("const value = 1;", "const value = 42;"), contentOf("d.txt"))
    }

    @Test
    fun `Reemplazar segmento completo a través de líneas`() {
        val original = "header\nfun a() {\n    return 1\n}\nfooter\n"
        seed("e.txt", original)

        val receipt = edit("e.txt", "fun a() {\n    return 1\n}", "fun a() {\n    return 2\n}")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("header\nfun a() {\n    return 2\n}\nfooter\n", contentOf("e.txt"))
    }

    @Test
    fun `El reemplazo es exitoso cuando el fragmento se encuentra al principio del archivo`() {
        seed("f.txt", "antigua primera línea\nb\nc\n")

        val receipt = edit("f.txt", "antigua primera línea", "Nueva primera línea")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("Nueva primera línea\nb\nc\n", contentOf("f.txt"))
    }

    @Test
    fun `El reemplazo es exitoso cuando el fragmento está al final del archivo`() {
        seed("g.txt", "a\nb\nantigua línea de cola")

        val receipt = edit("g.txt", "antigua línea de cola", "Nueva línea de cola")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("a\nb\nNueva línea de cola", contentOf("g.txt"))
    }

    @Test
    fun `El recibo tiene una diferencia a nivel de línea que indica qué líneas se han modificado`() {
        val original = (1..8).joinToString("\n") { "line$it" } + "\n"
        seed("h.txt", original)

        val receipt = edit("h.txt", "line4", "LINE4\nAgregar nueva fila")

        assertTrue(receipt, receipt.startsWith("OK: workspace_edit"))
        // Resumen: primera línea modificada + número de líneas modificadas
        assertTrue(receipt, receipt.contains("primera línea cambiada 4"))
        assertTrue(receipt, receipt.contains("Texto original 1 línea → Nuevo contenido 2 lineas"))
        // @@ Encabezados y líneas agregadas y eliminadas
        assertTrue(receipt, receipt.contains("@@ -1,7 +1,8 @@"))
        assertTrue(receipt, receipt.contains("\n-line4\n"))
        assertTrue(receipt, receipt.contains("\n+LINE4\n+Agregar nueva fila\n"))
        // La línea de contexto todavía está ahí y se puede localizar la ubicación del cambio.
        assertTrue(receipt, receipt.contains("\n line3\n"))
        assertTrue(receipt, receipt.contains("\n line5\n"))
    }

    @Test
    fun `Vaciar new_string significa eliminar el fragmento`() {
        seed("i.txt", "keep\nremove me\nkeep\n")

        val receipt = edit("i.txt", "remove me\n", "")

        assertTrue(receipt, receipt.startsWith("OK:"))
        assertEquals("keep\nkeep\n", contentOf("i.txt"))
    }

    @Test
    fun `find_tools Esta herramienta se puede encontrar usando palabras clave de edición`() {
        // Descubribilidad: una vez distribuida la herramienta, hay que encontrarla. Tanto el nombre como la descripción deben contener palabras interrogativas comunes.
        val defs = WorkspaceTool.toolDefs()
        listOf("Editar archivo", "Editar", "reemplazar", "Modificar", "workspace edit").forEach { query ->
            val names = FindToolsTool.search(query, defs).map { it.name }
            assertTrue("Consulta \"$query\"se debe encontrar workspace_edit, pero en realidad: $names", WorkspaceTool.NAME_EDIT in names)
        }
    }

    @Test
    fun `Los cruces de caminos todavía están bloqueados por los límites del área de trabajo`() {
        val receipt = edit("../escape.txt", "a", "b")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains(".."))
    }

    @Test
    fun `Si el archivo no existe, se informará un error en lugar de crear uno nuevo`() {
        val receipt = edit("missing.txt", "a", "b")

        assertTrue(receipt, receipt.startsWith("Error:"))
        assertTrue(receipt, receipt.contains("no existe"))
        assertTrue(
            "Los archivos no deben crearse de la nada",
            runBlocking { manager.readFile("missing.txt") } is WorkspaceManager.ReadResult.Error,
        )
    }

    @Test
    fun `Las reglas de seguridad de contenido también solo verifican la ruta de workspace_edit`() {
        // Escribir con elipses y rutas absolutas en el texto no puede activar reglas de ruta.
        val benign =
            mapOf(
                "path" to "notes/todo.md",
                "old_string" to "Pendiente: Espere 3 días...",
                "new_string" to "Ver /usr/local/bin con C:\\Windows",
            )
        assertNull(ContentSafetyRules.checkArgs(WorkspaceTool.NAME_EDIT, benign))

        assertEquals(
            "workspace-traversal",
            ContentSafetyRules.checkArgs(
                WorkspaceTool.NAME_EDIT,
                mapOf("path" to "../etc/passwd", "old_string" to "a", "new_string" to "b"),
            )?.ruleId,
        )
        assertEquals(
            "workspace-absolute-path",
            ContentSafetyRules.checkArgs(
                WorkspaceTool.NAME_EDIT,
                mapOf("path" to "/etc/passwd", "old_string" to "a", "new_string" to "b"),
            )?.ruleId,
        )
    }
}
