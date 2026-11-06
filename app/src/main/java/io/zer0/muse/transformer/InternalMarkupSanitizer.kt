package io.zer0.muse.transformer

import io.zer0.muse.perf.BoundedLruCache

/**
 * Limpiador de marcas internas invisible para el usuario.
 *
 * Modelos a veces mood / mod / think / reflection Escriba directamente en el contenido, especialmente
 * Cuando se trunca un modelo de ensayo o una salida de streaming. Los campos internos son los encargados de llevar este contenido, cuerpo, vista previa de la sesión y
 * La generación de títulos solo puede usar texto después de este limpiador.
 *
 * ## v2.6.8: exportación unificada
 *
 * aquí al mismo tiempo `card_state` Autoridad de limpieza de colas. Anteriormente, card_state solo tenía "un punto de escritura y un punto de extracción".
 * (Bubble Rendering)", y más de otros diez lectores de texto originales (copiados/Compartir/adelante/Editar borrador/Cotización/mapa de mensajes/Colección/Exportar/
 * Vista previa de la sesión/FTS Index) todos leen el contenido directamente, por lo que JSON se filtra al usuario y también puede accederse a la búsqueda de texto completo.
 * Ahora sólo hay tres salidas al mundo exterior con una semántica clara. La persona que llama puede elegir uno según el propósito y ya no realiza la separación por separado:
 *
 *  - [displayContent]     Revelar: retire el bloque de marcador interno + la cola del estado de la tarjeta, converja el espacio en blanco.
 *  - [exportContent]      Copiar/Compartir/Exportar/Lea en voz alta: retire la etiqueta de efectos especiales en línea en la apertura de la pantalla.
 *  - [searchableContent]  Recuperación: retire solo la parte posterior del estado de la tarjeta y conserve el texto del índice existente, como estado de ánimo/pensamiento.
 *
 * [stripForDisplay] Es la implementación subyacente del calibre de visualización (la cola del estado de la tarjeta se procesa al mismo nivel que el estado de ánimo/pensamiento),
 * Puede continuar usándolo para puntos de llamada históricos como bibliotecas de transmisión, canales de inferencia, generación de títulos, etc., y no se perderá card_state.
 */
object InternalMarkupSanitizer {
    /**
     * v2.6.8: Las marcas de inicio y fin del segmento de estado de la tarjeta (la única definición autorizada).
     *
     * Envuélvalo con U+2063 SEPARADOR INVISIBLE para reducir la probabilidad de colisión con el texto normal; escribir vista lateral
     * `CardAction.appendCardStateToMessage`, el formato permanece sin cambios para que sea compatible con los mensajes históricos que se han incluido en la base de datos.
     */
    const val CARD_STATE_OPEN: String = "\u2063[card_state]\u2063"
    const val CARD_STATE_CLOSE: String = "\u2063[/card_state]\u2063"

    /**
     * Sondeo económico del estado de la tarjeta.
     *
     * Intencionalmente no contiene caracteres invisibles:contains Es un escaneo único, por lo que solo es necesario cortocircuitar con anticipación los escaneos regulares.
     * Cualquier juicio sobre "si hay un estado de tarjeta en este texto" pasará este nivel primero.
     */
    private const val CARD_STATE_PROBE: String = "[card_state]"

    /** v2.6.7 Rendimiento: La expresión regular de convergencia en blanco se extrae como constantes a nivel de archivo, lo que evita la necesidad de crear una nueva expresión regular cada vez que se llama. */
    private val whitespaceRun = Regex("\\n{3,}")

    private val closedInternalBlock = Regex(
        "(?is)(?:<(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[(?:(?:mood|mod|think|thinking|reflection|moodfx))\\])" +
            "[\\s\\S]*?" +
            "(?:</(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[/(?:(?:mood|mod|think|thinking|reflection|moodfx))\\])",
    )
    private val unclosedInternalStart = Regex(
        "(?is)(?:<(?:(?:mood|mod|think|thinking|reflection|moodfx))>|\\[(?:(?:mood|mod|think|thinking|reflection|moodfx))\\]).*$",
    )
    private val moodBlock = Regex(
        "(?is)(?:<(?:mood|mod)>|\\[(?:mood|mod)\\])([\\s\\S]*?)(?:</(?:mood|mod)>|\\[/(?:mood|mod)\\])",
    )

    /**
     * v1.0.90: Detección de etiquetas de nombre de etiqueta (incluidas medias etiquetas y etiquetas cerradas aisladas).
     *
     * Resulta que la persona que llama sólo detecta `<mood>` Con este tipo de apertura completa de la etiqueta, el modelo escupe media etiqueta (`<mood`) o simplemente cerrar la etiqueta
     * (`</mood>`) se mostrará directamente en la pantalla como texto normal y el usuario verá el texto original de la etiqueta de estado de ánimo.
     * Cualquier contenido que alcance esta marca debe pasar por el proceso de limpieza completo.
     */
    val TAG_MARKER_REGEX =
        Regex("(?i)[<\\[]/?\\s*(?:mood|mod|think|thinking|reflection|moodfx)")

