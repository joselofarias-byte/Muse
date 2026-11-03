@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError", "UnusedPrivateProperty")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.museBottomBarInsets
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.huge
import io.zer0.muse.ui.theme.pill

@Composable
internal fun ProviderEditBottomBar(
    canFetch: Boolean,
    isFetching: Boolean,
    canDelete: Boolean,
    onFetch: (Boolean) -> Unit,
    onAddModel: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .museBottomBarInsets(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Obtener(operación principal:Botón Cápsula Sólido Negro);Translúcido atenuado cuando no está disponible
            val fetchContentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(
                alpha = if (canFetch) 1f else 0.5f,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(MuseIconSizes.touchTarget) // Botones de la barra de acción inferior objetivo táctil unificado de 48 dp
                    .background(
                        color = if (canFetch) {
                            MaterialTheme.colorScheme.inverseSurface
                        } else {
                            MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.12f)
                        },
                        shape = MuseShapes.huge,
                    )
                    .clickable(enabled = canFetch) { onFetch(true) },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFetching) {
                        MuseSpinner(
                            size = 16.dp,
                            color = fetchContentColor,
                        )
                    } else {
                        Icon(
                            imageVector = MuseIcons.refresh,
                            contentDescription = null,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                            tint = fetchContentColor,
                        )
                    }
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.settings_provider_fetch),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = fetchContentColor,
                    )
                }
            }

            // Agregar nuevo modelo(operaciones:Botón de cápsula de trazo)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(MuseIconSizes.touchTarget) // Botones de la barra de acción inferior objetivo táctil unificado de 48 dp
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MuseShapes.huge,
                    )
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MuseShapes.huge,
                    )
                    .clickable(onClick = onAddModel),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.plus,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.settings_provider_add_new_model),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Eliminar(botón icono,Solo se muestra cuando la eliminación está disponible)
            if (canDelete) {
                Box(
                    modifier = Modifier
                        .size(MuseIconSizes.touchTarget) // Botones de la barra de acción inferior objetivo táctil unificado de 48 dp
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = MuseShapes.huge,
                        )
                        .clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.trash,
                        contentDescription = stringResource(R.string.settings_common_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
        }
    }
}

