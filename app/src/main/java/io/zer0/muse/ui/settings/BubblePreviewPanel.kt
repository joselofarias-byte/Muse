@file:Suppress("FunctionNaming")

package io.zer0.muse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isUnspecified
import io.zer0.muse.R
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.ui.theme.MuseBubbleStyles
import io.zer0.muse.ui.theme.MusePaddings

/** v2.5.0: Vista previa del estilo del cuerpo — Misma fuente que el diseño de texto de MessageBubble(Escala del tamaño de fuente + espaciado entre caracteres). */
@Composable
private fun previewBodyStyle(prefs: ChatPreferences): TextStyle {
    val bodyBase = MaterialTheme.typography.bodyMedium
    val bodyScale = prefs.messageFontScale.coerceIn(0.85f, 1.3f)
    return bodyBase.copy(
        fontSize = bodyBase.fontSize * bodyScale,
        lineHeight = bodyBase.lineHeight.let { if (it.isUnspecified) it else it * bodyScale },
        letterSpacing = prefs.messageLetterSpacingEm.coerceIn(-0.02f, 0.1f).em,
    )
}

/**
 * v2.5.0: Panel de vista previa del diseño del chat — El efecto compuesto de la configuración actual de la apariencia del chat se muestra en tiempo real en la página de configuración.
 *
 * Anular: tamaño de fuente del mensaje/Espaciado entre caracteres, redondeo de burbujas/pancarta,MOOD/Piense en visualizaciones de bloques, marcas de tiempo y nombres de modelos.
 * Datos de demostración estática; El cálculo de estilo tiene el mismo origen que MessageBubble.(MuseBubbleStyles + Misma escala de diseño),
 * garantia"Lo que ves es lo que obtienes".
 */
@Composable
internal fun BubblePreviewPanel(prefs: ChatPreferences, modifier: Modifier = Modifier) {
    val bodyStyle = previewBodyStyle(prefs)
    Column(modifier = modifier.fillMaxWidth().padding(MusePaddings.cardInner)) {
        PreviewUserBubble(prefs, bodyStyle)
        Spacer(Modifier.height(10.dp))
        PreviewAssistantBubble(prefs, bodyStyle)
    }
}

/** Vista previa: mensajes de usuario (derecha,Fondo claro de color principal.,esquinas redondeadas/pancarta/Las marcas de tiempo se reflejan en tiempo real). */
@Composable
private fun PreviewUserBubble(prefs: ChatPreferences, bodyStyle: TextStyle) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        val shape = MuseBubbleStyles.userBubbleShape(prefs.bubbleRadius.toFloat())
        Column(
            modifier = Modifier
                .then(if (prefs.bubbleFullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(max = 260.dp))
                .clip(shape)
                .background(MuseBubbleStyles.userSurfaceColor())
                .border(1.dp, MuseBubbleStyles.userBorderColor(), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_preview_user_bubble),
                style = bodyStyle,
                color = MuseBubbleStyles.userContentColor(),
            )
            if (prefs.showTimestamp) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_preview_timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Vista previa:AI Mensaje (izquierda,tarjeta de color claro;MOOD/Piense en filas de etiquetas con nombres de modelos mostrados por interruptor). */
@Composable
private fun PreviewAssistantBubble(prefs: ChatPreferences, bodyStyle: TextStyle) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        val shape = MuseBubbleStyles.assistantBubbleShape(prefs.bubbleRadius.toFloat())
        Column(
            modifier = Modifier
                .then(if (prefs.bubbleFullWidth) Modifier.fillMaxWidth() else Modifier.widthIn(max = 260.dp))
                .clip(shape)
                .background(MuseBubbleStyles.assistantSurfaceColor())
                .border(1.dp, MuseBubbleStyles.assistantBorderColor(), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (prefs.showMoodBlock || prefs.showReasoning) {
                val tags = buildList {
                    if (prefs.showMoodBlock) add("MOOD")
                    if (prefs.showReasoning) add(stringResource(R.string.chat_reasoning_title))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    tags.forEach { tag ->
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.settings_preview_assistant_bubble),
                style = bodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (prefs.showModelName) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_preview_model_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
