package io.zer0.muse.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * todos `registerReceiver(...)` Todas las llamadas deben declarar explícitamente la bandera de exportación.
 *
 * Antecedentes:`ContextCompat.registerReceiver` en flags **No contiene RECEIVER_EXPORTED ni
 * RECEIVER_NOT_EXPORTED** tirar incondicionalmente `IllegalArgumentException`, independientemente de la versión del sistema.
 * Botón de realización histórica `Build.VERSION.SDK_INT >= TIRAMISU` decidió pasar `RECEIVER_EXPORTED` Aún así `0`,
 * Por lo tanto, los dispositivos con Android 12 y versiones inferiores fallan tan pronto como se registran (vivo V1990A / Android 10 Medición real).
 * Este tipo de regresión no se puede ver durante la compilación. Solo explota cuando la máquina real se ejecuta en esa ruta, por lo que se utiliza el escaneo del código fuente para fijarlo.
 *
 * Método de evaluación: para cada pulsador, presione el equilibrio entre corchetes para recuperar la lista completa de parámetros reales (esto también es válido para llamadas de varias líneas), que deben aparecer en la lista de parámetros reales.
 * `RECEIVER_EXPORTED` o `RECEIVER_NOT_EXPORTED`;También se permite el método de escritura de asignar primero la bandera a una variable local y luego pasarla.
 * (Se encontrará en el mismo archivo `val/var <name> =` (Miren los valores juntos). Si la bandera se convierte en un parámetro de función,
 * Para constantes entre archivos y otras formas que no son reconocidas por esta prueba, la prueba fallará y requerirá confirmación manual.——Esto es deliberado,
 * Es mejor informar una vez que perder silenciosamente un receptor que fallará en sistemas más antiguos.
 *
 * Los comentarios y los literales de cadena se reemplazan con espacios antes de escanear (manteniendo nuevas líneas), como se menciona en KDoc
 * `registerReceiver` No será tratado como un punto de llamada.
 */
class RegisterReceiverFlagTest {

    @Test
    fun `every registerReceiver call declares an exported flag`() {
        val files = sourceRoots().flatMap { root -> kotlinSources(root) }
        assertTrue("No se escanearon archivos .kt", files.isNotEmpty())

        val sources = files.map { file ->
            relativeName(file) to Files.newBufferedReader(file.toPath()).use { it.readText() }
        }
        val violations = registerReceiverViolations(sources)
        val callCount = sources.sumOf { (_, text) -> countReceiveCalls(text) }

        assertTrue(
            "La siguiente llamada a RegisterReceiver no pasa RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED," +
                "ContextCompat Lanzará IllegalArgumentException directamente (fallará en sistemas antiguos):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
        // En pocas palabras: confirme que se hayan escaneado dos puntos de llamada conocidos para evitar caminos/Los fallos frecuentes provocan un funcionamiento en vacío.
        assertTrue("Sólo escaneado $callCount En la llamada de RegisterReceiver, se sospecha que el análisis no es válido.", callCount >= 2)
        listOf(
            "terminal${File.separator}TermuxChannel.kt",
            "schedule${File.separator}ScheduledTaskRunner.kt",
        ).forEach { suffix ->
            assertTrue(
                "No encontrado en los resultados del escaneo $suffix, lo que indica que la ruta raíz del código fuente no se encuentra correctamente:" + sources.map { it.first },
                sources.any { (name, _) -> name.endsWith(suffix) },
            )
        }
    }

    /** Autocomprobación: el escáner realmente debe poder detectar llamadas a las que les faltan indicadores; de lo contrario, la afirmación anterior puede estar inactiva. */
    @Test
    fun `scan reports a call that omits the export flag`() {
        val violating = """
            class Sample {
                fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
                    val flags = 0
                    ContextCompat.registerReceiver(context, receiver, filter, flags)
                    context.registerReceiver(receiver, filter)
                }
            }
        """.trimIndent()

        val violations = registerReceiverViolations(listOf("Sample.kt" to violating))
        assertEquals("Se informan ambas llamadas a las que les faltan indicadores:$violations", 2, violations.size)
        assertTrue("Se debe informar el número de archivo y línea:$violations", violations[0].startsWith("Sample.kt:4 -> "))
        assertTrue("Se debe informar el número de archivo y línea:$violations", violations[1].startsWith("Sample.kt:5 -> "))

        val compliant = """
            class Sample {
                fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                    val flags = ContextCompat.RECEIVER_NOT_EXPORTED
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        filter,
                        flags,
                    )
                    // ContextCompat.registerReceiver(context, receiver, filter, 0) Los comentarios no cuentan.
                    Log.d("tag", "registerReceiver(ctx, r, filter)")
                }
            }
        """.trimIndent()

        assertEquals(
            "No se deben informar comentarios, cadenas literales ni llamadas marcadas:${registerReceiverViolations(listOf("Sample.kt" to compliant))}",
            emptyList<String>(),
            registerReceiverViolations(listOf("Sample.kt" to compliant)),
        )
    }

    /** Encuentre el punto de llamada al que le falta la bandera de exportación archivo por archivo y regrese `nombre de archivo:Número de línea -> llamar texto`. */
    private fun registerReceiverViolations(sources: List<Pair<String, String>>): List<String> {
        val violations = mutableListOf<String>()
        sources.forEach { (name, raw) ->
            val scan = maskCommentsAndStrings(raw)
            CALL_SITE.findAll(scan).forEach { match ->
                val call = balancedCall(scan, match.range.last)
                if (!declaresExportFlag(call, raw)) {
                    violations += "$name:${lineOf(scan, match.range.first)} -> ${oneLine(call)}"
                }
            }
        }
        return violations
    }

    private fun countReceiveCalls(raw: String): Int = CALL_SITE.findAll(maskCommentsAndStrings(raw)).count()

    /** Si el indicador de exportación se declara en la tabla de parámetros real; apoyado"Asignar primero a variables locales y luego pasar". */
    private fun declaresExportFlag(call: String, rawSource: String): Boolean {
        if (call.contains(EXPORTED) || call.contains(NOT_EXPORTED)) return true
        // Ruta degenerada: aparece una variable local en la llamada y su declaración rvalue en este archivo está marcada.
        return IDENTIFIER.findAll(call).map { it.value }.any { name ->
            val decl = declaredValue(name).find(rawSource) ?: return@any false
            val value = rawSource.substring(decl.range.last + 1).take(DECLARATION_WINDOW)
            value.contains(EXPORTED) || value.contains(NOT_EXPORTED)
        }
    }

    /** de `(` De la posición se extrae el texto completo de la llamada equilibrando los paréntesis. */
    private fun balancedCall(source: String, openIndex: Int): String {
        var depth = 0
        for (i in openIndex until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openIndex, i + 1)
                }
            }
        }
        return source.substring(openIndex)
    }

    private fun lineOf(source: String, index: Int): Int = source.take(index).count { it == '\n' } + 1

    private fun oneLine(call: String): String =
        call.replace(WHITESPACE, " ").let { if (it.length > 160) it.take(160) + "..." else it }

    /**
     * Líneas de comentarios y bloques (Kotlin Nestable), reemplace cadenas y caracteres literales con espacios, conserve líneas nuevas y longitudes,
     * De esta manera, los números de línea posteriores y las posiciones de los corchetes seguirán correspondiendo al archivo original.
     */
    private fun maskCommentsAndStrings(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        while (i < source.length) {
            i = when {
                source.startsWith("//", i) -> maskLineComment(source, out, i)
                source.startsWith("/*", i) -> maskBlockComment(source, out, i)
                source.startsWith("\"\"\"", i) -> maskRawString(source, out, i)
                source[i] == '"' || source[i] == '\'' -> maskQuoted(source, out, i)
                else -> i + 1
            }
        }
        return out.toString()
    }

    private fun maskLineComment(source: String, out: StringBuilder, from: Int): Int {
        var i = from
        while (i < source.length && source[i] != '\n') {
            out.setCharAt(i, ' ')
            i++
        }
        return i
    }

    private fun maskBlockComment(source: String, out: StringBuilder, from: Int): Int {
        var i = from
        var depth = 0
        while (i < source.length) {
            if (source.startsWith("/*", i)) {
                depth++
                out.setCharAt(i, ' ')
                out.setCharAt(i + 1, ' ')
                i += 2
            } else if (source.startsWith("*/", i)) {
                depth--
                out.setCharAt(i, ' ')
                out.setCharAt(i + 1, ' ')
                i += 2
                if (depth == 0) return i
            } else {
                if (source[i] != '\n') out.setCharAt(i, ' ')
                i++
            }
        }
        return i
    }

    private fun maskRawString(source: String, out: StringBuilder, from: Int): Int {
        var i = from + 3
        for (k in from until i) out.setCharAt(k, ' ')
        while (i < source.length && !source.startsWith("\"\"\"", i)) {
            if (source[i] != '\n') out.setCharAt(i, ' ')
            i++
        }
        val end = minOf(i + 3, source.length)
        for (k in i until end) out.setCharAt(k, ' ')
        return end
    }

    private fun maskQuoted(source: String, out: StringBuilder, from: Int): Int {
        val quote = source[from]
        var i = from + 1
        out.setCharAt(from, ' ')
        while (i < source.length && source[i] != quote) {
            if (source[i] == '\\' && i + 1 < source.length) {
                out.setCharAt(i, ' ')
                out.setCharAt(i + 1, ' ')
                i += 2
            } else {
                if (source[i] != '\n') out.setCharAt(i, ' ')
                i++
            }
        }
        if (i < source.length) {
            out.setCharAt(i, ' ')
            i++
        }
        return i
    }

    private fun sourceRoots(): List<File> =
        listOf("app/src/main/java", "src/main/java")
            .map(::File)
            .filter(File::isDirectory)
            .ifEmpty { listOfNotNull(File("../app/src/main/java").takeIf(File::isDirectory)) }

    private fun kotlinSources(root: File): List<File> =
        // Utilice java.io para recorrer: la prueba única se ejecuta en android.jar para evitar depender de la API del directorio NIO que no ha sido burlada.
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    private fun relativeName(file: File): String =
        file.path.replace('/', File.separatorChar).let { path ->
            val marker = "src${File.separator}main${File.separator}java${File.separator}"
            if (path.contains(marker)) path.substringAfter(marker) else file.name
        }

    private companion object {
        const val EXPORTED = "RECEIVER_EXPORTED"
        const val NOT_EXPORTED = "RECEIVER_NOT_EXPORTED"
        const val DECLARATION_WINDOW = 400

        val CALL_SITE = Regex("""\bregisterReceiver\s*\(""")
        val IDENTIFIER = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
        val WHITESPACE = Regex("""\s+""")

        fun declaredValue(name: String): Regex =
            Regex("""\b(?:val|var)\s+${Regex.escape(name)}\b[^=\n]*=""")
    }
}
