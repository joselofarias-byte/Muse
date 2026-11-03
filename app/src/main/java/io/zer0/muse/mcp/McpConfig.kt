package io.zer0.muse.mcp

import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.ui.SsrfGuard
import kotlinx.serialization.Serializable

/**
 * Phase 9.5 (M3): MCP Tipo de transferencia.
 *
 * - [SSE]: Server-Sent Events transmisión(MCP 2024-11-05 normativo).
 *   Exposición del lado del servidor SSE endpoint(usado para server→client empujar)+ POST endpoint(usado para client→server Solicitar).
 *   Se aplica a versiones anteriores. MCP server(Como por ejemplo @modelcontextprotocol/server-everything v0.x).
 * - [STREAMABLE_HTTP]: Streamable HTTP transmisión(MCP 2025-03-26 normativo).
 *   soltero endpoint,POST Solicitar + Accept: application/json, text/event-stream.
 *   La respuesta puede ser una única secuencia JSON o SSE.(Depende de si el servidor necesita streaming push).
 *   Aplicable a la nueva versión MCP server(2025-03-26 después).
 *
 * - [STDIO]: transferencia de subproceso local (P1-A).
 *   En el tiempo de ejecución de Nodo integrado de la aplicación (MuseRuntime) para iniciar el servidor MCP local (soporte de comandos
 *   node / npm / npx), delimitando la comunicación JSON-RPC mediante líneas stdin/stdout.
 *   (Antecedentes históricos: Zeng Yin Android W^X La decisión límite no es factible y se resolvió mediante la solución de tiempo de ejecución incorporada. )
 */
@Serializable
enum class McpTransportType { SSE, STREAMABLE_HTTP, STDIO }

/**
 * Phase 9.5 (M3): MCP server Estado de la conexión.
 */
enum class McpConnectionState {
    /** No conectado(Inicial / Cerrado). */
    DISCONNECTED,

    /** Conectando(Inicializar apretón de manos). */
    CONNECTING,

    /** Conectado(initialize éxito,Puede enviar y recibir solicitudes). */
    CONNECTED,

    /** Reconectando(Retiro exponencial). */
    RECONNECTING,

    /** La conexión falló(Se superó el número máximo de reintentos). */
    FAILED,

    /** Phase 10.4: Es necesario volver a autorizar la autorización de OAuth(token Caducó y la actualización falló). */
    NEEDS_AUTH,
}

/**
 * Phase 10.4 (M3): MCP OAuth 2.1 PKCE configuración.
 *
 * MCP Requisitos de especificación OAuth 2.1(= OAuth 2.0 + fuerza PKCE + best practices).
 * Proceso de código de autorización + PKCE:
 *  1. client generar code_verifier(43-128 cadena aleatoria de caracteres)
 *  2. code_challenge = BASE64URL(SHA256(code_verifier))
 *  3. Abra el navegador para authorization_endpoint,response_type=code&code_challenge=...
 *  4. Después de la autorización del usuario,server redirigir a redirect_uri,Con parámetro de código
 *  5. client Reemplazar con código + code_verifier access_token + refresh_token
 *  6. Solicitudes de seguimiento Bearer access_token;Utilice refresco_token para renovar cuando caduque
 *
 * @param clientId OAuth client id(Necesita preinscribirse en el servidor o dynamic registration)
 * @param redirectUri devolución de llamada URI(app deep link,Como por ejemplo io.zer0.muse://oauth/callback)
 * @param authorizationEndpoint Página de autorización URL(Como por ejemplo https://server/oauth/authorize)
 * @param tokenEndpoint token Intercambio URL(Como por ejemplo https://server/oauth/token)
 * @param scopes Lista de ámbitos separados por espacios(Como por ejemplo "tools:read tools:call")
 * @param enabled Ya sea para habilitar OAuth(Para cerrar, utilice [McpServerConfig.authToken] estático token)
 */
@Serializable
data class McpOAuthConfig(
    val clientId: String = "",
    val redirectUri: String = "io.zer0.muse://oauth/callback",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val scopes: String = "",
    val enabled: Boolean = false,
)

/**
 * Configuración de identidad de la aplicación de Feishu MCP.
 *
 * Uso de Feishu MCP tenant_access_token(TAT)como encabezado de solicitud,El token tiene un período de validez corto.,
 * No se puede guardar como ficha de portador permanente.Muse Sólo persiste cifrado appId/appSecret,
 * Obtenga el TAT a corto plazo bajo demanda durante el tiempo de ejecución y guárdelo en caché en [McpTokenInfo].
 */
