package io.zer0.muse.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Reader
import java.util.UUID

/**
 * Fuente de salida de la clase de comando: mantenga la cola al truncar.
 *
 * El criterio es la semántica ("La conclusión está en las últimas líneas.": Errores de compilación, fallas de prueba, códigos de salida), no volumen de salida.
 * Ya hay nombres de herramientas en la colección (terminal_exec / execute_shell / termux_exec / device_shell),
 * También hay prefijos cuando la herramienta recopila internamente flujos de salida (python_stdout / node_stdout / shizuku_command):
 * Los dos comparten una tabla para evitar que el mismo canal de comando juzgue la dirección una vez en la capa de adquisición y la capa de orquestación.
 */
private val TAIL_KEEPING_OUTPUT_SOURCES = setOf(
    "terminal_exec",
    "execute_shell",
    "termux_exec",
    "device_shell",
    "shizuku_command",
    "python_stdout",
    "python_stderr",
    "node_stdout",
    "node_stderr",
    // La salida estándar/stderr ejecutada por el script tiene la semántica final cubierta por el prefijo anterior; aquí hay otra capa basada en el nombre de la herramienta,
    // Esto es para que cuando se reescriba la ruta de adquisición o se utilice otro método de llamada en el futuro, la dirección no degenere en la cabeza. La redundancia es inofensiva, pero la omisión es perjudicial.
    "execute_python_script",
    "execute_node_script",
)

/** Si la fuente de salida trunca la vista previa para mantener la cola (clase de comando) o el encabezado (archivo, clase de resultado de búsqueda). */
internal fun keepsOutputTail(sourceName: String?): Boolean =
    sourceName != null && sourceName.trim().lowercase() in TAIL_KEEPING_OUTPUT_SOURCES

/**
 * Keeps short command output inline and spills larger output to a file that read_file can page.
 *
 * El segmento que se retiene cuando se trunca la salida está determinado por [keepsOutputTail] Determinado por la semántica de la herramienta: dejar la cola de la salida del comando,
 * Los archivos y los resultados de la búsqueda se dejan en el encabezado. El umbral permanece sin cambios, solo se toma el segmento.
 */
