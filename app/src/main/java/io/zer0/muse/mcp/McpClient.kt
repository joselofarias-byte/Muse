package io.zer0.muse.mcp

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 9.5 (M3): MCP Clase de datos de protocolo.
 *
 * Según la especificación MCP:
 * - 2024-11-05: https://spec.modelcontextprotocol.io/spec/2024-11-05/
 * - 2025-03-26: https://spec.modelcontextprotocol.io/spec/2025-03-26/
 *
 * JSON-RPC 2.0 Acuerdo:
 * - Solicitar: {jsonrpc:"2.0", id, method, params?}
 * - respuesta: {jsonrpc:"2.0", id, result? | error?}
 * - Notificación: {jsonrpc:"2.0", method, params?}(Ninguno id)
 */

@Serializable
data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonElement? = null,
)

@Serializable
data class McpToolCallResult(
    val content: List<JsonElement> = emptyList(),
    val isError: Boolean = false,
    /** MCP 2025-03-26 Resultados de herramientas estructuradas opcionales,Algunos servidores solo devuelven datos comerciales aquí. */
    val structuredContent: JsonElement? = null,
)

/**
 * Phase 11.1.2: MCP Resource(MCP 2025-03-26 spec § Resources).
 *
 * Resource Es un recurso pasivo expuesto por el servidor.(traer URI),por usuario/El cliente elige inyectar el contexto de la conversación.
 * Diferencias con la herramienta:Tool LLM lo convoca activamente.,Resource Es una elección pasiva del usuario.
 *
 * @param uri Identificador único de recurso(Como por ejemplo file:///doc.md / db://schema/users)
 * @param name Nombre del recurso(legible por el usuario)
 * @param description Descripción(Opcional)
 * @param mimeType MIME Tipo(Opcional,Como por ejemplo text/markdown / application/json)
 */
@Serializable
data class McpResource(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
)

/**
 * Phase 11.1.2: MCP Resource contenido(resources/read respuesta).
 *
 * @param uri Recursos URI
 * @param mimeType MIME Tipo(Opcional)
 * @param text contenido del texto(alternativa a la burbuja)
 * @param blob Contenido binario codificado en base64.(Alternativa al texto)
 */
@Serializable
data class McpResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

/**
 * Phase 11.1.2: MCP Prompt(MCP 2025-03-26 spec § Prompts).
 *
 * Prompt ¿La plantilla de comando de acceso directo está expuesta por el servidor?,Devuelve un grupo después de que el usuario hace clic en el disparador messages,
 * Sirve como punto de partida para una nueva conversación o inserta en la conversación actual.
 *
 * @param name prompt nombre único(Usado como indicaciones/obtener key)
 * @param description Descripción(Opcional)
 * @param arguments Lista de parámetros(Opcional,Pasar después de que el usuario complete prompts/get)
 */
@Serializable
data class McpPrompt(
    val name: String,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
)

/** Prompt Definición de parámetros. */
@Serializable
data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
)

/**
 * Phase 11.1.2: MCP Prompt contenido(prompts/get respuesta).
 *
 * @param description Descripción(Opcional)
 * @param messages Lista de mensajes(role + content),como punto de partida para la conversación
 */
@Serializable
data class McpPromptResult(
    val description: String? = null,
    val messages: List<McpPromptMessage> = emptyList(),
)

/**
 * Prompt noticias(role + content).
 *
 * @param role user / assistant / system
 * @param content Contenido del mensaje(JsonObject,puede ser {type:"text",text:"..."} y otras formas)
 */
@Serializable
data class McpPromptMessage(
    val role: String,
    val content: JsonElement,
)

/**
 * Espere el primer evento de punto final de SSE.
 *
 * EventSource El oyente cerrará el canal en caso de fallo. llamar directamente [ReceiveChannel.receive]
 * puede tirar ClosedReceiveChannelException,y“Servidor desconectado”Sólo un error de conexión normal,no puedo dejar
 * MCP La rutina en segundo plano bloquea toda la aplicación.
 */
internal suspend fun awaitSseEndpoint(channel: ReceiveChannel<String>, timeoutMs: Long): String? = withTimeoutOrNull(timeoutMs) {
    channel.receiveCatching().getOrNull()
}

@Serializable
internal data class McpServerInfo(
    val name: String? = null,
    val version: String? = null,
)

/**
 * Phase 9.5 (M3): MCP Client.
 *
 * Responsabilidades:
 *  1. Establecer conexión SSE / StreamableHTTP,Completar el protocolo de enlace de inicialización
 *  2. proporcionar [listTools] / [callTool] suspend API(Herramientas/lista de MCP de referencia,tools/call)
 *  3. Reconectarse automáticamente(retroceso exponencial:base * 2^attempt,límite superior 30s,alcanzar [McpServerConfig.maxReconnectAttempts] girar FAILED)
 *  4. expuesto [state] StateFlow Para que la interfaz de usuario observe el estado de la conexión
 *
 * Implementación del protocolo:
 *  - initialize Solicitud para traer protocolVersion(2025-03-26 / 2024-11-05,server Elige tu propia versión compatible)
 *  - Initialized Las notificaciones se envían después de la respuesta de inicialización.(MCP El segundo paso del apretón de manos requerido.)
 *  - tools/list La respuesta resuelve [McpTool] lista
 *  - tools/call Solicitud para traer name + arguments(JsonObject),La respuesta resuelve [McpToolCallResult]
 *
 * Implementación de transmisión:
 *  - [SseTransport]: EventSource recibir server→client evento,POST Notificado por el servidor endpoint
 *  - [StreamableHttpTransport]: soltero endpoint POST,La respuesta puede ser una secuencia JSON o SSE
 *
 * Modelo de roscado:
 *  - Todas las E/S de la red en Dispatchers.IO
 *  - Cambio de estado aprobado MutableStateFlow,Seguridad del hilo
 *  - Solicitar-Para emparejamiento de respuestas ConcurrentHashMap<id, Channel<JsonObject>>(Admitir solicitudes simultáneas)
 *
 * Escrito de forma independiente(Según especificaciones oficiales de MCP),Apache 2.0.
 */
