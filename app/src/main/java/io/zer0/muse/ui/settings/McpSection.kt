package io.zer0.muse.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.mcp.McpConnectionState
import io.zer0.muse.mcp.McpFeishuAuthConfig
import io.zer0.muse.mcp.McpPrompt
import io.zer0.muse.mcp.McpPromptResult
import io.zer0.muse.mcp.McpRegistry
import io.zer0.muse.mcp.McpResource
import io.zer0.muse.mcp.McpResourceContent
import io.zer0.muse.mcp.McpServerConfig
import io.zer0.muse.mcp.McpServerTemplates
import io.zer0.muse.mcp.McpTransportType
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseActionSheetRow
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.compose.koinInject

/**
 * Etapa 7: gestión del servidor MCP section — Lista agrupada.
 *
 * Cada servidor se representa como [SettingsGroup] una línea dentro,nombre para mostrar/URL/Estado,
 * Al final a la derecha está el botón de reconectar + eliminar,Delgada línea divisoria entre líneas.
 * Agregar botón fuera del grupo(Alinear al final de la lista del sistema"agregar"Operación).
 */
@Composable
internal fun McpSection() {
    val mcpRegistry: McpRegistry = koinInject()
    // v1.0.79 (E-1): inyectar ToolRegistry,Se utiliza para contar el número de herramientas registradas para cada servidor.(Aviso al conectar pero 0 herramientas)
    val toolRegistry: io.zer0.muse.tools.ToolRegistry = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val servers by mcpRegistry.servers.collectAsStateWithLifecycle(initialValue = null)
    val serversState by mcpRegistry.serversState.collectAsStateWithLifecycle(initialValue = null)

    var showAddDialog by remember { mutableStateOf(false) }

    SectionLabel(stringResource(R.string.settings_mcp_title))
    Text(
        text = stringResource(R.string.settings_mcp_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
    // v1.0.92 (B-5): Instrucciones de determinación automática de riesgos(plegable,Explicar por qué ciertas herramientas requieren aprobación.)
    var riskNoteExpanded by remember { mutableStateOf(false) }
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable { riskNoteExpanded = !riskNoteExpanded },
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_mcp_risk_note_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    // LAYOUT-01: El título plegado toma el ancho restante + límite de línea.(Cuando solo se toma el ancho, el ancho restante aún puede ser extremadamente pequeño,
                    // Los caracteres chinos se ajustarán palabra por palabra para que toda la línea sea más alta.),Evite que el ícono de galón a la derecha lo apriete.
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (riskNoteExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(
                visible = riskNoteExpanded,
                // v2.x: ficha de complemento spec(estaba por defecto spec)
                enter = fadeIn(animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS)) +
                    expandVertically(
                        animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                    ),
                exit = fadeOut(animationSpec = MuseMotion.tween(MuseAnimation.FAST_MS)) +
                    shrinkVertically(
                        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                    ),
            ) {
                Text(
                    text = stringResource(R.string.settings_mcp_risk_note_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // v1.74: Evitación de captura de variables locales !! aserción no nula(Las propiedades delegadas no pueden smart-cast)
        val serverList = servers
        when {
            serverList == null -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseSpinner(
                        size = 28.dp,
                    )
                }
            }
            serverList.isEmpty() -> {
                // El estado vacío proporciona directamente la entrada para agregar: anteriormente solo había una oración "Haga clic a continuación para agregar".
                // y lo real `+` En otra parte de la página, el usuario debe llenar la pantalla para encontrar el botón.
                MuseEmptyState(
                    icon = MuseIcons.puzzle,
                    title = stringResource(R.string.settings_mcp_empty_title),
                    subtitle = stringResource(R.string.settings_mcp_empty_subtitle),
                    actionText = stringResource(R.string.settings_mcp_add_action),
                    onAction = { showAddDialog = true },
                )
            }
            else -> {
                // v1.0.92 Rendimiento: Precalcular el número de herramientas para cada servidor. — La implementación anterior escanea completamente la tabla de herramientas para cada fila del servidor.
                // (O(server×tools),Ejecute cada reorganización);En su lugar, actualice una vez por estado de conexión y atributo por prefijo.
                val toolCountByServerId = remember(serverList, serversState) {
                    val all = toolRegistry.listTools()
                    serverList.associate { s -> s.id to all.count { it.name.startsWith("mcp_${s.id}__") } }
                }
                serverList.forEachIndexed { index, server ->
                    if (index > 0) SettingsGroupDivider()
                    val state = serversState?.get(server.id) ?: McpConnectionState.DISCONNECTED
                    val reconnect = {
                        resultOf { mcpRegistry.reconnect(server.id) }
                            .onError { _, t ->
                                MuseToast.show(context.getString(R.string.settings_mcp_failed, t?.message))
                            }
                        Unit
                    }
                    McpServerRow(
                        server = server,
                        state = state,
                        mcpRegistry = mcpRegistry,
                        // v1.0.79 (E-1): El número de herramientas registradas para este servidor.(Advertencia cuando la conexión es exitosa pero 0 herramientas)
                        // v1.0.92 Rendimiento: Obtener valor precalculado,No más escaneo progresivo de tablas de herramientas
                        toolCount = toolCountByServerId[server.id] ?: -1,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                resultOf { mcpRegistry.updateServer(server.copy(enabled = enabled)) }
                                    .onError { _, t ->
                                        MuseToast.show(context.getString(R.string.settings_mcp_failed, t?.message))
                                    }
                            }
                        },
                        onReconnect = reconnect,
                        onDelete = {
                            scope.launch {
                                resultOf { mcpRegistry.removeServer(server.id) }
                                    .onError { _, t ->
                                        MuseToast.show(context.getString(R.string.settings_mcp_failed, t?.message))
                                    }
                            }
                        },
                    )
                    if (state == McpConnectionState.FAILED) {
                        MuseErrorStateBox(
                            message = stringResource(R.string.settings_mcp_failed, server.name),
                            onRetry = reconnect,
                            modifier = Modifier.padding(top = MusePaddings.tinyGap),
                        )
                    }
                }
            }
        }
    }

    // botón agregar(Fuera del grupo)
    MuseTactileButton(
        icon = MuseIcons.plus,
        onClick = { showAddDialog = true },
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp),
    )

    if (showAddDialog) {
        McpServerAddDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { config -> mcpRegistry.addServer(config) },
        )
    }
}

