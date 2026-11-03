package io.zer0.muse.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M5.1: Pruebas de regresión de límites de ruta de trabajo.
 *
 * Aceptación de cobertura:Ruta fuera de límites rechazada —— `..` Recorrido, ruta absoluta, anulación de prefijo,
 * Las rutas relativas normales todavía están disponibles;Todas las IO se hacen públicas API(Consistente con la ruta de producción).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceBoundaryTest {

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = WorkspaceManager(context)
    }

    @Test
    fun `normal relative paths work inside the workspace`() = runTest {
        val write = manager.writeFile("docs/notes.txt", "hello boundary")
        assertTrue("Las escrituras normales deberían tener éxito: $write", write is WorkspaceManager.OpResult.Success)

        val read = manager.readFile("docs/notes.txt")
        assertTrue(read is WorkspaceManager.ReadResult.Success)
        assertTrue((read as WorkspaceManager.ReadResult.Success).content == "hello boundary")

        val list = manager.listDir("docs")
        assertTrue(list is WorkspaceManager.ListResult.Success)
    }

    @Test
    fun `workspace read pages oversized files without dropping later content`() = runTest {
        val content = "workspace output line\n".repeat(90_000)
        val path = java.io.File(manager.rootDir, "large-output.txt")
        try {
            assertTrue(manager.writeFile("large-output.txt", content) is WorkspaceManager.OpResult.Success)

            val first = manager.readFilePage("large-output.txt")
            val second = manager.readFilePage(
                "large-output.txt",
                offsetChars = TOOL_OUTPUT_READ_PAGE_CHARS,
                lengthChars = TOOL_OUTPUT_READ_PAGE_CHARS,
            )

            assertTrue(first is WorkspaceManager.ReadResult.Success)
            assertTrue(second is WorkspaceManager.ReadResult.Success)
            assertTrue((first as WorkspaceManager.ReadResult.Success).content.startsWith(content.take(TOOL_OUTPUT_READ_PAGE_CHARS)))
            assertTrue(first.content.contains("workspace_read"))
            assertTrue(
                (second as WorkspaceManager.ReadResult.Success).content.startsWith(
                    content.substring(TOOL_OUTPUT_READ_PAGE_CHARS, TOOL_OUTPUT_READ_PAGE_CHARS * 2),
                ),
            )
        } finally {
            path.delete()
        }
    }

    @Test
    fun `dot dot traversal is rejected`() = runTest {
        val write = manager.writeFile("../escape.txt", "should not exist")
        assertTrue(write is WorkspaceManager.OpResult.Error)

        val read = manager.readFile("a/../../escape.txt")
        assertTrue(read is WorkspaceManager.ReadResult.Error)

        // No se deben generar archivos fuera del espacio de trabajo
        val escaped = java.io.File(manager.rootDir.parentFile, "escape.txt")
        assertFalse("Los documentos ultravioleta no deben existir.", escaped.exists())
    }

    @Test
    fun `absolute style paths never escape the root`() = runTest {
        // Preámbulo '/' Después de recortarlo, equivale a una ruta relativa.,El punto de entrega aún debe estar dentro del área de trabajo.(containment Semántica)
        val write = manager.writeFile("/etc/hosts", "contained")
        assertTrue(write is WorkspaceManager.OpResult.Success)
        assertTrue(
            "El punto de colocación debe estar dentro de la raíz del espacio de trabajo.",
            java.io.File(manager.rootDir, "etc/hosts").exists(),
        )
        // Ruta completa con prefijo raíz:El archivo no existe → Debe devolver un error,No debe triunfar en silencio
        val read = manager.readFile("/data/data/io.zer0.muse/files/workspace/docs/missing.txt")
        assertTrue(read is WorkspaceManager.ReadResult.Error)
    }

    @Test
    fun `deletion stays inside the root`() = runTest {
        manager.writeFile("keep/a.txt", "x")
        val delete = manager.delete("../outside-target")
        assertTrue(delete is WorkspaceManager.OpResult.Error)
        assertTrue("Los archivos normales todavía existen", manager.readFile("keep/a.txt") is WorkspaceManager.ReadResult.Success)
    }
}
