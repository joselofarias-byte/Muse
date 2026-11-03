package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.tools.script.SkillBridgeHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Una respuesta limitada devuelta por una salida controlada;[body] Hasta el límite superior de bytes proporcionado a petición. */
internal data class CatalogHttpResponse(
    val status: Int,
    val body: ByteArray,
)

/**
 * Capa de transporte de extracción de directorio.
 *
 * Encomienda de realización de producción [SkillBridgeHttpClient](DNS Fijo + verificación de redirección salto a salto + revisión de ruta directa);
 * Las pruebas unitarias inyectan transferencias falsas para cubrir el tamaño/tiempo de espera/Las políticas del cliente, como los códigos de estado, no dependen de la red.
 */
internal fun interface CatalogHttpTransport {
    /** @throws Exception La transferencia falló (DNS/enrutamiento/tiempo de espera/IO). */
    fun getBytes(url: String, maxBytes: Int): CatalogHttpResponse
}

/** Obtener resultados;[signedCatalog] Solo es necesario enviar la carga útil analizada al verificador de directorio para la verificación de la firma. */
internal data class CatalogFetchResult(
    val signedCatalog: SignedPluginCatalog? = null,
    val error: String? = null,
) {
    val success: Boolean get() = signedCatalog != null
}

/**
 * Plugin Directory Client: extrae de una URL de directorio configurable `SignedPluginCatalog` JSON.
 *
 * Estrategia y [PluginDownloadClient] Alineación:
 *  - solo aceptar `https://` Dirección del directorio (el contenido del directorio en sí aún debe pasar la verificación de firma,https Sólo protección adicional de la transmisión);
 *  - Iniciar solicitudes a través de la salida HTTP controlada por el proyecto, que es responsable de la fijación de DNS, la verificación salto a salto y el tiempo de espera;
 *  - leer límite superior [maxCatalogBytes], rechace directamente el exceso de límite en lugar de truncarlo y analizarlo silenciosamente;
 *  - Un directorio no configurado (URL vacía) devuelve un error explícito y nunca se devuelve un directorio vacío para pretender estar disponible.
 */
internal class PluginCatalogClient(
    private val transport: CatalogHttpTransport = SkillBridgeTransport(),
    private val maxCatalogBytes: Int = MAX_CATALOG_BYTES,
) {

    fun fetch(catalogUrl: String): CatalogFetchResult {
        val url = catalogUrl.trim()
        if (url.isEmpty()) return CatalogFetchResult(error = "El directorio de complementos no está configurado")
        if (!url.startsWith(HTTPS_PREFIX)) {
            return CatalogFetchResult(error = "Se debe utilizar la dirección del directorio https://")
        }
        return try {
            // Leer 1 byte más: la capa de transporte se trunca según el límite superior. Si la respuesta excede el límite superior, significa que la respuesta real excede el límite y debe ser rechazada.
            val response = transport.getBytes(url, maxCatalogBytes + 1)
            if (response.status !in 200..299) {
                return CatalogFetchResult(error = "Error en la solicitud de directorio: HTTP ${response.status}")
            }
            val bytes = response.body
            if (bytes.size > maxCatalogBytes) {
                return CatalogFetchResult(error = "El tamaño del directorio excede el límite")
            }
            val signed = AppJson.decodeFromString(
                SignedPluginCatalog.serializer(),
                bytes.toString(Charsets.UTF_8),
            )
            CatalogFetchResult(signedCatalog = signed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: SerializationException) {
            Logger.w(TAG, "Falló el análisis JSON del directorio: ${error.message}")
            CatalogFetchResult(error = "El contenido del directorio no es un directorio de firmas legal. JSON")
        } catch (error: Exception) {
            Logger.w(TAG, "Error al extraer el directorio: ${error.message}", error)
            CatalogFetchResult(error = error.message ?: "Error al extraer el directorio")
        }
    }

    /**
     * Transporte de producción: salida HTTP controlada + tiempo de espera específico del directorio.
     *
     * La salida en sí es la única implementación permitida para iniciar solicitudes; aquí solo el tiempo de espera (conexión/leer/llamar en su conjunto),
     * DNS Se conservan la verificación de redirección salto a salto fija y la verificación de ruta SSRF.
     */
    private class SkillBridgeTransport : CatalogHttpTransport {
        private val client = SkillBridgeHttpClient(
            baseClient = io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build(),
        )

        override fun getBytes(url: String, maxBytes: Int): CatalogHttpResponse {
            val result = client.getBytes(url, maxBytes)
            return CatalogHttpResponse(status = result.status, body = result.body)
        }
    }

    companion object {
        private const val TAG = "PluginCatalogClient"
        private const val HTTPS_PREFIX = "https://"

        /**
         * Tapa del catálogo JSON; con `PluginCatalogVerifier` El MAX_CATALOG_BYTES sigue siendo consistente,
         * Evite que los clientes pasen cargas útiles que el validador necesariamente rechazará.
         */
        internal const val MAX_CATALOG_BYTES = 512 * 1024

        internal const val CONNECT_TIMEOUT_MS = 10_000L
        internal const val READ_TIMEOUT_MS = 15_000L
        internal const val CALL_TIMEOUT_MS = 20_000L
    }
}
