package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseCornerRadius

/**
 * contenedor de grupo — Un conjunto de contenedores redondeados compartidos.,Las líneas están separadas por finas líneas divisorias.
 *
 * v0.34: Implementación autónoma — Renderizar como tarjeta de Surface(surfaceVariant 0.5f Fondo + esquinas redondeadas 20dp),
 * con [io.zer0.muse.ui.common.surface.CardGroup] Consistencia visual. Reserve este contenedor compatible para páginas secundarias existentes
 * (BackupSection / ThemeSection / AboutSection espera)continuar usando;Página de configuración de primer nivel y página nueva
 * Utilice el elemento CardGroup + DSL directamente.
 *
 * Imágenes agrupadas para alinear las listas de configuraciones del sistema.
 *
 * Uso:
 * ```
 * SettingsGroup {
 *     SettingsItemRow(icon, "Título", "subtítulo") { onClick() }
 *     SettingsGroupDivider()
 *     SettingsSwitchRow(icon, "cambiar", checked) { onChange(it) }
 * }
 * ```
 *
 * @param modifier Modifier(Predeterminado fillMaxWidth)
 * @param content Contenido del grupo(insertado por la persona que llama [SettingsItemRow] / [SettingsSwitchRow] / [SettingsGroupDivider])
 */
@Deprecated(
    "CMP-06: co-exists with CardGroup; consolidate to CardGroup + DSL items",
)
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    // CMP-08: ficha de paseo — Consistente con CardGroup:surface fondo + contornoTrazo variante,
    // No más codificación de ramas claras y oscuras escritas a mano en blanco al 96%/10%.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(MuseCornerRadius.CARD.dp),
        color = colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(0.6.dp, colorScheme.outlineVariant),
    ) {
        Column {
            content()
        }
    }
}
