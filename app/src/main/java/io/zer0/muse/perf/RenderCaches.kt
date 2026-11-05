package io.zer0.muse.perf

import android.graphics.Bitmap
import io.zer0.muse.ui.markdown.MarkdownBlock
import io.zer0.muse.ui.markdown.parseMarkdown

/**
 * Almacenamiento en caché de resultados de renderizado a nivel de proceso.
 *
 * por qué es necesario:Cuando la LazyColumn de la lista de chat salga de la pantalla, el elemento será destruido. composition,
 * Se perderá la memoria caché que cuelga del ciclo de vida de la composición.;Markdown se analiza completamente al retroceder,
 * data-URI La decodificación de la imagen base64 debe recalcularse desde cero. —— Esta es la fuente directa del retraso en el desplazamiento.
 * El caché se cuelga aquí.(En proceso, independiente de la composición),Desplácese hacia afuera y hacia atrás para un golpe directo.
 */

/** Capacidad inicial del cucharón y factor de llenado,LinkedHashMap Valor predeterminado,No se requieren ajustes comerciales. */
private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f

/**
 * Caché LRU limitado(Seguridad del hilo).
 *
 * @param maxSize Límite de capacidad,La unidad consta de [sizeOf] decidir(Número de elementos o bytes)
 * @param sizeOf Capacidad ocupada por un solo registro;Las entradas que devuelven 0 no llenarán la capacidad por sí solas.
 *
 * Semántica:`accessOrder = true` de LinkedHashMap,último escrito/El elemento leído está al final de la lista vinculada.,
 * La eliminación siempre comienza desde la cabeza.(Más tiempo sin usar)Llevar. Aprobar tanto la lectura como la escritura. synchronized —— Representación en el hilo principal,
 * Decodificación en hilo IO,Ambas partes alcanzarán el caché.
 */
internal class BoundedLruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val sizeOf: (V) -> Int,
) {
    private val entries = LinkedHashMap<K, V>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var currentSize = 0

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        val previous = entries.put(key, value)
        if (previous != null) currentSize -= sizeOf(previous)
        currentSize += sizeOf(value)
        trim()
    }

    @Synchronized
    fun containsKey(key: K): Boolean = entries.containsKey(key)

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun totalSize(): Int = currentSize

    @Synchronized
    fun clear() {
        entries.clear()
        currentSize = 0
    }

    /** Eliminar del final el que lleva más tiempo sin usarse,hasta que la capacidad vuelva al límite superior. */
    private fun trim() {
        while (currentSize > maxSize && entries.isNotEmpty()) {
            val eldestKey = entries.keys.first()
            val removed = entries.remove(eldestKey) ?: continue
            currentSize -= sizeOf(removed)
        }
    }
}

/**
 * Si se acierta, devuelve el valor almacenado en caché,Llamado sólo en caso de fallo [decode] Y escriba los resultados nuevamente en el caché.
 *
 * El propósito de crear una función independiente es permitir que la propiedad de "los resultados no se recalculen" se verifique directamente sin mapas de bits de Android.
 */
internal fun <T : Any> cachedDecode(
    cache: BoundedLruCache<String, T>,
    key: String,
    decode: () -> T?,
): T? {
    cache.get(key)?.let { return it }
    val decoded = decode() ?: return null
    cache.put(key, decoded)
    return decoded
}

/** Markdown El límite superior del número de resultados de análisis almacenados en caché.:Es aproximadamente igual al volumen del mensaje de \"una pantalla, dos capas + una pantalla antes y después\". */
internal const val MARKDOWN_CACHE_MAX_ENTRIES = 128

/**
 * El texto que exceda esta cantidad de caracteres no se almacenará en caché.
 *
 * La ocupación de un único resultado de análisis aumenta con el número de bloques.,Rellenar unos cuantos mensajes de 10.000 palabras exprimirá la LRU.,Por el contrario, reduce la tasa de aciertos.;
 * Hay muy pocos mensajes tan largos en la lista.,El costo de recálculo es aceptable.
 */
internal const val MARKDOWN_CACHE_MAX_CHARS = 20_000

/**
 * Caché de análisis a nivel de proceso para Markdown sin transmisión.
 *
 * key = ID del mensaje + contenido hash([MessagePaginator.contentCacheKey]),
 * Cuando no se puede obtener la ID del mensaje, degenerará en contenido puro. hash(key cinturón interior `content#` Prefijo para distinguir).
 * El resultado del análisis solo está determinado por el contenido.,El ID del mensaje sólo se utiliza para evitar la eliminación innecesaria de diferentes mensajes.,
 * Por lo tanto, los ID faltantes o duplicados no devolverán un resultado de error.,El peor de los casos es una caída en la tasa de aciertos.
 */
