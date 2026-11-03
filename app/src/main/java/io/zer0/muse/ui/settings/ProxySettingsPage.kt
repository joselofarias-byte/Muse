package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Página de configuración del proxy de red global.
 *
 * Admite proxy HTTP/SOCKS5,Host, puerto, nombre de usuario y contraseña configurables.
 * La aplicación debe reiniciarse después de la modificación para que surta efecto.(OkHttpClient Lea la configuración una vez cuando se inicializa Koin).
 */
@Composable
fun ProxySettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())
    val scope = rememberCoroutineScope()

    // borrador local,Haga clic en Guardar antes de escribir DataStore
    // Correcciones en la interfaz (persistencia-9): Revisión del borrador del formulario rememberSaveable,rotar/La reconstrucción del proceso no pierde el contenido completo.;
    // key Heredar el valor original de cada campo.(son todos escalares salvables,Y ProxyConfig no tiene campo de identificación,Por lo tanto, no es necesario utilizar la instancia de configuración para key)
    var enabled by rememberSaveable(proxyConfig.enabled) { mutableStateOf(proxyConfig.enabled) }
    var type by rememberSaveable(proxyConfig.type) { mutableStateOf(proxyConfig.type) }
    var host by rememberSaveable(proxyConfig.host) { mutableStateOf(proxyConfig.host) }
    var portText by rememberSaveable(proxyConfig.port) { mutableStateOf(proxyConfig.port.toString()) }
    var username by rememberSaveable(proxyConfig.username) { mutableStateOf(proxyConfig.username) }
    var password by rememberSaveable(proxyConfig.password) { mutableStateOf(proxyConfig.password) }
    // v2.6.9: Nombres de dominio que no utilizan proxies (separados por comas)
    var bypass by rememberSaveable(proxyConfig.bypassHosts) { mutableStateOf(proxyConfig.bypassHosts) }
    var saving by rememberSaveable { mutableStateOf(false) }

    val proxyTypes = listOf("HTTP" to stringResource(R.string.proxy_type_http), "SOCKS5" to stringResource(R.string.proxy_type_socks5))
    // v2.6.9(comentarios de los usuarios): Una vez que el proxy se cambia a un ProxySelector dinámico, las nuevas conexiones entran en vigor inmediatamente.
    // viejos consejos“Entrará en vigor después de reiniciar la aplicación.”Hará que los usuarios piensen que necesitan reiniciar, pero en realidad no es así; el idioma se ha modificado simultáneamente.
    val proxySavedNeedsRestartText = stringResource(R.string.proxy_saved_needs_restart)

    SettingsSubPageScaffold(title = stringResource(R.string.proxy_title), onBack = onBack) {
        item { SectionLabel(stringResource(R.string.proxy_title)) }
        item {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = MuseIcons.route,
                    title = stringResource(R.string.proxy_enable),
                    subtitle = if (enabled) stringResource(R.string.proxy_enabled) else stringResource(R.string.proxy_disabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                SettingsGroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.proxy_type),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    proxyTypes.forEach { (value, label) ->
                        MuseChip(
                            selected = type == value,
                            onClick = { type = value },
                            label = label,
                        )
                    }
                }
                SettingsGroupDivider()
                MuseTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.proxy_host)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                MuseTextField(
                    value = portText,
                    onValueChange = { v ->
                        portText = v.filter { it.isDigit() }.take(5)
                    },
                    label = { Text(stringResource(R.string.proxy_port)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                MuseTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.proxy_username)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                MuseTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.proxy_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsGroupDivider()
                // v2.6.9: No utilice nombres de dominio proxy (bypass).
                // Puntos débiles reales: los usuarios nacionales suelen utilizar agentes globales, pero modelos locales (localhost:11434 etc)
                // Si también utiliza un proxy para servicios de intranet, fallará directamente. Esta columna es la apertura para ellos.
                MuseTextField(
                    value = bypass,
                    onValueChange = { bypass = it },
                    label = { Text(stringResource(R.string.proxy_bypass)) },
                    placeholder = { Text(stringResource(R.string.proxy_bypass_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.proxy_bypass_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        // v2.6.9: Prueba de conexión —— No recibí ningún comentario después de que me asignaron un agente anteriormente.
        // Los usuarios sólo pueden entrar en la conversación.“A ver si puedes enviarlo”, el fallo tampoco es necesariamente un problema del agente.
        item {
            ProxyTestRow(
                proxyConfig = ProxyConfig(
                    enabled = enabled,
                    type = type,
                    host = host.trim(),
                    port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 7890,
                    username = username.trim(),
                    password = password,
                    bypassHosts = bypass,
                ),
            )
        }
        item {
            Text(
                text = stringResource(R.string.proxy_restart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        item {
            MuseCapsuleButton(
                text = stringResource(R.string.action_save),
                onClick = {
                    val port = portText.toIntOrNull()?.coerceIn(1, 65535) ?: 7890
                    scope.launch {
                        saving = true
                        try {
                            settings.saveProxyConfig(
                                ProxyConfig(
                                    enabled = enabled,
                                    type = type,
                                    host = host.trim(),
                                    port = port,
                                    username = username.trim(),
                                    password = password,
                                    bypassHosts = bypass.trim(),
                                ),
                            )
                            MuseToast.show(proxySavedNeedsRestartText)
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                loading = saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
