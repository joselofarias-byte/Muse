package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.ProviderRegistry
import io.zer0.common.Result
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.R
import io.zer0.muse.auth.OAuthManager
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.feedback.MuseAlertDialog
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.findActivity
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseLargeTitleHeader
import io.zer0.muse.ui.common.navigation.MuseStickyBackButton
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.museSafeTopInsetPadding
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.update.UpdateCheckResult
import io.zer0.muse.update.UpdateChecker
import io.zer0.muse.update.UpdateNotifier
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v0.34: Contenedor de página de configuración secundaria universal — Gran barra de título.
 *
 * Navegación en tres pasos(Retorno a la izquierda + título mediano + espacio a la derecha). usar LazyColumn(Admite extensiones LazyListScope como
 * providerListSection).
 *
 * P1-4 Adaptación de tableta:[onBack] Cambiar a anulable(No es necesario un botón de retorno en la columna derecha en el modo de doble columna);
 * Expanded LazyColumn está centrado y tiene un ancho limitado en modo 720dp,Evite estirar demasiado los elementos de la lista en pantallas grandes.
 */
@Composable
fun SettingsSubPageScaffold(title: String, onBack: (() -> Unit)? = null, content: LazyListScope.() -> Unit) {
    // v2.0.1: cabeza rodante — Título grande (item 0) se elimina con el desplazamiento (conservando la tecla de retorno).
    // La barra de búsqueda sólo se conserva en la página de configuración de primer nivel; la barra de búsqueda ya no se encuentra en las subpáginas de segundo y tercer nivel.
    // Después de que se lanza el título grande, el botón de retorno montado en el techo aparece gradualmente en la esquina superior izquierda (MuseStickyBackButton), garantizado para ser devuelto en cualquier momento.
    val listState = rememberLazyListState()
    val isHeaderStuck by remember {
        derivedStateOf { listState.firstVisibleItemIndex >= 1 }
    }
    val backStuckProgress by animateFloatAsState(
        targetValue = if (isHeaderStuck) 1f else 0f,
        animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
        label = "settingsSubBackStuck",
    )

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        // v2.0.1: El título grande se movió a la lista (se salió al desplazarse),topBar Déjelo en blanco.
        topBar = {},
        containerColor = MaterialTheme.colorScheme.background,
        topBarHandlesInsets = true,
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // P1-4: Box paquete LazyColumn,Expanded Utilice contentAlignment para centrar y limitar la lista en modo.
            // P0: Primero abre la ventana/La altura limitada del diseño principal se pasa al contenedor de desplazamiento único.,evitar ciertos Android 16 / ROM
            // edge-to-edge La ruta de medición coloca a LazyColumn bajo una restricción de altura ilimitada,Activar composición
            // "Vertically scrollable component was measured with an infinity maximum height".
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    // v2.0.1: El margen interior de la barra de estado está a cargo del contenedor de desplazamiento (el título grande se desplaza con la lista y la barra de búsqueda del techo se adsorbe debajo de la barra de estado).
                    // Utilice museSafeTopInsetPadding:insets Cuando el tamaño de la pantalla es anormalmente pequeño (como la pantalla virtual MuMu), recurra a un valor conservador para evitar que la barra del techo invada la barra de estado.
                    .museSafeTopInsetPadding()
                    // Las páginas de configuración ordinarias sólo evitan el teclado y la barra de navegación, y no superponen gestos del sistema insertados en todo el contenedor de desplazamiento.
                    // OPPO La capa de compatibilidad de gestos de pantalla completa puede informar áreas de gestos horizontales anormales, lo que hace que la página parezca más pequeña en general.
                    .imePadding()
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                val boundedHeight = boundedSettingsScrollHeight(
                    parentMaxHeight = maxHeight,
                    windowHeight = LocalConfiguration.current.screenHeightDp.dp,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = boundedHeight)
                        .testTag(SETTINGS_SCROLL_CONTAINER_TAG)
                        // La página de configuración mantiene todo el ancho de la ventana del dispositivo; Las ventanas celulares no se pueden truncar con un valor fijo de 720 dp.
                        .padding(horizontal = MusePaddings.screen),
                    contentPadding = PaddingValues(
                        // El botón de retorno en el techo superior flota en la esquina superior izquierda y el contenido se deslizará debajo de él después de desplazarse (la primera palabra está oscurecida).
                        // Agregue la altura del botón de acuerdo con el progreso del techo, para que el contenido siempre termine debajo del botón.
                        top = MusePaddings.touchTarget * backStuckProgress,
                        bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.cardGap),
                ) {
                    item(key = "settings_large_header") {
                        // offset Relleno horizontal desplazado de lista: título/El botón Atrás está en el mismo nivel que la página de inicio.
                        MuseLargeTitleHeader(
                            title = title,
                            onBack = onBack,
                            modifier = Modifier.offset(x = -MusePaddings.screen),
                        )
                    }
                    content()
                }
            }
            // v2.0.1: El botón de retorno montado en el techo que aparece gradualmente después de desplazarse (aún puedes regresar después de que se lanza el título grande).
            if (onBack != null && backStuckProgress > 0.01f) {
                MuseStickyBackButton(
                    onBack = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .museSafeTopInsetPadding()
                        .padding(start = 8.dp, top = 8.dp)
                        .alpha(backStuckProgress),
                )
            }
        }
    }
}

