package io.zer0.muse.common.markdown

/**
 * v1.0.53: Markdown frontmatter analizador(Cubrir la capacidad previa del flujo de trabajo).
 *
 * apoyo `---` Pares clave-valor simples envueltos(No importar la biblioteca YAML,Análisis de escritura a mano):
 * ```
 * ---
 * title: Mis documentos
 * cover: covers/xxx.jpg
 * description: Descripción de una frase
 * ---
 * ```
 *
 * Devuelto cuando falla el análisis null(La persona que llama cambia a renderizado sin metadatos),No afecta la representación del texto.
 * Analizar solo pares clave-valor de mosaicos,Matrices no compatibles/estructura anidada(Ignora este tipo de línea).
 */
data class Frontmatter(
    /** Título del documento. */
    val title: String?,
    /** cita de portada(ruta relativa o file:// URI). */
    val cover: String?,
    /** Descripción de una frase. */
    val description: String?,
    /** valor clave completo(minúscula key → valor original,para una futura expansión). */
    val raw: Map<String, String>,
)

/**
 * frontmatter analizador(función pura,apátrida,Se puede probar individualmente).
 */
object FrontmatterParser {

    private const val DELIMITER = "---"

    /**
     * Analizar la cuestión frontal.
     *
     * @return Analizar resultados;No se devolvió ninguna portada ni formato ilegal. null
     */
    fun parse(markdown: String): Frontmatter? {
        val body = stripBom(markdown)
        if (!body.startsWith("$DELIMITER\n") && !body.startsWith("$DELIMITER\r\n")) return null

        // Coloque el final de la primera línea.
        val firstLineEnd = body.indexOf('\n')
        if (firstLineEnd < 0) return null

        // Encuentra el segundo --- OK(Principio de línea --- y ningún otro carácter al final de línea)
        val rest = body.substring(firstLineEnd + 1)
        val lines = rest.split('\n')
        val kv = LinkedHashMap<String, String>()
        var closingIdx = -1

        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.trim() == DELIMITER) {
                closingIdx = idx
                break
            }
            // analizar key: value
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx > 0) {
                val key = trimmed.substring(0, colonIdx).trim().lowercase()
                var value = trimmed.substring(colonIdx + 1).trim()
                // Eliminar las primeras y últimas comillas coincidentes
                value = stripQuotes(value)
                // fila de valor nulo(Como por ejemplo "tags:" etiqueta de matriz)ignorar,No permitido raw
                if (key.isNotBlank() && value.isNotEmpty()) kv[key] = value
            }
            // Otras lineas(matriz/anidado/Comentario)ignorar
        }

        // El segundo --- falta → Regresar en su totalidad null(análisis no parcial)
        if (closingIdx < 0) return null

        return Frontmatter(
            title = kv["title"]?.takeIf { it.isNotBlank() },
            cover = kv["cover"]?.takeIf { it.isNotBlank() },
            description = kv["description"]?.takeIf { it.isNotBlank() },
            raw = kv,
        )
    }

    /**
     * Volver al texto después de quitar la portada.(Regresar como está cuando no hay ningún frente.).
     */
    fun strip(markdown: String): String {
        val body = stripBom(markdown)
        if (!body.startsWith("$DELIMITER\n") && !body.startsWith("$DELIMITER\r\n")) return markdown

        val firstLineEnd = body.indexOf('\n')
        if (firstLineEnd < 0) return markdown

        val rest = body.substring(firstLineEnd + 1)
        val lines = rest.split('\n')
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.trim() == DELIMITER) {
                // Devuelve el contenido después de la línea de cierre.(Conservar la semántica del prefijo de lista de materiales original:Devolución después de eliminar la lista de materiales,La persona que llama se encarga él mismo)
                return lines.drop(idx + 1).joinToString("\n").trimStart('\n')
            }
        }
        return markdown
    }

    /**
     * Establecer campo de portada y volver a serializar frontmatter;Cree uno nuevo si no hay ningún tema inicial.
     *
     * Se utiliza para reescribir el documento después de la generación de la portada AI.(Phase 3).
     *
     * @param markdown documento original
     * @param coverPath cubrir camino(Sugerencias "covers/xxx.jpg" ruta relativa)
     * @return Completar después de escribir markdown
     */
    fun withCover(markdown: String, coverPath: String): String {
        val existing = parse(markdown)
        if (existing == null) {
            // Ninguno frontmatter: Nuevo,title Extracto del primer # título del texto(Si no, omitir)
            val body = stripBom(markdown)
            val title = body.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")?.trim()
            val sb = StringBuilder()
            sb.appendLine(DELIMITER)
            if (!title.isNullOrBlank()) sb.appendLine("title: \"${escapeValue(title)}\"")
            sb.appendLine("cover: $coverPath")
            sb.appendLine(DELIMITER)
            sb.append(body.trimStart('\n'))
            return sb.toString()
        }

        // Frontmatter existente: reemplazo/Insertar fila de portada
        val body = stripBom(markdown)
        val firstLineEnd = body.indexOf('\n')
        val rest = body.substring(firstLineEnd + 1)
        val lines = rest.split('\n')
        val out = mutableListOf<String>()
        out.add(DELIMITER)
        var inserted = false
        for (line in lines) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.trim() == DELIMITER) {
                if (!inserted) {
                    out.add("cover: $coverPath")
                    inserted = true
                }
                out.add(trimmed)
                break
            }
            val colonIdx = trimmed.indexOf(':')
            val key = if (colonIdx > 0) trimmed.substring(0, colonIdx).trim().lowercase() else ""
            if (key == "cover") {
                out.add("cover: $coverPath")
                inserted = true
            } else {
                out.add(trimmed)
            }
        }
        if (!inserted) out.add("cover: $coverPath") // defensa:Línea de cierre no encontrada
        // Mantener el texto después de cerrar la línea
        return out.joinToString("\n") + "\n" + lines.dropWhile { it.trimEnd('\r').trim() != DELIMITER }.drop(1).joinToString("\n")
    }

    // ── herramientas internas ─────────────────────────────────────────────────────────

    private fun stripBom(text: String): String = if (text.startsWith('\uFEFF')) text.substring(1) else text

    /** Eliminar las primeras y últimas comillas coincidentes("xxx" / 'xxx'),Si no hay coincidencia, se devuelve sin cambios. */
    private fun stripQuotes(value: String): String {
        if (value.length >= 2) {
            val first = value.first()
            val last = value.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length - 1)
            }
        }
        return value
    }

    /** Escape cuando el valor contiene comillas(Se utiliza al responder). */
    private fun escapeValue(value: String): String = value.replace("\"", "\\\"")
}
