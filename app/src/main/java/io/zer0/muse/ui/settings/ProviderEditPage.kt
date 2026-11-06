@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod", "TooManyFunctions", "ReturnCount", "TooGenericExceptionCaught", "SwallowedException", "MaxLineLength", "ComplexCondition", "UseCheckOrError")

package io.zer0.muse.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelListCache
import io.zer0.ai.core.ModelRegistry
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.findActivity
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.compose.koinInject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** ProviderType enumerado Saver(rememberSaveable No se puede guardar la enumeración directamente,Serializar con nombre/Deserialización). */
private val ProviderTypeSaver = Saver<ProviderType, String>(
    save = { it.name },
    restore = { runCatching { ProviderType.valueOf(it) }.getOrDefault(ProviderType.DEFAULT) },
)

@Composable
internal fun ProviderEditPage(
    config: ProviderConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
    isFromPreset: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var displayName by rememberSaveable { mutableStateOf(config.displayName) }
    var type by rememberSaveable(stateSaver = ProviderTypeSaver) { mutableStateOf(config.type) }
    var baseUrl by rememberSaveable { mutableStateOf(config.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(config.apiKey) }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    // modelsState para lista de modelos,Necesidad de personalizar Saver,y se puede reconstruir desde la configuración después de la rotación,mantener remember
    val modelsState = remember { mutableStateListOf<Model>().apply { addAll(config.models) } }
    var enabled by rememberSaveable { mutableStateOf(config.enabled) }
    var balanceApiPath by rememberSaveable { mutableStateOf(config.balanceApiPath) }
    var balanceResultPath by rememberSaveable { mutableStateOf(config.balanceResultPath) }
    var isQueryingBalance by rememberSaveable { mutableStateOf(false) }
    var balanceResult by rememberSaveable { mutableStateOf<String?>(null) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    // P1-3: Parámetros limitantes actuales(RPM + Simultaneidad máxima). Vincular cuadro de texto con estado de cadena,Cuando se guarda, se analiza como Int(vacío/ilegal → 0=Sin límite)
    var requestLimitPerMinuteText by rememberSaveable { mutableStateOf(config.requestLimitPerMinute.takeIf { it > 0 }?.toString() ?: "") }
    var maxConcurrentRequestsText by rememberSaveable { mutableStateOf(config.maxConcurrentRequests.takeIf { it > 0 }?.toString() ?: "") }

    // Vertex AI Configuración(Sólo se muestran los tipos GÉMINI)
    val geminiSpecific = (config.resolvedSpecific() as? io.zer0.ai.core.ProviderSpecificConfig.Gemini)
        ?: io.zer0.ai.core.ProviderSpecificConfig.Gemini()
    var useVertexAi by rememberSaveable { mutableStateOf(geminiSpecific.useVertexAI) }
    var useServiceAccount by rememberSaveable { mutableStateOf(geminiSpecific.useServiceAccount) }
    var serviceAccountEmail by rememberSaveable { mutableStateOf(geminiSpecific.serviceAccountEmail) }
    var privateKey by rememberSaveable { mutableStateOf(geminiSpecific.privateKey) }
    var privateKeyVisible by rememberSaveable { mutableStateOf(false) }
    var vertexLocation by rememberSaveable { mutableStateOf(geminiSpecific.location) }
    var vertexProjectId by rememberSaveable { mutableStateOf(geminiSpecific.projectId) }

    // OpenAI / Anthropic / Custom specific campo
    val resolvedSpecific = config.resolvedSpecific()
    var openAIChatCompletionsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.chatCompletionsPath
                ?: ProviderSpecificConfig.OpenAI().chatCompletionsPath,
        )
    }
    var openAIUseResponseApi by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.useResponseApi
                ?: ProviderSpecificConfig.OpenAI().useResponseApi,
        )
    }
    var openAIIncludeHistoryReasoning by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.includeHistoryReasoning
                ?: ProviderSpecificConfig.OpenAI().includeHistoryReasoning,
        )
    }
    var openAIEmbeddingsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.embeddingsPath
                ?: ProviderSpecificConfig.OpenAI().embeddingsPath,
        )
    }
    var openAIImagesPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.imagesPath
                ?: ProviderSpecificConfig.OpenAI().imagesPath,
        )
    }
    var openAIStripModelPrefix by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.stripModelPrefix
                ?: ProviderSpecificConfig.OpenAI().stripModelPrefix,
        )
    }
    // v2.6.2: OpenAI Los proveedores también pueden personalizar los encabezados de las solicitudes (algunas estaciones de transferencia requieren sesión x-opencode, etc.).
    // En la implementación anterior, solo se podía completar el tipo Personalizado.OpenCode Go Espere por defecto (OpenAI tipo) por lo tanto no se puede agregar.
    var openAICustomHeadersText by rememberSaveable {
        mutableStateOf(
            formatCustomHeaders(
                (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.customHeaders ?: emptyMap(),
            ),
        )
    }
    // v2.6.2: OpenAI Los proveedores de tipos también pueden agregar campos del cuerpo de la solicitud (del mismo calibre que los encabezados personalizados).
    var openAICustomBodyText by rememberSaveable {
        mutableStateOf(
            formatCustomBody(
                (resolvedSpecific as? ProviderSpecificConfig.OpenAI)?.customBody ?: emptyMap(),
            ),
        )
    }
    var anthropicPromptCaching by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.promptCaching
                ?: ProviderSpecificConfig.Anthropic().promptCaching,
        )
    }
    var anthropicPromptCacheTtl by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.promptCacheTtl
                ?: ProviderSpecificConfig.Anthropic().promptCacheTtl,
        )
    }
    var anthropicMessagesPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.messagesPath
                ?: ProviderSpecificConfig.Anthropic().messagesPath,
        )
    }
    var anthropicModelsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Anthropic)?.modelsPath
                ?: ProviderSpecificConfig.Anthropic().modelsPath,
        )
    }
    var customChatCompletionsPath by rememberSaveable {
        mutableStateOf(
            (resolvedSpecific as? ProviderSpecificConfig.Custom)?.chatCompletionsPath
                ?: ProviderSpecificConfig.Custom().chatCompletionsPath,
        )
    }
    var customHeadersText by rememberSaveable {
        mutableStateOf(
            formatCustomHeaders(
                (resolvedSpecific as? ProviderSpecificConfig.Custom)?.customHeaders ?: emptyMap(),
            ),
        )
    }
    var customBodyText by rememberSaveable {
        mutableStateOf(
            formatCustomBody(
                (resolvedSpecific as? ProviderSpecificConfig.Custom)?.customBody ?: emptyMap(),
            ),
        )
    }

    // Obtener el estado del modelo ascendente(fetchedModels para List<Model>,Necesidad de personalizar Saver,mantener remember)
    var isFetchingModels by rememberSaveable { mutableStateOf(false) }
    var fetchError by rememberSaveable { mutableStateOf<String?>(null) }
    var fetchedModels by remember { mutableStateOf<List<Model>>(emptyList()) }
    var showModelsPicker by rememberSaveable { mutableStateOf(false) }
    // P2-2: La hoja de diferencia del modelo ascendente muestra el estado(Se muestra después de una extracción exitosa,Permitir que los usuarios decidan si fusionar cambios)
    var showModelDiffSheet by remember { mutableStateOf(false) }

    // Selección de pestaña inferior:0=Configuración,1=modelo
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Agregar nuevo cuadro de diálogo de modelo
    var showAddModelDialog by rememberSaveable { mutableStateOf(false) }

    // Eliminar confirmación
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }

    // Estado de prueba de conexión(v2.4: pruebas independientes,Solo prueba sin cambiar,no escribas modelsState)
    // - isTestingConnection: Prueba en progreso(mostrar CircularProgressIndicator)
    // - testConnectionResult: Resultado exitoso de la prueba(Incluyendo el número de modelos.,Pantalla verde ✓)
    // - testConnectionError: Mensaje de error de prueba fallida(Pantalla delineada,rojo ✗)
    var isTestingConnection by rememberSaveable { mutableStateOf(false) }
    var testConnectionResult by rememberSaveable { mutableStateOf<String?>(null) }
    var testConnectionError by rememberSaveable { mutableStateOf<String?>(null) }

    // v1.0.8 (7.6): Estado de verificación de estado del modelo individual(Índice por modelo.id)
    //  - Vacío al entrar a la página(Idle),Después de hacer clic en el botón de prueba, se vuelve InProgress,Cambiar después de la finalización Success/Failed
    //  - No persistente(remember en vez de rememberSaveable),Perdido después de salir de la página.,conformarse a"retroalimentación instantánea"Semántica
    var modelTestStatuses by remember { mutableStateOf<Map<String, ModelTestStatus>>(emptyMap()) }

    // Importe credenciales de Vertex AI desde un archivo JSON de cuenta de servicio de Google
    val context = LocalContext.current
    val localizedResources = androidx.compose.ui.platform.LocalResources.current
    val ioScope = rememberCoroutineScope()
    val settingsRepo: SettingsRepository = koinInject()
    // v2.4: Primero verifique el caché(5minutosTTL,Sólo válido para la configuración actual),Presione éxito tras golpe/El fallo se distribuye al estado correspondiente.
    LaunchedEffect(Unit) {
        val cached = settingsRepo.getCachedConnectionTest(config.id)
        if (cached != null) {
            if (cached.isSuccess) {
                testConnectionResult = cached.result
                testConnectionError = null
            } else {
                testConnectionError = cached.result
                testConnectionResult = null
            }
        }
    }
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ioScope.launch {
            resultOf {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // v1.114: Limitar lectura 10MB,Prevenir archivos muy grandes OOM
                        val MAX_READ_BYTES = 10L * 1024 * 1024
                        val sb = StringBuilder()
                        val buffer = CharArray(8192)
                        var total = 0L
                        BufferedReader(InputStreamReader(stream)).use { reader ->
                            while (true) {
                                val read = reader.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_READ_BYTES) {
                                    error("Archivo demasiado grande,exceder ${MAX_READ_BYTES / 1024 / 1024}MB Límite")
                                }
                                sb.append(buffer, 0, read)
                            }
                        }
                        val text = sb.toString()
                        parseServiceAccountJson(text)
                    }
                }
            }.onSuccess { parsed ->
                if (parsed == null) {
                    fetchError = context.getString(R.string.settings_provider_json_parse_failed)
                    return@onSuccess
                }
                fetchError = null
                serviceAccountEmail = parsed.first
                privateKey = parsed.second
                vertexProjectId = parsed.third
                MuseToast.show(context.getString(R.string.settings_provider_imported_from_json))
            }.onError { msg, t ->
                fetchError = context.getString(R.string.settings_provider_import_failed, msg)
            }
        }
    }

    // Extraiga la lógica empresarial del modelo ascendente
    // v1.132 Optimizar:
    //  - caché primero:5 minutos TTL,Devuelve directamente al golpear,saltar red
    //  - URL Finalización de estrategias múltiples:base → base+/v1 → Inténtalo de nuevo después de quitar la cola /v1(Cubrir la información faltante del usuario/Complete múltiples escenarios)
    //  - Clasificación de errores:401/403 Informar un error inmediatamente o no fallback(Los problemas de credenciales no están cubiertos)
    //  - Botón de actualización manual para omitir el caché(forceFresh = true)
    val fetchModels: (Boolean) -> Unit = { forceFresh ->
        if (apiKey.isBlank()) {
            fetchError = context.getString(R.string.settings_provider_fill_api_key_first)
        } else {
            val tempSpecific = if (type == ProviderType.GEMINI) {
                io.zer0.ai.core.ProviderSpecificConfig.Gemini(
                    useVertexAI = useVertexAi,
                    useServiceAccount = useServiceAccount,
                    serviceAccountEmail = serviceAccountEmail.trim(),
                    privateKey = privateKey.trim(),
                    location = vertexLocation.trim().ifBlank { "us-central1" },
                    projectId = vertexProjectId.trim(),
                )
            } else {
                null
            }
            val tempConfig = ProviderConfig(
                id = config.id,
                displayName = displayName,
                type = type,
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                specific = tempSpecific,
            )
            isFetchingModels = true
            fetchError = null
            ioScope.launch {
                // v1.132: caché primero(5 minutos TTL)
                if (!forceFresh) {
                    val cached = ModelListCache.get(tempConfig, forceFresh = false)
                    if (cached != null) {
                        isFetchingModels = false
                        if (cached.isEmpty()) {
                            fetchError = context.getString(R.string.settings_provider_no_models_returned)
                        } else {
                            val enriched = cached.map { ModelRegistry.enrich(it) }
                            // P2-2: No cubierto directamente modelsState,Cambie a una visualización transparente de las diferencias y deje que los usuarios decidan
                            fetchedModels = enriched
                            showModelDiffSheet = true
                            fetchError = null
                        }
                        return@launch
                    }
                }

                // v1.132: URL Finalización de estrategias múltiples — Cubrir la información faltante del usuario/Complete más escenarios /v1
                val base = tempConfig.baseUrl.trimEnd('/')
                val urlsToTry = mutableListOf<String>()
                if (base.isNotBlank()) {
                    urlsToTry.add(base)
                    if (!base.endsWith("/v1") && !base.endsWith("/v1beta")) {
                        urlsToTry.add("$base/v1")
                    } else if (base.endsWith("/v1")) {
                        // El usuario ha completado /v1 pero es posible que el upstream no lo necesite.,Prueba también a despegar
                        urlsToTry.add(base.removeSuffix("/v1"))
                    }
                } else {
                    // Base URL Usar punto final predeterminado cuando esté vacío
                    urlsToTry.add(tempConfig.baseUrl)
                }

                var lastError: Throwable? = null
                var lastHttpCode: Int? = null
                for (url in urlsToTry) {
                    val result = resultOf {
                        withContext(Dispatchers.IO) {
                            ProviderRegistry.create(tempConfig.copy(baseUrl = url))
                                .listModels(tempConfig.copy(baseUrl = url))
                        }
                    }
                    if (result.isSuccess) {
                        isFetchingModels = false
                        result.getOrThrow().let { models ->
                            if (models.isEmpty()) {
                                fetchError = context.getString(R.string.settings_provider_no_models_returned)
                            } else {
                                // v1.97: Verifique los modelos devueltos por el upstream uno por uno enrich(derivación automática
                                // abilities/modalities/contextWindow),Preservar los campos declarados por el proveedor
                                val enriched = models.map { ModelRegistry.enrich(it) }
                                // v1.132: escribir caché(Correspondiente a la URL final exitosa config)
                                ModelListCache.put(tempConfig.copy(baseUrl = url), models)
                                // P2-2: No cubierto directamente modelsState,Cambie a una visualización transparente de las diferencias y deje que los usuarios decidan
                                fetchedModels = enriched
                                showModelDiffSheet = true
                                fetchError = null
                            }
                        }
                        return@launch
                    } else {
                        lastError = (result as Result.Error).throwable
                        // v1.132: 401/403 Informar un error inmediatamente,No fallback(Problemas de credenciales)
                        val msg = lastError?.message.orEmpty()
                        if (msg.contains("401") || msg.contains("403")) {
                            lastHttpCode = if (msg.contains("401")) 401 else 403
                            break
                        }
                    }
                }

                // Todo falló
                isFetchingModels = false
                val msg = lastError?.message ?: context.getString(R.string.settings_provider_unknown_error)
                fetchError = when {
                    msg.contains("HTML page instead of OpenAI-compatible JSON", ignoreCase = true) ->
                        context.getString(R.string.settings_provider_test_error_html)
                    msg.contains("404") -> context.getString(R.string.settings_provider_404_error)
                    msg.contains("401") || msg.contains("403") -> context.getString(R.string.settings_provider_auth_error)
                    msg.contains("Unable to resolve") || msg.contains("UnknownHost") -> context.getString(R.string.settings_provider_unable_to_resolve)
                    msg.contains("timeout", ignoreCase = true) -> context.getString(R.string.settings_provider_connection_timeout)
                    else -> context.getString(R.string.settings_provider_fetch_failed, msg.take(200))
                }
            }
        }
    }

    // v1.32: Al ingresar a la página de edición, el modelo ascendente se extraerá automáticamente.(No esperes para pasar al modelo. Tab)
    // v1.132: Utilice caché en su lugar(forceFresh=false),5 Acceso repetido en minutos sin acceso repetido a la red
    LaunchedEffect(Unit) {
        if (modelsState.isEmpty() && apiKey.isNotBlank() && !isFetchingModels) {
            fetchModels(false)
        }
    }

    // v2.x(Comentarios del usuario n.º 3-2): Clasificación de errores por falla de prueba. + **razón completa**.
    // no más take(80) Truncar —— Comentarios de los usuarios"Sólo se muestra la mitad y no se puede ver el motivo real.",
    // El nombre de la clase de excepción original y el mensaje completo ahora se conservan para facilitar el enrutamiento automático.
    fun classifyTestFailure(ctx: android.content.Context, throwable: Throwable?): Pair<String, Boolean> {
        val msg = throwable?.message.orEmpty()
        val rawDetail = (throwable?.javaClass?.simpleName ?: "") +
            (if (msg.isNotBlank()) ": $msg" else "")
        val classified = when {
            msg.contains("HTML page instead of OpenAI-compatible JSON", ignoreCase = true) ->
                ctx.getString(R.string.settings_provider_test_error_html)
            msg.contains("401") || msg.contains("403") ->
                ctx.getString(R.string.settings_provider_test_error_auth)
            msg.contains("404") ->
                ctx.getString(R.string.settings_provider_test_error_404)
            msg.contains("Unable to resolve", ignoreCase = true) ||
                msg.contains("UnknownHost", ignoreCase = true) ->
                ctx.getString(R.string.settings_provider_test_error_unknown_host)
            msg.contains("timeout", ignoreCase = true) ->
                ctx.getString(R.string.settings_provider_test_error_timeout)
            else -> ctx.getString(R.string.settings_provider_test_error_failed, rawDetail.ifBlank { "unknown" })
        }
        return classified to false
    }

    // Construir un temporal correspondiente al formulario actual. ProviderConfig
    fun buildTempConfig(): ProviderConfig {
        val isCustom = config.resolvedSpecific() is ProviderSpecificConfig.Custom
        val tempSpecific = when (type) {
            ProviderType.GEMINI -> ProviderSpecificConfig.Gemini(
                useVertexAI = useVertexAi,
                useServiceAccount = useServiceAccount,
                serviceAccountEmail = serviceAccountEmail.trim(),
                privateKey = privateKey.trim(),
                location = vertexLocation.trim().ifBlank { "us-central1" },
                projectId = vertexProjectId.trim(),
            )
            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if (isCustom) {
                ProviderSpecificConfig.Custom(
                    chatCompletionsPath = customChatCompletionsPath.trim(),
                    customHeaders = parseCustomHeaders(customHeadersText),
                    customBody = parseCustomBody(customBodyText),
                )
            } else {
                ProviderSpecificConfig.OpenAI(
                    chatCompletionsPath = openAIChatCompletionsPath.trim(),
                    useResponseApi = openAIUseResponseApi,
                    includeHistoryReasoning = openAIIncludeHistoryReasoning,
                    embeddingsPath = openAIEmbeddingsPath.trim(),
                    imagesPath = openAIImagesPath.trim(),
                    stripModelPrefix = openAIStripModelPrefix.trim(),
                    // v2.6.2: OpenAI Los proveedores de tipos también admiten encabezados de solicitud personalizados/Cuerpo de la solicitud (escenario de encabezado obligatorio de la estación de transferencia).
                    customHeaders = parseCustomHeaders(openAICustomHeadersText),
                    customBody = parseCustomBody(openAICustomBodyText),
                )
            }
            ProviderType.ANTHROPIC -> ProviderSpecificConfig.Anthropic(
                promptCaching = anthropicPromptCaching,
                promptCacheTtl = anthropicPromptCacheTtl.trim().ifBlank { "5m" },
                messagesPath = anthropicMessagesPath.trim(),
                modelsPath = anthropicModelsPath.trim(),
            )
        }
        return ProviderConfig(
            id = config.id,
            displayName = displayName,
            type = type,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            specific = tempSpecific,
            balanceApiPath = balanceApiPath.trim(),
            balanceResultPath = balanceResultPath.trim(),
            // P1-3: Parámetros limitantes actuales(chequeo de salud/Tráelo también al tirar del modelo.,Mantenga la configuración consistente)
            requestLimitPerMinute = requestLimitPerMinuteText.trim().toIntOrNull() ?: 0,
            maxConcurrentRequests = maxConcurrentRequestsText.trim().toIntOrNull() ?: 0,
        )
    }

    // v2.4: Pruebe de forma independiente la lógica empresarial de conexión
    //  - Diferenciarse de fetchModels:solo llama ProviderRegistry.create().listModels() Haz una prueba de luz,
    //    no escribas modelsState,no escribas ModelListCache,Mostrar solo resultados
    //  - Clasificación de errores:401/403 → API Key Inválido,404 → URL No compatible,timeout → Tiempo de espera de conexión,
    //    UnknownHost → No se puede conectar al servidor(mensaje corto,Distinción detallada de errores con fetchModels)
    //  - Número de modelos mostrados correctamente(La conexión es normal. · N modelos)
    //  - Ejecutado usando la rutina ioScope.launch,evitar el bloqueo UI
    val testConnection: () -> Unit = {
        // Reutilizar buildTempConfig() Construir configuración temporal —— ProviderConfig se perderá si lo frota con las manos
        // useResponseApi / customHeaders Con tales detalles del protocolo, los resultados de las pruebas no son confiables.
        val tempConfig = buildTempConfig()
        isTestingConnection = true
        testConnectionResult = null
        testConnectionError = null
        ioScope.launch {
            val (result, isSuccess) = withContext(Dispatchers.IO) {
                // La conexión de prueba se basa principalmente en la "solicitud de modelo real"./models Sólo se utiliza para complementar el número de modelos.
                //
                // El orden de la implementación anterior se invirtió: primero presione /models y luego, en caso de falla, se degradó a la sonda de chat.
                // Pero /models resulta ser el punto final menos confiable —— Gran número de interfaces y estaciones de transferencia compatibles
                // No lo implemente, devuelva una estructura no estándar o imponga restricciones de autenticación separadas.
                // Por lo tanto, se considera que un proveedor que puede chatear normalmente ha fallado aquí. Lo que ve el usuario es
                // “Casi todas las pruebas de proveedores fallan”, y realmente ir a charlar es bueno.
                //
                // Cambiar a: Enviar primero un mensaje real (esta es la ruta que el usuario realmente quiere usar),
                // Si se aprueba, será un éxito; Sólo cuando no se pueda obtener el nombre del modelo comprobable, se devolverá la detección /models.
                val provider = ProviderRegistry.create(tempConfig)
                val probeModelId =
                    config.models.firstOrNull { it.id.isNotBlank() }?.id
                        ?: modelsState.firstOrNull { it.id.isNotBlank() }?.id

                if (probeModelId == null) {
                    // Sin nombre de modelo conocido: solo se pueden probar /modelos (cuando el nuevo proveedor no ha obtenido la lista).
                    val listResult = resultOf { provider.listModels(tempConfig) }
                    if (listResult.isSuccess) {
                        context.getString(
                            R.string.settings_provider_test_success_count,
                            listResult.getOrThrow().size,
                        ) to true
                    } else {
                        classifyTestFailure(context, (listResult as Result.Error).throwable)
                    }
                } else {
                    val probeModel = io.zer0.ai.core.Model(
                        id = probeModelId,
                        providerId = tempConfig.id,
                    )
                    val health = resultOf { provider.healthCheck(probeModel) }
                    val healthOk = health.getOrNull()?.success == true
                    if (healthOk) {
                        // Si puedes hablar, realmente puedes. Simplemente intente obtener la cantidad de modelos para que la copia sea más específica.
                        // Si no puede obtenerlo, no afectará la conclusión, así que use runCatching silencioso.
                        val modelCount = runCatching { provider.listModels(tempConfig).size }.getOrDefault(0)
                        if (modelCount > 0) {
                            context.getString(R.string.settings_provider_test_success_count, modelCount) to true
                        } else {
                            context.getString(R.string.settings_provider_test_success_chat_ok, probeModelId) to true
                        }
                    } else {
                        // Fallo del chat: la razón por la que el enlace del chat se informa primero (está más cerca del uso real que el informe de error de /models),
                        // healthCheck Las excepciones se han traducido en palabras prácticas.
                        val healthMessage = health.getOrNull()?.message
                        if (!healthMessage.isNullOrBlank()) {
                            context.getString(R.string.settings_provider_test_error_failed, healthMessage) to false
                        } else {
                            classifyTestFailure(context, (health as? Result.Error)?.throwable)
                        }
                    }
                }
            }
            if (isSuccess) {
                testConnectionResult = result
                testConnectionError = null
            } else {
                testConnectionError = result
                testConnectionResult = null
            }
            isTestingConnection = false
            // v2.4: Resultados de la prueba de almacenamiento en caché(5 minutos TTL,Se mostrará directamente la próxima vez que ingrese a la página.)
            ioScope.launch {
                settingsRepo.saveConnectionTestCache(tempConfig.id, result, isSuccess)
            }
        }
    }

    // v1.0.8 (7.6): Comprobación de estado de modelo único — Verificar con una llamada real de LLM model + apiKey + baseUrl ¿Está disponible?
    //  - Reutilizar buildTempConfig() Construir una configuración temporal correspondiente al formulario actual.(También se puede probar la apiKey/baseUrl no guardada del usuario.)
    //  - llamar Provider.healthCheck(model) enviar "Reply exactly OK." mensaje de prueba,15s tiempo de espera
    //  - escritura de estado modelTestStatuses(Índice por modelo.id),ProviderModelRow Mostrar estado en consecuencia chip
    //  - Diferencia de testConnection:testConnection Solo punto final de prueba/modelos(ligero),testModel Verificar enlace de chat completo
    fun testModel(model: io.zer0.ai.core.Model) {
        if (apiKey.isBlank()) {
            modelTestStatuses = modelTestStatuses + (model.id to ModelTestStatus.Failed("API Key esta vacio"))
            return
        }
        // establecer en InProgress,Deshabilitar el botón de prueba
        modelTestStatuses = modelTestStatuses + (model.id to ModelTestStatus.InProgress)
        val tempConfig = buildTempConfig()
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                val r = resultOf {
                    io.zer0.ai.ProviderRegistry.create(tempConfig).healthCheck(model)
                }
                if (r.isSuccess) {
                    r.getOrThrow()
                } else {
                    io.zer0.ai.core.HealthCheckResult(
                        success = false,
                        message = (r as Result.Error).throwable?.message?.take(120),
                    )
                }
            }
            val status: ModelTestStatus = if (result.success) {
                ModelTestStatus.Success(result.message)
            } else {
                ModelTestStatus.Failed(result.message)
            }
            modelTestStatuses = modelTestStatuses + (model.id to status)
        }
    }

    // Consultar lógica empresarial de equilibrio.
    fun queryBalance(tempConfig: ProviderConfig) {
        if (tempConfig.balanceApiPath.isBlank()) {
            balanceResult = context.getString(R.string.settings_provider_configure_balance_first)
            return
        }
        isQueryingBalance = true
        balanceResult = null
        ioScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = io.zer0.ai.core.ProxyProvider.applyTo(OkHttpClient.Builder())
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    val url = tempConfig.baseUrl.trimEnd('/') + "/" + tempConfig.balanceApiPath.trimStart('/')
                    val request = Request.Builder()
                        .url(url)
                        .apply {
                            if (tempConfig.apiKey.isNotBlank()) {
                                header("Authorization", "Bearer ${tempConfig.apiKey}")
                            }
                        }
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body.string()
                        if (!response.isSuccessful) {
                            return@use context.getString(
                                R.string.settings_provider_balance_query_failed_http,
                                response.code,
                                body.take(120),
                            )
                        }
                        if (tempConfig.balanceResultPath.isBlank()) {
                            return@use context.getString(R.string.settings_provider_balance_response, body.take(500))
                        }
                        val value = extractJsonPath(body, tempConfig.balanceResultPath)
                        if (value != null) {
                            context.getString(R.string.settings_provider_balance_result, value)
                        } else {
                            context.getString(
                                R.string.settings_provider_balance_path_not_found,
                                tempConfig.balanceResultPath,
                                body.take(300),
                            )
                        }
                    }
                }.getOrElse { e ->
                    when {
                        e.message?.contains("Unable to resolve", ignoreCase = true) == true ||
                            e.message?.contains("UnknownHost", ignoreCase = true) == true ->
                            context.getString(R.string.settings_provider_unable_to_resolve_server)
                        e.message?.contains("timeout", ignoreCase = true) == true ->
                            context.getString(R.string.settings_provider_query_timeout)
                        else -> context.getString(R.string.settings_provider_query_failed, e.message?.take(80) ?: "")
                    }
                }
            }
            balanceResult = result
            isQueryingBalance = false
        }
    }

    /**
     * v1.97: Construir desde el estado de edición actual ProviderConfig(Para guardar y compartir con código QR para reutilizar).
     */
    fun buildCurrentConfig(): ProviderConfig {
        val parsedModels = if (modelsState.isEmpty() && fetchedModels.isNotEmpty()) {
            fetchedModels.toList()
        } else {
            modelsState.toList()
        }
        val isCustom = config.resolvedSpecific() is ProviderSpecificConfig.Custom
        val newSpecific = when (type) {
            ProviderType.GEMINI -> ProviderSpecificConfig.Gemini(
                useVertexAI = useVertexAi,
                useServiceAccount = useServiceAccount,
                serviceAccountEmail = serviceAccountEmail.trim(),
                privateKey = privateKey.trim(),
                location = vertexLocation.trim().ifBlank { "us-central1" },
                projectId = vertexProjectId.trim(),
            )
            ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> if (isCustom) {
                ProviderSpecificConfig.Custom(
                    chatCompletionsPath = customChatCompletionsPath.trim(),
                    customHeaders = parseCustomHeaders(customHeadersText),
                    customBody = parseCustomBody(customBodyText),
                )
            } else {
                ProviderSpecificConfig.OpenAI(
                    chatCompletionsPath = openAIChatCompletionsPath.trim(),
                    useResponseApi = openAIUseResponseApi,
                    includeHistoryReasoning = openAIIncludeHistoryReasoning,
                    embeddingsPath = openAIEmbeddingsPath.trim(),
                    imagesPath = openAIImagesPath.trim(),
                    stripModelPrefix = openAIStripModelPrefix.trim(),
                    // v2.6.2: OpenAI Los proveedores de tipos también admiten encabezados de solicitud personalizados/Cuerpo de la solicitud (escenario de encabezado obligatorio de la estación de transferencia).
                    customHeaders = parseCustomHeaders(openAICustomHeadersText),
                    customBody = parseCustomBody(openAICustomBodyText),
                )
            }
            ProviderType.ANTHROPIC -> ProviderSpecificConfig.Anthropic(
                promptCaching = anthropicPromptCaching,
                promptCacheTtl = anthropicPromptCacheTtl.trim().ifBlank { "5m" },
                messagesPath = anthropicMessagesPath.trim(),
                modelsPath = anthropicModelsPath.trim(),
            )
        }
        val savedCategory = if (isNew && !isFromPreset) {
            io.zer0.ai.core.ProviderCategory.CUSTOM
        } else {
            config.category
        }
        return config.copy(
            displayName = displayName.ifBlank { type.name },
            type = type,
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            models = parsedModels,
            specific = newSpecific,
            enabled = enabled,
            balanceApiPath = balanceApiPath.trim(),
            balanceResultPath = balanceResultPath.trim(),
            category = savedCategory,
            // P1-3: Parámetros limitantes actuales(0 Indica que no hay límite,RateLimitDecorator Superposición bajo demanda cuando ProviderRegistry.create)
            requestLimitPerMinute = requestLimitPerMinuteText.trim().toIntOrNull() ?: 0,
            maxConcurrentRequests = maxConcurrentRequestsText.trim().toIntOrNull() ?: 0,
        )
    }

    // Guardar la lógica empresarial
    val save: () -> Unit = {
        onSave(buildCurrentConfig())
    }

    // ── P1-6: OAuth Iniciar sesión ──────────────────────────────────────────────
    // P2-11: OAuthManager Ya inyectado en MuseApp.onCreate SecureCredentialStore,
    // Aquí accede directamente a través de OAuthManager.(Leer/Actualizar/Deshacer).
    // Observe el flujo de estado de OAuthManager,Ventana emergente del controlador/completar automáticamente apiKey/mensaje de error
    val oauthState by OAuthManager.stateFlow.collectAsStateWithLifecycle()
    // Device Flow El estado emergente de código_usuario(userCode, verificationUri)
    var showDeviceCodeDialog by remember { mutableStateOf(false) }
    var deviceCodeInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    // P2-11: ¿Hay alguna almacenada? OAuth token(Controlar la visualización del botón "Revocar acceso")
    var hasStoredOAuthToken by remember { mutableStateOf(false) }
    // P2-11: Ventana emergente de confirmación "Revocar acceso"
    var showRevokeConfirm by remember { mutableStateOf(false) }

    // P2-11: Al ingresar a la página, lea primero los datos almacenados en SecureCredentialStore. token,
    // Reutilizar directamente sin caducidad,Caduca automáticamente refresh,Si falla, se le solicita al usuario que inicie sesión nuevamente.
    LaunchedEffect(config.id, config.oauthConfig) {
        if (config.oauthConfig == null) return@LaunchedEffect
        val stored = OAuthManager.getStoredToken(config.id) ?: return@LaunchedEffect
        hasStoredOAuthToken = true
        val now = System.currentTimeMillis()
        val bufferMs = 60_000L // 60s Actualizar ventana por adelantado,Alineado con OAuthManager.REFRESH_BUFFER_SECONDS
        if (stored.expiresAt == 0L || stored.expiresAt > now + bufferMs) {
            // No caducado,Complete directamente el cuadro de entrada apiKey
            if (stored.accessToken.isNotBlank() && apiKey.isBlank()) {
                apiKey = stored.accessToken
                MuseToast.show(context.getString(R.string.oauth_stored))
            }
        } else {
            // Caducado,Prueba la actualización automática
            // B-24: Pase el config.oauthConfig persistente a la ruta de actualización — Configuraciones del proveedor de memoria solo en
            // launch* proceso de llenado,App Vacío después de reiniciar,config debe inyectarse explícitamente para garantizar que la actualización automática esté disponible durante los reinicios.
            OAuthManager.refreshTokenIfNeeded(config.id, config.oauthConfig)
                .onSuccess { newToken ->
                    apiKey = newToken
                    MuseToast.show(context.getString(R.string.oauth_token_refreshed))
                }
                .onFailure {
                    hasStoredOAuthToken = false
                    MuseToast.show(context.getString(R.string.oauth_token_expired))
                }
        }
    }

    LaunchedEffect(oauthState) {
        when (val s = oauthState) {
            is OAuthManager.State.SUCCESS -> {
                apiKey = s.apiKey
                hasStoredOAuthToken = true
                // v2.6.2(Codex Solución de inicio de sesión): Analizar el ID de la cuenta ChatGPT y escribir customHeaders ——
                // Codex Las solicitudes de punto final deben llevar el encabezado ChatGPT-Account-ID además del portador.
                // La implementación anterior lee el reclamo de nivel superior (en realidad anidado en `https://api.openai.com/auth` espacio de nombres),
                // Siempre es nulo; y cuando el análisis falla, se omite todo el onSave e incluso el access_token no se guarda.
                val accountId = io.zer0.muse.auth.parseChatGptAccountId(s.idToken)
                    ?: io.zer0.muse.auth.parseChatGptAccountId(s.apiKey)
                val base = buildCurrentConfig()
                val openAiSpecific =
                    (base.specific as? io.zer0.ai.core.ProviderSpecificConfig.OpenAI)
                        ?: io.zer0.ai.core.ProviderSpecificConfig.OpenAI(useResponseApi = true)
                val mergedHeaders = openAiSpecific.customHeaders +
                    mapOf("originator" to "muse") +
                    (accountId?.let { mapOf("ChatGPT-Account-ID" to it) } ?: emptyMap())
                // Independientemente de si el ID de cuenta se analiza correctamente o no, se guardará el token de acceso (de lo contrario, el inicio de sesión será en vano)
                onSave(
                    base.copy(
                        apiKey = s.apiKey,
                        specific = openAiSpecific.copy(customHeaders = mergedHeaders),
                    ),
                )
                if (!accountId.isNullOrBlank()) {
                    MuseToast.show(
                        context.getString(R.string.oauth_codex_account_bound, accountId.take(8)),
                    )
                } else {
                    Logger.w(
                        "ProviderEditPage",
                        "Codex Inicie sesión correctamente pero no se pudo analizar chatgpt_account_id de id_token (idToken existir=${!s.idToken.isNullOrBlank()})",
                    )
                }
                MuseToast.show(context.getString(R.string.oauth_login_success))
                MuseToast.show(context.getString(R.string.oauth_stored))
                OAuthManager.resetState()
                deviceCodeInfo = null
                showDeviceCodeDialog = false
            }
            is OAuthManager.State.ERROR -> {
                MuseToast.show(context.getString(R.string.oauth_login_failed, s.message))
                OAuthManager.resetState()
                deviceCodeInfo = null
                showDeviceCodeDialog = false
            }
            is OAuthManager.State.AWAITING_USER -> {
                // Device Flow llevar userCode,Visualización de ventana emergente;Auth Code Flow El código de usuario está vacío,Mostrado en línea por ConfigTab
                if (s.userCode.isNotBlank()) {
                    deviceCodeInfo = s.userCode to s.verificationUri
                    showDeviceCodeDialog = true
                }
            }
            else -> { /* IDLE / POLLING: Mostrar el estado de carga en línea mediante ConfigTab */ }
        }
    }
    // Cancelar un flujo de OAuth en curso al salir de la página,Evite la fuga de estado a la siguiente entrada
    DisposableEffect(Unit) {
        onDispose { OAuthManager.cancel() }
    }
    // OAuth Portal de inicio de sesión:Device Flow Prioridad,Rebajar Auth Code Flow
    // P2-11: paso a través providerId = config.id,OAuthManager persiste automáticamente después de iniciar sesión correctamente TokenBundle
    val onOAuthLogin: () -> Unit = lambda@{
        val oauthConfig = config.oauthConfig ?: run {
            MuseToast.show(context.getString(R.string.oauth_login_failed, "OAuth No configurado"))
            return@lambda
        }
        if (oauthConfig.deviceCodeUrl.isNullOrBlank()) {
            // Auth Code Flow Requiere contexto de actividad
            val activity = context.findActivity() ?: run {
                MuseToast.show(context.getString(R.string.oauth_login_failed, "No se puede obtener el contexto de la actividad"))
                return@lambda
            }
            ioScope.launch { OAuthManager.launchAuthorizationCodeFlow(activity, oauthConfig, config.id) }
        } else {
            ioScope.launch { OAuthManager.launchDeviceFlow(oauthConfig, config.id) }
        }
    }
    // P2-11: revocar el acceso — Eliminar el token en SecureCredentialStore + borrar el cuadro de entrada apiKey
    val onRevokeOAuth: () -> Unit = {
        showRevokeConfirm = true
    }
    val isOAuthLoading = oauthState is OAuthManager.State.AWAITING_USER ||
        oauthState is OAuthManager.State.POLLING

    // v1.35: Cambiar a pantalla completa normal Scaffold(ya no se usa Dialog),El inserto de la barra de navegación del sistema se pasó correctamente
    // v1.48: BackHandler Comprobar cambios no guardados,Evite perder ediciones por error
    var showDiscardConfirm by remember { mutableStateOf(false) }
    // v1.97: Ventana emergente para compartir códigos QR
    var showQrShareDialog by remember { mutableStateOf(false) }
    // v1.134 P1-2: usar buildCurrentConfig() == config Comparación de campo completo,
    // Portada specific / Vertex / OpenAI / Anthropic / Custom / balanceApiPath y otros campos avanzados.
    val hasUnsavedChanges by remember {
        derivedStateOf { buildCurrentConfig() != config }
    }
    BackHandler {
        if (hasUnsavedChanges) showDiscardConfirm = true else onDismiss()
    }
    if (showDiscardConfirm) {
        MuseDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = stringResource(R.string.settings_provider_discard_changes_title),
            content = { Text(stringResource(R.string.settings_provider_discard_changes_content)) },
            confirmText = stringResource(R.string.settings_provider_discard),
            onConfirm = {
                showDiscardConfirm = false
                onDismiss()
            },
            destructive = true,
        )
    }
    io.zer0.muse.ui.common.surface.MusePageScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            MuseTopBar(
                title = displayName.ifBlank {
                    if (isNew) stringResource(R.string.settings_provider_new) else config.displayName.ifBlank { config.id }
                },
                onBack = onDismiss,
                actions = {
                    // v1.97: Botón compartir código QR(Solo se muestra en proveedores existentes,No se muestra cuando es nuevo)
                    if (!isNew) {
                        MuseTactileButton(
                            icon = MuseIcons.qrcode,
                            onClick = { showQrShareDialog = true },
                            contentDescription = stringResource(R.string.qr_share_btn),
                        )
                    }
                    // v2.4: El botón Probar conexión se ha movido a ConfigTab(Junto al cuadro de entrada baseUrl/apiKey,
                    // La cápsula de resultados se muestra cerca,Cumplir con la página de configuración del sistema"Comentarios de operaciones cercanas"estilo)
                    // v1.134 P0-2: Botón Guardar con Surface+cápsula en la que se puede hacer clic(Evitar el valor predeterminado de Material3 Button)
                    Surface(
                        shape = MuseShapes.pill,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        onClick = save,
                        modifier = Modifier,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_common_save),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                        )
                    }
                },
            )
        },
        bottomBar = {
            // Barra de operación flotante inferior
            ProviderEditBottomBar(
                canFetch = !isFetchingModels && apiKey.isNotBlank(),
                isFetching = isFetchingModels,
                canDelete = onDelete != null && !isNew && !config.builtIn,
                onFetch = fetchModels,
                onAddModel = { showAddModelDialog = true },
                onDelete = { showDeleteConfirm = true },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                // v1.125: eliminar redundante imePadding(),Scaffold El InnerPadding ya contiene el desplazamiento de IME.,
                // El doble imePadding elevará demasiado toda el área de contenido,Esto hace que el teclado oscurezca el cuadro de entrada.
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // v1.0.90 fix: El control segmentado de escritura a mano original se utiliza para pestañas en un contenedor con altura ilimitada. fillMaxHeight(),
                // Las dos pestañas se extienden hasta toda la altura de la pantalla y el texto sale del área visible (como se muestra en la captura de pantalla del usuario). Utilice pestañas de cápsulas unificadas en su lugar.
                MuseCapsuleTab(
                    tabs = listOf(
                        stringResource(R.string.settings_provider_tab_config),
                        stringResource(R.string.settings_provider_tab_models),
                    ),
                    selectedIndex = selectedTab,
                    onSelect = { selectedTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                )

                when (selectedTab) {
                    0 -> ConfigTab(
                        displayName = displayName,
                        onDisplayNameChange = { displayName = it },
                        type = type,
                        onTypeChange = { type = it },
                        isCustomSpecific = config.resolvedSpecific() is ProviderSpecificConfig.Custom,
                        baseUrl = baseUrl,
                        onBaseUrlChange = { baseUrl = it },
                        apiKey = apiKey,
                        onApiKeyChange = { apiKey = it },
                        apiKeyVisible = apiKeyVisible,
                        onApiKeyVisibleChange = { apiKeyVisible = it },
                        enabled = enabled,
                        onEnabledChange = { enabled = it },
                        balanceApiPath = balanceApiPath,
                        onBalanceApiPathChange = { balanceApiPath = it },
                        balanceResultPath = balanceResultPath,
                        onBalanceResultPathChange = { balanceResultPath = it },
                        showAdvanced = showAdvanced,
                        onShowAdvancedChange = { showAdvanced = it },
                        useVertexAi = useVertexAi,
                        onUseVertexAiChange = { useVertexAi = it },
                        useServiceAccount = useServiceAccount,
                        onUseServiceAccountChange = { useServiceAccount = it },
                        serviceAccountEmail = serviceAccountEmail,
                        onServiceAccountEmailChange = { serviceAccountEmail = it },
                        privateKey = privateKey,
                        onPrivateKeyChange = { privateKey = it },
                        privateKeyVisible = privateKeyVisible,
                        onPrivateKeyVisibleChange = { privateKeyVisible = it },
                        vertexLocation = vertexLocation,
                        onVertexLocationChange = { vertexLocation = it },
                        vertexProjectId = vertexProjectId,
                        onVertexProjectIdChange = { vertexProjectId = it },
                        openAIChatCompletionsPath = openAIChatCompletionsPath,
                        onOpenAIChatCompletionsPathChange = { openAIChatCompletionsPath = it },
                        openAIUseResponseApi = openAIUseResponseApi,
                        onOpenAIUseResponseApiChange = { openAIUseResponseApi = it },
                        openAIIncludeHistoryReasoning = openAIIncludeHistoryReasoning,
                        onOpenAIIncludeHistoryReasoningChange = { openAIIncludeHistoryReasoning = it },
                        openAIEmbeddingsPath = openAIEmbeddingsPath,
                        onOpenAIEmbeddingsPathChange = { openAIEmbeddingsPath = it },
                        openAIImagesPath = openAIImagesPath,
                        onOpenAIImagesPathChange = { openAIImagesPath = it },
                        openAIStripModelPrefix = openAIStripModelPrefix,
                        onOpenAIStripModelPrefixChange = { openAIStripModelPrefix = it },
                        openAICustomHeadersText = openAICustomHeadersText,
                        onOpenAICustomHeadersTextChange = { openAICustomHeadersText = it },
                        openAICustomBodyText = openAICustomBodyText,
                        onOpenAICustomBodyTextChange = { openAICustomBodyText = it },
                        anthropicPromptCaching = anthropicPromptCaching,
                        onAnthropicPromptCachingChange = { anthropicPromptCaching = it },
                        anthropicPromptCacheTtl = anthropicPromptCacheTtl,
                        onAnthropicPromptCacheTtlChange = { anthropicPromptCacheTtl = it },
                        anthropicMessagesPath = anthropicMessagesPath,
                        onAnthropicMessagesPathChange = { anthropicMessagesPath = it },
                        anthropicModelsPath = anthropicModelsPath,
                        onAnthropicModelsPathChange = { anthropicModelsPath = it },
                        customChatCompletionsPath = customChatCompletionsPath,
                        onCustomChatCompletionsPathChange = { customChatCompletionsPath = it },
                        customHeadersText = customHeadersText,
                        onCustomHeadersTextChange = { customHeadersText = it },
                        customBodyText = customBodyText,
                        onCustomBodyTextChange = { customBodyText = it },
                        fetchError = fetchError,
                        onFetchErrorDismiss = { fetchError = null },
                        onFetchRetry = { fetchModels(true) },
                        isTestingConnection = isTestingConnection,
                        testConnectionResult = testConnectionResult,
                        testConnectionError = testConnectionError,
                        canTestConnection = !isTestingConnection && baseUrl.isNotBlank(),
                        onTestConnection = testConnection,
                        onTestResultDismiss = {
                            testConnectionResult = null
                            testConnectionError = null
                        },
                        isQueryingBalance = isQueryingBalance,
                        balanceResult = balanceResult,
                        onQueryBalance = { queryBalance(buildTempConfig()) },
                        oauthConfig = config.oauthConfig,
                        onOAuthLogin = onOAuthLogin,
                        isOAuthLoading = isOAuthLoading,
                        // P2-11: Transmisión transparente del estado del token almacenado + devolución de llamada de revocación
                        hasStoredOAuthToken = hasStoredOAuthToken,
                        onRevokeOAuth = onRevokeOAuth,
                        // P1-3: Parámetros limitantes de corriente de transmisión transparentes
                        requestLimitPerMinuteText = requestLimitPerMinuteText,
                        onRequestLimitPerMinuteTextChange = { requestLimitPerMinuteText = it.filter { ch -> ch.isDigit() } },
                        maxConcurrentRequestsText = maxConcurrentRequestsText,
                        onMaxConcurrentRequestsTextChange = { maxConcurrentRequestsText = it.filter { ch -> ch.isDigit() } },
                        onImportServiceAccountJson = {
                            runCatching {
                                serviceAccountJsonLauncher.launch(arrayOf("application/json"))
                            }.onFailure {
                                fetchError = context.getString(R.string.settings_provider_file_picker_failed, it.message ?: "")
                            }
                        },
                    )

                    1 -> ModelsTab(
                        config = config,
                        modelsState = modelsState,
                        isFetching = isFetchingModels,
                        onFetch = fetchModels,
                        onAddModel = { showAddModelDialog = true },
                        // v1.0.8 (7.6): Devolución de llamada + estado transparente de verificación de estado del modelo Map
                        onTestModel = { testModel(it) },
                        modelTestStatuses = modelTestStatuses,
                    )
                }
            }
        }
    }

    // Agregar nuevo cuadro de diálogo de modelo
    // v1.97: Deducido automáticamente a través de ModelRegistry al agregar abilities/modalities/contextWindow
    if (showAddModelDialog) {
        AddModelDialog(
            onDismiss = { showAddModelDialog = false },
            onConfirm = { modelId ->
                if (modelsState.none { it.id == modelId }) {
                    val raw = Model(id = modelId, name = modelId, providerId = config.id)
                    val enriched = ModelRegistry.enrich(raw)
                    modelsState.add(enriched)
                }
                showAddModelDialog = false
            },
        )
    }

    // Eliminar cuadro de diálogo de confirmación
    if (showDeleteConfirm && onDelete != null) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.settings_provider_delete_title),
            content = { Text(stringResource(R.string.settings_provider_delete_content, config.displayName.ifBlank { config.id })) },
            confirmText = stringResource(R.string.settings_common_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
                onDismiss()
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showDeleteConfirm = false },
            destructive = true,
        )
    }

    // v1.97: Ventana emergente para compartir códigos QR(Compartir el estado de edición actual ProviderConfig)
    if (showQrShareDialog) {
        val qrContent = io.zer0.muse.ui.qrcode.QrCodeGenerator.encodeProvider(buildCurrentConfig())
        io.zer0.muse.ui.qrcode.QrCodeShareDialog(
            content = qrContent,
            onDismiss = { showQrShareDialog = false },
        )
    }

    // Selección de modelo que aparece después de una extracción exitosa Sheet
    if (showModelsPicker) {
        val existingIds = modelsState.map { it.id }.toSet()
        FetchedModelsPickerSheet(
            models = fetchedModels,
            existingIds = existingIds,
            providerType = type,
            providerName = displayName.ifBlank { config.id },
            onDismiss = { showModelsPicker = false },
            onConfirm = { selectedModels ->
                selectedModels.forEach { m -> if (modelsState.none { it.id == m.id }) modelsState.add(m) }
                showModelsPicker = false
            },
        )
    }

    // P2-2: Diferencias del modelo ascendente Sheet(Recién mostrado de forma transparente después de una extracción exitosa/Eliminado/Agrupación consistente de tres categorías)
    //  - onAdd: Fusionar los nuevos modelos seleccionados en modelsState(Ignorar mayúsculas y minúsculas y deduplicar por ID)
    //  - onRemove: Eliminar los modelos eliminados marcados de modelsState(Ignorar coincidencia de casos por identificación)
    //  - onDismiss: cerrar Sheet(Se activa al hacer clic en la máscara/tecla de retorno/finalización de la aplicación)
    if (showModelDiffSheet) {
        ModelDiffSheet(
            localModels = modelsState.toList(),
            upstreamModels = fetchedModels,
            onAdd = { toAdd ->
                toAdd.forEach { m ->
                    if (modelsState.none { it.id.equals(m.id, ignoreCase = true) }) {
                        modelsState.add(m)
                    }
                }
            },
            onRemove = { toRemove ->
                val removeIds = toRemove.map { it.id.lowercase() }.toHashSet()
                modelsState.removeAll { existing -> existing.id.lowercase() in removeIds }
            },
            onDismiss = { showModelDiffSheet = false },
        )
    }

    // P1-6: Device Flow user_code ventana emergente — Mostrar uri_verificación + código_usuario al usuario
    if (showDeviceCodeDialog && deviceCodeInfo != null) {
        val (userCode, verificationUri) = deviceCodeInfo!!
        MuseDialog(
            onDismissRequest = {
                // El usuario cancela:Cancelar el proceso OAuth
                OAuthManager.cancel()
                showDeviceCodeDialog = false
                deviceCodeInfo = null
            },
            title = stringResource(R.string.oauth_login),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            R.string.oauth_device_code_prompt,
                            verificationUri,
                            userCode,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    // v2.6.0: proporcionar“Abra la página de autorización directamente”botón —— La implementación original solo muestra el texto de la URL.,
                    // Los usuarios deben abrir su propio navegador y proporcionar la dirección.,La experiencia de inicio de sesión con un solo clic no funciona.
                    MuseCapsuleButton(
                        text = stringResource(R.string.oauth_device_open_page),
                        onClick = {
                            runCatching {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(verificationUri),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }.onFailure {
                                io.zer0.muse.ui.common.feedback.MuseToast.show(
                                    context.getString(R.string.oauth_device_open_failed),
                                )
                            }
                        },
                        leadingIcon = MuseIcons.externalLink,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    // user_code Utilice tamaño de fuente grande + centro,Conveniente para que los usuarios copien a la página de autorización
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = userCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = androidx.compose.ui.unit.TextUnit(
                                    2f,
                                    androidx.compose.ui.unit.TextUnitType.Sp,
                                ),
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = MusePaddings.itemGap),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.oauth_open_verification_uri),
            onConfirm = {
                // Abrir con intención verification_uri
                runCatching {
                    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri))
                    openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(openIntent)
                }.onFailure {
                    MuseToast.show(
                        localizedResources.getString(R.string.oauth_login_failed, it.message ?: "No puedo abrir el navegador"),
                    )
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = {
                OAuthManager.cancel()
                showDeviceCodeDialog = false
                deviceCodeInfo = null
            },
        )
    }

    // P2-11: Revocar la ventana emergente de confirmación de acceso a OAuth — Eliminar SecureCredentialStore token
    if (showRevokeConfirm) {
        MuseDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = stringResource(R.string.oauth_revoke),
            content = { Text(stringResource(R.string.oauth_revoke_confirm)) },
            confirmText = stringResource(R.string.oauth_revoke),
            onConfirm = {
                showRevokeConfirm = false
                ioScope.launch {
                    OAuthManager.revokeStoredToken(config.id)
                    // Borre el cuadro de entrada apiKey + sincronice el estado local
                    apiKey = ""
                    hasStoredOAuthToken = false
                    MuseToast.show(localizedResources.getString(R.string.oauth_revoke_success))
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showRevokeConfirm = false },
            destructive = true,
        )
    }
}
