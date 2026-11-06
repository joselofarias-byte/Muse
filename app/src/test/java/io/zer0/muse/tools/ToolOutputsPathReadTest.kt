package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * read_file La ruta al directorio de salida de la herramienta.
 *
 * El recibo también incluye el formulario de montaje. `tool_outputs:/x.txt` forma relativa `tool_outputs/x.txt`,
 * Ambos métodos de escritura deben leer el mismo archivo; la forma relativa solía tener una capa adicional de prefijos y nunca podía chocar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolOutputsPathReadTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun prepareFile(): File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.filesDir, TOOL_OUTPUTS_DIR).apply { mkdirs() }
        return File(dir, "capture.txt").apply { writeText("Contenido de salida completo", Charsets.UTF_8) }
    }

    @Test
    fun `read_file opens tool outputs by relative path`() = runBlocking {
        prepareFile()
        val result = FileTools.execute(
            FileTools.NAME_READ_FILE,
            mapOf("path" to "$TOOL_OUTPUTS_DIR/capture.txt"),
            ApplicationProvider.getApplicationContext(),
            tempFolder.newFolder("workspace"),
        )
        assertEquals("Contenido de salida completo", result)
    }

    @Test
    fun `read_file accepts the mount form used in receipts`() = runBlocking {
        prepareFile()
        val result = FileTools.execute(
            FileTools.NAME_READ_FILE,
            mapOf("path" to "$TOOL_OUTPUTS_DIR:/capture.txt"),
            ApplicationProvider.getApplicationContext(),
            tempFolder.newFolder("workspace"),
        )
        assertEquals("Contenido de salida completo", result)
        assertTrue("No debe informar que la ruta no existe.: $result", !result.contains("El archivo no existe"))
    }
}