/**
 * Fila de servidor MCP única(punto de estado + nombre/URL/Estado + Interruptor de inicio/parada + Más menú + Reconectar/boton borrar).
 *
 * Extraído como Composable independiente es para permitir"Más"Menú desplegable con diálogo de exploración de Recursos/Solicitudes
 * El estado se puede mantener de forma independiente por fila.(Evite compartir recuerde el estado en forEach).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpServerRow(
    server: McpServerConfig,
    state: McpConnectionState,
    mcpRegistry: McpRegistry,
    /** v1.0.79 (E-1): El número de herramientas registradas para este servidor.;CONNECTED Pero cuando es 0, se emite una advertencia. */
    toolCount: Int = -1,
    onToggleEnabled: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    var showResources by remember { mutableStateOf(false) }
    var showPrompts by remember { mutableStateOf(false) }
    // OAuth apoyo:Configuración de OAuth habilitada o registrada clientId
    val oauthSupported = server.oauthConfig.let { it.enabled || it.clientId.isNotBlank() }
    // cadena de descripción de estado(Debe extraerse en el contexto @Composable,no puedo estar ahí semantics{} lambda llamar dentro stringResource)
    val stateEnabledText = stringResource(R.string.settings_state_enabled)
    val stateDisabledText = stringResource(R.string.settings_state_disabled)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Punto indicador de estado(Sensación de lujo)
        io.zer0.muse.ui.common.settings.StatusDot(
            color = when (state) {
                McpConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                McpConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                McpConnectionState.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
            // L-SC5: StatusDot.size Cambiar tipo de DP,llamador 8 → 8.dp.
            size = 8.dp,
            pulse = state == McpConnectionState.CONNECTING,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name.ifBlank { server.id },
                style = MaterialTheme.typography.bodyLarge,
                color = if (server.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
            Text(
                text = if (server.transportType == McpTransportType.STDIO) {
                    "${server.transportType.name} · ${server.command.take(50)}${if (server.command.length > 50) "..." else ""}"
                } else {
                    "${server.transportType.name} · ${server.url.take(50)}${if (server.url.length > 50) "..." else ""}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = state.name,
                style = MaterialTheme.typography.labelSmall,
                color = when (state) {
                    McpConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                    McpConnectionState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
            )
            // v1.0.79 (E-1): Conectado pero sin herramientas registradas — Solicita al usuario que verifique los enlaces del servidor o de la extensión auxiliar
            if (state == McpConnectionState.CONNECTED && toolCount == 0) {
                Text(
                    text = stringResource(R.string.settings_mcp_connected_no_tools),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (state == McpConnectionState.CONNECTED && toolCount > 0) {
                Text(
                    text = stringResource(R.string.settings_mcp_connected_tools, toolCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            // v1.0.92 (B-4): Muestra la última hora de conexión exitosa cuando no está conectado(Ayuda a determinar si el servidor ha estado disponible)
            if (state != McpConnectionState.CONNECTED && server.lastConnectedAt > 0L) {
                Text(
                    text = stringResource(
                        R.string.settings_mcp_last_connected,
                        android.text.format.DateUtils.getRelativeTimeSpanString(server.lastConnectedAt).toString(),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        // Etapa D: interruptor de arranque/parada(independiente de la conexión,Permitir que la configuración se conserve pero no se conecte)
        MuseSwitch(
            checked = server.enabled,
            onCheckedChange = onToggleEnabled,
            modifier = Modifier.semantics {
                stateDescription = if (server.enabled) stateEnabledText else stateDisabledText
            },
        )
        // v1.134 P0-6: Más botones del menú de operación para MuseTactileButton(48dp Tocar objetivo + Ninguno ripple)
        MuseTactileButton(
            icon = MuseIcons.moreVertical,
            onClick = { menuExpanded = true },
            contentDescription = stringResource(R.string.settings_mcp_more),
        )
        // v1.134 P0-7: DropdownMenu → MuseDialog Lista de operaciones
        if (menuExpanded) {
            MuseDialog(
                onDismissRequest = { menuExpanded = false },
                title = server.name,
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (oauthSupported) {
                            MuseActionSheetRow(text = stringResource(R.string.settings_mcp_oauth_authorize)) {
                                menuExpanded = false
                                resultOf { mcpRegistry.startOAuthFlow(server.id) }
                                    .onSuccess { url ->
                                        if (url == null) {
                                            MuseToast.show(context.getString(R.string.settings_mcp_oauth_cannot_start))
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                            }.onFailure {
                                                MuseToast.show(context.getString(R.string.settings_mcp_oauth_open_failed, it.message))
                                            }
                                        }
                                    }
                                    .onError { _, t ->
                                        MuseToast.show(context.getString(R.string.settings_mcp_failed, t?.message))
                                    }
                            }
                            MuseActionSheetRow(text = stringResource(R.string.settings_mcp_oauth_revoke)) {
                                menuExpanded = false
                                scope.launch {
                                    resultOf { mcpRegistry.revokeOAuth(server.id) }
                                        .onSuccess {
                                            MuseToast.show(context.getString(R.string.settings_mcp_oauth_revoked))
                                        }
                                        .onError { _, t ->
                                            MuseToast.show(context.getString(R.string.settings_mcp_oauth_revoke_failed, t?.message))
                                        }
                                }
                            }
                        }
                        MuseActionSheetRow(text = stringResource(R.string.settings_mcp_browse_resources)) {
                            menuExpanded = false
                            showResources = true
                        }
                        MuseActionSheetRow(text = stringResource(R.string.settings_mcp_browse_prompts)) {
                            menuExpanded = false
                            showPrompts = true
                        }
                        MuseActionSheetRow(text = stringResource(R.string.settings_mcp_reconnect)) {
                            menuExpanded = false
                            onReconnect()
                        }
                        MuseActionSheetRow(
                            text = stringResource(R.string.settings_common_delete),
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                },
                confirmText = stringResource(R.string.common_close),
                onConfirm = { menuExpanded = false },
                onDismiss = { menuExpanded = false },
            )
        }
    }

    if (showResources) {
        ResourcesBrowserDialog(
            serverId = server.id,
            mcpRegistry = mcpRegistry,
            onDismiss = { showResources = false },
        )
    }
    if (showPrompts) {
        PromptsBrowserDialog(
            serverId = server.id,
            mcpRegistry = mcpRegistry,
            onDismiss = { showPrompts = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun McpServerAddDialog(onDismiss: () -> Unit, onAdd: suspend (McpServerConfig) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var transportType by remember {
        mutableStateOf(McpTransportType.STREAMABLE_HTTP)
    }
    var authToken by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Campos avanzados(Mostrar modo contraído avanzado por ProviderSection)
    var showAdvanced by remember { mutableStateOf(false) }
    var headersText by remember { mutableStateOf("") }
    var autoReconnect by remember { mutableStateOf(true) }
    var maxReconnectAttempts by remember { mutableStateOf("5") }
    var reconnectBaseMs by remember { mutableStateOf("3000") }
    var requestTimeoutMs by remember { mutableStateOf("30000") }
    var toolsReadyTimeoutMs by remember { mutableStateOf("5000") }
    var feishuAppId by remember { mutableStateOf("") }
    var feishuAppSecret by remember { mutableStateOf("") }
    var selectedTemplateId by remember { mutableStateOf(McpServerTemplates.CUSTOM_ID) }
    var templateMenuExpanded by remember { mutableStateOf(false) }
    val selectedTemplate = remember(selectedTemplateId) {
        McpServerTemplates.find(selectedTemplateId)
    }
    val feishuAutoRefresh = selectedTemplate.id == "feishu_remote"
    // cadena de descripción de estado(Debe extraerse en el contexto @Composable,no puedo estar ahí semantics{} lambda llamar dentro stringResource)
    val stateEnabledText = stringResource(R.string.settings_state_enabled)
    val stateDisabledText = stringResource(R.string.settings_state_disabled)

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_mcp_add_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v1.74: cambiar ?.let evitar !!(Las propiedades delegadas no pueden smart-cast)
                addError?.let { err ->
                    InlineError(message = err, onDismiss = { addError = null })
                }
                Text(
                    text = "Plantilla de configuración",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    MuseCapsuleButton(
                        text = selectedTemplate.displayName,
                        onClick = { templateMenuExpanded = true },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    MuseAnchoredMenu(
                        expanded = templateMenuExpanded,
                        onDismissRequest = { templateMenuExpanded = false },
                    ) {
                        (listOf(McpServerTemplates.custom) + McpServerTemplates.all).forEach { template ->
                            MuseListItem(
                                onClick = {
                                    selectedTemplateId = template.id
                                    name = if (template.id == McpServerTemplates.CUSTOM_ID) {
                                        ""
                                    } else {
                                        template.displayName
                                    }
                                    url = ""
                                    command = if (template.transportType == McpTransportType.STDIO) {
                                        template.commandPlaceholder
                                    } else {
                                        ""
                                    }
                                    authToken = ""
                                    headersText = template.defaultHeaders.entries.joinToString("\n") {
                                        "${it.key}: ${it.value}"
                                    }
                                    feishuAppId = ""
                                    feishuAppSecret = ""
                                    transportType = template.transportType
                                    addError = null
                                    templateMenuExpanded = false
                                },
                                headlineContent = {
                                    Column {
                                        Text(template.displayName)
                                        Text(
                                            text = template.summary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                Text(
                    text = selectedTemplate.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                SettingField(
                    label = stringResource(R.string.settings_mcp_display_name),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.settings_mcp_display_name_placeholder),
                )
                if (transportType == McpTransportType.STDIO) {
                    SettingField(
                        label = stringResource(R.string.settings_mcp_stdio_command),
                        value = command,
                        onValueChange = { command = it },
                        placeholder = stringResource(R.string.settings_mcp_stdio_command_placeholder),
                    )
                    Text(
                        text = stringResource(R.string.settings_mcp_stdio_command_help),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SettingField(
                        label = "URL",
                        value = url,
                        onValueChange = { url = it },
                        placeholder = selectedTemplate.urlPlaceholder,
                    )
                }
                Text(stringResource(R.string.settings_mcp_transport_type), style = MaterialTheme.typography.labelMedium)
                // Transport labels must keep their intrinsic width. A fixed Row lets
                // MuseCapsuleButton measure StreamableHTTP/STDIO too narrowly, so the
                // button's single-line ellipsis hides the actual transport option.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MuseCapsuleButton(
                        text = "SSE",
                        onClick = { transportType = McpTransportType.SSE },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    MuseCapsuleButton(
                        text = "StreamableHTTP",
                        onClick = { transportType = McpTransportType.STREAMABLE_HTTP },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    MuseCapsuleButton(
                        text = "STDIO",
                        onClick = { transportType = McpTransportType.STDIO },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
                // Phase 11.1.1: stdio La transferencia no está disponible en la zona de pruebas de Android(SELinux Límite exec),
                // Solicitar claramente a los usuarios que solo admitan dos transmisiones de red SSE / StreamableHTTP
                Text(
                    text = stringResource(R.string.settings_mcp_stdio_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (feishuAutoRefresh) {
                    SettingField(
                        label = "Feishu App ID",
                        value = feishuAppId,
                        onValueChange = { feishuAppId = it },
                        placeholder = "cli_xxxxx",
                    )
                    SettingField(
                        label = "Feishu App Secret",
                        value = feishuAppSecret,
                        onValueChange = { feishuAppSecret = it },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        text = "Muse El Tenent_access_token se obtiene y renueva automáticamente, lo que elimina la necesidad de reemplazar manualmente el TAT cada 2 horas.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    SettingField(
                        label = stringResource(R.string.settings_mcp_bearer_token),
                        value = authToken,
                        onValueChange = { authToken = it },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }

                // Área de plegado avanzada(headers / Reconectar parámetros/tiempo de espera)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // LAYOUT-01: El encabezado plegable "avanzado" toma el ancho restante + límite de línea,Evite que el ícono de galón a la derecha lo apriete.
                    Text(
                        text = stringResource(R.string.settings_common_advanced),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = if (showAdvanced) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = if (showAdvanced) {
                            stringResource(
                                R.string.settings_common_collapse,
                            )
                        } else {
                            stringResource(R.string.settings_common_expand)
                        },
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(20.dp),
                    )
                }
                if (showAdvanced) {
                    MuseTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_mcp_custom_headers)) },
                        placeholder = { Text("Authorization: Bearer xxx\nX-API-Key: yyy") },
                        singleLine = false,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MuseSwitch(
                            checked = autoReconnect,
                            onCheckedChange = { autoReconnect = it },
                            modifier = Modifier.semantics {
                                stateDescription = if (autoReconnect) stateEnabledText else stateDisabledText
                            },
                        )
                        Text(
                            text = stringResource(R.string.settings_mcp_auto_reconnect),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    SettingField(
                        label = stringResource(R.string.settings_mcp_max_reconnect),
                        value = maxReconnectAttempts,
                        onValueChange = { maxReconnectAttempts = it.filter { c -> c.isDigit() } },
                        placeholder = "5",
                    )
                    SettingField(
                        label = stringResource(R.string.settings_mcp_reconnect_base),
                        value = reconnectBaseMs,
                        onValueChange = { reconnectBaseMs = it.filter { c -> c.isDigit() } },
                        placeholder = "3000",
                    )
                    SettingField(
                        label = stringResource(R.string.settings_mcp_request_timeout),
                        value = requestTimeoutMs,
                        onValueChange = { requestTimeoutMs = it.filter { c -> c.isDigit() } },
                        placeholder = "30000",
                    )
                    SettingField(
                        label = stringResource(R.string.settings_mcp_tools_ready_timeout),
                        value = toolsReadyTimeoutMs,
                        onValueChange = { toolsReadyTimeoutMs = it.filter { c -> c.isDigit() } },
                        placeholder = "5000",
                    )
                }
            }
        },
        confirmText = stringResource(R.string.settings_common_add),
        onConfirm = {
            if (transportType == McpTransportType.STDIO && command.isBlank()) {
                addError = context.getString(R.string.settings_mcp_stdio_command_required)
            } else if (transportType != McpTransportType.STDIO && url.isBlank()) {
                addError = context.getString(R.string.settings_mcp_failed, "URL no puede estar vacío")
            } else if (feishuAutoRefresh && (feishuAppId.isBlank() || feishuAppSecret.isBlank())) {
                addError = context.getString(R.string.settings_mcp_failed, "La plantilla Feishu requiere completar el ID de la aplicación y App Secret")
            } else {
                val config = McpServerConfig(
                    id = "mcp-" + System.currentTimeMillis(),
                    name = name.ifBlank { selectedTemplate.displayName },
                    transportType = transportType,
                    url = url.trim(),
                    command = command.trim(),
                    authToken = authToken.trim(),
                    headers = parseHeaders(headersText),
                    autoReconnect = autoReconnect,
                    maxReconnectAttempts = maxReconnectAttempts.toIntOrNull()?.takeIf { it > 0 }
                        ?: 5,
                    reconnectBaseMs = reconnectBaseMs.toLongOrNull()?.takeIf { it > 0 }
                        ?: 3000L,
                    requestTimeoutMs = requestTimeoutMs.toLongOrNull()?.takeIf { it > 0 }
                        ?: 30_000L,
                    toolsReadyTimeoutMs = toolsReadyTimeoutMs.toLongOrNull()?.takeIf { it > 0 }
                        ?: 5_000L,
                    feishuAuth = McpFeishuAuthConfig(
                        enabled = feishuAutoRefresh,
                        appId = feishuAppId.trim(),
                        appSecret = feishuAppSecret.trim(),
                    ),
                )
                scope.launch {
                    runCatching { onAdd(config) }
                        .onSuccess { onDismiss() }
                        .onFailure { addError = context.getString(R.string.settings_mcp_failed, it.message) }
                }
            }
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * Explorar el servidor especificado Resources(Phase 11.1.2 listAllResources / readResource Acceso).
 *
 * - estado de la lista:Column + forEach mostrar uri / name / description
 * - Estado de los detalles:Leer contenido después de hacer clic en un elemento,Superponer un MuseDialog para mostrar texto/blob
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResourcesBrowserDialog(serverId: String, mcpRegistry: McpRegistry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var resources by remember { mutableStateOf<List<McpResource>>(emptyList()) }
    var viewingContent by remember { mutableStateOf<List<McpResourceContent>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        loading = true
        loadError = null
        resultOf {
            val all = mcpRegistry.listAllResources()
            all[serverId] ?: emptyList()
        }.onSuccess { resources = it }
            .onError { _, t ->
                loadError = context.getString(R.string.settings_mcp_load_resources_failed, t?.message)
            }
        loading = false
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_mcp_browse_resources_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v1.74: cambiar ?.let evitar !!(Las propiedades delegadas no pueden smart-cast)
                loadError?.let { err ->
                    InlineError(message = err, onDismiss = { loadError = null })
                }
                readError?.let { err ->
                    InlineError(message = err, onDismiss = { readError = null })
                }
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MuseSpinner(
                            size = 20.dp,
                        )
                        Text(stringResource(R.string.settings_mcp_loading), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (resources.isEmpty()) {
                    // v1.48: h14 Resources El estado vacío cambió a centrado con un icono Row(Pequeño espacio,No se necesitan componentes grandes de MuseEmptyState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MuseIcons.box,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(R.string.settings_mcp_no_resources),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // v1.0.7 Reparación:MuseDialog content Ya traído verticalScroll,LazyColumns no se puede anidar.
                    // Usar en su lugar Column + forEach,Desplazamiento unificado por la capa exterior de MuseDialog(Alineación VoiceCloningPage v1.132).
                    Column {
                        resources.forEach { res ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            readError = null
                                            val contents = runCatching {
                                                mcpRegistry.readResource(serverId, res.uri)
                                            }.onFailure {
                                                readError = context.getString(R.string.settings_mcp_read_failed, it.message)
                                            }.getOrDefault(emptyList())
                                            viewingContent = contents
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = res.name ?: res.uri,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!res.description.isNullOrBlank()) {
                                    Text(
                                        text = res.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                Text(
                                    text = res.uri,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_common_close),
        onConfirm = onDismiss,
    )

    // Detalles del contenido del recurso(Superpuesto en la parte superior del cuadro de diálogo de la lista)
    viewingContent?.let { contents ->
        MuseDialog(
            onDismissRequest = { viewingContent = null },
            title = stringResource(R.string.settings_mcp_resource_content),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (contents.isEmpty()) {
                        Text(stringResource(R.string.settings_mcp_empty_content), style = MaterialTheme.typography.bodySmall)
                    } else {
                        contents.forEach { c ->
                            Text(
                                text = c.text ?: c.blob ?: stringResource(R.string.settings_mcp_no_text),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.settings_common_close),
            onConfirm = { viewingContent = null },
        )
    }
}

/**
 * Explorar el servidor especificado Prompts(Phase 11.1.2 listAllPrompts / getPrompt Acceso).
 *
 * tres estados:
 *  - lista:Nombre para mostrar/descripción/nombre de parámetro
 *  - Entrada de parámetros:Después de seleccionar un mensaje,Representar dinámicamente un cuadro de entrada con sus argumentos
 *  - Visualización de resultados:getPrompt regresó messages(role + content)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PromptsBrowserDialog(serverId: String, mcpRegistry: McpRegistry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var prompts by remember { mutableStateOf<List<McpPrompt>>(emptyList()) }
    var selectedPrompt by remember { mutableStateOf<McpPrompt?>(null) }
    var promptResult by remember { mutableStateOf<McpPromptResult?>(null) }
    var loadingResult by remember { mutableStateOf(false) }
    val argValues = remember(selectedPrompt) { mutableStateMapOf<String, String>() }
    var loadError by remember { mutableStateOf<String?>(null) }
    var promptError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(serverId) {
        loading = true
        loadError = null
        resultOf {
            val all = mcpRegistry.listAllPrompts()
            all[serverId] ?: emptyList()
        }.onSuccess { prompts = it }
            .onError { _, t ->
                loadError = context.getString(R.string.settings_mcp_load_prompts_failed, t?.message)
            }
        loading = false
    }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_mcp_browse_prompts_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // v1.74: cambiar ?.let evitar !!(Las propiedades delegadas no pueden smart-cast)
                loadError?.let { err ->
                    InlineError(message = err, onDismiss = { loadError = null })
                }
                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MuseSpinner(
                            size = 20.dp,
                        )
                        Text(stringResource(R.string.settings_mcp_loading), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (prompts.isEmpty()) {
                    // v1.48: h14 Prompts El estado vacío cambió a centrado con un icono Row(Pequeño espacio,No se necesitan componentes grandes de MuseEmptyState)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MuseIcons.note,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(R.string.settings_mcp_no_prompts),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // v1.0.7 Reparación:MuseDialog content Ya traído verticalScroll,LazyColumns no se puede anidar.
                    // Usar en su lugar Column + forEach,Desplazamiento unificado por la capa exterior de MuseDialog.
                    Column {
                        prompts.forEach { p ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        argValues.clear()
                                        selectedPrompt = p
                                        promptResult = null
                                        promptError = null
                                    }
                                    .padding(vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (!p.description.isNullOrBlank()) {
                                    Text(
                                        text = p.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                                if (p.arguments.isNotEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_mcp_prompt_args, p.arguments.joinToString { it.name }),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmText = stringResource(R.string.settings_common_close),
        onConfirm = onDismiss,
    )

    // Entrada de parámetros dialog(Aparece después de seleccionar el mensaje)
    selectedPrompt?.let { prompt ->
        MuseDialog(
            onDismissRequest = { selectedPrompt = null },
            title = stringResource(R.string.settings_mcp_prompt_title, prompt.name),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // v1.74: cambiar ?.let evitar !!(Las propiedades delegadas no pueden smart-cast)
                    promptError?.let { err ->
                        InlineError(message = err, onDismiss = { promptError = null })
                    }
                    if (loadingResult) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MuseSpinner(
                                size = 16.dp,
                            )
                            Text(stringResource(R.string.settings_mcp_getting), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (prompt.arguments.isEmpty()) {
                        Text(stringResource(R.string.settings_mcp_prompt_no_args), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            prompt.arguments.forEach { arg ->
                                MuseTextField(
                                    value = argValues[arg.name] ?: "",
                                    onValueChange = { argValues[arg.name] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            buildString {
                                                append(arg.name)
                                                if (arg.required) append(" *")
                                            },
                                        )
                                    },
                                    placeholder = arg.description?.let { { Text(it) } },
                                    singleLine = true,
                                )
                            }
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.settings_mcp_get),
            onConfirm = {
                if (!loadingResult) {
                    loadingResult = true
                    promptError = null
                    scope.launch {
                        val result = resultOf {
                            mcpRegistry.getPrompt(serverId, prompt.name, argValues.toMap())
                        }.onError { _, t ->
                            promptError = context.getString(R.string.settings_mcp_get_failed, t?.message)
                        }.getOrNull()
                        loadingResult = false
                        if (result != null) {
                            promptResult = result
                            selectedPrompt = null
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { selectedPrompt = null },
        )
    }

    // Visualización de resultados dialog
    promptResult?.let { result ->
        MuseDialog(
            onDismissRequest = { promptResult = null },
            title = stringResource(R.string.settings_mcp_prompt_result),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!result.description.isNullOrBlank()) {
                        Text(
                            text = result.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (result.messages.isEmpty()) {
                        Text(stringResource(R.string.settings_mcp_no_messages), style = MaterialTheme.typography.bodySmall)
                    } else {
                        result.messages.forEach { msg ->
                            Text(
                                text = "[${msg.role}]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = msg.content.toDisplayText(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.settings_common_close),
            onConfirm = { promptResult = null },
        )
    }
}

/**
 * Dialog Barra de error del área de contenido — sustituto MuseToast,Evite que Toast sea bloqueado por la ventana de diálogo.
 */
@Composable
private fun InlineError(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MuseIcons.info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = MuseIcons.x,
                contentDescription = stringResource(R.string.settings_common_close),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onDismiss() },
            )
        }
    }
}

/**
 * poner"por línea key:value"El texto se analiza en un mapa de encabezados.
 * Las líneas vacías o sin dos puntos se ignoran,El texto vacío devuelve un mapa vacío.
 */
private fun parseHeaders(text: String): Map<String, String> {
    return text.lines()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val idx = trimmed.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
        }
        .toMap()
}

/**
 * Poner el mensaje de aviso de MCP content(JsonElement)Convertir a texto visualizable.
 * en forma de {type:"text", text:"..."} Extracto text;De lo contrario toString().
 */
private fun JsonElement.toDisplayText(): String {
    val obj = this as? JsonObject ?: return toString()
    val type = (obj["type"] as? JsonPrimitive)?.content
    val text = (obj["text"] as? JsonPrimitive)?.content
    return if (type == "text" && text != null) text else toString()
}
