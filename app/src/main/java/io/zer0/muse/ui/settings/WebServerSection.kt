package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.util.NetworkUtils
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.web.WebServer
import io.zer0.muse.web.WebServerConfig
import kotlinx.coroutines.launch

/**
 * P1-2 Reparación: Configuración del servidor web incorporado section — Lista agrupada.
 *
 * Actualización de la fase C: Obtenga dinámicamente IP LAN + dirección de acceso a copia con un solo clic.
 *
 * P2-13:WebServer PIN seguro
 *  - Muestra los 6 dígitos actuales PIN(texto plano),Se utiliza para la verificación de acceso por primera vez en el lado web.
 *  - proporcionar"regenerar PIN"botón,El PIN antiguo caduca inmediatamente
 *  - recordatorio obvio"Otros bajo el mismo Wi-Fi pueden acceder a través de IP + PIN"
 *
 * El servicio backend WebServerConfig + WebServer se inicia automáticamente cuando se inicia MuseApp,
 * Después de guardar la página de configuración, la configuración se aplicará inmediatamente y se mostrará el estado operativo real. Esta sección da control a los usuarios.:
 *  - Interruptor de arranque/parada(Iniciar o detener inmediatamente después de guardar WebServer)
 *  - Configuración de puerto(Predeterminado 8765)
 *  - Contraseña(para iniciar sesión en JWT,Regenerable)
 *  - PIN(Se utiliza para la verificación de acceso por primera vez en el lado web.,Regenerable)
 *  - Dirección de acceso(IP dinámica + botón copiar)
 *  - v2.x: HTTPS cambiar(certificado autofirmado,Desactivado predeterminado);Después de abrirlo, se muestra la huella digital del certificado SHA-256 para su verificación.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod", "FunctionNaming")
@Composable
internal fun WebServerSection(settings: SettingsRepository, webServer: WebServer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val config by settings.webServerConfigFlow.collectAsStateWithLifecycle(
        initialValue = WebServerConfig(),
    )
    val isRunning by webServer.isRunning.collectAsStateWithLifecycle(initialValue = false)
    val lastError by webServer.lastError.collectAsStateWithLifecycle(initialValue = null)
    var showPortDialog by remember { mutableStateOf(false) }
    var portInput by remember { mutableStateOf(config.port.toString()) }
    // Obtener LAN dinámicamente IP
    val localIp = remember { NetworkUtils.getLocalIpAddress() }
    val accessUrl = buildAccessUrl(config.allowLan, localIp, config.port, config.httpsEnabled)
    val runningAccessUrl = if (isRunning) accessUrl else null

    SectionLabel(stringResource(R.string.settings_web_title))
    Text(
        text = stringResource(R.string.settings_web_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        text = when {
            isRunning -> stringResource(R.string.settings_web_status_running, config.port)
            lastError != null -> stringResource(R.string.settings_web_status_failed, lastError.orEmpty())
            else -> stringResource(R.string.settings_web_status_stopped)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )

    // P2-13: recordatorio obvio — Otros bajo el mismo Wi-Fi pueden acceder a través de IP + PIN
    if (runningAccessUrl != null && config.pin.isNotBlank() && config.allowLan) {
        Surface(
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            shape = MuseShapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = MuseIcons.info,
                    contentDescription = stringResource(R.string.settings_web_security_hint),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.settings_web_access_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_web_address_pin, runningAccessUrl, config.pin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = stringResource(R.string.settings_web_pin_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }

    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // Interruptor de arranque/parada
        SettingsSwitchRow(
            icon = MuseIcons.router,
            title = stringResource(R.string.settings_web_enable),
            subtitle = stringResource(R.string.settings_web_enable_subtitle),
            checked = config.enabled,
            onCheckedChange = { enabled ->
                scope.launch {
                    resultOf { webServer.saveAndApplyConfig(config.copy(enabled = enabled)) }
                        .onError { _, t ->
                            MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                        }
                }
            },
        )
        SettingsGroupDivider()
        lanAccessSwitch(config = config, webServer = webServer)
        SettingsGroupDivider()
        // v2.x: HTTPS cambiar(certificado autofirmado);Después de guardar, reinicie el servicio y listo. TLS
        httpsSwitch(config = config, webServer = webServer)
        // puerto
        SettingsItemRow(
            icon = MuseIcons.globe,
            title = stringResource(R.string.settings_web_port),
            subtitle = stringResource(R.string.settings_web_port_subtitle, config.port),
            onClick = {
                portInput = config.port.toString()
                showPortDialog = true
            },
        )
        SettingsGroupDivider()
        // Contraseña(Pantalla insensibilizada)
        SettingsItemRow(
            icon = MuseIcons.lock,
            title = stringResource(R.string.settings_web_password),
            subtitle = if (config.password.isBlank()) {
                stringResource(
                    R.string.settings_web_password_not_set,
                )
            } else {
                "${config.password.take(2)}****"
            },
        )
        SettingsGroupDivider()
        // Regenerar contraseña
        SettingsItemRow(
            icon = MuseIcons.lock,
            title = stringResource(R.string.settings_web_regenerate_password),
            subtitle = stringResource(R.string.settings_web_regenerate_password_subtitle),
            onClick = {
                val newPassword = WebServerConfig.generateRandomPassword()
                scope.launch {
                    resultOf { webServer.saveAndApplyConfig(config.copy(password = newPassword)) }
                        .onSuccess {
                            MuseToast.show(context.getString(R.string.settings_web_new_password, newPassword), 3500)
                        }
                        .onError { _, t ->
                            MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                        }
                }
            },
        )
        SettingsGroupDivider()
        // P2-13: actual PIN(visualización de texto claro,Con botón de copiar)
        SettingsItemRow(
            icon = MuseIcons.key,
            title = stringResource(R.string.settings_web_pin),
            subtitle = if (config.pin.isBlank()) stringResource(R.string.settings_web_pin_not_set) else config.pin,
        ) {
            if (config.pin.isNotBlank()) {
                MuseTactileButton(
                    icon = MuseIcons.copy,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Muse WebServer PIN", config.pin))
                        MuseToast.show(context.getString(R.string.settings_web_pin_copied, config.pin))
                    },
                    contentDescription = stringResource(R.string.settings_web_copy_pin),
                    tint = MaterialTheme.colorScheme.onSurface,
                    iconSize = 20.dp,
                )
            }
        }
        SettingsGroupDivider()
        // P2-13: regenerar PIN
        SettingsItemRow(
            icon = MuseIcons.key,
            title = stringResource(R.string.settings_web_regenerate_pin),
            subtitle = stringResource(R.string.settings_web_regenerate_pin_subtitle),
            onClick = {
                val newPin = WebServerConfig.generateRandomPin()
                scope.launch {
                    resultOf { webServer.saveAndApplyConfig(config.copy(pin = newPin)) }
                        .onSuccess {
                            MuseToast.show(context.getString(R.string.settings_web_new_pin, newPin), 3500)
                        }
                        .onError { _, t ->
                            MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                        }
                }
            },
        )
        SettingsGroupDivider()
        // Dirección de acceso(IP dinámica + botón copiar)
        SettingsItemRow(
            icon = MuseIcons.wifi,
            title = stringResource(R.string.settings_web_access_address),
            subtitle = when {
                runningAccessUrl != null -> runningAccessUrl
                lastError != null -> stringResource(R.string.settings_web_status_failed, lastError.orEmpty())
                else -> stringResource(R.string.settings_web_not_running)
            },
        ) {
            if (runningAccessUrl != null) {
                MuseTactileButton(
                    icon = MuseIcons.copy,
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Muse WebServer", runningAccessUrl))
                        MuseToast.show(context.getString(R.string.settings_web_address_copied, runningAccessUrl))
                    },
                    contentDescription = stringResource(R.string.settings_web_copy_address),
                    tint = MaterialTheme.colorScheme.onSurface,
                    iconSize = 20.dp,
                )
            }
        }
        // v2.x: HTTPS Exponer la huella digital del certificado SHA-256 en tiempo de ejecución,Para que los usuarios lo comprueben/confianza
        if (config.httpsEnabled && isRunning) {
            val fingerprint = webServer.status().fingerprint
            if (fingerprint != null) {
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.lock,
                    title = stringResource(R.string.settings_web_https_fingerprint),
                    subtitle = fingerprint,
                )
            }
        }
    }

    // Cuadro de diálogo de modificación de puerto
    if (showPortDialog) {
        MuseDialog(
            onDismissRequest = { showPortDialog = false },
            title = stringResource(R.string.settings_web_edit_port),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MuseTextField(
                        value = portInput,
                        onValueChange = { portInput = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.settings_web_port_number)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.settings_web_port_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmText = stringResource(R.string.settings_common_save),
            onConfirm = {
                val port = portInput.toIntOrNull()
                if (port == null || port < 1024 || port > 65535) {
                    MuseToast.show(context.getString(R.string.settings_web_port_invalid))
                    return@MuseDialog
                }
                scope.launch {
                    resultOf { webServer.saveAndApplyConfig(config.copy(port = port)) }
                        .onSuccess {
                            showPortDialog = false
                            MuseToast.show(context.getString(R.string.settings_web_port_updated))
                        }
                        .onError { _, t ->
                            MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                        }
                }
            },
            dismissText = stringResource(R.string.settings_common_cancel),
            onDismiss = { showPortDialog = false },
        )
    }
}

/** R-SEC-03: El valor predeterminado es solo local;La dirección IP de LAN se muestra solo después de habilitar el acceso a LAN. */
private fun buildAccessUrl(allowLan: Boolean, localIp: String?, port: Int, httpsEnabled: Boolean): String? {
    // v2.x: HTTPS Mostrar dirección de acceso https:// cuando esté habilitado(Evite que los usuarios no puedan conectarse a la dirección http al hacer clic en ella)
    val scheme = if (httpsEnabled) "https" else "http"
    return if (allowLan) localIp?.let { "$scheme://$it:$port" } else "$scheme://127.0.0.1:$port"
}

