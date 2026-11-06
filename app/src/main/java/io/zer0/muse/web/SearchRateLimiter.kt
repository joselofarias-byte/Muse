package io.zer0.muse.web

import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Retroceso que limita la velocidad de búsqueda (único, estado de memoria a nivel de proceso, no persistente).
 *
 * Puntos de implementación:
 *  - Mantenimiento de memoria provider → nextAvailableAt Marca de tiempo
 *  - HTTP 429(Too Many Requests)/ 402(Payment Required, parte de la API de búsqueda se utiliza cuando se agota la cuota)
 *    Marque el proveedor como de tarifa limitada y las solicitudes posteriores se rechazarán directamente durante el período de suspensión para evitar chocar contra la pared repetidamente y ser prohibidos.
 *  - Admite el análisis del encabezado de respuesta Reintentar después para anular el tiempo de espera predeterminado
 *
 * Seguridad del hilo: uso [ConcurrentHashMap] Garantice la seguridad de la lectura y escritura de subprocesos múltiples.
 *
 * Uso:
 *  - Antes de llamar a buscar [await] Comprobar si la velocidad está restringida
 *  - HTTP Llamado en respuesta al 429/402. [markRateLimited] marca
 *  - O usarlo directamente [assertNotRateLimited] Detección completa en un solo paso+marca+Lanzar excepción
 */
object SearchRateLimiter {
    /** Tiempo de espera predeterminado:60 Segundos (usados ​​sin el encabezado Retry-After). */
    private const val DEFAULT_BACKOFF_MS = 60_000L

    /** Tiempo máximo de retroceso:5 minutos (para evitar que un único reintento posterior sea demasiado largo y provoque una indisponibilidad a largo plazo). */
    private const val MAX_BACKOFF_MS = 5 * 60 * 1000L

    /** provider logotipo → La marca de tiempo de la próxima hora invocable (System.currentTimeMillis()). */
    private val nextAvailableAt = ConcurrentHashMap<String, Long>()

    /**
     * Comprueba si el proveedor es actualmente llamable.
     *
     * @param provider provider Identidad (como "Tavily"/"Bing"/"SearXNG")
     * @return true=invocable;false=Límite de velocidad, hay que esperar. [getRetryAfterMs] Reintentar en milisegundos
     */
    fun await(provider: String): Boolean {
        val now = System.currentTimeMillis()
        val until = nextAvailableAt[provider] ?: 0L
        return now >= until
    }

    /**
     * Marca al proveedor como de tarifa limitada.
     *
     * @param provider provider logotipo
     * @param retryAfterMs Tiempo de retroceso (milisegundos); superado [MAX_BACKOFF_MS] se truncará y los valores negativos se tratarán como 0
     */
    fun markRateLimited(provider: String, retryAfterMs: Long) {
        val backoff = retryAfterMs.coerceIn(0L, MAX_BACKOFF_MS)
        nextAvailableAt[provider] = System.currentTimeMillis() + backoff
    }

    /**
     * Devuelve el tiempo de espera restante del proveedor en milisegundos.
     * Si la velocidad no está limitada o el período de espera ha expirado, se devolverá 0.
     */
    fun getRetryAfterMs(provider: String): Long {
        val now = System.currentTimeMillis()
        val until = nextAvailableAt[provider] ?: 0L
        return (until - now).coerceAtLeast(0L)
    }

    /**
     * Analizar Reintentar después de los encabezados de respuesta HTTP.
     *
     * Se admiten dos formatos (RFC 7231):
     *  1. Segundos enteros: p.e. "120"(más común)
     *  2. HTTP-date: Como por ejemplo "Wed, 21 Oct 2026 07:28:00 GMT"
     *
     * Si no existe dicho encabezado o el análisis falla, se devolverá el valor predeterminado de 60 años.
     *
     * @param headers Mapa de encabezado de respuesta (key Coincidencia que no distingue entre mayúsculas y minúsculas "Retry-After")
     */
    fun extractRetryAfterMs(headers: Map<String, String>): Long {
        // Búsqueda que no distingue entre mayúsculas y minúsculas Retry-After
        val raw = headers.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?: return DEFAULT_BACKOFF_MS

        // 1) Intente analizar como un número entero segundos (HTTP estándar)
        raw.trim().toLongOrNull()?.let { seconds ->
            return (seconds * 1000L).coerceIn(0L, MAX_BACKOFF_MS)
        }

        // 2) Intente analizar la fecha HTTP (RFC 1123)
        // contexto no suspendido,runCatching Seguro (no aceptará CancellationException)
        runCatching {
            val sdf = java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                java.util.Locale.US,
            ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            val target = sdf.parse(raw.trim())?.time ?: return DEFAULT_BACKOFF_MS
            val diff = target - System.currentTimeMillis()
            return diff.coerceIn(0L, MAX_BACKOFF_MS)
        }

        return DEFAULT_BACKOFF_MS
    }

    /**
     * Sobrecarga de comodidad: directamente desde OkHttp [Response] Extraer Reintentar después.
     */
    fun extractRetryAfterMs(resp: Response): Long {
        val headers = resp.headers
        val map: Map<String, String> = headers.names().associateWith { headers[it] ?: "" }
        return extractRetryAfterMs(map)
    }

    /**
     * Compruebe si la respuesta HTTP tiene velocidad limitada (429 / 402), si es así, marque el proveedor y tire [SearchRateLimitException].
     *
     * Para que cada Proveedor `resp.use { }` dentro del bloque,`if (!resp.isSuccessful)` llamado antes.
     * Se permiten respuestas sin límite de velocidad tal como están (no se genera ninguna excepción).
     *
     * @param provider provider logotipo
     * @param resp OkHttp respuesta
     * @throws SearchRateLimitException Cuando el código de respuesta es 429 o 402
     */
    fun assertNotRateLimited(provider: String, resp: Response) {
        val code = resp.code
        if (code != 429 && code != 402) return
        val retryAfterMs = extractRetryAfterMs(resp)
        markRateLimited(provider, retryAfterMs)
        throw SearchRateLimitException(
            cause = "$provider HTTP $code",
            retryAfterMs = retryAfterMs,
        )
    }

    /** Borre el estado del límite de velocidad del proveedor especificado (solo para pruebas o reinicio manual). */
    fun clear(provider: String) {
        nextAvailableAt.remove(provider)
    }

    /** Borre el estado de limitación de tarifas de todos los proveedores. */
    fun clearAll() {
        nextAvailableAt.clear()
    }
}

/**
 * La búsqueda tiene velocidad limitada.
 *
 * por [SearchRateLimiter] Lanzado en los siguientes escenarios:
 *  1. [SearchRateLimiter.await] Devolución falsa (llamada repetida dentro del período de suspensión)
 *  2. HTTP Respuesta 429/402([SearchRateLimiter.assertNotRateLimited] detectado)
 *
 * UI Muestre mensajes amigables al usuario después de la captura de la capa:"El servicio de búsqueda tiene velocidad limitada. Vuelve a intentarlo después de X segundos.".
 *
 * @param cause Descripción del motivo del límite de velocidad (como el nombre del proveedor + código de estado HTTP), que solo se utiliza para construir el mensaje.
 * @param retryAfterMs Tiempo de espera de reintento recomendado (milisegundos),UI Calcular en consecuencia"X Vuelve a intentarlo en segundos"
 */
class SearchRateLimitException(
    cause: String,
    val retryAfterMs: Long,
) : RuntimeException("Search rate limited: $cause, retry after ${retryAfterMs}ms")
