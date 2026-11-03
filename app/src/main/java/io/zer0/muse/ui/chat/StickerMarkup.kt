package io.zer0.muse.ui.chat

/**
 * v2.x: Analizador de etiquetas de memes.
 *
 * La modelo escribe en el cuerpo de la respuesta. `[[sticker:Nombre de categoría]]`,La capa de renderizado la reemplaza con una tarjeta con imagen de emoticono.;
 * El modelo no puede ver el contenido de la imagen.,Solo presione el nombre de la carpeta establecido cuando el usuario importe(Clasificación)Seleccionar.
 *
 * Responsabilidades:
 *  - [findMarkers]:Encuentra todas las etiquetas completas(saltar ``` valla de código,Evite ejemplos de código accidentales)
 *  - [findOpenMarkerStart]:Detectar marca de cola abierta(La mitad del texto se traga durante la renderización en streaming,No expuesto `[[sticker:Ninguno`)
 *  - [split]:Cortar el texto en"Segmento de texto + segmento de emoticones"Para renderizar capa para renderizar segmento por segmento
 *
 * estrategia de tolerancia a fallos:La persona que llama debe ignorar esta etiqueta cuando falla la coincidencia del nombre de la categoría.(Preferiría tener menos pelo,No te ensucies el pelo);
 * Las marcas no cerradas se tragarán y no se mostrarán.(El modelo a veces falla/Se degrada elegantemente cuando se trunca).
 */
object StickerMarkup {
    private const val OPEN = "[["
    private const val PREFIX = "sticker:"

    /** marcado completo:[[sticker:Nombre de categoría]](Nombre de categoría 1-32 caracteres,sin corchetes/nueva línea). */
    private val MARKER_REGEX =
        Regex(
            """\[\[\s*sticker\s*:\s*([^\[\]\n]{1,32}?)\s*\]\]""",
            RegexOption.IGNORE_CASE,
        )

    /** Ventana de tolerancia a fallos marcada y abierta:[[ Aún no hay cierre tras superar esta longitud.,tratado como texto normal. */
    private const val OPEN_MARKER_MAX_TAIL = 40

    /** La posición de una etiqueta completa dentro del texto principal.(start Contiene,end no contiene). */
    data class Marker(val start: Int, val end: Int, val category: String)

    /** Segmentos de renderizado:Segmento de texto ordinario o segmento de emoticonos. */
    sealed interface Segment {
        data class Text(val text: String) : Segment

        data class Sticker(val category: String) : Segment
    }

    /**
     * Encuentra todas las etiquetas completas(en orden de aparición).
     *
     * saltar ``` líneas dentro de la valla del código——en el ejemplo de código `[[sticker:...]]` Los literales no se deben convertir.
     */
    fun findMarkers(text: String): List<Marker> {
        if (!text.contains(OPEN)) return emptyList()
        val result = mutableListOf<Marker>()
        var offset = 0
        var inFence = false
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
            } else if (!inFence) {
                for (m in MARKER_REGEX.findAll(line)) {
                    val category = m.groupValues[1].trim()
                    if (category.isNotEmpty()) {
                        result.add(
                            Marker(
                                start = offset + m.range.first,
                                end = offset + m.range.last + 1,
                                category = category,
                            ),
                        )
                    }
                }
            }
            offset += line.length + 1 // +1: '\n'
        }
        return result
    }

    /**
     * Detección"etiqueta abierta al final"posición inicial(Truncar desde allí al renderizar,Evite exponer la mitad del cuerpo. `[[sticker:abierto`).
     *
     * Condiciones de juicio:El último del texto. `[[` después(≤[OPEN_MARKER_MAX_TAIL] personaje)No `]]`,
     * Y el contenido de este párrafo se ajusta a `sticker:` La forma de prefijo de(o vacío,Acabo de escribir `[[`).
     * Devuelve -1 si no hay tokens abiertos.
     */
    fun findOpenMarkerStart(text: String): Int {
        var searchEnd = text.length
        while (searchEnd > 1) {
            val idx = text.lastIndexOf(OPEN, searchEnd - 1)
            if (idx < 0) return -1
            val after = text.substring(idx + OPEN.length)
            if (after.length > OPEN_MARKER_MAX_TAIL || after.contains("]]")) {
                // Demasiado largo o cerrado:esto [[ Ni media marca,sigue mirando hacia adelante
                searchEnd = idx
                continue
            }
            val trimmed = after.trimStart().lowercase()
            if (trimmed.isEmpty() || PREFIX.startsWith(trimmed) || trimmed.startsWith(PREFIX)) {
                return idx
            }
            searchEnd = idx
        }
        return -1
    }

    /**
     * Corta el texto en segmentos de renderizado.
     *
     * - corte de marca cerrada [Segment.Sticker](Llevar el nombre de la categoría tal como está.,Coincidencia tolerante a fallos por capa de renderizado);
     * - La marca abierta al final se traga entera.(No mostrar medio texto);
     * - Devuelve un single si no hay etiqueta [Segment.Text](Transferencia directa a la representación original sin gastos adicionales para la persona que llama);
     * - Se recortarán las nuevas líneas en blanco antes y después de las etiquetas y al final de los párrafos.,Evite espacios grandes encima y debajo de la tarjeta de emoticones.
     */
    fun split(text: String): List<Segment> {
        if (!text.contains(OPEN)) return listOf(Segment.Text(text))
        val openStart = findOpenMarkerStart(text)
        val display = if (openStart >= 0) text.substring(0, openStart).trimEnd() else text
        val markers = findMarkers(display)
        if (markers.isEmpty()) return listOf(Segment.Text(display))
        val out = mutableListOf<Segment>()
        var cursor = 0
        for (m in markers) {
            if (m.start > cursor) {
                val t = display.substring(cursor, m.start).trim()
                if (t.isNotBlank()) out.add(Segment.Text(t))
            }
            out.add(Segment.Sticker(m.category))
            cursor = m.end
        }
        if (cursor < display.length) {
            val t = display.substring(cursor).trim()
            if (t.isNotBlank()) out.add(Segment.Text(t))
        }
        return out.ifEmpty { listOf(Segment.Text(display)) }
    }
}
