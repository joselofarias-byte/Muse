@file:Suppress("LongParameterList", "CyclomaticComplexMethod", "FunctionNaming")

package io.zer0.muse.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.zer0.ai.core.UIMessage
import io.zer0.muse.transformer.MoodSkinParser
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.messageCopyText
import io.zer0.muse.ui.theme.MuseIconSizes

/**
 * v2.6.0: Organice rápidamente botones de acción individuales.
 *
 * Reúna en un solo lugar cada representación de botones y comportamiento de clic que originalmente estaban dispersos en MessageBubble.
 * con [QuickAction] Todos los grupos tienen correspondencia uno a uno. El estilo es completamente consistente con el diseño del acceso directo original (outline color,
 * touchTarget tamaño,iconSmall icono), el comportamiento se alinea uno por uno con la implementación original:
 * - COPY: Copie el texto (incluida la lógica de empalme de razonamiento la maneja la persona que llama messageCopyText)
 * - REASONING: Copie solo el proceso de pensamiento (después de la limpieza)
 * - DELETE: Activar confirmación secundaria (destructiva, roja)
 * - El resto de acciones son consistentes con mantener presionada la opción del mismo nombre en el panel "Más".
 */
@Composable
internal fun QuickActionButton(
    action: QuickAction,
    msg: UIMessage,
    isSpeaking: Boolean,
    favorite: Boolean,
    onCopyMessage: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onQuote: () -> Unit,
    onAnnotate: () -> Unit,
    onDelegate: () -> Unit,
    onFork: () -> Unit,
    onMultiSelect: () -> Unit,
    onInfo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleTts: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onTranslate: () -> Unit,
    onShare: () -> Unit,
) {
    val description = when (action) {
        QuickAction.REASONING ->
            stringResource(io.zer0.muse.R.string.action_copy) + " " +
                stringResource(QuickAction.REASONING.labelRes)
        QuickAction.TTS ->
            if (isSpeaking) {
                stringResource(io.zer0.muse.R.string.chat_tts_stop)
            } else {
                stringResource(io.zer0.muse.R.string.chat_tts_play)
            }
        QuickAction.FAVORITE ->
            if (favorite) {
                stringResource(io.zer0.muse.R.string.chat_favorite_remove)
            } else {
                stringResource(io.zer0.muse.R.string.chat_favorite_add)
            }
        else -> stringResource(action.labelRes)
    }
    val tint = when (action) {
        QuickAction.DELETE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val icon = when (action) {
        QuickAction.FAVORITE -> MuseIcons.star
        QuickAction.TTS -> MuseIcons.play
        else -> action.icon
    }
    MuseTactileButton(
        icon = icon,
        onClick = {
            when (action) {
                QuickAction.COPY -> onCopyMessage(messageCopyText(msg.content, msg.reasoning))
                QuickAction.REASONING -> onCopyMessage(MoodSkinParser.cleanForExport(msg.reasoning.orEmpty()))
                QuickAction.TRANSLATE -> onTranslate()
                QuickAction.SHARE -> onShare()
                QuickAction.REGENERATE -> onRegenerate()
                QuickAction.DELETE -> onDelete()
                QuickAction.QUOTE -> onQuote()
                QuickAction.ANNOTATE -> onAnnotate()
                QuickAction.DELEGATE -> onDelegate()
                QuickAction.FORK -> onFork()
                QuickAction.MULTI_SELECT -> onMultiSelect()
                QuickAction.INFO -> onInfo()
                QuickAction.FAVORITE -> onToggleFavorite()
                QuickAction.TTS -> onToggleTts()
                QuickAction.EDIT -> onEdit()
                QuickAction.FORWARD -> onForward()
            }
        },
        contentDescription = description,
        tint = tint,
        size = MuseIconSizes.touchTarget,
        iconSize = MuseIconSizes.iconSmall,
    )
}
