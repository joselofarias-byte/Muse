package io.zer0.muse.ui

import io.zer0.ai.RefImageUrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * SSRF protección — Determinar si el destino del enlace es una intranet/bucle invertido/Direcciones de red no públicas como enlace local.
 *
 * Extracto de LinkPreviewCard para su reutilización,Y evita demasiadas funciones en un solo archivo(detekt TooManyFunctions).
 * Vista previa del enlace del chat(Y captura secundaria de Coil de og:image)Impulsado por la salida del modelo o la URL pegada por el usuario,
 * Si no se agrega ninguna verificación, se puede inducir a revertir la conexión. 127.0.0.1(Incorporado WebServer)/Anfitrión LAN.
 *
 * C-6: DNS Rebinding protección — [isPrivateHost] Haga una resolución para cada nuevo nombre de dominio y acorte el caché TTL;
 * TTL Extremadamente corto Un ataque récord(Utilice la red pública al analizar y cambie a la intranet después de conectarse.)por [HOST_VERDICT_TTL_MS] ventana de caché
 * Ten todo en cuenta,Volver a analizar por evento después de la expiración de la caché.P2-19: El almacenamiento en caché corto TTL + limitado evita WebView recurso por recurso
 * hilo principal bloqueado DNS(ANR riesgo),El mismo host se resuelve sólo una vez dentro de la ventana de caché.
 *
 * P2-33(ANR erradicar): Separación explícita de tres rutas de llamadas,El hilo principal nunca comienza. DNS —
 *  - [isBlockedAsync]: analizado en [Dispatchers.IO] ejecutar,El hilo principal solo espera./Resultados de consumo;
 *  - [cachedVerdictOrNull]: Lectura de caché pura,Hilo principal ajustable(Regresar en caso de error null,por la persona que llama fail-closed);
 *  - [isBlocked]: versión de bloqueo sincrónico,Sólo las personas que llaman que ya están en conversaciones en segundo plano.(raspado web/HTTP Herramientas, etc.).
 * Los resultados del primer análisis también se escriben en la memoria caché.,Por lo tanto, la misma consulta de host después de la "primera resolución de nombre de dominio en frío" ya no activará DNS.
 */
internal object SsrfGuard {

    /** Caché de resultados de juicio del host TTL(milisegundos):30s Los recursos duplicados dentro de la ventana ya no activan DNS. */
    private const val HOST_VERDICT_TTL_MS = 30_000L

    /** Límite de entrada de caché:Cuando se exceda, borre todo(Evite eliminar la complejidad elemento por elemento). */
    private const val HOST_VERDICT_CACHE_MAX = 512

    /** Anfitrión → Caché de resultados del juicio(Con tiempo de vencimiento). */
    private val hostVerdictCache = java.util.concurrent.ConcurrentHashMap<String, HostVerdict>()

    private data class HostVerdict(val blocked: Boolean, val expiresAt: Long)

    /**
     * P2-33: Resolvedor de host inyectable.
     *
     * Implementación de la producción como sistema. DNS([InetAddress.getAllByName]);Inyección de prueba única en un analizador lento/geocodificador fijo,
     * Para afirmar de manera determinista que "el análisis se produce en el subproceso IO y no en el subproceso que llama" y el caché anula el primer análisis.
     */
    internal fun interface HostResolver {
        /** en**hilo de llamada**Host de resolución síncrona(La persona que llama es responsable de garantizar que no se ejecute en el hilo principal.). */
        fun resolve(host: String): Array<InetAddress>
    }

    /** Analizador predeterminado:DNS del sistema. */
    private val systemResolver = HostResolver { host -> InetAddress.getAllByName(host) }

    @Volatile
    private var resolver: HostResolver = systemResolver

    /** Analizador de inyección solo para pruebas(Finalmente se debe llamar al inyector. [resetForTest] restaurar). */
    internal fun setResolverForTest(hostResolver: HostResolver) {
        resolver = hostResolver
    }

