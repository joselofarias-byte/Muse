package io.zer0.muse.web

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.util.stripHtmlSimple
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * La búsqueda web de Baidu te lo dice todo.
 *
 * En el entorno de red nacional de Android,Bing/Jina/SearXNG Puede bloquearse mediante redirección, autenticación o enrutamiento al mismo tiempo;
 * Por lo general, todavía se puede acceder a las páginas de Baidu en dichas redes. Aquí solo se analiza la página de resultados pública y no se cargan datos distintos de las consultas de los usuarios.
 */
private fun Response.searchDiagnostic(): String {
    var redirects = 0
    var previous = priorResponse
    while (previous != null) {
        redirects++
        previous = previous.priorResponse
    }
    return "code=$code, finalHost=${request.url.host}, redirects=$redirects, " +
        "contentType=${header("Content-Type")?.substringBefore(';') ?: "unknown"}, " +
        "contentLength=${header("Content-Length") ?: "unknown"}"
}

class BaiduProvider(
    private val client: OkHttpClient,
) : WebSearchService {
    override val name: String = "Baidu"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        resultOf {
            val url = "https://www.baidu.com/s?wd=" + java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
            client.executeAsync(request).use { response ->
                if (!response.isSuccessful) {
                    Logger.w(name, "search failed: ${response.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(response.body.string(), maxResults)
            }
        }.onError { msg, t -> Logger.w(name, "search error: $msg", t) }
            .getOrNull()
            ?: emptyList()
    }

    private fun parseResults(body: String, maxResults: Int): List<WebSearchResult> {
        val document = Jsoup.parse(body)
        return document.select("div.result, div.c-container").asSequence()
            .mapNotNull { item ->
                val anchor = item.selectFirst("h3 a, a[data-click]") ?: return@mapNotNull null
                val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?: return@mapNotNull null
                val snippet = item.selectFirst(".c-span-last, .c-color-text, .c-font-normal, .content-right_8Zs40")
                    ?.text()?.trim().orEmpty()
                WebSearchResult(
                    title = title,
                    url = href,
                    snippet = snippet.take(300),
                    source = name,
                )
            }
            .distinctBy { it.url }
            .take(maxResults)
            .toList()
    }
}

class SearXNGProvider(
    private val client: OkHttpClient,
    private val endpoint: String = "https://searx.be",
) : WebSearchService {
    override val name: String = "SearXNG"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        // H-WS1: Utilice resultOf en su lugar runCatching,evitar tragar CancellationException
        resultOf {
            val url = "$endpoint/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&format=json&categories=general&language=zh-CN"
            val req = Request.Builder().url(url)
                .header("User-Agent", "muse/1.0 (Android LLM client)")
                .header("Accept", "application/json")
                .get().build()
            // M-WS2: usar executeAsync(enqueue + suspendCancellableCoroutine)bloqueo alternativo execute()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("SearXNG", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                val body = resp.body.string()
                parseResults(body, maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("SearXNG", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * Tavily provider — AI Buscar API(https://tavily.com),Requiere clave API.
 *
 * Acuerdo: POST https://api.tavily.com/search
 * Cuerpo de la solicitud: { "api_key": "...", "query": "...", "max_results": N, "search_depth": "basic" }
 * Regresar: { results: [{ title, url, content, score }] }
 *
 * API key Configurar en ajustes. Cuando no hay ninguna clave, se devuelve directamente una lista vacía.
 */
class TavilyProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://api.tavily.com",
) : WebSearchService {
    override val name: String = "Tavily"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Tavily", "no API key configured, skip")
            return@withContext emptyList()
        }
        // H-WS1: Utilice resultOf en su lugar runCatching,evitar tragar CancellationException
        resultOf {
            // Phase 8.5 Reparación: Utilice buildJsonObject para construir el cuerpo de la solicitud,Evite escapar de errores al concatenar manualmente cadenas JSON.
            // La implementación original solo se ocupaba de las comillas dobles de la consulta. replace,pero:
            //  1. apiKey sin escapar(Si está incluido " o \ destruirá JSON)
            //  2. query Las barras invertidas no se escapan.(Originales `\` será comido)
            //  3. nueva línea/Los caracteres de control, como las pestañas, no tienen escape.(JSON Las cadenas no permiten caracteres de control literal)
            // Utilice kotlinx.serialization en su lugar buildJsonObject,Todo el escape de cadenas es manejado por la biblioteca de serialización.
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("api_key", kotlinx.serialization.json.JsonPrimitive(apiKey))
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("max_results", kotlinx.serialization.json.JsonPrimitive(maxResults))
                put("search_depth", kotlinx.serialization.json.JsonPrimitive("basic"))
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            // M-WS2: usar executeAsync(enqueue + suspendCancellableCoroutine)bloqueo alternativo execute()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Tavily", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                val body = resp.body.string()
                parseResults(body, maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Tavily", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.135: Auto Buscar Provider — Respaldo multimotor + detección de resultados de baja calidad.
 *
 * diseño:
 *  - Pruebe varios en orden de prioridad provider,Se encontró un resultado vacío./baja calidad/Si hay una excepción, automáticamente cambiará a la siguiente.
 *  - Detectar calculadora de fecha, diccionario/Se omiten las explicaciones de la enciclopedia y otros resultados de baja calidad.
 *  - Los resultados finales se deduplican y se ordenan por relevancia de la consulta.
 *
 * Provider prioridad:
 *  1. El usuario ha configurado la clave API comercial provider(Tavily/Brave/Serper/Zhipu/Bocha/Metaso/Exa/Firecrawl/Perplexity)
 *  2. gratis/Gratis key provider: Bing → Jina → SearXNG
 */
/**
 * DeepSeek Busque la descripción predeterminada de la API.
 *
 * POST /v1/tools/search, regresar { results: [{ title, url, summary, ... }] }.
 * forma y [GenericSearchProvider] Combina perfectamente, por lo que en lugar de escribir una clase separada, reutilizamos directamente el adaptador universal.
 *
 * need_content=false: El resumen oficial en sí ya tiene entre 600 y 800 palabras, y ocupar toda la página de contenido abrumará el contexto.
 */
internal val DEEPSEEK_SEARCH_ENGINE =
    CustomSearchEngine(
        id = "preset_deepseek_search",
        name = "DeepSeek",
        endpoint = "https://api.deepseek.com/v1/tools/search",
        method = "POST",
        bodyTemplate = "{\"query\":\"{query}\",\"count\":{count},\"need_content\":false}",
        resultsPath = "results",
        titlePath = "title",
        urlPath = "url",
        // summary Más completo que el fragmento, recurra cuando no esté disponible
        snippetPath = "summary",
        enabled = true,
    )

class AutoWebSearchService(
    private val client: OkHttpClient,
    config: WebSearchConfig,
) : WebSearchService {
    override val name: String = "Auto"

    @Volatile
    private var config: WebSearchConfig = config

    /** Actualizar la configuración en tiempo de ejecución,Reemplace sincrónicamente el estado interno. */
    fun updateConfig(newConfig: WebSearchConfig) {
        synchronized(this) {
            config = newConfig
        }
    }

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        // Comprobación previa del límite de velocidad global
        if (!SearchRateLimiter.await(name)) {
            throw SearchRateLimitException(
                cause = "$name rate-limited (await=false)",
                retryAfterMs = SearchRateLimiter.getRetryAfterMs(name),
            )
        }

        val currentConfig = config
        val chain = buildProviderChain(currentConfig)
        if (chain.isEmpty()) {
            Logger.w("AutoWebSearch", "no provider available")
            return emptyList()
        }

        val attempts = mutableListOf<String>()
        var firstLowQualityResults: List<WebSearchResult>? = null

        for (provider in chain) {
            val providerName = provider.name
            try {
                // Verificación previa del límite de velocidad de un solo proveedor
                if (!SearchRateLimiter.await(providerName)) {
                    attempts.add("$providerName: rate-limited")
                    continue
                }
                val results = provider.search(query, maxResults)
                if (results.isEmpty()) {
                    attempts.add("$providerName: empty")
                    continue
                }
                if (isLikelyLowQualityResults(query, results)) {
                    attempts.add("$providerName: low-quality")
                    if (firstLowQualityResults == null) {
                        firstLowQualityResults = results
                    }
                    continue
                }
                attempts.add("$providerName: ok(${results.size})")
                Logger.i("AutoWebSearch", "query='$query' attempts=$attempts")
                return normalizeResults(query, results, maxResults)
            } catch (e: SearchRateLimitException) {
                attempts.add("$providerName: rate-limit")
            } catch (e: Exception) {
                attempts.add("$providerName: error(${e.message})")
                Logger.w("AutoWebSearch", "$providerName failed", e)
            }
        }

        // Todas las alternativas fallaron o fueron de baja calidad.:Vuelva a los resultados de baja calidad que obtuvo por primera vez,Evite ir completamente con las manos vacías
        if (firstLowQualityResults != null) {
            Logger.w("AutoWebSearch", "query='$query' all low-quality, returning first attempt. attempts=$attempts")
            return normalizeResults(query, firstLowQualityResults, maxResults)
        }

        Logger.w("AutoWebSearch", "query='$query' all failed. attempts=$attempts")
        return emptyList()
    }

    private fun buildProviderChain(cfg: WebSearchConfig): List<WebSearchService> {
        val chain = mutableListOf<WebSearchService>()

        // v2.6.10: Los motores definidos por el usuario ocupan el primer lugar.
        // Este es su propio servicio de recuperación, que se acerca más a las necesidades que cualquier rastreador integrado;
        // Un motor desactivado conserva su configuración pero no ingresa al enlace.
        cfg.customEngines
            .filter { it.enabled && it.endpoint.isNotBlank() }
            .forEach { engine -> chain.add(GenericSearchProvider(client, engine)) }

        // Las API de búsqueda configuradas por el usuario tienen prioridad sobre los rastreos gratuitos.apiKeys es un nuevo formato,apiKey es el formato antiguo;
        // El formato antiguo solo recurre cuando nombreProveedor apunta al proveedor, para evitar enviar accidentalmente una clave a otros proveedores.
        fun keyFor(provider: String): String = cfg.apiKeys[provider].orEmpty().ifBlank {
            cfg.apiKey.takeIf { cfg.providerName.equals(provider, ignoreCase = true) }.orEmpty()
        }
        fun addIfConfigured(provider: String, factory: (String) -> WebSearchService) {
            keyFor(provider).takeIf { it.isNotBlank() }?.let { chain.add(factory(it)) }
        }
        addIfConfigured("Tavily") { TavilyProvider(client, it) }
        addIfConfigured("Perplexity") { PerplexitySearchProvider(client, it) }
        addIfConfigured("Brave") { BraveSearchProvider(client, it) }
        addIfConfigured("Serper") { SerperSearchProvider(client, it) }
        addIfConfigured("Zhipu") { ZhipuSearchProvider(client, it) }
        addIfConfigured("Bocha") { BochaSearchProvider(client, it) }
        addIfConfigured("Metaso") { MetasoSearchProvider(client, it) }
        addIfConfigured("Exa") { ExaSearchProvider(client, it) }
        addIfConfigured("Firecrawl") { FirecrawlProvider(client, it) }
        addIfConfigured("Jina") { JinaProvider(client, it) }
        // v2.6.10: DeepSeek La búsqueda también accede a la cadena Auto
        keyFor("DeepSeek").takeIf { it.isNotBlank() }?.let { key ->
            chain.add(GenericSearchProvider(client, DEEPSEEK_SEARCH_ENGINE.copy(apiKey = key)))
        }
        // SearXNG autohospedado es un servicio de búsqueda propiedad del usuario que tiene prioridad sobre el rastreo público gratuito.
        if (cfg.endpoint.isNotBlank() && cfg.providerName.equals("SearXNG", ignoreCase = true)) {
            chain.add(SearXNGProvider(client, cfg.endpoint))
        }
        if (cfg.providerName.equals("Custom API", ignoreCase = true) && cfg.apiKey.isNotBlank()) {
            chain.add(TavilyProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.tavily.com" }))
        }

        // Finalmente opte por el rastreo HTTP gratuito. Android WebView no se inicia aquí ni se utiliza el kernel del navegador celular.
        chain.add(BingProvider(client))
        chain.add(BaiduProvider(client))

        // Permitir que los usuarios avanzados desactiven el respaldo automático; está habilitado de forma predeterminada y los usuarios normales pueden utilizar toda la cadena gratuita sin configuración.
        return if (cfg.fallbackEnabled) chain else chain.take(1)
    }

    companion object {
        /** Dominios conocidos de baja calidad(Calculadora de fecha, conversión de unidades y otras estaciones de herramientas). */
        private val LOW_QUALITY_DOMAINS = setOf(
            "timeanddate.com",
            "datecalculator.org",
            "calculator.net",
            "calculatorsoup.com",
            "unitconverters.net",
            "rapidtables.com",
            "datetime360.com",
            "timedatecalc.com",
        )

        /** diccionario chino/Palabras clave del sitio de interpretación. */
        private val DICTIONARY_PATTERNS = listOf("zdic.net", "Código Han", "diccionario", "Diccionario", "Explicación básica", "significado chino")

        /** Palabras clave de la calculadora de fechas. */
        private val DATE_CALC_PATTERNS = listOf(
            "date calculator",
            "days calculator",
            "age calculator",
            "day calculator",
            "datecalculator",
            "dayscalculator",
            "hours calculator",
            "time calculator",
        )

        private fun hasCjk(text: String): Boolean = text.any { it in '\u3400'..'\u9fff' }

        private fun isLowQualityResult(query: String, result: WebSearchResult): Boolean {
            val lowerUrl = result.url.lowercase()
            val lowerTitle = result.title.lowercase()
            val lowerSnippet = result.snippet.lowercase()
            val combined = "$lowerTitle $lowerSnippet $lowerUrl"

            // Fecha/tiempo/sitio de calculadora de edad
            if (DATE_CALC_PATTERNS.any { combined.contains(it) }) return true
            if (LOW_QUALITY_DOMAINS.any { lowerUrl.contains(it) }) return true

            // Al consultar en chino,Diccionario/Código Han/Parafrasear los resultados suele ser irrelevante(A menos que el usuario busque palabras explícitamente)
            if (hasCjk(query) && DICTIONARY_PATTERNS.any { combined.contains(it) }) return true

            return false
        }

        /**
         * Determinar si un conjunto de resultados es de baja calidad en general.
         * heurístico:top 3 Al menos 2 de las entradas cumplen con las reglas de baja calidad.,Se considera que los resultados de la consulta actual del proveedor no están disponibles.
         */
        private fun isLikelyLowQualityResults(query: String, results: List<WebSearchResult>): Boolean {
            if (results.isEmpty()) return true
            val topN = results.take(3)
            val lowQualityCount = topN.count { isLowQualityResult(query, it) }
            return lowQualityCount >= 2
        }

        /** Calcular la puntuación de relevancia del resultado para la consulta.,utilizado para clasificar. */
        private fun scoreResult(query: String, result: WebSearchResult): Int {
            val terms = query.split(Regex("\\s+")).filter { it.length > 1 }
            val lowerTitle = result.title.lowercase()
            val lowerSnippet = result.snippet.lowercase()
            var score = 0
            terms.forEach { term ->
                val lowerTerm = term.lowercase()
                if (lowerTitle.contains(lowerTerm)) score += 3
                if (lowerSnippet.contains(lowerTerm)) score += 1
            }
            if (result.snippet.isBlank()) score -= 3
            if (result.title.isBlank()) score -= 5
            return score
        }

        /**
         * Resultados normalizados:Filtrar campos vacíos, deduplicar, eliminar baja calidad, ordenar por relevancia, truncar.
         */
        private fun normalizeResults(query: String, results: List<WebSearchResult>, maxResults: Int): List<WebSearchResult> = results
            .asSequence()
            .filter { it.title.isNotBlank() && it.url.isNotBlank() }
            .filter { !isLowQualityResult(query, it) }
            .distinctBy { it.url.lowercase().trimEnd('/') }
            .sortedByDescending { scoreResult(query, it) }
            .take(maxResults)
            .toList()
    }
}

/**
 * Servicio de búsqueda compuesta — Distribuido a implementaciones específicas según el nombre del proveedor.
 *
 * por [WebSearchConfig] conducir:settings Configuración providerName / apiKey / endpoint,
 * Reconstruir la instancia subyacente al cambiar de proveedor(evitar sostener stale client).
 */
class CompositeWebSearchService(
    private val client: OkHttpClient,
    config: WebSearchConfig,
) : WebSearchService {
    override val name: String get() = config.providerName

    /**
     * La configuración actualmente efectiva y el proveedor específico.
     * Phase 8.5 Reparación:usar `@Volatile var config` + sincronizar `updateConfig`,
     * sustituto original `by lazy` Contado solo una vez + implementación vacía updateConfig,Habilite el cambio de proveedor de tiempo de ejecución para que surta efecto.
     */
    @Volatile
    private var config: WebSearchConfig = config

    @Volatile
    private var delegate: WebSearchService = buildDelegate(config)

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        // Verificación previa del límite de velocidad: lanza SearchRateLimitException directamente durante el período de suspensión sin iniciar una solicitud para evitar colisiones repetidas con la prohibición de 429/402.
        if (!SearchRateLimiter.await(name)) {
            throw SearchRateLimitException(
                cause = "$name rate-limited (await=false)",
                retryAfterMs = SearchRateLimiter.getRetryAfterMs(name),
            )
        }
        return delegate.search(query, maxResults)
    }

    /** Configuración del interruptor(Cambiar en tiempo de ejecución provider/apiKey/endpoint). Reconstrucción sincrónica delegate,Evite los clientes obsoletos. */
    fun updateConfig(newConfig: WebSearchConfig) {
        synchronized(this) {
            config = newConfig
            delegate = buildDelegate(newConfig)
        }
    }

    private fun buildDelegate(cfg: WebSearchConfig): WebSearchService = Companion.buildDelegate(client, cfg)

    companion object {
        /**
         * Construir la búsqueda correspondiente según la configuración. Provider(Para que el botón de prueba llame directamente,Evite los límites de velocidad con stale config).
         * Utilice lo último en interfaz de usuario WebSearchConfig,correspondencia de instanciación directa Provider,No atraviesa la capa Composite.
         */
        fun buildDelegate(client: OkHttpClient, cfg: WebSearchConfig): WebSearchService {
            return when (cfg.providerName) {
                // v1.135: Auto multimotor fallback
                "Auto" -> AutoWebSearchService(client, cfg)
                "Bing" -> BingProvider(client)
                "Jina" -> JinaProvider(client, cfg.apiKey)
                // M-WS3: Personalizar API — Distribución secundaria basada en si apiKey no está vacía:
                //  Hay una interfaz compatible con apiKey y Tavily,Utilice la interfaz compatible con SearXNG sin apiKey(Originalmente asignado incondicionalmente a SearXNG,ignorar apiKey)
                "Custom API" ->
                    if (cfg.apiKey.isNotBlank()) {
                        TavilyProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.tavily.com" })
                    } else {
                        SearXNGProvider(client, cfg.endpoint.ifBlank { "https://searx.be" })
                    }
                "SearXNG" -> SearXNGProvider(client, cfg.endpoint.ifBlank { "https://searx.be" })
                "Tavily" -> TavilyProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.tavily.com" })
                // v1.97: Agregar búsqueda provider
                "Zhipu" -> ZhipuSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://open.bigmodel.cn/api/paas/v4" })
                // v2.6.10: DeepSeek Buscar —— Reutilizar el adaptador universal
                "DeepSeek" ->
                    GenericSearchProvider(
                        client,
                        DEEPSEEK_SEARCH_ENGINE.copy(apiKey = cfg.apiKey),
                    )
                "Brave" -> BraveSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.search.brave.com/res/v1" })
                "Serper" -> SerperSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://google.serper.dev" })
                "Bocha" -> BochaSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.bochaai.com/v1" })
                "Metaso" -> MetasoSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://metaso.cn/api/v1" })
                "Exa" -> ExaSearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.exa.ai" })
                "Firecrawl" -> FirecrawlProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.firecrawl.dev/v1" })
                // Perplexity AI Buscar API(sonar-pro modelo + citations)
                "Perplexity" -> PerplexitySearchProvider(client, cfg.apiKey, cfg.endpoint.ifBlank { "https://api.perplexity.ai" })
                // Usado por defecto Bing HTTP(gratis,No es necesario API key)
                else -> BingProvider(client)
            }
        }
    }
}

/**
 * v0.33: Bing HTML Buscar Provider (v1.131 Versión optimizada).
 *
 * darse cuenta:GET https://www.bing.com/search?q={query} (Raspado HTML de Bing real)
 * - No es necesario API key,Obtención a través de HTML + Análisis con Jsoup
 * - Consejos clave:
 *   1) Cookie `SRCHHPGUSR=ULSR=1&SRCHLANG=zh-CN` + `_SS=...` Deje que Bing regrese estable y analizable HTML
 *   2) URL agregar `mkt=zh-CN&setlang=zh-CN&ensearch=1` Evite redireccionamientos regionales a cn.bing.com
 *   3) Uso de Chrome UA de escritorio + encabezados de solicitud completos,Emule navegadores de escritorio para obtener resultados con bloques b_algo HTML
 *   4) Selector de fragmentos de varios niveles(.b_caption p → .b_lineclamp4 → .b_focusTextLarge),
 *      Lidiando con las diversas estructuras de bloques de resultados de Bing HTML
 *   5) Saltar bloques de resultados no naturales(b_algo b_algo_default b_ans / b_card bypass)
 * - Analizar con Jsoup(OkHttp Solicitud para mantener un proxy consistente con un cliente existente/Configuración de tiempo de espera)
 *
 * reglas de análisis:
 *  - selector principal: `li.b_algo:not(.b_algo_default):not(.b_card)` Tome el bloque de resultados naturales
 *  - enlace del título: `h2 > a`(Señor) / `h2 a`(Ten todo en cuenta)
 *  - snippet: `.b_caption p` / `.b_caption .b_paractl` / `.b_lineclamp4` / `.b_focusTextLarge`
 */
class BingProvider(
    private val client: OkHttpClient,
) : WebSearchService {
    override val name: String = "Bing"

    /** Utilice la búsqueda HTTP HTML del portal de China; No inicie Android WebView. */
    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        resultOf {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://cn.bing.com/search?q=$encoded&mkt=zh-CN&setlang=zh-CN"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            client.executeAsync(request).use { response ->
                if (!response.isSuccessful) {
                    Logger.w(name, "HTTP search failed: ${response.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseHttpResults(response.body.string(), maxResults)
            }
        }.onError { msg, t -> Logger.w(name, "HTTP search error: $msg", t) }
            .getOrNull()
            ?: emptyList()
    }

    private fun parseHttpResults(body: String, maxResults: Int): List<WebSearchResult> {
        val document = Jsoup.parse(body)
        return document.select("li.b_algo").asSequence()
            .mapNotNull { item ->
                val anchor = item.selectFirst("h2 a") ?: return@mapNotNull null
                val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = anchor.absUrl("href").takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
                val snippet = item.selectFirst(".b_caption p, .b_lineclamp4, .b_focusTextLarge")
                    ?.text()?.trim().orEmpty()
                WebSearchResult(title, url, snippet.take(300), name)
            }
            .distinctBy { it.url }
            .take(maxResults)
            .toList()
    }
}

/**
 * Jina AI Reader Search Provider — Basado en Jina AI Reader Search API(https://s.jina.ai/).
 *
 * Acuerdo: POST https://s.jina.ai/
 *  - Encabezado de solicitud: Authorization: Bearer {apiKey}(Opcional,Sin clave, nivel gratuito pero con límite de tarifa)
 *  - Cuerpo de la solicitud JSON: {"q": query, "num": maxResults}
 *  - respuesta JSON: {"data": [{"title", "url", "description", "content"}]}
 *
 * snippet tomar description,Si está vacío, utilice las primeras 200 palabras del contenido para resumir.
 * Se puede utilizar sin una clave API(El nivel gratuito tiene límites de tarifas),apiKey Vacío sin encabezado de Autorización.
 */
class JinaProvider(
    private val client: OkHttpClient,
    private val apiKey: String = "",
) : WebSearchService {
    override val name: String = "Jina"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        val endpoints = listOf("https://s.jinaai.cn/", "https://s.jina.ai/")
        for (endpoint in endpoints) {
            val result = resultOf {
                val payload = kotlinx.serialization.json.buildJsonObject {
                    put("q", kotlinx.serialization.json.JsonPrimitive(query))
                    put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
                }.toString()
                val reqBuilder = Request.Builder().url(endpoint)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                if (apiKey.isNotBlank()) reqBuilder.header("Authorization", "Bearer $apiKey")
                val req = reqBuilder.post(payload.toRequestBody("application/json".toMediaType())).build()
                client.executeAsync(req).use { resp ->
                    SearchRateLimiter.assertNotRateLimited(name, resp)
                    if (!resp.isSuccessful) {
                        Logger.w("Jina", "search failed: ${resp.searchDiagnostic()} endpoint=$endpoint")
                        emptyList()
                    } else {
                        parseResults(resp.body.string(), maxResults)
                    }
                }
            }.onError { _, t ->
                if (t is SearchRateLimitException) throw t
                Logger.w("Jina", "search error endpoint=$endpoint", t)
            }.getOrNull().orEmpty()
            if (result.isNotEmpty()) return@withContext result
        }
        emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["data"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // snippet tener prioridad description,Si está vacío, utilice las primeras 200 palabras del contenido para obtener los detalles.
            val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val snippet = if (description.isNotBlank()) description else content.take(200)
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Búsqueda del espectro de sabiduría Provider — API de búsqueda web Zhipu AI.
 *
 * Acuerdo: POST https://open.bigmodel.cn/api/paas/v4/web_search
 *  - Encabezado de solicitud: Authorization: Bearer {apiKey}
 *  - Cuerpo de la solicitud: {"search_query": query, "num": maxResults}
 *  - respuesta: {"search_result": [{"title", "link", "content"}]}
 */
class ZhipuSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://open.bigmodel.cn/api/paas/v4",
) : WebSearchService {
    override val name: String = "Zhipu"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Zhipu", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("search_query", kotlinx.serialization.json.JsonPrimitive(query))
                put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
            }.toString()
            val req = Request.Builder().url("$endpoint/web_search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Zhipu", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Zhipu", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["search_result"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Brave Search Provider — Brave API de búsqueda.
 *
 * Acuerdo: GET https://api.search.brave.com/res/v1/web/search?q={query}&count={max}
 *  - Encabezado de solicitud: X-Subscription-Token: {apiKey}
 *  - respuesta: {"web": {"results": [{"title", "url", "description"}]}}
 */
class BraveSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://api.search.brave.com/res/v1",
) : WebSearchService {
    override val name: String = "Brave"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Brave", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val url = "$endpoint/web/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") + "&count=$maxResults"
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .get().build()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Brave", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Brave", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val webObj = root["web"] as? JsonObject ?: return emptyList()
        val arr = webObj["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Serper Search Provider — Google Buscar API(serper.dev).
 *
 * Acuerdo: POST https://google.serper.dev/search
 *  - Encabezado de solicitud: X-API-KEY: {apiKey}
 *  - Cuerpo de la solicitud: {"q": query, "num": maxResults}
 *  - respuesta: {"organic": [{"title", "link", "snippet"}]}
 */
class SerperSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://google.serper.dev",
) : WebSearchService {
    override val name: String = "Serper"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Serper", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("q", kotlinx.serialization.json.JsonPrimitive(query))
                put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("X-API-KEY", apiKey)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Serper", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Serper", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["organic"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: búsqueda de bochas Provider — API de búsqueda de IA de Bocha.
 *
 * Acuerdo: POST https://api.bochaai.com/v1/web-search
 *  - Encabezado de solicitud: Authorization: Bearer {apiKey}
 *  - Cuerpo de la solicitud: {"query": query, "count": maxResults, "summary": true}
 *  - respuesta: {"data": {"webPages": {"value": [{"name", "url", "summary"}]}}}
 */
class BochaSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://api.bochaai.com/v1",
) : WebSearchService {
    override val name: String = "Bocha"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Bocha", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("count", kotlinx.serialization.json.JsonPrimitive(maxResults))
                put("summary", kotlinx.serialization.json.JsonPrimitive(true))
            }.toString()
            val req = Request.Builder().url("$endpoint/web-search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Bocha", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Bocha", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val dataObj = root["data"] as? JsonObject ?: return emptyList()
        val webPagesObj = dataObj["webPages"] as? JsonObject ?: return emptyList()
        val arr = webPagesObj["value"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["summary"]?.jsonPrimitive?.contentOrNull
                ?: obj["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Búsqueda de la torre secreta Provider — API de búsqueda de IA de Secret Tower.
 *
 * Acuerdo: POST https://metaso.cn/api/v1/search
 *  - Encabezado de solicitud: Authorization: Bearer {apiKey}
 *  - Cuerpo de la solicitud: {"q": query, "num": maxResults}
 *  - respuesta: {"data": {"results": [{"title", "url", "content"}]}}
 */
class MetasoSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://metaso.cn/api/v1",
) : WebSearchService {
    override val name: String = "Metaso"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Metaso", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("q", kotlinx.serialization.json.JsonPrimitive(query))
                put("num", kotlinx.serialization.json.JsonPrimitive(maxResults))
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Metaso", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Metaso", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val dataObj = root["data"] as? JsonObject ?: return emptyList()
        val arr = dataObj["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * v1.97: Exa Search Provider — Exa AI API de búsqueda.
 *
 * Acuerdo: POST https://api.exa.ai/search
 *  - Encabezado de solicitud: Authorization: Bearer {apiKey}
 *  - Cuerpo de la solicitud: {"query": query, "numResults": maxResults, "contents": {"text": {"maxCharacters": 300}}}
 *  - respuesta: {"results": [{"title", "url", "text"}]}
 */
class ExaSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://api.exa.ai",
) : WebSearchService {
    override val name: String = "Exa"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Exa", "no API key configured, skip")
            return@withContext emptyList()
        }
        resultOf {
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("query", kotlinx.serialization.json.JsonPrimitive(query))
                put("numResults", kotlinx.serialization.json.JsonPrimitive(maxResults))
                put(
                    "contents",
                    kotlinx.serialization.json.buildJsonObject {
                        put(
                            "text",
                            kotlinx.serialization.json.buildJsonObject {
                                put("maxCharacters", kotlinx.serialization.json.JsonPrimitive(300))
                            },
                        )
                    },
                )
            }.toString()
            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Exa", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Exa", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val arr = root["results"] as? JsonArray ?: return emptyList()
        return arr.take(max).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val snippet = obj["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            WebSearchResult(
                title = title,
                url = link,
                snippet = if (snippet.length > 300) snippet.take(300) + "…" else snippet,
                source = name,
            )
        }
    }
}

/**
 * Firecrawl Proveedor de búsqueda.
 *
 * Acuerdo: POST {endpoint}/search,El cuerpo de la solicitud es JSON.
 * Requiere clave API. Admite rastreo y búsqueda web.
 */
class FirecrawlProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://api.firecrawl.dev/v1",
) : WebSearchService {
    override val name: String = "Firecrawl"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        // H-WS1: Utilice resultOf en su lugar try/catch,evitar tragar CancellationException,Consistente con otros proveedores
        resultOf {
            val payload = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("limit", JsonPrimitive(maxResults))
                put("lang", JsonPrimitive("en"))
            }.toString()

            val req = Request.Builder().url("$endpoint/search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body.string()
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(body) as? JsonObject ?: return@use emptyList()
                val data = root["data"]?.let { it as? JsonArray } ?: return@use emptyList()
                data.take(maxResults).mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: obj["metadata"]?.let { (it as? JsonObject)?.get("title") }
                            ?.jsonPrimitive?.contentOrNull ?: url
                    val snippet = obj["description"]?.jsonPrimitive?.contentOrNull
                        ?: obj["markdown"]?.jsonPrimitive?.contentOrNull?.take(300) ?: ""
                    WebSearchResult(
                        title = stripHtmlSimple(title),
                        url = url,
                        snippet = if (snippet.length > 300) snippet.take(300) + "\u2026" else snippet,
                        source = name,
                    )
                }
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Firecrawl", "search error", t)
        }.getOrNull() ?: emptyList()
    }
}

/**
 * Perplexity Search Provider — Perplexity AI API de búsqueda.
 *
 * Acuerdo: POST https://api.perplexity.ai/chat/completions
 *  - Encabezado de solicitud: Authorization: Bearer {apiKey},Content-Type: application/json
 *  - Cuerpo de la solicitud: { "model": "sonar-pro",
 *              "messages": [{ "role": "user", "content": query }],
 *              "return_citations": true,
 *              "return_images": false }
 *  - respuesta: { "choices": [{ "message": { "content": "..." } }],
 *           "citations": ["url1", "url2", ...] }
 *
 * Perplexity Devolver respuestas generadas por IA + lista de URL de referencia,Este proveedor envuelve cada URL de referencia en un
 * [WebSearchResult],snippet tomar message.content(Truncado a 300 palabras),source = "Perplexity".
 * Sin referencia, pero rebajado a un único resultado cuando la IA da la respuesta(Título "Perplexity AI"),Mantenga la respuesta como un fragmento.
 * necesitar API Key(Configurar en ajustes),Cuando no hay ninguna clave, se devuelve directamente una lista vacía.
 */
class PerplexitySearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val endpoint: String = "https://api.perplexity.ai",
) : WebSearchService {
    override val name: String = "Perplexity"

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        if (apiKey.isBlank()) {
            Logger.w("Perplexity", "no API key configured, skip")
            return@withContext emptyList()
        }
        // H-WS1: Utilice resultOf en su lugar runCatching,evitar tragar CancellationException
        resultOf {
            // Utilice buildJsonObject para construir el cuerpo de la solicitud,El escape de cadenas lo maneja la biblioteca de serialización.
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("model", kotlinx.serialization.json.JsonPrimitive("sonar-pro"))
                put(
                    "messages",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.buildJsonObject {
                                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                                put("content", kotlinx.serialization.json.JsonPrimitive(query))
                            },
                        ),
                    ),
                )
                put("return_citations", kotlinx.serialization.json.JsonPrimitive(true))
                put("return_images", kotlinx.serialization.json.JsonPrimitive(false))
            }.toString()
            val req = Request.Builder().url("$endpoint/chat/completions")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            // M-WS2: usar executeAsync(enqueue + suspendCancellableCoroutine)bloqueo alternativo execute()
            client.executeAsync(req).use { resp ->
                // 429/402 Detección de límite de velocidad: marcar el proveedor y lanzar SearchRateLimitException (reenviado a la interfaz de usuario mediante onError)
                SearchRateLimiter.assertNotRateLimited(name, resp)
                if (!resp.isSuccessful) {
                    Logger.w("Perplexity", "search failed: ${resp.searchDiagnostic()}")
                    return@use emptyList()
                }
                parseResults(resp.body.string(), maxResults)
            }
        }.onError { _, t ->
            // Las excepciones al límite de velocidad se muestran hacia arriba para que la interfaz de usuario proporcione indicaciones fáciles de usar; otras excepciones todavía se tragan y se devuelve una lista vacía.
            if (t is SearchRateLimitException) throw t
            Logger.w("Perplexity", "search error", t)
        }.getOrNull() ?: emptyList()
    }

    private fun parseResults(body: String, max: Int): List<WebSearchResult> {
        val json = Json { ignoreUnknownKeys = true }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        // Obtenga la respuesta al contenido generado por IA(choices[0].message.content)
        val choices = root["choices"] as? JsonArray ?: return emptyList()
        val firstChoice = choices.firstOrNull() as? JsonObject ?: return emptyList()
        val messageObj = firstChoice["message"] as? JsonObject ?: return emptyList()
        val content = messageObj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val snippet = if (content.length > 300) content.take(300) + "…" else content
        // Lista de URL de referencia(citations[].url Directamente en la respuesta hay una serie de cadenas.)
        val citations = (root["citations"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return when {
            // Cada URL de referencia se incluye en un resultado.,snippet Comparte respuestas de IA(Truncado a 300 palabras)
            citations.isNotEmpty() -> citations.take(max).map { url ->
                WebSearchResult(
                    title = extractTitle(url),
                    url = url,
                    snippet = snippet,
                    source = name,
                )
            }
            // Cuando no hay ninguna referencia, se devuelve directamente una lista vacía.,evitar la construcción url="" Los resultados no válidos contaminan aguas abajo
            else -> {
                Logger.d("WebSearchService", "Perplexity No hay resultados de citas")
                emptyList()
            }
        }
    }

    /** Extraer el host de la URL como encabezado(Cuando no hay ningún host, recurra a "Perplexity Citation"). */
    private fun extractTitle(url: String): String {
        return runCatching {
            val host = java.net.URI(url).host
            if (host.isNullOrBlank()) "Perplexity Citation" else host
        }.getOrElse { "Perplexity Citation" }
    }
}
