package io.zer0.muse.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v1.0.47 P6: Agent Mode Tarjetas recordatorias mejoradas.
 *
 * tres estados(Se puede apilar):
 *  - bloqueo de sesión:Agent Sesión bloqueada después de habilitar el modo,Mostrar solo estado,No se puede cerrar
 *  - Degradación de herramienta débil:La capacidad de llamada de la herramienta del modelo actual es débil,Degradado automáticamente a serie,se puede cerrar
 *  - Agent Mode Consejos:Otros consejos relacionados con el modo agente,se puede cerrar
 *
 * El estilo visual sigue la parte superior de ChatScreen. Banner(tertiaryContainer + MuseShapes.medium),
 * por [ChatScreen] encima de la lista de mensajes según [ChatUiState] Representación condicional de campo.
 */
@Composable
fun AgentModeHintCard(
    isSessionLocked: Boolean,
    weakToolHint: String?,
    agentModeHint: String?,
    onDismissWeakToolHint: () -> Unit,
    onDismissAgentModeHint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        // 1) estado de bloqueo de sesión(permanente,No se puede cerrar)
        AnimatedVisibility(
            visible = isSessionLocked,
            // v2.6.7: Crece en su lugar (zoom + aparición gradual), ya no se estira verticalmente.
            enter = MuseMotion.scaleFadeEnter(initialScale = 0.96f),
            exit = MuseMotion.scaleFadeExit(),
        ) {
            HintRow(
                icon = MuseIcons.lock,
                title = stringResource(R.string.chat_agent_session_locked),
                titleColor = MaterialTheme.colorScheme.onSecondaryContainer,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        }

        // 2) Consejos para degradar herramientas débiles(se puede cerrar)
        AnimatedVisibility(
            visible = !weakToolHint.isNullOrEmpty(),
            // v2.6.7: Crece en su lugar (zoom + aparición gradual), ya no se estira verticalmente.
            enter = MuseMotion.scaleFadeEnter(initialScale = 0.96f),
            exit = MuseMotion.scaleFadeExit(),
        ) {
            HintRow(
                icon = MuseIcons.alertTriangle,
                title = stringResource(R.string.chat_agent_weak_tool_title),
                subtitle = weakToolHint,
                titleColor = MaterialTheme.colorScheme.onErrorContainer,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                action = {
                    MuseCapsuleButton(
                        text = stringResource(R.string.chat_agent_hint_dismiss),
                        onClick = onDismissWeakToolHint,
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                },
            )
        }

        // 3) Agent Mode Consejos generales(se puede cerrar)
        AnimatedVisibility(
            visible = !agentModeHint.isNullOrEmpty(),
            // v2.6.7: Crece en su lugar (zoom + aparición gradual), ya no se estira verticalmente.
            enter = MuseMotion.scaleFadeEnter(initialScale = 0.96f),
            exit = MuseMotion.scaleFadeExit(),
        ) {
            HintRow(
                icon = MuseIcons.chat,
                title = stringResource(R.string.chat_agent_weak_tool_title),
                subtitle = agentModeHint,
                titleColor = MaterialTheme.colorScheme.onTertiaryContainer,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                action = {
                    MuseCapsuleButton(
                        text = stringResource(R.string.chat_agent_hint_dismiss),
                        onClick = onDismissAgentModeHint,
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                },
            )
        }
    }
}

@Composable
private fun HintRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    titleColor: androidx.compose.ui.graphics.Color,
    containerColor: androidx.compose.ui.graphics.Color,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = containerColor,
        shape = MuseShapes.medium,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.itemGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = titleColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = titleColor,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            action?.invoke()
        }
    }
}
