package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Línea separadora dentro del grupo — utilizado en [SettingsGroup] entre dos elementos.
 *
 * Deje sangría izquierda de 16 dp(Los divisores de agrupación del sistema de alineación no llegan al borde visual).
 */
@Composable
fun SettingsGroupDivider() {
    HorizontalDivider(
        // CMP-10: Sangría uniforme 56dp(Consistente con MuseDivider por defecto;Anteriormente, deriva de 54 dp)
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f),
        thickness = 0.6.dp,
    )
}
