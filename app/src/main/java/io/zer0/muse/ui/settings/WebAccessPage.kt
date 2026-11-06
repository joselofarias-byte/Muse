package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.web.WebServer
import io.zer0.muse.web.WebServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.security.SecureRandom

/** La dirección de acceso y el PIN deben ser instantáneas de información \"vistas de un vistazo\", combinadas en una unidad de visualización dentro de una pantalla. */
private data class WebAccessSnapshot(
    /** El estado de ejecución real del servicio (tomado del servidor web, independientemente de si está habilitado en la configuración). */
    val running: Boolean,
    /** El puerto de escucha real; cuando el servicio no se está ejecutando, es el puerto configurado. */
    val port: Int,
    /** El motivo del último fallo de inicio. */
    val lastError: String?,
    /** Direcciones de acceso disponibles actualmente. */
    val address: WebAccessAddress,
    /** PIN actual de 6 dígitos; cadena vacía si no se genera. */
    val pin: String,
)

/** Longitud del lado del mapa de bits del código QR (píxeles). */
private const val QR_BITMAP_PX = 640

/**
 * ajustes → conectar → Acceso web.
 *
 * Una pantalla muestra "dirección + PIN + código QR" y puede conectarse desde iPhone/PC escaneando:
 *  - cambiar de escritura [WebServerConfig.enabled], inicie el servicio si no se inicia después de abrir, detenga y libere el puerto después de cerrar
 *  - Correspondencia del conmutador LAN [WebServerConfig.allowLan], escriba claramente "Solo esta máquina puede acceder" al cerrar.
 *  - La dirección toma el puerto en ejecución real y la IP de LAN es [resolveLanIpAddress] Enumere las tarjetas de red y obtenga
 *  - PIN Regenerable; El servicio se reiniciará con la configuración y el PIN anterior dejará de ser válido inmediatamente.
 *  - El código QR se representa zxing en un mapa de bits y el espacio exterior lo proporciona una tarjeta de fondo blanco.
 */
