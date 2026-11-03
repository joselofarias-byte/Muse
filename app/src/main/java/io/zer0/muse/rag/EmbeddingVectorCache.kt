package io.zer0.muse.rag

import java.security.MessageDigest

/**
 * embedding Caché vectorial (LRU a nivel de proceso).
 *
 * ## por qué es necesario
 *
 * El "reordenamiento vectorial" de la correlación de la memoria debe ajustarse en cada ronda. `provider.embed(query + todos los datos del candidato)`.
 * Este es un viaje de ida y vuelta HTTPS bajo el origen de la nube, y**Los datos de los candidatos apenas cambian entre rondas** —— Llévalos en cada ronda
 * Recodificar equivale a cargar la misma entrada repetidamente y dejar que el servidor calcule repetidamente el mismo lote de vectores.
 *
 * Después del almacenamiento en caché, la situación típica es la siguiente: se alcanzan N vectores de hechos y solo se envía una solicitud a la consulta; el usuario vuelve a intentarlo./regenerar
 * En la misma oración, se responde incluso a la consulta y no se envía ninguna llamada de red.
 *
 * ## Claves e invalidaciones
 *
 * clave = Provider Huella digital + resumen de contenido de texto. La huella digital contiene apiKey/baseUrl/model, así que cambie el modelo o
 * Después de cambiar la clave, el vector anterior, naturalmente, ya no aparecerá (el vector de otro modelo no se utilizará para calcular el coseno).
 *
 * Utilice SHA-256 primeros 16 bytes en lugar de `String.hashCode()`: Los errores de cálculo vectorial harán que la memoria inyectada se ordene incorrectamente.
 * Este tipo de error no reporta ningún error y solo se puede expresar como"AI Parece que recordé algo mal.", no vale la pena arriesgarse a una colisión para guardar unos pocos bytes.
 *
 * La capacidad está limitada según la cantidad de artículos. Si se supera el aforo, se eliminarán los artículos que lleven más tiempo sin utilizarse ([LinkedHashMap] secuencia de acceso).
 */
internal class EmbeddingVectorCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val entries = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>): Boolean = size > maxEntries
    }

    /** Número de aciertos (con fines de diagnóstico). */
    var hits: Int = 0
        private set

    /** Número de errores (para fines de diagnóstico). */
    var misses: Int = 0
        private set

    fun key(fingerprint: String, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val hex = digest.take(DIGEST_BYTES).joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "$fingerprint|$hex"
    }

    fun get(key: String): FloatArray? = synchronized(entries) {
        val hit = entries[key]
        if (hit == null) misses += 1 else hits += 1
        hit
    }

    /** Caché de solo lectura, las visitas no se cuentan/Miss estadísticas (la persona que llama las usa para"Es legal estés muerto o no."prejuicio). */
    fun peek(key: String): FloatArray? = synchronized(entries) { entries[key] }

    fun put(key: String, vector: FloatArray) {
        synchronized(entries) { entries[key] = vector }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        private const val DIGEST_BYTES = 16
        private const val DEFAULT_MAX_ENTRIES = 512
    }
}
