package io.zer0.muse.web

import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 8.4: Web Entrada de resultados de búsqueda.
 *
 * @param title Título
 * @param url Enlace original(Se puede hacer clic)
 * @param snippet fragmento de resumen(truncado,Facilita la inyección de contexto LLM.)
 * @param source Fuente(provider nombre,Como por ejemplo "SearXNG"/"Tavily")
 */
@Serializable
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
)

/**
 * Phase 8.4: Servicio de búsqueda en Internet.
 *
 * Diseñado para soportar múltiples búsquedas provider,
 * abstracción unificada como [search] interfaz,Regresar [WebSearchResult] lista.
 *
 * Implementación actual:
 *  - [SearXNGProvider]: Metabuscador de código abierto,Autohospedado,No es necesario API key(Predeterminado endpoint = https://searx.be)
 *  - [TavilyProvider]: Búsqueda comercial de IA API,necesidad API key(https://tavily.com)
 *
 * Se puede ampliar más tarde: Brave Search / Google CSE / Serper espera(quedarse Phase 8.4+)
 *
 * Uso:
 *  - ChatViewModel Habilitado por el usuario"búsqueda en internet"tiempo,Ajustar primero [search] Obtenga los mejores N resultados
 *  - Formatee los resultados como un resumen de rebajas.,Inyectado como contexto del SISTEMA(prompt engineering)
 *  - LLM Respuesta basada en el contexto de Internet.,MarkdownText hace que se pueda hacer clic automáticamente en los enlaces de las respuestas generadas
 */
interface WebSearchService {
    /** provider logotipo(para configuración pantalla + registro). */
    val name: String

    /**
     * Realizar una búsqueda.
     * @param query palabra de consulta
     * @param maxResults Número máximo de devoluciones(Predeterminado 5)
     * @return Lista de resultados;Devuelve una lista vacía en caso de error.(El error ha sido log,No lanzar excepción)
     */
    suspend fun search(query: String, maxResults: Int = 5): List<WebSearchResult>

    /**
     * Buscar con opciones adicionales(date_range / time_period Filtrar por rango de tiempo igual).
     *
     * La implementación predeterminada ignora las opciones y delega directamente a [search],Se pueden anular proveedores específicos según sea necesario para respaldar
     * Filtrado avanzado como rango de tiempo. De esta forma, el antiguo proveedor puede ser compatible con nuevas llamadas sin modificaciones.
     *
     * @param options Opciones adicionales map,Puede contener rango de fecha/período de tiempo, etc. key
     */
    suspend fun searchWithOptions(query: String, maxResults: Int = 5, options: Map<String, String> = emptyMap()): List<WebSearchResult> =
        search(query, maxResults)
}

/**
 * SearXNG provider — Metabuscador de código abierto,Instancia pública predeterminada https://searx.be。
 *
 * Acuerdo: GET {endpoint}/search?q={query}&format=json&categories=general
 * Regresar: { results: [{ title, url, content, ... }] }
 *
 * Las instancias autohospedadas se pueden cambiar endpoint(Configurar en ajustes). Las instancias públicas pueden tener un flujo de corriente limitado/No disponible,
 * Por lo tanto, la degradación devuelve una lista vacía cuando falla.,No bloquees las conversaciones.
 */

/**
 * Web Configuración de búsqueda(persistir en SettingsRepository).
 *
 * @param providerName provider nombre("SearXNG" / "Tavily")
 * @param apiKey API key(Tavily Requerido,SearXNG ignorar)
 * @param endpoint Personalizar endpoint(SearXNG Instancia autohospedada/Agente de Tavily)
 * @param enabled Ya sea para habilitar"búsqueda en internet"cambiar(InputBar encender)
 */
@Serializable
enum class WebSearchMode {
    OFF,

    /** Prioridad automática: utilice la búsqueda nativa del modelo cuando sea compatible; de ​​lo contrario, utilice la cadena de búsqueda local. */
    AUTO,
    LOCAL,
    NATIVE,
}

/**
 * Motor de búsqueda definido por el usuario.
 *
 * Para "conectar su propio servicio de búsqueda": SearXNG autohospedado, puerta de enlace interna de la empresa, cualquier API de búsqueda que devuelva JSON.
 * No es necesario cambiar el código, simplemente rellena la plantilla y podrás acceder.
 *
 * @param id Identificación estable (para agregar y eliminar listas, no use nombres como identificadores, el cambio de nombre se interrumpirá)
 * @param name Nombre para mostrar, también utilizado como nombre del proveedor en el enlace
 * @param endpoint Dirección de solicitud completa (incluido el protocolo, excluyendo la cadena de consulta)
 * @param apiKey Opcional, inyectado como Portador si no está vacío
 * @param method GET o POST
 * @param queryParam GET nombre del parámetro de consulta, predeterminado q
 * @param bodyTemplate POST cuerpo de la solicitud (JSON texto,{query} será reemplazado,{count} Cambiar el número de resultados)
 * @param resultsPath La ruta de la matriz de resultados en el JSON de respuesta, como data.results/web.pages. Déjelo en blanco para indicar que la raíz es una matriz.
 * @param titlePath El nombre del campo del título en un solo resultado, predeterminado title
 * @param urlPath El nombre del campo del enlace en un único resultado, predeterminado url
 * @param snippetPath El nombre del campo del resumen en un solo resultado, predeterminado content
 * @param enabled Si habilitarlo (la configuración se mantendrá pero no se ingresará al enlace después de deshabilitarlo)
 */