    /** Sólo para pruebas:Restaure el solucionador predeterminado y borre el caché de decisiones del host. */
    internal fun resetForTest() {
        resolver = systemResolver
        hostVerdictCache.clear()
    }

    /**
     * G4: Validador SSRF de URL de imagen de referencia — http/https URL por [isBlocked] juicio(Ir a la intranet/Reservar
     * La dirección devuelve false y debe rechazarse.),el resto scheme(data:/file:) constante true(No se requiere internet,liberar).
     * Tipo [RefImageUrlValidator] Definido en el módulo ai(Para que Koin inyecte declaraciones),
     * Proporcionado por la capa de aplicación SsrfBridgeModule registrada en Koin [io.zer0.ai.image.AgnesImageProvider] Usar.
     */
    val refImageUrlValidator: RefImageUrlValidator = object : RefImageUrlValidator {
        override fun isAllowed(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                return !isBlocked(trimmed)
            }
            return true
        }
    }

    /** Devuelve verdadero para indicar que se debe denegar el rastreo.(no http(s)/No se puede analizar/dirección intranet). */
    fun isBlocked(url: String): Boolean {
        val host = parseHttpUri(url)?.host?.takeIf { it.isNotBlank() } ?: return true
        return isPrivateHost(host)
    }

    /**
     * P2-33: Decisión asincrónica — analizado en [Dispatchers.IO] ejecutar,La llamada del hilo principal no se bloquea.
     *
     * Semántica y [isBlocked] completamente consistente(no http(s)/El análisis falló/Intranet → true),La única diferencia es que el análisis se produce en
     * hilo de fondo;Los resultados del análisis se escriben como de costumbre. [hostVerdictCache],Seguimiento [cachedVerdictOrNull] con
     * [isBlocked] todos pueden golpear,Es decir, la ruta del "primer análisis" también está cubierta por el caché.
     */
    suspend fun isBlockedAsync(url: String): Boolean {
        val host = parseHttpUri(url)?.host?.takeIf { it.isNotBlank() } ?: return true
        cachedVerdict(host)?.let { return it }
        return withContext(Dispatchers.IO) { resolveAndCache(host) }
    }

    /**
     * P2-33: Calentamiento de fondo — Llenar caché solo,No me importa el valor de retorno(Para que el hilo principal de WebView vuelva a llamar en caso de error cerrado y luego se autorrepare).
     */
    suspend fun prewarm(url: String) {
        isBlockedAsync(url)
    }

    /**
     * P2-33: Consuma solo decisiones sin bloqueo almacenadas en caché(Seguridad del hilo principal,Nunca inicies DNS).
     *
     * @return El acierto de caché devuelve el resultado del juicio;Regresar en caso de error null,La persona que llama debe cerrar fallidamente el controlador.
     *         no http(s)/URL irresoluble siempre devuelta true(rechazo conservador).
     */
    fun cachedVerdictOrNull(url: String): Boolean? {
        val host = parseHttpUri(url)?.host?.takeIf { it.isNotBlank() } ?: return true
        return cachedVerdict(host)
    }

    /**
     * B-31: Extracto de texto libre http(s) URL,Sutra uno por uno [isBlocked] juicio;
     * cualquier golpe(Intranet/bucle invertido/enlace local/No se puede analizar, etc.)es decir, regresar true,La persona que llama debe rechazar este texto en su totalidad.
     * Único responsable del posicionamiento. http(s) fragmento,red privada/enlace local/IPv6/Decisiones como IP entera convergen uniformemente para [isPrivateAddress].
     */
    fun hasBlockedUrlInText(text: String): Boolean = HTTP_URL_IN_TEXT.findAll(text).any { isBlocked(it.value) }

    private fun parseHttpUri(url: String): URI? = try {
        URI(url).takeIf { it.scheme?.lowercase() in setOf("http", "https") }
    } catch (_: Exception) {
        null
    }

    /**
     * Juicio sincrónico(puede iniciar DNS):Disponible solo para personas que llaman y ya están en conversaciones en segundo plano.
     *
     * El hilo principal que usa la persona que llama [isBlockedAsync](colgar)o [cachedVerdictOrNull](almacenamiento en caché puro),
     * De lo contrario, la primera resolución del nombre de dominio frío bloqueará el hilo principal.(ANR).
     */
    private fun isPrivateHost(host: String): Boolean {
        // P2-19: Se omiten los aciertos cortos de caché TTL DNS;señorita/Analizar después del vencimiento,El mismo host sólo se resuelve una vez dentro de la ventana
        cachedVerdict(host)?.let { return it }
        return resolveAndCache(host)
    }

    /** golpe de caché(No caducado)Juicio de devolución,De lo contrario null(No iniciado DNS). */
    private fun cachedVerdict(host: String): Boolean? {
        val cached = hostVerdictCache[host] ?: return null
        return if (System.currentTimeMillis() < cached.expiresAt) cached.blocked else null
    }

    /** Resolver host y escribir en caché(El fracaso del análisis se juzgará de forma conservadora como blocked). */
    private fun resolveAndCache(host: String): Boolean {
        // Doble verificación:Cuando se llama simultáneamente, solo se analizará el primero en llegar.,El recién llegado consume el caché directamente.
        cachedVerdict(host)?.let { return it }
        val blocked = try {
            resolver.resolve(host).any { isPrivateAddress(it) }
        } catch (_: Exception) {
            true // El análisis falló(no existe/secuestrado)Rechazar conservadoramente,No enviar solicitud de rastreo
        }
        // caché limitado:Si se excede el límite superior, borre todo el,Evite el crecimiento de entrada ilimitado
        if (hostVerdictCache.size >= HOST_VERDICT_CACHE_MAX) {
            hostVerdictCache.clear()
        }
        hostVerdictCache[host] = HostVerdict(blocked = blocked, expiresAt = System.currentTimeMillis() + HOST_VERDICT_TTL_MS)
        return blocked
    }

    @Suppress("ReturnCount") // multicapa early-return fail-fast,La legibilidad es mejor que forzar la convergencia hacia una única salida
    private fun isPrivateAddress(addr: InetAddress): Boolean {
        val v6 = addr as? Inet6Address
        if (v6 != null) {
            val bytes = v6.address
            val isV4Mapped = isV4Mapped(bytes)
            if (isV4Mapped) {
                return isPrivateIpv4(bytes[12].toInt() and 0xff, bytes[13].toInt() and 0xff)
            }
            // fc00::/7 ULA(Java isSiteLocalAddress solo cubrir fec0::/10,No cubierto fc00::/7)
            if (isUla(bytes)) return true
        }
        return addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
    }

    /** IPv4-mapped IPv6 (::ffff:a.b.c.d) juicio,Java isLoopbackAddress, etc. devuelve falso para las direcciones asignadas. */
    private fun isV4Mapped(bytes: ByteArray): Boolean = bytes.size == 16 &&
        bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
        bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()

    private fun isUla(bytes: ByteArray): Boolean = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC

    private fun isPrivateIpv4(a: Int, b: Int): Boolean = when {
        a == 0 -> true // 0.0.0.0/8
        a == 10 -> true // 10.0.0.0/8
        a == 127 -> true // 127.0.0.0/8
        a == 169 && b == 254 -> true // 169.254.0.0/16 link-local
        a == 172 && b in 16..31 -> true // 172.16.0.0/12
        a == 192 && b == 168 -> true // 192.168.0.0/16
        a >= 224 -> true // 224.0.0.0/4 multicast y sección reservada
        else -> false
    }

    /** B-31: Posición en texto libre http(s) URL fragmento(Sólo objetivo,Se entrega sentencia de red privada a [isPrivateAddress]). */
    private val HTTP_URL_IN_TEXT: Regex = Regex("""https?://[^\s"'`<>]+""", RegexOption.IGNORE_CASE)
}
