package io.zer0.muse.mcp

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Phase 11.1.3: MCP Dynamic Client Registration(RFC 7591 + RFC 9728 + RFC 8414).
 *
 * MCP normativo(2025-03-26 Authorization)pedido OAuth 2.1,y recomendar el registro dinámico de clientes para
 * Escenario sin prerregistro client_id. El proceso completo consta de tres pasos.:
 *
 * 1. **descubrimiento de metadatos**(RFC 9728 + RFC 8414):
 *    - GET `{server_url}/.well-known/oauth-protected-resource` → Regresar `authorization_servers` matriz
 *    - GET `{auth_server}/.well-known/oauth-authorization-server` → Regresar
 *      `authorization_endpoint` / `token_endpoint` / `registration_endpoint`
 *
 * 2. **Registro dinámico**(RFC 7591):
 *    - POST `registration_endpoint`(application/json)
 *    - Cuerpo de la solicitud:`client_name` / `redirect_uris` / `token_endpoint_auth_method="none"` /
 *      `grant_types=["authorization_code","refresh_token"]` / `response_types=["code"]` /
 *      `scope` / `code_challenge_method="S256"`
 *    - respuesta(201 Created):`client_id`(Los clientes públicos normalmente no tienen client_secret)
 *
 * 3. **PKCE Autorizar**(Reutilización de la Fase 10.4 [McpOAuthFlow]):
 *    - Obtenido usando el Paso 2 `client_id` + PKCE pair Siga el proceso del código de autorización
 *
 * Conexión a la Fase 10.4:
 *  - actual [McpOAuthConfig] Requerir que los usuarios completen manualmente `clientId` / endpoints
 *  - Esto es lo que debe resolver el registro dinámico"Configuración manual client_id"Puntos de dolor
 *  - Después de un registro exitoso,poner `clientId` / endpoints rellenar para [McpOAuthConfig],persistir en DataStore
 *
 * Escrito de forma independiente(Según RFC 7591/9728/8414 + especificación MCP),Apache 2.0.
 */
object McpDynamicRegistration {

    private const val TAG = "McpDynReg"