@Serializable
data class McpFeishuAuthConfig(
    val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
) {
    /** ¿Ha completado suficientes credenciales de solicitud?,Puedes intentar cambiar automáticamente por TAT. */
    fun isConfigured(): Boolean = enabled && appId.isNotBlank() && appSecret.isNotBlank()

    /** Devolver una copia de la credencial cifrada,Para la persistencia del almacén de datos. */
    suspend fun encrypted(): McpFeishuAuthConfig = copy(
        appId = SecureKeyStore.encrypt(appId),
        appSecret = SecureKeyStore.encrypt(appSecret),
    )

    /** Devolver una copia de la credencial descifrada,Para usar antes de las solicitudes de MCP. */
    suspend fun decrypted(): McpFeishuAuthConfig = copy(
        appId = SecureKeyStore.decrypt(appId),
        appSecret = SecureKeyStore.decrypt(appSecret),
    )
}

/**
 * Phase 10.4 (M3): OAuth token caché(persistir en DataStore).
 *
 * @param accessToken token de acceso(Traer al solicitar Bearer)
 * @param refreshToken token de actualización(access_token Usar cuando esté caducado)
 * @param expiresAt Marca de tiempo de vencimiento(ms),0 Indica que no tiene vencimiento
 * @param scope realmente autorizado scope(puede diferir de lo solicitado scope)
 */
@Serializable
data class McpTokenInfo(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val scope: String = "",
) {
    /** ¿Ha caducado?(Deje un plazo de entrega de 30 años,Evitar caducar en el momento de la solicitud.). */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt in 1..(now + 30_000L)

    /** ¿Es válido?(Hay accessToken y no ha caducado). */
    fun isValid(now: Long = System.currentTimeMillis()): Boolean = accessToken.isNotBlank() && !isExpired(now)

    /** Si se puede actualizar(si refreshToken). */
    fun canRefresh(): Boolean = refreshToken.isNotBlank()

    /**
     * M-05: Devolver accessToken/refreshToken cifrado(ir [SecureKeyStore.encrypt])copia de,Por llamar antes que la perseverancia.
     * Los valores nulos se dejan como están.(No cifrar valores nulos). Comportamiento coherente con WebServerConfig.encrypted.
     */
    suspend fun encrypted(): McpTokenInfo = copy(
        accessToken = SecureKeyStore.encrypt(accessToken),
        refreshToken = SecureKeyStore.encrypt(refreshToken),
    )

    /**
     * M-05: Devolver accessToken/refreshToken descifrado(ir [SecureKeyStore.decrypt])copia de,Se llama después de leer desde la capa de persistencia.
     * El antiguo texto sin formato se transmite de forma transparente mediante descifrado.(compatibles). Comportamiento coherente con WebServerConfig.decrypted.
     */
    suspend fun decrypted(): McpTokenInfo = copy(
        accessToken = SecureKeyStore.decrypt(accessToken),
        refreshToken = SecureKeyStore.decrypt(refreshToken),
    )
}

