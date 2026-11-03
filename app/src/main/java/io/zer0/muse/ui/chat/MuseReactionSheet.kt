package io.zer0.muse.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings

// E4 (Especialización front-end H8): Selector de respuesta de emoticonos de mensaje.
// La convención de interfaz de usuario del proyecto prohíbe los caracteres emoji(Ver AuditLogPage/comentario de configuración),Respuesta predeterminada adoptada
// Material Expresión de icono semántico(Facebook fórmula reaction,Sin etiqueta de texto).
// reaction Valor predeterminado de almacenamiento de campo key("like"/"love"/...),UI Las capas se asignan mediante reaccionarIcon/reactionLabelRes.
internal val REACTION_PRESETS: List<Pair<String, ImageVector>> = listOf(
    "like" to MuseIcons.thumbUp,
    "love" to MuseIcons.heart,
    "laugh" to MuseIcons.moodSmile,
    "wow" to MuseIcons.star,
    "sad" to MuseIcons.moodSad,
    "angry" to MuseIcons.flame,
)

/** reaction key → Contenido Descripción Cadena Recurso;Clave desconocida devuelta null(Capa de renderizado omitida). */
internal fun reactionLabelRes(key: String): Int? = when (key) {
    "like" -> R.string.chat_reaction_like
    "love" -> R.string.chat_reaction_love
    "laugh" -> R.string.chat_reaction_laugh
    "wow" -> R.string.chat_reaction_wow
    "sad" -> R.string.chat_reaction_sad
    "angry" -> R.string.chat_reaction_angry
    else -> null
}

/** reaction key → icono;La clave desconocida devuelve nulo. */
internal fun reactionIcon(key: String): ImageVector? = REACTION_PRESETS.firstOrNull { it.first == key }?.second

/** Panel de selección de respuesta de expresión — Respuesta predeterminada horizontal + borrar(Cuando hay una respuesta). */
@Composable
internal fun MuseReactionSheet(current: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = MusePaddings.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.chat_reaction_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.screen)) {
                REACTION_PRESETS.forEach { (key, icon) ->
                    ReactionButton(
                        key = key,
                        icon = icon,
                        selected = key == current,
                        onClick = { onSelect(key) },
                    )
                }
            }
            if (current != null) {
                Spacer(Modifier.height(MusePaddings.tightGap))
                MuseCapsuleButton(
                    text = stringResource(R.string.chat_reaction_clear),
                    onClick = { onSelect(null) },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
        }
    }
}

/** botón de respuesta única — Trazo resaltado seleccionado;Utilice etiquetas localizadas para descripciones de contenido(TalkBack). */
@Composable
private fun ReactionButton(key: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    // Las etiquetas toman valor en el contexto componible(semantics lambda No se puede llamar dentro stringResource)
    val label = reactionLabelRes(key)?.let { stringResource(it) }
    Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .size(MuseIconSizes.touchTarget)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = CircleShape,
            )
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    )
}