    /** HTTP cliente(breve tiempo de espera,Evite el retraso en la interfaz de usuario). */
    private val httpClient: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Resultados del descubrimiento de metadatos.
     *
     * @param authorizationEndpoint Página de autorización URL(Como por ejemplo https://server/oauth/authorize)
     * @param tokenEndpoint token Intercambio URL(Como por ejemplo https://server/oauth/token)
     * @param registrationEndpoint Registro dinámico URL(Como por ejemplo https://server/oauth/register);
     *        null Indica que este servidor de autenticación no admite el registro dinámico
     * @param scopesSupported Lista de ámbitos admitidos(Opcional,para indicaciones de la interfaz de usuario)
     */
    @Serializable
    data class McpAuthMetadata(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String? = null,
        val scopesSupported: List<String> = emptyList(),
    )

    /**
     * Resultados de registro dinámico.
     *
     * @param clientId obtenido por registro client_id
     * @param clientIdIssuedAt Marca de tiempo del problema(unix segundos,0 Indica que no hay retorno)
     * @param clientSecret clave de cliente(Los clientes públicos suelen estar vacíos.)
     * @param clientSecretExpiresAt Marca de tiempo de vencimiento(unix segundos,0 Indica que no tiene vencimiento)
     */
    @Serializable
    data class RegisteredClient(
        val clientId: String,
        val clientIdIssuedAt: Long = 0L,
        val clientSecret: String = "",
        val clientSecretExpiresAt: Long = 0L,
    )

    /**
     * Step 1: Descubrimiento de metadatos.
     *
     * A partir de la URL del servidor MCP,comprobar primero Protected Resource Metadata(RFC 9728)encontró
     * authorization_servers,Verifique el primer servidor de autenticación nuevamente Authorization Server Metadata
     * (RFC 8414)Obtenga puntos finales.
     *
     * @param serverUrl MCP server URL(Como por ejemplo https://server.example.com/mcp)
     * @return Metadatos;Retorno del fracaso null
     */
    suspend fun discoverMetadata(serverUrl: String): McpAuthMetadata? = withContext(Dispatchers.IO) {
        val base = baseUrl(serverUrl) ?: return@withContext null
        resultOf {
            // Step 1a: GET {base}/.well-known/oauth-protected-resource
            val resourceMeta = fetchJson("$base/.well-known/oauth-protected-resource") ?: return@resultOf null
            val authServers = resourceMeta["authorization_servers"]
                ?.let { parseStringArray(it) }
                ?: emptyList()
            if (authServers.isEmpty()) {
                // compatibles:Algunos servidores colocan directamente puntos finales en los metadatos de recursos.
                val authEndpoint = resourceMeta["authorization_endpoint"]?.jsonPrimitive?.contentOrNull
                val tokenEndpoint = resourceMeta["token_endpoint"]?.jsonPrimitive?.contentOrNull
                if (authEndpoint != null && tokenEndpoint != null) {
                    return@resultOf McpAuthMetadata(
                        authorizationEndpoint = authEndpoint,
                        tokenEndpoint = tokenEndpoint,
                        registrationEndpoint = resourceMeta["registration_endpoint"]?.jsonPrimitive?.contentOrNull,
                        scopesSupported = parseScopes(resourceMeta["scopes_supported"]),
                    )
                }
                return@resultOf null
            }

            // pasos 1b: GET {auth_server}/.well-known/oauth-authorization-server
            for (authServer in authServers) {
                val authMeta = fetchJson("$authServer/.well-known/oauth-authorization-server") ?: continue
                val authEndpoint = authMeta["authorization_endpoint"]?.jsonPrimitive?.contentOrNull ?: continue
                val tokenEndpoint = authMeta["token_endpoint"]?.jsonPrimitive?.contentOrNull ?: continue
                return@resultOf McpAuthMetadata(
                    authorizationEndpoint = authEndpoint,
                    tokenEndpoint = tokenEndpoint,
                    registrationEndpoint = authMeta["registration_endpoint"]?.jsonPrimitive?.contentOrNull,
                    scopesSupported = parseScopes(authMeta["scopes_supported"]),
                )
            }
            null
        }.onError { msg, t -> Logger.w(TAG, "discoverMetadata falló: $msg", t) }
            .getOrNull()
    }

    /**
     * Step 2: Registro de cliente dinámico(RFC 7591).
     *
     * @param registrationEndpoint Registrar punto final(de [discoverMetadata] conseguir)
     * @param redirectUri devolución de llamada URI(Debe ser coherente con las expectativas del servidor MCP.,Como por ejemplo io.zer0.muse://oauth/callback)
     * @param clientName nombre del cliente(Predeterminado "muse")
     * @param scopes Lista de ámbitos separados por espacios(Como por ejemplo "tools:read tools:call")
     * @return Resultados del registro;Retorno del fracaso null
     */
    suspend fun registerClient(
        registrationEndpoint: String,
        redirectUri: String,
        clientName: String = "muse",
        scopes: String = "",
    ): RegisteredClient? = withContext(Dispatchers.IO) {
        resultOf {
            // M-DYNREG1: Utilice buildJsonObject para construir el cuerpo de la solicitud,Evite que JSON escrito a mano escape a los caracteres de control que faltan
            val requestBody = buildJsonObject {
                put("client_name", clientName)
                put("redirect_uris", JsonArray(listOf(JsonPrimitive(redirectUri))))
                put("token_endpoint_auth_method", "none")
                put(
                    "grant_types",
                    JsonArray(
                        listOf(
                            JsonPrimitive("authorization_code"),
                            JsonPrimitive("refresh_token"),
                        ),
                    ),
                )
                put("response_types", JsonArray(listOf(JsonPrimitive("code"))))
                if (scopes.isNotBlank()) {
                    put("scope", scopes)
                }
                put("code_challenge_method", "S256")
            }
            val req = Request.Builder()
                .url(registrationEndpoint)
                .post(AppJson.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "registerClient falló: HTTP ${resp.code}")
                    return@resultOf null
                }
                val body = resp.body.string()
                val obj = AppJson.decodeFromString(JsonObject.serializer(), body)
                val clientId = obj["client_id"]?.jsonPrimitive?.contentOrNull ?: return@resultOf null
                RegisteredClient(
                    clientId = clientId,
                    clientIdIssuedAt = obj["client_id_issued_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    clientSecret = obj["client_secret"]?.jsonPrimitive?.contentOrNull ?: "",
                    clientSecretExpiresAt = obj["client_secret_expires_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            }
        }.onError { msg, t -> Logger.w(TAG, "registerClient falló: $msg", t) }
            .getOrNull()
    }

    /**
     * Completa con un clic"Descubrir + Registrarse"dos pasos,Devolver el llenado [McpOAuthConfig](no contiene token,token Tomado por el proceso PKCE).
     *
     * @param serverUrl MCP server URL
     * @param redirectUri devolución de llamada URI
     * @param scopes espacio separado scope
     * @return Complete clientId + puntos finales [McpOAuthConfig];Retorno del fracaso null
     */
    suspend fun discoverAndRegister(
        serverUrl: String,
        redirectUri: String = "io.zer0.muse://oauth/callback",
        scopes: String = "",
    ): McpOAuthConfig? = withContext(Dispatchers.IO) {
        val meta = discoverMetadata(serverUrl) ?: return@withContext null
        val regEndpoint = meta.registrationEndpoint ?: return@withContext null
        // M-DYNREG2: Verifique que registroEndpoint tenga el mismo origen que serverUrl,prevenir Open Redirect
        val regBase = baseUrl(regEndpoint)
        val serverBase = baseUrl(serverUrl)
        if (regBase == null || serverBase == null || regBase != serverBase) {
            Logger.w(TAG, "registrationEndpoint Origen diferente al serverUrl: $regEndpoint vs $serverUrl,rechazar")
            return@withContext null
        }
        val registered = registerClient(regEndpoint, redirectUri, scopes = scopes) ?: return@withContext null
        McpOAuthConfig(
            clientId = registered.clientId,
            redirectUri = redirectUri,
            authorizationEndpoint = meta.authorizationEndpoint,
            tokenEndpoint = meta.tokenEndpoint,
            scopes = scopes,
            enabled = true,
        )
    }

    // ── auxiliar ──────────────────────────────────────────────────────────────

    /** Extracto de la URL completa base(scheme://host[:port],no contiene path). */
    private fun baseUrl(url: String): String? {
        val idx = url.indexOf("://").takeIf { it > 0 } ?: return null
        val afterScheme = url.substring(idx + 3)
        val slashIdx = afterScheme.indexOf('/')
        val base = if (slashIdx > 0) afterScheme.substring(0, slashIdx) else afterScheme
        return url.substring(0, idx + 3) + base
    }

    /** GET Solicitar y analizar JSON. Devuelve nulo en caso de error. */
    private fun fetchJson(url: String): JsonObject? {
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body.string()
                AppJson.decodeFromString(JsonObject.serializer(), body)
            }
        }.onFailure { Logger.w(TAG, "fetchJson($url) falló: ${it.message}") }
            .getOrNull()
    }

    /** Analizar el campo ámbitos_compatibles(JSON matriz of string). */
    private fun parseScopes(el: kotlinx.serialization.json.JsonElement?): List<String> {
        if (el == null) return emptyList()
        return parseStringArray(el)
    }

    /** Universales:Analizar la matriz JSON de cadena. */
    private fun parseStringArray(el: kotlinx.serialization.json.JsonElement): List<String> {
        val arr = el as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    }
}