class McpClient(
    private val config: McpServerConfig,
    private val client: OkHttpClient = McpClient.defaultClient,
    /** Phase 10.4: SettingsRepository inyectar(OAuth Se utiliza para la persistencia del token cuando está habilitado;null Ir estático token). */
    private val settings: io.zer0.muse.data.SettingsRepository? = null,
    /** P1-A: contexto de la aplicación (stdio Se utiliza para rutas de ejecución integradas de resolución de transporte; puede ser nulo para otros transportes). */
    private val appContext: android.content.Context? = null,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idCounter = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, Channel<JsonObject>>()

    private val _state = MutableStateFlow(McpConnectionState.DISCONNECTED)
    val state: StateFlow<McpConnectionState> = _state.asStateFlow()

    /** Conexión SSE actual(Cancelar cuando se usa con cerrar). */
    @Volatile
    private var eventSource: EventSource? = null

    /** P1-A: stdio transmisión(proceso secundario local). */
    @Volatile
    private var stdioTransport: McpStdioTransport? = null

    /** SSE en modo transferencia POST endpoint(server Notificado a través de un evento de punto final). */
    @Volatile
    private var postEndpoint: String? = null

    /**
     * Streamable HTTP ID de sesión.
     *
     * MCP 2025-03-26 Permitir que el servidor responda durante la inicialización
     * `Mcp-Session-Id` Asignar sesión en el encabezado de respuesta,Herramientas/lista de seguimiento、tools/call Debe esperar la solicitud.
     * Recupere el encabezado de la solicitud tal como está. Cuando no existe tal cabeza,Muchos servidores harán que el apretón de manos parezca exitoso.,Pero las solicitudes posteriores regresan.
     * 404/400,El modelo termina sin recibir ninguna herramienta MCP.
     */
    @Volatile
    private var streamableSessionId: String? = null

    /** initialize Versión del protocolo MCP negociada,Las solicitudes HTTP posteriores se envían según la versión seleccionada por el servidor. */
    @Volatile
    private var negotiatedProtocolVersion: String = PROTOCOL_VERSION

    /** server Después de terminar la sesión,Es necesario reinicializar la siguiente solicitud. */
    @Volatile
    private var streamableSessionInvalidated = false

    /** Streamable HTTP La recuperación de sesión es mutuamente excluyente para evitar inicializaciones repetidas para solicitudes simultáneas. */
    private val streamableSessionRecoveryMutex = Mutex()

    /** Se incrementa cada vez que se reemplaza realmente la sesión HTTP Streamable, lo que se utiliza para permitir que solicitudes simultáneas reutilicen la misma ronda de resultados de recuperación. */
    private val streamableSessionGeneration = AtomicLong(0)

    /** Resumen de la falla más reciente de la capa de transporte,Guardar solo el código de estado/Categoría de red,El cuerpo de la respuesta o las credenciales no se guardan. */
    @Volatile
    private var lastTransportFailure: String? = null

    /** Información del servidor(initialize Volver en respuesta). */
    @Volatile
    private var serverInfo: McpServerInfo? = null

    /** Número de reconexiones(Restablecer después de una conexión exitosa). */
    @Volatile
    private var reconnectAttempts = 0

    /** ¿Has tomado la iniciativa? close(Distinguir entre apagado activo y desconexión de red,El cierre activo no desencadena la reconexión). */
    @Volatile
    private var closed = false

    /** Phase 10.4: OAuth token caché de memoria(Cargar desde la configuración,refresh Actualizar más tarde). */
    @Volatile
    private var oauthToken: McpTokenInfo? = null

    /** Memoria caché Feishu TAT(corto plazo token,Las credenciales reales todavía se almacenan en la clave token cifrada del repositorio de configuración.). */
    @Volatile
    private var feishuTenantToken: McpTokenInfo? = null

    private val feishuAuthEnabled: Boolean
        get() = config.feishuAuth.isConfigured() && settings != null

    private val feishuTenantTokenClient = McpFeishuTenantTokenClient()
    private val feishuRefreshMutex = Mutex()

    /** Phase 10.4: actual PKCE pair(startOAuthFlow generar,completeOAuthFlow consumo). */
    @Volatile
    private var pendingPkce: McpOAuthFlow.PkcePair? = null

    /** Phase 10.4: Ya sea para habilitar OAuth. */
    private val oauthEnabled: Boolean get() = config.oauthConfig.enabled && settings != null

    /**
     * Phase 9.5 (P3-2): tools/list_changed Devolución de llamada de notificación.
     *
     * Cuando el servidor emite `notifications/tools/list_changed`(MCP 2025-03-26 §Tools)desencadenado cuando,
     * por [McpRegistry] Implementación de inyección:Vuelva a extraer herramientas/lista y vuelva a registrarse en ToolRegistry.
     *
     * Diseño desacoplado:McpClient no depende de Registry,Lanzar eventos solo a través de devoluciones de llamada,Mantenga las dependencias en una dirección.
     * Devolución de llamada al [handleNotification] La ejecución del contexto de rutina IO,Los implementadores deben evitar bloquear durante largos períodos de tiempo.
     * (Registry El cliente lanzará una rutina independiente para realizar la extracción real.).
     */
    @Volatile
    var onToolsListChanged: (() -> Unit)? = null

    /**
     * P2-24: Devolución de llamada exitosa de reconexión automática(Solo se activa cuando la reconexión después de RECONECTAR es exitosa,La primera conexión no se activa.).
     * Registry Sincronice el estado de la conexión en consecuencia y vuelva a tirar de la herramienta(El conjunto de herramientas del servidor puede cambiar durante la desconexión).
     * La devolución de llamada se ejecuta en el contexto de la rutina de conexión.,Los implementadores deben evitar bloquear durante largos períodos de tiempo.(Registry La terminal lanzará la corrutina independiente.).
     */
    @Volatile
    var onReconnected: (() -> Unit)? = null

    /** B3-09: prompts/list_changed devolución de llamada de notificación(Cuando no hay caché, solo se utilizan señales de actualización.). */
    @Volatile
    var onPromptsListChanged: (() -> Unit)? = null

    /** B3-09: resources/list_changed devolución de llamada de notificación(Cuando no hay caché, solo se utilizan señales de actualización.). */
    @Volatile
    var onResourcesListChanged: (() -> Unit)? = null

    /**
     * Inicie el cliente MCP. Establecer conexión + inicializar el protocolo de enlace.
     * Si falla, se activará el proceso de reconexión.(si [McpServerConfig.autoReconnect] para true).
     */
    fun start() {
        // M-MCP4: synchronized proteger check-then-launch,Evite condiciones de carrera que conduzcan a conexiones duplicadas
        synchronized(this) {
            if (closed) return
            if (_state.value != McpConnectionState.DISCONNECTED &&
                _state.value != McpConnectionState.FAILED &&
                _state.value != McpConnectionState.NEEDS_AUTH
            ) {
                return
            }
            scope.launch { connect() }
        }
    }

    /**
     * Establecer conexión sincrónicamente + inicializar el protocolo de enlace.
     * Llamado en la rutina IO. Activador de falla [scheduleReconnect].
     *
     * Phase 10.4: OAuth Cuando está habilitado,Primero asegúrese de que el token sea válido.(Cargar/refresh);Ningún token o actualización falló
     * Ir al estado NEEDS_AUTH(Espere el ajuste de la interfaz de usuario [startOAuthFlow] Guiar a los usuarios para que autoricen).
     */
    /**
     * C-25: OAuth Enmascaramiento del registro de URI de devolución de llamada — solo quédate scheme://host/path,descartar query
     * (query Con autorización code / state,Todo el registro de entrada filtrará el código de autorización.).
     */
    private fun sanitizeRedirectUriForLog(uri: String): String = runCatching {
        val u = java.net.URI(uri)
        "${u.scheme}://${u.host}${u.rawPath ?: ""}"
    }.getOrElse { "(unparseable redirect uri)" }

    private suspend fun connect() {
        try {
            connectInternal()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            Logger.e(TAG, "[${config.name}] MCP Proceso de conexión anormal,Transferido a reconexión/estado de falla", e)
            if (!closed && _state.value != McpConnectionState.FAILED) {
                scheduleReconnect()
            }
        }
    }

    private suspend fun connectInternal() {
        _state.value = McpConnectionState.CONNECTING
        // Cada vez que se crea una nueva conexión HTTP Streamable, se debe volver a obtener el ID de sesión desde la inicialización.
        streamableSessionId = null
        negotiatedProtocolVersion = PROTOCOL_VERSION
        streamableSessionInvalidated = false
        val endpointDesc = if (config.transportType == McpTransportType.STDIO) {
            "command=${config.command}"
        } else {
            "url=${config.url}"
        }
        Logger.d(TAG, "[${config.name}] Conectando transport=${config.transportType} $endpointDesc")

        // Phase 10.4: OAuth Verificar primero en modo token
        if (oauthEnabled && !ensureValidToken()) {
            Logger.w(TAG, "[${config.name}] OAuth token Inválido o no autorizado,girar NEEDS_AUTH")
            _state.value = McpConnectionState.NEEDS_AUTH
            return
        }
        if (config.feishuAuth.enabled && !ensureFeishuTenantToken()) {
            Logger.w(TAG, "[${config.name}] La adquisición de Feishu TAT fracasó,Entrar en reconexión/estado de falla")
            scheduleReconnect()
            return
        }

        val connected = when (config.transportType) {
            McpTransportType.SSE -> connectSse()
            McpTransportType.STREAMABLE_HTTP -> connectStreamableHttp()
            McpTransportType.STDIO -> connectStdio()
        }

        if (connected) {
            val wasReconnect = reconnectAttempts > 0
            reconnectAttempts = 0
            _state.value = McpConnectionState.CONNECTED
            Logger.i(TAG, "[${config.name}] Conectado,server=${serverInfo?.name}:${serverInfo?.version}")
            // P2-24: Después de una reconexión exitosa, notifique al Registro sobre el estado de sincronización y actualice la herramienta.(Utilice connectServer para la primera conexión registerTools)
            if (wasReconnect) {
                runCatching { onReconnected?.invoke() }
                    .onFailure { e -> Logger.w(TAG, "[${config.name}] onReconnected Excepción de devolución de llamada: ${e.message}") }
            }
        } else {
            Logger.w(TAG, "[${config.name}] La conexión falló")
            scheduleReconnect()
        }
    }

    // ── Phase 10.4: OAuth 2.1 PKCE ───────────────────────────────────────────

    /**
     * Asegúrese de que el token de OAuth sea válido:cargar caché → cheque vencido → refresh.
     * @return true Indica que actualmente hay una válida token;false Indica no autorizado/actualización fallida(Requiere reautorización)
     */
    private suspend fun ensureValidToken(forceRefresh: Boolean = false): Boolean {
        if (!oauthEnabled) return true
        // primera carga:Leer caché desde DataStore
        if (oauthToken == null) {
            oauthToken = settings?.getMcpToken(config.id)
        }
        val token = oauthToken ?: return false // No autorizado
        // M-MCP2: forceRefresh=true obligatorio refresh(Se utiliza para solicitar volver a intentarlo después de recibir 401.)
        if (!forceRefresh && token.isValid()) return true
        // Caducado → intentarlo refresh
        if (!token.canRefresh()) {
            settings?.clearMcpToken(config.id)
            oauthToken = null
            return false
        }
        Logger.d(TAG, "[${config.name}] OAuth token Caducado,intentarlo refresh")
        val newToken = McpOAuthFlow.refreshAccessToken(config.oauthConfig, token.refreshToken)
        if (newToken == null) {
            Logger.w(TAG, "[${config.name}] OAuth refresh falló,Requiere reautorización")
            settings?.clearMcpToken(config.id)
            oauthToken = null
            return false
        }
        oauthToken = newToken
        settings?.saveMcpToken(config.id, newToken)
        Logger.i(TAG, "[${config.name}] OAuth token refreshed,El nuevo período de validez es ${newToken.expiresAt}")
        return true
    }

    /**
     * Asegúrese de que Feishu tenant_access_token sea válido.
     *
     * Manejado por separado de MCP OAuth:Feishu TAT No refresh_token,Usar después de la caducidad app_id +
     * app_secret Intercambiar de nuevo. El TAT a corto plazo se conserva en el almacenamiento de tokens cifrados existente.,Pero nunca escribas un diario.
     */
    private suspend fun ensureFeishuTenantToken(forceRefresh: Boolean = false): Boolean {
        if (!config.feishuAuth.enabled) return true
        if (!feishuAuthEnabled) {
            Logger.w(TAG, "[${config.name}] La actualización automática de Feishu está habilitada,Pero falta el ID de la aplicación/Secreto de la aplicación o el Repositorio de configuración")
            return false
        }
        return feishuRefreshMutex.withLock {
            if (feishuTenantToken == null) {
                feishuTenantToken = settings?.getMcpToken(config.id)
            }
            if (!forceRefresh && feishuTenantToken?.isUsableForFeishu() == true) return@withLock true

            val auth = config.feishuAuth
            val token = feishuTenantTokenClient.fetch(auth.appId, auth.appSecret)
                ?: return@withLock false
            feishuTenantToken = token
            settings?.saveMcpToken(config.id, token)
            Logger.i(TAG, "[${config.name}] Feishu TAT ha sido actualizado,Válido hasta ${token.expiresAt}")
            true
        }
    }

    /** El token Feishu se actualiza cuando quedan menos de 30 minutos.,Esto evita que varios dispositivos cambien tokens con frecuencia y provoquen que los demás dejen de ser válidos. */
    private fun McpTokenInfo.isUsableForFeishu(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && (expiresAt == 0L || expiresAt > now + FEISHU_REFRESH_MARGIN_MS)

    /**
     * Encabezado de solicitud de construcción:estático headers + authToken + OAuth Bearer(OAuth máxima prioridad).
     * OAuth token por [ensureValidToken] actualizar previamente a [oauthToken],Este método sólo lee.
     */
    private fun resolvedAuthHeaders(): Map<String, String> {
        val headers = config.resolvedHeaders().toMutableMap()
        oauthToken?.takeIf { it.accessToken.isNotBlank() }?.let { token ->
            headers["Authorization"] = "Bearer ${token.accessToken}"
        }
        feishuTenantToken?.takeIf { it.accessToken.isNotBlank() }?.let { token ->
            headers["X-Lark-MCP-TAT"] = token.accessToken
        }
        return headers
    }

    /**
     * Phase 10.4: Iniciar el proceso de autorización de OAuth(Llamado por la interfaz de usuario).
     *
     * @return Autorizar URL(Abra el navegador con Intent.ACTION_VIEW);null Indica que OAuth no está habilitado
     */
    fun startOAuthFlow(): String? {
        if (!oauthEnabled) return null
        val pkce = McpOAuthFlow.generatePkcePair()
        pendingPkce = pkce
        return McpOAuthFlow.buildAuthorizationUrl(config.oauthConfig, pkce)
    }

    /**
     * Phase 10.4: Completa el proceso de autorización de OAuth(Llamado después de la devolución de llamada del navegador).
     *
     * @param redirectUri devolución de llamada URI(Contiene código + parámetros de estado.)
     * @return true éxito(token persistido,Ajustable [start] Reconectar);false falló(state Ninguna coincidencia/intercambio falló)
     */
    suspend fun completeOAuthFlow(redirectUri: String): Boolean {
        val pkce = pendingPkce ?: run {
            Logger.w(TAG, "[${config.name}] completeOAuthFlow pero no pending PKCE pair")
            return false
        }
        val parsed = McpOAuthFlow.parseRedirectCallback(redirectUri, config.oauthConfig.redirectUri)
        if (parsed == null) {
            // C-25: El URI de devolución de llamada contiene code/state,Todo el registro de entrada filtrará el código de autorización. — Solo recuerda scheme://host/path
            Logger.w(TAG, "[${config.name}] OAuth Error al analizar la devolución de llamada: ${sanitizeRedirectUriForLog(redirectUri)}")
            return false
        }
        val (code, state) = parsed
        if (state != pkce.state) {
            Logger.w(TAG, "[${config.name}] OAuth state no hay coincidencia: expected=${pkce.state}, got=$state")
            return false
        }
        val token = McpOAuthFlow.exchangeCodeForToken(config.oauthConfig, code, pkce.verifier)
        if (token == null) {
            Logger.w(TAG, "[${config.name}] OAuth token Error en el intercambio")
            return false
        }
        oauthToken = token
        settings?.saveMcpToken(config.id, token)
        pendingPkce = null
        Logger.i(TAG, "[${config.name}] OAuth Autorización exitosa,token Válido hasta ${token.expiresAt}")
        return true
    }

    /** Phase 10.4: Revocar la autorización de OAuth(claro token + PKCE,girar NEEDS_AUTH). */
    suspend fun revokeOAuth() {
        oauthToken = null
        pendingPkce = null
        settings?.clearMcpToken(config.id)
        _state.value = McpConnectionState.NEEDS_AUTH
        Logger.i(TAG, "[${config.name}] OAuth Autorización revocada")
    }

    /** Phase 10.4: Si se requiere autorización de OAuth(NEEDS_AUTH Estado + OAuth habilitado). */
    fun needsOAuth(): Boolean = oauthEnabled && _state.value == McpConnectionState.NEEDS_AUTH

    // ── SSE transmisión ──────────────────────────────────────────────────────────────

    /**
     * SSE conexión de transporte(MCP 2024-11-05):
     *  1. EventSource conectado a [McpServerConfig.url],recibir server→client evento
     *  2. El primer evento suele ser el evento del punto final.(data: <POST endpoint URL>),
     *     Dígale al cliente dónde PUBLICAR solicitudes posteriores. URL
     *  3. initialize Las solicitudes se envían vía POST,Las respuestas se devuelven a través de eventos SSE
     */
    private suspend fun connectSse(): Boolean {
        // H-MCP1: Al volver a conectarse, cancele el antiguo eventSource,Evite fugas de recursos y discrepancias en las respuestas
        eventSource?.cancel()
        eventSource = null

        val sseFactory = EventSources.createFactory(client)
        val firstEventChannel = Channel<String>(Channel.CONFLATED)
        var gotEndpoint = false

        val request = Request.Builder()
            .url(config.url.toHttpUrl())
            .header("Accept", "text/event-stream")
            .apply { resolvedAuthHeaders().forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                if (!response.isSuccessful) {
                    Logger.w(TAG, "[${config.name}] SSE conectar HTTP ${response.code}")
                    firstEventChannel.close()
                    eventSource.cancel()
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // MCP 2024-11-05: primer evento type="endpoint",data si POST URL
                if (type == "endpoint" && !gotEndpoint) {
                    gotEndpoint = true
                    postEndpoint = resolvePostUrl(data)
                    Logger.d(TAG, "[${config.name}] SSE endpoint: $postEndpoint")
                    firstEventChannel.trySend("endpoint")
                } else {
                    // eventos ordinarios: data es una respuesta JSON-RPC/Notificación
                    handleSseEvent(data)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Logger.w(TAG, "[${config.name}] SSE conexión cerrada")
                if (!closed) scheduleReconnect()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // v1.113: distinguir"Coroutine cancela proactivamente"con"Fallo real de la red".
                // OkHttp en Call.cancel() lanzamiento de tiempo IOException("Canceled"),La reconexión no debería activarse en este momento.
                if (closed || !scope.isActive) {
                    firstEventChannel.close()
                    return
                }
                if (t is java.io.IOException && t.message == "Canceled") {
                    Logger.d(TAG, "[${config.name}] SSE ser Call.cancel() Cancelar,Sin reconexión")
                    firstEventChannel.close()
                    return
                }
                Logger.w(TAG, "[${config.name}] SSE La conexión falló: ${t?.message ?: response?.code}")
                firstEventChannel.close()
                if (!closed) scheduleReconnect()
            }
        }

        eventSource = sseFactory.newEventSource(request, listener)

        // Esperar evento de punto final(L-MCP2: Tiempo de espera extraído como constante)
        val gotEp = awaitSseEndpoint(firstEventChannel, SSE_ENDPOINT_TIMEOUT_MS)
        firstEventChannel.close()
        if (gotEp == null || postEndpoint == null) {
            eventSource?.cancel()
            return false
        }
        // M-MCP1: Verifique que postEndpoint y config.url tengan el mismo origen,prevenir Open Redirect
        val ep = postEndpoint ?: return false
        if (!isSameOrigin(ep, config.url)) {
            Logger.w(TAG, "[${config.name}] SSE endpoint Origen diferente al servidor: $ep vs ${config.url},Conexión rechazada")
            eventSource?.cancel()
            eventSource = null
            postEndpoint = null
            return false
        }

        // Enviar solicitud de inicialización
        return sendInitialize()
    }

    /** SSE manejo de eventos:Analizar mensajes JSON-RPC,prensa response / notification / server request Distribuir. */
    private fun handleSseEvent(data: String) {
        if (data.isBlank()) return
        val obj = runCatching {
            AppJson.decodeFromString(JsonObject.serializer(), data)
        }.getOrNull() ?: return

        val idEl = obj["id"]
        val methodEl = obj["method"]

        if (idEl == null) {
            // Ninguno id: notification(debe tener method)
            val method = (methodEl as? JsonPrimitive)?.content ?: return
            val params = obj["params"] as? JsonObject ?: buildJsonObject {}
            handleNotification(method, params)
            return
        }

        val id = (idEl as? JsonPrimitive)?.content?.toLongOrNull()
        if (id == null) {
            Logger.w(TAG, "[${config.name}] Se recibió un mensaje de identificación no válido: ${data.take(200)}")
            return
        }

        if (methodEl != null) {
            // Hay una identificación y hay method: server→client request(apoyo ping,Devuelve el método desconocido MethodNotFound)
            val method = (methodEl as? JsonPrimitive)?.content
            handleServerRequest(id, method)
            return
        }

        // Sí identificación Ninguna method: response,partido pending channel
        val channel = pendingRequests.remove(id) ?: return
        channel.trySend(obj)
        channel.close()
    }

    /**
     * v1.0.47 P4 / Phase 9.5 (P3-2): Proceso server→client notification(Ninguno id).
     *
     * apoyado notification(MCP 2025-03-26):
     *  - `notifications/tools/list_changed`: Cambios en la lista de herramientas,gatillo [onToolsListChanged] devolución de llamada,
     *    Vuelva a extraer las herramientas/lista del Registro y vuelva a registrarlas.
     *  - `notifications/prompts/list_changed`: prompt cambios de lista(registrando,acceso posterior).
     *  - `notifications/resources/list_changed`: Cambios en la lista de recursos(registrando,acceso posterior).
     *  - `notifications/initialized`: Notificación de finalización del protocolo de enlace(ignorar,client Toma la iniciativa de enviar).
     *  - `notifications/progress`: progreso de tarea largo(registrando).
     *  - Otros: Mantenga un registro.
     *
     * seguridad de devolución de llamada:[onToolsListChanged] La llamada está envuelta en runCatching,Evite excepciones del lado del registro
     * Afecta el procesamiento de notificaciones posteriores(Como múltiples eventos en el flujo SSE).
     */
    private fun handleNotification(method: String, params: JsonObject) {
        Logger.i(TAG, "recibido notification: $method (params=${params.toString().take(200)})")
        when (method) {
            "notifications/tools/list_changed" -> {
                // Activar la herramienta de extracción del Registro;Se lanzará una rutina independiente dentro de la devolución de llamada.,No bloquees aquí
                runCatching { onToolsListChanged?.invoke() }
                    .onFailure { e -> Logger.w(TAG, "[${config.name}] onToolsListChanged Excepción de devolución de llamada: ${e.message}") }
            }
            "notifications/prompts/list_changed" -> {
                runCatching { onPromptsListChanged?.invoke() }
                    .onFailure { e -> Logger.w(TAG, "[${config.name}] onPromptsListChanged Excepción de devolución de llamada: ${e.message}") }
            }
            "notifications/resources/list_changed" -> {
                runCatching { onResourcesListChanged?.invoke() }
                    .onFailure { e -> Logger.w(TAG, "[${config.name}] onResourcesListChanged Excepción de devolución de llamada: ${e.message}") }
            }
            "notifications/progress",
            -> {
                // registrado,La notificación de progreso no tiene caché local
            }
            "notifications/initialized" -> {
                // client Notificación de apretón de manos no solicitado,server No debería volver a publicar;ignorar
            }
            else -> {
                Logger.d(TAG, "[${config.name}] sin procesar notification: $method")
            }
        }
    }

    /** SSE Solicitud POST en modo transporte(initialize / tools/list / tools/call espera). */
    private suspend fun postSseRequest(jsonRpc: String, retryOn401: Boolean = true): JsonObject? {
        val endpoint = postEndpoint ?: return null
        if (config.feishuAuth.enabled && !ensureFeishuTenantToken()) return null
        val authHeaders = resolvedAuthHeaders()
        val request = Request.Builder()
            .url(endpoint.toHttpUrl())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .post(jsonRpc.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // SSE modo,POST La solicitud suele regresar. 202(Las respuestas llegan a través de eventos de ESS)
        // Pero algunos servidores devuelven directamente respuestas JSON.(No estándar pero común)
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    // M-MCP2: Forzar el token de actualización al recibir 401 y volver a intentarlo
                    if (resp.code == 401 && retryOn401 && (oauthEnabled || feishuAuthEnabled)) {
                        Logger.w(TAG, "[${config.name}] POST recibido 401,Intente actualizar las credenciales de autenticación y vuelva a intentarlo.")
                        val refreshed = if (oauthEnabled) {
                            ensureValidToken(forceRefresh = true)
                        } else {
                            ensureFeishuTenantToken(forceRefresh = true)
                        }
                        if (refreshed) {
                            return@withContext postSseRequest(jsonRpc, retryOn401 = false)
                        }
                        return@withContext null
                    }
                    if (resp.isSuccessful) {
                        val body = resp.body.string()
                        if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                            runCatching { AppJson.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
                        } else {
                            null // 202 Accepted,Espere eventos SSE
                        }
                    } else {
                        lastTransportFailure = "MCP HTTP ${resp.code}"
                        null
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // v1.113: Causado por la cancelación de la rutina. IOException("Canceled") La lógica de reconexión no debe activarse
                if (closed || e.message == "Canceled") {
                    Logger.d(TAG, "[${config.name}] POST Solicitud cancelada,Sin reconexión")
                    return@withContext null
                }
                Logger.w(TAG, "[${config.name}] POST Solicitar excepción de IO: ${e.message}")
                lastTransportFailure = "MCP network request failed"
                null
            }
        }
    }

    /** Analizar eventos de punto final de SSE data(Quizás un camino relativo,Como por ejemplo /messages?session_id=xxx). */
    private fun resolvePostUrl(data: String): String {
        val trimmed = data.trim()
        return runCatching {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                val base = config.url.toHttpUrl()
                base.resolve(trimmed)?.toString() ?: trimmed
            }
        }.getOrDefault(trimmed)
    }

    /** M-MCP1: Verificar si dos URL tienen el mismo origen(scheme + host + port consistente). */
    private fun isSameOrigin(url1: String, url2: String): Boolean {
        return try {
            val u1 = url1.toHttpUrl()
            val u2 = url2.toHttpUrl()
            u1.scheme == u2.scheme && u1.host == u2.host && u1.port == u2.port
        } catch (e: Exception) {
            false
        }
    }

    // ── StreamableHTTP transmisión ───────────────────────────────────────────────────

    /**
     * StreamableHTTP conexión de transporte(MCP 2025-03-26):
     *  1. POST initialize llegar [McpServerConfig.url],Accept: application/json, text/event-stream
     *  2. La respuesta puede ser una sola JSON(sin transmisión)o flujo SSE(transmisión)
     *  3. Analizar respuesta,apretón de manos completo
     *
     * A diferencia de la ESS,StreamableHTTP No es necesario mantener una conexión larga — Cada solicitud se PUBLICA de forma independiente.
     * Si el servidor regresa SSE,La conexión permanece abierta hasta que finaliza la transmisión SSE.
     */
    private suspend fun connectStreamableHttp(): Boolean {
        return sendInitialize()
    }

    /**
     * StreamableHTTP Solicitar:POST JSON-RPC,La respuesta puede ser JSON o SSE.
     * Analizar respuesta,Regresar JsonObject(respuesta única)O agregue el último evento de una transmisión SSE.
     */
    private suspend fun postStreamableRequest(jsonRpc: String, retryOn401: Boolean = true): JsonObject? {
        if (config.feishuAuth.enabled && !ensureFeishuTenantToken()) return null
        val authHeaders = resolvedAuthHeaders()
        val requestSessionId = streamableSessionId
        val request = Request.Builder()
            .url(config.url.toHttpUrl())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", negotiatedProtocolVersion)
            .apply {
                requestSessionId?.let { header("Mcp-Session-Id", it) }
            }
            .apply { authHeaders.forEach { (k, v) -> header(k, v) } }
            .post(jsonRpc.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { resp ->
                    // M-MCP2: Forzar el token de actualización al recibir 401 y volver a intentarlo
                    if (resp.code == 401 && retryOn401 && (oauthEnabled || feishuAuthEnabled)) {
                        Logger.w(TAG, "[${config.name}] StreamableHTTP recibido 401,Intente actualizar las credenciales de autenticación y vuelva a intentarlo.")
                        val refreshed = if (oauthEnabled) {
                            ensureValidToken(forceRefresh = true)
                        } else {
                            ensureFeishuTenantToken(forceRefresh = true)
                        }
                        if (refreshed) {
                            return@withContext postStreamableRequest(jsonRpc, retryOn401 = false)
                        }
                        return@withContext null
                    }
                    if (!resp.isSuccessful) {
                        lastTransportFailure = "MCP HTTP ${resp.code}"
                        if (
                            resp.code == 404 &&
                            requestSessionId != null &&
                            streamableSessionId == requestSessionId
                        ) {
                            // Only invalidate the session used by this request. A late 404 from
                            // the previous session must not clear a newer ID installed by recovery.
                            streamableSessionInvalidated = true
                            streamableSessionId = null
                        }
                        Logger.w(
                            TAG,
                            "[${config.name}] StreamableHTTP HTTP ${resp.code}",
                        )
                        return@use null
                    }
                    // initialize Generalmente la primera solicitud HTTP Streamable,Pero la especificación permite server
                    // Sesión de actualización en respuestas posteriores. ID,Entonces cada respuesta lee y sobrescribe el caché local.
                    resp.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId ->
                        if (streamableSessionId != sessionId) {
                            streamableSessionGeneration.incrementAndGet()
                        }
                        streamableSessionId = sessionId
                        Logger.d(
                            TAG,
                            "[${config.name}] StreamableHTTP session established " +
                                "(length=${sessionId.length})",
                        )
                    }
                    val contentType = resp.header("Content-Type") ?: ""
                    val body = resp.body.string()
                    when {
                        contentType.contains("text/event-stream") -> {
                            // SSE fluir: Analizar datos: filas,Obtener el último evento JSON
                            parseSseResponse(body)
                        }
                        contentType.contains("application/json") -> {
                            runCatching { AppJson.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
                        }
                        else -> {
                            // Ten todo en cuenta: Pruebe cuando se analiza JSON
                            if (body.isNotBlank() && body.trimStart().startsWith("{")) {
                                runCatching { AppJson.decodeFromString(JsonObject.serializer(), body) }.getOrNull()
                            } else {
                                null
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Logger.w(TAG, "[${config.name}] StreamableHTTP Solicitar excepción de IO: ${e.message}")
                lastTransportFailure = "MCP network request failed"
                null
            }
        }
    }

    /**
     * Analizar el cuerpo de respuesta SSE(datos de texto plano: fila).
     * v1.0.47 P4: Iterar a través de todos los eventos — notification llama ahora handleNotification,
     * response Graba y devuelve el último(Evite ser sobrescrito por notificación).
     */
    private fun parseSseResponse(body: String): JsonObject? {
        var lastResponse: JsonObject? = null
        val sb = StringBuilder()

        // Manejar un único evento JSON analizado:Distribuir por tipo
        fun processEvent(text: String) {
            val json = runCatching {
                AppJson.decodeFromString(JsonObject.serializer(), text)
            }.getOrNull() ?: return

            val idEl = json["id"]
            val methodEl = json["method"]
            when {
                // notification(Ninguno id,si method):Distribuir inmediatamente
                idEl == null && methodEl != null -> {
                    val method = (methodEl as? JsonPrimitive)?.content ?: return
                    val params = json["params"] as? JsonObject ?: buildJsonObject {}
                    handleNotification(method, params)
                }
                // server→client request(Hay una identificación y hay method):apoyo ping,Devuelve el método desconocido MethodNotFound
                idEl != null && methodEl != null -> {
                    val method = (methodEl as? JsonPrimitive)?.content
                    val id = (idEl as? JsonPrimitive)?.content?.toLongOrNull()
                    if (id != null) handleServerRequest(id, method)
                }
                // response(si id,Ninguno method):Graba y devuelve el último
                else -> {
                    lastResponse = json
                }
            }
        }

        for (line in body.lines()) {
            if (line.startsWith("data:")) {
                // L-MCP4: Según la especificación SSE,Para datos de varias líneas \n conectar
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line.removePrefix("data:").trim())
            } else if (line.isBlank() && sb.isNotEmpty()) {
                processEvent(sb.toString())
                sb.clear()
            }
        }
        // Manejar situaciones donde el final no termina con una línea en blanco
        if (sb.isNotEmpty()) {
            processEvent(sb.toString())
        }
        return lastResponse
    }

    // ── JSON-RPC Solicitar ─────────────────────────────────────────────────────────

    /** Enviar solicitud de inicialización + notificación inicializada,Apretón de manos completo. */
    /**
     * P1-A: stdio conexión de transporte — Inicie el subproceso del servidor MCP local y complete el protocolo de enlace de inicialización.
     */
    private suspend fun connectStdio(): Boolean {
        val ctx = appContext ?: run {
            Logger.w(TAG, "[${config.name}] stdio Falta el contexto de la aplicación de conexión")
            return false
        }
        stdioTransport?.stop()
        val transport = McpStdioTransport(
            config = config,
            context = ctx,
            scope = scope,
            onMessage = { json -> handleStdioMessage(json) },
            onExit = { code -> handleStdioExit(code) },
        )
        return if (transport.start()) {
            stdioTransport = transport
            sendInitialize()
        } else {
            false
        }
    }

    /**
     * P1-A: stdio Desvío de mensajes.
     *  - Hay una identificación y hay method → server→client Solicitud (devolver paquete sendResponse)
     *  - si id → Solicitar respuesta (entregada a Solicitudes pendientes)
     *  - Ninguno id → Notificar (ir [handleNotification])
     */
    private fun handleStdioMessage(json: JsonObject) {
        val id = (json["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val method = (json["method"] as? JsonPrimitive)?.contentOrNull
        when {
            id != null && method != null -> handleServerRequest(id, method)
            id != null -> pendingRequests[id]?.trySend(json)
            method != null -> {
                val params = json["params"] as? JsonObject ?: JsonObject(emptyMap())
                handleNotification(method, params)
            }
        }
    }

    /** P1-A: stdio Salidas de proceso — Vuelva a conectarse cuando no esté cerrado activamente. */
    private fun handleStdioExit(code: Int) {
        Logger.w(TAG, "[${config.name}] stdio Salidas de proceso: code=$code")
        stdioTransport = null
        if (!closed) scheduleReconnect()
    }

    private suspend fun sendInitialize(allowSessionRecovery: Boolean = true): Boolean {
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put(
                "params",
                buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    // v1.0.47 P4: Declarar capacidades de muestreo(Permitir al servidor solicitar al cliente LLM)
                    put(
                        "capabilities",
                        buildJsonObject {
                            put("sampling", buildJsonObject {})
                            // v1.0.47 P4: Declarar capacidad de raíces(Permitir que el servidor acceda a la raíz del sistema de archivos del cliente)
                            put(
                                "roots",
                                buildJsonObject {
                                    put("listChanged", true)
                                },
                            )
                        },
                    )
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "muse")
                            put("version", "1.0.0")
                        },
                    )
                },
            )
        }
        val response = sendRequest(id, request, allowSessionRecovery = allowSessionRecovery) ?: return false
        logJsonRpcError("initialize", response)

        // analizar serverInfo
        val result = response["result"] as? JsonObject
        // L-MCP3: Lea la versión del protocolo devuelta por el servidor y regístrela
        val serverProtocolVersion = (result?.get("protocolVersion") as? JsonPrimitive)?.content
        if (serverProtocolVersion != null) {
            negotiatedProtocolVersion = serverProtocolVersion
            Logger.i(TAG, "[${config.name}] server protocolVersion=$serverProtocolVersion (client=${PROTOCOL_VERSION})")
        }
        serverInfo = result?.let {
            val info = it["serverInfo"] as? JsonObject
            McpServerInfo(
                name = (info?.get("name") as? JsonPrimitive)?.content,
                version = (info?.get("version") as? JsonPrimitive)?.content,
            )
        }

        // Sólo un shell JSON-RPC sin errores no significa que el protocolo de enlace haya sido exitoso;Debe confirmar result
        // existir,De lo contrario, las herramientas/listas posteriores estarán en“falso CONNECTED”El juego de herramientas se vacía silenciosamente.
        if (response["error"] != null || result == null) return false
        // Enviar notificación inicializada(Ninguno id)
        sendNotification("notifications/initialized")
        return true
    }

    /**
     * tools/list:Enumere todas las herramientas expuestas por el servidor.
     * @return Lista de herramientas;Devuelve una lista vacía en caso de error.
     */
    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        listToolsOrNull() ?: emptyList()
    }

    /**
     * tools/list Una versión que acepta valores NULL de .
     *
     * La lista vacía es un resultado legal del servidor MCP.,No confundir con fallas de HTTP/JSON-RPC;
     * Registry En función de esto, debe decidir si permite que el chat continúe esperando el registro de la herramienta.
     */
    suspend fun listToolsOrNull(): List<McpTool>? = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext null
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/list")
        }
        val response = sendRequest(id, request) ?: return@withContext null
        logJsonRpcError("tools/list", response)
        if (response["error"] != null) return@withContext null
        val result = response["result"] as? JsonObject ?: return@withContext null
        val toolsEl = result["tools"] ?: return@withContext null
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpTool.serializer()),
                AppJson.encodeToString(toolsEl),
            )
        }.getOrNull()
    }

    /**
     * tools/call:Llame a la herramienta especificada.
     * @param name Nombre de la herramienta
     * @param arguments parámetros(JsonObject)
     * @return Resultado de la llamada;Retorno del fracaso isError=true resultado vacío
     */
    suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) {
            lastTransportFailure = "MCP client is not connected"
            return@withContext McpToolCallResult(
                isError = true,
                content = listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", lastTransportFailure ?: "MCP client is not connected")
                    },
                ),
            )
        }
        lastTransportFailure = null
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "tools/call")
            put(
                "params",
                buildJsonObject {
                    put("name", name)
                    put("arguments", arguments)
                },
            )
        }
        val response = sendRequest(id, request) ?: return@withContext McpToolCallResult(
            isError = true,
            content = listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", lastTransportFailure ?: "MCP transport request failed")
                },
            ),
        )
        logJsonRpcError("tools/call", response)
        response["error"]?.let { error ->
            return@withContext McpToolCallResult(
                isError = true,
                content = listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "JSON-RPC error: $error")
                    },
                ),
            )
        }
        val result = response["result"] ?: return@withContext McpToolCallResult(
            isError = true,
            content = listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", "MCP response did not contain a result")
                },
            ),
        )
        val rawResultJson = AppJson.encodeToString(result)
        runCatching {
            AppJson.decodeFromString(McpToolCallResult.serializer(), rawResultJson)
        }.getOrElse { decodeError ->
            // v1.0.79 (D-2): Los errores de análisis ya no se descartan silenciosamente — Mantenga el resultado original JSON y devuélvalo como detalles del error,
            // Y registre el registro. anteriormente getOrDefault(isError=true Resultado vacío) La causa del error del servidor será
            // trágalo todo,El modelo no puede corregirse a sí mismo sin conocer la causa del fallo.
            Logger.w(TAG, "[${config.id}] tools/call Error al analizar el resultado: ${decodeError.message}")
            McpToolCallResult(
                isError = true,
                content = listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "MCP server returned malformed result: $rawResultJson")
                    },
                ),
            )
        }
    }

    // ── Phase 11.1.2: resources / prompts ───────────────────────────────────

    /**
     * Phase 11.1.2: resources/list — Enumere todos los recursos expuestos por el servidor.
     *
     * @return Lista de recursos;Devuelve una lista vacía en caso de error.
     */
    suspend fun listResources(): List<McpResource> = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext emptyList()
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "resources/list")
        }
        val response = sendRequest(id, request) ?: return@withContext emptyList()
        logJsonRpcError("resources/list", response)
        val result = response["result"] as? JsonObject ?: return@withContext emptyList()
        val resourcesEl = result["resources"] ?: return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpResource.serializer()),
                AppJson.encodeToString(resourcesEl),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Phase 11.1.2: resources/read — Lea el contenido del recurso especificado.
     *
     * @param uri Recursos URI(resources/list Campo uri devuelto)
     * @return Lista de contenido de recursos(Posiblemente múltiples,Si un recurso de directorio devuelve varios archivos);Devuelve una lista vacía en caso de error.
     */
    suspend fun readResource(uri: String): List<McpResourceContent> = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext emptyList()
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "resources/read")
            put(
                "params",
                buildJsonObject {
                    put("uri", uri)
                },
            )
        }
        val response = sendRequest(id, request) ?: return@withContext emptyList()
        logJsonRpcError("resources/read", response)
        val result = response["result"] as? JsonObject ?: return@withContext emptyList()
        val contentsEl = result["contents"] ?: return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpResourceContent.serializer()),
                AppJson.encodeToString(contentsEl),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Phase 11.1.2: prompts/list — Enumere todas las plantillas de mensajes expuestas por el servidor.
     *
     * @return prompt lista;Devuelve una lista vacía en caso de error.
     */
    suspend fun listPrompts(): List<McpPrompt> = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) return@withContext emptyList()
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "prompts/list")
        }
        val response = sendRequest(id, request) ?: return@withContext emptyList()
        logJsonRpcError("prompts/list", response)
        val result = response["result"] as? JsonObject ?: return@withContext emptyList()
        val promptsEl = result["prompts"] ?: return@withContext emptyList()
        runCatching {
            AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(McpPrompt.serializer()),
                AppJson.encodeToString(promptsEl),
            )
        }.getOrDefault(emptyList())
    }

    /**
     * Phase 11.1.2: prompts/get — Obtenga el contenido del mensaje del mensaje especificado.
     *
     * @param name prompt nombre(prompts/list El campo de nombre devuelto)
     * @param arguments parámetros(name→value),corresponder [McpPrompt.arguments]
     * @return prompt Lista de mensajes;Retornar vacío en caso de falla messages
     */
    suspend fun getPrompt(name: String, arguments: Map<String, String> = emptyMap()): McpPromptResult = withContext(Dispatchers.IO) {
        if (_state.value != McpConnectionState.CONNECTED) {
            return@withContext McpPromptResult()
        }
        val id = idCounter.getAndIncrement()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "prompts/get")
            put(
                "params",
                buildJsonObject {
                    put("name", name)
                    if (arguments.isNotEmpty()) {
                        put(
                            "arguments",
                            buildJsonObject {
                                arguments.forEach { (k, v) -> put(k, v) }
                            },
                        )
                    }
                },
            )
        }
        val response = sendRequest(id, request) ?: return@withContext McpPromptResult()
        logJsonRpcError("prompts/get", response)
        val result = response["result"] ?: return@withContext McpPromptResult()
        runCatching {
            AppJson.decodeFromString(McpPromptResult.serializer(), AppJson.encodeToString(result))
        }.getOrDefault(McpPromptResult())
    }

    /** M-MCP5: Registro de respuestas de error JSON-RPC(code + message),Evite el descarte silencioso. */
    private fun logJsonRpcError(context: String, response: JsonObject?) {
        val error = response?.get("error") as? JsonObject ?: return
        val code = (error["code"] as? JsonPrimitive)?.content
        val message = (error["message"] as? JsonPrimitive)?.content
        Logger.w(TAG, "[${config.name}] $context JSON-RPC error: code=$code, message=$message")
    }

    /**
     * B3-09: Proceso server→client request.
     * ping Volver vacío result,El método desconocido devuelve JSON-RPC MethodNotFound.
     */
    private fun handleServerRequest(id: Long, method: String?) {
        scope.launch {
            val response = if (method == "ping") {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", buildJsonObject {})
                }
            } else {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", -32601)
                            put("message", "Method not found")
                        },
                    )
                }
            }
            sendResponse(response)
        }
    }

    /** Enviar respuesta JSON-RPC(usado para server→client request paquete de devolución). */
    private suspend fun sendResponse(response: JsonObject) {
        val jsonStr = AppJson.encodeToString(response)
        when (config.transportType) {
            McpTransportType.STREAMABLE_HTTP -> postStreamableRequest(jsonStr)
            McpTransportType.SSE -> postSseRequest(jsonStr)
            McpTransportType.STDIO -> stdioTransport?.send(jsonStr)
        }
    }

    /**
     * Enviar solicitud JSON-RPC,Esperando respuesta(con tiempo de espera).
     * SSE modo:POST Espere los eventos SSE más tarde;StreamableHTTP:Obtenga la respuesta directamente.
     */
    private suspend fun sendRequest(id: Long, request: JsonObject, allowSessionRecovery: Boolean = true): JsonObject? {
        val jsonStr = AppJson.encodeToString(request)
        val timeoutMs = config.requestTimeoutMs
        val sessionGenerationAtStart = if (config.transportType == McpTransportType.STREAMABLE_HTTP) {
            streamableSessionGeneration.get()
        } else {
            -1L
        }

        val response = when (config.transportType) {
            McpTransportType.STREAMABLE_HTTP -> {
                withTimeoutOrNull(timeoutMs) { postStreamableRequest(jsonStr) }
            }
            McpTransportType.SSE -> {
                // SSE modo:Registrarse pending channel,POST Solicitar,Espere a que vuelva el evento SSE
                val channel = Channel<JsonObject>(Channel.CONFLATED)
                pendingRequests[id] = channel
                try {
                    withTimeoutOrNull(timeoutMs) {
                        // POST Solicitar(server Puede devolver la respuesta directamente,Posiblemente también a través de eventos SSE)
                        val directResponse = postSseRequest(jsonStr)
                        if (directResponse != null) {
                            directResponse
                        } else {
                            // Espere eventos SSE
                            channel.receiveCatching().getOrNull()
                        }
                    }
                } finally {
                    pendingRequests.remove(id)
                }
            }
            McpTransportType.STDIO -> {
                // P1-A: Registrarse pending channel -> escribir stdin -> Espere a que responda la línea estándar
                val channel = Channel<JsonObject>(Channel.CONFLATED)
                pendingRequests[id] = channel
                try {
                    withTimeoutOrNull(timeoutMs) {
                        if (stdioTransport?.send(jsonStr) == true) {
                            channel.receiveCatching().getOrNull()
                        } else {
                            null
                        }
                    }
                } finally {
                    pendingRequests.remove(id)
                }
            }
        }

        if (
            response == null &&
            allowSessionRecovery &&
            config.transportType == McpTransportType.STREAMABLE_HTTP &&
            (
                streamableSessionInvalidated ||
                    streamableSessionGeneration.get() > sessionGenerationAtStart ||
                    streamableSessionRecoveryMutex.isLocked
                )
        ) {
            val recovered = streamableSessionRecoveryMutex.withLock {
                if (streamableSessionGeneration.get() > sessionGenerationAtStart) {
                    true
                } else if (streamableSessionInvalidated) {
                    streamableSessionInvalidated = false
                    Logger.i(TAG, "[${config.name}] MCP session Caducado,Vuelva a intentar la solicitud original después de reinicializar")
                    sendInitialize(allowSessionRecovery = false)
                } else {
                    false
                }
            }
            if (recovered) {
                return sendRequest(id, request, allowSessionRecovery = false)
            }
        }
        return response
    }

    /** Enviar notificaciones JSON-RPC(Ninguno id,Sin respuesta). */
    private suspend fun sendNotification(method: String) {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        val jsonStr = AppJson.encodeToString(request)
        when (config.transportType) {
            McpTransportType.STREAMABLE_HTTP -> postStreamableRequest(jsonStr)
            McpTransportType.SSE -> postSseRequest(jsonStr)
            McpTransportType.STDIO -> stdioTransport?.send(jsonStr)
        }
    }

    // ── Reconectar ──────────────────────────────────────────────────────────────────

    /**
     * Reconexión de retroceso exponencial:base * 2^attempt,Límite superior 30 s.
     * alcanzar [McpServerConfig.maxReconnectAttempts] Cambie al estado FALLADO.
     */
    private fun scheduleReconnect() {
        if (closed) return
        if (!config.autoReconnect) {
            _state.value = McpConnectionState.FAILED
            return
        }
        if (reconnectAttempts >= config.maxReconnectAttempts) {
            Logger.w(TAG, "[${config.name}] Se superó el número máximo de reconexiones ${config.maxReconnectAttempts},girar FAILED")
            _state.value = McpConnectionState.FAILED
            return
        }

        _state.value = McpConnectionState.RECONNECTING
        val delayMs = minOf(
            config.reconnectBaseMs * (1L shl reconnectAttempts),
            RECONNECT_MAX_DELAY_MS,
        )
        reconnectAttempts++
        Logger.d(TAG, "[${config.name}] No. $reconnectAttempts reconexión,${delayMs}ms Vuelve a intentarlo más tarde")

        scope.launch {
            delay(delayMs)
            if (!closed) connect()
        }
    }

    // ── ciclo de vida ─────────────────────────────────────────────────────────────

    /** Cerrar activamente la conexión(No activa la reconexión). */
    override fun close() {
        closed = true
        _state.value = McpConnectionState.DISCONNECTED
        stdioTransport?.stop()
        stdioTransport = null
        eventSource?.cancel()
        eventSource = null
        scope.cancel()
        pendingRequests.clear()
        streamableSessionId = null
        negotiatedProtocolVersion = PROTOCOL_VERSION
    }

    private companion object {
        const val TAG = "McpClient"
        const val PROTOCOL_VERSION = "2025-03-26"

        // L-MCP2: tiempo de espera/Recablear números mágicos extraídos como constantes
        const val SSE_ENDPOINT_TIMEOUT_MS = 5_000L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val FEISHU_REFRESH_MARGIN_MS = 30 * 60 * 1000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val defaultClient = io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
            .connectTimeout(15, TimeUnit.SECONDS)
            // v1.114 Reparación: SSE Escenario de conexión larga readTimeout 60s es demasiado corto(Si el servidor no tiene datos durante mucho tiempo, se desconectará accidentalmente.),
            //   Consistente con el cliente de chat cambiado a 120s
            .readTimeout(120, TimeUnit.SECONDS)
            // v1.114 Reparación: SSE Las sesiones pueden durar mucho tiempo.(MCP Llamadas a herramientas/razonamiento largo),callTimeout 60s Interrumpirá con fuerza toda la sesión.;
            //   0 significa ilimitado,Vida controlada por readTimeout / pingInterval
            .callTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
