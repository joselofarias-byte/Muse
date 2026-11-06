@file:Suppress("FunctionNaming", "LongParameterList")

package io.zer0.muse.ui.common.form

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * elección unificada/Panel inferior navegable.
 *
 * Adecuado para escenarios en los que es necesario explorar múltiples opciones, como proveedores, modelos, idiomas, clasificación, etc.
 * Las páginas comerciales solo proporcionan contenido y ya no necesitan implementar títulos, botones de cierre y contenedores de paneles repetidamente.
 */
@Composable
fun MuseSelectionSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    maxHeightFraction: Float = 0.76f,
    content: @Composable ColumnScope.() -> Unit,
) {
    MuseBottomSheet(
        onDismissRequest = onDismissRequest,
        maxHeightFraction = maxHeightFraction,
        horizontalPadding = MusePaddings.screen,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: Ancho del título del componente compartido + límite de línea (conserve como máximo 2 líneas, sin truncamiento agresivo),
                    // El tamaño de fuente anti-ampliación se comprime en una fila con el botón de cerrar a la derecha
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier,
                ) {
                    Icon(
                        imageVector = MuseIcons.x,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}