@Composable
internal fun AddModelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var modelId by remember { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_provider_add_new_model),
        content = {
            MuseTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_provider_model_id)) },
                singleLine = true,
            )
        },
        confirmText = stringResource(R.string.settings_common_add),
        onConfirm = {
            val id = modelId.trim()
            if (id.isNotBlank()) onConfirm(id)
            onDismiss()
        },
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigTab(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    type: ProviderType,
    onTypeChange: (ProviderType) -> Unit,
    isCustomSpecific: Boolean,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onApiKeyVisibleChange: (Boolean) -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    balanceApiPath: String,
    onBalanceApiPathChange: (String) -> Unit,
    balanceResultPath: String,
    onBalanceResultPathChange: (String) -> Unit,
    showAdvanced: Boolean,
    onShowAdvancedChange: (Boolean) -> Unit,
    useVertexAi: Boolean,
    onUseVertexAiChange: (Boolean) -> Unit,
    useServiceAccount: Boolean,
    onUseServiceAccountChange: (Boolean) -> Unit,
    serviceAccountEmail: String,
    onServiceAccountEmailChange: (String) -> Unit,
    privateKey: String,
    onPrivateKeyChange: (String) -> Unit,
    privateKeyVisible: Boolean,
    onPrivateKeyVisibleChange: (Boolean) -> Unit,
    vertexLocation: String,
    onVertexLocationChange: (String) -> Unit,
    vertexProjectId: String,
    onVertexProjectIdChange: (String) -> Unit,
    openAIChatCompletionsPath: String,
    onOpenAIChatCompletionsPathChange: (String) -> Unit,
    openAIUseResponseApi: Boolean,
    onOpenAIUseResponseApiChange: (Boolean) -> Unit,
    openAIIncludeHistoryReasoning: Boolean,
    onOpenAIIncludeHistoryReasoningChange: (Boolean) -> Unit,
    openAIEmbeddingsPath: String,
    onOpenAIEmbeddingsPathChange: (String) -> Unit,
    openAIImagesPath: String,
    onOpenAIImagesPathChange: (String) -> Unit,
    openAIStripModelPrefix: String,
    onOpenAIStripModelPrefixChange: (String) -> Unit,
    // v2.6.2: OpenAI Encabezados de solicitud personalizados de proveedores de tipos/Cuerpo de la solicitud (escenario de encabezado obligatorio de la estación de transferencia).
    openAICustomHeadersText: String,
    onOpenAICustomHeadersTextChange: (String) -> Unit,
    openAICustomBodyText: String,
    onOpenAICustomBodyTextChange: (String) -> Unit,
    anthropicPromptCaching: Boolean,
    onAnthropicPromptCachingChange: (Boolean) -> Unit,
    anthropicPromptCacheTtl: String,
    onAnthropicPromptCacheTtlChange: (String) -> Unit,
    anthropicMessagesPath: String,
    onAnthropicMessagesPathChange: (String) -> Unit,
    anthropicModelsPath: String,
    onAnthropicModelsPathChange: (String) -> Unit,
    customChatCompletionsPath: String,
    onCustomChatCompletionsPathChange: (String) -> Unit,
    customHeadersText: String,
    onCustomHeadersTextChange: (String) -> Unit,
    customBodyText: String,
    onCustomBodyTextChange: (String) -> Unit,
    fetchError: String?,
    onFetchErrorDismiss: () -> Unit,
    onFetchRetry: () -> Unit = {},
    // v2.4: Pruebe de forma independiente los parámetros relacionados con la conexión(Solo prueba sin cambiar,no escribas modelsState)
    isTestingConnection: Boolean,
    testConnectionResult: String?,
    testConnectionError: String?,
    canTestConnection: Boolean,
    onTestConnection: () -> Unit,
    onTestResultDismiss: () -> Unit,
    isQueryingBalance: Boolean,
    balanceResult: String?,
    onQueryBalance: () -> Unit,
    onImportServiceAccountJson: () -> Unit,
    // P1-6: OAuth Iniciar sesión(solo si oauthConfig != null visualización de la hora)
    oauthConfig: OAuthConfig? = null,
    onOAuthLogin: () -> Unit = {},
    isOAuthLoading: Boolean = false,
    // P2-11: Estado del token OAuth almacenado + revocar devolución de llamada
    hasStoredOAuthToken: Boolean = false,
    onRevokeOAuth: () -> Unit = {},
    // P1-3: Parámetros limitantes actuales(RPM + Simultaneidad máxima,Una cadena vacía significa que no hay límite)
    requestLimitPerMinuteText: String = "",
    onRequestLimitPerMinuteTextChange: (String) -> Unit = {},
    maxConcurrentRequestsText: String = "",
    onMaxConcurrentRequestsTextChange: (String) -> Unit = {},
) {
    // Cadena de coincidencia de prefijo(Usado para iniciosCon/contiene juicio)
    val connectionSuccessPrefix = stringResource(R.string.settings_provider_prefix_connection_success)
    val cannotConnectPrefix = stringResource(R.string.settings_provider_prefix_cannot_connect)
    val fetchModelsFailedPrefix = stringResource(R.string.settings_provider_prefix_fetch_models_failed)
    val queryFailedPrefix = stringResource(R.string.settings_provider_prefix_query_failed)
    val configureFirstPrefix = stringResource(R.string.settings_provider_prefix_configure)
    val pathNotFoundPrefix = stringResource(R.string.settings_provider_prefix_path_not_found)
    val unableToResolvePrefix = stringResource(R.string.settings_provider_prefix_unable_to_resolve)
    val queryTimeoutPrefix = stringResource(R.string.settings_provider_prefix_query_timeout)
    // cadena de descripción de estado(Debe extraerse en el contexto @Composable,no puedo estar ahí semantics{} lambda llamar dentro stringResource)
    val stateEnabledText = stringResource(R.string.settings_state_enabled)
    val stateDisabledText = stringResource(R.string.settings_state_disabled)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = MusePaddings.sectionGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
    ) {
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    // Selección de tipo
                    Text(stringResource(R.string.settings_provider_type), style = MaterialTheme.typography.labelMedium)
                    // La cantidad de tipos se expande con el acuerdo del proveedor y el desplazamiento horizontal evita que el último elemento quede recortado por el ancho de la tarjeta.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        ProviderType.entries.forEach { t ->
                            // v1.134 P0-8: FilterChip → MuseChip cápsula
                            MuseChip(
                                selected = type == t,
                                onClick = { onTypeChange(t) },
                                label = providerDisplayTypeName(t),
                            )
                        }
                    }
                    SettingField(
                        label = stringResource(R.string.settings_provider_display_name),
                        value = displayName,
                        onValueChange = onDisplayNameChange,
                    )
                    SettingField(
                        label = stringResource(R.string.settings_provider_base_url),
                        value = baseUrl,
                        onValueChange = onBaseUrlChange,
                        placeholder = when (type) {
                            ProviderType.OPENAI -> ProviderConfig.DEFAULT_OPENAI_BASE_URL
                            ProviderType.ANTHROPIC -> ProviderConfig.DEFAULT_ANTHROPIC_BASE_URL
                            ProviderType.GEMINI -> ProviderConfig.DEFAULT_GEMINI_BASE_URL
                            // v1.0.6: OPENAI_RESPONSES Reutilice la misma configuración de interfaz de usuario de OpenAI(/v1/responses El cambio está controlado por el interruptor useResponseApi)
                            ProviderType.OPENAI_RESPONSES -> ProviderConfig.DEFAULT_OPENAI_RESPONSES_BASE_URL
                        },
                    )
                    // F-11: Entrada de grupo de claves múltiples(por fila/separados por comas,Admite rotación 429/401 de múltiples teclas)
                    ApiKeyPoolField(
                        apiKey = apiKey,
                        onApiKeyChange = onApiKeyChange,
                        apiKeyVisible = apiKeyVisible,
                        onApiKeyVisibleChange = onApiKeyVisibleChange,
                    )

                    // P1-6: OAuth Botón de inicio de sesión + línea de estado(solo si oauthConfig != null visualización de la hora)
                    // - Envolver con Surface + MuseShapes.pill,no Material3 Button
                    // - Cargando(AWAITING_USER / POLLING):Botón desactivado + progreso circular + "Espere a que se complete la autorización..."
                    // - Haga clic:llamar onOAuthLogin,Iniciado por ProviderEditPage Device Flow / Auth Code Flow
                    // P2-11: Cuando se ha almacenado el token, se muestra un botón adicional "Revocar acceso".(Error rojoCoincidencia de color del contenedor,
                    //   Mismo estilo que el botón de inicio de sesión pero opuesto en semántica.)
                    if (oauthConfig != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = MusePaddings.contentGap),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                        ) {
                            // OAuth Botón de inicio de sesión(no Material3 Button,Envuelto con Surface + en el que se puede hacer clic)
                            val oauthBtnBg = if (isOAuthLoading) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                            val oauthBtnContentColor = if (isOAuthLoading) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            }
                            Surface(
                                shape = MuseShapes.pill,
                                color = oauthBtnBg,
                                modifier = Modifier.clickable(enabled = !isOAuthLoading) { onOAuthLogin() },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (isOAuthLoading) {
                                        MuseSpinner(
                                            size = MuseIconSizes.iconTiny,
                                            color = oauthBtnContentColor,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = MuseIcons.lock,
                                            contentDescription = null,
                                            tint = oauthBtnContentColor,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.oauth_login),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = oauthBtnContentColor,
                                    )
                                }
                            }
                            // P2-11: Revocar el acceso al botón de la cápsula(Solo se muestra si ya hay un token almacenado)
                            // - usar Surface + MuseShapes.pill,Mismo estilo que el botón de inicio de sesión.
                            // - errorContainer La combinación de colores sugiere "operaciones destructivas".
                            // - Ya en el proceso de inicio de sesión(isOAuthLoading)Desactivar,Evitar conflictos estatales
                            if (hasStoredOAuthToken) {
                                val revokeBtnBg = if (isOAuthLoading) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                }
                                val revokeBtnContentColor = if (isOAuthLoading) {
                                    MaterialTheme.colorScheme.outline
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                                Surface(
                                    shape = MuseShapes.pill,
                                    color = revokeBtnBg,
                                    modifier = Modifier.clickable(enabled = !isOAuthLoading) { onRevokeOAuth() },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Icon(
                                            imageVector = MuseIcons.x,
                                            contentDescription = null,
                                            tint = revokeBtnContentColor,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                        Text(
                                            text = stringResource(R.string.oauth_revoke),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                            color = revokeBtnContentColor,
                                        )
                                    }
                                }
                            }
                            // Mensaje de estado de carga
                            if (isOAuthLoading) {
                                Text(
                                    text = stringResource(R.string.oauth_polling),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }

                    // v2.4: Botón de conexión de prueba independiente + cápsula de resultados
                    // - Bajo prueba: botón interior CircularProgressIndicator
                    // - éxito: verde ✓ cápsula + "La conexión es normal. · N modelos"
                    // - falló: rojo ✗ Cápsulas + Mensaje de error de clasificación(API Key No válida/URL no compatible/Se agotó el tiempo de espera de la conexión/No se puede conectar al servidor)
                    // - Forma de cápsula con MuseShapes.pill
                    // - Utilice MuseTactileButton en lugar del botón de cerrar Material3 IconButton
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MusePaddings.contentGap),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                    ) {
                        // Botón de prueba de conexión(forma de cápsula,no Material3 IconButton)
                        val testBtnBg = if (canTestConnection) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        }
                        val testBtnContentColor = if (canTestConnection) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                        Box(
                            modifier = Modifier
                                .background(color = testBtnBg, shape = MuseShapes.pill)
                                .clickable(enabled = canTestConnection) { onTestConnection() }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isTestingConnection) {
                                        MuseSpinner(
                                            size = MuseIconSizes.iconTiny,
                                            color = testBtnContentColor,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = MuseIcons.globe,
                                            contentDescription = null,
                                            tint = testBtnContentColor,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                    }
                                    Spacer(Modifier.size(6.dp))
                                    // F-12: Pruebas de punto final(Sólo ajustar /models)Prueba con modelo(enlace completo)distinción semántica
                                    Text(
                                        text = stringResource(R.string.settings_provider_test_connection_endpoint),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                        color = testBtnContentColor,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.settings_provider_test_connection_endpoint_sub),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = testBtnContentColor.copy(alpha = 0.8f),
                                )
                            }
                        }

                        // Cápsula de resultados(éxito/estado fallido,MuseShapes.pill forma)
                        if (testConnectionResult != null || testConnectionError != null) {
                            val isSuccess = testConnectionResult != null
                            val message = testConnectionResult ?: testConnectionError ?: ""
                            val capsuleColor = if (isSuccess) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                            val capsuleBg = if (isSuccess) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            }
                            Surface(
                                shape = MuseShapes.pill,
                                color = capsuleBg,
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isSuccess) MuseIcons.check else MuseIcons.x,
                                        contentDescription = null,
                                        tint = capsuleColor,
                                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                                    )
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = capsuleColor,
                                        modifier = Modifier.weight(1f),
                                    )
                                    // botón cerrar:Utilice MuseTactileButton en su lugar Material3 IconButton
                                    MuseTactileButton(
                                        icon = MuseIcons.x,
                                        onClick = onTestResultDismiss,
                                        contentDescription = stringResource(R.string.settings_common_close),
                                        size = 20.dp,
                                        iconSize = MuseIconSizes.iconTiny,
                                        tint = capsuleColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Extraer mensaje de error
        if (fetchError != null) {
            item {
                MuseErrorStateBox(
                    message = fetchError,
                    onRetry = onFetchRetry,
                    onDismiss = onFetchErrorDismiss,
                )
            }
        }

        // Solicitud de adición manual después de que falla la adquisición automática
        if (fetchError != null &&
            (
                fetchError.contains(cannotConnectPrefix) ||
                    fetchError.contains("404") ||
                    fetchError.contains(fetchModelsFailedPrefix)
                )
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_provider_manual_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            }
        }

        // Vertex AI Configuración(solo GEMINI)
        if (type == ProviderType.GEMINI) {
            item {
                SettingsGroup {
                    Column(
                        modifier = Modifier.padding(MusePaddings.cardInner),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        Text(stringResource(R.string.settings_provider_vertex_ai), style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MuseSwitch(
                                checked = useVertexAi,
                                onCheckedChange = onUseVertexAiChange,
                                modifier = Modifier.semantics {
                                    stateDescription = if (useVertexAi) stateEnabledText else stateDisabledText
                                },
                            )
                            Text(
                                text = stringResource(R.string.settings_provider_enable_vertex),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = MusePaddings.iconPadding),
                            )
                        }
                        if (useVertexAi) {
                            SettingField(
                                label = stringResource(R.string.settings_provider_location),
                                value = vertexLocation,
                                onValueChange = onVertexLocationChange,
                                placeholder = "us-central1",
                            )
                            SettingField(
                                label = stringResource(R.string.settings_provider_project_id),
                                value = vertexProjectId,
                                onValueChange = onVertexProjectIdChange,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MuseSwitch(
                                    checked = useServiceAccount,
                                    onCheckedChange = onUseServiceAccountChange,
                                    modifier = Modifier.semantics {
                                        stateDescription = if (useServiceAccount) stateEnabledText else stateDisabledText
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.settings_provider_use_service_account),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                )
                            }
                            if (useServiceAccount) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        MuseIcons.upload,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                    Spacer(Modifier.size(MusePaddings.contentGap))
                                    MuseCapsuleButton(
                                        text = stringResource(R.string.settings_provider_import_from_json),
                                        onClick = onImportServiceAccountJson,
                                        variant = IosCapsuleButtonVariant.Text,
                                        fillWidth = false,
                                    )
                                }
                                SettingField(
                                    label = stringResource(R.string.settings_provider_service_account_email),
                                    value = serviceAccountEmail,
                                    onValueChange = onServiceAccountEmailChange,
                                )
                                MuseTextField(
                                    value = privateKey,
                                    onValueChange = onPrivateKeyChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.settings_provider_private_key)) },
                                    placeholder = { Text("-----BEGIN PRIVATE KEY-----\n...") },
                                    minLines = 3,
                                    maxLines = 8,
                                    visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        MuseTactileButton(
                                            icon = if (privateKeyVisible) MuseIcons.eyeOff else MuseIcons.eye,
                                            onClick = { onPrivateKeyVisibleChange(!privateKeyVisible) },
                                            contentDescription = if (privateKeyVisible) {
                                                stringResource(
                                                    R.string.settings_common_hide,
                                                )
                                            } else {
                                                stringResource(R.string.settings_common_show)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Campos avanzados(plegable)
        item {
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onShowAdvancedChange(!showAdvanced) }
                            .padding(vertical = MusePaddings.tightGap),
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
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                        )
                    }
                    if (showAdvanced) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MuseSwitch(
                                checked = enabled,
                                onCheckedChange = onEnabledChange,
                                modifier = Modifier.semantics {
                                    stateDescription = if (enabled) stateEnabledText else stateDisabledText
                                },
                            )
                            Text(
                                text = stringResource(R.string.settings_provider_enable_this),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = MusePaddings.iconPadding),
                            )
                        }
                        SettingField(
                            label = stringResource(R.string.settings_provider_balance_api_path),
                            value = balanceApiPath,
                            onValueChange = onBalanceApiPathChange,
                            placeholder = "/dashboard/billing/usage",
                        )
                        SettingField(
                            label = stringResource(R.string.settings_provider_balance_result_path),
                            value = balanceResultPath,
                            onValueChange = onBalanceResultPathChange,
                            placeholder = "\$.data.total_usage",
                        )
                        // P1-3: Parámetros limitantes actuales — RPM(Número máximo de solicitudes por minuto)+ Número máximo de solicitudes simultáneas
                        // vacío = Sin límite;> 0 Cuando ProviderRegistry.crea superposiciones RateLimitDecorator
                        SettingField(
                            label = stringResource(R.string.settings_provider_rate_limit_rpm),
                            value = requestLimitPerMinuteText,
                            onValueChange = onRequestLimitPerMinuteTextChange,
                            placeholder = stringResource(R.string.settings_provider_rate_limit_rpm_hint),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        SettingField(
                            label = stringResource(R.string.settings_provider_max_concurrent),
                            value = maxConcurrentRequestsText,
                            onValueChange = onMaxConcurrentRequestsTextChange,
                            placeholder = stringResource(R.string.settings_provider_max_concurrent_hint),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        // v1.134 P0-3: El botón de consulta de saldo utiliza Surface+cápsula en la que se puede hacer clic
                        val balanceEnabled = !isQueryingBalance && balanceApiPath.isNotBlank() && baseUrl.isNotBlank()
                        Surface(
                            shape = MuseShapes.pill,
                            color = if (balanceEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (balanceEnabled) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            onClick = if (balanceEnabled) onQueryBalance else ({}),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (isQueryingBalance) {
                                    MuseSpinner(
                                        size = 16.dp,
                                    )
                                } else {
                                    Icon(
                                        MuseIcons.wallet,
                                        contentDescription = null,
                                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                                    )
                                }
                                Text(stringResource(R.string.settings_provider_query_balance))
                            }
                        }
                        balanceResult?.let { result ->
                            val isError = result.startsWith(queryFailedPrefix) || result.startsWith(configureFirstPrefix) || result.startsWith(pathNotFoundPrefix) || result.startsWith(unableToResolvePrefix) || result.startsWith(queryTimeoutPrefix)
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = MusePaddings.contentGap),
                            )
                        }

                        // Provider Campos avanzados específicos
                        when (type) {
                            // v1.0.6: OPENAI_RESPONSES Reutilice la misma configuración de interfaz de usuario de OpenAI(/v1/responses El cambio está controlado por el interruptor useResponseApi)
                            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if (isCustomSpecific) {
                                Text(
                                    text = stringResource(R.string.settings_provider_custom_specific),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_chat_completions_path),
                                    value = customChatCompletionsPath,
                                    onValueChange = onCustomChatCompletionsPathChange,
                                    placeholder = "/chat/completions",
                                )
                                MuseTextField(
                                    value = customHeadersText,
                                    onValueChange = onCustomHeadersTextChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(R.string.settings_provider_custom_headers),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    placeholder = { Text("Authorization: Bearer xxx") },
                                    minLines = 3,
                                    maxLines = 8,
                                )
                                MuseTextField(
                                    value = customBodyText,
                                    onValueChange = onCustomBodyTextChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(R.string.settings_provider_custom_body),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    placeholder = { Text("{\"seed\": 42}") },
                                    minLines = 3,
                                    maxLines = 8,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.settings_provider_openai_specific),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_chat_completions_path),
                                    value = openAIChatCompletionsPath,
                                    onValueChange = onOpenAIChatCompletionsPathChange,
                                    placeholder = "/chat/completions",
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MuseSwitch(
                                        checked = openAIUseResponseApi,
                                        onCheckedChange = onOpenAIUseResponseApiChange,
                                        modifier = Modifier.semantics {
                                            stateDescription = if (openAIUseResponseApi) stateEnabledText else stateDisabledText
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_provider_use_responses_api),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MuseSwitch(
                                        checked = openAIIncludeHistoryReasoning,
                                        onCheckedChange = onOpenAIIncludeHistoryReasoningChange,
                                        modifier = Modifier.semantics {
                                            stateDescription = if (openAIIncludeHistoryReasoning) stateEnabledText else stateDisabledText
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_provider_include_history_reasoning),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                    )
                                }
                                SettingField(
                                    label = stringResource(R.string.settings_provider_embeddings_path),
                                    value = openAIEmbeddingsPath,
                                    onValueChange = onOpenAIEmbeddingsPathChange,
                                    placeholder = "/embeddings",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_images_path),
                                    value = openAIImagesPath,
                                    onValueChange = onOpenAIImagesPathChange,
                                    placeholder = "/images/generations",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_strip_model_prefix),
                                    value = openAIStripModelPrefix,
                                    onValueChange = onOpenAIStripModelPrefixChange,
                                    placeholder = stringResource(R.string.settings_provider_strip_model_prefix_placeholder),
                                )
                                // v2.6.2: OpenAI Los proveedores de tipos también abren encabezados de solicitud personalizados/Cuerpo de solicitud.
                                // Algunas estaciones de transferencia (como OpenCode Go) exigen encabezados de sesión y, si faltan, se emitirá un 400;
                                // La implementación anterior solo muestra esta entrada bajo el tipo Personalizado y el proveedor predeterminado no puede completarla.
                                MuseTextField(
                                    value = openAICustomHeadersText,
                                    onValueChange = onOpenAICustomHeadersTextChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(R.string.settings_provider_custom_headers),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    placeholder = { Text("x-opencode-session: my-session") },
                                    minLines = 3,
                                    maxLines = 8,
                                )
                                MuseTextField(
                                    value = openAICustomBodyText,
                                    onValueChange = onOpenAICustomBodyTextChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = {
                                        Text(
                                            stringResource(R.string.settings_provider_custom_body),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                    placeholder = { Text("{\"seed\": 42}") },
                                    minLines = 3,
                                    maxLines = 8,
                                )
                            }

                            ProviderType.ANTHROPIC -> {
                                Text(
                                    text = stringResource(R.string.settings_provider_anthropic_specific),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MuseSwitch(
                                        checked = anthropicPromptCaching,
                                        onCheckedChange = onAnthropicPromptCachingChange,
                                        modifier = Modifier.semantics {
                                            stateDescription = if (anthropicPromptCaching) stateEnabledText else stateDisabledText
                                        },
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_provider_enable_prompt_caching),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(start = MusePaddings.iconPadding),
                                    )
                                }
                                SettingField(
                                    label = stringResource(R.string.settings_provider_prompt_cache_ttl),
                                    value = anthropicPromptCacheTtl,
                                    onValueChange = onAnthropicPromptCacheTtlChange,
                                    placeholder = "5m",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_messages_path),
                                    value = anthropicMessagesPath,
                                    onValueChange = onAnthropicMessagesPathChange,
                                    placeholder = "/messages",
                                )
                                SettingField(
                                    label = stringResource(R.string.settings_provider_models_path),
                                    value = anthropicModelsPath,
                                    onValueChange = onAnthropicModelsPathChange,
                                    placeholder = "/models",
                                )
                            }

                            ProviderType.GEMINI -> { /* Vertex AI Ya configurado en tarjeta independiente */ }
                        }
                    }
                }
            }
        }
    }
}

