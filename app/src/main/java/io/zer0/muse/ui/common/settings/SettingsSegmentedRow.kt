package io.zer0.muse.ui.common.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

/**
 * Fila de configuración con control segmentado — Icono izquierdo + título + subtítulo,Insertar a continuación [MuseCapsuleTab].
 *
 * Se utiliza para escenarios como estilo de chat, tono, modo de tema, etc. que requieren una selección única dentro de tarjetas agrupadas.,
 * Uniforma el espacio visual entre los controles segmentados y otras filas de tarjetas.
 *
 * @param icon icono izquierdo
 * @param title título principal
 * @param subtitle subtítulo
 * @param options Lista de texto de opciones
 * @param selectedIndex Índice actualmente seleccionado
 * @param onSelectedChange Seleccione cambiar devolución de llamada
 */
@Composable
fun SettingsSegmentedRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(MuseIconSizes.icon),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // LAYOUT-01: Restricciones de viaje(Ver la misma descripción de SettingsSwitchRow)
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MuseCapsuleTab(
                tabs = options,
                selectedIndex = selectedIndex,
                onSelect = onSelectedChange,
                modifier = Modifier.padding(top = MusePaddings.auxGap),
            )
        }
    }
}
