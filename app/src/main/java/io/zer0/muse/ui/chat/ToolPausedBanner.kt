package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v2.6.7: Banner de la página de chat cuando la ejecución de la herramienta se pausa globalmente.
 *
 * Antecedentes (comentarios de los usuarios): Después de activar "Pausar la ejecución de la herramienta" en la configuración, cada herramienta regresa
 * "La herramienta xxx ha sido suspendida y no se han realizado operaciones reales en esta ronda.", tarda 0 ms y completa 12 pasos en una ronda"falló".
 * La perspectiva del usuario es muy similar a"Internet no funciona/la herramienta no funciona", y la verdad es un interruptor de configuración.
 *
 * Este banner solo hace dos cosas:
 *  1. Deje en claro que la herramienta no se ejecutará realmente ahora;
 *  2. Restaure las exportaciones con un solo clic, eliminando la necesidad de pasar por dos niveles de configuración.
 *
 * No poner deliberadamente un color de advertencia: es una elección del usuario, no un error.
 */
@Composable
fun ToolPausedBanner(onResume: () -> Unit = {}, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MuseShapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MuseIcons.pause,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.chat_tool_paused_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.chat_tool_paused_banner_action),
                onClick = onResume,
                fillWidth = false,
                variant = io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Secondary,
            )
        }
    }
}