/**
 * Devuelve la altura máxima limitada del contenedor de desplazamiento de la página.
 *
 * Durante la medición normal, primero se utiliza la altura máxima del diseño principal; si el diseño principal pasa por restricciones ilimitadas(Compose de
 * Infinity),Retroceda a la altura actual de la ventana, asegurando que LazyColumn nunca mida a una altura ilimitada.
 */
internal const val SETTINGS_SCROLL_CONTAINER_TAG = "settings-scroll-container"

internal fun boundedSettingsScrollHeight(parentMaxHeight: Dp, windowHeight: Dp): Dp {
    val fallback = windowHeight.takeIf { it.value.isFinite() && it.value > 0f } ?: 1.dp
    return parentMaxHeight.takeIf { it.value.isFinite() && it.value > 0f } ?: fallback
}

/**
 * pagina de segundo nivel:Modelos y Servicios(Lista de proveedores)
 *
 * v1.133: Dividir y refactorizar — Sólo se mantiene la lista de proveedores. Otras secciones incrustadas originales se han dividido en páginas independientes de segundo nivel.:
 *  - [SettingsWebSearchPage] búsqueda en internet
 *  - [SettingsAsrPage] reconocimiento de voz ASR
 *  - [SettingsMcpPage] MCP servidor
 *  - [SettingsAssistantResourcesPage] Recursos asistentes(Favoritos/libro mundial/mensaje rapido/inyección de patrón/Skills/interruptor de memoria)
 *  generación de imágenes/La configuración de generación de video ha sido devuelta a la página del proveedor.(ImageGenSection/VideoGenSection).
 *  La entrada con retrato de usuario ya está en el nivel superior."Universales"grupo,No es necesario repetirlo aquí.
 */
