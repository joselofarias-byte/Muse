package io.zer0.muse.ui.common.form

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * Dale un trazo fino a la cápsula de selección.
 *
 * En un contenedor con fondo blanco (ventana emergente, tarjeta), sin seleccionar [MuseChip] Igual que el fondo, casi invisible.
 * Bordes en los que se puede hacer clic. Aquí, Modifier se usa para expandir el trazo sin cambiar la lista de parámetros de MuseChip.
 * ——Ya tiene 6 parámetros, que excederían el umbral de verificación estática y afectarían a las personas que llaman.
 *
 * Uso:`MuseChip(..., modifier = Modifier.chipOutline(selected = selected))`
 */
@Composable
fun Modifier.chipOutline(selected: Boolean): Modifier =
    if (selected) {
        this
    } else {
        this.border(
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = MuseShapes.semiLarge,
        )
    }