/** R-SEC-03: conmutador de acceso LAN;El valor predeterminado es solo local,Sólo después de encenderlo se permitirá el acceso al mismo dispositivo Wi-Fi a través de IP. */
@Composable
private fun lanAccessSwitch(config: WebServerConfig, webServer: WebServer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SettingsSwitchRow(
        icon = MuseIcons.wifi,
        title = stringResource(R.string.settings_web_allow_lan),
        subtitle = stringResource(R.string.settings_web_allow_lan_subtitle),
        checked = config.allowLan,
        onCheckedChange = { allowLan ->
            scope.launch {
                resultOf { webServer.saveAndApplyConfig(config.copy(allowLan = allowLan)) }
                    .onError { _, t ->
                        MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                    }
            }
        },
    )
}

/** v2.x: HTTPS cambiar — certificado autofirmado;Después de guardar, reinicie inmediatamente el servicio y cambie HTTP/HTTPS. */
@Composable
private fun httpsSwitch(config: WebServerConfig, webServer: WebServer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SettingsSwitchRow(
        icon = MuseIcons.lock,
        title = stringResource(R.string.settings_web_https),
        subtitle = stringResource(R.string.settings_web_https_subtitle),
        checked = config.httpsEnabled,
        onCheckedChange = { enabled ->
            scope.launch {
                resultOf { webServer.saveAndApplyConfig(config.copy(httpsEnabled = enabled)) }
                    .onError { _, t ->
                        MuseToast.show(context.getString(R.string.settings_web_failed, t?.message))
                    }
            }
        },
    )
}