@Composable
fun SettingsModelPage(
    onBack: () -> Unit,
    /** v1.x: Entrada de configuración del motor de reconocimiento de voz(Página del proveedor → ASR página). */
    onOpenAsr: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val assistantRepository: AssistantRepository = koinInject()
    // v1.48: h13 initialValue usar null,Ingresar al estado de carga por primera vez en lugar de mostrar el estado
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = null)
    val visibleProviders = providers?.filterNot { it.hiddenFromSettings }
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editingConfig by remember { mutableStateOf<io.zer0.ai.core.ProviderConfig?>(null) }
    var isNewProvider by remember { mutableStateOf(false) }
    var isFromPreset by remember { mutableStateOf(false) }
    // v1.38: Estado de visualización de la página de pantalla completa de selección de proveedor predeterminado(El ModalBottomSheet original se cambia a una página de pantalla completa)
    var showPresetPicker by remember { mutableStateOf(false) }
    // v2.6.0: Estado de solicitud de Device Flow para iniciar sesión en OAuth con un solo clic(userCode, verificationUri).
    // Device Flow Debe mostrarse en la interfaz de usuario user_code,Sólo el usuario puede ir a la página de autorización para ingresar;La ruta original de un solo clic solo se reproduce
    // “Abriendo el navegador”pero no muestra el código → El usuario no puede completar la autorización.
    var quickLoginDeviceInfo by remember { mutableStateOf<Pair<String, String>?>(null) }
    // v1.97: Escanea el código QR para importar Provider
    var showQrScanDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // v1.133: Estado de verificación del estado del lote del proveedor(encabezado de lista"Todas las pruebas"disparador de botón)
    //  - testStatuses: Estado de prueba único indexado por proveedor.id(Testing / Success(latency) / Failed(error))
    //  - isTestingAllProviders: Detección global en curso(Se utiliza para desactivar el botón + mostrar la rotación del cabezal.)
    // Utilice mutableStateMapOf en su lugar mutableStateOf<Map> Es para permitir que los async concurrentes actualicen los suyos. key,
    // evitar leer-cambiar-escribe carrera perdida(Una vez completado cada asíncrono, configure el suyo propio de forma independiente key,SnapshotStateMap Atómico garantizado).
    val providerTestStatuses = remember { mutableStateMapOf<String, ProviderTestStatus>() }
    var isTestingAllProviders by remember { mutableStateOf(false) }

    // v1.133: "Todas las pruebas" — Pruebe simultáneamente todos los proveedores configurados,Cada proveedor muestra la carga/los resultados de forma independiente
    //  - Reutilice la lógica de prueba ligera de ProviderEditPage.testConnection(Llame solo al punto final /models,No decir la verdad chat)
    //  - Concurrencia de rutinas:async + awaitAll,Cada asíncrono actualiza su propio estado de forma independiente(Admite la finalización asincrónica de proveedores rápidos y lentos)
    //  - Error de reutilización jerárquica settings_provider_test_error_* cuerda,Mantenga la coherencia con una sola prueba
    val onTestAllProviders: () -> Unit = {
        val list = visibleProviders
        when {
            // Detectando o la lista no se ha cargado.,Ignorar los desencadenantes repetidos
            isTestingAllProviders || list == null -> Unit
            list.isEmpty() -> {
                io.zer0.muse.ui.common.feedback.MuseToast.show(
                    context.getString(R.string.settings_provider_test_all_no_providers),
                )
            }
            else -> {
                scope.launch {
                    isTestingAllProviders = true
                    // restablecer:Todos los proveedores marcados como Testing
                    providerTestStatuses.clear()
                    list.forEach { providerTestStatuses[it.id] = ProviderTestStatus.Testing }
                    // Pruebas de concurrencia:Uno por proveedor async,Actualizar el estado de forma independiente cuando se complete
                    coroutineScope {
                        list.map { config ->
                            async(Dispatchers.IO) {
                                val status: ProviderTestStatus = if (config.apiKey.isBlank()) {
                                    // Si no hay una clave API, fallará directamente.,No enviar solicitudes de red
                                    ProviderTestStatus.Failed(
                                        context.getString(R.string.settings_provider_test_skip_no_key),
                                    )
                                } else {
                                    val start = System.currentTimeMillis()
                                    val result = resultOf {
                                        ProviderRegistry.create(config).listModels(config)
                                    }
                                    val latency = System.currentTimeMillis() - start
                                    when (result) {
                                        is Result.Success -> ProviderTestStatus.Success(latency, result.data.size)
                                        is Result.Error -> {
                                            val msg = result.throwable?.message.orEmpty()
                                            val classified = when {
                                                msg.contains("401") || msg.contains("403") ->
                                                    context.getString(R.string.settings_provider_test_error_auth)
                                                msg.contains("404") ->
                                                    context.getString(R.string.settings_provider_test_error_404)
                                                msg.contains("Unable to resolve", ignoreCase = true) ||
                                                    msg.contains("UnknownHost", ignoreCase = true) ->
                                                    context.getString(R.string.settings_provider_test_error_unknown_host)
                                                msg.contains("timeout", ignoreCase = true) ->
                                                    context.getString(R.string.settings_provider_test_error_timeout)
                                                else -> context.getString(
                                                    R.string.settings_provider_test_error_failed,
                                                    msg.take(80),
                                                )
                                            }
                                            ProviderTestStatus.Failed(classified)
                                        }
                                    }
                                }
                                // Finalización única — Actualiza el tuyo de forma independiente key(átomo,sin carrera)
                                providerTestStatuses[config.id] = status
                            }
                        }.awaitAll()
                    }
                    isTestingAllProviders = false
                    // Resumen:recuento de éxito/fracaso,Toast Notificar a los usuarios de los resultados finales.
                    val successCount = providerTestStatuses.values.count { it is ProviderTestStatus.Success }
                    val failedCount = providerTestStatuses.values.count { it is ProviderTestStatus.Failed }
                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                        context.getString(R.string.settings_provider_test_all_done, successCount, failedCount),
                    )
                }
            }
        }
    }

    // v1.97: Escanee la ventana emergente del código QR
    if (showQrScanDialog) {
        io.zer0.muse.ui.qrcode.QrCodeScanDialog(
            onDismiss = { showQrScanDialog = false },
            onResult = { content ->
                showQrScanDialog = false
                val provider = io.zer0.muse.ui.qrcode.QrCodeGenerator.parseProviderQr(content)
                if (provider != null) {
                    // Genere una nueva ID para evitar conflictos con la configuración original.,Actualice también el ID de proveedor de los modelos.
                    // Utilice UUID en lugar de marca de tiempo —— Predeterminado/Cuando los códigos QR se importan continuamente, la identificación se eliminará en el mismo milisegundo.
                    val newId = "provider-" + java.util.UUID.randomUUID()
                    val imported = provider.copy(
                        id = newId,
                        builtIn = false,
                        models = provider.models.map { it.copy(providerId = newId) },
                    )
                    scope.launch {
                        settings.addProvider(imported)
                        io.zer0.muse.ui.common.feedback.MuseToast.show(
                            context.getString(R.string.qr_import_success, imported.displayName),
                        )
                    }
                } else {
                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                        context.getString(R.string.qr_import_invalid),
                    )
                }
            },
        )
    }

    // v1.35: Renderice la página en pantalla completa directamente al editar el proveedor(No hay necesidad Dialog,Evitar que la barra de navegación del sistema bloquee los botones inferiores)
    // v2.6.1: usar key(config.id) paquete —— ProviderEditPage muchos internos rememberSaveable
    // Guardar por ubicación combinada de forma predeterminada/restaurar,Al editar diferentes proveedores continuamente, la apiKey del proveedor anterior, etc.
    // Lleva el estado al siguiente(Prueba real del usuario:La conexión de prueba informa caracteres no ASCII en la clave anterior). Después de separar por ID de configuración,
    // Cambiar el objeto de edición reconstruirá todo el estado del subárbol,No más residuos.
    editingConfig?.let { config ->
        key(config.id) {
            ProviderEditPage(
                config = config,
                isNew = isNewProvider,
                isFromPreset = isFromPreset,
                onDismiss = { editingConfig = null },
                onSave = { saved ->
                    scope.launch {
                        if (isNewProvider) {
                            settings.addProvider(saved)
                        } else {
                            settings.updateProvider(saved)
                        }
                        editingConfig = null
                    }
                },
                onDelete = {
                    scope.launch {
                        settings.deleteProvider(config.id)
                        assistantRepository.removeProviderBinding(config.id)
                        editingConfig = null
                    }
                },
            )
        }
        return
    }

    // v2.6.0: Ventana emergente de solicitud de Device Flow para iniciar sesión con un solo clic —— Muestra el código de usuario + abre la página de autorización con un clic.
    quickLoginDeviceInfo?.let { (userCode, verificationUri) ->
        MuseDialog(
            onDismissRequest = {
                OAuthManager.cancel()
                quickLoginDeviceInfo = null
            },
            title = stringResource(R.string.oauth_login),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.oauth_device_code_prompt, verificationUri, userCode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    Surface(
                        shape = MuseShapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = userCode,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = MusePaddings.itemGap),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.size(MusePaddings.contentGap))
                    MuseCapsuleButton(
                        text = stringResource(R.string.oauth_device_open_page),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(verificationUri),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                io.zer0.muse.ui.common.feedback.MuseToast.show(
                                    context.getString(R.string.oauth_device_open_failed),
                                )
                            }
                        },
                        leadingIcon = MuseIcons.externalLink,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }

    // v1.38: Página de pantalla completa de selección de proveedor predeterminada(sustituto ModalBottomSheet,Cura atascada después del cierre)
    if (showPresetPicker) {
        PresetProviderPickerDialog(
            onDismiss = { showPresetPicker = false },
            onPickPreset = { preset ->
                val newId = "provider-" + java.util.UUID.randomUUID()
                val newConfig = preset.copy(
                    id = newId,
                    builtIn = false,
                    apiKey = "",
                    models = preset.models.map { it.copy(providerId = newId) },
                )
                // v2.6.0: OAuth Inicio de sesión predeterminado con un clic —— Haga clic en Predeterminado para ir directamente a la autorización.
                // Guardar automáticamente después de la verificación+Actívalo y no ingreses a la página de edición (misma experiencia que la aplicación oficial ChatGPT).
                // Device Flow Los ajustes preestablecidos (como xAI) no requieren Actividad;Auth Code Flow Necesario.
                val oauth = preset.oauthConfig
                if (oauth != null) {
                    val isDeviceFlow = !oauth.deviceCodeUrl.isNullOrBlank()
                    val activity = context.findActivity()
                    if (!isDeviceFlow && activity == null) {
                        io.zer0.muse.ui.common.feedback.MuseToast.show(
                            context.getString(R.string.oauth_activity_context_unavailable),
                        )
                        showPresetPicker = false
                    } else {
                        showPresetPicker = false
                        io.zer0.muse.ui.common.feedback.MuseToast.show(
                            context.getString(R.string.oauth_quick_login_started),
                        )
                        scope.launch {
                            // Primero descargue la configuración (después de iniciar sesión correctamente, OAuthManager escribe el token según el ID del proveedor)
                            settings.addProvider(newConfig)
                            // v2.6.0: Device Flow Estado de escucha durante la encuesta,Después de obtener el código de usuario, aparece una ventana emergente
                            // (Mismo mecanismo que la página de edición.);De lo contrario, el usuario no podrá ver el código y no podrá autorizarlo.
                            val watcher = if (isDeviceFlow) {
                                scope.launch {
                                    OAuthManager.stateFlow.collect { s ->
                                        if (s is OAuthManager.State.AWAITING_USER && s.userCode.isNotBlank()) {
                                            quickLoginDeviceInfo = s.userCode to s.verificationUri
                                        }
                                    }
                                }
                            } else {
                                null
                            }
                            val flow: kotlin.Result<String> = if (isDeviceFlow) {
                                OAuthManager.launchDeviceFlow(oauth, newId)
                            } else {
                                OAuthManager.launchAuthorizationCodeFlow(activity!!, oauth, newId)
                            }
                            watcher?.cancel()
                            quickLoginDeviceInfo = null
                            flow.fold(
                                onSuccess = {
                                    settings.setActiveProvider(newId)
                                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                                        context.getString(R.string.oauth_quick_login_success, preset.displayName),
                                    )
                                },
                                onFailure = { err ->
                                    // Si el inicio de sesión falla, elimine la configuración vacía recién agregada para evitar dejar un montón de proveedores sin terminar.
                                    settings.deleteProvider(newId)
                                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                                        context.getString(R.string.oauth_login_failed, err.message ?: ""),
                                    )
                                },
                            )
                        }
                    }
                } else {
                    editingConfig = newConfig
                    isNewProvider = true
                    isFromPreset = true
                    showPresetPicker = false
                }
            },
            onPickCustom = {
                editingConfig = io.zer0.ai.core.ProviderConfig(
                    id = "provider-" + java.util.UUID.randomUUID(),
                    displayName = "",
                    type = io.zer0.ai.core.ProviderType.OPENAI,
                    category = io.zer0.ai.core.ProviderCategory.CUSTOM,
                )
                isNewProvider = true
                isFromPreset = false
                showPresetPicker = false
            },
        )
        return
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_sub_model_services), onBack = onBack) {
        val providersList = visibleProviders
        if (providersList == null) {
            // v1.48: h13 Mostrar indicador de carga centrada en la primera carga,Evite el estado intermitente de la lista de proveedores
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MuseSpinner()
                }
            }
        } else {
            // Provider lista(Empaquetado con la extensión interna LazyListScope,llamar directamente)
            providerListSection(
                providers = providersList,
                activeProviderId = activeProviderId,
                onActivate = { id -> scope.launch { settings.setActiveProvider(id) } },
                onEdit = { config ->
                    editingConfig = config
                    isNewProvider = false
                    isFromPreset = false
                },
                onDelete = {
                        id ->
                    scope.launch {
                        settings.deleteProvider(id)
                        assistantRepository.removeProviderBinding(id)
                    }
                },
                onAddProvider = { showPresetPicker = true },
                // v1.97: Escanea el código QR para importar Provider
                onScanQr = { showQrScanDialog = true },
                // v1.133: Pruebas de salud por lotes(encabezado de lista"Todas las pruebas"botón)
                testStatuses = providerTestStatuses,
                isTestingAll = isTestingAllProviders,
                onTestAll = onTestAllProviders,
            )
        }

        // v1.x: Configuración del modelo de generación de medios. — Pasar de la página secundaria de configuración a la página del proveedor
        item { SectionLabel(stringResource(R.string.settings_provider_media_gen_section)) }
        item { ImageGenSection(settings = settings, scope = scope) }
        item { VideoGenSection(settings = settings, scope = scope) }
        // v1.x: motor de reconocimiento de voz(Motor independiente fuera del sistema de modelo de proveedor.,La entrada está colapsada a la página del proveedor.)
        item {
            SettingsItemRow(
                icon = MuseIcons.microphone,
                title = stringResource(R.string.settings_asr_entry_title),
                subtitle = stringResource(R.string.settings_asr_entry_subtitle),
                onClick = onOpenAsr,
            )
        }
    }
}

