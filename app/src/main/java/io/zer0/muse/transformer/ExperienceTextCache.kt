package io.zer0.muse.transformer

/**
 * Caché de texto en minúsculas de entradas de experiencias.
 *
 * El segmento del banco de experiencias debe participar en "clasificar según la relevancia del problema actual" en cada ronda, y el método de puntuación es
 * `title + " " + content` Haga una copia del párrafo completo en minúsculas y luego escanee la subcadena. La cantidad de elementos de experiencia la acumulan los usuarios.
 * El contenido también puede ser muy largo. —— Cada ronda asigna una nueva copia completa en minúscula de cada experiencia, lo cual es pura duplicación de trabajo.
 *
 * clave `id + updatedAt`: Después de que la experiencia haya sido editada `updatedAt` Debe cambiarse y, naturalmente, la copia anterior dejará de ser válida.
 * No es necesario escuchar los eventos de escritura. El aforo se limita en función del número de artículos, y se eliminarán aquellos que lleven más tiempo sin utilizarse.
 *
 * Solo se almacena en caché la copia en minúsculas (los resultados de la puntuación no se almacenan en caché): la puntuación depende de la pregunta actual y las preguntas son diferentes en cada ronda.
 */
internal class ExperienceTextCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    private val entries = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > maxEntries
    }

    /** Número de aciertos (con fines de diagnóstico). */
    var hits: Int = 0
        private set

    /** Número de errores (para fines de diagnóstico). */
    var misses: Int = 0
        private set

    fun key(id: String, updatedAt: Long): String = "$id@$updatedAt"

    fun get(key: String): String? = synchronized(entries) {
        val hit = entries[key]
        if (hit == null) misses += 1 else hits += 1
        hit
    }

    fun put(key: String, lowered: String) {
        synchronized(entries) { entries[key] = lowered }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 256
    }
}
