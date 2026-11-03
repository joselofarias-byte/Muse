package io.zer0.muse.web

import java.util.Base64

/**
 * Citas de medios en mensajes. → Enlaces que el navegador puede recuperar.
 *
 * App foto lateral/Hay tres formas de cita de discurso:`file:///data/user/…` camino absoluto,`data:image/…;base64,…`
 * Datos en línea y base64 desnuda. Ninguno de los tres está disponible en el navegador (la primera es la ruta local y las dos últimas no son direcciones).
 * Reemplácelos uniformemente antes de emitir [MEDIA_PATH_PREFIX] + token;[HostMediaStore] Los que no se pueden registrar (los expedientes han sido borrados,
 * Los puntos fuera del directorio permitido y el contenido no son medios identificables) se eliminan directamente de la matriz, sin dejar ninguna dirección 404 inevitable. ——
 * Obtener una dirección no disponible en una página web será más difícil de manejar que obtener una matriz vacía.
 *
 * `http(s)` La dirección se entrega tal como está: el navegador puede recuperarla originalmente, pero evitar el proxy agregará otra capa de puntos de falla.
 */
internal class HostMediaLinks(
    private val store: HostMediaStore,
) {

    /** Conversión de una única referencia; devolver nulo indica que los medios no están disponibles en la página web.[ownerMessageId] El prefijo de propiedad utilizado para archivos de disco en línea. */
    fun link(value: String, ownerMessageId: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(HTTP_SCHEME) || trimmed.startsWith(HTTPS_SCHEME)) return trimmed
        return tokenFor(trimmed, ownerMessageId)?.let { MEDIA_PATH_PREFIX + it }
    }

    /** Convierta la versión de la lista y elimine los elementos no disponibles. */
    fun links(values: List<String>, ownerMessageId: String): List<String> =
        values.mapNotNull { link(it, ownerMessageId) }

    /** Las referencias de voz siguen una cadena separada: los mensajes que no son de voz no se envían a voiceUrl. */
    fun voiceLink(value: String?, ownerMessageId: String): String? =
        value?.takeIf { it.isNotBlank() }?.let { link(it, ownerMessageId) }

    /**
     * Recursos desechables como avatares: solo reconoce archivos locales y `http(s)`.
     *
     * Base64 en línea no se coloca aquí —— El avatar no pertenece a ningún mensaje y los archivos eliminados no tienen posibilidad de eliminarse.
     * Simplemente agregue huérfanos; si no se puede transferir, se devolverá a nulo y el primer carácter se devolverá desde el front-end.
     */
    fun fileOrHttpLink(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(HTTP_SCHEME) || trimmed.startsWith(HTTPS_SCHEME)) return trimmed
        if (!trimmed.startsWith(FILE_SCHEME) && !isLocalPath(trimmed)) return null
        return store.tokenForFile(trimmed)?.let { MEDIA_PATH_PREFIX + it }
    }

    /** Enviado por formulario de referencia; cada uno está disponible sólo en [HostMediaStore] El token se obtendrá únicamente en el directorio permitido. */
    private fun tokenFor(value: String, ownerMessageId: String): String? = when {
        value.startsWith(FILE_SCHEME) || isLocalPath(value) -> store.tokenForFile(value)
        value.startsWith(DATA_SCHEME) -> store.tokenForInlineData(
            HostMediaContentType.inlinePayload(value),
            HostMediaContentType.declaredMime(value),
            ownerMessageId,
        )

        looksLikeBase64(value) -> store.tokenForInlineData(value, null, ownerMessageId)
        else -> null
    }

    /** sin `file://` Ruta nativa prefijada:Unix Rutas absolutas frente a rutas de letras de unidades de Windows. */
    private fun isLocalPath(value: String): Boolean =
        value.startsWith("/") || value.startsWith(UNC_PREFIX) || DRIVE_PATH.matches(value)

    private fun looksLikeBase64(value: String): Boolean {
        if (value.length < MIN_BASE64_LENGTH) return false
        // base64 La longitud mod 4 no puede ser 1 (un número impar de bytes finales daría como resultado un bit de relleno adicional).
        if (value.length % 4 == 1) return false
        return runCatching { Base64.getMimeDecoder().decode(value).isNotEmpty() }.getOrDefault(false)
    }

    companion object {
        /** Prefijo de enrutamiento de medios, tal como está instalado en WebServer `/api/media/{token}` Los caminos son consistentes. */
        const val MEDIA_PATH_PREFIX = "/api/media/"

        private const val FILE_SCHEME = "file://"
        private const val DATA_SCHEME = "data:"
        private const val HTTP_SCHEME = "http://"
        private const val HTTPS_SCHEME = "https://"

        /** Prefijo de ruta compartida de red (`\\server\share\x.png`), que también es un método de escritura de ruta que puede ser analizado por esta máquina. */
        private const val UNC_PREFIX = "\\\\"

        /** Cualquier valor inferior a esta longitud no puede ser datos de imagen (los gifs de marcador de posición también son mucho más largos). */
        private const val MIN_BASE64_LENGTH = 32

        /** Windows Ruta de la letra de unidad (`C:\…` / `C:/…`). */
        private val DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
    }
}