@Composable
fun WebAccessPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val webServer: WebServer = koinInject()
    val context = LocalContext.current
    val localizedResources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val config by settings.webServerConfigFlow.collectAsStateWithLifecycle(initialValue = WebServerConfig())
    val isRunning by webServer.isRunning.collectAsStateWithLifecycle(initialValue = false)
    val lastError by webServer.lastError.collectAsStateWithLifecycle(initialValue = null)
    // El puerto está sujeto al monitoreo real del servidor: el puerto en la configuración no tendrá efecto hasta que se reinicie el servicio.
    val port = webServer.status().port.takeIf { it > 0 } ?: config.port
    var lanIp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(config.allowLan) {
        lanIp = withContext(Dispatchers.IO) { resolveLanIpAddress() }
    }
    val snapshot = WebAccessSnapshot(
        running = isRunning,
        port = port,
        lastError = lastError,
        address = resolveWebAccessAddress(config.allowLan, lanIp, port, config.httpsEnabled),
        pin = config.pin,
    )
    val host = remember(settings, webServer) { WebServerAccessHost(settings, webServer) }

    fun toggleEnabled(enabled: Boolean) {
        scope.launch {
            resultOf { applyWebAccessEnabled(host, config, enabled, isRunning) }
                .onSuccess { runningNow ->
                    if (enabled && !runningNow) {
                        MuseToast.show(localizedResources.getString(R.string.settings_web_status_failed, lastError.orEmpty()), 3500)
                    }
                }
                .onError { _, t -> MuseToast.show(localizedResources.getString(R.string.settings_web_failed, t?.message)) }
        }
    }

    fun toggleLan(allowLan: Boolean) {
        // La dirección de enlace y CORS deben cambiarse en consecuencia y el servicio solo se puede reiniciar.
        scope.launch {
            resultOf { webServer.saveAndApplyConfig(config.copy(allowLan = allowLan)) }
                .onError { _, t -> MuseToast.show(localizedResources.getString(R.string.settings_web_failed, t?.message)) }
        }
    }

    fun regeneratePin() {
        val newPin = WebServerConfig.generateRandomPin(SecureRandom())
        scope.launch {
            resultOf { webServer.saveAndApplyConfig(config.copy(pin = newPin)) }
                .onSuccess {
                    MuseToast.show(localizedResources.getString(R.string.settings_web_access_pin_regenerated, newPin), 3500)
                }
                .onError { _, t -> MuseToast.show(localizedResources.getString(R.string.settings_web_failed, t?.message)) }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_screen_web_access), onBack = onBack) {
        item(key = "web_access_intro") {
            Text(
                text = stringResource(R.string.settings_web_access_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        item(key = "web_access_hero") {
            WebAccessHeroCard(
                snapshot = snapshot,
                onCopyAddress = {
                    snapshot.address.url?.let { url ->
                        copyToClipboard(context, "Muse WebServer", url)
                        MuseToast.show(localizedResources.getString(R.string.settings_web_address_copied, url))
                    }
                },
                onCopyPin = {
                    if (snapshot.pin.isNotBlank()) {
                        copyToClipboard(context, "Muse WebServer PIN", snapshot.pin)
                        MuseToast.show(localizedResources.getString(R.string.settings_web_pin_copied, snapshot.pin))
                    }
                },
            )
        }
        item(key = "web_access_switches") {
            WebAccessSwitchGroup(
                config = config,
                onToggleEnabled = ::toggleEnabled,
                onToggleLan = ::toggleLan,
                onRegeneratePin = ::regeneratePin,
            )
        }
        item(key = "web_access_tips") { WebAccessTips() }
    }
}

/** La tarjeta combinada de estado de ejecución + dirección de acceso + PIN + código QR permite a los usuarios ver toda la información de conexión en una pantalla. */
@Composable
private fun WebAccessHeroCard(
    snapshot: WebAccessSnapshot,
    onCopyAddress: () -> Unit,
    onCopyPin: () -> Unit,
) {
    val url = snapshot.address.url
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    snapshot.running -> stringResource(R.string.settings_web_status_running, snapshot.port)
                    snapshot.lastError != null ->
                        stringResource(R.string.settings_web_status_failed, snapshot.lastError.orEmpty())
                    else -> stringResource(R.string.settings_web_not_running)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (snapshot.running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            WebAccessValueRow(
                label = stringResource(R.string.settings_web_access_address),
                value = url ?: stringResource(R.string.settings_web_access_no_ip),
                faded = url == null,
                onCopy = onCopyAddress.takeIf { url != null },
                copyDescription = stringResource(R.string.settings_web_copy_address),
            )
            WebAccessValueRow(
                label = stringResource(R.string.settings_web_pin),
                value = snapshot.pin.ifBlank { stringResource(R.string.settings_web_pin_not_set) },
                faded = snapshot.pin.isBlank(),
                onCopy = onCopyPin.takeIf { snapshot.pin.isNotBlank() },
                copyDescription = stringResource(R.string.settings_web_copy_pin),
            )
            WebAccessQrBlock(address = snapshot.address)
            Text(
                text = stringResource(R.string.settings_web_access_first_login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

/** Línea \"Etiqueta - Valor + Botón Copiar\"; cuando el valor esté vacío, utilice un color claro para solicitar la copia en lugar de dejarlo en blanco. */
@Composable
private fun WebAccessValueRow(
    label: String,
    value: String,
    faded: Boolean,
    onCopy: (() -> Unit)?,
    copyDescription: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                color = if (faded) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(4.dp))
                MuseTactileButton(
                    icon = MuseIcons.copy,
                    onClick = onCopy,
                    contentDescription = copyDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                    iconSize = 20.dp,
                )
            }
        }
    }
}

/** La etiqueta de prueba del bloque de código QR se utiliza para la afirmación de comportamiento de \"solo el modo local no representa códigos QR\". */
internal const val WEB_ACCESS_QR_TAG = "web_access_qr"

/**
 * Bloque de código QR.
 *
 * Solo se representa cuando no es "accesible sólo para esta máquina": la dirección de bucle invertido se dibuja como un código QR y otros dispositivos no pueden conectarse incluso si lo escanean.
 * Por lo tanto, en ese modo, no se representa el bloque completo y se reemplaza con una descripción, sin dejar ningún marco vacío.
 * Cuando el acceso LAN está habilitado pero no se obtiene la IP, no se representará el bloque completo (la línea de dirección ya ha dado un mensaje).
 */
@Composable
internal fun WebAccessQrBlock(address: WebAccessAddress) {
    if (address.localOnly) {
        Text(
            text = stringResource(R.string.settings_web_access_local_only_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        return
    }
    val url = address.url ?: return
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.Default) { QrCodeGenerator.generateQrBitmap(url, QR_BITMAP_PX) }
    }
    val contentDescription = stringResource(R.string.settings_web_access_qr_cd)
    val scanHint = stringResource(R.string.settings_web_access_scan_hint)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(WEB_ACCESS_QR_TAG),
        shape = MuseShapes.medium,
        color = Color.White,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val image = bitmap
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.size(220.dp),
                )
            } else {
                Box(
                    modifier = Modifier.size(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MuseIcons.qrcode,
                        contentDescription = null,
                        tint = Color.Black.copy(alpha = 0.2f),
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Text(
                text = scanHint,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black.copy(alpha = 0.6f),
            )
        }
    }
}

/** Conmutador de habilitación, conmutador LAN,PIN Se regeneran tres líneas. */
@Composable
private fun WebAccessSwitchGroup(
    config: WebServerConfig,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onRegeneratePin: () -> Unit,
) {
    SettingsGroup {
        SettingsSwitchRow(
            icon = MuseIcons.router,
            title = stringResource(R.string.settings_web_access_enable),
            subtitle = stringResource(R.string.settings_web_access_enable_subtitle),
            checked = config.enabled,
            onCheckedChange = onToggleEnabled,
        )
        SettingsGroupDivider()
        SettingsSwitchRow(
            icon = MuseIcons.wifi,
            title = stringResource(R.string.settings_web_access_lan),
            subtitle = if (config.allowLan) {
                stringResource(R.string.settings_web_access_lan_on)
            } else {
                stringResource(R.string.settings_web_access_lan_off)
            },
            checked = config.allowLan,
            onCheckedChange = onToggleLan,
        )
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.refresh,
            title = stringResource(R.string.settings_web_regenerate_pin),
            subtitle = stringResource(R.string.settings_web_regenerate_pin_subtitle),
            onClick = onRegeneratePin,
        )
    }
}

/** Tres instrucciones al final:iPhone Pantalla completa, mismo Wi-Fi, desconectado al apagarlo. */
@Composable
private fun WebAccessTips() {
    val tips = listOf(
        stringResource(R.string.settings_web_access_tip_iphone),
        stringResource(R.string.settings_web_access_tip_same_wifi),
        stringResource(R.string.settings_web_access_tip_disable),
    )
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            tips.forEach { tip ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(16.dp),
                    )
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

/** Escribe en el portapapeles; La etiqueta se utiliza sólo para descripciones legibles por humanos de las entradas del portapapeles del sistema. */
private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

/** poner [SettingsRepository] con [WebServer] Adáptese a los límites de efectos secundarios requeridos por la lógica del cambio. */
private class WebServerAccessHost(
    private val settings: SettingsRepository,
    private val webServer: WebServer,
) : WebAccessHost {
    override suspend fun saveConfig(config: WebServerConfig) {
        settings.saveWebServerConfig(config)
    }

    override suspend fun startServer(): Boolean = webServer.start()

    override suspend fun stopServer() {
        webServer.stop()
    }
}
