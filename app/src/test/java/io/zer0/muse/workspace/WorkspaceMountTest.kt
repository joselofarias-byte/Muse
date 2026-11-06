package io.zer0.muse.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pruebas de regresión de la capa de montaje del espacio de trabajo.
 *
 * Cobertura: montaje predeterminado disponible, resolución de prefijo de ruta, montaje de solo lectura rechaza escritura, rechazos de ruta fuera de límites,
 * Límite de visibilidad de la sesión, verificación de identidad y eliminación.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceMountTest {

    private lateinit var manager: WorkspaceManager

    private lateinit var context: Context

    /** Un directorio externo utilizado para simular \"un lugar elegido por el usuario\". */
    private lateinit var externalDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WorkspaceManager(context)
        externalDir = File(context.filesDir, "external-doc").apply { mkdirs() }
    }

    /** Monte un directorio externo de solo lectura. */
    private suspend fun mountReadOnly(id: String = "docs"): WorkspaceMount {
        val mount = WorkspaceMount(
            id = id,
            label = "Documentación",
            provider = MountProvider.LOCAL,
            rootPath = externalDir.absolutePath,
            capabilities = WorkspaceMount.READ_ONLY_CAPABILITIES,
            createdAt = System.currentTimeMillis(),
        )
        val result = manager.upsertMount(mount)
        assertTrue("El montaje debería tener éxito.: $result", result is WorkspaceMountStore.MountStoreResult.Success)
        return mount
    }

    @Test
    fun defaultMountIsAlwaysPresent() = runTest {
        val mounts = manager.mounts()
        assertEquals(2, mounts.size)
        assertEquals(WorkspaceMount.MAIN_ID, mounts.first().id)
        assertTrue(mounts.first().canWrite)
    }

    @Test
    fun builtInToolOutputsMountIsListedReadOnly() = runTest {
        val mounts = manager.mounts()
        val toolOutputs = mounts.first { it.id == WorkspaceMountStore.TOOL_OUTPUTS_MOUNT_ID }
        assertTrue("Se deben incluir los soportes de salida de herramientas incorporados", toolOutputs.canList)
        assertTrue("Los soportes de salida de herramientas incorporados deben ser legibles", toolOutputs.canRead)
        assertTrue("El soporte de salida de herramienta incorporado no se puede escribir", toolOutputs.isReadOnly)
        assertTrue("Los soportes integrados deben marcarse como elementos del sistema.", toolOutputs.system)
    }

    @Test
    fun defaultRootViewListsToolOutputsMount() = runTest {
        val result = manager.listDir("")
        assertTrue("La raíz predeterminada de la columna debería tener éxito: $result", result is WorkspaceManager.ListResult.Success)
        val paths = (result as WorkspaceManager.ListResult.Success).entries.map { it.relativePath }
        assertTrue("La vista raíz predeterminada debe contener montajes de salida de herramientas: $paths", paths.contains("tool_outputs:/"))
    }

    @Test
    fun toolOutputsMountRejectsWrite() = runTest {
        val result = manager.writeFile("tool_outputs:/a.txt", "x")
        assertTrue("Los soportes de salida de herramientas integrados deben denegar escrituras: $result", result is WorkspaceManager.OpResult.Error)
        assertTrue("El mensaje debe indicar solo lectura: $result", (result as WorkspaceManager.OpResult.Error).message.contains("solo lectura"))
    }

    @Test
    fun toolOutputFilesAreReadableThroughTheMount() = runTest {
        val dir = File(context.filesDir, WorkspaceMountStore.TOOL_OUTPUTS_MOUNT_ID).apply { mkdirs() }
        File(dir, "capture.txt").writeText("Salida larga de herramienta", Charsets.UTF_8)
        val result = manager.readFile("tool_outputs:/capture.txt")
        assertTrue("El soporte de salida de la herramienta debe ser legible: $result", result is WorkspaceManager.ReadResult.Success)
        assertEquals("Salida larga de herramienta", (result as WorkspaceManager.ReadResult.Success).content)
    }

    @Test
    fun builtInToolOutputsMountCannotBeRemovedOrRenamed() = runTest {
        val id = WorkspaceMountStore.TOOL_OUTPUTS_MOUNT_ID
        assertTrue("Los soportes integrados no se pueden eliminar", !manager.removeMount(id))
        val renamed = WorkspaceMount(
            id = id,
            label = "Intenta cambiar tu nombre",
            provider = MountProvider.LOCAL,
            rootPath = externalDir.absolutePath,
        )
        val upsert = manager.upsertMount(renamed)
        assertTrue("No se puede cambiar el nombre de los soportes integrados: $upsert", upsert is WorkspaceMountStore.MountStoreResult.Error)
        assertTrue("El soporte incorporado aún debería estar en la lista.", manager.mounts().any { it.id == id })
    }

    @Test
    fun pathWithoutPrefixResolvesToDefaultMount() = runTest {
        val write = manager.writeFile("note.txt", "hello")
        assertTrue("La escritura debería tener éxito.: $write", write is WorkspaceManager.OpResult.Success)
        val direct = manager.readFile("note.txt")
        val prefixed = manager.readFile("main:/note.txt")
        assertEquals(
            (direct as WorkspaceManager.ReadResult.Success).content,
            (prefixed as WorkspaceManager.ReadResult.Success).content,
        )
    }

    @Test
    fun readOnlyMountRejectsWrite() = runTest {
        mountReadOnly()
        val result = manager.writeFile("docs:/a.txt", "x")
        assertTrue("Los montajes de solo lectura deben denegar escrituras: $result", result is WorkspaceManager.OpResult.Error)
        val message = (result as WorkspaceManager.OpResult.Error).message
        assertTrue("El mensaje debe indicar solo lectura: $message", message.contains("solo lectura"))
    }

    @Test
    fun readOnlyMountStillAllowsRead() = runTest {
        mountReadOnly()
        File(externalDir, "a.txt").writeText("desde directorio externo", Charsets.UTF_8)
        val result = manager.readFile("docs:/a.txt")
        assertTrue("Los montajes de solo lectura deben ser legibles: $result", result is WorkspaceManager.ReadResult.Success)
        assertEquals("desde directorio externo", (result as WorkspaceManager.ReadResult.Success).content)
    }

    @Test
    fun parentSegmentIsRejected() = runTest {
        mountReadOnly()
        val result = manager.readFile("docs:/../escape.txt")
        assertTrue("Las rutas que contienen .. deben rechazarse: $result", result is WorkspaceManager.ReadResult.Error)
    }

    @Test
    fun listDirShowsMountPrefixedPaths() = runTest {
        mountReadOnly()
        File(externalDir, "inner").mkdirs()
        val result = manager.listDir("docs:/")
        assertTrue("El directorio de listado debería tener éxito: $result", result is WorkspaceManager.ListResult.Success)
        val names = (result as WorkspaceManager.ListResult.Success).entries.map { it.relativePath }
        assertTrue("La ruta de la subclave debe tener el prefijo mount: $names", names.any { it == "docs:/inner" })
    }

    @Test
    fun sessionScopeBlocksUnlistedMount() = runTest {
        mountReadOnly()
        manager.setSessionMounts("session-a", setOf(WorkspaceMount.MAIN_ID))
        val scope = manager.sessionMountIds("session-a")
        assertNotNull(scope)
        val blocked = manager.listDir("docs:/", scope)
        assertTrue("Las monturas fuera de alcance deben rechazarse: $blocked", blocked is WorkspaceManager.ListResult.Error)
        val allowed = manager.listDir("", scope)
        assertTrue("Los soportes predeterminados dentro del alcance deberían estar disponibles: $allowed", allowed is WorkspaceManager.ListResult.Success)
    }

    @Test
    fun invalidMountIdIsRejected() = runTest {
        val bad = WorkspaceMount(
            id = "Docs!",
            label = "mal logotipo",
            provider = MountProvider.LOCAL,
            rootPath = externalDir.absolutePath,
        )
        val result = manager.upsertMount(bad)
        assertTrue("Los logotipos ilegales deben rechazarse: $result", result is WorkspaceMountStore.MountStoreResult.Error)
    }

    @Test
    fun removingMountDropsItFromRegistry() = runTest {
        mountReadOnly()
        assertTrue(manager.removeMount("docs"))
        assertEquals(2, manager.mounts().size)
        val result = manager.listDir("docs:/")
        assertTrue("Los soportes eliminados ya no deberían estar disponibles: $result", result is WorkspaceManager.ListResult.Error)
    }

    @Test
    fun defaultMountCannotBeRemoved() = runTest {
        assertTrue(!manager.removeMount(WorkspaceMount.MAIN_ID))
        assertEquals(2, manager.mounts().size)
    }

    @Test
    fun grepRejectsPathWithParentSegment() = runTest {
        mountReadOnly()
        val result = manager.grep("needle", "docs:/../escape.md")
        assertTrue("Las rutas que contienen .. deberían dar un error en lugar de generar una excepción: $result", result is WorkspaceManager.GrepResult.Error)
    }

    @Test
    fun grepOnMountOutsideScopeReturnsError() = runTest {
        mountReadOnly()
        File(externalDir, "a.md").writeText("needle", Charsets.UTF_8)
        val result = manager.grep("needle", "docs:/", scope = setOf(WorkspaceMount.MAIN_ID))
        assertTrue("Los montajes fuera de alcance deberían dar un error en lugar de generar una excepción: $result", result is WorkspaceManager.GrepResult.Error)
    }

    @Test
    fun grepOnReadOnlyMountStillFindsMatches() = runTest {
        mountReadOnly()
        File(externalDir, "a.md").writeText("needle\nother", Charsets.UTF_8)
        val result = manager.grep("needle", "docs:/")
        assertTrue("Los montajes de solo lectura deben poder buscarse: $result", result is WorkspaceManager.GrepResult.Success)
        assertEquals(1, (result as WorkspaceManager.GrepResult.Success).matches.size)
    }

    @Test
    fun grepWithoutPathSpansAllVisibleMounts() = runTest {
        mountReadOnly()
        File(externalDir, "a.md").writeText("needle", Charsets.UTF_8)
        manager.writeFile("b.txt", "needle here")
        val result = manager.grep("needle") as WorkspaceManager.GrepResult.Success
        assertEquals("Ambos golpes deben ser tomados en: ${result.matches}", 2, result.matches.size)
        assertTrue(
            "La ruta de acceso debe tener un prefijo de montaje.: ${result.matches.map { it.path }}",
            result.matches.any { it.path.startsWith("docs:/") },
        )
    }

    @Test
    fun copyBetweenMountsCarriesContent() = runTest {
        mountReadOnly()
        File(externalDir, "src.txt").writeText("Contenido de montaje cruzado", Charsets.UTF_8)
        val result = manager.copy("docs:/src.txt", "copied.txt")
        assertTrue("La copia entre montaje debería realizarse correctamente: $result", result is WorkspaceManager.OpResult.Success)
        val back = manager.readFile("copied.txt")
        assertEquals("Contenido de montaje cruzado", (back as WorkspaceManager.ReadResult.Success).content)
    }
}
