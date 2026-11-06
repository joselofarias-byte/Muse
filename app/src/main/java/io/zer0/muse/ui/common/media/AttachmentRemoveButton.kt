@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.common.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * Operación de eliminación de archivos adjuntos: círculo visual pequeño, área de clic independiente de 48 dp, sin cruz gigante que cubra la miniatura.
 *
 * CMP-11: El área táctil se dibuja automáticamente con Box + en el que se puede hacer clic (isomorfo a MuseTactileButton),
 * No haga referencia directa al IconButton M3; los puntos exteriores de 48 dp garantizan la posibilidad de hacer clic y los puntos interiores de 22 dp garantizan una apariencia y sensación de ligereza.
 */
@Composable
internal fun AttachmentRemoveButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(MuseIconSizes.touchTarget)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = MuseElevation.low,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = MuseIcons.x,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconTiny),
                )
            }
        }
    }
}