/**
 * v1.133: pagina de segundo nivel — búsqueda en internet(Separarse de ConfiguraciónModelPage).
 *
 * Sólo renderizar [WebSearchSection],dejar"Modelos y Servicios"La página solo mantiene una lista de proveedores.
 */
@Composable
fun SettingsWebSearchPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val webSearchConfig by settings.webSearchConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.web.WebSearchConfig(),
    )
    SettingsSubPageScaffold(title = stringResource(R.string.settings_web_search_section), onBack = onBack) {
        item { WebSearchSection(webSearchConfig = webSearchConfig, settings = settings) }
    }
}

/**
 * v1.133: pagina de segundo nivel — Recursos asistentes(Separarse de ConfiguraciónModelPage).
 *
 * acomodar [AssistantEntriesSection]:Gestión asistente/Favoritos/Libro mundial/Mensaje rápido/Modo de inyección/Habilidades/Cambio de memoria a largo plazo.
 * dejar"Modelos y Servicios"La página solo mantiene una lista de proveedores.
 */
@Composable
fun SettingsAssistantResourcesPage(
    onBack: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenLorebooks: () -> Unit = {},
    onOpenWorldbook: () -> Unit = {},
    onOpenQuickMessages: () -> Unit = {},
    onOpenPromptInjections: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
) {
    SettingsSubPageScaffold(title = stringResource(R.string.settings_assistant_resources_title), onBack = onBack) {
        item {
            AssistantEntriesSection(
                onOpenAssistants = onOpenAssistants,
                onOpenFavorites = onOpenFavorites,
                onOpenLorebooks = onOpenLorebooks,
                onOpenWorldbook = onOpenWorldbook,
                onOpenQuickMessages = onOpenQuickMessages,
                onOpenPromptInjections = onOpenPromptInjections,
                onOpenSkills = onOpenSkills,
            )
        }
    }
}

