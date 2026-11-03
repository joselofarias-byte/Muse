package io.zer0.muse.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.plugin.ProviderPlugin
import io.zer0.ai.plugin.ProviderPluginRegistry
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.plugin.ConfigItem
import io.zer0.muse.data.plugin.ConfigItemType
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginSecurityGate
import io.zer0.muse.data.plugin.PluginVersion
import io.zer0.muse.data.plugin.market.CatalogRefreshResult
import io.zer0.muse.data.plugin.market.InstallPlanResult
import io.zer0.muse.data.plugin.market.PluginCatalogEntry
import io.zer0.muse.data.plugin.market.PluginCatalogRepository
import io.zer0.muse.data.plugin.market.PluginDownloadClient
import io.zer0.muse.data.plugin.market.PluginInstallCoordinator
import io.zer0.muse.data.plugin.market.PluginInstallPlan
import io.zer0.muse.data.plugin.market.PluginMarketDefaults
import io.zer0.muse.data.plugin.market.PluginMarketInstaller
import io.zer0.muse.data.plugin.market.PluginMarketTrustRoots
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseIndeterminateProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.koin.compose.koinInject
import java.io.File

/**
 * Unifique la página "Administración de complementos" (v1.0.62).
 *
 * Combina dos tipos de complementos:
 *  - Complementos externos (.muse-plugin / ZIP paquete):PluginManager SkillExecutor ejecuta la gestión y el registro de herramientas en la tabla de habilidades
 *  - Provider complemento (JSON configuración):ProviderPluginRegistry Gestión, se puede convertir a configuración de proveedor.
 *
 * Entrada de importación unificada: identifica automáticamente los tipos de archivos (ZIP cabeza PK → complementos externos;JSON → Provider complemento).
 */
