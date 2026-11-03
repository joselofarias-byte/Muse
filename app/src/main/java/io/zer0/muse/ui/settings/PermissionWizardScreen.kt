package io.zer0.muse.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.automation.AutomationInitializer
import io.zer0.muse.automation.executors.RootRequestFailure
import io.zer0.muse.tools.system.AccessibilityProviderInstaller
import io.zer0.muse.tools.system.AndroidPermissionLevel
import io.zer0.muse.tools.system.RootAuthorizer
import io.zer0.muse.tools.system.ShizukuAuthorizer
import io.zer0.muse.tools.system.ShizukuInstaller
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.statusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * P3-3: Página de guía de permisos — tres canales(Accesibilidad/Shizuku/Root)Asistente de configuración de permisos.
 *
 * Guíe a los usuarios paso a paso para habilitar las capacidades de automatización de la interfaz de usuario.:
 *  1. Servicios de accesibilidad(ACCESSIBILITY):canal básico,UI Requerido para la operación
 *  2. Shizuku(SHIZUKU):shell Permiso para ejecutar comandos.,No es necesario root(Recomendado)
 *  3. Root(ROOT):canal de degradación,Requiere dispositivo rooteado
 *
 * La página muestra el nivel de permiso actual + estado de cada canal + botones de operación(habilitar/Instalación/Autorizar).
 */