@Serializable
data class CustomSearchEngine(
    val id: String = "",
    val name: String = "",
    val endpoint: String = "",
    val apiKey: String = "",
    val method: String = "GET",
    val queryParam: String = "q",
    val bodyTemplate: String = "",
    val resultsPath: String = "",
    val titlePath: String = "title",
    val urlPath: String = "url",
    val snippetPath: String = "content",
    val enabled: Boolean = true,
)

@Serializable
data class WebSearchConfig(
    /** Modo de búsqueda; De forma predeterminada, prioriza automáticamente la búsqueda nativa del modelo y luego recurre a la cadena API/HTTP local. */
    val mode: WebSearchMode = WebSearchMode.AUTO,
    val providerName: String = "Auto",
    val apiKey: String = "",
    val endpoint: String = "",
    /** v1 Campos compatibles:mode=OFF La sincronización horaria se considera desactivada; Las personas que llaman anteriormente todavía pueden leer habilitadas. */
    val enabled: Boolean = false,
    /** v2: El presupuesto de búsqueda de ronda única evita que el modelo se llame infinitamente en una respuesta. por defecto 50(en realidad déjalo ir,Solo ciclo anti-muertos). */
    val maxSearchesPerTurn: Int = 50,
    /** v2: El número máximo de resultados devueltos a la vez. */
    val maxResults: Int = 5,
    /** v2: provider Si se debe continuar usando la cadena de respaldo gratuita después de una falla. */
    val fallbackEnabled: Boolean = true,
    /** Busque la versión de configuración de la política. Si falta la configuración anterior, el almacén de configuración la migrará al valor predeterminado actual. */
    val policyVersion: Int = 0,
    /**
     * v1.135: Mapeo de claves API de múltiples motores de búsqueda(providerName → key).
     * suministro [AutoWebSearchService] Se utiliza al crear la cadena de respaldo del proveedor.
     * En modo de proveedor único [apiKey] Se sincronizará con la clave del proveedor actual.
     */
    val apiKeys: Map<String, String> = emptyMap(),
    /**
     * v2.6.10: Lista de motores de búsqueda definida por el usuario.
     *
     * Los motores personalizados ocupan el primer lugar en enlaces automáticos —— Se trata de un servicio prestado por los propios usuarios.
     * Más cerca de sus necesidades que cualquier rastreo gratuito integrado.
     */
    val customEngines: List<CustomSearchEngine> = emptyList(),
) {
    /**
     * M-04: Devuelve apiKey/apiKeys cifradas(ir [SecureKeyStore.encrypt])copia de,Por llamar antes que la perseverancia.
     * Los valores nulos se dejan como están.(No cifrar valores nulos). Presione WebServerConfig.modo cifrado.
     */
    suspend fun encrypted(): WebSearchConfig = copy(
        apiKey = SecureKeyStore.encrypt(apiKey),
        apiKeys = apiKeys.mapValues { SecureKeyStore.encrypt(it.value) },
    )

    /**
     * M-04: Devolver apiKey / apiKeys descifrados(ir [SecureKeyStore.decrypt])copia de,Se llama después de leer desde la capa de persistencia.
     * El antiguo texto sin formato se transmite de forma transparente mediante descifrado.(compatibles). Presione WebServerConfig.modo descifrado.
     *
     * v1.135 compatibles:La versión antigua sólo salvó una [apiKey],Sincronízalo aquí para [apiKeys] mapeo,
     * Permita seguir usando la clave configurada después de cambiar al modo automático.
     */
    suspend fun decrypted(): WebSearchConfig {
        val decryptedKey = SecureKeyStore.decrypt(apiKey)
        val decryptedKeys = apiKeys.mapValues { SecureKeyStore.decrypt(it.value) }
        val base = copy(
            apiKey = decryptedKey,
            apiKeys = decryptedKeys,
        )
        return if (
            base.providerName != "Auto" &&
            decryptedKey.isNotBlank() &&
            !decryptedKeys.containsKey(base.providerName)
        ) {
            base.copy(apiKeys = decryptedKeys + (base.providerName to decryptedKey))
        } else {
            base
        }
    }

    companion object {
        /**
         * Lista de proveedores admitidos(SettingsScreen Para menú desplegable).
         * v1.28: Trae el tuyo y quédate solo Bing(Gratis no es necesario API key),guarda otro"Personalizar API"
         * Opción para que los usuarios accedan a sus propias búsquedas API(SearXNG/Tavily Interfaz compatible).
         *
         * v1.98: Nuevo Perplexity(sonar-pro + citations),Empatado con Valiente/Exa/Bocha.
         */
        val SUPPORTED_PROVIDERS = listOf(
            "Auto",
            "Bing", "Jina", "Custom API",
            "SearXNG", "Tavily",
            "Zhipu", "Brave", "Serper", "Bocha", "Metaso", "Exa", "DeepSeek",
            "Perplexity",
        )

        /** v2.3.1: Nombre del motor API personalizado — Guardar en configuración(providerName)logotipo,No se puede cambiar a voluntad. */
        const val CUSTOM_PROVIDER_NAME: String = "Personalizar API"

        /** v2.3.1: El método de escritura histórica en inglés de API personalizada(Las configuraciones antiguas pueden almacenar este valor.). */
        const val CUSTOM_PROVIDER_NAME_EN: String = "Custom API"

        /** v2.3.1: Una colección de dos métodos de escritura para API personalizadas.(UI Se comparten la determinación de la cadena de deduplicación y reversión). */
        val CUSTOM_PROVIDER_NAMES: Set<String> = setOf(CUSTOM_PROVIDER_NAME, CUSTOM_PROVIDER_NAME_EN)

        /**
         * Colección de proveedores que requiere clave API(WebSearchSection Determine si se mostrará el cuadro de entrada de la clave API en función de esto).
         *
         * - "Personalizar API" Mostrado en chino en la copia de la interfaz de usuario,Ambos métodos de escritura se incluyen aquí para evitar omisiones.
         * - Bing / SearXNG / Jina(Sin clave, ve al nivel gratuito)No es necesario API Key,Por lo tanto no está en el set.
         * - Cubrir simultáneamente Brave / Perplexity / Exa / Bocha Espere todas las búsquedas comerciales. API
         */
        val PROVIDERS_NEEDING_API_KEY: Set<String> = setOf(
            CUSTOM_PROVIDER_NAME, CUSTOM_PROVIDER_NAME_EN,
            "Tavily",
            "Zhipu", "Brave", "Serper", "Bocha", "Metaso", "Exa",
            "Firecrawl", "Perplexity",
        )
    }
}