@Composable
fun PluginManagePage(onBack: () -> Unit) {
    val pluginManager: PluginManager = koinInject()
    val registry: ProviderPluginRegistry = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    var externalPlugins by remember { mutableStateOf(pluginManager.list()) }
    var providerPlugins by remember { mutableStateOf(registry.list()) }
    var importing by remember { mutableStateOf(false) }

    /** UI-FIX: Tres niveles de páginas de complementos —— 0 Instalado / 1 Mercado / 2 Gestión de Fideicomisos. */
    var pluginTab by rememberSaveable { mutableIntStateOf(0) }
    // v2.6.13: Interruptor maestro enchufable. Después de cerrar todas las herramientas complementarias/Hook/No se realiza ninguna activación, pero se conservan la instalación y la configuración.
    val pluginsEnabled by settings.pluginsGloballyEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    var pendingExternalInstall by remember { mutableStateOf<PendingExternalInstall?>(null) }
    var pendingDeleteExternal by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }
    var pendingDeleteProvider by remember { mutableStateOf<ProviderPlugin?>(null) }

    /** Esperando confirmación del usuario“Firmar y habilitar”El asistente escribe el borrador por sí solo; no firmará ni escribirá la raíz de confianza antes de la confirmación. */
    var pendingSignDraft by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }

    // ── Phase 5: Mercado de complementos (exploración del directorio de firmas + instalación con un solo clic)──
    // La URL del directorio/raíz de confianza se almacena en el DataStore; el almacén almacena en caché el directorio y la secuencia aceptada, rechazando el respaldo y la caducidad.
    // v2.0.1: El componente de mercado se reinyecta en el singleton de Koin — y herramientas de backend(plugin_market_search/install)Compartir
    // Se utilizan la misma caché de directorio y la misma secuencia aceptada para evitar la bifurcación de la protección alternativa causada por instancias duales.
    val marketTrustRoots: PluginMarketTrustRoots = koinInject()
    val marketSettings = marketTrustRoots.settings
    val marketRepository: PluginCatalogRepository = koinInject()
    val catalogRootKeys by marketTrustRoots.keys.collectAsStateWithLifecycle()
    // Reconstrucción del coordinador después de un cambio de raíz de confianza: la verificación de firma del directorio siempre debe utilizar la raíz de confianza configurada actualmente.
    val marketCoordinator = remember(marketRepository, catalogRootKeys) {
        PluginInstallCoordinator(
            trustRootKeys = catalogRootKeys,
            lastAcceptedSequence = { catalogId -> marketRepository.lastAcceptedSequence(catalogId) },
        )
    }
    // Las descargas solo se escriben en la caché privada de la aplicación, nunca directamente en el directorio del complemento.
    val marketDownloadClient = remember(context) {
        PluginDownloadClient(stagingDir = File(context.cacheDir, "plugin_market_staging"))
    }
    val marketInstaller = remember(pluginManager) { PluginMarketInstaller(pluginManager) }

    /** null = Se están leyendo los ajustes; cadena vacía = Explícitamente no configurado. */
    var catalogUrl by remember { mutableStateOf<String?>(null) }

    /** Dirección de directorio definida por el usuario; vacío significa que se está utilizando el directorio oficial incorporado. */
    var catalogUrlOverride by remember { mutableStateOf("") }
    var marketEntries by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var marketRefreshing by remember { mutableStateOf(false) }
    var marketError by remember { mutableStateOf<String?>(null) }
    var showMarketSettings by remember { mutableStateOf(false) }
    var marketInstall by remember { mutableStateOf<MarketInstallJob?>(null) }
    var marketInstallJob by remember { mutableStateOf<Job?>(null) }
    // La tarjeta hizo clic en la cuadrícula del mercado (null = Detalles no abiertos)
    var marketDetailEntry by remember { mutableStateOf<PluginCatalogEntry?>(null) }

    /** Solicitud de reversión pendiente: ID del complemento y versión de destino. */
    var rollbackRequest by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ── P0-9: gestión de confianza(Retirar entrada)──
    /** Lista de editores de confianza(La misma instancia del almacén de confianza que la verificación de instalación). */
    var trustedPublishers by remember { mutableStateOf(pluginManager.trustedPublishers()) }

    /** La identificación del emisor para la cual se debe confirmar la revocación. */
    var revokePublisherId by remember { mutableStateOf<String?>(null) }

    fun refreshMarketCatalog() {
        val url = catalogUrl.orEmpty()
        if (url.isBlank() || marketRefreshing) return
        marketRefreshing = true
        scope.launch {
            try {
                when (val result = marketRepository.refresh(url)) {
                    CatalogRefreshResult.NotConfigured -> Unit
                    is CatalogRefreshResult.Updated -> {
                        marketEntries = result.signedCatalog.payload.entries
                        marketError = null
                    }
                    is CatalogRefreshResult.Rejected -> marketError = result.reason
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                marketError = error.message ?: context.getString(R.string.plugin_market_load_failed)
            } finally {
                marketRefreshing = false
            }
        }
    }

    /** Descartar los paquetes preparados que aún no hayan sido confirmados; Los paquetes que se han enviado para su instalación no se eliminan aquí. */
    fun discardMarketPlan() {
        marketInstall?.plan?.let { marketInstaller.discard(it) }
    }

    /** solo si [entryId] La fase de actualización es mientras la tarea de instalación actual aún está en progreso; la antigua rutina no debe sobrescribir el nuevo estado de la tarea. */
    fun updateMarketInstall(
        entryId: String,
        phase: MarketInstallPhase,
        plan: PluginInstallPlan? = null,
        trustPublisher: Boolean = false,
    ): Boolean {
        val current = marketInstall ?: return false
        if (current.entryId != entryId) return false
        marketInstall = current.copy(
            phase = phase,
            plan = plan ?: current.plan,
            trustPublisher = if (plan != null) trustPublisher else current.trustPublisher,
        )
        return true
    }

    fun dismissMarketInstall() {
        marketInstallJob?.cancel()
        marketInstallJob = null
        discardMarketPlan()
        marketInstall = null
    }

    /** Descargar → autorización de seguridad → Generar el plano de instalación; En este momento solo se muestra la vista previa y el directorio del complemento aún no se ha escrito. */
    fun startMarketInstall(entry: PluginCatalogEntry) {
        marketInstallJob?.cancel()
        discardMarketPlan()
        marketInstall = MarketInstallJob(entry.id, MarketInstallPhase.Downloading)
        marketInstallJob = scope.launch {
            var staged: File? = null
            var planCreated = false
            try {
                val catalog = marketRepository.currentCatalog()
                    ?: throw IllegalStateException(context.getString(R.string.plugin_market_state_invalid))
                val download = withContext(Dispatchers.IO) { marketDownloadClient.download(entry) }
                val artifact = download.artifact
                    ?: throw IllegalStateException(download.error ?: context.getString(R.string.plugin_download_failed))
                staged = artifact.file
                updateMarketInstall(entry.id, MarketInstallPhase.Preparing)
                val review = withContext(Dispatchers.IO) { pluginManager.reviewFromFile(artifact.file) }
                    .getOrElse { error ->
                        throw IllegalStateException(error.message ?: context.getString(R.string.plugin_review_failed))
                    }
                if (!review.allowed) {
                    throw IllegalStateException(
                        review.reason ?: context.getString(R.string.muse_plugins_security_rejected),
                    )
                }
                if (review.signature.status == PluginSecurityGate.SignatureStatus.UNSIGNED) {
                    throw IllegalStateException(context.getString(R.string.muse_plugins_market_unsigned_package))
                }
                val plan = when (
                    val planResult = marketCoordinator.preparePlan(
                        signedCatalog = catalog,
                        entryId = entry.id,
                        stagedFile = artifact.file,
                        artifactSha256 = artifact.sha256,
                        reviewedPackage = PluginInstallCoordinator.PluginPackageReview(
                            preview = review.preview,
                            contentSha256 = review.preview.contentSha256,
                        ),
                    )
                ) {
                    is InstallPlanResult.Ready -> planResult.plan
                    is InstallPlanResult.Rejected -> throw IllegalStateException(planResult.reason)
                }
                planCreated = true
                val shown = updateMarketInstall(
                    entryId = entry.id,
                    phase = MarketInstallPhase.AwaitingConfirm,
                    plan = plan,
                    // El usuario debe hacer clic explícitamente en los editores desconocidos en el cuadro de confirmación.“Confía en el editor e instala”.
                    trustPublisher = plan.preview.signatureStatus ==
                        PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
                )
                // Esta tarea ha sido reemplazada por una nueva solicitud de instalación: el plan no se mostrará, limpie el paquete preparado inmediatamente.
                if (!shown) marketInstaller.discard(plan)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                updateMarketInstall(
                    entry.id,
                    MarketInstallPhase.Failed(
                        error.message ?: context.getString(R.string.plugin_install_failed),
                    ),
                )
            } finally {
                // Plan no generado (descargar/revisión/Limpie el paquete preparado cuando la verificación falle o se cancele).
                if (!planCreated) runCatching { staged?.delete() }
            }
        }
    }

    /** Después de que el usuario confirma, se verifica dos veces y se envía de forma atómica; la marca de confianza del emisor desconocido se fija cuando se genera el plan. */
    fun confirmMarketInstall() {
        val pending = marketInstall ?: return
        val plan = pending.plan ?: return
        if (pending.phase == MarketInstallPhase.Installing) return
        marketInstall = pending.copy(phase = MarketInstallPhase.Installing)
        marketInstallJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                marketInstaller.confirmAndInstall(plan, pending.trustPublisher)
            }
            marketInstaller.discard(plan)
            result
                .onSuccess {
                    externalPlugins = pluginManager.list()
                    if (marketInstall?.entryId == plan.entry.id) marketInstall = null
                    MuseToast.show(context.getString(R.string.muse_plugins_confirmed))
                }
                .onFailure { error ->
                    updateMarketInstall(
                        plan.entry.id,
                        MarketInstallPhase.Failed(
                            error.message ?: context.getString(R.string.plugin_install_failed),
                        ),
                    )
                }
        }
    }

    LaunchedEffect(Unit) {
        // Lea primero la raíz de confianza y luego cargue la caché: la verificación de la caché debe utilizar la raíz de confianza actualmente configurada.
        marketTrustRoots.reload()
        val url = marketSettings.catalogUrl()
        catalogUrl = url
        catalogUrlOverride = marketSettings.catalogUrlOverride()
        val cached = marketRepository.loadCache()
        marketEntries = cached.entries
        marketError = cached.error
        if (url.isNotBlank()) refreshMarketCatalog()
    }

    /** Importación unificada:PK cabeza → Paquete de complemento externo; de lo contrario, lo procesa el complemento JSON Provider. */
    suspend fun importFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            // P2-28: Límite de tamaño — Los paquetes de complementos externos los proporciona PluginManager.withTempPluginFile
            // copyLimited(MAX_PLUGIN_PACKAGE_BYTES) Limitación de corriente;Provider JSON Copiar etapas desde abajo
            // 1MB Limitación actual + registro.loadFromFile seguro doble de límite superior incorporado,Eliminar OOM.
            val header = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    byteArrayOf(input.read().toByte(), input.read().toByte())
                }
            }.getOrNull() ?: error(context.getString(R.string.plugin_file_read_failed))
            val isZip = header.size == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            if (isZip) {
                pluginManager.reviewFromUri(uri)
                    .onSuccess { decision ->
                        withContext(Dispatchers.Main) {
                            if (decision.allowed) {
                                pendingExternalInstall = PendingExternalInstall(uri, decision.preview)
                            } else {
                                MuseToast.show(
                                    context.getString(
                                        R.string.muse_plugins_review_failed,
                                        decision.reason ?: context.getString(R.string.muse_plugins_security_rejected),
                                    ),
                                    3500,
                                )
                            }
                        }
                    }
                    .onFailure { e ->
                        val msg = e.message ?: e::class.simpleName ?: "unknown"
                        withContext(Dispatchers.Main) {
                            MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                        }
                    }
                return@withContext
            }
            val tempFile = File(context.cacheDir, "plugin_import_${System.nanoTime()}.json")
            try {
                // P2-28: La fase de replicación también es limitada.,JSON de gran tamaño rechazado antes de escribir en el disco,Evite la inundación de caché + OOM
                val maxJsonBytes = io.zer0.ai.plugin.ProviderPluginRegistry.MAX_PROVIDER_JSON_BYTES
                var total = 0L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxJsonBytes) {
                                tempFile.delete()
                                error(context.getString(R.string.plugin_json_too_large, maxJsonBytes / 1024))
                            }
                            output.write(buf, 0, n)
                        }
                    }
                } ?: error(context.getString(R.string.plugin_file_read_failed))
                registry.loadFromFile(tempFile)
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            providerPlugins = registry.list()
                            MuseToast.show(context.getString(R.string.provider_plugins_import_success))
                        }
                    }
                    .onFailure { e ->
                        val msg = e.message ?: e::class.simpleName ?: "unknown"
                        withContext(Dispatchers.Main) {
                            MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                        }
                    }
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    fun convertToProvider(plugin: ProviderPlugin) {
        scope.launch {
            val config = registry.toProviderConfig(plugin)
            settings.addProvider(config)
            MuseToast.show(context.getString(R.string.provider_plugins_import_success))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            resultOf { importFromUri(uri) }
                .onError { msg, t -> Logger.w("PluginManagePage", "importFromUri failed: $msg", t) }
            importing = false
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.muse_plugins_manage),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            MuseFloatingButton(
                icon = MuseIcons.plus,
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                contentDescription = stringResource(R.string.provider_plugins_import),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .then(
                    if (widthClass == WindowWidthClass.Expanded) Modifier.widthIn(max = 720.dp) else Modifier,
                ),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            // UI-FIX: agrupamiento superior —— Instalado/Mercado/Gestión de confianza, ya no apilar tres piezas en una lista larga
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseCapsuleTab(
                        tabs = listOf(
                            stringResource(R.string.muse_plugins_tab_installed),
                            stringResource(R.string.muse_plugins_market_title),
                            stringResource(R.string.muse_plugins_trust_title),
                        ),
                        selectedIndex = pluginTab,
                        onSelect = { pluginTab = it },
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
                }
            }
            // v2.6.13: Interruptor maestro enchufable. Colóquelo encima de la subcategoría en lugar de enterrarlo en una pestaña. ——
            // Su función es "detener pérdidas con un clic cuando hay un problema con el complemento". Los usuarios no deben encontrar la pestaña correcta antes de usarla.
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.muse_plugins_global_switch_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.muse_plugins_global_switch_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MuseSwitch(
                        checked = pluginsEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settings.savePluginsGloballyEnabled(enabled) }
                        },
                    )
                }
            }
            if (pluginTab == 1) {
                // ── Área de mercado de complementos (Phase 5)──
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.muse_plugins_market_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            // LAYOUT-01: Row El texto interior se amplía + se limita a una línea para evitar que se actualice a la derecha cuando se amplía el tamaño de fuente./Más botones apretados en una fila
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (marketRefreshing) {
                            MuseSpinner(
                                size = MuseIconSizes.iconSmall,
                            )
                        } else if (catalogUrl?.isNotBlank() == true) {
                            MuseTactileButton(
                                icon = MuseIcons.refresh,
                                onClick = { refreshMarketCatalog() },
                                contentDescription = stringResource(R.string.muse_plugins_market_refresh),
                            )
                        }
                        MuseTactileButton(
                            icon = MuseIcons.sliders,
                            onClick = { showMarketSettings = true },
                            contentDescription = stringResource(R.string.muse_plugins_market_configure),
                        )
                    }
                }
                when {
                    // Cargando configuración: no se muestra ninguna“No configurado”, ni pretender que el directorio esté disponible.
                    catalogUrl == null -> item { MarketLoadingRow() }
                    catalogUrl.isNullOrBlank() -> item {
                        MuseEmptyState(
                            icon = MuseIcons.puzzle,
                            title = stringResource(R.string.muse_plugins_market_not_configured),
                            subtitle = stringResource(R.string.muse_plugins_market_not_configured_hint),
                            actionText = stringResource(R.string.muse_plugins_market_configure),
                            onAction = { showMarketSettings = true },
                        )
                    }
                    marketEntries.isEmpty() && marketRefreshing -> item { MarketLoadingRow() }
                    marketEntries.isEmpty() -> item {
                        val failure = marketError
                        MuseEmptyState(
                            icon = if (failure != null) MuseIcons.alertCircle else MuseIcons.puzzle,
                            title = failure ?: stringResource(R.string.muse_plugins_market_empty),
                            subtitle = if (failure != null) {
                                stringResource(R.string.muse_plugins_market_error_hint)
                            } else {
                                null
                            },
                            actionText = if (failure != null) stringResource(R.string.common_retry) else null,
                            onAction = if (failure != null) ({ refreshMarketCatalog() }) else null,
                        )
                    }
                    else -> {
                        marketError?.let { reason ->
                            item { MarketErrorRow(reason = reason, onRetry = { refreshMarketCatalog() }) }
                        }
                        // Cuadrícula de dos columnas: hay dos tarjetas enchufables en cada fila. Haga clic para ingresar a la página de detalles y luego instálela (la lista grande original ocupa demasiado espacio).
                        // Tenga cuidado de no utilizar LazyVerticalGrid: incrustará el LazyColumn actual y provocará un colapso de altura ilimitado.
                        // En este caso, están dispuestas en parejas según "dos celdas por fila", lo que es visualmente equivalente y estructuralmente seguro.
                        items(marketEntries.chunked(2), key = { "market_row_${it.first().id}" }) { pair ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                                verticalAlignment = Alignment.Top,
                            ) {
                                pair.forEach { entry ->
                                    MarketEntryCell(
                                        entry = entry,
                                        installedVersion = externalPlugins.firstOrNull { it.id == entry.id }?.version,
                                        phase = marketInstall?.takeIf { it.entryId == entry.id }?.phase,
                                        onClick = { marketDetailEntry = entry },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // ── P0-9: gestión de confianza(Emisores confiables/Directorio Raíz de confianza)──
            }
            if (pluginTab == 2) {
                item { SectionHeader(stringResource(R.string.muse_plugins_trust_title)) }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        Text(
                            text = stringResource(R.string.muse_plugins_trust_publishers_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (trustedPublishers.isEmpty()) {
                            Text(
                                text = stringResource(R.string.muse_plugins_trust_publishers_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            trustedPublishers.forEach { publisher ->
                                TrustedPublisherRow(
                                    publisherId = publisher.publisherId,
                                    fingerprint = publisher.fingerprint,
                                    onRevoke = { revokePublisherId = publisher.publisherId },
                                )
                            }
                        }
                        Spacer(Modifier.height(MusePaddings.contentGap))
                        Text(
                            text = stringResource(R.string.muse_plugins_trust_catalog_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val userRootKeys = catalogRootKeys.keys - PluginMarketDefaults.catalogRootKeys.keys
                        if (userRootKeys.isEmpty()) {
                            Text(
                                text = stringResource(R.string.muse_plugins_trust_catalog_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            userRootKeys.forEach { keyId ->
                                CatalogRootKeyRow(
                                    keyId = keyId,
                                    onRemove = {
                                        scope.launch {
                                            marketSettings.removeCatalogRootKey(keyId)
                                            // P0-9: Borrar entradas de caché de mercado después de la revocación de la raíz del directorio,Evite restos de antiguas entradas de verificación de raíz de confianza
                                            marketRepository.clearCache()
                                            marketTrustRoots.reload()
                                            marketEntries = emptyList()
                                            catalogUrl = marketSettings.catalogUrl()
                                            MuseToast.show(context.getString(R.string.muse_plugins_trust_catalog_removed))
                                            refreshMarketCatalog()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (pluginTab == 0) {
                // ── Área de complementos externos ──
                item { SectionHeader(stringResource(R.string.muse_plugins_external)) }
                if (importing && externalPlugins.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            MuseSpinner(
                                size = MuseIconSizes.iconMedium,
                            )
                        }
                    }
                } else if (externalPlugins.isEmpty()) {
                    item {
                        MuseEmptyState(
                            icon = MuseIcons.puzzle,
                            title = stringResource(R.string.muse_plugins_empty_hint),
                        )
                    }
                } else {
                    items(externalPlugins, key = { "ext_${it.id}" }) { plugin ->
                        val verified = pluginManager.loadVerifiedPlugin(plugin.id)
                        val configEntries = verified?.let {
                            pluginManager.getPluginConfigsWithDefaults(it.manifest)
                        }
                        val panelHtml = remember(plugin.id, plugin.version) {
                            if (plugin.enabled) pluginManager.loadPluginPanel(plugin.id) else null
                        }
                        InstalledPluginRow(
                            plugin = plugin,
                            configEntries = configEntries,
                            panelHtml = panelHtml,
                            onConfigChanged = { key, value ->
                                scope.launch {
                                    pluginManager.setPluginConfig(plugin.id, key, value)
                                    externalPlugins = pluginManager.list()
                                }
                            },
                            onToggle = {
                                scope.launch {
                                    pluginManager.setEnabled(plugin.id, !plugin.enabled)
                                    externalPlugins = pluginManager.list()
                                }
                            },
                            onConfirm = if (
                                plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED ||
                                plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED
                            ) {
                                {
                                    scope.launch {
                                        pluginManager.confirmInstallation(
                                            plugin.id,
                                            trustPublisher = plugin.signatureStatus ==
                                                PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
                                        )
                                            .onSuccess {
                                                externalPlugins = pluginManager.list()
                                                MuseToast.show(context.getString(R.string.muse_plugins_confirmed))
                                            }
                                            .onFailure { error ->
                                                val msg = error.message ?: error::class.simpleName ?: "unknown"
                                                MuseToast.show(
                                                    context.getString(R.string.provider_plugins_import_failed, msg),
                                                    3500,
                                                )
                                            }
                                    }
                                }
                            } else {
                                null
                            },
                            onDelete = { pendingDeleteExternal = plugin },
                            // Borradores sin firmar realizados por el Asistente: solo se pueden ejecutar si están firmados y habilitados con la clave de autor nativa.
                            onSignAndEnable = if (
                                plugin.signatureStatus == PluginSecurityGate.SignatureStatus.UNSIGNED &&
                                !plugin.installationConfirmed
                            ) {
                                { pendingSignDraft = plugin }
                            } else {
                                null
                            },
                            rollbackVersion = pluginManager.listRetainedVersions(plugin.id)
                                .firstOrNull { it.version != plugin.version }
                                ?.version,
                            onRollback = { version -> rollbackRequest = plugin.id to version },
                        )
                    }
                }

                // ── Provider Área de complementos ──
                item {
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    SectionHeader(stringResource(R.string.provider_plugins_title))
                }
                if (providerPlugins.isEmpty()) {
                    item {
                        MuseEmptyState(
                            icon = MuseIcons.puzzle,
                            title = stringResource(R.string.provider_plugins_empty),
                        )
                    }
                } else {
                    items(providerPlugins, key = { "prov_${it.id}" }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            onDelete = { pendingDeleteProvider = plugin },
                            onConvert = { convertToProvider(plugin) },
                        )
                    }
                }
            }
        }
    }

    // Confirmación de instalación del complemento externo: la instalación solo se puede enviar después de demostrar capacidades, herramientas y resúmenes.
    pendingExternalInstall?.let { pending ->
        val preview = pending.preview
        val unknownAuthor = stringResource(R.string.muse_plugins_unknown_author)
        val noCapabilities = stringResource(R.string.muse_plugins_none)
        MuseDialog(
            onDismissRequest = { pendingExternalInstall = null },
            title = stringResource(R.string.muse_plugins_install_confirm_title, preview.name),
            content = { PluginInstallPreviewContent(preview) },
            confirmText = stringResource(
                when (preview.signatureStatus) {
                    PluginSecurityGate.SignatureStatus.UNSIGNED ->
                        R.string.muse_plugins_install_save_draft
                    PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED ->
                        R.string.muse_plugins_install_trust_publisher_action
                    else -> R.string.muse_plugins_install_confirm_action
                },
            ),
            onConfirm = {
                pendingExternalInstall = null
                scope.launch {
                    val result = if (
                        preview.signatureStatus == PluginSecurityGate.SignatureStatus.UNSIGNED
                    ) {
                        pluginManager.installDraftFromUri(pending.uri, preview)
                    } else {
                        pluginManager.installConfirmedFromUri(
                            pending.uri,
                            preview,
                            trustPublisher = preview.signatureStatus ==
                                PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
                        )
                    }
                    result
                        .onSuccess {
                            externalPlugins = pluginManager.list()
                            MuseToast.show(
                                context.getString(
                                    if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.UNSIGNED) {
                                        R.string.muse_plugins_draft_saved
                                    } else {
                                        R.string.muse_plugins_imported
                                    },
                                ),
                            )
                        }
                        .onFailure { error ->
                            val msg = error.message ?: error::class.simpleName ?: "unknown"
                            MuseToast.show(
                                context.getString(R.string.provider_plugins_import_failed, msg),
                                3500,
                            )
                        }
                }
            },
        )
    }

    // Confirmación de "Firmar y activar" del borrador escrito por el asistente: muestra la identidad del complemento y la lista de herramientas, y el usuario asiente antes de firmar y escribir la raíz de confianza.
    pendingSignDraft?.let { target ->
        val noTools = stringResource(R.string.muse_plugins_none)
        MuseDialog(
            onDismissRequest = { pendingSignDraft = null },
            title = stringResource(R.string.muse_plugins_sign_enable_title),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Text(
                        text = stringResource(
                            R.string.muse_plugins_sign_enable_summary,
                            target.name,
                            target.id,
                            target.version,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.muse_plugins_sign_enable_tools,
                            target.tools.joinToString(", ") { it.name }.ifBlank { noTools },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.muse_plugins_sign_enable_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmText = stringResource(R.string.muse_plugins_sign_enable_action),
            onConfirm = {
                pendingSignDraft = null
                scope.launch {
                    pluginManager.signAndEnableDraft(target.id)
                        .onSuccess {
                            externalPlugins = pluginManager.list()
                            MuseToast.show(context.getString(R.string.muse_plugins_sign_enable_success))
                        }
                        .onFailure { error ->
                            val msg = error.message ?: error::class.simpleName ?: "unknown"
                            MuseToast.show(
                                context.getString(R.string.muse_plugins_sign_enable_failed, msg),
                                3500,
                            )
                        }
                }
            },
        )
    }

    // Confirmación de instalación en el mercado: capacidades de vista previa/Herramientas/Emisor/Después de la toma de huellas dactilares, se realiza una verificación secundaria y un envío atómico.
    val marketPlan = marketInstall?.plan
    if (marketPlan != null && marketInstall?.phase == MarketInstallPhase.AwaitingConfirm) {
        val preview = marketPlan.preview
        MuseDialog(
            onDismissRequest = { dismissMarketInstall() },
            title = stringResource(R.string.muse_plugins_install_confirm_title, preview.name),
            content = { PluginInstallPreviewContent(preview) },
            confirmText = stringResource(
                if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED) {
                    R.string.muse_plugins_install_trust_publisher_action
                } else {
                    R.string.muse_plugins_install_confirm_action
                },
            ),
            onConfirm = { confirmMarketInstall() },
            onDismiss = { dismissMarketInstall() },
        )
    }

    // Progreso de la instalación del mercado: deshabilite el apagado durante la fase de envío para evitar informar erróneamente el envío atómico como cancelación.
    if (marketInstall?.phase == MarketInstallPhase.Installing) {
        MuseDialog(
            onDismissRequest = {},
            title = stringResource(R.string.muse_plugins_market_installing),
            content = {
                MuseIndeterminateProgressBar(
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissText = null,
        )
    }

    // Configuración del directorio de Market: de forma predeterminada, se utiliza el directorio oficial integrado en el paquete de instalación. Esto sólo se procesa aquí.“Cobertura personalizada”.
    marketDetailEntry?.let { entry ->
        val detailId = entry.id
        MarketEntryDetailSheet(
            entry = entry,
            installedVersion = externalPlugins.firstOrNull { it.id == detailId }?.version,
            phase = marketInstall?.takeIf { it.entryId == detailId }?.phase,
            onInstall = { startMarketInstall(entry) },
            onCancel = { dismissMarketInstall() },
            onRetry = { startMarketInstall(entry) },
            onDismissFailure = { dismissMarketInstall() },
            onDismiss = { marketDetailEntry = null },
        )
    }

    if (showMarketSettings) {
        var urlInput by remember { mutableStateOf(catalogUrlOverride) }
        var keyIdInput by remember {
            // La raíz de confianza oficial incorporada no se puede anular, por lo que el valor inicial de keyId solo toma el agregado por el usuario.
            mutableStateOf(
                (catalogRootKeys.keys - PluginMarketDefaults.catalogRootKeys.keys)
                    .firstOrNull()
                    .orEmpty(),
            )
        }
        var publicKeyInput by remember { mutableStateOf("") }
        var settingsError by remember { mutableStateOf<String?>(null) }
        MuseFormDialog(
            onDismissRequest = { showMarketSettings = false },
            title = stringResource(R.string.muse_plugins_market_settings_title),
            subtitle = stringResource(R.string.muse_plugins_market_settings_hint),
            onConfirm = {
                scope.launch {
                    val trimmedUrl = urlInput.trim()
                    if (trimmedUrl.isNotEmpty() && !trimmedUrl.startsWith("https://")) {
                        settingsError = context.getString(R.string.muse_plugins_market_invalid_url)
                        return@launch
                    }
                    marketSettings.saveCatalogUrl(trimmedUrl)
                    catalogUrlOverride = trimmedUrl
                    catalogUrl = marketSettings.catalogUrl()
                    if (publicKeyInput.isNotBlank()) {
                        val saved = marketSettings.saveCatalogRootKey(keyIdInput, publicKeyInput)
                        if (saved.isFailure) {
                            settingsError =
                                saved.exceptionOrNull()?.message
                                    ?: context.getString(R.string.plugin_public_key_invalid)
                            return@launch
                        }
                    }
                    marketTrustRoots.reload()
                    settingsError = null
                    showMarketSettings = false
                    MuseToast.show(context.getString(R.string.muse_plugins_market_settings_saved))
                    refreshMarketCatalog()
                }
            },
        ) {
            MuseTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.muse_plugins_market_url_label)) },
                singleLine = true,
            )
            MuseTextField(
                value = keyIdInput,
                onValueChange = { keyIdInput = it },
                label = { Text(stringResource(R.string.muse_plugins_market_key_id_label)) },
                singleLine = true,
            )
            MuseTextField(
                value = publicKeyInput,
                onValueChange = { publicKeyInput = it },
                label = { Text(stringResource(R.string.muse_plugins_market_public_key_label)) },
                minLines = 2,
                maxLines = 4,
            )
            settingsError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    // Confirmación de reversión: la reversión volverá a verificar la firma y el resumen de contenido de la copia histórica. El error no cambiará la versión actual.
    rollbackRequest?.let { (pluginId, version) ->
        MuseDialog(
            onDismissRequest = { rollbackRequest = null },
            title = stringResource(R.string.muse_plugins_rollback_title),
            content = {
                Text(
                    text = stringResource(R.string.muse_plugins_rollback_confirm, pluginId, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.muse_plugins_rollback_action, version),
            onConfirm = {
                rollbackRequest = null
                scope.launch {
                    pluginManager.rollbackTo(pluginId, version)
                        .onSuccess {
                            externalPlugins = pluginManager.list()
                            MuseToast.show(context.getString(R.string.muse_plugins_rollback_done))
                        }
                        .onFailure { error ->
                            MuseToast.show(
                                error.message ?: context.getString(R.string.plugin_rollback_failed),
                                3500,
                            )
                        }
                }
            },
        )
    }

    // P0-9: Revocar la confirmación del fideicomiso del emisor — Después de la revocación, los complementos del editor se desactivarán automáticamente.,Es necesario reafirmar la confianza antes de poder restablecerla.
    revokePublisherId?.let { publisherId ->
        MuseDialog(
            onDismissRequest = { revokePublisherId = null },
            title = stringResource(R.string.muse_plugins_trust_revoke_title),
            content = {
                Text(
                    text = stringResource(R.string.muse_plugins_trust_revoke_confirm, publisherId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.muse_plugins_trust_revoke_action),
            onConfirm = {
                revokePublisherId = null
                scope.launch {
                    pluginManager.revokePublisher(publisherId)
                    trustedPublishers = pluginManager.trustedPublishers()
                    externalPlugins = pluginManager.list()
                    MuseToast.show(context.getString(R.string.muse_plugins_trust_revoke_done))
                }
            },
        )
    }

    // Confirmación de eliminación del complemento externo
    pendingDeleteExternal?.let { target ->
        MuseDialog(
            onDismissRequest = { pendingDeleteExternal = null },
            title = stringResource(R.string.provider_plugins_delete),
            content = {
                Text(
                    // ST-02: Nombre + consecuencia + revocable(Reimportar)
                    text = stringResource(R.string.provider_plugins_delete_confirm_named, target.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.provider_plugins_delete),
            onConfirm = {
                scope.launch {
                    pluginManager.uninstall(target.id)
                    externalPlugins = pluginManager.list()
                }
                pendingDeleteExternal = null
            },
            destructive = true,
        )
    }
    // Provider Confirmación de eliminación del complemento
    pendingDeleteProvider?.let { target ->
        MuseDialog(
            onDismissRequest = { pendingDeleteProvider = null },
            title = stringResource(R.string.provider_plugins_delete),
            content = {
                Text(
                    // ST-02: Nombre + consecuencia + revocable(Reimportar)
                    text = stringResource(R.string.provider_plugins_delete_confirm_named, target.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.provider_plugins_delete),
            onConfirm = {
                scope.launch {
                    registry.unregister(target.id)
                    providerPlugins = registry.list()
                }
                pendingDeleteProvider = null
            },
            destructive = true,
        )
    }
}

private data class PendingExternalInstall(
    val uri: Uri,
    val preview: PluginSecurityGate.InstallPreview,
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
    )
}

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
private fun InstalledPluginRow(
    plugin: PluginManager.InstalledPlugin,
    onToggle: () -> Unit,
    onConfirm: (() -> Unit)?,
    onDelete: () -> Unit,
    /** Entrada \"Firmar y activar\" para borradores sin firmar;null Indica que la línea no está disponible (ni borrador ni confirmada). */
    onSignAndEnable: (() -> Unit)? = null,
    /** Versiones históricas a las que se puede retroceder;null Indica que no hay réplicas para revertir. */
    rollbackVersion: String? = null,
    onRollback: (String) -> Unit = {},
    /** B7-01: Elementos de configuración del complemento(Declaración+valor actual); nulo significa que el complemento no declara una configuración o que el manifiesto no se puede leer. */
    configEntries: List<Pair<ConfigItem, JsonElement?>>? = null,
    /** v1.0.92: Panel de interfaz de usuario del complemento HTML;null Indica no declarado.(Precarga de llamadas,Evite la lectura de discos en línea) */
    panelHtml: String? = null,
    onConfigChanged: suspend (String, JsonElement) -> Unit = { _, _ -> },
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.itemGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // ST-04: Mostrar solo la versión a los usuarios/Número de herramientas;ID del complemento/huella digital/sha256 En el área plegable "Información del desarrollador"
                    text = stringResource(R.string.plugin_summary_brief, plugin.version, plugin.tools.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = signatureStatusLabel(plugin.signatureStatus),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (!plugin.installationConfirmed) {
                    stringResource(R.string.muse_plugins_draft)
                } else {
                    stringResource(if (plugin.enabled) R.string.skill_enabled else R.string.skill_disabled)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (plugin.enabled && plugin.installationConfirmed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(MusePaddings.itemGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            if (plugin.installationConfirmed) {
                MuseCapsuleButton(
                    text = stringResource(if (plugin.enabled) R.string.skill_disabled else R.string.skill_enabled),
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    variant = IosCapsuleButtonVariant.Secondary,
                )
            } else if (onSignAndEnable != null) {
                // El asistente escribe el borrador: firmarlo y activarlo es la única forma de hacerlo ejecutable.
                MuseCapsuleButton(
                    text = stringResource(R.string.muse_plugins_sign_enable_action),
                    onClick = onSignAndEnable,
                    modifier = Modifier.weight(1f),
                    variant = IosCapsuleButtonVariant.Secondary,
                )
            } else if (onConfirm != null) {
                MuseCapsuleButton(
                    text = stringResource(
                        if (plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED) {
                            R.string.muse_plugins_install_trust_publisher_action
                        } else {
                            R.string.muse_plugins_install_confirm_action
                        },
                    ),
                    onClick = { onConfirm?.invoke() },
                    modifier = Modifier.weight(1f),
                    variant = IosCapsuleButtonVariant.Secondary,
                )
            } else {
                Text(
                    text = stringResource(R.string.muse_plugins_unsigned_draft_only),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = MusePaddings.itemGap),
                )
            }
            MuseCapsuleButton(
                text = stringResource(R.string.provider_plugins_delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                variant = IosCapsuleButtonVariant.Secondary,
            )
        }
        // Entrada de reversión: aparece solo cuando hay una copia histórica de la "versión no actual"; la verificación real se rehace durante la reversión.
        rollbackVersion?.let { version ->
            MuseCapsuleButton(
                text = stringResource(R.string.muse_plugins_rollback_action, version),
                onClick = { onRollback(version) },
                modifier = Modifier.fillMaxWidth(),
                variant = IosCapsuleButtonVariant.Secondary,
            )
        }
        // ST-04: La información del desarrollador está colapsada de forma predeterminada. — id/Huella digital del editor/El resumen del contenido es solo para revisión.,No molesta a los usuarios comunes
        var showDevInfo by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDevInfo = !showDevInfo }
                .padding(vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.muse_plugins_developer_info),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                // LAYOUT-01: Utilice el ancho del encabezado plegado + limite las filas para evitar que el ícono de galón a la derecha lo apriete en una fila.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (showDevInfo) {
                    MuseIcons.chevronUp
                } else {
                    MuseIcons.chevronDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
        if (showDevInfo) {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                Text(
                    text = stringResource(R.string.plugin_summary, plugin.id, plugin.version, plugin.tools.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plugin.publisherId.isNotBlank() && plugin.publisherKeyFingerprint.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.muse_plugins_publisher_fingerprint,
                            plugin.publisherId,
                            plugin.publisherKeyFingerprint,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plugin.contentSha256.isNotBlank()) {
                    Text(
                        text = plugin.contentSha256,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // v1.0.92: Panel de interfaz de usuario del complemento — Los complementos que declaran uiPanel proporcionan interfaces personalizadas de solo lectura
        if (panelHtml != null) {
            var showPanel by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showPanel = true }
                    .padding(vertical = MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.muse_plugins_open_panel),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Igual que arriba, el encabezado plegado está limitado a 1 línea
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = MuseIcons.chevronUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
            if (showPanel) {
                MuseDialog(
                    onDismissRequest = { showPanel = false },
                    title = plugin.name,
                    content = {
                        RichContentCard(
                            language = "html",
                            content = panelHtml,
                            showPreviewButton = false,
                        )
                    },
                    confirmText = stringResource(R.string.action_close),
                    onConfirm = { showPanel = false },
                )
            }
        }
        // B7-01: Formulario de configuración del complemento — Solo se muestra si el complemento declara un elemento de configuración y está habilitado
        if (!configEntries.isNullOrEmpty()) {
            val configScope = rememberCoroutineScope()
            var showConfig by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showConfig = !showConfig }
                    .padding(vertical = MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.muse_plugins_config),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Igual que arriba, el encabezado plegado está limitado a 1 línea
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (showConfig) {
                        MuseIcons.chevronUp
                    } else {
                        MuseIcons.chevronDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
            if (showConfig) {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    configEntries.forEach { (item, value) ->
                        ConfigFieldRow(
                            item = item,
                            currentValue = value,
                            onChanged = { newValue ->
                                configScope.launch {
                                    onConfigChanged(item.key, newValue)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun signatureStatusLabel(status: PluginSecurityGate.SignatureStatus): String = when (status) {
    PluginSecurityGate.SignatureStatus.VALID_TRUSTED -> stringResource(R.string.muse_plugins_signature_trusted)
    PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED -> stringResource(R.string.muse_plugins_signature_untrusted)
    PluginSecurityGate.SignatureStatus.UNSIGNED -> stringResource(R.string.muse_plugins_signature_unsigned)
    PluginSecurityGate.SignatureStatus.INVALID -> stringResource(R.string.muse_plugins_signature_invalid)
    PluginSecurityGate.SignatureStatus.UNSUPPORTED -> stringResource(R.string.muse_plugins_signature_unsupported)
}

/** B7-01: Fila de campo de configuración única —— Representar por tipo de declaración de manifiesto(string/boolean/number/select). */
@Composable
private fun ConfigFieldRow(item: ConfigItem, currentValue: JsonElement?, onChanged: (JsonElement) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        when (item.type) {
            ConfigItemType.boolean -> {
                val checked = (currentValue as? JsonPrimitive)?.booleanOrNull ?: false
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Text(
                        text = item.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: Configure el nombre del campo con ancho + límite de fila para evitar que el interruptor de la derecha lo comprima en una columna.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSwitch(
                        checked = checked,
                        onCheckedChange = { onChanged(JsonPrimitive(it)) },
                    )
                }
            }
            ConfigItemType.number -> {
                val committed = (currentValue as? JsonPrimitive)?.contentOrNull.orEmpty()
                var text by remember(currentValue) { mutableStateOf(committed) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Text(
                        text = item.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: Igual que arriba, configure el límite de nombre de campo en 1 línea
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTextField(
                        value = text,
                        onValueChange = { input -> text = input.filter { c -> c.isDigit() || c == '-' || c == '.' } },
                        singleLine = true,
                        modifier = Modifier
                            .width(120.dp)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && text != committed) {
                                    text.toDoubleOrNull()?.let { onChanged(JsonPrimitive(it)) }
                                }
                            },
                    )
                }
            }
            ConfigItemType.select -> {
                val selected = (currentValue as? JsonPrimitive)?.contentOrNull.orEmpty()
                MuseDropdown(
                    value = selected,
                    onValueChange = { onChanged(JsonPrimitive(it)) },
                    label = item.key,
                    options = item.options.map { option -> option.value to option.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                val committed = (currentValue as? JsonPrimitive)?.contentOrNull.orEmpty()
                var text by remember(currentValue) { mutableStateOf(committed) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Text(
                        text = item.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: Igual que arriba, configure el límite de nombre de campo en 1 línea
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier
                            .width(200.dp)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && text != committed) {
                                    onChanged(JsonPrimitive(text))
                                }
                            },
                    )
                }
            }
        }
        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun PluginCard(plugin: ProviderPlugin, onDelete: () -> Unit, onConvert: () -> Unit) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MuseIcons.puzzle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Spacer(Modifier.size(MusePaddings.iconPadding))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.plugin_models_summary, plugin.models.size, plugin.baseUrl),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { onConvert() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.swapHorizontal,
                        contentDescription = stringResource(R.string.provider_plugins_convert),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Spacer(Modifier.size(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.provider_plugins_convert),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.size(MusePaddings.contentGap))
            MuseTactileButton(
                icon = MuseIcons.trash,
                onClick = onDelete,
                contentDescription = stringResource(R.string.provider_plugins_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Etapa de tarea de instalación de mercado;Failed Llevar motivos de cara al usuario. */
private sealed interface MarketInstallPhase {
    data object Downloading : MarketInstallPhase
    data object Preparing : MarketInstallPhase
    data object AwaitingConfirm : MarketInstallPhase
    data object Installing : MarketInstallPhase
    data class Failed(val reason: String) : MarketInstallPhase
}

/** El estado de la interfaz de usuario de una instalación de mercado;[plan] solo descargando+revisión+Existe después de que todos los planes tengan éxito. */
private data class MarketInstallJob(
    val entryId: String,
    val phase: MarketInstallPhase,
    val plan: PluginInstallPlan? = null,
    /** Indicador de confianza explícito para emisor desconocido; fijo al generar el plan, comprometido con el valor original al confirmar. */
    val trustPublisher: Boolean = false,
)

@Composable
private fun MarketLoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.screen),
        contentAlignment = Alignment.Center,
    ) {
        MuseSpinner(
            size = MuseIconSizes.iconMedium,
        )
    }
}

@Composable
private fun MarketErrorRow(reason: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Icon(
            imageVector = MuseIcons.alertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        MuseCapsuleButton(
            text = stringResource(R.string.common_retry),
            onClick = onRetry,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/** No se permite la instalación cuando la versión del directorio es inferior a la versión instalada: la política de versiones rechazará la degradación y la interfaz no debería dar un callejón sin salida. */
private fun isDowngrade(installedVersion: String?, catalogVersion: String): Boolean {
    val installed = installedVersion?.let(PluginVersion::parse) ?: return false
    val candidate = PluginVersion.parse(catalogVersion) ?: return false
    return candidate < installed
}

/**
 * Tarjetas de cuadrícula de mercado —— Icono + Nombre + Versión/Editor + Estado, haga clic para ingresar a la página de detalles.
 */
@Composable
private fun MarketEntryCell(
    entry: PluginCatalogEntry,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MuseSurface(
        onClick = onClick,
        modifier = modifier,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Box(
                modifier = Modifier
                    .size(MuseIconSizes.controlTouch)
                    .clip(MuseShapes.medium)
                    .background(MuseActionColors.tonalContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.puzzle,
                    contentDescription = null,
                    tint = MuseActionColors.tonalContent,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.muse_plugins_market_entry_meta, entry.version, entry.publisherId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MarketEntryStateLine(
                entryVersion = entry.version,
                installedVersion = installedVersion,
                phase = phase,
            )
        }
    }
}

/** Línea de estado en la tarjeta (Descargando / Instalando / Instalado / Fallido / Instalable). */
@Composable
private fun MarketEntryStateLine(entryVersion: String, installedVersion: String?, phase: MarketInstallPhase?) {
    val (label, color) = when {
        phase == MarketInstallPhase.Downloading || phase == MarketInstallPhase.Preparing ->
            stringResource(R.string.muse_plugins_market_downloading) to MuseActionColors.mutedContent
        phase == MarketInstallPhase.Installing ->
            stringResource(R.string.muse_plugins_market_installing) to MuseActionColors.mutedContent
        phase is MarketInstallPhase.Failed ->
            stringResource(R.string.muse_plugins_market_install_failed, phase.reason) to MaterialTheme.colorScheme.error
        installedVersion == entryVersion ->
            stringResource(R.string.muse_plugins_market_installed) to MaterialTheme.colorScheme.primary
        else -> stringResource(
            if (installedVersion != null) {
                R.string.muse_plugins_market_update_action
            } else {
                R.string.muse_plugins_market_install_action
            },
        ) to MuseActionColors.mutedContent
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Panel de detalles del complemento de mercado —— Información completa + instalación/Cancelar/Vuelva a intentar la acción.
 */
@Composable
private fun MarketEntryDetailSheet(
    entry: PluginCatalogEntry,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissFailure: () -> Unit,
    onDismiss: () -> Unit,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.muse_plugins_market_entry_meta, entry.version, entry.publisherId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.description.isNotBlank()) {
            Spacer(Modifier.height(MusePaddings.contentGap))
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(
                R.string.muse_plugins_market_entry_capabilities,
                entry.capabilities.ifEmpty { listOf(stringResource(R.string.muse_plugins_none)) }
                    .joinToString(", "),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.itemGap))
        MarketInstallActions(
            entryVersion = entry.version,
            installedVersion = installedVersion,
            phase = phase,
            onInstall = onInstall,
            onCancel = onCancel,
            onRetry = onRetry,
            onDismissFailure = onDismissFailure,
        )
    }
}

/** Instalar fila de acción (compartida entre la tarjeta de cuadrícula y el panel de detalles). */
@Composable
private fun MarketInstallActions(
    entryVersion: String,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            phase == MarketInstallPhase.Downloading || phase == MarketInstallPhase.Preparing -> {
                MuseIndeterminateProgressBar(
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (phase == MarketInstallPhase.Downloading) {
                            R.string.muse_plugins_market_downloading
                        } else {
                            R.string.muse_plugins_market_preparing
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
            phase == MarketInstallPhase.AwaitingConfirm -> Text(
                text = stringResource(R.string.muse_plugins_market_awaiting_confirm),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            phase == MarketInstallPhase.Installing -> {
                MuseIndeterminateProgressBar(
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.muse_plugins_market_installing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            phase is MarketInstallPhase.Failed -> {
                Text(
                    text = stringResource(R.string.muse_plugins_market_install_failed, phase.reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_retry),
                    onClick = onRetry,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_close),
                    onClick = onDismissFailure,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
            installedVersion == entryVersion -> Text(
                text = stringResource(R.string.muse_plugins_market_installed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            isDowngrade(installedVersion, entryVersion) -> Text(
                // La versión del directorio es inferior a la versión instalada: la instalación será rechazada por la política de versiones y no se permitirá ningún callejón sin salida.
                text = stringResource(R.string.muse_plugins_market_downgrade_blocked, installedVersion.orEmpty()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            else -> MuseCapsuleButton(
                text = stringResource(
                    if (installedVersion != null) {
                        R.string.muse_plugins_market_update_action
                    } else {
                        R.string.muse_plugins_market_install_action
                    },
                ),
                onClick = onInstall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** P0-9: Línea de editor confiable — Mostrar identificación + huella digital,Proporciona una entrada de revocación. */
@Composable
private fun TrustedPublisherRow(publisherId: String, fingerprint: String, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = publisherId,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.muse_plugins_trust_publisher_fingerprint, fingerprint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MuseCapsuleButton(
            text = stringResource(R.string.muse_plugins_trust_revoke_action),
            onClick = onRevoke,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/** P0-9: línea raíz de confianza del directorio — mostrar keyId,Proporcionar acceso de eliminación(La raíz incorporada no es extraíble). */
@Composable
private fun CatalogRootKeyRow(keyId: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Text(
            text = keyId,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MuseCapsuleButton(
            text = stringResource(R.string.muse_plugins_trust_catalog_remove_action),
            onClick = onRemove,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/** Vista previa de confirmación de instalación: Capacidades/Herramientas/Huella digital del editor/Estado de la firma; El mercado y la importación local comparten el mismo contenido. */
@Composable
private fun PluginInstallPreviewContent(preview: PluginSecurityGate.InstallPreview) {
    val unknownAuthor = stringResource(R.string.muse_plugins_unknown_author)
    val noCapabilities = stringResource(R.string.muse_plugins_none)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_summary,
                preview.version,
                preview.author.ifBlank { unknownAuthor },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preview.description.isNotBlank()) {
            Text(
                text = preview.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // El tiempo de ejecución debe escribirse en el resumen:JS A diferencia del poder de aislamiento de Python, los usuarios tienen derecho a saber qué están autorizando.
        Text(
            text = stringResource(R.string.muse_plugins_runtime_label, preview.runtime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_capabilities,
                preview.capabilities.ifEmpty { listOf(noCapabilities) }
                    .joinToString(", "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Los reclamos y los permisos son dos cosas diferentes, pero colocados uno al lado del otro en la interfaz es fácil leer "solo requiere estos permisos".
        // Las declaraciones de capacidad no constituyen restricciones durante la ejecución real (la relajación es intencional, el ajuste hará que un lote de complementos que no han sido declarados se rompan directamente),
        // Así que aquí simplemente aclaramos la semántica y no pretendemos que sea un límite seguro.
        Text(
            text = stringResource(R.string.muse_plugins_capabilities_disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_tools,
                preview.tools.joinToString(", ") { it.name },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_code,
                preview.entryBytes,
                preview.extraFileCount,
                preview.entrySha256,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_signature_status,
                signatureStatusLabel(preview.signatureStatus),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (preview.publisherId.isNotBlank() && preview.publisherKeyFingerprint.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.muse_plugins_publisher_fingerprint,
                    preview.publisherId,
                    preview.publisherKeyFingerprint,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when (preview.signatureStatus) {
                PluginSecurityGate.SignatureStatus.UNSIGNED ->
                    stringResource(R.string.muse_plugins_install_unsigned_warning)
                PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED ->
                    stringResource(
                        R.string.muse_plugins_install_untrusted_warning,
                        preview.publisherId,
                        preview.publisherKeyFingerprint,
                    )
                PluginSecurityGate.SignatureStatus.VALID_TRUSTED ->
                    stringResource(R.string.muse_plugins_install_trusted_warning)
                PluginSecurityGate.SignatureStatus.INVALID ->
                    stringResource(R.string.muse_plugins_signature_invalid)
                PluginSecurityGate.SignatureStatus.UNSUPPORTED ->
                    stringResource(R.string.muse_plugins_signature_unsupported)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}