internal class ToolOutputCapture(
    private val outputDirectory: File,
    filePrefix: String,
    private val inlineLimitChars: Int = TOOL_OUTPUT_INLINE_FALLBACK_CHARS,
    private val previewLimitChars: Int = TOOL_RESULT_PREVIEW_CHARS,
    private val keepTail: Boolean = keepsOutputTail(filePrefix),
) : Closeable {
    private val fileName = "${filePrefix.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${UUID.randomUUID()}.txt"
    private val inlineContent = StringBuilder()
    private val headPreview = StringBuilder()

    /** Ventana de desplazamiento para vista previa de la cola (solo en [keepTail] ): conserva solo el último [previewLimitChars] palabras. */
    private val tailPreview = StringBuilder()
    private var totalChars = 0L
    private var outputFile: File? = null
    private var writer: BufferedWriter? = null
    private var completed = false

    val savedFile: File? get() = outputFile

    init {
        require(inlineLimitChars >= 0)
        require(previewLimitChars >= 0)
    }

    fun append(text: CharSequence) {
        check(!completed) { "Tool output capture is already complete" }
        if (text.isEmpty()) return
        appendPreview(text)
        totalChars += text.length

        if (writer == null && inlineContent.length + text.length <= inlineLimitChars) {
            inlineContent.append(text)
            return
        }
        if (writer == null) startFile()
        writer?.append(text) ?: error("Tool output file writer was not initialized")
    }

    fun finish(header: String, emptyMessage: String): String {
        check(!completed) { "Tool output capture is already complete" }
        completed = true
        writer?.close()
        writer = null

        val file = outputFile ?: return buildString {
            if (header.isNotBlank()) {
                append(header)
                if (inlineContent.isNotEmpty()) append('\n')
            }
            append(inlineContent.ifEmpty { StringBuilder(emptyMessage) })
        }
        val mountPath = "$TOOL_OUTPUTS_DIR:/$fileName"
        // Primero obtenga la vista previa renderizada y luego informe su longitud real: ambas provienen de la misma cadena.
        // No habrá ningún error de "mostrar más que mensajes"/Desalineación "falta una palabra".
        val previewText = renderPreview()
        return buildString {
            if (header.isNotBlank()) append(header).append('\n')
            append("[El resultado completo se ha guardado en: ").append(file.absolutePath).append("]\n")
            append(
                "[Utilice workspace_read para leer segmentadamente cuando se requiera contenido completo.: path=$mountPath, " +
                    "offset_chars=0, length_chars=$TOOL_OUTPUT_READ_PAGE_CHARS;También puedes usar read_file para leer (path=$TOOL_OUTPUTS_DIR/$fileName)]\n\n",
            )
            append(previewText)
            append("\n\n")
            append(previewNotice(previewText.length))
        }
    }

    /**
     * Obtenga una vista previa del texto sin dejar medio par sustituto.
     *
     * El modo de cola descarta el agente bajo aislado al principio y el modo principal descarta el agente alto aislado al final:
     * En ambos casos, solo aparecerá uno en el límite de truncamiento. Después de descartar [previewNotice] Recuento según la longitud real.
     */
    private fun renderPreview(): String {
        if (!keepTail) {
            while (headPreview.isNotEmpty() && Character.isHighSurrogate(headPreview[headPreview.length - 1])) {
                headPreview.setLength(headPreview.length - 1)
            }
            return headPreview.toString()
        }
        while (tailPreview.isNotEmpty() && Character.isLowSurrogate(tailPreview[0])) {
            tailPreview.deleteCharAt(0)
        }
        return tailPreview.toString()
    }

    /** Consejo de paginación: indique de qué extremo se toma la vista previa, la cantidad total del texto original y la cantidad de caracteres no mostrados, para que el modelo pueda determinar si continúa leyendo. */
    private fun previewNotice(shownChars: Int): String {
        val omitted = (totalChars - shownChars).coerceAtLeast(0L)
        return if (keepTail) {
            "[Lo anterior es el final de la salida. $shownChars Vista previa de palabras; total del texto original $totalChars palabra, comienzo $omitted Las palabras no se muestran;" +
                "Utilice read_file si es necesario desde offset_chars=0 leer]"
        } else {
            "[Lo anterior es el comienzo de la salida. $shownChars Vista previa de palabras; total del texto original $totalChars palabras, el resto $omitted Las palabras no se muestran;" +
                "Utilice read_file para continuar leyendo mediante el cursor del carácter si es necesario]"
        }
    }

    override fun close() {
        if (completed) return
        completed = true
        writer?.close()
        writer = null
    }

    private fun appendPreview(text: CharSequence) {
        if (!keepTail) {
            val remaining = previewLimitChars - headPreview.length
            if (remaining > 0) headPreview.append(text, 0, minOf(text.length, remaining))
            return
        }
        tailPreview.append(text)
        // Si se excede el límite superior, el primer párrafo se descarta: solo las últimas líneas del resultado del comando tienen significado.
        val overflow = tailPreview.length - previewLimitChars
        if (overflow > 0) tailPreview.delete(0, overflow)
    }

    private fun startFile() {
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs() && !outputDirectory.isDirectory) {
            throw IOException("No se puede crear el directorio de salida de la herramienta: ${outputDirectory.absolutePath}")
        }
        val file = File(outputDirectory, fileName)
        val newWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
        try {
            newWriter.append(inlineContent)
            inlineContent.setLength(0)
            outputFile = file
            writer = newWriter
        } catch (error: Throwable) {
            runCatching { newWriter.close() }
            runCatching { file.delete() }
            throw error
        }
    }
}

internal data class ToolTextStreamCaptureResult(
    val preview: String,
    val reference: String,
    val savedFile: File?,
    val totalChars: Long,
)

internal fun captureTextStream(
    reader: Reader,
    outputDirectory: File,
    filePrefix: String,
    previewLimitChars: Int,
): ToolTextStreamCaptureResult {
    require(previewLimitChars >= 0)
    val capture = ToolOutputCapture(outputDirectory, filePrefix)
    // aquí está"Para análisis"vista previa del prefijo (HTML análisis, muestreo del primer párrafo), por lo que siempre tome el comienzo;
    // La vista previa paginada del modelo la genera capture.finish en la dirección de la fuente de salida.
    val preview = StringBuilder(minOf(previewLimitChars, TOOL_RESULT_PREVIEW_CHARS))
    var totalChars = 0L
    reader.use {
        val buffer = CharArray(8_192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            val chunk = String(buffer, 0, count)
            capture.append(chunk)
            totalChars += count
            val remaining = previewLimitChars - preview.length
            if (remaining > 0) preview.append(chunk, 0, minOf(chunk.length, remaining))
        }
    }
    val reference = capture.finish(header = "", emptyMessage = "")
    return ToolTextStreamCaptureResult(preview.toString(), reference, capture.savedFile, totalChars)
}

internal suspend fun captureLargeToolOutput(context: Context, filePrefix: String, output: String): String {
    if (output.length <= TOOL_OUTPUT_INLINE_FALLBACK_CHARS) return output
    return try {
        withContext(Dispatchers.IO) {
            ToolOutputCapture(File(context.filesDir, TOOL_OUTPUTS_DIR), filePrefix).apply {
                append(output)
            }.finish(header = "", emptyMessage = "")
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        output + "\n\n[La salida completa falló; el texto original completo de este mensaje permanece: ${error.message ?: "Error de almacenamiento"}]"
    }
}
