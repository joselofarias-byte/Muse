package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.knowledge.BuiltInKnowledgeDocSeeder
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.RagConfig
import io.zer0.muse.rag.RagService
import io.zer0.muse.web.SearchRateLimitException
import io.zer0.muse.web.WebSearchPolicy
import io.zer0.muse.web.WebSearchRequest
import io.zer0.muse.web.WebSearchService
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P1-3b Deslocalización:Skill Buscar/HTTP Implementación de herramientas (migrada desde SkillExecutor.kt).
 * Llamado por el delegado de SkillExecutor.
 */
@Suppress("LongParameterList") // Punto de agregación de inyección de dependencia: dependencias opcionales (DAOs / Proveedor de servicios/estrategias) inyección centralizada, del mismo calibre que RagService/SkillExecutor
class SkillSearchToolsImpl(
    private val context: Context,
    private val client: OkHttpClient,
    private val webSearchService: WebSearchService?,
    private val knowledgeDocDao: KnowledgeDocDao?,
    private val ragService: RagService?,
    private val ragConfigProvider: suspend () -> RagConfig = { RagConfig() },
    private val webSearchCoordinator: io.zer0.muse.web.WebSearchCoordinator? = null,
    private val webSearchPolicyProvider: suspend () -> WebSearchPolicy = { WebSearchPolicy() },
    private val builtInKnowledgeDocSeeder: BuiltInKnowledgeDocSeeder? = null,
) {
    fun validatePublicUrl(url: String): Boolean {
        val uri =
            try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        // Verificación secundaria después de la resolución DNS IP(protegerse contra DNS rebinding):Siempre que algún resultado del análisis apunte a la intranet, será rechazado.
        val addresses =
            try {
                java.net.InetAddress.getAllByName(host)
            } catch (e: Exception) {
                return false
            }
        return addresses.all { addr ->
            // IPv4 red privada/bucle invertido/Los métodos integrados de InetAddress anulan el enlace local, etc.
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return@all false
            }
            // IPv6 red privada fc00::/7(InetAddress.isSiteLocalAddress Devoluciones para IPv6 false,Requiere juicio manual)
            if (addr is java.net.Inet6Address) {
                val bytes = addr.address
                // fc00::/7 Los 7 primeros son 1111110,Es decir, el primer rango de bytes. 0xfc..0xfd
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return@all false
            }
            true
        }
    }

    /**
     * A-07: Implementación de OkHttp con protección salto a salto SSRF. Desactivar redireccionamientos de seguimiento automático(client Predeterminado followRedirects=true),
     * Seguir manualmente 30x,Repite cada salto [validatePublicUrl] — Evitar el redireccionamiento 30x de la URL pública inicial a
     * 127.0.0.1 / 169.254.169.254 Espere la dirección de intranet(DNS rebinding / open-redirect Ataque combinado).
     * restricción:Número máximo de saltos MAX_REDIRECTS(Igual que FileTools.parse_link),Evite bucles de redireccionamiento infinitos.
     *
     * @param request Solicitud construida pero no ejecutada
     * @param clientBuilder Basado en inyectar cliente de chat newBuilder(Se utiliza para anular tiempos de espera, etc.)
     * @return respuesta de golpe final;La parte visitante es responsable de use/close
     */
    private fun executeWithHopGuard(request: Request.Builder, clientBuilder: OkHttpClient.Builder): okhttp3.Response {
        var redirects = 0
        var redirectReq = request
        while (true) {
            val hopUrl = redirectReq.build().url
            // Verificación salto a salto:Cualquier salto que apunte a la red interna será rechazado.(incluyendo la inicial URL)
            val hopStr = hopUrl.toString()
            if (!validatePublicUrl(hopStr)) {
                // Lanzar excepción comercial,La rama IOException de la persona que llama proporciona cobertura unificada(Trae información de salto)
                throw java.io.IOException("El objetivo de redireccionamiento apunta a la dirección de intranet.,Rechazado: $hopStr")
            }
            // Cada salto se crea nuevo. followRedirects=false de client(Reutilizar la configuración de conexión y la cobertura de tiempo de espera del cliente compartido)
            val hopClient = clientBuilder.followRedirects(false).build()
            val resp = hopClient.newCall(redirectReq.build()).execute()
            // location para locales val,Kotlin puede convertir inteligentemente el siguiente if a no vacío después de salir temprano.
            val location = resp.header("Location")
            if (resp.code !in 300..399 || location == null) {
                return resp // no redirigido(O falta la redirección Location):Devolver la respuesta final directamente(incluso si está bien o mal 2xx)
            }
            resp.close() // Seguir manualmente,Cerrar esta respuesta de redireccionamiento(no leer body,guardar conexión)
            redirects++
            if (redirects > MAX_REDIRECTS) {
                throw java.io.IOException("El número de redirecciones supera el límite superior.($MAX_REDIRECTS),terminado")
            }
            // Ubicación relativa analizada por la URL actual
            val next =
                hopUrl.resolve(location)
                    ?: throw java.io.IOException("La ubicación de redireccionamiento no se puede resolver: $location")
            val nextStr = next.toString()
            // Mantener el método original/body/header,Reemplazar solo URL(Consistente con la semántica de redireccionamiento predeterminada de OkHttp:
            // 307/308 Reservar body,301/302/303 Manejado de forma compatible por servidores que aceptan GET)
            val prev = redirectReq.build()
            redirectReq =
                Request.Builder()
                    .url(nextStr)
                    .method(prev.method, prev.body)
                    .apply { prev.headers.forEach { (k, v) -> header(k, v) } }
        }
    }

    private companion object {
        const val WEB_FETCH_PARSE_PREVIEW_CHARS = 1_000_000

        /** Número máximo de saltos para seguir una redirección manualmente(Bucle de redireccionamiento anti-infinito). */
        const val MAX_REDIRECTS = 10
    }

    /** HTTP GET pedido. en el fracaso(404/tiempo de espera/La conexión falló)Bajar a fragmentos de búsqueda;401/403 Espere errores comerciales y no baje de categoría. */
    suspend fun execHttpGet(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF protección:La verificación de URL inicial se anula mediante la verificación salto a salto de ejecutarWithHopGuard(Verificación del primer salto)
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 300L) ?: 30L
        val req = Request.Builder().url(url).get()
        args["headers"]?.let { applyHeaders(req, it) }
        // Reutilizar grupo de conexiones,Solo anula callTimeout con followRedirects=false(Verificación SSRF salto a salto,ver A-07)
        val timeoutClient =
            client.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
        return try {
            executeWithHopGuard(req, timeoutClient).use { resp ->
                // condiciones de degradación:solo 404(El recurso no existe,La búsqueda puede tener resúmenes relevantes);401/403 Espere errores comerciales y no baje de categoría
                if (resp.code == 404) {
                    val degraded = degradeToSearchSummary(url, resp.code)
                    if (degraded != null) return@use degraded
                }
                val body = resp.body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    captureTextStream(
                        reader = reader,
                        outputDirectory = File(context.filesDir, TOOL_OUTPUTS_DIR),
                        filePrefix = "http_get",
                        previewLimitChars = 0,
                    )
                }
                formatSkillHttpResponse(resp.code, body.reference)
            }
        } catch (e: java.io.IOException) {
            // tiempo de espera/La conexión falló/Los rechazos de SSRF salto por salto se degradan a resúmenes de búsqueda;Si la degradación no está disponible, devuelva el error original
            val degraded = degradeToSearchSummary(url, -1, e.message ?: "")
            if (degraded != null) return degraded
            // v2.2.1: Comentarios de fallas con dirección y etapa.(tiempo de espera/analizar/TLS),Reemplazar puro e.message
            ToolFailureText.httpFailure("HTTP GET", url, e)
        }
    }

    /** HTTP POST pedido. */
    fun execHttpPost(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF protección:La verificación de URL inicial se anula mediante la verificación salto a salto de ejecutarWithHopGuard(Verificación del primer salto)
        val body = args["body"] ?: ""
        // v1.52: Tipo de contenido predeterminado con charset=utf-8,Evite los caracteres confusos del cuerpo chino
        val rawContentType = args["content_type"] ?: "application/json"
        val contentType =
            if (rawContentType.contains("charset", ignoreCase = true)) {
                rawContentType
            } else {
                "$rawContentType; charset=utf-8"
            }
        // timeout: Predeterminado 30 segundos
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 300L) ?: 30L
        val req =
            Request.Builder().url(url)
                .post(body.toRequestBody(contentType.toMediaType()))
        args["headers"]?.let { applyHeaders(req, it) }
        // Reutilizar grupo de conexiones,Sólo cubrir callTimeout(followRedirects=false Establecido por ejecutarWithHopGuard,ver A-07)
        val timeoutClient =
            client.newBuilder()
                .callTimeout(timeoutSec, TimeUnit.SECONDS)
        return try {
            executeWithHopGuard(req, timeoutClient).use { resp ->
                val responseBody = resp.body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    captureTextStream(
                        reader = reader,
                        outputDirectory = File(context.filesDir, TOOL_OUTPUTS_DIR),
                        filePrefix = "http_post",
                        previewLimitChars = 0,
                    )
                }
                formatSkillHttpResponse(resp.code, responseBody.reference)
            }
        } catch (e: java.io.IOException) {
            // Fallo de conexión o rechazo SSRF salto a salto(v2.2.1: Con dirección y etapa de falla.)
            ToolFailureText.httpFailure("HTTP POST", url, e)
        }
    }

    /** Analizar la ruta de la zona de pruebas(Calificar en filesDir / cacheDir,Evitar el cruce de caminos). */

    // H-SE1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
    fun applyHeaders(req: Request.Builder, headersJson: String) {
        resultOf {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), headersJson)
            obj.forEach { (k, v) ->
                (v as? JsonPrimitive)?.content?.let { req.header(k, it) }
            }
        }.onError { msg, _ ->
            Logger.w("SkillExecutor", "applyHeaders El análisis falló: $msg(originales: $headersJson)")
        }
    }

    // ── v0.24: Búsqueda y adquisición de información. ──────────────────────────────────────────────

    /**
     * web_search — Utilice el configurado WebSearchService(SearXNG/Tavily)Buscar.
     * Deje que LLM decida proactivamente cuándo buscar,En lugar de inyectarse en cada conversación.
     */
    suspend fun execWebSearch(args: Map<String, String>, turnKey: String = "default"): String {
        val service =
            webSearchService
                ?: return context.getString(R.string.skill_web_search_not_configured)
        // Primero limpie las comillas mal formadas generadas accidentalmente por LLM/Personajes invisibles.
        val rawQuery =
            args["query"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return context.getString(R.string.skill_missing_param_query)
        // v2.4.6: Estandarizar primero(Borrar comillas con formato incorrecto/personajes invisibles),refinado(poner"una palabra"Convergencia a cadena de palabras clave),
        // mejorar"El término de búsqueda es demasiado amplio"problema. Las palabras clave cortas no son pasivas.
        val query =
            io.zer0.muse.web.WebSearchQueryNormalizer.refine(rawQuery)
                .takeIf { it.isNotEmpty() }
                ?: return context.getString(R.string.skill_missing_param_query)
        if (rawQuery != query) {
            Logger.i("SkillExecutor", "web_search query refined: '$rawQuery' -> '$query'")
        }
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val searchPolicy = webSearchPolicyProvider()
        val response =
            webSearchCoordinator?.search(
                WebSearchRequest(query = query, maxResults = searchPolicy.maxResults, dateRange = args["date_range"]),
                turnKey = turnKey,
                policy = searchPolicy,
            )
        if (response != null) {
            return formatCoordinatedSearchResponse(response, maxResults)
        }
        // Entorno de prueba compatible/Cadena de inyección antigua: llame al servicio directamente cuando no haya un coordinador.
        // date_range / time_period: rango de tiempo(Opcional,Elige uno,time_period sinónimos compatibles)
        val dateRange = args["date_range"]?.takeIf { it.isNotBlank() }
        val timePeriod = args["time_period"]?.takeIf { it.isNotBlank() }
        val options =
            buildMap<String, String> {
                dateRange?.let { put("date_range", it) }
                timePeriod?.let { put("time_period", it) }
            }
        // H-SE1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
        val results =
            resultOf {
                service.searchWithOptions(query, maxResults, options)
            }.onError { msg, t ->
                // search-rate-limiter: Devuelve mensajes amigables a LLM cuando se limita la velocidad para evitar colisiones repetidas 429/402
                if (t is SearchRateLimitException) {
                    val secs = (t.retryAfterMs / 1000).coerceAtLeast(1)
                    return context.getString(R.string.skill_search_no_result, query) +
                        "\n(El servicio de búsqueda tiene velocidad limitada, por favor $secs Vuelve a intentarlo después de unos segundos)"
                }
                Logger.w("SkillExecutor", "web_search falló: $msg")
            }.getOrNull() ?: emptyList()
        if (results.isEmpty()) return context.getString(R.string.skill_search_no_result, query)
        val sb = StringBuilder(context.getString(R.string.skill_search_result_header, query, maxResults))
        results.forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title}")
            sb.appendLine("    URL: ${r.url}")
            sb.appendLine("    Resumen: ${r.snippet}")
        }
        // El texto del resultado sólo conserva los datos de la búsqueda; Las estrategias de respuesta pertenecen a las indicaciones del sistema interno del modelo y no se mezclan con los resultados de las herramientas visibles para el usuario.
        return sb.toString().trimEnd()
    }

    private fun formatCoordinatedSearchResponse(response: io.zer0.muse.web.WebSearchResponse, maxResults: Int): String {
        if (response.status != io.zer0.muse.web.WebSearchStatus.RESULTS || response.results.isEmpty()) {
            return when (response.status) {
                io.zer0.muse.web.WebSearchStatus.BUDGET_EXCEEDED -> "Presupuesto de búsqueda agotado: la mayoría de las búsquedas en esta ronda ${WebSearchPolicy().maxSearchesPerTurn} veces, por favor responda según los resultados existentes."
                io.zer0.muse.web.WebSearchStatus.DUPLICATE_QUERY -> "Este término de búsqueda ya ha sido buscado en esta ronda, no vuelva a buscarlo."
                io.zer0.muse.web.WebSearchStatus.RATE_LIMITED -> "El servicio de búsqueda tiene velocidad limitada, inténtalo de nuevo más tarde."
                io.zer0.muse.web.WebSearchStatus.FAILED -> "El servicio de búsqueda falló:${response.attempts.lastOrNull()?.message ?: "Anormalidad de la red"}"
                else -> "Buscar“${response.normalizedQuery}”No se encontraron resultados."
            }
        }
        val sb = StringBuilder(context.getString(R.string.skill_search_result_header, response.normalizedQuery, response.results.size))
        response.results.take(maxResults).forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title}")
            sb.appendLine("    URL: ${r.url}")
            sb.appendLine("    Resumen: ${r.snippet}")
        }
        sb.appendLine("\nFuente de búsqueda:${response.provider ?: "desconocido"};Estado de esta ronda:${response.status}")
        return sb.toString().trimEnd()
    }

    /**
     * web_fetch — Obtener el texto de la página web de la URL especificada(Analizar con Jsoup HTML,Elimine los elementos de ruido y obtenga el cuerpo en texto plano.).
     * HTTP Bajar de categoría en caso de falla o no 2xx:Utilice webSearchService para buscar el nombre de dominio URL,Devuelve un resumen de los primeros 3 resultados.
     */
    suspend fun execWebFetch(args: Map<String, String>): String {
        val url = args["url"] ?: return context.getString(R.string.skill_missing_param_url)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return context.getString(R.string.skill_url_invalid_scheme)
        }
        // SSRF protección:La verificación de URL inicial se anula mediante la verificación salto a salto de ejecutarWithHopGuard(Verificación del primer salto)
        val req =
            Request.Builder().url(url).get()
                .header("User-Agent", "Mozilla/5.0 (Android LLM client)")
        args["headers"]?.let { applyHeaders(req, it) }
        // Reutilizar grupo de conexiones,Sólo cubrir callTimeout(restricción:HTTP Solicitar tiempo de espera de 30 segundos);followRedirects=false Establecido por guardia salto a salto
        val timeoutClient =
            client.newBuilder()
                .callTimeout(30, TimeUnit.SECONDS)
        return try {
            executeWithHopGuard(req, timeoutClient).use { resp ->
                if (!resp.isSuccessful) {
                    // Rebajar:Buscar el nombre de dominio del sitio,Devolver resumen de los primeros 3 resultados
                    val degraded = degradeToSearchSummary(url, resp.code)
                    if (degraded != null) return@use degraded
                    return@use "HTTP ${resp.code}"
                }
                val captured = resp.body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    captureTextStream(
                        reader = reader,
                        outputDirectory = File(context.filesDir, TOOL_OUTPUTS_DIR),
                        filePrefix = "web_fetch",
                        previewLimitChars = WEB_FETCH_PARSE_PREVIEW_CHARS,
                    )
                }
                // Analizar con Jsoup,Elimine los elementos de ruido y obtenga el cuerpo en texto plano.
                val doc = Jsoup.parse(captured.preview, url)
                doc.select("script, style, noscript, nav, footer, header, aside").remove()
                val bodyEl = doc.body()
                val text = bodyEl.text()
                // Contraer espacios en blanco continuos
                val cleaned = text.replace(Regex("\\s{3,}"), "\n\n").trim()
                buildString {
                    append(formatWebFetchResult(resp.code, cleaned))
                    if (captured.savedFile != null) {
                        if (captured.totalChars > WEB_FETCH_PARSE_PREVIEW_CHARS) {
                            append("\n\n[El análisis del texto de la página web solo cubre el frente $WEB_FETCH_PARSE_PREVIEW_CHARS personajes;")
                        } else {
                            append("\n\n[Se ha guardado el código fuente completo de la página web.;")
                        }
                        append("El código fuente completo se puede leer en secciones a través de read_file]\n")
                        append(captured.reference)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            // Anormalidad de la red(tiempo de espera/La conexión falló/Rechazo de la SSRF salto a salto)Bajar a fragmentos de búsqueda
            val degraded = degradeToSearchSummary(url, -1, e.message ?: "")
            if (degraded != null) return degraded
            throw e
        }
    }

    /**
     * raspado web/La solicitud no pudo bajar de categoría:Utilice webSearchService para buscar el nombre de dominio de una URL,Devuelve un resumen de los primeros 3 resultados.
     *
     * @param url solicitud original URL(Se utiliza para extraer nombres de dominio como términos de búsqueda.)
     * @param httpCode HTTP código de estado(>0 Indica respuesta recibida,<0 Indica anomalía en la red)
     * @param errorMsg Mensaje de error cuando ocurre una anomalía en la red(httpCode < 0 usado cuando)
     * @return Texto de resumen de degradación;Si el servicio de búsqueda no está configurado, la búsqueda falla o no hay resultados,Regresar null(La persona que llama devuelve el error original.)
     */
    suspend fun degradeToSearchSummary(url: String, httpCode: Int, errorMsg: String = ""): String? {
        val service = webSearchService ?: return null
        val domain =
            resultOf { java.net.URI(url).host }
                .onError { msg, _ -> Logger.w("SkillExecutor", "Error en la resolución del nombre de dominio de búsqueda de degradación: $msg") }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val results =
            resultOf { service.search(domain, 3) }
                .onError { msg, t ->
                    // search-rate-limiter: Solo se registrarán registros cuando la búsqueda degradada tenga una velocidad limitada y se devolverá un valor nulo para permitir que la persona que llama muestre el error de rastreo original.
                    if (t is SearchRateLimitException) {
                        Logger.w("SkillExecutor", "La velocidad de búsqueda degradada es limitada: ${t.retryAfterMs}ms")
                    } else {
                        Logger.w("SkillExecutor", "La búsqueda de degradación falló: $msg")
                    }
                }
                .getOrNull() ?: return null
        if (results.isEmpty()) return null
        val fallbackMsg =
            if (httpCode > 0) {
                "Fallo en el rastreo web(HTTP $httpCode),El siguiente es un resumen de búsquedas relacionadas en este sitio.:"
            } else {
                "Fallo en el rastreo web: ${errorMsg.ifBlank { "Anormalidad de la red" }},El siguiente es un resumen de búsquedas relacionadas en este sitio.:"
            }
        val sb = StringBuilder(fallbackMsg)
        results.forEachIndexed { idx, r ->
            sb.appendLine("[${idx + 1}] ${r.title} - ${r.url}")
            sb.appendLine("    ${r.snippet}")
        }
        return sb.toString().trimEnd()
    }

    /**
     * knowledge_search — Búsqueda de texto completo en la base de conocimientos del usuario(título + contenido).
     * Deje que LLM verifique activamente la base de conocimientos,En lugar de depender de que el usuario realice manualmente @.
     *
     * v1.97: Se agregó el parámetro include_internal para controlar si se deben devolver documentos de desarrollo internos.(devdoc).
     * Predeterminado false,Buscar solo documentos creados por usuarios;LLM debe pasar a verdadero cuando el usuario solicita la funcionalidad de la aplicación Muse.
     * Reparación"El devdoc interno está expuesto a consultas normales."problema.
     */
    suspend fun execKnowledgeSearch(args: Map<String, String>): String {
        val dao = knowledgeDocDao ?: return context.getString(R.string.skill_knowledge_not_configured)
        val query = args["query"] ?: return context.getString(R.string.skill_missing_param_query)
        if (query.isBlank()) return context.getString(R.string.skill_query_blank)
        val topK = args["top_k"]?.toIntOrNull()?.coerceIn(1, 50) ?: 5
        val threshold = args["threshold"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.3f
        // v1.97: include_internal — Si se debe incluir documentación de desarrollo interno(devdoc),Predeterminado false
        val includeInternal = args["include_internal"]?.toBoolean() ?: false
        // v2.4.6: folder — Limitar carpetas de búsqueda(Camino dentro de la base de conocimiento). una carpeta = un campo de búsqueda independiente,
        //   Después de ingresar, busque únicamente dentro de la carpeta y sus subcarpetas.,No enlace a otras carpetas.
        val folderArg = args["folder"]?.trim()?.takeIf { it.isNotBlank() }
        if (includeInternal) {
            resultOf { builtInKnowledgeDocSeeder?.ensureSeeded() }
                .onError { msg, error -> Logger.w("SkillExecutor", "La inicialización del documento de función incorporada falló: $msg", error) }
        }

        // v2.4.6: alcance de la carpeta → docId colección(Coincidencia de prefijo de ruta,Contiene subdirectorios)
        var folderScopeDocIds: List<String>? = null
        if (folderArg != null) {
            val path = io.zer0.muse.rag.KnowledgeFolderBrowser.normalize(folderArg)
            val allDocs = resultOf { dao.observeAllUser().first() }.getOrNull().orEmpty()
            val hit = allDocs.filter { doc ->
                val p = io.zer0.muse.rag.KnowledgeFolderBrowser.folderPath(doc)
                p == path || p.startsWith("$path/")
            }
            folderScopeDocIds = hit.map { it.id }
            if (folderScopeDocIds.isEmpty()) {
                return context.getString(R.string.skill_knowledge_no_match, folderArg, threshold.toString())
            }
        }

        // v2.x: ecodiagnóstico — Registre los resultados de cada etapa de búsqueda.,Genera una línea de diagnóstico cuando falta,
        // dejar"threshold Si los parámetros son válidos"Verificable(Comentarios sobre este parámetro antes"como decoración").
        var vectorDiagnostic = "La recuperación de vectores no está habilitada"

        // v1.54: Priorizar la búsqueda de vectores(coincidencia semántica),Bajar a LIKE subcadena coincidente cuando no hay índice
        val rs = ragService
        if (rs != null) {
            // H-SE1: Usar en su lugar resultOf{}(Lanzamiento correcto CancellationException)
            val ragConfig =
                resultOf { ragConfigProvider() }
                    .onError { msg, _ -> Logger.w("SkillExecutor", "ragConfigProvider falló: $msg") }
                    .getOrNull() ?: io.zer0.muse.rag.RagConfig()
            val vectorResults =
                resultOf {
                    rs.retrieve(query, topK, threshold, ragConfig, scopeDocIds = folderScopeDocIds)
                }.onError { msg, _ ->
                    Logger.w("SkillExecutor", "Falló la recuperación del vector,bajar de categoría a LIKE: $msg")
                }.getOrNull()
            if (vectorResults != null) {
                // v1.97: Filtrar por dentro devdoc(include_internal=false excluir documentos internos)
                // v1.133: SearchResult.isSe ha unificado el juicio del campo interno(RagService.retrieve relleno,
                // La fuente de datos es KnowledgeDocEntity.isInternal). sustituto original `docId.startsWith("devdoc-")` codificado,
                // Evite fallas de filtrado después de cambios en el nombre de la identificación del documento interno.
                val filtered =
                    if (includeInternal) {
                        vectorResults
                    } else {
                        vectorResults.filterNot { it.isInternal }
                    }
                if (filtered.isNotEmpty()) {
                    val sb =
                        StringBuilder(
                            context.getString(R.string.skill_knowledge_vector_header, query, filtered.size, threshold.toString(), topK),
                        )
                    // Declaración de aislamiento: los resultados de la búsqueda son datos, no instrucciones.
                    // Si "por favor ejecute…"Este tipo de oración imperativa puede ser ejecutada como una orden por el modelo. ——
                    // Aquí primero dibujamos los límites y luego damos el texto.
                    sb.appendLine(
                        context.getString(R.string.skill_knowledge_reference_only_notice),
                    )
                    filtered.forEachIndexed { idx, r ->
                        sb.appendLine(
                            "[${idx + 1}] Fuente: ${r.docTitle} " +
                                "(docId=${r.docId}, chunkId=${r.chunkId.ifBlank { "-" }}, " +
                                "Similitud ${"%.2f".format(r.score)})",
                        )
                        sb.appendLine("    fragmento: ${r.chunkContent.take(300)}")
                    }
                    return sb.toString().trimEnd()
                }
                vectorDiagnostic =
                    if (vectorResults.isEmpty()) {
                        "Búsqueda de vectores sin resultados"
                    } else {
                        "golpe vectorial ${vectorResults.size} Artículo,Vaciar después de filtrar documentos internos"
                    }
            } else {
                vectorDiagnostic = "Falló la recuperación del vector,Palabras clave degradadas"
            }
            // No hay resultados para la búsqueda de vectores,Sigue intentando ME GUSTA buscar(Compatible con documentos antiguos no indexados)
        }

        // Rebajar:LIKE coincidencia de subcadenas(Documentos antiguos no indexados fragmentados)
        // M-KB1: Escapar del carácter comodín LIKE(% _ \),Compatible con DAO ESCAPE '\' cláusula
        val allResults =
            dao.search(
                io.zer0.muse.data.knowledge.KnowledgeDocDao.escapeLikeQuery(query),
                includeInternal = includeInternal,
            ).first()
        // v1.97: Filtrar por dentro devdoc(include_internal=false excluir documentos internos)
        // v1.133: Utilice el campo isInternal en su lugar(Coherente con la etiqueta MIGRATION_38_39,sustituto original fileType="devdoc" codificado)
        val visibleResults =
            if (includeInternal) {
                allResults
            } else {
                allResults.filterNot { it.isInternal }
            }
                // v2.4.6: Aislamiento del alcance de la carpeta — LIKE También se debe restringir la ruta de degradación.,De lo contrario, se vinculará a otras carpetas.
                .let { list ->
                    val scope = folderScopeDocIds
                    if (scope == null) list else list.filter { it.id in scope }
                }
        val scored =
            visibleResults.map { doc ->
                // v1.97: Mejorar calificación — El título coincide exactamente=1.0,El título contiene=0.8,El contenido aparece varias veces para aumentar la puntuación.
                // v2.x: Puntuación continua — La partitura antigua se discretiza como {0.5,0.6,0.7,0.8,1.0},Se debe acceder a todo el contenido. ≥0.5,
                // causa threshold ≤0.5 El conjunto de resultados es exactamente el mismo.(Comentarios medidos"No hay diferencia entre pasar 0.5 y el 0.3 predeterminado").
                // Ahora mapeo fluido por densidad de visitas:El titulo es exacto=1.0 / El título contiene=0.85 / éxito de contenido 0.3~0.8 intervalo continuo.
                val titleMatch = doc.title.contains(query, ignoreCase = true)
                val contentMatches = doc.content.split(query, ignoreCase = true).size - 1
                val score =
                    when {
                        doc.title.equals(query, ignoreCase = true) -> 1.0f
                        titleMatch -> 0.85f
                        contentMatches > 0 -> {
                            // Se considera un acierto cada 500 caracteres."Denso";saturating El mapeo evita que el texto largo sea visitado varias veces más que el título.
                            val density = contentMatches * 500f / doc.content.length.coerceAtLeast(200)
                            val saturated = density / (density + 1f)
                            (0.3f + 0.5f * saturated).coerceAtMost(0.8f)
                        }
                        else -> 0.0f
                    }
                doc to score
            }.filter { it.second >= threshold }
        if (scored.isEmpty()) {
            return context.getString(R.string.skill_knowledge_no_match, query, threshold.toString()) +
                "\n(Diagnóstico: threshold=$threshold, top_k=$topK, include_internal=$includeInternal; " +
                "$vectorDiagnostic; concordancia de palabras clave ${visibleResults.size} Artículo)"
        }
        val results = scored.sortedByDescending { it.second }.take(topK)
        val sb = StringBuilder(context.getString(R.string.skill_knowledge_like_header, query, results.size, threshold.toString(), topK))
        results.forEachIndexed { idx, (doc, score) ->
            sb.appendLine("[${idx + 1}] ${doc.title} (${doc.fileType}, ${doc.content.length} palabra, score=$score)")
            val matchIdx = doc.content.indexOf(query, ignoreCase = true)
            val snippet =
                if (matchIdx < 0) {
                    doc.content.take(200)
                } else {
                    val start = (matchIdx - 80).coerceAtLeast(0)
                    val end = (matchIdx + query.length + 120).coerceAtMost(doc.content.length)
                    "..." + doc.content.substring(start, end) + "..."
                }
            sb.appendLine("    fragmento: $snippet")
        }
        return sb.toString().trimEnd()
    }

    /**
     * arxiv_search — arXiv Búsqueda de artículos académicos.
     * usar http://export.arxiv.org/api/query?search_query=all:Consulta&max_results=N
     * Regresar Atom XML,Entrada/título/resumen/enlace de análisis regular.
     */
    fun execArxivSearch(args: Map<String, String>): String {
        val query = args["query"] ?: return context.getString(R.string.skill_missing_param_query)
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // category: Clasificación de materias(Como por ejemplo cs.AI/cs.CL);date_from/date_to: rango de fechas(YYYY-MM-DD)
        // M-SE10: category Codificación con URLEncoder;date_from/date_to Verificar el formato AAAA-MM-DD
        val category =
            args["category"]?.takeIf { it.isNotBlank() }
                ?.let { java.net.URLEncoder.encode(it, "UTF-8") }
        val dateRegex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        val dateFrom =
            args["date_from"]?.takeIf { it.isNotBlank() }
                ?.let { if (dateRegex.matches(it)) it else return "date_from Error de formato,debería ser YYYY-MM-DD: $it" }
        val dateTo =
            args["date_to"]?.takeIf { it.isNotBlank() }
                ?.let { if (dateRegex.matches(it)) it else return "date_to Error de formato,debería ser YYYY-MM-DD: $it" }
        val searchQuery =
            buildString {
                append("all:").append(encoded)
                category?.let { append("+AND+cat:").append(it) }
                if (dateFrom != null || dateTo != null) {
                    val from = dateFrom?.replace("-", "")?.let { it + "0000" } ?: "000000000000"
                    val to = dateTo?.replace("-", "")?.let { it + "2359" } ?: "999912312359"
                    // submittedDate:[YYYYMMDD0000 TO YYYYMMDD2359](Los corchetes están precodificados como %5B/%5D)
                    append("+AND+submittedDate:%5B").append(from).append("+TO+").append(to).append("%5D")
                }
            }
        // v1.71: uso HTTPS,Evite el riesgo de intermediarios en la transmisión de texto claro
        // v1.109 Reparación: Específicamente especificado sortBy=relevance,Evite que las consultas compuestas se sobrescriban por secuencia de fechas
        val url = "https://export.arxiv.org/api/query?search_query=$searchQuery&max_results=$maxResults&sortBy=relevance&sortOrder=descending"
        val req =
            Request.Builder().url(url).get()
                .header("User-Agent", "muse/1.0 (Android LLM client)")
        // Reutilizar grupo de conexiones,Sólo cubrir callTimeout(restricción:HTTP Solicitar tiempo de espera de 30 segundos)
        val timeoutClient =
            client.newBuilder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        timeoutClient.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) return context.getString(R.string.skill_arxiv_search_failed, resp.code)
            val xml = resp.body.string()
            // Atom XML entry bloquear:<entry>...<title>Título</title><summary>Resumen</summary><link href="URL"/>...</entry>
            val entries =
                Regex(
                    pattern = """<entry>([\s\S]*?)</entry>""",
                    options = setOf(RegexOption.IGNORE_CASE),
                ).findAll(xml).take(maxResults).toList()
            if (entries.isEmpty()) return context.getString(R.string.skill_arxiv_no_result, query)
            val sb = StringBuilder(context.getString(R.string.skill_arxiv_result_header, query, entries.size))
            entries.forEachIndexed { idx, e ->
                val block = e.groupValues[1]
                val title =
                    Regex("""<title>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
                        ?: "(Sin título)"
                val summary =
                    Regex("""<summary>([\s\S]*?)</summary>""", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
                        ?.take(300) ?: "(Sin resumen)"
                val link =
                    Regex("""<link[^>]*href="([^"]+)"[^>]*/>""", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.get(1) ?: "(Sin enlace)"
                val published =
                    Regex("""<published>([^<]+)</published>""", RegexOption.IGNORE_CASE)
                        .find(block)?.groupValues?.get(1)?.substringBefore("T") ?: ""
                sb.appendLine("[${idx + 1}] $title${if (published.isNotBlank()) " ($published)" else ""}")
                sb.appendLine("    URL: $link")
                sb.appendLine("    Resumen: $summary")
            }
            return sb.toString().trimEnd()
        }
    }

    /**
     * install_skill — LLM Genere definiciones de habilidades usted mismo y guárdelas en la biblioteca(Phase 2 autoexpansión).
     *
     * LLM Genere la cadena de definición de habilidad en formato .skill.json,por [SkillImporter.parse] Almacenar después de la verificación.
     * restricciones de seguridad:implementationKotlin Debe ser una de las 4 implementaciones integradas.,No se admite la ejecución de código arbitrario.
     */
}

internal fun formatWebFetchResult(statusCode: Int, body: String): String = "HTTP $statusCode\n$body"

internal fun formatSkillHttpResponse(statusCode: Int, body: String): String = "HTTP $statusCode\n$body"
