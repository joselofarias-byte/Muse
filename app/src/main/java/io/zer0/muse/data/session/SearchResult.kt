package io.zer0.muse.data.session

/**
 * Elemento de resultado de búsqueda.
 *
 * @param messageId mensaje coincidente id
 * @param sessionId Perteneciente a la sesión id(Haz clic para saltar)
 * @param sessionTitle Título de la sesión(Los resultados muestran)
 * @param contentSnippet fragmento de contenido(Haga coincidir los 30 caracteres antes y después de la palabra clave)
 * @param role rol de mensaje
 * @param createdAt Crear marca de tiempo
 * @param content Tarea 2: Mensaje original(Proporciona interfaz de usuario para extraer el contexto de 2 oraciones antes y después + resaltado de palabras clave);
 *   FTS4 snippet Para camino [contentSnippet] Eso es todo,Este campo se completa a partir de una ruta LIKE o una proyección JOIN
 */
data class SearchResult(
    val messageId: String,
    val sessionId: String,
    val sessionTitle: String,
    val contentSnippet: String,
    val role: String,
    val createdAt: Long,
    val content: String = "",
)
