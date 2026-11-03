package io.zer0.muse.web

/**
 * Memorizar comandos de solo lectura(`memory.list` / `memory.search`)proyección de carga.
 *
 * ## El alcance de la memoria cubierto por este lote.
 *
 * La memoria se divide en varias capas según su alcance.,Este lote solo hace la capa "grupo de memoria principal/sesión" —— Eso es lo que puedes ver directamente en la página de memoria.
 * esa lista de artículos:
 *  - **memoria de sesión**:Elementos extraídos de conversaciones.(sesión con fuente),`source` Problema
 *    [HostMemorySourceKind.CONVERSATION];
 *  - **grupo de memoria principal**:Elementos bajo el alcance del asistente actual —— El asistente principal es `main`,El sub-asistente es tuyo assistantId,
 *    y páginas de memoria,system prompt La inyección comparte el mismo conjunto de rutas de subbiblioteca(ver [FactHostMemorySource]).
 *
 * ## Este lote no está cubierto.
 *
 *  - **Capa de organización base de hechos**:Borde del gráfico de entidad, lista de contradicciones, sustitución(`supersedes`)/ fusionar(`merged_into`)cadena,
 *    Deep Atomización de capas. estos son"Cómo entender la memoria"Estructura derivada de,La lista es de sólo lectura y no se expande.;
 *  - **Resumen de subbiblioteca entre asistentes**:Una solicitud solo puede leer la biblioteca correspondiente a un alcance.,"Todos los alcances"La unión de se deja para el siguiente lote.;
 *  - **Otras fuentes de memoria**:Resumen de desplazamiento de la sesión(`session_summaries`), producto compilado(`compiled_sections` /
 *    memory.md), base de datos de experiencias, memoria de chat grupal —— No están en esta lista.
 *
 * ## Tamaño de página
 *
 * Las entradas se emiten en orden descendente por id.(autocreciente id ≈ Orden de almacenamiento,Cuanto más grande, más nuevo),`cursor` Obtenga el último elemento en la página anterior id,
 * La página siguiente solo toma la identificación más pequeña. De esta manera la paginación no depende de compensaciones.:Las nuevas entradas solo aparecerán antes de la primera página.,
 * Las siguientes páginas no estarán llenas de repeticiones ni líneas saltadas. Fijelo en la parte superior solo como `pinned` La marca se emite y no participa en la clasificación. ——
 * Una vez fijado pasa a primer plano,keyset La paginación filtrará entradas en los límites de la página.
 */
internal object HostMemoryReads {

    const val TYPE_LIST = "memory.list"
    const val TYPE_SEARCH = "memory.search"

    /** El valor predeterminado del número de elementos en una sola página. */
    const val DEFAULT_LIMIT = 30

    /** Número máximo de entradas en una sola página:No importa cuán grande sea la página, no tiene significado para la página web.,Simplemente insertando un único recibo en unos pocos cientos de KB. */
    const val MAX_LIMIT = 100

    /**
     * El límite superior de caracteres para un solo texto en la lista.
     *
     * La página de lista solo muestra dos o tres líneas.,200 Las palabras son suficientes para juzgar."¿De qué se trata esta nota?";Un hecho compilado puede contener miles de palabras.,
     * Distribuir el párrafo completo aumentará el número de recibos de 30 en una página a varios cientos. KB,La página web no los dibujará a todos.
     * Para leer el texto completo, accede a los detalles de la memoria en la aplicación.
     */
    const val CONTENT_LIMIT = 200

    /** Límite de caracteres para fragmentos de impacto. */
    const val SNIPPET_LIMIT = 120

    /** Resultados paginados. */
    data class MemoryPage(val entries: List<HostMemoryEntry>, val hasMore: Boolean)

