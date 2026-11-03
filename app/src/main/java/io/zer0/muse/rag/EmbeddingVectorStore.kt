package io.zer0.muse.rag

import io.zer0.common.resultOf

/**
 * "Obtención de vectores por lotes con caché"——poner [EmbeddingVectorCache] Combinado con codificación por lotes de una sola vez.
 *
 * Hay dos razones para categorías separadas:
 *  1. Sólo hay tres cosas en esta lógica (verificar caché → Complete los elementos faltantes en lotes → fusionar por posición), con análisis de configuración,
 *     Provider Construir irrelevante, mezclado [EmbeddingService] lo empujará al umbral del número de función;
 *  2. Se puede probar directamente utilizando el proveedor suplente. —— En realidad, no es necesario crear un proveedor de nube.
 *
 * Convención semántica:**Si falta uno, todo fracasará.**(devuelve nulo). Asigne el vector desalineado a otro texto,
 * sólo aparecerá como"AI Recordé lo equivocado", no se informan errores, no vale la pena correr el riesgo.
 */
internal class EmbeddingVectorStore(
    private val cache: EmbeddingVectorCache = EmbeddingVectorCache(),
) {

    /** Número de aciertos (con fines de diagnóstico). */
    val hits: Int get() = cache.hits

    /** El número de errores es igual al número de textos realmente enviados para codificación (para diagnóstico). */
    val misses: Int get() = cache.misses

    suspend fun embed(provider: EmbeddingProvider, fingerprint: String, texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        val keys = texts.map { cache.key(fingerprint, it) }
        val results = arrayOfNulls<FloatArray>(texts.size)
        val missing = ArrayList<Int>()
        keys.forEachIndexed { index, key ->
            val hit = cache.get(key)
            if (hit != null) results[index] = hit else missing.add(index)
        }
        return if (missing.isEmpty()) {
            results.takeIfComplete()
        } else {
            fetchMissing(provider, texts, keys, results, missing)
        }
    }

    /**
     * Solo se verifica el caché, no se activa la codificación y no se modifican las estadísticas de visitas.
     *
     * Dar "Hay un tiempo de espera/Uso de la "semántica del disyuntor" por parte de la persona que llama (como la incrustación de consultas de RAG):
     * Si se alcanza el caché, no debería ingresar a la ventana de tiempo de espera de la red. Si el caché no se alcanza, la persona que llama decide cómo recuperarlo y cómo interrumpir el circuito si no se puede recuperar.
     */
    fun peek(fingerprint: String, text: String): FloatArray? = cache.peek(cache.key(fingerprint, text))

    /** Escribe un vector por separado (con [peek] Uso, la persona que llama es responsable del tiempo de espera y la semántica de falla al recuperar el número). */
    fun store(fingerprint: String, text: String, vector: FloatArray) {
        cache.put(cache.key(fingerprint, text), vector)
    }

    /** Solo el texto faltante se envía a codificación y luego se vuelve a fusionar según la posición [results]. */
    private suspend fun fetchMissing(
        provider: EmbeddingProvider,
        texts: List<String>,
        keys: List<String>,
        results: Array<FloatArray?>,
        missing: List<Int>,
    ): List<FloatArray>? {
        val fetched = resultOf { provider.embed(missing.map { texts[it] }) }.getOrNull()
        if (fetched == null || fetched.size != missing.size) return null
        missing.forEachIndexed { offset, index ->
            results[index] = fetched[offset]
            cache.put(keys[index], fetched[offset])
        }
        return results.takeIfComplete()
    }

    /** Regrese solo después de que se hayan cubierto todos los puestos. */
    private fun Array<FloatArray?>.takeIfComplete(): List<FloatArray>? {
        val collected = ArrayList<FloatArray>(size)
        for (item in this) collected += item ?: return null
        return collected
    }
}
