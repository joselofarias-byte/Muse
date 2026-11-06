package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.PreferenceStore
import io.zer0.muse.data.UserPreference
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v2.6.7 fix (AUDIT F9): Página de gestión de preferencias del usuario.
 *
 * Antecedentes:`PreferenceStore` de `preferencesFlow` / `remove` / `setEnabled` / `clear`
 * Anteriormente, no había llamadas a todas las posiciones. —— El asistente te dirá "No lo vuelvas a hacer en el futuro"…”se registra como una restricción estricta, pero el usuario no puede verla, eliminarla ni desactivarla.
 * Una preferencia mal recordada se vuelve permanente.
 *
 * Aquí las capacidades existentes se conectan a las interfaces disponibles: listar, desactivar/Habilitar, eliminar.
 * Los asistentes aún procesan nuevas entradas `remember_preference` Finalización de la herramienta (esa es la entrada natural a la misma).
 */
@Composable
@Suppress("LongMethod") // Función de combinación a nivel de página: es más fácil leer la ventana emergente y la lista en el mismo lugar, y la división introducirá el paso de parámetros.
fun UserPreferencePage(onBack: () -> Unit, store: PreferenceStore = koinInject()) {
    val preferences by store.preferencesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<UserPreference?>(null) }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_preference_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        if (preferences.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                MuseEmptyState(
                    icon = MuseIcons.check,
                    title = stringResource(R.string.settings_preference_empty),
                    subtitle = stringResource(R.string.settings_preference_empty_hint),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    bottom = MusePaddings.listBottomClearance,
                ),
            ) {
                item(key = "desc") {
                    Text(
                        text = stringResource(R.string.settings_preference_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = MusePaddings.itemGap),
                    )
                }
                item(key = "label") {
                    SectionLabel(stringResource(R.string.settings_preference_subtitle))
                }
                items(preferences, key = { it.id }) { pref ->
                    PreferenceRow(
                        pref = pref,
                        onToggle = { enabled ->
                            scope.launch {
                                runCatching { store.setEnabled(pref.id, enabled) }
                                    .onFailure {
                                        Logger.w("UserPreferencePage", "No se pudo cambiar el estado de preferencia: ${it.message}")
                                    }
                            }
                        },
                        onDelete = { deleting = pref },
                    )
                    if (pref != preferences.lastOrNull()) {
                        SettingsGroupDivider()
                    }
                }
            }
        }
    }

    deleting?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.settings_preference_delete),
            itemName = target.rule,
            consequence = stringResource(R.string.settings_preference_delete_confirm),
            onConfirm = {
                deleting = null
                scope.launch {
                    runCatching { store.remove(target.id) }
                        .onFailure { Logger.w("UserPreferencePage", "Error al eliminar preferencias: ${it.message}") }
                }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun PreferenceRow(pref: UserPreference, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    SettingsGroup {
        SettingsItemRow(
            icon = if (pref.enabled) MuseIcons.check else MuseIcons.circle,
            title = pref.rule,
            titleMaxLines = 3,
            subtitle = buildString {
                if (!pref.enabled) {
                    append(stringResource(R.string.settings_preference_disabled_tag))
                    if (pref.source.isNotBlank()) append(" · ")
                }
                if (pref.source.isNotBlank()) {
                    append(stringResource(R.string.settings_preference_source, pref.source))
                }
            }.ifBlank { null },
            onClick = { onToggle(!pref.enabled) },
        ) {
            Text(
                text = stringResource(
                    if (pref.enabled) {
                        R.string.settings_preference_disable
                    } else {
                        R.string.settings_preference_enable
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.trash,
            title = stringResource(R.string.settings_preference_delete),
            onClick = onDelete,
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.settings_preference_delete),
                onClick = onDelete,
                fillWidth = false,
            )
        }
    }
}
