package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.feature.FeatureFlags
import io.zer0.muse.ui.common.form.MuseSettingsIcon
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Pruebe etiquetas y anclajes semánticos para cada fila de entradas en la página de categorías. */
internal const val SETTINGS_ENTRY_ROW_TAG: String = "settings-entry-row"

/** La línea de entrada de la copia analizada (CardGroup El DSL no está en el contexto de composición, la cadena debe calcularse primero). */
private data class ResolvedEntryRow(
    val entry: SettingsEntrySpec,
    val title: String,
    val subtitle: String?,
)

/** El valor actual de la entrada del interruptor y la devolución de llamada de escritura. */
data class SettingsToggleUiState(val checked: Boolean, val onCheckedChange: (Boolean) -> Unit)

/**
 * Entrada de enrutamiento de página de categoría: conecte el estado en tiempo real en el almacén con la representación pura [SettingsGroupScreen] en.
 *
 * La descripción de la entrada del proxy y el valor del cambio de PII provienen del repositorio de configuración, y estas dos cosas se dejan aquí;
 * La página de categorías en sí solo reconoce la lista de declaraciones + la cadena analizada, lo que facilita la separación de la representación DI y las afirmaciones.
 */
@Composable
fun SettingsGroupPage(
    kind: SettingsGroupKind,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val actionHost = rememberSettingsActionHost()
    val piiGuardEnabled by settings.piiGuardEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val proxyConfig by settings.proxyConfigFlow.collectAsStateWithLifecycle(initialValue = ProxyConfig())

    val proxyDisabled = stringResource(R.string.proxy_disabled)
    val proxySummary = when {
        !proxyConfig.enabled -> proxyDisabled
        proxyConfig.host.isBlank() || proxyConfig.port <= 0 -> proxyDisabled
        else -> "${proxyConfig.type} ${proxyConfig.host}:${proxyConfig.port}"
    }

    SettingsGroupScreen(
        kind = kind,
        onBack = onBack,
        onNavigate = onNavigate,
        onAction = actionHost.run,
        busyAction = actionHost.busyAction,
        subtitles = mapOf(SettingsEntryId.PROXY to proxySummary),
        toggleState = { key ->
            when (key) {
                SettingsToggleKey.PII_GUARD -> SettingsToggleUiState(piiGuardEnabled) { value ->
                    scope.launch { settings.savePiiGuardEnabled(value) }
                }
            }
        },
    )
}

/**
 * Página de categoría de primer nivel: barra superior (volver + nombre de categoría)+ Tarjeta de entrada para esta categoría.
 *
 * Todas las entradas provienen de [SettingsCatalog], aquí solo es responsable de traducir la declaración en líneas: se puede hacer clic en las flechas,
 * El elemento de cambio se asigna al interruptor y la acción en la página se asigna a la línea de acción (se muestra un círculo de progreso durante la ejecución).
 */
@Composable
fun SettingsGroupScreen(
    kind: SettingsGroupKind,
    onBack: () -> Unit,
    onNavigate: (Any) -> Unit,
    onAction: (SettingsActionId) -> Unit = {},
    busyAction: SettingsActionId? = null,
    subtitles: Map<SettingsEntryId, String> = emptyMap(),
    toggleState: (SettingsToggleKey) -> SettingsToggleUiState = { SettingsToggleUiState(false) {} },
) {
    val group = SettingsCatalog.group(kind)
    SettingsSubPageScaffold(title = stringResource(group.titleRes), onBack = onBack) {
        group.sections.forEach { section ->
            item(key = "settings_section_${kind.name}_${section.labelRes ?: 0}") {
                SettingsEntryCard(
                    label = section.labelRes?.let { stringResource(it) },
                    entries = section.entries.filter { it.isAvailable() },
                    onNavigate = onNavigate,
                    onAction = onAction,
                    busyAction = busyAction,
                    subtitles = subtitles,
                    toggleState = toggleState,
                )
            }
        }
    }
}

/** Condiciones de visualización de la página de categoría: la puerta está abierta durante la compilación y esta entrada no está marcada como no mostrada temporalmente. */
private fun SettingsEntrySpec.isAvailable(): Boolean =
    browseVisible && (id != SettingsEntryId.MINIPHONE || FeatureFlags.MINI_PHONE)

@Composable
private fun SettingsEntryCard(
    label: String?,
    entries: List<SettingsEntrySpec>,
    onNavigate: (Any) -> Unit,
    onAction: (SettingsActionId) -> Unit,
    busyAction: SettingsActionId?,
    subtitles: Map<SettingsEntryId, String>,
    toggleState: (SettingsToggleKey) -> SettingsToggleUiState,
) {
    // CardGroup El bloque de contenido es DSL simple, no el contexto compuesto: encabezado/La descripción se analiza primero en una cadena aquí.
    val rows = entries.map { entry ->
        ResolvedEntryRow(
            entry = entry,
            title = stringResource(entry.titleRes),
            subtitle = subtitles[entry.id] ?: entry.descRes?.let { stringResource(it) },
        )
    }
    CardGroup(
        modifier = Modifier.fillMaxWidth(),
        title = label?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        rows.forEach { row ->
            val entry = row.entry
            val action = entry.action
            item(
                key = entry.id,
                modifier = Modifier.testTag(SETTINGS_ENTRY_ROW_TAG),
                onClick = when (action) {
                    is SettingsEntryAction.Navigate -> ({ onNavigate(action.route) })
                    is SettingsEntryAction.Toggle -> null
                    is SettingsEntryAction.Action -> ({ onAction(action.id) })
                },
                leadingContent = { MuseSettingsIcon(entry.icon) },
                headlineContent = { Text(row.title) },
                supportingContent = row.subtitle?.let { text -> { Text(text) } },
                trailingContent = {
                    when (action) {
                        is SettingsEntryAction.Toggle -> {
                            val state = toggleState(action.key)
                            MuseSwitch(checked = state.checked, onCheckedChange = state.onCheckedChange)
                        }
                        is SettingsEntryAction.Action -> if (action.id == busyAction) {
                            MuseSpinner(size = 20.dp)
                        } else {
                            ChevronRight()
                        }
                        is SettingsEntryAction.Navigate -> ChevronRight()
                    }
                },
            )
        }
    }
}
