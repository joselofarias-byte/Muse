package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow

/**
 * Etapa 7: Tarjeta de entrada section — Lista agrupada.
 *
 * usar [SettingsGroup] Paquete múltiple [SettingsItemRow],Finas líneas divisorias entre líneas.,Sin elevación.
 * reemplazar viejo"Cada línea es independiente. Card + spacedBy"Modo de apilamiento.
 *
 * contiene:Assistant Gestión/Favoritos/Libro Mundial/Mensaje Rápido/Modo Inyección.
 *
 * v2.x: El interruptor de memoria a largo plazo se ha movido a la página de configuración de memoria.
 */
@Composable
internal fun AssistantEntriesSection(
    onOpenAssistants: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenLorebooks: () -> Unit,
    onOpenWorldbook: () -> Unit,
    onOpenQuickMessages: () -> Unit,
    onOpenPromptInjections: () -> Unit,
    onOpenSkills: () -> Unit,
) {
    // ── Phase 8.2: Asistente de Gestión + Favoritos + Libro Mundial + Mensaje Rápido + Inyección de Patrones ──
    SectionLabel(stringResource(R.string.settings_assistant_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        SettingsItemRow(
            icon = MuseIcons.users,
            title = stringResource(R.string.settings_assistant_manage),
            subtitle = stringResource(R.string.settings_assistant_manage_subtitle),
            onClick = onOpenAssistants,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.bookmark,
            title = stringResource(R.string.settings_assistant_favorites),
            subtitle = stringResource(R.string.settings_assistant_favorites_subtitle),
            onClick = onOpenFavorites,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.book,
            title = stringResource(R.string.settings_assistant_lorebook),
            subtitle = stringResource(R.string.settings_assistant_lorebook_subtitle),
            onClick = onOpenLorebooks,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        // P1-2: Libro del mundo dinámico(permanente/palabras clave/regular/inyección profunda,independiente de Lorebook)
        SettingsItemRow(
            icon = MuseIcons.globe,
            title = stringResource(R.string.settings_assistant_worldbook),
            subtitle = stringResource(R.string.settings_assistant_worldbook_subtitle),
            onClick = onOpenWorldbook,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.chat,
            title = stringResource(R.string.settings_assistant_quick_messages),
            subtitle = stringResource(R.string.settings_assistant_quick_messages_subtitle),
            onClick = onOpenQuickMessages,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.swapHorizontal,
            title = stringResource(R.string.settings_assistant_prompt_injection),
            subtitle = stringResource(R.string.settings_assistant_prompt_injection_subtitle),
            onClick = onOpenPromptInjections,
        ) {
            ChevronRight()
        }
        SettingsGroupDivider()
        SettingsItemRow(
            icon = MuseIcons.puzzle,
            title = stringResource(R.string.settings_assistant_skills),
            subtitle = stringResource(R.string.settings_assistant_skills_subtitle),
            onClick = onOpenSkills,
        ) {
            ChevronRight()
        }
    }
}