internal object MarkdownParseCache {
    private val cache = BoundedLruCache<String, List<MarkdownBlock>>(MARKDOWN_CACHE_MAX_ENTRIES) { 1 }

    /**
     * Obtener caché,Si falla, use [parse] Analiza y escribe de nuevo.
     *
     * @param messageId noticias id(Enviar mensajes en la escena del chat. id;Sin transferencia de escena de identificación null)
     * @param content Texto a analizar
     * @param parse función analítica,Análisis completo predeterminado
     */
    fun getOrParse(
        messageId: String?,
        content: String,
        parse: (String) -> List<MarkdownBlock> = ::parseMarkdown,
    ): List<MarkdownBlock> {
        val key = keyOf(messageId, content)
        cache.get(key)?.let { return it }
        val parsed = parse(content)
        if (content.length <= MARKDOWN_CACHE_MAX_CHARS) cache.put(key, parsed)
        return parsed
    }

    /** Caché de cálculo key;La prueba y la persona que llama comparten el mismo algoritmo.,Evite la deriva en ambos lados. */
    fun keyOf(messageId: String?, content: String): String =
        if (messageId.isNullOrBlank()) {
            "content#${content.hashCode()}"
        } else {
            MessagePaginator.contentCacheKey(messageId, content)
        }

    fun containsKey(key: String): Boolean = cache.containsKey(key)

    fun size(): Int = cache.size()

    fun clear() = cache.clear()
}

/** La caché de mapa de bits se adapta al tamaño del montón:Cuanto más rica es la pila, más queda.,pero no más que [IMAGE_CACHE_MAX_BYTES_CEILING]. */
private const val IMAGE_CACHE_MAX_BYTES_CEILING = 32 * 1024 * 1024
private const val IMAGE_CACHE_HEAP_FRACTION = 10

/**
 * data-URI Caché de resultados de decodificación de imágenes(nivel de proceso,Límite superior en bytes).
 *
 * estrategia de reciclaje:Mapa de bits retenido por caché**No**Reciclar activamente. Cuando sale el estado de producción original recycle,
 * Esto se debe a que el ciclo de vida del mapa de bits está completamente ligado a la composición en ese momento.;Ahora es posible que el caché aún contenga el mismo objeto.,
 * recycle Deséchelo después de su uso. "Canvas: trying to use a recycled bitmap". En su lugar, GC recicla los mapas de bits,
 * La capacidad está controlada por el límite de bytes de esta caché.,No crecerá indefinidamente.
 *
 * En lugar de reutilizar MemoryCache:data-URI de Coil, BitmapFactory lo decodifica directamente.(Bobinas de derivación
 * DataUriFetcher,Falla silenciosamente en algunos dispositivos.),Y el MemoryCache de Coil 2 es sólo para ImageLoader
 * Tubería interna abierta para escritura,No existe una entrada externa para insertar mapas de bits arbitrarios.;Si está cableado, habrá una capa adicional de adaptación y pisarlo.
 * Versión de implementación interna,Es mejor utilizar el límite de bytes usted mismo.
 */
internal object DataUriImageCache {
    private val bitmaps = BoundedLruCache<String, Bitmap>(maxBytes()) { it.byteCount.coerceAtLeast(1) }

    private fun maxBytes(): Int = minOf(
        IMAGE_CACHE_MAX_BYTES_CEILING,
        (Runtime.getRuntime().maxMemory() / IMAGE_CACHE_HEAP_FRACTION).toInt(),
    )

    /**
     * Clave de caché de imagen: longitud de todo el URI de datos + hash.
     *
     * Hazlo sin la sección base64. key,Esto es para que la persona que llama no tenga que cortar una cadena de varios MB para calcular la clave.;
     * hashCode Será almacenado en caché internamente por String,La misma instancia sólo se cuenta una vez.
     */
    fun keyOf(dataUri: String): String = "${dataUri.length}:${dataUri.hashCode()}"

    fun get(key: String): Bitmap? = bitmaps.get(key)

    /** Devuelve directamente al golpear,Decodificar en caso de error;Los resultados de la decodificación se vuelven a escribir en la memoria caché para su reutilización directa en la siguiente combinación. */
    fun decodeOrGet(key: String, decode: () -> Bitmap?): Bitmap? = cachedDecode(bitmaps, key, decode)

    fun size(): Int = bitmaps.size()

    fun clear() = bitmaps.clear()
}
