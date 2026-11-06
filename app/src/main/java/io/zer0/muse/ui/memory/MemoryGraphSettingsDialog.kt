@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.memory

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.EdgeBuildFrequency
import io.zer0.muse.data.MemoryGraphConfig
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.theme.MusePaddings

/**
 * v2.6.2(comentarios de los usuarios): Ventana emergente de configuración de constelación de memoria.
 *
 * Ábrelo desde el pequeño botón en la esquina superior derecha de la página de la constelación y controla:
 *  - AI Profundizar la asociación (use un modelo grande para determinar el tipo de relación para bordes débiles, alto costo, desactivado de forma predeterminada)
 *  - Frecuencia suplementaria (finalizar sesión/abrir página de constelación/solo manual)
 *  - Restablezca inmediatamente la asociación (ejecute manualmente un parche de borde)
 */
@Composable
fun MemoryGraphSettingsDialog(
    config: MemoryGraphConfig,
    onDismiss: () -> Unit,
    onSave: (MemoryGraphConfig) -> Unit,
    onRebuildNow: () -> Unit,
) {
    var aiEnhance by remember { mutableStateOf(config.aiEnhanceEnabled) }
    var frequency by remember { mutableStateOf(config.edgeBuildFrequency) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.memory_graph_settings_title),
        confirmText = stringResource(R.string.settings_common_save),
        onConfirm = { onSave(config.copy(aiEnhanceEnabled = aiEnhance, edgeBuildFrequency = frequency)) },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsSwitchRow(
                    title = stringResource(R.string.memory_graph_ai_enhance_title),
                    subtitle = stringResource(R.string.memory_graph_ai_enhance_desc),
                    checked = aiEnhance,
                    onCheckedChange = { aiEnhance = it },
                )
                SettingsGroupDivider()
                Text(
                    text = stringResource(R.string.memory_graph_frequency_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 8.dp),
                )
                Column(
                    modifier = Modifier.padding(horizontal = MusePaddings.screen),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FrequencyOption(
                        text = stringResource(R.string.memory_graph_frequency_session_end),
                        selected = frequency == EdgeBuildFrequency.SESSION_END,
                        onClick = { frequency = EdgeBuildFrequency.SESSION_END },
                    )
                    FrequencyOption(
                        text = stringResource(R.string.memory_graph_frequency_on_open),
                        selected = frequency == EdgeBuildFrequency.ON_OPEN,
                        onClick = { frequency = EdgeBuildFrequency.ON_OPEN },
                    )
                    FrequencyOption(
                        text = stringResource(R.string.memory_graph_frequency_manual),
                        selected = frequency == EdgeBuildFrequency.MANUAL,
                        onClick = { frequency = EdgeBuildFrequency.MANUAL },
                    )
                }
                SettingsGroupDivider()
                Column(modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 8.dp)) {
                    MuseCapsuleButton(
                        text = stringResource(R.string.memory_graph_rebuild_now),
                        onClick = onRebuildNow,
                        fillWidth = true,
                    )
                    Text(
                        text = stringResource(R.string.memory_graph_rebuild_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
    )
}

/** Opciones de frecuencia: Primario sólido si está seleccionado, Texto con trazos si no está seleccionado. */
@Composable
private fun FrequencyOption(text: String, selected: Boolean, onClick: () -> Unit) {
    MuseCapsuleButton(
        text = text,
        onClick = onClick,
        variant = if (selected) IosCapsuleButtonVariant.Primary else IosCapsuleButtonVariant.Text,
        fillWidth = true,
    )
}
