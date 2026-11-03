package io.zer0.muse.automation.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.settings.SettingsSubPageScaffold
import kotlinx.coroutines.launch

/**
 * UI Página de configuración de automatización.
 *
 * v2.x: La configuración de permisos de tres canales ha convergido en la página "Asistente de configuración de permisos"(Elimine la duplicación capa por capa entre dos páginas);
 * Esta pagina esta reservada:Descripción de funciones, entrada del asistente, disposición de acciones, prueba de lectura de pantalla y diagnóstico del desarrollador.
 */
@Suppress("LongMethod", "FunctionNaming")
@Composable
fun AutomationSettingsPage(
    manager: AutomationManager,
    onBack: () -> Unit = {},
    /** v1.xxx: F-24 Entrada de arreglo — Vaya a la página de tareas programadas para programar acciones automatizadas. */
    onOpenScheduledTasks: () -> Unit = {},
    /** v2.x: Entrada al asistente de configuración de permisos. */
    onOpenPermissionWizard: () -> Unit = {},
    /** Tasker/Interruptor de automatización externo; Opcional para mantener las pruebas de la página Robolectric sin Koin. */
    settings: SettingsRepository? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val taskerEnabled by settings?.taskerTriggerEnabledFlow?.collectAsStateWithLifecycle(initialValue = false)
        ?: remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ScreenTestResult?>(null) }

    SettingsSubPageScaffold(
        title = stringResource(R.string.automation_settings_title),
        onBack = onBack,
    ) {
        // Tarjeta de instrucciones superior
        item(key = "intro") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = MuseIcons.computer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.automation_settings_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // v2.x: La configuración de permisos de tres canales ha convergido en el "Asistente de configuración de permisos"(Elimine la duplicación capa por capa entre dos páginas)
        item(key = "permissions-entry") {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.shieldCheck,
                    title = stringResource(R.string.permission_wizard_title),
                    subtitle = stringResource(R.string.permission_wizard_desc),
                    onClick = onOpenPermissionWizard,
                ) {
                    ChevronRight()
                }
            }
        }

        // AUD-20261011-TASKER-ABUSE: exported broadcast remains opt-in and visibly warns about model cost.
        item(key = "tasker-trigger") {
            SettingsGroup {
                SettingsItemRow(
                    icon = MuseIcons.link,
                    title = stringResource(R.string.automation_tasker_title),
                    subtitle = stringResource(R.string.automation_tasker_desc),
                    onClick = null,
                ) {
                    MuseSwitch(
                        checked = taskerEnabled,
                        onCheckedChange = settings?.let { repository ->
                            { enabled -> scope.launch { repository.saveTaskerTriggerEnabled(enabled) } }
                        },
                        contentDescription = stringResource(R.string.automation_tasker_title),
                    )
                }
            }
        }

        // v1.xxx: F-24 Cómo orquestar acciones automatizadas — Descripción + Ir a la página de tareas programadas con un clic
        item(key = "orchestration") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = MuseIcons.clock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.automation_orchestration_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.automation_orchestration_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    MuseCapsuleButton(
                        text = stringResource(R.string.automation_orchestration_action),
                        onClick = onOpenScheduledTasks,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = MuseIcons.clock,
                    )
                }
            }
        }

        // botón de prueba
        item(key = "test") {
            MuseCapsuleButton(
                text = if (testing) {
                    stringResource(R.string.automation_test_running)
                } else {
                    stringResource(R.string.automation_test_action)
                },
                onClick = {
                    if (testing) return@MuseCapsuleButton
                    scope.launch {
                        testing = true
                        testResult = null
                        val outcome = runCatching {
                            manager.refreshPermissions()
                            val screen = manager.readScreen()
                            if (screen.source == "unavailable") {
                                ScreenTestResult(
                                    context.getString(R.string.automation_test_no_channel),
                                    success = false,
                                )
                            } else {
                                val unknown = context.getString(R.string.automation_test_unknown)
                                // ST-04: Los nombres de los paquetes y las fuentes de datos son información de diagnóstico del desarrollador.,Doblar en el área de plegado devInfo
                                ScreenTestResult(
                                    message = buildString {
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_node_count,
                                                screen.nodes.size,
                                            ),
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_resolution,
                                                screen.screenWidth,
                                                screen.screenHeight,
                                            ),
                                        )
                                    }.trimEnd('\n'),
                                    success = true,
                                    devInfo = buildString {
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_current_app,
                                                screen.packageName ?: unknown,
                                            ),
                                        )
                                        appendLine(
                                            context.getString(
                                                R.string.automation_test_source,
                                                screen.source,
                                            ),
                                        )
                                    }.trimEnd('\n'),
                                )
                            }
                        }.getOrElse {
                            ScreenTestResult(
                                context.getString(
                                    R.string.automation_test_failed,
                                    it.message ?: context.getString(R.string.automation_test_unknown),
                                ),
                                success = false,
                            )
                        }
                        testResult = outcome
                        testing = false
                    }
                },
                enabled = !testing,
                loading = testing,
                leadingIcon = MuseIcons.play,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Resultados de la prueba
        testResult?.let { result ->
            item(key = "test-result") {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (result.success) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // ST-04: La información de diagnóstico del desarrollador está colapsada de forma predeterminada,Expandir para mostrar el nombre del paquete y la fuente de datos
                        result.devInfo?.let { devInfo ->
                            var showDevInfo by remember { mutableStateOf(false) }
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showDevInfo = !showDevInfo }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.automation_developer_info),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = if (showDevInfo) {
                                        MuseIcons.chevronUp
                                    } else {
                                        MuseIcons.chevronDown
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (showDevInfo) {
                                Text(
                                    text = devInfo,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lectura de pantalla única de los resultados de la autoprueba:Copia visible del usuario + éxito(Decidir el color de la tarjeta de referencia).
 * devInfo Información de diagnóstico para desarrolladores(nombre del paquete/fuente de datos),ST-04 La visualización se contrajo de forma predeterminada.
 */
private data class ScreenTestResult(
    val message: String,
    val success: Boolean,
    val devInfo: String? = null,
)