/**
 * F-11: Componente de entrada de grupo de claves múltiples.
 *
 * Establezca la cadena de clave API del proveedor en(coma/nueva línea separada,con [io.zer0.ai.util.KeyRoulette] El formato de análisis es consistente.)
 * Dividir en varias líneas para mostrar,apoyo:
 *  - Edición directa superior de TextArea de varias líneas/Pegar varios key
 *  - línea de instrucción:Cuando hay una llave activa se muestra su número de cola de desensibilización.(Como por ejemplo sk-***aB3),De lo contrario mostrar"N configurado Key"
 *  - botón eliminar al final de cada línea(Eliminar elementos uno por uno)
 *  - "Borrar todo"botón
 *
 * La persistencia sigue siendo una cadena separada por comas.,Compatible con la lógica de rotación de KeyRoulette existente.
 *
 * @param apiKey cadena de clave original(Separados por coma o nueva línea)
 * @param onApiKeyChange Devuelve la cadena actualizada al guardar
 * @param apiKeyVisible Ya sea para mostrar en texto claro
 * @param onApiKeyVisibleChange texto plano/Devolución de llamada de cambio de máscara
 * @param activeKey El número de cola de desensibilización de la clave actualmente en uso;Cuando es nulo, se muestra el número de configuraciones.
 */
