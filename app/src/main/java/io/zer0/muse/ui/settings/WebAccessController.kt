package io.zer0.muse.ui.settings

import io.zer0.muse.web.WebServerConfig
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Los resultados del análisis de la "dirección de acceso" de la versión web.
 *
 * Sólo se describen la dirección y el rango visible, no el estado de ejecución. —— La persona que llama determina si el servicio se está ejecutando
 * `WebServer.isRunning` Obtener, en la configuración `enabled` No basta con representar el estado operativo real.
 */
internal data class WebAccessAddress(
    /** Direcciones de acceso disponibles;null Significa que el acceso a la LAN está habilitado pero no se obtiene la IP de la LAN. La persona que llama debería preguntar en lugar de dejarlo en blanco. */
    val url: String?,
    /** true Indica que actualmente solo es accesible para esta máquina ([WebServerConfig.allowLan] cerrar). */
    val localOnly: Boolean,
)

/** La dirección de bucle invertido utilizada para el acceso solo local. */
private const val LOOPBACK_HOST = "127.0.0.1"

/** Preferencia de nombre de tarjeta de red: tome el primer prefijo seleccionado en orden, primero el inalámbrico y el resto (incluidas las tarjetas de red virtuales) al final. */
private val LAN_INTERFACE_PREFIXES = listOf("wlan", "ap", "eth")

/**
 * Dirección de acceso a la asamblea:`http://<ip>:<port>`;[ip] Devuelve nulo si está vacío.
 *
 * Es una función pura, compartida por la página y la prueba única, y no lee el estado del sistema.
 */
internal fun buildWebAccessUrl(ip: String?, port: Int, https: Boolean = false): String? {
    val host = ip?.trim().orEmpty()
    if (host.isEmpty()) return null
    val scheme = if (https) "https" else "http"
    return "$scheme://$host:$port"
}

/**
 * Presione "Si abrir la LAN" para calcular la dirección de acceso que se mostrará en la página.
 *
 * - El acceso LAN está cerrado: la dirección de bucle invertido está fijada y marcada [WebAccessAddress.localOnly]
 * - El acceso LAN está habilitado: use IP LAN; cuando no se obtiene IP [WebAccessAddress.url] para null
 */
internal fun resolveWebAccessAddress(
    allowLan: Boolean,
    lanIp: String?,
    port: Int,
    https: Boolean = false,
): WebAccessAddress = if (allowLan) {
    WebAccessAddress(url = buildWebAccessUrl(lanIp, port, https), localOnly = false)
} else {
    WebAccessAddress(url = buildWebAccessUrl(LOOPBACK_HOST, port, https), localOnly = true)
}

/** Una dirección de candidato en una tarjeta de red. */
internal data class LanIpCandidate(
    /** Nombre de la tarjeta de red, como `wlan0`. */
    val interfaceName: String,
    /** Dirección IPv4 decimal con puntos. */
    val address: String,
    /** Si pertenece a un segmento de red privada (`siteLocal`). */
    val siteLocal: Boolean,
    /** Ya sea para hacer un bucle invertido en la dirección. */
    val loopback: Boolean,
)

/**
 * Elija un IPv4 que pueda usarse para el acceso LAN desde las direcciones candidatas.
 *
 * Reglas: excluir direcciones de bucle invertido, direcciones de segmentos de red no privadas y direcciones vacías; visitas al nombre de la tarjeta de red [LAN_INTERFACE_PREFIXES]
 * delante de la fila (`wlan` → `ap` → `eth`, una tarjeta de red virtual como `docker0` clasificado en último lugar);
 * Se mantiene el orden de enumeración del mismo archivo y se toma el primero disponible.
 */
internal fun pickLanIpAddress(candidates: List<LanIpCandidate>): String? {
    var best: LanIpCandidate? = null
    var bestRank = Int.MAX_VALUE
    for (candidate in candidates) {
        if (candidate.loopback || !candidate.siteLocal || candidate.address.isBlank()) continue
        val rank = interfaceRank(candidate.interfaceName)
        if (rank < bestRank) {
            best = candidate
            bestRank = rank
        }
    }
    return best?.address
}

/** El orden de preferencia de los nombres de las tarjetas de red; devuelto cuando no se alcanza la lista de preferencias. [Int.MAX_VALUE]. */
private fun interfaceRank(interfaceName: String): Int {
    val index = LAN_INTERFACE_PREFIXES.indexOfFirst { interfaceName.startsWith(it) }
    return if (index < 0) Int.MAX_VALUE else index
}

/** Enumere la dirección LAN IPv4 del dispositivo actual; devuelve nulo si no se puede obtener. */
internal fun resolveLanIpAddress(): String? =
    runCatching { pickLanIpAddress(collectLanIpCandidates()) }.getOrNull()

/** Recorra todas las tarjetas de red y recopile candidatos IPv4 sin bucle invertido(Se eliminará el sufijo de identificación de zona en la dirección.). */
private fun collectLanIpCandidates(): List<LanIpCandidate> {
    val result = mutableListOf<LanIpCandidate>()
    for (nic in NetworkInterface.getNetworkInterfaces().toList()) {
        if (!nic.isUp || nic.isLoopback) continue
        for (address in nic.inetAddresses.toList()) {
            val candidate = ipv4CandidateOf(nic, address)
            if (candidate != null) result += candidate
        }
    }
    return result
}

/** Convertir una dirección en la tarjeta de red en una candidata; devuelve nulo si no es IPv4 o no puede obtener una dirección con puntos. */
private fun ipv4CandidateOf(nic: NetworkInterface, address: InetAddress): LanIpCandidate? {
    val ipv4 = address as? Inet4Address ?: return null
    val host = ipv4.hostAddress?.substringBefore('%') ?: return null
    return LanIpCandidate(
        interfaceName = nic.name.orEmpty(),
        address = host,
        siteLocal = ipv4.isSiteLocalAddress,
        loopback = ipv4.isLoopbackAddress,
    )
}

/**
 * El límite del efecto secundario del cambio de versión web: escribir configuración + iniciar y detener servicios.
 *
 * El propósito de la interfaz de extracción es permitir que la lógica del conmutador realice afirmaciones sin Android/Ktor (consulte WebAccessToggleTest).
 */
internal interface WebAccessHost {
    /** Escribir configuración de persistencia. */
    suspend fun saveConfig(config: WebServerConfig)

    /** Inicie el servicio y regrese si tuvo éxito. */
    suspend fun startServer(): Boolean

    /** Detenga el servicio y libere el puerto. */
    suspend fun stopServer()
}

/**
 * Cambie a "Habilitar versión web": primero establezca la configuración y luego inicie o detenga el servicio según sea necesario.
 *
 * El servicio no se reiniciará si ya se está ejecutando; cuando el interruptor esté apagado, solo se detendrá cuando esté realmente en funcionamiento para evitar paradas innecesarias.
 *
 * @return El estado de ejecución real del servicio después del cambio.
 */
internal suspend fun applyWebAccessEnabled(
    host: WebAccessHost,
    current: WebServerConfig,
    enabled: Boolean,
    running: Boolean,
): Boolean {
    host.saveConfig(current.copy(enabled = enabled))
    if (!enabled) {
        if (running) host.stopServer()
        return false
    }
    if (running) return true
    return host.startServer()
}
