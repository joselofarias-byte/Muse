package io.zer0.muse.ui.common.settings

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.zer0.muse.ui.common.icons.MuseIcons

/**
 * flecha derecha — A la derecha del elemento de configuración ">" indicador.
 *
 * utilizado en [SettingsItemRow] La ubicación final,Indica que se puede hacer clic en esta fila para pasar al siguiente nivel.
 */
@Composable
fun ChevronRight() {
    Icon(
        imageVector = MuseIcons.chevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.outline,
    )
}