/**
 * v1.133: pagina de segundo nivel — reconocimiento de voz ASR(Separarse de ConfiguraciónModelPage).
 */
@Composable
fun SettingsAsrPage(onBack: () -> Unit, onOpenProviderSettings: () -> Unit = {}) {
    val settings: SettingsRepository = koinInject()
    val asrConfig by settings.asrConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.asr.AsrConfig(),
    )
    SettingsSubPageScaffold(title = stringResource(R.string.section_asr), onBack = onBack) {
        item { AsrSection(asrConfig = asrConfig, settings = settings, onOpenProviderSettings = onOpenProviderSettings) }
    }
}

/**
 * v1.133: pagina de segundo nivel — MCP servidor(Separarse de ConfiguraciónModelPage).
 */
@Composable
fun SettingsMcpPage(onBack: () -> Unit) {
    SettingsSubPageScaffold(title = stringResource(R.string.section_mcp), onBack = onBack) {
        item { McpSection() }
    }
}

/**
 * pagina de segundo nivel:Datos y respaldo(Backup + WebServer)
 *
 * La configuración de la copia de seguridad en la nube se gestiona a través de una CloudBackupPage independiente; esta página conserva el estado y las cargas/Acceso rápido a recuperación y sincronización automática.
 */
@Composable
fun SettingsDataPage(onBack: () -> Unit, onOpenCloudBackup: () -> Unit = {}) {
    val settings: SettingsRepository = koinInject()
    val sessionRepository: io.zer0.muse.data.session.SessionRepository = koinInject()
    val backupService: io.zer0.muse.backup.BackupService = koinInject()
    val sessionCount by sessionRepository.observeSessionCount().collectAsStateWithLifecycle(initialValue = 0)
    val messageCount by sessionRepository.observeMessageCount().collectAsStateWithLifecycle(initialValue = 0)

    SettingsSubPageScaffold(title = stringResource(R.string.settings_sub_data_backup), onBack = onBack) {
        item {
            BackupSection(
                sessionCount = sessionCount,
                messageCount = messageCount,
                backupService = backupService,
                settings = settings,
                sessionRepository = sessionRepository,
                autoBackupLogDao = koinInject(),
                onOpenCloudBackup = onOpenCloudBackup,
            )
        }
        item {
            WebServerSection(
                settings = settings,
                webServer = koinInject(),
            )
        }
    }
}

