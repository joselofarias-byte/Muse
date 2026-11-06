package io.zer0.muse.miniphone.data.moment

import org.json.JSONArray

/**
 * Lista de imágenes dinámica con `imagesJson` de transferencia mutua.
 *
 * La biblioteca solo almacena un fragmento de texto JSON.(Máximo de nueve imágenes seguidas.),No permitir que Room tenga una tabla relacionada más:
 * Las imágenes no tienen su propio ciclo de vida.,Si elimina una actualización, debería desaparecer.,Guardarlo como texto es lo más fácil de hacer.
 * Los errores de análisis siempre se tratarán como "sin imagen". —— Un dato sucio no debería hacer que todo el segmento dinámico sea ilegible.
 */

/** Lista de rutas de imágenes → `imagesJson`;Almacenamiento de lista vacía `[]`. */
internal fun encodeMomentImages(images: List<String>): String {
    val array = JSONArray()
    images.forEach { path -> array.put(path) }
    return array.toString()
}

/** `imagesJson` → Lista de rutas de imágenes;Las cadenas vacías, las matrices vacías y el texto incorrecto obtienen listas vacías. */
internal fun decodeMomentImages(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { path -> path.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
