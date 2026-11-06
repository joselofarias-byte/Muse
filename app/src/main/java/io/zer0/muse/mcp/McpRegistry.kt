package io.zer0.muse.mcp

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 9.5 (M3): MCP server Registro + Persistencia + Herramienta Puente de Registro.
 *
 * Responsabilidades:
 *  1. El servidor MCP persistente está configurado para DataStore(JSON Depósito y retiro completos)
 *  2. Administrar múltiples [McpClient] Ejemplo(cada uno [McpServerConfig] corresponde a uno)
 *  3. Después de una conexión exitosa,Registre las herramientas expuestas por el servidor localmente [ToolRegistry],
 *     Nombre de la herramienta más prefijo `mcp_{serverId}__{toolName}` Evite conflictos de nombres
 *  4. expuesto [serversState] StateFlow Permite que la interfaz de usuario observe el estado de conexión de todos los servidores.
 *  5. Cuando está desconectado,Dar de baja la herramienta correspondiente de ToolRegistry
 *
 * llamador:
 *  - [io.zer0.muse.ui.ChatViewModel] Llamado al inicio [startAll] Conectar todo habilitado server
 *  - [io.zer0.muse.ui.SettingsScreen] Pase [addServer] / [removeServer] / [updateServer] gestión
 *  - [io.zer0.muse.tools.ToolRegistry] Al recibir la llamada de la herramienta LLM,Pase `mcp_` ruta de prefijo a [McpClient.callTool]
 *
 * estrategia de persistencia:
 *  - DataStore Preferences key="mcp_servers_json",el valor es [McpServerConfig] lista JSON
 *  - en la memoria [servers] ¿Es la lista de configuración actual?,[clients] es la instancia de cliente correspondiente map
 *  - [serversState] si server id → Instantánea del estado de la conexión,UI Actualización en tiempo real
 *
 * Escrito de forma independiente(Según especificaciones oficiales de MCP),Apache 2.0.
 */
/** v1.0.92 (B-6): server id forma jurídica(con nombre de herramienta "mcp_{id}__{tool}" Alineación de convención de prefijo). */
private val SERVER_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

