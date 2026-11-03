package io.zer0.muse.tools

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Denominación correcta de importaciones concurrentes.
 *
 * El número de serie se agregará automáticamente cuando haya un nombre duplicado, y la determinación del número de serie y la realización del pedido deben completarse dentro de la misma retención de bloqueo;
 * Si solo se bloquea la escritura, dos importaciones simultáneas calcularán cada una el mismo número de secuencia y la que se escriba más tarde sobrescribirá la que se escribió primero.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkspaceImportConcurrencyTest {

    private lateinit var context: Context

    private lateinit var manager: WorkspaceManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WorkspaceManager(context)
    }

    /** Cree un archivo fuente para importarlo y devuelva su URI. */
    private fun sourceFile(name: String, content: String): Uri {
        val file = File(context.cacheDir, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return Uri.fromFile(file)
    }

    private fun importedContent(relativePath: String): String =
        runBlocking {
            val result = manager.readFile(relativePath)
            assertTrue("Los resultados de la importación no se pueden volver a leer: $relativePath -> $result", result is WorkspaceManager.ReadResult.Success)
            (result as WorkspaceManager.ReadResult.Success).content
        }

    @Test
    fun `Cuando importo archivos con el mismo nombre simultáneamente, obtengo dos nombres de archivo diferentes y ambos contenidos están en`() {
        val first = sourceFile("import-a.txt", "Contenido A: Primera copia\n")
        val second = sourceFile("import-b.txt", "Contenido B: Segunda copia\n")

        val results = runBlocking {
            coroutineScope {
                listOf(first, second).map { uri ->
                    async(Dispatchers.IO) {
                        manager.importFile(uri, targetDirectory = "", displayName = "note.txt")
                    }
                }.awaitAll()
            }
        }

        val paths = results.map { result ->
            assertTrue("Importación fallida: $result", result is WorkspaceManager.FileImportResult.Success)
            (result as WorkspaceManager.FileImportResult.Success).relativePath
        }

        assertEquals("Se deben realizar dos importaciones con el mismo nombre en dos archivos: $paths", 2, paths.toSet().size)
        assertEquals(
            "Ambos contenidos deben estar en: ${paths.map { importedContent(it) }}",
            setOf("Contenido A: Primera copia\n", "Contenido B: Segunda copia\n"),
            paths.map { importedContent(it) }.toSet(),
        )
    }

    @Test
    fun `Después de la importación simultánea, continuar importando un tercer archivo con el mismo nombre aún pospondrá el número de serie.`() {
        val first = sourceFile("import-c.txt", "c1")
        val second = sourceFile("import-d.txt", "c2")
        val third = sourceFile("import-e.txt", "c3")

        val paths = runBlocking {
            coroutineScope {
                listOf(first, second).map { uri ->
                    async(Dispatchers.IO) { manager.importFile(uri, targetDirectory = "", displayName = "same.txt") }
                }.awaitAll()
            }.map { (it as WorkspaceManager.FileImportResult.Success).relativePath }
        }
        val thirdPath = runBlocking { manager.importFile(third, targetDirectory = "", displayName = "same.txt") }

        assertTrue("La tercera importación falló: $thirdPath", thirdPath is WorkspaceManager.FileImportResult.Success)
        val allPaths = paths + (thirdPath as WorkspaceManager.FileImportResult.Success).relativePath
        assertEquals("Se deben completar tres importaciones en tres archivos.: $allPaths", 3, allPaths.toSet().size)
    }
}
