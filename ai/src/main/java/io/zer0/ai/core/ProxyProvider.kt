package io.zer0.ai.core

import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.6.9: proveedor de proxy global (ai La única fuente verdadera en el lado del módulo).
 *
 * ## por qué es necesario
 *
 * 30 ubicaciones en total `OkHttpClient.Builder()` Solía ​​haber sólo tres lugares donde se aplicaban proxies configurados por el usuario.
 * La consecuencia es que después de que el usuario está configurado con un agente, el chat puede pasar, pero el análisis de documentos MCP/canal/ASR/nube /
 * Estos canales en el mercado de complementos todavía están conectados directamente——El rendimiento es que "algunas funciones pueden acceder a Internet y otras no".
 *
 * ## ¿Por qué ahora podemos lograr que "El cambio de proxy surta efecto inmediatamente sin reiniciar"?
 *
 * antigua implementación `builder.proxy(proxy)`,prox​y Corregido en el momento de la construcción, esto es
 * La causa principal de "reiniciar al cambiar el proxy". Pero OkHttp [ProxySelector.select] **en cada construcción
 * Será llamado en nuevas conexiones.**. Entonces, en lugar de instalar un proxy fijo aquí, instale uno**Dinámico ProxySelector**:
 * Lee la configuración actual cada vez que selecciona. Una vez que cambie la configuración, el nuevo agente se utilizará la próxima vez que se establezca la conexión.
 *
 * Por lo tanto, no es necesario reconstruir el cliente, reiniciar ni reemplazar en caliente el singleton de Koin.
 *
 * Límites (declaración honesta):
 *  - Una conexión larga que se ha establecido y se está reutilizando (keep-alive) seguirá utilizando el antiguo proxy hasta que se recicle la conexión.
 *    esto es intencional——No debe interrumpir las solicitudes en curso para cambiar de proxy.
 *  - si en algún lugar**otra vez**Extra ajustado `builder.proxy(...)`,OkHttp El selector será ignorado,
 *    Ese tipo de lugar todavía necesita ser reiniciado. Así que no instale agentes fijos en ningún otro lugar.
 *
 * Seguridad del hilo: uso [AtomicReference], la lectura y la escritura no tienen bloqueos. La escritura se produce en los cambios de configuración (baja frecuencia),
 * La lectura ocurre cada vez que se establece una conexión (selector call), son operaciones extremadamente ligeras.
 */
object ProxyProvider {

    /** Descripción del agente. Datos puros, no dependen del ProxyConfig del módulo de la aplicación. */
    data class Descriptor(
        val enabled: Boolean,
        val type: String,
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        /** Hosts sin proxy (separados por comas, admite sufijos,`*` Comodines y prefijos de IP). */
        val bypassHosts: String = "",
    ) {
        val isUsable: Boolean
            get() = enabled && host.isNotBlank() && port in 1..65535

        /** construir java.net.Proxy(DNS Entregado al agente: use createUnresolved). */
        fun toJavaProxy(): Proxy {
            val address = InetSocketAddress.createUnresolved(host.trim(), port)
            return when (type.uppercase()) {
                "SOCKS", "SOCKS5" -> Proxy(Proxy.Type.SOCKS, address)
                else -> Proxy(Proxy.Type.HTTP, address)
            }
        }

        /**
         * Determine si un host debe omitir el proxy.
         *
         * Reglas de coincidencia:
         *  1. exactamente igual
         *  2. a `.` comienzo → coincidencia de sufijo (`.local` golpear `a.local`)
         *  3. Contiene `*` → comodín (`*.internal` golpear `x.internal`)
         *  4. a `.` finalizando → coincidencia de prefijo (`192.168.` golpear `192.168.1.5`)
         */
        fun shouldBypass(hostName: String): Boolean {
            val target = hostName.trim().lowercase()
            return bypassHosts.isNotBlank() &&
                target.isNotBlank() &&
                bypassHosts.split(',', ';', '\n')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .any { rule -> matchesRule(target, rule) }
        }

        private fun matchesRule(target: String, rule: String): Boolean = when {
            rule == target -> true
            rule.contains("*") ->
                Regex("^" + Regex.escape(rule).replace("\\*", ".*") + "$").matches(target)
            rule.startsWith(".") -> target.endsWith(rule) || target == rule.removePrefix(".")
            rule.endsWith(".") -> target.startsWith(rule)
            else -> false
        }
    }

    private val current = AtomicReference<Descriptor?>(null)

    private val noProxyList = listOf(Proxy.NO_PROXY)

    /**
     * Dinámico ProxySelector —— Lea la configuración actual cada vez que se establece una conexión.
     * Esta es la implementación principal de "cambiar el proxy sin reiniciar".
     */
    private object DynamicProxySelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val cfg = current.get()
            val useProxy = cfg != null &&
                cfg.isUsable &&
                !cfg.shouldBypass(uri.host.orEmpty())
            return if (useProxy && cfg != null) listOf(cfg.toJavaProxy()) else noProxyList
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // Si la conexión falla, no vuelva a intentarlo aquí: déjelo en manos del propio reintento y manejo de errores de la capa superior de OkHttp.
            // Evite reintentos ocultos en el selector, lo que puede llevar a un consumo de tiempo impredecible.
        }
    }

    /** Llamado por el lado de la aplicación: actualice el proxy actual (pase nulo o enabled=false significa conexión directa). Con efecto inmediato. */
    fun set(descriptor: Descriptor?) {
        current.set(descriptor)
    }

    /** Descripción del agente actual (puede ser nula). */
    fun get(): Descriptor? = current.get()

    /**
     * Instale el proxy dinámico en OkHttpClient.Builder.
     *
     * **Debe ajustarse dondequiera que se cree OkHttpClient.**. Nota: No realice ajustes adicionales
     * `builder.proxy(...)`, lo que invalidará el selector y devolverá "es necesario reiniciar para cambiar el proxy".
     */
    fun applyTo(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        builder.proxySelector(DynamicProxySelector)
        // Las credenciales no se pueden colocar en el selector (selector Seleccione solo proxy), por autenticador en
        // La autorización de proxy se agrega cuando se recibe 407; y cada vez que se lea la configuración actual, el cambio de contraseña entrará en vigor.
        builder.proxyAuthenticator { _, response ->
            val cfg = current.get()
            when {
                cfg == null || !cfg.isUsable -> null
                cfg.username.isBlank() || cfg.password.isBlank() -> null
                // El encabezado de autenticación aún está adjunto 407 → Si las credenciales son incorrectas, desiste y evita un bucle sin fin.
                response.request.header("Proxy-Authorization") != null -> null
                else -> response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(cfg.username, cfg.password))
                    .build()
            }
        }
        return builder
    }

    /** Construcción de conveniencia: cree un cliente basado en el proxy actual. */
    fun buildClient(configure: OkHttpClient.Builder.() -> Unit = {}): OkHttpClient =
        applyTo(OkHttpClient.Builder().apply(configure)).build()
}