class McpRegistry(
    private val toolRegistry: ToolRegistry,
    private val settings: io.zer0.muse.data.SettingsRepository,
    private val context: Context,
    /**
     * v1.0.79 (C-3): Auxiliar de almacén — Una vez que la conexión se realiza correctamente, el servidor MCP se vincula automáticamente a la extensión del asistente principal.
     * El MCP agregado previamente no se configurará automáticamente en la extensión del asistente,Asistente de prensa de esquema de herramientas de chat principal mcpServerIds
     * Las herramientas MCP no son visibles después del filtrado(Solo se pueden ver herramientas locales.),Debes ir manualmente a la página del asistente para verificar.
     * Omitir el enlace automático si es nulo(prueba/compatibles).
     */
    private val assistantRepository: io.zer0.muse.data.assistant.AssistantRepository? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Todas las configuraciones actuales del servidor MCP(Memoria + persistencia). */
    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    /** Instantánea del estado de la conexión para cada servidor(server id → state). */
    private val _serversState = MutableStateFlow<Map<String, McpConnectionState>>(emptyMap())
    val serversState: StateFlow<Map<String, McpConnectionState>> = _serversState.asStateFlow()

    /** Instancia de cliente MCP activa(server id → client).H-REG1: Utilice ConcurrentHashMap para garantizar la seguridad de los subprocesos. */
    private val clients = ConcurrentHashMap<String, McpClient>()

    /** Exponer el estado de preparación de tres etapas al mundo exterior evita que la interfaz de usuario o el chat traten las herramientas conectadas como si estuvieran disponibles. */
    data class ServerReadiness(
        val connected: Boolean,
        val toolsReady: Boolean,
        val assistantBound: Boolean,
    )

    suspend fun readiness(serverId: String, assistantId: String = "default"): ServerReadiness {
        val connected = clients[serverId]?.state?.value == McpConnectionState.CONNECTED
        val toolsReady = serverId in toolsReadyServers
        val repo = assistantRepository
        val assistantBound = repo?.getById(assistantId)?.let { assistant ->
            repo.parseMcpServerIds(assistant).contains(serverId)
        } ?: false
        return ServerReadiness(connected, toolsReady, assistantBound)
    }

    /**
     * Herramientas completadas/servidor de listas.
     *
     * No se puede utilizar“ToolRegistry ¿Existe una herramienta de prefijo en”Juicio listo:legales MCP server
     * Puede devolver una lista de herramientas vacía,De lo contrario, cada primer mensaje esperará en vano hasta que se agote el tiempo de espera.
     */
    private val toolsReadyServers = ConcurrentHashMap.newKeySet<String>()

    /** tools/list fracaso claro server,Solía ​​fallar rápidamente en el primer mensaje en lugar de esperar un tiempo de espera completo. */
    private val toolLoadFailedServers = ConcurrentHashMap.newKeySet<String>()

    /** M-REG3: Marque si se ha llamado a startAll,Evite conexiones duplicadas con bloques de inicio. */
    private val startAllCalled = AtomicBoolean(false)

    /**
     * Phase 9.5 (P3-2): per-server Protección de reentrada de actualización de herramientas.
     *
     * Cuando la notificación de herramientas/list_changed llega con frecuencia,Evite las actualizaciones simultáneas de herramientas que causan:
     *  - Lista de herramientas brevemente vacía(unregister ventana antes de registrarse)
     *  - Tirar repetidamente de herramientas/listas desperdicia la red
     * compareAndSet Asegúrese de que solo se esté ejecutando una actualización en el mismo servidor al mismo tiempo.
     */
    private val refreshingTools = ConcurrentHashMap<String, Boolean>()

    init {
        registerManagementTools()
        // Cargue la configuración del servidor guardada desde DataStore al inicio
        scope.launch {
            settings.mcpServersFlow.collect { saved ->
                _servers.value = saved
                // Limpie los enlaces de MCP que ya no existen en el asistente al inicio para evitar que los identificadores huérfanos sigan afectando el juicio de preparación de la herramienta.
                runCatching {
                    assistantRepository?.removeOrphanMcpServerBindings(saved.map { it.id }.toSet())
                }.onFailure { Logger.w(TAG, "Error al limpiar el enlace MCP huérfano: ${it.message}", it) }
                // Conectar automáticamente todos los habilitados server(Aún no conectado)
                saved.filter { it.enabled && it.url.isNotBlank() }.forEach { cfg ->
                    if (!clients.containsKey(cfg.id)) {
                        connectServer(cfg)
                    }
                }
            }
        }
    }

    /**
     * Agregue servidor MCP y persistencia + conexión automática.
     * SSRF Verificación por [upsertServer] / [connectServer] de [McpServerConfig.isSsrfBlocked] Bolsillo de entrada,
     * La sentencia no se repetirá aquí.
     * @return ¿Tiene éxito?(id Agregar solo si no hay duplicados)
     */
    suspend fun addServer(config: McpServerConfig): Boolean {
        // v1.0.92 (B-6): Formulario de identificación de verificación defensiva(letras/numeros/subrayado/guión,más largo 64),
        // Evitar que los caracteres especiales se rompan "mcp_{id}__{tool}" Coincidencia de prefijos y registro de herramientas.
        if (!SERVER_ID_REGEX.matches(config.id)) {
            Logger.w(TAG, "addServer rechazar ilegal server id: ${config.id}")
            return false
        }
        val current = _servers.value
        if (current.any { it.id == config.id }) return false
        val newList = current + config
        persist(newList)
        _servers.value = newList
        if (config.enabled && config.url.isNotBlank()) {
            connectServer(config)
        }
        return true
    }

    /** Actualizar la configuración del servidor existente(id partido). Vuelva a conectarse al servidor conectado. */
    suspend fun updateServer(config: McpServerConfig) {
        val newList = _servers.value.map { if (it.id == config.id) config else it }
        persist(newList)
        _servers.value = newList
        // Desconectar conexión antigua,Vuelva a conectarse con la nueva configuración
        disconnectServer(config.id)
        if (config.enabled && config.url.isNotBlank()) {
            connectServer(config)
        }
    }

    /** Escritura idempotente utilizada por la herramienta de configuración: actualice la identificación al mismo tiempo; de lo contrario, agréguela. */
    suspend fun upsertServer(config: McpServerConfig): String {
        // G2: Verificación de ingreso SSRF — Negarse a apuntar el nuevo MCP a la red interna/RFC1918/169.254/metadata esperando dirección
        // (loopback excepto,Ollama/Escenario de desarrollo de host MCP local). Los servidores existentes permiten la reconexión a la configuración original
        // (loopback La versión está integrada por isSsrfBlocked,La configuración de intranet existente sin bucle invertido sigue estando disponible.,
        //  Solo se bloquea al volver a insertar).
        if (config.isSsrfBlocked()) {
            return "MCP servidor ${config.name}(${config.id}) URL apuntar a la intranet/dirección reservada,Rechazado por la protección de la SSRF(Permitir únicamente direcciones públicas http/https o localhost)."
        }
        return if (_servers.value.any { it.id == config.id }) {
            updateServer(config)
            "Servidor MCP actualizado ${config.name}(${config.id}), reconectándose."
        } else if (addServer(config)) {
            "Servidor MCP agregado ${config.name}(${config.id}), conectando y descubriendo herramientas."
        } else {
            "MCP servidor ${config.id} Error de escritura."
        }
    }

    /** Deje que el asistente se vincule a un servidor MCP; un ID de asistente vacío está vinculado al asistente predeterminado de forma predeterminada. */
    suspend fun bindServerToAssistant(serverId: String, assistantId: String = "default"): String {
        if (_servers.value.none { it.id == serverId }) return "Servidor MCP no encontrado: $serverId"
        val repo = assistantRepository ?: return "El almacén auxiliar no está listo y no puede vincularse al MCP."
        val assistant = repo.getById(assistantId) ?: return "Asistente no encontrado: $assistantId"
        val ids = repo.parseMcpServerIds(assistant).toMutableSet()
        val added = ids.add(serverId)
        if (added) {
            repo.upsert(
                assistant.copy(mcpServerIdsJson = io.zer0.common.AppJson.encodeToString(ListSerializer(String.serializer()), ids.toList())),
            )
        }
        return if (added) "Ya MCP $serverId Vincularse al asistente ${assistant.name}." else "MCP $serverId Ya obligado a asistente ${assistant.name}."
    }

    /** Elimine el servidor y desconéctelo. */
    suspend fun removeServer(id: String) {
        val newList = _servers.value.filterNot { it.id == id }
        persist(newList)
        _servers.value = newList
        runCatching {
            assistantRepository?.removeMcpServerBinding(id)
        }.onFailure { Logger.w(TAG, "No se pudo borrar el enlace del servidor MCP(id=$id): ${it.message}", it) }
        disconnectServer(id)
    }

    /** Iniciar todo habilitado server(Llamado cuando se inicia la aplicación.). */
    fun startAll() {
        // M-REG3: Utilice AtomicBoolean para asegurarse de que se llame solo una vez,Evite conexiones duplicadas con bloques de inicio
        if (!startAllCalled.compareAndSet(false, true)) return
        scope.launch {
            // No depende del orden de programación del recopilador de inicio,Lea explícitamente la configuración de persistencia una vez antes de conectarse.
            val saved = settings.mcpServersFlow.first()
            _servers.value = saved
            saved.filter { it.enabled && it.url.isNotBlank() }.forEach { connectServer(it) }
        }
    }

    /** Vuelva a conectarse manualmente al servidor especificado. */
    fun reconnect(id: String) {
        scope.launch {
            val cfg = _servers.value.firstOrNull { it.id == id } ?: return@launch
            disconnectServer(id)
            connectServer(cfg)
        }
    }

    // ── Phase 10.4: OAuth 2.1 PKCE agente(Para llamadas de UI) ──────────────────────────

    /**
     * Phase 10.4: Inicie el proceso de autorización de OAuth para el servidor especificado.
     * @return Autorizar URL(UI Abrir con intención.ACTION_VIEW);null Indica que el servidor no existe o OAuth no está habilitado
     */
    fun startOAuthFlow(serverId: String): String? {
        val client = clients[serverId] ?: return null
        return runCatching { client.startOAuthFlow() }.getOrNull()
    }

    /**
     * Phase 10.4: Completa el proceso de autorización de OAuth(Llamado después de la devolución de llamada del navegador).
     * Vuelva a conectarse automáticamente al servidor después del éxito.
     * @return true éxito;false falló(state No coincide/falló el intercambio/el servidor no existe)
     */
    suspend fun completeOAuthFlow(serverId: String, redirectUri: String): Boolean {
        val client = clients[serverId] ?: return false
        val ok = client.completeOAuthFlow(redirectUri)
        if (ok) {
            // Autorización exitosa,Reconectar(puedo conseguirlo esta vez token,apretón de manos normal)
            val cfg = _servers.value.firstOrNull { it.id == serverId } ?: return ok
            disconnectServer(serverId)
            connectServer(cfg)
        }
        return ok
    }

    /** Phase 10.4: Revoca la autorización OAuth del servidor especificado. */
    suspend fun revokeOAuth(serverId: String) {
        val client = clients[serverId] ?: return
        client.revokeOAuth()
    }

    /** Phase 10.4: Especifica si el servidor requiere autorización OAuth. */
    fun needsOAuth(serverId: String): Boolean {
        val client = clients[serverId] ?: return false
        return client.needsOAuth()
    }

    // ── Phase 11.1.2: resources / prompts agregación API ──────────────────────────

    /**
     * Phase 11.1.2: Recursos agregados de todos los servidores conectados.
     * Marcar la fuente de cada recurso server,UI Presente al usuario la opción de inyectar contexto.
     *
     * @return Lista de recursos para todos los servidores conectados(serverId → resource lista)
     */
    suspend fun listAllResources(): Map<String, List<McpResource>> {
        val result = mutableMapOf<String, List<McpResource>>()
        clients.forEach { (serverId, client) ->
            if (client.state.value == McpConnectionState.CONNECTED) {
                resultOf {
                    val resources = client.listResources()
                    if (resources.isNotEmpty()) result[serverId] = resources
                }.onError { msg, _ ->
                    Logger.w(TAG, "[$serverId] listResources falló: $msg")
                }
            }
        }
        return result
    }

    /**
     * Phase 11.1.2: Lea el contenido del recurso especificado del servidor especificado.
     * @return Lista de contenido de recursos(Posiblemente múltiples);Devuelve una lista vacía en caso de error.
     */
    suspend fun readResource(serverId: String, uri: String): List<McpResourceContent> {
        val client = clients[serverId] ?: return emptyList()
        if (client.state.value != McpConnectionState.CONNECTED) return emptyList()
        return resultOf { client.readResource(uri) }
            .onError { msg, _ -> Logger.w(TAG, "[$serverId] readResource($uri) falló: $msg") }
            .getOrNull() ?: emptyList()
    }

    /**
     * Phase 11.1.2: Solicitudes agregadas de todos los servidores conectados.
     * Marque la fuente de cada mensaje server,UI Se muestra como un botón de acceso directo.
     *
     * @return Lista rápida de todos los servidores conectados(serverId → prompt lista)
     */
    suspend fun listAllPrompts(): Map<String, List<McpPrompt>> {
        val result = mutableMapOf<String, List<McpPrompt>>()
        clients.forEach { (serverId, client) ->
            if (client.state.value == McpConnectionState.CONNECTED) {
                resultOf {
                    val prompts = client.listPrompts()
                    if (prompts.isNotEmpty()) result[serverId] = prompts
                }.onError { msg, _ ->
                    Logger.w(TAG, "[$serverId] listPrompts falló: $msg")
                }
            }
        }
        return result
    }

    /**
     * Phase 11.1.2: Obtenga el contenido del mensaje especificado del servidor especificado.
     * @return prompt Lista de mensajes;Retornar vacío en caso de falla messages
     */
    suspend fun getPrompt(serverId: String, name: String, arguments: Map<String, String> = emptyMap()): McpPromptResult {
        val client = clients[serverId] ?: return McpPromptResult()
        if (client.state.value != McpConnectionState.CONNECTED) return McpPromptResult()
        return resultOf { client.getPrompt(name, arguments) }
            .onError { msg, _ -> Logger.w(TAG, "[$serverId] getPrompt($name) falló: $msg") }
            .getOrNull() ?: McpPromptResult()
    }

    // ── Phase 11.1.3: Dynamic Client Registration agente ──────────────────────

    /**
     * Phase 11.1.3: Descubrir automáticamente metadatos de OAuth + registrar clientes dinámicamente.
     *
     * Después del éxito, complete el [McpOAuthConfig] Persistencia a la configuración del servidor.,Luego transfiera a NEEDS_AUTH y otra autorización de UI.
     * Razón del fracaso:server URL Inalcanzable/No se admite el registro dinámico/El punto final de registro devuelve un error.
     *
     * @return Llenado [McpOAuthConfig];Retorno del fracaso null
     */
    suspend fun discoverAndRegisterOAuth(serverId: String): McpOAuthConfig? {
        val cfg = _servers.value.firstOrNull { it.id == serverId } ?: return null
        if (cfg.url.isBlank()) return null
        val oauthConfig = McpDynamicRegistration.discoverAndRegister(
            serverUrl = cfg.url,
            // L-REG1: leer desde la configuración redirectUri,en lugar de codificar
            redirectUri = cfg.oauthConfig.redirectUri,
        ) ?: return null
        // Persistencia a la configuración del servidor.
        val updated = cfg.copy(oauthConfig = oauthConfig)
        updateServer(updated)
        Logger.i(TAG, "[$serverId] Registro dinámico exitoso,clientId=${oauthConfig.clientId}")
        return oauthConfig
    }

    /**
     * Conéctese al especificado server,Después de que el protocolo de enlace sea exitoso, registre las herramientas para [ToolRegistry].
     */
    private suspend fun connectServer(config: McpServerConfig) {
        if (clients.containsKey(config.id)) return // Conectado
        // G2: Entrada de conexión bolsillo SSRF — Incluso pasando por alto la entrada upsertServer(startAll Leer la configuración del inventario/
        // init collector/reconnect/OAuth Reconectarse más tarde),Tampoco inicia una conexión a la intranet.
        if (config.isSsrfBlocked()) {
            Logger.w(TAG, "[${config.id}] URL Golpear la protección SSRF(Intranet/dirección reservada),Conexión rechazada")
            updateState(config.id, McpConnectionState.FAILED)
            return
        }
        // Phase 10.4: entrante settings,Hacer que McpClient admita la persistencia del token OAuth
        val client = McpClient(config, settings = settings, appContext = context)
        // init collector y empezarTodos pueden descubrir el mismo al mismo tiempo server,Utilice putIfAbsent para evitar el doble client
        // Apretones de manos paralelos, registros repetidos de herramientas MCP con el mismo nombre y reconexiones repetidas.
        if (clients.putIfAbsent(config.id, client) != null) {
            client.close()
            return
        }
        updateState(config.id, McpConnectionState.CONNECTING)

        // Phase 9.5 (P3-2): Inyecte la devolución de llamada tools/list_changed,server Vuelva a buscar automáticamente la lista de herramientas cuando cambie.
        // lanzar una rutina independiente dentro de la devolución de llamada,Evite bloquear la rutina de procesamiento de notificaciones de McpClient.
        client.onToolsListChanged = {
            scope.launch {
                refreshTools(config.id, client)
            }
        }
        // B3-09: prompts/resources Activar señal de actualización cuando se cambia(La lista es para consultas bajo demanda.,Abre la siguiente ronda y obtén automáticamente lo último)
        client.onPromptsListChanged = {
            Logger.i(TAG, "[${config.id}] prompts/list_changed Notificación,Actualizar automáticamente la siguiente consulta")
        }
        client.onResourcesListChanged = {
            Logger.i(TAG, "[${config.id}] resources/list_changed Notificación,Actualizar automáticamente la siguiente consulta")
        }
        // P2-24: Sincronice el estado del registro + herramienta para volver a extraer después de que la reconexión automática sea exitosa(El conjunto de herramientas puede cambiar durante la desconexión)
        client.onReconnected = {
            scope.launch { handleServerReconnected(config.id, client) }
        }

        client.start()
        // M-REG1: usar first{} Reemplaza el sondeo de 200 ms,Esperando el estado final(CONNECTED/FAILED/NEEDS_AUTH)
        val finalState = withTimeoutOrNull(10_000L) {
            client.state.first {
                it == McpConnectionState.CONNECTED ||
                    it == McpConnectionState.FAILED ||
                    it == McpConnectionState.NEEDS_AUTH
            }
        }
        if (finalState == null) {
            // tiempo de espera:Planta baja client.start() La rutina de conexión iniciada todavía se ejecuta en segundo plano.,
            // Desconectar directamente client(es close() voluntad scope.cancel() Cancelar todas las corrutinas),Evite estados de registro inexactos
            Logger.w(TAG, "[${config.id}] connectServer 10s tiempo de espera,Cancelar la conexión subyacente")
            disconnectServer(config.id)
            return
        }
        updateState(config.id, finalState)

        // Conexión exitosa,Tire de las herramientas y regístrese para ToolRegistry
        if (finalState == McpConnectionState.CONNECTED) {
            registerTools(config.id, client)
            markConnected(config.id)
        }
    }

    /** Desconectarse del servidor,Cerrar sesión en herramientas. */
    private fun disconnectServer(id: String) {
        clients.remove(id)?.let { client ->
            // Phase 9.5 (P3-2): Borrar referencia de devolución de llamada,Evite que las notificaciones sigan apareciendo después de la desconexión refreshTools
            client.onToolsListChanged = null
            client.onPromptsListChanged = null
            client.onResourcesListChanged = null
            client.onReconnected = null
            client.close()
            // Cerrar sesión en todas las herramientas de este servidor(coincidencia de prefijos)
            unregisterTools(id)
        }
        toolsReadyServers.remove(id)
        toolLoadFailedServers.remove(id)
        // Limpiar etiqueta de actualización,Evite errores de juicio comparandoAndSet cuando se vuelva a conectar la próxima vez
        refreshingTools.remove(id)
        _serversState.update { it - id }
    }

    /**
     * P2-24: McpClient Se llama después de que la reconexión automática sea exitosa — Sincronice el estado del registro y vuelva a extraer herramientas.
     * El conjunto de herramientas del servidor puede cambiar durante una desconexión,Debe dar de baja el antiguo y registrar el nuevo.;
     * al mismo tiempo [McpConnectionState] Sincronizar de nuevo CONNECTED,Evite que la interfaz de usuario muestre un estado obsoleto.
     */
    private suspend fun handleServerReconnected(serverId: String, client: McpClient) {
        if (clients[serverId] !== client) return // desconectado/reemplazado client,Ignorar viejas devoluciones de llamada
        updateState(serverId, McpConnectionState.CONNECTED)
        if (client.state.value != McpConnectionState.CONNECTED) return
        Logger.i(TAG, "[$serverId] Reconexión automática exitosa,Redibujar la lista de herramientas")
        refreshTools(serverId, client)
        markConnected(serverId)
    }

    /**
     * Extraer del servidor MCP tools/list,Regístrese para [ToolRegistry].
     * Nombre de la herramienta: `mcp_{serverId}__{originalToolName}`
     */
    private suspend fun registerTools(serverId: String, client: McpClient) {
        toolsReadyServers.remove(serverId)
        toolLoadFailedServers.remove(serverId)
        var tools: List<McpTool>? = null
        for (attempt in 0 until 3) {
            val toolsResult = resultOf { client.listToolsOrNull() }
            tools = toolsResult
                .onError { msg, _ -> Logger.w(TAG, "[$serverId] tools/list falló(attempt=${attempt + 1}): $msg") }
                .getOrNull()
            if (tools != null) break
            if (attempt < 2) delay(if (attempt == 0) 250L else 1_000L)
        }
        val resolvedTools = tools ?: run {
            toolLoadFailedServers.add(serverId)
            return
        }
        Logger.i(TAG, "[$serverId] Registrarse ${resolvedTools.size} herramientas MCP")
        // v1.0.79 (C-3): conectar+Después de un registro exitoso,Vincular automáticamente el servidor a la extensión auxiliar principal,
        // De lo contrario, el asistente mcpServerIds filtra el esquema principal de las herramientas de chat.,No puedo ver las herramientas para el nuevo MCP.
        autoBindToDefaultAssistant(serverId)
        resolvedTools.forEach { tool ->
            val registeredName = "mcp_${serverId}__${tool.name}"
            val description = tool.description ?: context.getString(R.string.mcp_tool_no_description)
            // Analizar nombres de parámetros de inputSchema(JSON Schema properties de key)
            val params = parseInputSchema(tool.inputSchema)
            val required = parseRequired(tool.inputSchema)
            val parameterTypes = parseInputSchemaTypes(tool.inputSchema)
            toolRegistry.registerJson(
                ToolRegistry.ToolDef(
                    name = registeredName,
                    description = "[MCP server=$serverId tool=${tool.name}] $description",
                    parameters = params,
                    required = required,
                    category = "mcp",
                    parameterTypes = parameterTypes,
                    rawParametersJsonSchema = tool.inputSchema?.toString(),
                    // El nombre de la herramienta de pares lo elige un tercero. — `get_diagnostics` También puede ser una llamada saliente.
                    // No utilice nombres para impulsar riesgos, unifique criterios de herramientas dinámicas (conservador NORMAL,ASK Aparece el modo para su aprobación).
                    riskLevel = ToolPermissionResolver.riskLevelFor(registeredName),
                ),
            ) { args ->
                // Preservar el tipo JSON declarado por inputSchema,Evite que booleano/número/matriz/objeto se comprima en cadenas.
                val arguments = coerceMcpArguments(args, tool.inputSchema)
                val timeoutMs = _servers.value.firstOrNull { it.id == serverId }
                    ?.requestTimeoutMs
                    ?.coerceIn(1_000L, 120_000L)
                    ?: 30_000L
                try {
                    val result = withTimeoutOrNull(timeoutMs) {
                        client.callTool(tool.name, arguments)
                    }
                    if (result == null) {
                        // El tiempo de espera ya no es silencioso: use requestTimeoutMs configurado por el servidor para devolver el motivo del error
                        // (v2.2.1: Con nombre del servidor y segundos de tiempo de espera.,El modelo puede localizar directamente qué servidor es lento.).
                        Logger.w(TAG, "[$serverId] Herramientas ${tool.name} tiempo de espera de llamada(${timeoutMs}ms)")
                        "Error: " + context.getString(
                            R.string.mcp_tool_call_timeout,
                            "$serverId / ${tool.name} (${timeoutMs / 1000}s)",
                        )
                    } else if (result.isError) {
                        // v1.0.79 (A-1): Detalles completos del error devueltos — server regresó content/structuredContent
                        // Tráelos a todos de vuelta,El modelo sólo puede corregirse a sí mismo si comprende la causa del fallo.
                        val detail = formatToolResult(result)
                        Logger.w(TAG, "[$serverId] Herramientas ${tool.name} llamada fallida: $detail")
                        "Error: " + context.getString(
                            R.string.mcp_tool_call_failed,
                            "$serverId / ${tool.name}",
                        ) + ": " + detail
                    } else {
                        formatToolResult(result)
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // v2.2.1: La llamada arroja una excepción(Conexión perdida/Errores de protocolo, etc.)No más caer en la trampa general,Devolver con el nombre del servidor/herramienta.
                    Logger.w(TAG, "[$serverId] Herramientas ${tool.name} excepción de llamada: ${e.message}", e)
                    "Error: [MCP $serverId] Herramientas '${tool.name}' excepción de llamada(${e.javaClass.simpleName}): ${e.message ?: "error desconocido"}"
                }
            }
        }
        toolsReadyServers.add(serverId)
    }

    /**
     * v1.0.79 (C-3): Vincule automáticamente el servidor MCP conectado a la extensión del asistente principal.
     *
     * fondo: El servidor MCP recién agregado no ingresará automáticamente a la configuración del asistente una vez que la conexión sea exitosa.(mcpServerIdsJson),
     * Y el esquema de las herramientas de chat se filtra por el asistente mcpServerIds. → La herramienta MCP no es visible en el chat principal.
     * Este método se llama después de que RegisterTools tenga éxito.,Escriba serverId en la lista principal de extensiones auxiliares(idempotente).
     *
     * Nota: Sólo en asistente principal(Asistente predeterminado)Encuadernación automática;sub-asistente/Los usuarios aún administran los asistentes de equipo en la página del asistente.
     * control explícito,Evite la difusión automática a asistentes irrelevantes.
     */
    private suspend fun autoBindToDefaultAssistant(serverId: String) {
        val repo = assistantRepository ?: return
        if (serverId.isBlank()) return
        try {
            // La identificación del asistente principal está fijada en "default"(Consistente con MemoryTicker.MAIN_ASSISTANT_ID)
            val defaultAssistant = repo.getById("default") ?: run {
                repo.ensureDefaultExists()
                repo.getById("default")
            } ?: return
            val bound = repo.parseMcpServerIds(defaultAssistant).toMutableSet()
            if (serverId in bound) return // Atado,idempotente
            bound.add(serverId)
            repo.upsert(
                defaultAssistant.copy(mcpServerIdsJson = repo.serializeStringList(bound.toList())),
            )
            Logger.i(TAG, "[$serverId] Vinculado automáticamente a la extensión auxiliar principal(mcpServerIds=${bound.size})")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "[$serverId] Falló el enlace automático del asistente principal(No afecta el registro de herramientas.): ${e.message}")
        }
    }

    /** Cierre sesión en todas las herramientas para el servidor especificado. */
    private fun unregisterTools(serverId: String) {
        val prefix = "mcp_${serverId}__"
        toolRegistry.listTools()
            .filter { it.name.startsWith(prefix) }
            .forEach { toolRegistry.unregister(it.name) }
    }

    /**
     * Phase 9.5 (P3-2): Actualiza la lista de herramientas para el servidor especificado.
     *
     * escena desencadenante:server problema `notifications/tools/list_changed`(MCP 2025-03-26 §Tools),
     * Indica que el conjunto de herramientas se puede agregar, eliminar o modificar.,client La lista/herramientas almacenadas en caché ha caducado.
     *
     * proceso:
     *  1. Protección de reentrada:compareAndSet Evitar la actualización simultánea
     *  2. Verificar que el cliente todavía esté conectado(Notificaciones ignoradas durante la desconexión)
     *  3. unregisterTools(Qingjiu) → registerTools(Reclutar)
     *
     * Idempotencia:registerTools Dentro de toolRegistry.register hay una anulación,Es seguro registrar una herramienta con el mismo nombre repetidamente;
     * Pero primero cancele el registro y luego regístrese para asegurarse de que la herramienta eliminada se elimine correctamente.(Sólo la sobrescritura no se puede eliminar).
     *
     * @param serverId server logotipo
     * @param client correspondiente McpClient(Es posible que se haya desconectado después de ser sacado del mapa de clientes.,Necesito verificar state)
     */
    private suspend fun refreshTools(serverId: String, client: McpClient) {
        // Protección de reentrada:El mismo servidor sólo permite una actualización al mismo tiempo.
        // ConcurrentHashMap Ninguno compareAndSet,Simular con putIfAbsent:Devolver nulo indica la adquisición exitosa del candado.
        if (refreshingTools.putIfAbsent(serverId, true) != null) {
            Logger.d(TAG, "[$serverId] tools/list_changed activación de actualización,Pero ya hay una actualización en ejecución.,saltar")
            return
        }
        try {
            // Verificar que el cliente todavía esté conectado(Es posible que las notificaciones durante la desconexión aún estén en la cola de eventos)
            if (client.state.value != McpConnectionState.CONNECTED) {
                Logger.d(TAG, "[$serverId] tools/list_changed Llegó pero el cliente no está conectado,omitir actualización")
                return
            }
            Logger.i(TAG, "[$serverId] recibido tools/list_changed,Redibujar la lista de herramientas")
            unregisterTools(serverId)
            registerTools(serverId, client)
        } finally {
            refreshingTools.remove(serverId)
        }
    }

    /** de inputSchema(JSON Schema)Nombre del parámetro de análisis → Descripción. */
    private fun parseInputSchema(schema: kotlinx.serialization.json.JsonElement?): Map<String, String> {
        if (schema == null) return emptyMap()
        val obj = schema as? JsonObject ?: return emptyMap()
        val props = obj["properties"] as? JsonObject ?: return emptyMap()
        return props.entries.associate { (name, def) ->
            val defObj = def as? JsonObject
            val desc = (defObj?.get("description") as? JsonPrimitive)?.content
                ?: (defObj?.get("type") as? JsonPrimitive)?.content
                ?: "any"
            name to desc
        }
    }

    /** Analice los campos obligatorios de inputSchema. */
    private fun parseRequired(schema: kotlinx.serialization.json.JsonElement?): Set<String> {
        if (schema == null) return emptySet()
        val obj = schema as? JsonObject ?: return emptySet()
        val required = obj["required"] ?: return emptySet()
        return runCatching {
            // required es una matriz JSON of string
            val arr = required as? kotlinx.serialization.json.JsonArray ?: return emptySet()
            arr.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Espere a que el servidor MCP vinculado al asistente complete el protocolo de enlace y registre la herramienta.
     *
     * Cuando la aplicación se inicia por primera vez, la conexión MCP y el usuario que envía mensajes pueden ocurrir simultáneamente.;Si solo lees una vez ToolRegistry,
     * El primer mensaje no verá las herramientas de MCP hasta que regrese la lista de herramientas. Permitir que el chat continúe después del tiempo de espera,Sin embargo, se registrará un estado de no listo.
     */
    suspend fun awaitToolsForServers(serverIds: Set<String>, timeoutMs: Long? = null): Boolean {
        if (serverIds.isEmpty()) return true
        startAll()
        // Los servidores con URL no configuradas, deshabilitadas o vacías no se conectarán mediante startAll,Devolver el estado de falla directamente,
        // No haga que cada mensaje espere inútilmente durante un tiempo de espera completo.
        // No puedo leer solo aquí _servers.value:startAll() Se inicia de forma asíncrona.,Las primeras noticias pueden llegar justo a tiempo.
        // settings flow collector antes. Leer directamente una instantánea persistente,Elimine esta condición de carrera de inicio.
        val savedServers = settings.mcpServersFlow.first()
        _servers.value = savedServers
        val configuredServerIds = savedServers
            .asSequence()
            .filter { it.enabled && it.url.isNotBlank() }
            .map { it.id }
            .toSet()
        if (!serverIds.all { it in configuredServerIds }) return false
        // v1.0.92 (B-2): El tiempo de espera de herramienta lista toma el valor máximo según la configuración del servidor.;Aprobado explícitamente en prioridad,La reserva predeterminada es 5 segundos.
        val effectiveTimeoutMs = timeoutMs ?: serverIds
            .mapNotNull { id -> savedServers.firstOrNull { it.id == id }?.toolsReadyTimeoutMs }
            .maxOrNull()
            ?: 5_000L
        return withTimeoutOrNull<Boolean>(effectiveTimeoutMs) {
            var ready = false
            var failed = false
            while (!ready && !failed) {
                ready = serverIds.all { it in toolsReadyServers }
                failed = !ready && serverIds.any { id ->
                    id in toolLoadFailedServers ||
                        serversState.value[id] in setOf(
                            McpConnectionState.FAILED,
                            McpConnectionState.NEEDS_AUTH,
                        )
                }
                if (!ready && !failed) delay(50L)
            }
            ready && !failed
        } ?: false
    }

    /** Analizar tipos de parámetros de inputSchema,Para uso del esquema del proveedor de suma de comprobación de la herramienta JSON de ToolRegistry. */
    private fun parseInputSchemaTypes(schema: JsonElement?): Map<String, String> {
        val obj = schema as? JsonObject ?: return emptyMap()
        val props = obj["properties"] as? JsonObject ?: return emptyMap()
        return props.mapValues { (_, definition) ->
            ((definition as? JsonObject)?.get("type") as? JsonPrimitive)?.content ?: "string"
        }
    }

    /** Restaure el parámetro de cadena de ToolRegistry al valor JSON declarado por MCP inputSchema. */
    private fun coerceMcpArguments(args: JsonObject, schema: JsonElement?): JsonObject {
        val types = parseInputSchemaTypes(schema)
        return buildJsonObject {
            args.forEach { (name, value) ->
                val raw = (value as? JsonPrimitive)?.content ?: value.toString()
                val type = types[name]
                put(
                    name,
                    when (type) {
                        "integer" -> raw.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                        "number" -> raw.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                        "boolean" -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(raw)
                        "array", "object" -> runCatching { AppJson.parseToJsonElement(raw) }
                            .getOrElse { JsonPrimitive(raw) }
                        else -> JsonPrimitive(raw)
                    },
                )
            }
        }
    }

    /** Conversión unificada de contenido MCP en texto que se puede rellenar en el modelo,Los resultados de error también conservan la causa del servidor. */
    private fun formatToolContent(content: List<JsonElement>): String = content.joinToString("\n") { element ->
        val obj = element as? JsonObject
        val type = (obj?.get("type") as? JsonPrimitive)?.content
        val text = (obj?.get("text") as? JsonPrimitive)?.content
        if (type == "text" && text != null) text else element.toString()
    }

    /** MCP server sólo puede regresar structuredContent,Este resultado exitoso no puede reportarse erróneamente como vacío. */
    private fun formatToolResult(result: McpToolCallResult): String {
        val parts = buildList {
            formatToolContent(result.content).takeIf { it.isNotBlank() }?.let(::add)
            result.structuredContent?.let { add(it.toString()) }
        }
        return parts.joinToString("\n").ifBlank { "(MCP server returned no content)" }
    }

    /**
     * v1.0.92 (B-4): Registre la última hora de conexión exitosa del servidor(No activa la reconexión).
     * para la página de configuración"Última conexión"mostrar;Registros de fallos de persistencia únicamente,No afecta el proceso de conexión principal.
     */
    private suspend fun markConnected(id: String) {
        val now = System.currentTimeMillis()
        val newList = _servers.value.map { if (it.id == id) it.copy(lastConnectedAt = now) else it }
        if (newList == _servers.value) return
        runCatching { persist(newList) }.onFailure { Logger.w(TAG, "[$id] No se pudo registrar la última hora de conexión: ${it.message}") }
        _servers.value = newList
    }

    /** Actualice el estado de conexión de un único servidor. */
    private fun updateState(id: String, state: McpConnectionState) {
        _serversState.update { it + (id to state) }
    }

    /** Registre la herramienta de configuración MCP para que la utilice el asistente. La definición y ejecución de la herramienta están en el mismo registro para evitar“Puede llamar a MCP pero no puede crear MCP”. */
    private fun registerManagementTools() {
        fun def(
            name: String,
            description: String,
            parameters: Map<String, String>,
            required: Set<String> = emptySet(),
            risk: io.zer0.muse.tools.ToolRiskLevel = io.zer0.muse.tools.ToolRiskLevel.NORMAL,
        ) = io.zer0.muse.tools.ToolRegistry.ToolDef(name, description, parameters, required, "mcp", riskLevel = risk)
        toolRegistry.register(
            def("mcp_mgmt_list", "Enumera los servidores MCP actualmente configurados y el estado de la conexión.", emptyMap(), risk = io.zer0.muse.tools.ToolRiskLevel.SAFE),
        ) { _ ->
            val current = _servers.value
            if (current.isEmpty()) {
                "Actualmente no hay ningún servidor MCP configurado."
            } else {
                current.joinToString("\\n") { cfg ->
                    val endpoint = if (cfg.transportType == McpTransportType.STDIO) cfg.command else cfg.url
                    val state = _serversState.value[cfg.id] ?: McpConnectionState.DISCONNECTED
                    val enabledText = if (cfg.enabled) "enabled" else "disabled"
                    "${cfg.id} | ${cfg.name} | ${cfg.transportType.name} | $enabledText | $state | $endpoint"
                }
            }
        }
        toolRegistry.register(
            def(
                "mcp_mgmt_configure",
                "Cree o actualice la configuración del servidor MCP (SSE remoto/HTTP transmitible o STDIO local) y automatice conexiones, apretones de manos y herramientas de descubrimiento." +
                    "STDIO El modo ejecuta el comando en el tiempo de ejecución de Nodo incorporado (admite node / npm / npx comando), por ejemplo " +
                    "npx -y @modelcontextprotocol/server-filesystem /sdcard/Documents.",
                mapOf(
                    "id" to "ID único estable, se puede omitir",
                    "name" to "Nombre para mostrar del servidor",
                    "transport" to "SSE / STREAMABLE_HTTP / STDIO, por defecto STREAMABLE_HTTP",
                    "url" to "Punto final de transmisión de red (http/https);STDIO Patrón no requerido",
                    "command" to "STDIO comando de inicio del modo (node / npm / npx), por ejemplo npx -y @modelcontextprotocol/server-memory",
                    "auth_token" to "Token de portador opcional, que se cifrará y guardará",
                    "headers_json" to "Objeto JSON opcional, p. {\"X-API-Key\":\"...\"}",
                    "enabled" to "Opcional verdadero/falso, predeterminado true",
                ),
                setOf("name"),
                io.zer0.muse.tools.ToolRiskLevel.HIGH,
            ),
        ) { args -> configureFromTool(args) }
        toolRegistry.register(
            def(
                "mcp_mgmt_remove",
                "Elimine el servidor MCP configurado y cierre sesión en sus herramientas.",
                mapOf("id" to "MCP server id"),
                setOf("id"),
                io.zer0.muse.tools.ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val id = args["id"].orEmpty().trim()
            if (id.isBlank()) {
                "Falta la identificación del servidor MCP."
            } else if (_servers.value.none { it.id == id }) {
                "Servidor MCP no encontrado: $id"
            } else {
                removeServer(id)
                "Servidor MCP eliminado $id."
            }
        }
        toolRegistry.register(
            def(
                "mcp_mgmt_bind_assistant",
                "Vincule el servidor MCP configurado al asistente especificado, dando a sus herramientas acceso a las capacidades de diálogo del asistente.",
                mapOf("server_id" to "MCP server id", "assistant_id" to "ID del asistente, cuando se omite, es default"),
                setOf("server_id"),
            ),
        ) { args ->
            bindServerToAssistant(args["server_id"].orEmpty().trim(), args["assistant_id"].orEmpty().trim().ifBlank { "default" })
        }
        toolRegistry.register(def("mcp_mgmt_reconnect", "Vuelva a conectarse al servidor MCP configurado y actualice la lista de herramientas.", mapOf("id" to "MCP server id"), setOf("id"))) { args ->
            val id = args["id"].orEmpty().trim()
            if (_servers.value.none { it.id == id }) {
                "Servidor MCP no encontrado: $id"
            } else {
                reconnect(id)
                "Comenzó a reconectarse al servidor MCP $id."
            }
        }
    }

    private suspend fun configureFromTool(args: Map<String, String>): String {
        val name = args["name"].orEmpty().trim()
        val url = args["url"].orEmpty().trim()
        val command = args["command"].orEmpty().trim()
        val transport = when (args["transport"].orEmpty().trim().uppercase()) {
            "SSE" -> McpTransportType.SSE
            "STDIO" -> McpTransportType.STDIO
            "", "STREAMABLE_HTTP", "STREAMABLEHTTP", "HTTP" -> McpTransportType.STREAMABLE_HTTP
            else -> return "transport solo puede ser SSE / STREAMABLE_HTTP / STDIO."
        }
        // P1-A: stdio Usar comando para verificar;La transmisión de red utiliza verificación de URL
        if (transport == McpTransportType.STDIO) {
            if (name.isBlank() || command.isBlank()) return "stdio En modo, ni el nombre ni el comando pueden estar vacíos."
        } else {
            if (name.isBlank() || url.isBlank()) return "name y la URL no puede estar vacía."
            val parsedUrl = runCatching { java.net.URI(url) }.getOrNull()
            val badUrl = parsedUrl == null ||
                parsedUrl.scheme !in setOf("http", "https") ||
                parsedUrl.host.isNullOrBlank()
            if (badUrl) return "MCP url Debe ser una dirección http/https legal."
        }
        val id = args["id"].orEmpty().trim().ifBlank {
            name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                .ifBlank { "mcp_${System.currentTimeMillis()}" }
        }
        // v1.0.92 (B-6): Los identificadores pasados explícitamente también están sujetos a restricciones de forma.,Evite que los caracteres especiales rompan los prefijos de los nombres de las herramientas.
        if (!SERVER_ID_REGEX.matches(id)) {
            return "id Solo puede contener letras, números, guiones bajos y guiones.(Máximo 64 caracteres)."
        }
        val headers = args["headers_json"].orEmpty().trim().let { raw ->
            if (raw.isBlank()) {
                emptyMap()
            } else {
                runCatching {
                    val parsedRaw = io.zer0.common.AppJson.parseToJsonElement(raw)
                    val obj = parsedRaw as? kotlinx.serialization.json.JsonObject
                        ?: return "headers_json Debe ser un objeto JSON."
                    obj.mapValues { (_, value) -> value.toString().trim('"') }
                }.getOrElse { return "headers_json JSON no válido:${it.message ?: "Error de formato"}" }
            }
        }
        val enabled = args["enabled"].orEmpty().trim().lowercase().let { it != "false" && it != "0" }
        return upsertServer(
            McpServerConfig(
                id = id,
                name = name,
                transportType = transport,
                url = url,
                command = command,
                headers = headers,
                authToken = args["auth_token"].orEmpty().trim(),
                enabled = enabled,
            ),
        )
    }

    /** Conserve la lista de servidores en el DataStore. */
    private suspend fun persist(servers: List<McpServerConfig>) {
        settings.saveMcpServers(servers)
    }

    /**
     * v1.113: cerrar registry,Liberar recursos del grupo de subprocesos.
     *
     * McpRegistry Es un singleton de Koin,App Llamado a la salida. Apague el grupo de subprocesos dedicado y scope,
     * Evite fugas de hilo.
     */
    fun shutdown() {
        scope.cancel()
        Logger.i(TAG, "McpRegistry shutdown: scope cancelled")
    }

    private companion object {
        const val TAG = "McpRegistry"
    }
}