    /** Solo se retira la cubierta interior de la etiqueta, dejando el contenido del pensamiento en el interior para que lo utilice el panel de razonamiento. */
    fun stripContainerTags(text: String): String = text
        .replace(Regex("(?i)</?(?:mood|mod|think|thinking|reflection|moodfx)>"), "")
        .replace(Regex("(?i)\\[/?(?:mood|mod|think|thinking|reflection|moodfx)\\]"), "")
        .trim()

    /** Eliminar el bloque completo de estado de ánimo/mod del pase de inferencia,Pero mantén el resto del contenido pensado en el interior. */
    fun stripMoodBlocks(text: String): String = moodBlock.replace(text, "").trim()

    /** v2.6.8: Si hay una cola de estado de la tarjeta en el texto (contiene barato, sin regularidad). */
    fun containsCardState(text: String): Boolean = text.contains(CARD_STATE_PROBE)

    /**
     * v2.6.8: Retire la cola del estado de la tarjeta.
     *
     * Cuando no hay marca**Devuelve la misma instancia sin cambios**(sin recortes, sin reconstrucción), por lo que se puede colocar de forma segura en el camino caliente.
     * Si está marcado, retire toda la sección y elimine el espacio en blanco final. —— La cola se añade después del texto principal. `\n[card_state]…`,
     * Dejar el salto de línea en su lugar le dará a la burbuja una línea adicional de espacio en blanco. Cuando no está cerrado (sólo medio escrito), se trunca de la marca.
     * De acuerdo con las implementaciones históricas, es mejor perder el estado que filtrar JSON a los usuarios.
     */
    fun stripCardState(text: String): String {
        if (!containsCardState(text)) return text
        var result = text
        var stripped = false
        // Pase cada pieza de la cola (en teoría, solo hay una pieza, pero las cartas se apilarán cuando se pasen de regreso continuamente).
        var start = result.indexOf(CARD_STATE_OPEN)
        while (start >= 0) {
            val end = result.indexOf(CARD_STATE_CLOSE, start)
            if (end < 0) {
                // La cola está medio escrita (colapso/Truncar): truncar desde la marca, en lugar de perder el estado sin filtrar JSON.
                result = result.substring(0, start)
                stripped = true
                start = -1
            } else {
                result = result.removeRange(start, end + CARD_STATE_CLOSE.length)
                stripped = true
                start = result.indexOf(CARD_STATE_OPEN)
            }
        }
        // La cola se añade después del texto principal. `\n[card_state]…`, quedará un salto de línea final después de despegarlo; solo terminará cuando se despegue.
        return if (stripped) result.trimEnd() else result
    }

    /**
     * v2.6.8 Salida 1: El texto se puede mostrar directamente al usuario.
     *
     * equivalente a [stripForDisplay](Las colas del estado de la tarjeta ya se manejan al mismo nivel que el estado de ánimo/pensamiento allí).
     * Dejar un nombre aparte es para que cuando se lea el pulsador se vea que "este es el calibre para los usuarios".
     * También hace que sea más fácil evitar tener que cambiar docenas de llamadas cuando las dos diverjan en el futuro.
     *
     * Los resultados se almacenan en caché por el propio texto (ver [DisplayContentCache]): filtrado y recuento de listas de mensajes,
     * El juicio de agrupación solicitará el mismo mensaje en la misma actualización 3–4 veces, tomarlo una vez es suficiente.
     * salida con [stripForDisplay] Consistente byte a byte, el caché solo guarda cálculos repetidos y no cambia el calibre.
     */
    fun displayContent(text: String): String = displayContentCache.getOrCompute(text, ::stripForDisplay)

    /**
     * v2.6.8 Salida 2: Copiar/compartir/exportar/leer el texto en voz alta.
     *
     * en [displayContent] Básicamente límpialo `[glow]…[/glow]` Este tipo de etiqueta de efectos en línea (conserva el texto ajustado).
     * La única diferencia con el calibre de pantalla es este caparazón de efectos especiales. No debe haber marcas de protocolo en el texto que reciben los usuarios.
     */
    fun exportContent(text: String): String = MoodSkinParser.stripInlineEffects(displayContent(text)).trim()

    /**
     * v2.6.8 Salida 3: Buscar índice/recuperar texto para comparar.
     *
     * Retire solo la cola del estado de la tarjeta,**sin moverse** mood/think etc. dentro de bloques, ni converger a espacios en blanco:
     *  - El estado de la tarjeta es un campo de máquina (cardId,JSON valor clave), los usuarios no deben buscarlo ni aparecer en el fragmento de resultado;
     *  - La semántica que los usuarios pueden buscar ya está en el resumen de texto (la oración cuando se devuelve la tarjeta), y el estado no se perderá sin perder la recuperación;
     *  - mood/think Indexar o no es otra decisión. Esta vez no lo cambiaremos para evitar una deriva silenciosa del comportamiento de búsqueda.
     */
    fun searchableContent(text: String): String = stripCardState(text)

