package io.zer0.muse.web

import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector

/**
 * Web La configuración del conector para el servicio.
 *
 * Utilice el motor CIO en lugar de Netty:Netty Hay muchos lugares que dependen de la reflexión (`ReflectiveChannelFactory` construir canal,
 * `ResourceLeakDetector` La inicialización estática busca métodos por nombre),R8 Al confundir los paquetes de lanzamiento, se eliminarán uno por uno.
 * Aparece como inicio `NoClassDefFoundError` / `does not have a public non-arg constructor`.
 * CIO Es una implementación pura de Kotlin, sin estas rutas de reflexión, y no introduce muchas dependencias adicionales en Netty.
 */
internal fun webServerEngineConfiguration(
    tls: WebServerTls.TlsMaterial?,
    bindHost: String,
    listenPort: Int,
): CIOApplicationEngine.Configuration.() -> Unit {
    return {
        if (tls != null) {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = tls.alias,
                keyStorePassword = { tls.password },
                privateKeyPassword = { tls.password },
            ) {
                host = bindHost
                port = listenPort
            }
        } else {
            connector {
                host = bindHost
                port = listenPort
            }
        }
    }
}
