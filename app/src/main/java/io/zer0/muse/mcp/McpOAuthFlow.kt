package io.zer0.muse.mcp

import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Phase 10.4 (M3): MCP OAuth 2.1 PKCE Implementación de procesos.
 *
 * Implementar el proceso del código de autorización OAuth 2.1 + PKCE(RFC 7636),Cumplir con los requisitos de autorización de la especificación MCP.:
 *
 * 1. [generatePkcePair]: generar code_verifier + code_challenge(S256)
 * 2. [buildAuthorizationUrl]: Autorización de construcción URL(Abra el navegador con Intent.ACTION_VIEW desde la interfaz de usuario)
 * 3. [exchangeCodeForToken]: Reemplazar con código de autorización + code_verifier access_token
 * 4. [refreshAccessToken]: Renovar con refresco_token access_token
 *
 * PKCE algoritmo(RFC 7636 §4.2):
 * - code_verifier: 43-128 personaje,conjunto de caracteres [A-Z][a-z][0-9]-._~
 * - code_challenge = BASE64URL(SHA256(code_verifier)),eliminar = relleno
 * - code_challenge_method = "S256"
 *
 * Modelo de roscado:Todas las IO de la red en Dispatchers.IO.
 *
 * Escrito de forma independiente(prensa RFC 7636 + RFC 6749 + MCP normativo),Apache 2.0.
 */
object McpOAuthFlow {

    private const val TAG = "McpOAuthFlow"
    private const val VERIFIER_LENGTH = 64 // RFC 7636 Recomendado 43-128,Toma el valor medio 64
    private val VERIFIER_CHARS: CharArray = (
        ('A'..'Z').toList() + ('a'..'z').toList() + ('0'..'9').toList() +
            listOf('-', '.', '_', '~')
        ).toCharArray()

    /** token para intercambio OkHttp client(breve tiempo de espera,OAuth Los procesos no deben bloquearse durante largos períodos de tiempo.). */
    private val httpClient: OkHttpClient by lazy {
        io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * PKCE Par de códigos de verificación.
     *
     * @param verifier code_verifier(valor original,token Enviado durante el intercambio)
     * @param challenge code_challenge(SHA256 + BASE64URL,Enviar URL de autorización)
     * @param state CSRF Proteger cadena aleatoria(Verificación al devolver la llamada)
     */
    data class PkcePair(
        val verifier: String,
        val challenge: String,
        val state: String,
    )

    /**
     * Token respuesta del punto final(Estándar OAuth 2.0 token response).
     */
    @Serializable
    private data class TokenResponse(
        val access_token: String = "",
        val token_type: String = "Bearer",
        val expires_in: Long = 0L,
        val refresh_token: String? = null,
        val scope: String? = null,
        val error: String? = null,
        val error_description: String? = null,
    )

    /** Generar par de códigos de verificación PKCE(code_verifier + code_challenge + state). */
    fun generatePkcePair(): PkcePair {
        val verifier = generateCodeVerifier()
        val challenge = computeCodeChallenge(verifier)
        val state = generateRandomString(32)
        // L-MCP5: Desensibilización en el diario state,Sólo se registran los primeros 4 dígitos
        Logger.d(TAG, "PKCE pair generated: verifier length=${verifier.length}, state=${state.take(4)}***")
        return PkcePair(verifier, challenge, state)
    }

    /**
     * Autorización de construcción URL(Abra el navegador con Intent.ACTION_VIEW desde la interfaz de usuario).
     *
     * El orden de los parámetros sigue la especificación OAuth 2.1,contiene:
     * - response_type=code
     * - client_id
     * - redirect_uri
     * - code_challenge + code_challenge_method=S256
     * - state(CSRF protección)
     * - scope(Opcional)
     */
    fun buildAuthorizationUrl(config: McpOAuthConfig, pkce: PkcePair): String {
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to config.clientId)
            add("redirect_uri" to config.redirectUri)
            add("code_challenge" to pkce.challenge)
            add("code_challenge_method" to "S256")
            add("state" to pkce.state)
            if (config.scopes.isNotBlank()) add("scope" to config.scopes)
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        // El primer parámetro utiliza ? comienzo(Si se incluye el punto final ?,usar & conectar)
        return buildString {
            append(config.authorizationEndpoint.trimEnd('?', '&'))
            append(if (config.authorizationEndpoint.contains("?")) "&" else "?")
            append(query)
        }.also {
            // L-MCP5: Parámetros de estado insensibilizado en registros,Sólo se registran los primeros 4 dígitos
            val maskedUrl = it.replace("state=${pkce.state}", "state=${pkce.state.take(4)}***")
            Logger.d(TAG, "Authorization URL: $maskedUrl")
        }
    }