/**
 * pagina de segundo nivel:Apariencia(tema + tamaño de fuente)
 */
@Composable
fun SettingsAppearancePage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val fontSizeScale by settings.fontSizeScaleFlow.collectAsStateWithLifecycle(initialValue = "medium")
    val language by settings.languageFlow.collectAsStateWithLifecycle(initialValue = "system")
    SettingsSubPageScaffold(title = stringResource(R.string.settings_theme_appearance), onBack = onBack) {
        item {
            ThemeSection(
                themeMode = themeMode,
                fontSizeScale = fontSizeScale,
                settings = settings,
            )
        }
        item {
            LanguageSection(
                language = language,
                settings = settings,
            )
        }
        // v1.95: Iniciar página predeterminada(Tarea/Agente/Chat grupal)
        item {
            DefaultHomePageSection(
                settings = settings,
            )
        }
        // ── burbuja de mensaje ──
        item { SectionLabel(stringResource(R.string.settings_screen_group_bubble)) }
        item { BubbleStyleSection() }
        // ── Tarjetas e interfaces generativas (al regresar de la página de chat)──
        item { SectionLabel(stringResource(R.string.settings_screen_group_cards)) }
        item { GenerativeUiSection() }
    }
}

/**
 * Aspecto de la burbuja del mensaje: ancho de la burbuja/avatar del usuario/esquinas redondeadas.
 *
 * Estos son "cómo se ve el mensaje" y son dos cosas diferentes del color del tema, por lo que están divididos en secciones separadas.
 */
@Composable
private fun BubbleStyleSection() {
    val settings: SettingsRepository = koinInject()
    val prefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(initialValue = ChatPreferences())
    val scope = rememberCoroutineScope()

    fun update(block: (ChatPreferences) -> ChatPreferences) {
        scope.launch { settings.updateChatPreferences(block) }
    }

    SettingsGroup {
        SettingsSwitchRow(
            icon = MuseIcons.layoutColumns,
            title = stringResource(R.string.settings_chat_bubble_full_width),
            subtitle = stringResource(R.string.settings_chat_bubble_full_width_subtitle),
            checked = prefs.bubbleFullWidth,
            onCheckedChange = { v -> update { it.copy(bubbleFullWidth = v) } },
        )
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.user,
            title = stringResource(R.string.settings_chat_show_user_avatar),
            subtitle = stringResource(R.string.settings_chat_show_user_avatar_subtitle),
            checked = prefs.showUserAvatar,
            onCheckedChange = { v -> update { it.copy(showUserAvatar = v) } },
        )
        SettingsGroupDivider()
        // Cuatro niveles de filete de burbujas: cuadrado/esquina redondeada/esquina redondeada grande/cápsula
        val radiusOptions =
            listOf(
                stringResource(R.string.settings_bubble_radius_none),
                stringResource(R.string.settings_bubble_radius_small),
                stringResource(R.string.settings_bubble_radius_large),
                stringResource(R.string.settings_bubble_radius_pill),
            )
        val radiusValues = listOf(0, 8, 20, 28)
        val selectedRadiusIndex =
            radiusValues.indexOf(prefs.bubbleRadius)
                .coerceIn(0, radiusValues.lastIndex)
        SettingsSegmentedRow(
            icon = MuseIcons.square,
            title = stringResource(R.string.settings_bubble_radius_title),
            subtitle = stringResource(R.string.settings_bubble_radius_subtitle),
            options = radiusOptions,
            selectedIndex = selectedRadiusIndex,
            onSelectedChange = { idx ->
                update { it.copy(bubbleRadius = radiusValues[idx]) }
            },
        )
    }
}

/**
 * Tarjetas e interfaces generativas: si se debe permitir que los modelos utilicen tarjetas interactivas para alojar contenido,
 * y si priorizar proactivamente las tarjetas sobre los textos largos.
 */
