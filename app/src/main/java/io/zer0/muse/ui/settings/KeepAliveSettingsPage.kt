package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.schedule.KeepAliveService
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.9: Página de configuración de fondo permanente.
 *
 * Lo que hacemos es "utilizar todos los medios disponibles para mantenernos con vida bajo la autorización del usuario", en lugar de disfrazar u ocultar:
 *  - Servicio de recepción para residentes (el único medio eficaz y compatible con las normas para mantener vivos los procesos)
 *  - WakeLock opcional (permanece conectado incluso cuando la pantalla está apagada, consume más energía)
 *  - Arranque de exención de optimización de batería + arranque de lista blanca de inicio automático del fabricante (reutilizando KeepAliveGuide existente)
 *
 * Específicamente no se debe hacer: cambiar los nombres de los paquetes, falsificar los íconos de otras aplicaciones, ocultar notificaciones.
 * Se trata de firmas de malware que los sistemas pueden detectar y afectar la reputación de los proyectos de código abierto.
 */
@Composable
fun KeepAliveSettingsPage(onBack: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by settings.keepAliveEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val wakeLock by settings.keepAliveWakeLockFlow.collectAsStateWithLifecycle(initialValue = false)
    val keepAliveTitle by settings.keepAliveNotificationTitleFlow
        .collectAsStateWithLifecycle(initialValue = "")
    val keepAliveText by settings.keepAliveNotificationTextFlow
        .collectAsStateWithLifecycle(initialValue = "")
    var showGuide by remember { mutableStateOf(false) }
    var showNotificationEditor by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.settings_keep_alive_title),
        onBack = onBack,
    ) {
        item {
            KeepAliveSwitchesGroup(
                enabled = enabled,
                wakeLock = wakeLock,
                onToggleEnabled = { v ->
                    scope.launch { settings.saveKeepAliveEnabled(v) }
                    if (v) KeepAliveService.start(context) else KeepAliveService.stop(context)
                },
                onToggleWakeLock = { v -> scope.launch { settings.saveKeepAliveWakeLock(v) } },
                onOpenNotificationEditor = { showNotificationEditor = true },
            )
        }
        item {
            Text(
                text = stringResource(R.string.settings_keep_alive_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            )
        }
        item {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.battery,
                    title = stringResource(R.string.settings_keep_alive_battery_title),
                    subtitle = stringResource(R.string.settings_keep_alive_battery_subtitle),
                    onClick = { showGuide = true },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    icon = MuseIcons.sliders,
                    title = stringResource(R.string.settings_keep_alive_rom_title),
                    subtitle = stringResource(R.string.settings_keep_alive_rom_subtitle),
                    onClick = { showGuide = true },
                ) {
                    ChevronRight()
                }
            }
        }
    }

    if (showGuide) {
        KeepAliveGuideDialog(onDismiss = { showGuide = false })
    }

    if (showNotificationEditor) {
        NotificationTextDialog(
            currentTitle = keepAliveTitle,
            currentText = keepAliveText,
            onDismiss = { showNotificationEditor = false },
            onSave = { title, text ->
                scope.launch {
                    settings.saveKeepAliveNotification(title, text)
                    showNotificationEditor = false
                }
            },
        )
    }
}

/**
 * Grupo de interruptores de mantenimiento de actividad: interruptor principal + bloqueo de activación + entrada de copia de notificación.
 *
 * El propósito de escribir una carta por separado es permitir [KeepAliveSettingsPage] Manténgalo en una longitud legible.
 */
@Composable
private fun KeepAliveSwitchesGroup(
    enabled: Boolean,
    wakeLock: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleWakeLock: (Boolean) -> Unit,
    onOpenNotificationEditor: () -> Unit,
) {
    SettingsGroup {
        SettingsSwitchRow(
            icon = MuseIcons.deviceMobile,
            title = stringResource(R.string.settings_keep_alive_title),
            subtitle = stringResource(R.string.settings_keep_alive_subtitle),
            checked = enabled,
            onCheckedChange = onToggleEnabled,
        )
        if (enabled) {
            SettingsGroupDivider()
            SettingsSwitchRow(
                icon = MuseIcons.clock,
                title = stringResource(R.string.settings_keep_alive_wakelock_title),
                subtitle = stringResource(R.string.settings_keep_alive_wakelock_subtitle),
                checked = wakeLock,
                onCheckedChange = onToggleWakeLock,
            )
            SettingsGroupDivider()
            SettingsItemRow(
                icon = MuseIcons.edit,
                title = stringResource(R.string.settings_keep_alive_notification_custom_title),
                subtitle = stringResource(R.string.settings_keep_alive_notification_custom_subtitle),
                onClick = onOpenNotificationEditor,
            ) {
                ChevronRight()
            }
        }
    }
}

/**
 * Editor de copia de notificaciones residente.
 *
 * Este texto se mostrará permanentemente en la barra de notificaciones y el título y el texto se pueden cambiar para evitar exponer directamente el contenido de la conversación a otras personas.
 * Vuelva a la copia predeterminada incorporada cuando ambas se dejen en blanco (consulte KeepAliveService.buildNotification).
 */
@Composable
private fun NotificationTextDialog(currentTitle: String, currentText: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(currentTitle) }
    var text by remember { mutableStateOf(currentText) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_keep_alive_notification_custom_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_keep_alive_notification_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                MuseTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = {
                        Text(stringResource(R.string.settings_keep_alive_notification_title_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MuseTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(stringResource(R.string.settings_keep_alive_notification_text_placeholder))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = { onSave(title, text) },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}
