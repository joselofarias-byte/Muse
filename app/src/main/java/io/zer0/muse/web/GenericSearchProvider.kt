package io.zer0.muse.web

import io.zer0.common.AppDispatchers
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Motor de búsqueda HTTP universal —— Envía solicitudes según la plantilla rellenada por el usuario y obtén resultados según la ruta.
 *
 * El objetivo del diseño es "conectar un servicio de búsqueda sin cambiar el código", abarcando tres tipos comunes:
 *  1. GET Parámetros de consulta:`https://host/search?q=...`(Categoría SearXNG autohospedada)
 *  2. POST Cuerpo JSON fijo:`{"query":"...","count":5}`(Tavily / Exa Clase I)
 *  3. El resultado está envuelto en un objeto:`{"data":{"results":[...]}}`, usar [CustomSearchEngine.resultsPath] Especificar
 *
 * Cosas que deliberadamente no haces:
 *  - No adivine el encabezado de autenticación. Solo se inyecta cuando apiKey no está vacía `Authorization: Bearer`, otros métodos utilizan una puerta de enlace construida por usted mismo para realizar la transferencia.
 *  - No se realiza ningún análisis de HTML. Para capturar la página de resultados HTML, debe escribir un selector para cada sitio, lo cual depende del proveedor integrado.
 */
internal class GenericSearchProvider(
    private val client: OkHttpClient,
    private val engine: CustomSearchEngine,
) : WebSearchService {
    override val name: String = engine.name.ifBlank { "Custom" }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun search(query: String, maxResults: Int): List<WebSearchResult> = withContext(AppDispatchers.io) {
        resultOf { requestOnce(query, maxResults) }
            .onError { msg, t -> Logger.w(TAG, "$name La búsqueda falló: $msg", t) }
            .getOrNull()
            ?: emptyList()
    }

    private fun requestOnce(query: String, maxResults: Int): List<WebSearchResult> {
        val endpoint = engine.endpoint.trim()
        if (endpoint.isEmpty()) {
            Logger.w(TAG, "$name El punto final no está configurado, omitir")
            return emptyList()
        }

        val isPost = engine.method.equals("POST", ignoreCase = true)
        val url =
            if (isPost) {
                endpoint
            } else {
                val separator = if (endpoint.contains('?')) "&" else "?"
                val param = engine.queryParam.ifBlank { "q" }
                "$endpoint$separator$param=${encode(query)}&count=$maxResults"
            }

        val bodyText = if (isPost) buildBody(query, maxResults) else null
        val builder =
            Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .apply { if (engine.apiKey.isNotBlank()) header("Authorization", "Bearer ${engine.apiKey}") }

        val request =
            if (isPost) {
                builder
                    .header("Content-Type", "application/json")
                    .post((bodyText ?: "{}").toRequestBody(JSON_MEDIA))
                    .build()
            } else {
                builder.get().build()
            }

        return client.newCall(request).execute().use { response ->
            when {
                !response.isSuccessful -> {
                    Logger.w(TAG, "$name HTTP ${response.code}")
                    emptyList()
                }
                else -> {
                    val raw = response.body?.string().orEmpty()
                    if (raw.isBlank()) emptyList() else parseResults(raw, maxResults)
                }
            }
        }
    }

    /** poner {query} / {count} Reemplace la plantilla del cuerpo de la solicitud escrita por el usuario. */
    private fun buildBody(query: String, maxResults: Int): String {
        val template = engine.bodyTemplate.trim()
        if (template.isEmpty()) {
            return buildJsonObject {
                put("query", query)
                put("count", maxResults)
            }.toString()
        }
        return template
            .replace("{query}", escapeJsonString(query))
            .replace("{count}", maxResults.toString())
    }

    /**
     * Obtenga la matriz de resultados por resultsPath.
     *
     * resultsPath Capas de números de puntos de soporte (data.results), también admite subíndices de matriz (data.0.items).
     * Cuando se deja en blanco, el nodo raíz se trata como una matriz.
     */
    private fun parseResults(raw: String, maxResults: Int): List<WebSearchResult> {
        val root = json.parseToJsonElement(raw)
        val array = locateArray(root) ?: run {
            Logger.w(TAG, "$name Matriz de resultados no encontrada en la respuesta (resultsPath='${engine.resultsPath}')")
            return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj.readString(engine.titlePath.ifBlank { "title" })
            val url = obj.readString(engine.urlPath.ifBlank { "url" })
            if (url.isBlank()) return@mapNotNull null
            WebSearchResult(
                title = title.ifBlank { url },
                url = url,
                snippet = obj.readString(engine.snippetPath.ifBlank { "content" }),
                source = name,
            )
        }.take(maxResults)
    }

    private fun locateArray(root: JsonElement): JsonArray? {
        val path = engine.resultsPath.trim()
        if (path.isEmpty()) return root as? JsonArray
        return descend(root, path.split('.'), 0) as? JsonArray
    }

    /** Desciende capa por capa a lo largo del camino del punto; si falta alguna capa, se devuelve nulo. */
    private fun descend(node: JsonElement, segments: List<String>, index: Int): JsonElement? {
        if (index >= segments.size) return node
        val next =
            when (node) {
                is JsonObject -> node[segments[index]]
                is JsonArray -> node.getOrNull(segments[index].toIntOrNull() ?: -1)
                else -> null
            }
        return next?.let { descend(it, segments, index + 1) }
    }

    private fun JsonObject.readString(key: String): String {
        val node = this[key] ?: return ""
        return when (node) {
            is JsonPrimitive -> node.contentOrNull.orEmpty()
            is JsonArray ->
                node.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" ")
            else -> ""
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /** JSON Escape de cadena: solo procese caracteres que deban escaparse para evitar dañar la estructura en la plantilla de usuario. */
    private fun escapeJsonString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    companion object {
        private const val TAG = "GenericSearch"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "Muse/1.0 (Android) SearchClient"
    }
}