@Composable
private fun GenerativeUiSection() {
    val settings: SettingsRepository = koinInject()
    val prefs by settings.chatPreferencesFlow.collectAsStateWithLifecycle(initialValue = ChatPreferences())
    val scope = rememberCoroutineScope()

    fun update(block: (ChatPreferences) -> ChatPreferences) {
        scope.launch { settings.updateChatPreferences(block) }
    }

    SettingsGroup {
        SettingsSwitchRow(
            icon = MuseIcons.sparkle,
            title = stringResource(R.string.settings_chat_generative_ui),
            subtitle = stringResource(R.string.settings_chat_generative_ui_subtitle),
            checked = prefs.generativeUiEnabled,
            onCheckedChange = { v -> update { it.copy(generativeUiEnabled = v) } },
        )
        // Es un nivel más activo que "Permitir" y está activado de forma predeterminada.
        if (prefs.generativeUiEnabled) {
            SettingsGroupDivider()
            SettingsSwitchRow(
                icon = MuseIcons.sparkle,
                title = stringResource(R.string.settings_chat_prefer_card_output),
                subtitle = stringResource(R.string.settings_chat_prefer_card_output_subtitle),
                checked = prefs.preferCardOutput,
                onCheckedChange = { v -> update { it.copy(preferCardOutput = v) } },
            )
        }
    }
}

/**
 * pagina de segundo nivel:Acerca de
 * AboutSection Originalmente private,Reescribir en línea aquí(Número de versión + entrada de licencia de código abierto).
 *
 * P3-14:Nuevo"retroalimentación"entrada — llamar al correo Intent(Dirección de entrega de precarga/Tema/Información del dispositivo),Cuando no hay solicitud por correo
 *       fallback para copiar el contenido de los comentarios al portapapeles + mensaje de brindis.
 * P3-15:Nuevo"Buscar actualizaciones"botón — Solicitud asincrónica GitHub Releases API,Comparar con la versión localNombre,
 *       Utilice MuseDialog para notificarle cuando haya una nueva versión.(Después de la confirmación, el navegador abre la página de lanzamiento.),Últimos consejos para usar MuseAlertDialog,
 *       Los errores de red se indican con Toast.
 */
@Composable
fun SettingsAboutPage(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit = {},
    // v2.x: Entrada a la página de configuración de informes de fallos(Opcional·La configuración de informes respetuosa con la privacidad está desactivada de forma predeterminada)
    onOpenCrashReport: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val updateNotifier: UpdateNotifier = koinInject()
    val scope = rememberCoroutineScope()
    val updateCheckEnabled by settings.updateCheckEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val versionName = remember {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull() ?: "0.26"
    }
    val versionCode = remember {
        runCatching {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            // Utilice PackageInfoCompat.getLongVersionCode en su lugar PackageInfo.longVersionCode
            // (longVersionCode Campo requerido API 28,pero minSdk=26;PackageInfoCompat Compatibilidad de versiones internas)
            PackageInfoCompat.getLongVersionCode(packageInfo)
        }.getOrNull() ?: 26L
    }

    // Los dos portales de actualización manual comparten el mismo notificador para garantizar que el caché del banner de la página de inicio, las notificaciones y los resultados de las comprobaciones tengan el mismo calibre.
    var checking by remember { mutableStateOf(false) }
    var newVersion by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var upToDate by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(title = stringResource(R.string.section_about), onBack = onBack) {
        // Información de la aplicación
        item {
            SectionLabel(stringResource(R.string.settings_about_app_info))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                ) {
                    Text(
                        text = "muse",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_about_version_full, versionName, versionCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // v1.66: Cambiar al valor dinámico de BuildConfig,No más codificación "debug"
                        text = "Build: ${if (BuildConfig.DEBUG) "debug" else "release"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        // P3-14: Portal de comentarios — llamar al correo Intent,fallback al portapapeles
        item {
            SectionLabel(stringResource(R.string.settings_about_feedback_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_feedback),
                    onClick = { sendFeedback(context, versionName) },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_join_qq_group),
                    subtitle = stringResource(R.string.settings_about_join_qq_group_subtitle),
                    onClick = { openQQGroup(context) },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    title = stringResource(R.string.settings_crash_page_title),
                    subtitle = stringResource(R.string.settings_crash_enable_subtitle),
                    onClick = onOpenCrashReport,
                ) {
                    ChevronRight()
                }
            }
        }
        // P3-15: Buscar actualizaciones — Solicitud asincrónica GitHub Releases
        item {
            SectionLabel(stringResource(R.string.settings_about_update_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_check_update),
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val result = try {
                                updateNotifier.checkManually(context)
                            } finally {
                                checking = false
                            }
                            when (result) {
                                is UpdateCheckResult.NewVersion -> newVersion = result.release
                                UpdateCheckResult.UpToDate -> upToDate = true
                                is UpdateCheckResult.Failed -> MuseToast.show(
                                    context.getString(R.string.settings_about_update_check_failed, result.message),
                                )
                                UpdateCheckResult.Skipped -> Unit
                            }
                        }
                    },
                ) {
                    if (checking) {
                        MuseSpinner(
                            size = 20.dp,
                        )
                    } else {
                        ChevronRight()
                    }
                }
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = MuseIcons.refresh,
                    title = stringResource(R.string.settings_about_auto_update),
                    subtitle = stringResource(R.string.settings_about_auto_update_subtitle),
                    checked = updateCheckEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveUpdateCheckEnabled(v) } },
                )
            }
        }
        item {
            SectionLabel(stringResource(R.string.settings_about_legal_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.about_oss_licenses),
                    onClick = onOpenLicenses,
                ) {
                    ChevronRight()
                }
            }
        }
        // v1.119: Agrupación de almacenes de código abierto — Página de inicio del almacén / Estrella / Comentarios sobre problemas(todos saltan GitHub)
        item {
            SectionLabel(stringResource(R.string.settings_about_repo_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_repo_home),
                    onClick = { openUrl(context, "https://github.com/Zer0Qing/Muse") },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_star),
                    onClick = { openUrl(context, "https://github.com/Zer0Qing/Muse") },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_issues),
                    onClick = { openUrl(context, "https://github.com/Zer0Qing/Muse/issues") },
                ) {
                    ChevronRight()
                }
            }
        }
    }

    // P3-15: Ventana emergente de nueva versión encontrada — Después de la confirmación, utilice su navegador para abrir la página de descarga del sitio web oficial.
    newVersion?.let { version ->
        MuseDialog(
            onDismissRequest = { newVersion = null },
            title = stringResource(R.string.settings_about_new_version_title),
            content = {
                Text(
                    text = stringResource(R.string.settings_about_new_version_desc, version.tagName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            },
            confirmText = stringResource(R.string.settings_about_download),
            onConfirm = {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateNotifier.OFFICIAL_DOWNLOAD_PAGE))
                runCatching { context.startActivity(browserIntent) }
                newVersion = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { newVersion = null },
        )
    }

    // P3-15: Ya aparece el aviso de la última versión.
    if (upToDate) {
        MuseAlertDialog(
            onDismissRequest = { upToDate = false },
            title = stringResource(R.string.settings_about_check_update),
            message = stringResource(R.string.settings_about_up_to_date),
            confirmText = stringResource(R.string.settings_about_got_it),
        )
    }
}