    /**
     * Cambie el código de autorización por access_token.
     *
     * Enviar como aplicación POST/x-www-form-urlencoded:
     * - grant_type=authorization_code
     * - code=...
     * - redirect_uri=...(Debe ser consistente con la solicitud de autorización.)
     * - client_id=...
     * - code_verifier=...(PKCE verificar)
     *
     * @return Regresar exitosamente [McpTokenInfo],Retorno del fracaso null(error registrado)
     */
    suspend fun exchangeCodeForToken(config: McpOAuthConfig, code: String, verifier: String): McpTokenInfo? = withContext(Dispatchers.IO) {
        if (config.tokenEndpoint.isBlank()) {
            Logger.w(TAG, "tokenEndpoint is blank, cannot exchange code")
            return@withContext null
        }
        // M-OAUTH1: Verificar el uso del tokenEndpoint HTTPS,Evitar la transmisión de texto claro de tokens
        if (!config.tokenEndpoint.startsWith("https://")) {
            Logger.w(TAG, "tokenEndpoint No HTTPS,intercambio de basura token: ${config.tokenEndpoint}")
            return@withContext null
        }
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("client_id", config.clientId)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(config.tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()

        resultOf {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "Token exchange failed: HTTP ${resp.code}")
                    return@resultOf null
                }
                parseTokenResponse(body)
            }
        }.onError { msg, t ->
            Logger.w(TAG, "Token exchange exception: $msg", t)
        }.getOrNull()
    }

    /**
     * Renovar access_token con refresco_token.
     *
     * Enviar como aplicación POST/x-www-form-urlencoded:
     * - grant_type=refresh_token
     * - refresh_token=...
     * - client_id=...
     *
     * @return Devolver nuevo exitosamente [McpTokenInfo](refresh_token puede permanecer sin cambios,Mantener el valor anterior),Retorno del fracaso null
     */
    suspend fun refreshAccessToken(config: McpOAuthConfig, refreshToken: String): McpTokenInfo? = withContext(Dispatchers.IO) {
        if (config.tokenEndpoint.isBlank()) {
            Logger.w(TAG, "tokenEndpoint is blank, cannot refresh")
            return@withContext null
        }
        // M-OAUTH1: Verificar el uso del tokenEndpoint HTTPS,Evitar la transmisión de texto claro de tokens
        if (!config.tokenEndpoint.startsWith("https://")) {
            Logger.w(TAG, "tokenEndpoint No HTTPS,rechazar refresh token: ${config.tokenEndpoint}")
            return@withContext null
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", config.clientId)
            .build()

        val request = Request.Builder()
            .url(config.tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()

        resultOf {
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) {
                    Logger.w(TAG, "Token refresh failed: HTTP ${resp.code}")
                    return@resultOf null
                }
                // refresh La respuesta no puede contener información nueva. refresh_token,Mantén el viejo;server Utilice uno nuevo al rotar el refresco_token
                parseTokenResponse(body)?.let { token ->
                    token.copy(
                        refreshToken = token.refreshToken.ifBlank { refreshToken },
                    )
                }
            }
        }.onError { msg, t ->
            Logger.w(TAG, "Token refresh exception: $msg", t)
        }.getOrNull()
    }

    /**
     * Analizar el parámetro de consulta de la devolución de llamada de redirección_uri,Extraer código y estado.
     *
     * La devolución de llamada parece:io.zer0.muse://oauth/callback?code=xxx&state=yyy
     * en el fracaso(Como por ejemplo error=access_denied)Devuelve nulo.
     */
    fun parseRedirectCallback(redirectUri: String, expectedPrefix: String? = null): Pair<String, String>? {
        // L-OAUTH1: Prefijo de URI de devolución de llamada de validación(Eliminar la parte de consulta),Evite devoluciones de llamadas maliciosas
        if (expectedPrefix != null) {
            val prefix = expectedPrefix.substringBefore('?')
            if (!redirectUri.startsWith(prefix)) {
                Logger.w(TAG, "OAuth El prefijo del URI de devolución de llamada no coincide: expected=$prefix, got=${redirectUri.substringBefore('?')}")
                return null
            }
        }
        val query = redirectUri.substringAfter('?', "")
        if (query.isBlank()) return null
        val params = query.split('&').mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to urlDecode(it.substring(idx + 1))
        }.toMap()

        // OAuth 2.1 respuesta de error:error=...&error_description=...
        params["error"]?.let { err ->
            Logger.w(TAG, "OAuth callback error: $err, desc=${params["error_description"]}")
            return null
        }

        val code = params["code"] ?: return null
        val state = params["state"] ?: return null
        return code to state
    }

    // ── herramientas internas ──────────────────────────────────────────────────────────────

    /** generar code_verifier(43-128 personaje,[A-Z][a-z][0-9]-._~). */
    private fun generateCodeVerifier(): String {
        val random = SecureRandom()
        val sb = StringBuilder(VERIFIER_LENGTH)
        repeat(VERIFIER_LENGTH) {
            sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)])
        }
        return sb.toString()
    }

    /**
     * calcular code_challenge = BASE64URL(SHA256(verifier)).
     * BASE64URL: Estándar BASE64 + → -,/ → _,eliminar = Llenar.
     */
    private fun computeCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Generar cadena aleatoria(state / nonce usar). */
    private fun generateRandomString(length: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) {
            sb.append(VERIFIER_CHARS[random.nextInt(VERIFIER_CHARS.size)])
        }
        return sb.toString()
    }

    /** El análisis de la respuesta JSON del punto final del token es [McpTokenInfo]. */
    private fun parseTokenResponse(body: String): McpTokenInfo? {
        val resp = runCatching {
            AppJson.decodeFromString(TokenResponse.serializer(), body)
        }.getOrNull() ?: run {
            // Algunos servidores devuelven datos no estándar JSON,Intenta usar JsonObject para analizar
            tryParseLooseTokenResponse(body)
        }
        if (resp == null) {
            // v1.114 seguro:El cuerpo que no se pudo analizar puede contener credenciales,Truncar los primeros 200 caracteres;Logger.w automáticamente sanitizePii
            Logger.w(TAG, "Failed to parse token response (body omitted for security)")
            return null
        }
        if (!resp.error.isNullOrEmpty()) {
            Logger.w(TAG, "Token response error: ${resp.error}, desc=${resp.error_description}")
            return null
        }
        if (resp.access_token.isBlank()) {
            Logger.w(TAG, "Token response missing access_token")
            return null
        }
        val expiresAt = if (resp.expires_in > 0) {
            System.currentTimeMillis() + resp.expires_in * 1000L
        } else {
            0L
        }
        return McpTokenInfo(
            accessToken = resp.access_token,
            refreshToken = resp.refresh_token.orEmpty(),
            expiresAt = expiresAt,
            scope = resp.scope.orEmpty(),
        )
    }

    /** Análisis ascendente:Algunos nombres de campos del servidor no están estandarizados(Como token de acceso en lugar de access_token). */
    private fun tryParseLooseTokenResponse(body: String): TokenResponse? {
        val obj = runCatching {
            AppJson.decodeFromString(JsonObject.serializer(), body)
        }.getOrNull() ?: return null
        fun getStr(key: String): String = (obj[key] as? JsonPrimitive)?.content ?: ""
        fun getLong(key: String): Long = (obj[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        return TokenResponse(
            access_token = getStr("access_token").ifBlank { getStr("accessToken") },
            token_type = getStr("token_type").ifBlank { "Bearer" },
            expires_in = getLong("expires_in").takeIf { it > 0 } ?: getLong("expiresIn"),
            refresh_token = getStr("refresh_token").ifBlank { getStr("refreshToken") }.ifBlank { null },
            scope = getStr("scope").ifBlank { null },
            error = getStr("error").ifBlank { null },
            error_description = getStr("error_description").ifBlank { getStr("errorDescription") }.ifBlank { null },
        )
    }

    /** URL codificación(usar java.net.URLEncoder,UTF-8). */
    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    /** URL Descodificar. */
    private fun urlDecode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
}
