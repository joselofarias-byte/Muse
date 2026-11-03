package io.zer0.muse.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.zer0.common.Logger

/**
 * Leer ruta de los medios del mensaje:`GET /api/media/{token}`.
 *
 * Sólo lectura, sólo reconocimiento [HostMediaStore] token registrado en. instalado con `/ws` En el bloque de autenticación JWT del mismo nivel:
 * Las imágenes y las voces son registros de chat en sí mismos, y los dispositivos LAN que no estén conectados no deberían poder obtener capturas de pantalla de conversaciones históricas.
 *
 * 404 unificado transfronterizo e inexistente, ni 403 ni 500: el primero no da retroalimentación al detector y el segundo no debe ser devuelto por
 * Esto se desencadena por la situación normal de "archivo en limpieza".
 */
internal fun Route.installHostMediaRoutes(mediaStore: HostMediaStore) {
    get("/api/media/{token}") {
        val token = call.parameters["token"].orEmpty()
        val file = token.takeIf { it.isNotBlank() }?.let(mediaStore::resolve)
        if (file == null) {
            if (token.isNotBlank()) Logger.d(TAG, "medios no disponibles: token=$token")
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        val contentType = HostMediaContentType.of(file) ?: HostMediaContentType.OCTET_STREAM
        call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
        // LocalFileContent Envíe el archivo directamente al motor para enviarlo en fragmentos.,No lea la imagen completa en la memoria.
        call.respond(LocalFileContent(file, ContentType.parse(contentType)))
    }
}

private const val TAG = "HostMediaRoutes"

/**
 * El contenido multimedia se denomina mediante hash de contenido,token Correspondencia uno a uno con la ruta, el contenido del mismo archivo no cambiará, por lo que se puede almacenar en caché;
 * `private` No se permite compartir proxy intermedio.`max-age=300` No conservar las imágenes de la conversación eliminadas en el navegador durante medio día.
 */
private const val CACHE_CONTROL = "private, max-age=300"