    /** Devuelve el texto del cuerpo (bloque de marcado interno + cola de estado de la tarjeta) que se puede mostrar directamente al usuario. */
    fun stripForDisplay(text: String): String {
        // v2.6.7 Rendimiento: Camino rápido. Todas las etiquetas internas comienzan con '<' o '[' al principio,
        // Cuando ninguno de ellos está incluido, la marca regular definitivamente no dará en el blanco y sólo se requiere la convergencia en blanco.
        // Este método se llama N veces por cuadro en el filtrado de la lista de mensajes (N = número de mensajes), es el verdadero punto caliente del hilo principal.
        // v2.6.8: card_state etiquetado con '[' Al principio, el texto que lo contiene debe tomar el camino lento. ——
        // Por lo tanto, no se necesitan sondas adicionales en el camino rápido, la extracción se coloca en el camino lento y el costo del camino caliente permanece sin cambios.
        val hasMarkup = text.contains('<') || text.contains('[')
        val result = if (text.isBlank() || !hasMarkup) {
            text
        } else {
            stripMarkupBlocks(stripCardState(text))
        }
        return whitespaceRun.replace(result, "\n\n").trim()
    }

    /** Ruta lenta: ejecute un lavado de marca completa. Sólo si el texto contiene '<' o '[' cuando lo llaman. */
    private fun stripMarkupBlocks(text: String): String {
        var result = closedInternalBlock.replace(text, "")
        // Los bloques internos no cerrados se cortan de la etiqueta para evitar que el contenido del reflejo se muestre como texto.
        result = unclosedInternalStart.replace(result, "")
        // v1.0.90: Los dos elementos anteriores sólo tratan del caso de etiquetas abiertas. Sólo escupe media etiqueta (`<mood`) o una etiqueta de cierre aislada
        // (`</mood>`,`</think>`) se filtrará al texto principal tal como está. Aquí, escanee la cubierta de la etiqueta nuevamente.
        result = stripContainerTags(result)
        return result
    }

    /** Extraiga únicamente contenido de estado de ánimo/mod, compatible con mensajes históricos y hábitos de etiquetado de diferentes modelos. */
    fun extractMood(text: String): String? = moodBlock.findAll(text)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
        .ifBlank { null }
}

/** [DisplayContentCache] El límite superior del número de mensajes: aproximadamente igual a \"dos pantallas de mensajes + un párrafo antes y después\". */
internal const val DISPLAY_CONTENT_CACHE_MAX_ENTRIES: Int = 512

/**
 * El texto que supere este número de caracteres simplemente no se guarda.
 *
 * El tamaño de un único resultado aumenta linealmente con el texto. Unos pocos mensajes de 10.000 palabras pueden exprimir la LRU y, de hecho, reducir la tasa de aciertos;
 * Hay muy pocos mensajes tan largos en la lista y el costo de volver a calcularlos es aceptable.
 */
internal const val DISPLAY_CONTENT_CACHE_MAX_CHARS: Int = 8_000

/**
 * `displayContent` Caché acotado de resultados.
 *
 * key Utilice el texto mismo directamente en lugar de "ID de mensaje + hash de contenido": el resultado de la limpieza solo está determinado por el texto.
 * Utilice el texto como clave para que "la misma entrada debe obtener la misma salida" no dependa del hash y no colisione. —— La comparación de visitas primero mira la referencia,
 * Volviendo a mirar el contenido, se transmite el mismo mensaje en el mismo cuadro. [String] Por ejemplo, la comparación comienza en tiempo constante.
 *
 * Eliminación: la capa inferior es LRU de orden de acceso ([BoundedLruCache]), más allá [maxEntries]
 * El extremo que lleva más tiempo sin utilizarse se elimina cada cierto tiempo. La capacidad es constante y no aumentará con el número total de mensajes.
 *
 * Seguridad de subprocesos: los contenedores se bloquean operación por operación. Las llamadas de renderizado están en el hilo principal, pero se exportan/No se garantiza que el lado del índice sea de un solo subproceso,
 * Por lo tanto, aquí no se supone que haya una sola persona que llama.
 */
internal class DisplayContentCache(
    maxEntries: Int = DISPLAY_CONTENT_CACHE_MAX_ENTRIES,
) {
    private val entries = BoundedLruCache<String, String>(maxEntries) { 1 }

    /** Si el acierto tiene éxito, se devolverá directamente el último resultado. Si el resultado no es acertado, se llamará. [compute] y escribe los resultados. */
    fun getOrCompute(text: String, compute: (String) -> String): String {
        entries.get(text)?.let { return it }
        val computed = compute(text)
        if (text.length <= DISPLAY_CONTENT_CACHE_MAX_CHARS) entries.put(text, computed)
        return computed
    }

    fun containsKey(text: String): Boolean = entries.containsKey(text)

    fun size(): Int = entries.size()

    fun clear() = entries.clear()
}

/** Ejemplos a nivel de proceso: el valor del caché radica en la reorganización cruzada y la reutilización entre marcos.per-call Construir uno no significa nada. */
private val displayContentCache = DisplayContentCache()