/**
 * v1.119: abierto externo URL — Inicie el navegador con ACTION_VIEW,Aviso de brindis en caso de falla.
 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        if (intent.resolveActivity(context.packageManager) == null) {
            error("No activity can handle URL")
        }
        context.startActivity(intent)
    }.onFailure {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.settings_about_repo_home), url),
        )
        MuseToast.show(context.getString(R.string.settings_about_url_copied))
    }
}

/**
 * Abrir tarjeta de grupo QQ;Cuando QQ no esté instalado, copie el número de grupo al portapapeles y solicite.
 */
private fun openQQGroup(context: Context) {
    val uri = Uri.parse(
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=905451314" +
            "&card_type=group&source=external&jump_from=webapi",
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.tencent.mobileqq")
        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        if (intent.resolveActivity(context.packageManager) == null) {
            error("QQ is not installed")
        }
        context.startActivity(intent)
    }.onFailure {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(
                context.getString(R.string.settings_about_join_qq_group),
                "905451314",
            ),
        )
        MuseToast.show(context.getString(R.string.settings_about_qq_not_installed))
    }
}

/**
 * P3-14: Enviar comentarios — Llame a la intención del correo electrónico y complete previamente la dirección del destinatario/Tema/Información del dispositivo,
 * Cuando no hay una aplicación de correo electrónico, el respaldo copiará el contenido de los comentarios al portapapeles + mensaje de Toast.
 */
private fun sendFeedback(context: Context, versionName: String) {
    val deviceInfo = buildString {
        appendLine(
            context.getString(
                R.string.settings_feedback_android_version,
                android.os.Build.VERSION.RELEASE,
                android.os.Build.VERSION.SDK_INT,
            ),
        )
        appendLine(context.getString(R.string.settings_feedback_device_model, android.os.Build.MANUFACTURER, android.os.Build.MODEL))
        appendLine(context.getString(R.string.settings_feedback_app_version, versionName))
    }
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf("2119280571@qq.com"))
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_feedback_subject))
        putExtra(Intent.EXTRA_TEXT, deviceInfo)
    }
    // arreglo de fallas: No requerido para el contexto de actividad NEW_TASK,Unificar métodos de seguridad
    runCatching {
        io.zer0.muse.util.ShareIntentHelper.startChooserSafely(
            context,
            intent,
            context.getString(R.string.settings_feedback_chooser_title),
        )
    }.onFailure {
        // Aplicación sin correo:fallback al portapapeles
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val feedbackText = buildString {
            appendLine(context.getString(R.string.settings_feedback_recipient))
            appendLine(context.getString(R.string.settings_feedback_subject_label))
            appendLine()
            appendLine(deviceInfo)
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_feedback_subject), feedbackText))
        MuseToast.show(context.getString(R.string.settings_feedback_no_email_app), 3500)
    }
}
