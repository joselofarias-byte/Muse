package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * icono de configuración — Estilo de línea en blanco y negro.
 *
 * Representa directamente el icono de línea de 24 dp y el color sigue en la superficie (se adapta automáticamente al brillo)./modo oscuro).
 *
 * @param icon Icono de línea (recomendado Icons.Outlined.*)
 * @param modifier modificador
 */
@Composable
fun MuseSettingsIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(MuseIconSizes.icon),
    )
}