@Composable
internal fun ApiKeyPoolField(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onApiKeyVisibleChange: (Boolean) -> Unit,
    activeKey: String? = null,
) {
    // Dividido por la sintaxis de análisis de KeyRoulette(coma/nueva línea),Recuerde eliminar filas para una representación estable
    val keys = remember(apiKey) { parseApiKeys(apiKey) }

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        // Área de entrada multilínea:Admite pegar directamente varios key(Separados por coma o nueva línea)
        MuseTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_provider_api_key_pool)) },
            // Cuando está vacío, aparece un mensaje de formato: este cuadro tiene más de cien píxeles de alto. Cuando es gris puro, el usuario no sabe qué pegar.
            placeholder = {
                Text(
                    text = stringResource(R.string.settings_provider_api_key_pool_hint),
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            minLines = 3,
            maxLines = 8,
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                MuseTactileButton(
                    icon = if (apiKeyVisible) MuseIcons.eyeOff else MuseIcons.eye,
                    onClick = { onApiKeyVisibleChange(!apiKeyVisible) },
                    contentDescription = if (apiKeyVisible) {
                        stringResource(
                            R.string.settings_common_hide,
                        )
                    } else {
                        stringResource(R.string.settings_common_show)
                    },
                )
            },
        )

        // línea de instrucción:Número de cola de clave de actividad(cuando hay información)O número configurado + "Borrar todo"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val indicator = activeKey
                ?.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.settings_provider_active_key, it) }
                ?: stringResource(R.string.settings_provider_keys_count, keys.size)
            Text(
                text = indicator,
                style = MaterialTheme.typography.labelSmall,
                // La información de estado debe ser legible: anteriormente se utilizaba el contorno (color del borde) como color del texto, que era tan gris que era casi invisible.
                // "Borrar todo" en la misma fila es de color sólido puro, con la jerarquía completamente invertida.
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (keys.isNotEmpty()) {
                MuseCapsuleButton(
                    text = stringResource(R.string.settings_provider_clear_keys),
                    onClick = { onApiKeyChange("") },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
        }

        // Visualización de claves una por una + botón de eliminar para cada fila
        keys.forEachIndexed { index, key ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (apiKeyVisible) key else maskApiKey(key),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MuseTactileButton(
                    icon = MuseIcons.trash,
                    onClick = {
                        val remaining = keys.filterIndexed { i, _ -> i != index }
                        onApiKeyChange(remaining.joinToString(","))
                    },
                    contentDescription = stringResource(R.string.settings_provider_remove_key),
                    size = 32.dp,
                    iconSize = MuseIconSizes.iconSmall,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Dividir cadena de claves según la sintaxis de análisis de KeyRoulette(con [io.zer0.ai.util.KeyRoulette] interno
 * parseKeys ser consistente:con coma/nueva línea separada,Elimine los espacios en blanco iniciales y finales y filtre los elementos vacíos).
 */
private fun parseApiKeys(keysString: String): List<String> {
    return keysString.split(",", "\n", "\r\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

/**
 * Generar número de cola insensibilizado de clave,El formato es como "sk-***aB3"(Mantenga los primeros 3 bits + los últimos 3 bits).
 * Demasiado corto(<=6 poco)La clave de todas las mascarillas,Evite filtrar información.
 */
private fun maskApiKey(key: String): String {
    return if (key.length <= 6) {
        "*".repeat(key.length)
    } else {
        key.take(3) + "***" + key.takeLast(3)
    }
}
