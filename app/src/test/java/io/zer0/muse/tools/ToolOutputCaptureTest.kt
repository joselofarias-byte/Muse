package io.zer0.muse.tools

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.StringReader

class ToolOutputCaptureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `command output keeps the tail and drops the head`() {
        val output = "progress line\n".repeat(4_000) + "FAILED: assertion at BuildTest.kt:42\nexit=1"
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "terminal_exec")
        output.chunked(1_024).forEach(capture::append)

        val result = capture.finish(header = "exit=1", emptyMessage = "(Sin salida)")
        val savedFile = directory.listFiles()?.single()

        assertTrue(result.contains("[El resultado completo se ha guardado en:"))
        assertTrue(result.contains("path=tool_outputs:/"))
        assertTrue(result.contains("read_file"))
        assertTrue(result.contains("offset_chars=0"))
        // La conclusión del comando está al final: la cola debe estar allí y el registro de progreso de 4 KB al principio no debe ocupar espacio.
        assertTrue("Se debe conservar la conclusión final.", result.contains("FAILED: assertion at BuildTest.kt:42"))
        assertFalse("El registro de progreso del encabezado no debería aparecer en la vista previa", result.contains(output.take(TOOL_RESULT_PREVIEW_CHARS)))
        assertFalse(result.contains(output))
        assertEquals(output, savedFile?.readText())
    }

    @Test
    fun `pagination notice reports the total length of the captured output`() {
        val output = "progress line\n".repeat(4_000)
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "execute_shell")
        capture.append(output)

        val result = capture.finish(header = "exit=0", emptyMessage = "(Sin salida)")

        assertTrue("El mensaje de paginación debe indicar la cantidad total del texto original.", result.contains("texto original total ${output.length} palabra"))
        assertTrue("El mensaje de paginación debe indicar que la vista previa se toma desde el final.", result.contains("final de la salida"))
        assertTrue(result.contains("offset_chars=0"))
    }

    @Test
    fun `non command output keeps the head`() {
        val output = "header line\n".repeat(4_000) + "END OF FILE"
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "read_public_file")
        output.chunked(1_024).forEach(capture::append)

        val result = capture.finish(header = "", emptyMessage = "(vacío)")

        assertTrue("El comienzo de la salida de la clase de archivo es más útil.", result.contains(output.take(TOOL_RESULT_PREVIEW_CHARS)))
        assertTrue(result.contains("Inicio de la salida"))
        assertTrue(result.contains("texto original total ${output.length} palabra"))
    }

    @Test
    fun `tail preview never splits a surrogate pair`() {
        // Deje que el límite de 4 KB caiga exactamente en el medio de un emoji: el primer carácter de la mitad del ancho mueve todo el subíndice coincidente una posición.
        val output = "a" + "\uD83D\uDE00".repeat(3_000) + "\nDONE exit=0"
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        // El límite superior de en línea se establece en 1_000 palabras, lo que lo obliga a tomar la ruta de placa desplegable + vista previa final.
        val capture = ToolOutputCapture(directory, "terminal_exec", inlineLimitChars = 1_000)
        output.chunked(977).forEach(capture::append)

        val result = capture.finish(header = "", emptyMessage = "(Sin salida)")

        assertFalse("La vista previa no puede contener caracteres sustitutos huérfanos", hasUnpairedSurrogate(result))
        assertTrue(result.contains("DONE exit=0"))
        // Después de perder la mitad del agente, la cantidad de palabras en el mensaje aún debe ser consistente: mostradas + no mostradas = Cantidad total de texto original.
        val notice = Regex("""fin (\d+) Vista previa de palabras; total del texto original (\d+) palabra, comienzo (\d+) Palabras no mostradas""").find(result)
        assertTrue("El mensaje de búsqueda debe dar tres números.", notice != null)
        val shown = notice!!.groupValues[1].toInt()
        val total = notice.groupValues[2].toInt()
        val omitted = notice.groupValues[3].toInt()
        assertEquals(output.length, total)
        assertEquals("Mostrado + No mostrado debe ser igual a la cantidad total del texto original", total, shown + omitted)
    }

    @Test
    fun `small streamed output stays inline without creating a file`() {
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val capture = ToolOutputCapture(directory, "terminal_exec")
        capture.append("short result")

        assertEquals("exit=0\nshort result", capture.finish(header = "exit=0", emptyMessage = "(Sin salida)"))
        assertFalse(directory.exists())
    }

    @Test
    fun `captureTextStream keeps a full file while limiting only its parse preview`() {
        val source = "page text ".repeat(120_000)
        val directory = File(tempFolder.root, TOOL_OUTPUTS_DIR)
        val captured = captureTextStream(
            reader = StringReader(source),
            outputDirectory = directory,
            filePrefix = "parse_link",
            previewLimitChars = 1_000_000,
        )

        assertEquals(source.take(1_000_000), captured.preview)
        assertTrue(captured.reference.contains("offset_chars=0"))
        assertEquals(source, captured.savedFile?.readText())
    }

    @Test
    fun `large alternate-path tool results use the same complete file delivery`() = runBlocking {
        val context = mockk<Context>()
        every { context.filesDir } returns tempFolder.root
        val output = "large alternate tool result\n".repeat(2_000)

        val result = captureLargeToolOutput(context, "recovery_tool", output)
        val savedPath = Regex("""\[El resultado completo se ha guardado en: ([^\]]+)]""")
            .find(result)
            ?.groupValues
            ?.get(1)

        assertTrue(savedPath != null)
        assertEquals(output, File(savedPath!!).readText())
    }

    /** alto/Los caracteres sustitutos bajos deben aparecer en pares; uno aislado se dividirá durante la serialización JSON descendente. */
    private fun hasUnpairedSurrogate(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return true
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                return true
            } else {
                i++
            }
        }
        return false
    }
}