    /** `memory.list` Acuse de recibo.[cursor] Vacío indica la primera página. */
    fun listEvent(
        records: List<HostMemoryRecord>,
        cursor: Long?,
        limit: Int,
        requestId: String?,
    ): HostEvent {
        val page = page(records, cursor, limit)
        return HostEvent(
            type = TYPE_LIST,
            requestId = requestId,
            memory = page.entries,
            hasMore = page.hasMore,
        )
    }

    /** `memory.search` acuse de recibo:La estructura de entrada es consistente con la lista.,Extra con clips de éxito. */
    fun searchEvent(
        query: String,
        records: List<HostMemoryRecord>,
        requestId: String?,
    ): HostEvent = HostEvent(
        type = TYPE_SEARCH,
        requestId = requestId,
        memoryHits = records.map { record ->
            HostMemoryHit(entry = record.toEntry(), snippet = snippetOf(record.content, query))
        },
    )

    /**
     * Toma una página.
     *
     * [limit] Consíguelo primero `1..`[MAX_LIMIT];[cursor] No en este lote de entradas.(Eliminado/La biblioteca ha sido reemplazada.)tiempo,
     * degenerar en"Toma algo más pequeño que eso. id",No volverás a la primera página porque no encuentras el ancla.
     */
    fun page(records: List<HostMemoryRecord>, cursor: Long?, limit: Int): MemoryPage {
        val capped = limit.coerceIn(1, MAX_LIMIT)
        val ordered = records.sortedByDescending { idOf(it.id) }
        val remaining = if (cursor == null) ordered else ordered.filter { idOf(it.id) < cursor }
        return MemoryPage(
            entries = remaining.take(capped).map { it.toEntry() },
            hasMore = remaining.size > capped,
        )
    }

    /** La identificación de la entrada es una cadena decimal.;No se puede analizar(Teóricamente no habrá)Tratar como 0,Llega al final de la cola. */
    fun idOf(id: String): Long = id.toLongOrNull() ?: 0L

    /** fuente de hechos → Categoría de fuente en el protocolo. */
    fun sourceKind(sessionId: String?, factSource: String): String = when {
        !sessionId.isNullOrBlank() -> HostMemorySourceKind.CONVERSATION
        factSource == FACT_SOURCE_USER_EXPLICIT -> HostMemorySourceKind.MANUAL
        else -> HostMemorySourceKind.AUTO
    }

    /** Fragmentos cerca de la posición del golpe.;La cadena de consulta no está en el texto.(Por ejemplo, sólo se acierta el participio.)Tome el primer párrafo. */
    fun snippetOf(content: String, query: String): String {
        val text = content.trim()
        if (text.length <= SNIPPET_LIMIT) return text
        val hit = text.indexOf(query, ignoreCase = true).coerceAtLeast(0)
        val start = (hit - SNIPPET_LIMIT / 3).coerceIn(0, text.length - SNIPPET_LIMIT)
        val end = start + SNIPPET_LIMIT
        // Los límites de los fragmentos no pueden quedar en medio de pares de proxy,De lo contrario, los personajes sustitutos huérfanos se serializan.
        val from = if (start > 0 && Character.isLowSurrogate(text[start])) start + 1 else start
        val to = if (end < text.length && Character.isHighSurrogate(text[end - 1])) end - 1 else end
        return text.substring(from, to)
    }

    private fun HostMemoryRecord.toEntry(): HostMemoryEntry = HostMemoryEntry(
        id = id,
        content = content.prefixOf(CONTENT_LIMIT),
        createdAt = createdAt,
        source = source,
        pinned = pinned,
    )

    /** amputación de cabeza,No corta pares de proxy. */
    private fun String.prefixOf(limit: Int): String {
        if (length <= limit) return this
        val end = if (Character.isHighSurrogate(this[limit - 1])) limit - 1 else limit
        return substring(0, end)
    }

    /** Hechos dentro y fuera"declaración explícita del usuario"valor de origen. */
    private const val FACT_SOURCE_USER_EXPLICIT = "user_explicit"
}