/**
 * Phase 8.4: Compartir OkHttpClient(para búsqueda web,Independiente del cliente ChatService,
 * Evite que el SSE de conexión larga y la búsqueda de conexión corta se afecten entre sí).
 */
fun createWebSearchClient(): OkHttpClient = OkHttpClient.Builder()
    // MuMu/Las rutas IPv6 en algunas redes domésticas a menudo se ECONNREFUSEN directamente; Se prefiere IPv4,IPv6 Todavía reservado como respaldo.
    .dns(object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
    })
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    // v2.6.9: Cambie a ProxyProvider dinámico (el cambio de proxy entra en vigor inmediatamente)
    .also { io.zer0.ai.core.ProxyProvider.applyTo(it) }
    .build()

/**
 * v2.6.0: Según [ProxyConfig] Configure el proxy y la autenticación de proxy para OkHttpClient.Builder.
 *
 * herramientas publicas —— Anteriormente privado para uso exclusivo en búsquedas web,Dando como resultado intercambios de tokens de OAuth, solicitudes de modelos, etc.
 * Los enlaces de red restantes están conectados directamente y no pueden pasar por el proxy global.(Prueba real del usuario:El navegador ha sido autorizado correctamente por el proxy.,Pero la aplicación token
 * Error en el intercambio,La región rechazó la conexión directa a auth.openai.com).
 */
fun OkHttpClient.Builder.applyProxy(config: ProxyConfig): OkHttpClient.Builder {
    if (!config.enabled || config.host.isBlank() || config.port <= 0) return this
    val address = InetSocketAddress.createUnresolved(config.host, config.port)
    val proxy = when (config.type.uppercase()) {
        "SOCKS", "SOCKS5" -> Proxy(Proxy.Type.SOCKS, address)
        else -> Proxy(Proxy.Type.HTTP, address)
    }
    proxy(proxy)
    if (config.username.isNotBlank() && config.password.isNotBlank()) {
        val credential = okhttp3.Credentials.basic(config.username, config.password)
        proxyAuthenticator { _, response ->
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
    }
    return this
}

/**
 * M-WS2: poner el bloqueado client.newCall(req).execute() Envuelto en una llamada suspendida cancelable.
 *
 * usar suspendCancellableCoroutine + enqueue + invokeOnCancellation{call.cancel()} modo,
 * Interrumpir llamadas de red bloqueadas cuando se cancela la rutina(de cada proveedor original execute() Bloqueará el hilo y no podrá responder a la cancelación.).
 * Para reutilización por parte de los proveedores de SearXNG/Tavily/Bing.
 */
internal suspend fun OkHttpClient.executeAsync(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { runCatching { call.cancel() } }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}

/**
 * L-WS1: HTML La eliminación de etiquetas se ha migrado a io.zer0.muse.util.HtmlUtils#stripHtmlSimple.
 */