/**
 * Phase 9.5 (M3): MCP server configuración.
 *
 * uno [McpServerConfig] Corresponde a un servidor MCP externo.Muse Conéctese a él como cliente MCP,
 * Registre sus herramientas localmente [io.zer0.muse.tools.ToolRegistry],Para llamadas a funciones LLM.
 *
 * @param id solo id(Se utiliza para la persistencia de la sala + prefijo del nombre de la herramienta ToolRegistry)
 * @param name nombre para mostrar(legible por el usuario,Como por ejemplo "GitHub MCP")
 * @param transportType Tipo de transmisión SSE / STREAMABLE_HTTP / STDIO
 * @param url punto final URL:
 *   - SSE: SSE endpoint(Como por ejemplo https://server/sse),client Recibir eventos de esta URL,
 *          POST endpoint Obtenido dinámicamente del campo de punto final en el evento SSE
 *   - STREAMABLE_HTTP: soltero endpoint(Como por ejemplo https://server/mcp),Todas las solicitudes PUBLICAR aquí URL
 * @param headers Encabezado de solicitud personalizado(Como autorización,X-API-Key)
 * @param authToken Bearer token(estático token,OAuth Usado cuando no está habilitado;Prioridad inferior a OAuth token)
 * @param enabled Ya sea para habilitar(Los servidores deshabilitados no se conectan automáticamente)
 * @param autoReconnect Ya sea para reconectarse automáticamente(retroceso exponencial)
 * @param maxReconnectAttempts Número máximo de reconexiones(Predeterminado 5,Cambie al estado FALLADO después de alcanzar)
 * @param reconnectBaseMs Reconectar el intervalo base(Predeterminado 1000ms,retroceso exponencial:base * 2^attempt,límite superior 30s)
 * @param requestTimeoutMs Tiempo de espera de solicitud única(Predeterminado 30s)
 * @param oauthConfig Phase 10.4: OAuth 2.1 PKCE Configuración(enabled=true prioridad al usar OAuth token)
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transportType: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val url: String = "",
    /** P1-A: stdio Comandos de inicio transmitidos (p. ej. "npx -y @modelcontextprotocol/server-filesystem /sdcard"). */
    val command: String = "",
    val headers: Map<String, String> = emptyMap(),
    val authToken: String = "",
    val enabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseMs: Long = 1000L,
    val requestTimeoutMs: Long = 30_000L,
    val oauthConfig: McpOAuthConfig = McpOAuthConfig(),
    /** Feishu tenant_access_token actualiza automáticamente la configuración,Desactivado de forma predeterminada para mantener la compatibilidad con configuraciones de MCP anteriores. */
    val feishuAuth: McpFeishuAuthConfig = McpFeishuAuthConfig(),
    /** v1.0.92 (B-4): La marca de tiempo de la última conexión exitosa.(0=nunca lo logré);Visualización de la página de configuración"Última conexión"Usar. */
    val lastConnectedAt: Long = 0L,
    /** v1.0.92 (B-2): Tiempo de espera a que esta herramienta del servidor esté lista(milisegundos);Tome el valor máximo cuando varios servidores estén esperando al mismo tiempo. */
    val toolsReadyTimeoutMs: Long = 5_000L,
) {
    /**
     * Fusionar encabezados y authToken,Authorization Utilice encabezados primero.
     *
     * Phase 10.4: OAuth token por [McpClient] Inyectado dinámicamente antes de la solicitud.(No se maneja de esta manera),
     * Dado que el token de OAuth puede caducar, es necesario refresh,lógica en McpClient.ensureValidToken() adentro.
     * Este método solo maneja estática authToken(Escenarios que no son OAuth).
     */
    fun resolvedHeaders(): Map<String, String> {
        val result = headers.toMutableMap()
        if (authToken.isNotBlank() && !result.containsKey("Authorization")) {
            result["Authorization"] = "Bearer $authToken"
        }
        return result
    }

    /**
     * M-05: Devolver authToken cifrado(ir [SecureKeyStore.encrypt])copia de,Por llamar antes que la perseverancia.
     * Los valores nulos se dejan como están.(No cifrar valores nulos). Comportamiento coherente con WebServerConfig.encrypted.
     */
    suspend fun encrypted(): McpServerConfig = copy(
        headers = headers.mapValues { (key, value) ->
            if (isSensitiveHeader(key)) SecureKeyStore.encrypt(value) else value
        },
        authToken = SecureKeyStore.encrypt(authToken),
        feishuAuth = feishuAuth.encrypted(),
    )

    private fun isSensitiveHeader(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("authorization") ||
            normalized.contains("api-key") ||
            normalized.contains("apikey") ||
            normalized.contains("token") ||
            normalized.contains("secret") ||
            normalized.contains("password")
    }

    /**
     * M-05: Devolver authToken descifrado(ir [SecureKeyStore.decrypt])copia de,Se llama después de leer desde la capa de persistencia.
     * El antiguo texto sin formato se transmite de forma transparente mediante descifrado.(compatibles). Comportamiento coherente con WebServerConfig.decrypted.
     */
    suspend fun decrypted(): McpServerConfig = copy(
        headers = headers.mapValues { (key, value) ->
            if (isSensitiveHeader(key)) SecureKeyStore.decrypt(value) else value
        },
        authToken = SecureKeyStore.decrypt(authToken),
        feishuAuth = feishuAuth.decrypted(),
    )

    /**
     * G2: SSRF protección — Negarse a apuntar el servidor MCP a la red privada RFC1918/enlace local 169.254 /
     * nube metadata(169.254.169.254)/ ULA Esperar dirección que no sea de confianza(Anti-LLM mcp_server_configure
     * Ser inducido a dirigir el tráfico a la intranet.).
     *
     * excepción:permitir loopback(localhost / 127.x / ::1)—— MCP A menudo apunta a servicios de desarrollo nativos.
     * (Ollama / Host MCP local/herramienta web integrada server). juicio:URL debe ser http/https,
     * Si la dirección es toda loopback, se permitirá.;El resto es juzgado por SsrfGuard por su convergencia.
     */
    fun isSsrfBlocked(): Boolean {
        // P1-A: stdio Sin punto final de red,El rostro de la SSRF no existe
        if (transportType == McpTransportType.STDIO) return false
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: return true // No se puede analizar → rechazo conservador
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return true
        val host = uri.host ?: return true
        val resolved = runCatching { java.net.InetAddress.getAllByName(host) }.getOrNull()
            ?: return true // El análisis falló → rechazo conservador
        val all = resolved.filter { !it.isLoopbackAddress }
        if (all.isEmpty()) return false // lleno loopback(localhost/127.x/::1)→ permitir locales MCP
        return SsrfGuard.isBlocked(url)
    }
}