@Composable
fun PermissionWizardScreen(onBack: () -> Unit) {
    val a11yInstaller: AccessibilityProviderInstaller = koinInject()
    val shizukuInstaller: ShizukuInstaller = koinInject()
    val shizukuAuthorizer: ShizukuAuthorizer = koinInject()
    val rootAuthorizer: RootAuthorizer = koinInject()
    // v2.x: Root Solicitud de autorización(Desde "UI Migración de páginas automatizada — La tarjeta de permiso de esta página ha convergido en este asistente.)
    val automationManager = remember { AutomationInitializer.manager }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Estado de cada canal
    var a11yEnabled by remember { mutableStateOf(false) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var shizukuAvailable by remember { mutableStateOf(false) }
    var shizukuAuthorized by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var rootRequesting by remember { mutableStateOf(false) }
    var suiAvailable by remember { mutableStateOf(false) }

    // v2.2.1: Termux Canal de expansión(Opcional;No participar"Tres niveles de autoridad de canal"juicio)
    val termuxChannel = remember { io.zer0.muse.terminal.TermuxChannel.get(context) }
    // v2.6.7 Reparación: Termux RUN_COMMAND es un permiso de ejecución peligroso,
    // Debe ser otorgado mediante el cuadro emergente del sistema (no existe tal cambio en la página de configuración).
    val termuxPermissionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) {
                android.widget.Toast
                    .makeText(
                        context,
                        context.getString(R.string.permission_termux_grant_denied_hint),
                        android.widget.Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    var termuxInstalled by remember { mutableStateOf(false) }
    var termuxPermitted by remember { mutableStateOf(false) }
    var termuxProbe by remember { mutableStateOf<Boolean?>(null) }

    // v2.2.1: Accesible e independiente Provider(Mejoras opcionales;No participar"Tres niveles de autoridad de canal"juicio)
    var providerInstalled by remember { mutableStateOf(false) }
    var providerBundled by remember { mutableStateOf(false) }

    // estado de actualización
    suspend fun refresh() {
        try {
            a11yEnabled = a11yInstaller.isEnabled()
            shizukuInstalled = shizukuInstaller.isInstalled()
            shizukuAvailable = shizukuAuthorizer.isAvailable()
            // Si el bit de autorización es verdadero, aún puede ser imposible vincular UserService; El estado disponible real se muestra aquí.
            shizukuAuthorized = shizukuAuthorizer.checkReady()
            rootAvailable = rootAuthorizer.checkPermission()
            suiAvailable = shizukuAuthorizer.isSuiBackendAvailable()
            // v2.2.1: Termux Detección estática de canal + detección ligera cuando esté listo(verificar allow-external-apps)
            termuxInstalled = termuxChannel.isInstalled()
            termuxPermitted = termuxChannel.hasPermission()
            termuxProbe =
                if (termuxInstalled && termuxPermitted) {
                    termuxChannel.probe() is io.zer0.muse.terminal.TermuxChannel.Availability.Ready
                } else {
                    null
                }
            // v2.2.1: Detección estática de proveedor independiente(pretender o no;APK Si viene con el paquete determina si se mostrará la entrada de instalación)
            providerInstalled = a11yInstaller.isProviderInstalled()
            providerBundled = a11yInstaller.isProviderApkBundled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Logger.w("PermissionWizard", "El sistema denegó el estado del permiso de actualización: ${e.message}", e)
        } catch (e: IllegalStateException) {
            Logger.w("PermissionWizard", "No se pudo actualizar el estado del permiso: ${e.message}", e)
        }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refresh()
        }
    }

    val currentLevel =
        AndroidPermissionLevel.highestOf(
            AndroidPermissionLevel.ACCESSIBILITY to a11yEnabled,
            AndroidPermissionLevel.SHIZUKU to (shizukuAvailable && shizukuAuthorized),
            AndroidPermissionLevel.ROOT to (rootAvailable || suiAvailable),
        )

    MusePageScaffold(
        topBar = {
            // ST-08: Visual unificado MuseTopBar(Material utilizado anteriormente TopAppBar + flecha de texto,Fuera de contacto con el estilo general del sitio)
            MuseTopBar(
                title = stringResource(R.string.permission_wizard_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Descripción general de los niveles de permisos actuales
            // ST-08b: M3 Card → Propiedad del proyecto MuseSurface(Visualmente unificado con todas las tarjetas del sitio.)
            MuseSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.permission_current_level),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val levelText =
                        when (currentLevel) {
                            AndroidPermissionLevel.NONE -> stringResource(R.string.permission_level_none)
                            AndroidPermissionLevel.ACCESSIBILITY -> stringResource(R.string.permission_level_accessibility)
                            AndroidPermissionLevel.SHIZUKU -> stringResource(R.string.permission_level_shizuku)
                            AndroidPermissionLevel.ROOT -> stringResource(R.string.permission_level_root)
                        }
                    Text(text = levelText, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.permission_wizard_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Canal 1: Servicios de Accesibilidad
            ChannelCard(
                title = stringResource(R.string.accessibility_section_title),
                enabled = a11yEnabled,
                enabledText = stringResource(R.string.accessibility_status_enabled),
                disabledText = stringResource(R.string.accessibility_status_disabled),
                actionText = stringResource(R.string.accessibility_enable_btn),
                onAction = {
                    a11yInstaller.openSettings()
                },
                onRefresh = { refreshAsync() },
            )

            // canal 1.5(v2.2.1): Accesible e independiente Provider — Mejoras opcionales:Las actualizaciones principales de la aplicación no interrumpen la autorización de accesibilidad
            if (providerBundled || providerInstalled) {
                A11yProviderCard(
                    installed = providerInstalled,
                    bundled = providerBundled,
                    onInstall = {
                        val launched = a11yInstaller.installProvider()
                        if (!launched) {
                            MuseToast.show(context.getString(R.string.permission_a11y_provider_install_failed))
                        }
                        refreshAsync()
                    },
                    onRefresh = { refreshAsync() },
                )
            }

            // canal 2: Shizuku
            ShizukuChannelCard(
                installed = shizukuInstalled,
                available = shizukuAvailable,
                authorized = shizukuAuthorized,
                onInstall = { shizukuInstaller.openDownloadPage() },
                onAuthorize = {
                    scope.launch {
                        shizukuAuthorizer.requestPermission()
                        refresh()
                    }
                },
                onStartService = {
                    // Guíe al usuario para que abra la aplicación Shizuku para iniciar el servicio.(No se puede levantar directamente)
                    shizukuInstaller.openDownloadPage()
                },
                onRefresh = { refreshAsync() },
            )

            // canal 3: Root — Cuando no está autorizado, el comportamiento principal es iniciar una solicitud de autorización.(Magisk/KernelSU ventana emergente)
            ChannelCard(
                title = stringResource(R.string.root_section_title),
                enabled = rootAvailable,
                enabledText = stringResource(R.string.root_status_available),
                disabledText = stringResource(R.string.root_status_unavailable),
                actionText = if (!rootAvailable) stringResource(R.string.automation_root_action_request) else "",
                onAction = {
                    if (!rootRequesting) {
                        scope.launch {
                            rootRequesting = true
                            val result = automationManager.requestRoot()
                            rootRequesting = false
                            if (result.granted) {
                                MuseToast.show(context.getString(R.string.automation_root_granted))
                            } else {
                                val failure = result.failure ?: RootRequestFailure.ERROR
                                val msgRes =
                                    when (failure) {
                                        RootRequestFailure.NO_SU_BINARY -> R.string.automation_root_no_su
                                        RootRequestFailure.TIMEOUT -> R.string.automation_root_timeout
                                        RootRequestFailure.DENIED -> R.string.automation_root_denied
                                        RootRequestFailure.ERROR -> R.string.automation_root_failed
                                    }
                                MuseToast.show(context.getString(msgRes))
                            }
                            refresh()
                        }
                    }
                },
                onRefresh = { refreshAsync() },
            )

            // canal 4(v2.2.1): Termux Canal de expansión — Ejecución completa de comandos del entorno Linux(Opcional)
            TermuxChannelCard(
                installed = termuxInstalled,
                permitted = termuxPermitted,
                probeOk = termuxProbe,
                onGet = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://f-droid.org/packages/com.termux/"),
                            ),
                        )
                    }
                },
                onGrant = {
                    // v2.6.7 Arreglar (comentarios de los usuarios:Termux Nunca se concederán permisos):
                    // `com.termux.permission.RUN_COMMAND` Fue declarado por el propio Termux
                    // `dangerous` Permisos de tiempo de ejecución (Termux Texto original del aviso de seguridad oficial.:
                    // "a `dangerous` runtime permission, to be granted to the calling app").
                    //
                    // Este tipo de permiso solo se puede otorgar mediante el cuadro emergente del sistema, o `adb shell pm grant`,
                    // **No**Aparece en la lista de permisos en la página de configuración. La antigua implementación sólo saltó
                    // ACTION_APPLICATION_DETAILS_SETTINGS, entonces:
                    //   saltar sobre → No existe tal interruptor ahí → volver hasPermission() todavía false → Siempre estancado.
                    // La mayoría de las ROM no tienen la página secundaria "Permisos adicionales" y la entrada en la copia en realidad no existe.
                    //
                    // Enfoque correcto:Inicie directamente una solicitud de tiempo de ejecución en el lado de Muse y deje que el sistema muestre el cuadro de autorización.
                    // Termux Cuando el permiso no está instalado/declarado, el sistema volverá a llamar directamente para denegarlo.
                    // En este momento, volverá a adb boot (conserve la sección tryStart a continuación).
                    val activity = context.findActivity()
                    // launcher Siempre registrado (Compose Creado durante el período de combinación), envíe una solicitud cuando haya Actividad.
                    // Cuando Termux no está instalado, el sistema volverá a llamar directamente para rechazarlo y el mensaje de denegación guiará al usuario para que lo instale primero.
                    if (activity != null) {
                        termuxPermissionLauncher.launch(io.zer0.muse.terminal.TermuxChannel.PERMISSION)
                        return@TermuxChannelCard
                    }
                    // retroceder: Cuando no pueda iniciar una solicitud de tiempo de ejecución, proporcione un comando adb autocomprobable en lugar de apuntar en la dirección incorrecta.
                    val pkg = io.zer0.muse.terminal.TermuxChannel.PKG
                    val pkgUri = android.net.Uri.fromParts("package", pkg, null)

                    // ① parte ROM(ColorOS/MIUI/EMUI)Admite abrir directamente los detalles de la aplicación y ubicarla en la página de permisos
                    fun tryStart(action: String, uri: android.net.Uri?): Boolean = runCatching {
                        val i = if (uri != null) {
                            android.content.Intent(action, uri)
                        } else {
                            android.content.Intent(action)
                        }
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    }.isSuccess

                    val ok = tryStart(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri) ||
                        tryStart(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, null)

                    if (!ok) {
                        android.widget.Toast
                            .makeText(
                                context,
                                context.getString(R.string.permission_termux_grant_manual_hint),
                                android.widget.Toast.LENGTH_LONG,
                            )
                            .show()
                    } else {
                        // Incluso si el salto es exitoso, se deben dar instrucciones paso a paso. —— Este interruptor no es visible en la primera pantalla de información de la aplicación.
                        android.widget.Toast
                            .makeText(
                                context,
                                context.getString(R.string.permission_termux_grant_toast),
                                android.widget.Toast.LENGTH_LONG,
                            )
                            .show()
                    }
                },
                onOpenApp = {
                    runCatching {
                        context.packageManager.getLaunchIntentForPackage(io.zer0.muse.terminal.TermuxChannel.PKG)
                            ?.let { context.startActivity(it) }
                    }
                },
                onRefresh = { refreshAsync() },
            )

            // Sui Consejos de compatibilidad de backend
            if (suiAvailable) {
                MuseSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MuseIcons.circleCheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.permission_sui_detected),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Descripción de la constante de acción global
            MuseSurface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.permission_global_action_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = buildGlobalActionsHelp(context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(
    title: String,
    enabled: Boolean,
    enabledText: String,
    disabledText: String,
    actionText: String,
    onAction: () -> Unit,
    onRefresh: () -> Unit,
) {
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Row Haga que el título interior sea más ancho + limite la línea para evitar que el ícono de estado a la derecha la comprima en una columna cuando se amplía el tamaño de fuente.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (enabled) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (enabled) enabledText else disabledText,
                style = MaterialTheme.typography.bodyMedium,
                color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (actionText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                // ST-08b: M3 Button → MuseCapsuleButton(estilo principal)
                MuseCapsuleButton(text = actionText, onClick = onAction)
            }
            Spacer(Modifier.height(8.dp))
            // ST-08b: M3 OutlinedButton → MuseCapsuleButton(estilo secundario)
            MuseCapsuleButton(
                text = stringResource(R.string.permission_refresh_status),
                onClick = onRefresh,
                variant = IosCapsuleButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun ShizukuChannelCard(
    installed: Boolean,
    available: Boolean,
    authorized: Boolean,
    onInstall: () -> Unit,
    onAuthorize: () -> Unit,
    onStartService: () -> Unit,
    onRefresh: () -> Unit,
) {
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.shizuku_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Limitado a 1 línea (ver PROJECT_GUIDE §5)
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ready = installed && available && authorized
                Icon(
                    imageVector = if (ready) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // Visualización de estado paso a paso
            StatusLine(
                done = installed,
                text = if (installed) {
                    stringResource(R.string.shizuku_apk_installed)
                } else {
                    stringResource(R.string.shizuku_apk_not_installed)
                },
            )
            StatusLine(
                done = available,
                text = if (available) {
                    stringResource(R.string.shizuku_service_running)
                } else {
                    stringResource(R.string.shizuku_service_not_running)
                },
            )
            StatusLine(
                done = authorized,
                text = if (authorized) {
                    stringResource(R.string.shizuku_status_authorized)
                } else {
                    stringResource(R.string.shizuku_status_unauthorized_long)
                },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // ST-08b: tres botones de acción M3 Button → MuseCapsuleButton(fillWidth = false mantenerse al lado del otro)
                if (!installed) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.shizuku_install_btn),
                        onClick = onInstall,
                        fillWidth = false,
                    )
                } else if (!available) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.permission_open_shizuku_service),
                        onClick = onStartService,
                        fillWidth = false,
                    )
                } else if (!authorized) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.shizuku_authorize_btn),
                        onClick = onAuthorize,
                        fillWidth = false,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.permission_refresh_status),
                    onClick = onRefresh,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun TermuxChannelCard(
    installed: Boolean,
    permitted: Boolean,
    probeOk: Boolean?,
    onGet: () -> Unit,
    onGrant: () -> Unit,
    onOpenApp: () -> Unit,
    onRefresh: () -> Unit,
) {
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.permission_termux_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Limitado a 1 línea (ver PROJECT_GUIDE §5)
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ready = installed && permitted && probeOk == true
                Icon(
                    imageVector = if (ready) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            StatusLine(
                done = installed,
                text =
                if (installed) {
                    stringResource(R.string.permission_termux_apk_installed)
                } else {
                    stringResource(R.string.permission_termux_apk_not_installed)
                },
            )
            StatusLine(
                done = permitted,
                text =
                if (permitted) {
                    stringResource(R.string.permission_termux_permission_ok)
                } else {
                    stringResource(R.string.permission_termux_permission_missing)
                },
            )
            StatusLine(
                done = probeOk == true,
                text =
                when (probeOk) {
                    true -> stringResource(R.string.permission_termux_probe_ok)
                    false -> stringResource(R.string.permission_termux_probe_failed)
                    null -> stringResource(R.string.permission_termux_probe_unchecked)
                },
            )
            if (installed && permitted && probeOk != true) {
                Spacer(Modifier.height(8.dp))
                // issue #3.4: Los usuarios informaron que "las instrucciones agregadas en el manual para permitir llamadas externas tampoco son válidas".
                // La causa principal es que simplemente escribir termux.properties no tendrá efecto.,debe termux-reload-settings
                // (La copia antigua solo decía "Reiniciar Termux",El reinicio no tiene efecto en algunos escenarios de ROM/en segundo plano).
                // Aquí está el comando completo de dos pasos.,Y admite copia con un solo clic,Evite errores al escribir a mano.
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                val termuxCmd = "echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.permission_termux_config_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    val toastCtx = androidx.compose.ui.platform.LocalContext.current
                    // lint LocalContextGetResourceValueCall: El valor del recurso debe completarse durante el período de combinación.,
                    // No se puede llamar a GetString en la devolución de llamada de clic. No hay ningún marcador de posición para la copia aquí.,Utilice stringResource sin parámetros.
                    val copyDoneText = stringResource(R.string.permission_termux_copy_cmd_done)
                    MuseCapsuleButton(
                        text = stringResource(R.string.permission_termux_copy_cmd),
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(termuxCmd))
                            android.widget.Toast
                                .makeText(
                                    toastCtx,
                                    copyDoneText,
                                    android.widget.Toast.LENGTH_SHORT,
                                )
                                .show()
                        },
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    !installed -> {
                        MuseCapsuleButton(
                            text = stringResource(R.string.permission_termux_get_btn),
                            onClick = onGet,
                            fillWidth = false,
                        )
                    }

                    !permitted -> {
                        MuseCapsuleButton(
                            text = stringResource(R.string.permission_termux_grant_btn),
                            onClick = onGrant,
                            fillWidth = false,
                        )
                    }

                    probeOk != true -> {
                        MuseCapsuleButton(
                            text = stringResource(R.string.permission_termux_open_btn),
                            onClick = onOpenApp,
                            fillWidth = false,
                        )
                    }
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.permission_refresh_status),
                    onClick = onRefresh,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun A11yProviderCard(installed: Boolean, bundled: Boolean, onInstall: () -> Unit, onRefresh: () -> Unit) {
    MuseSurface(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.permission_a11y_provider_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Limitado a 1 línea (ver PROJECT_GUIDE §5)
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Mejoras opcionales:Utilice colores neutros cuando no esté instalado.,evitar con"Canal requerido no autorizado"confundir
                Icon(
                    imageVector = if (installed) MuseIcons.circleCheck else MuseIcons.alertTriangle,
                    contentDescription = null,
                    tint =
                    if (installed) {
                        MaterialTheme.statusColors.success
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text =
                if (installed) {
                    stringResource(R.string.permission_a11y_provider_installed)
                } else {
                    stringResource(R.string.permission_a11y_provider_not_installed)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_a11y_provider_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed && bundled) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.permission_a11y_provider_install_btn),
                        onClick = onInstall,
                        fillWidth = false,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.permission_refresh_status),
                    onClick = onRefresh,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(done: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Icon(
            imageVector = if (done) MuseIcons.circleCheck else MuseIcons.alertTriangle,
            contentDescription = null,
            // ST-06: Los no autorizados y los autorizados se distinguen por diferentes colores.(Anteriormente no autorizado está atenuado outline,Distinción insuficiente de lo autorizado)
            tint = if (done) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color =
            if (done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun buildGlobalActionsHelp(context: Context): String = context.getString(R.string.permission_action_id_help)

/**
 * v2.6.7: Busque Actividad desde Contexto.
 *
 * Las solicitudes de permiso de tiempo de ejecución deben iniciarse desde la Actividad (ContextWrapper La actividad no podrá estar directamente vinculada a la cadena).
 * Si no se puede encontrar, se devuelve null y la persona que llama recurre a la guía manual sin generar una excepción.
 */
private fun Context.findActivity(): android.app.Activity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
