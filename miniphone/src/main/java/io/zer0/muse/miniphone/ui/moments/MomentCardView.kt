package io.zer0.muse.miniphone.ui.moments

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.miniphone.ui.chat.ChatMenuPositionProvider
import io.zer0.muse.miniphone.ui.chat.MiniPhoneChatAvatar
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.shell.relativeTimeOf
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * una tarjeta dinámica:Autor, texto, imágenes, me gusta, comentarios, entrada de comentarios.
 *
 * Solo el nombre del comentario al que se respondió vive en la tarjeta.([replyTarget]),El resto del estado proviene de [moment];
 * Los comentarios publicados pasan por devoluciones de llamada,Después de que se eliminó ViewModel, trajo nuevos [MomentUi] Vuelve y vuelve a dibujar,
 * La tarjeta en sí no mantiene una "lista de revisión local"—— De esa manera no estará bien una vez que pases la página.
 * @param onDeleteComment Eliminar un comentario que escribiste;Su respuesta se eliminará junto con ella.,Almacenamiento desde arriba
 */
@Composable
internal fun MomentCardView(
    moment: MomentUi,
    onToggleLike: () -> Unit,
    onOpenImage: (images: List<String>, index: Int) -> Unit,
    onComment: (parentId: String?, content: String) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDeleteComment: (commentId: String) -> Unit = {},
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    var replyTarget by remember(moment.id) { mutableStateOf<CommentTarget?>(null) }
    // Mantenga presionado el comentario para que aparezca "Eliminar" y su cuadro de confirmación.:dos cosas separadas,El menú está cerrado y el cuadro de confirmación sigue abierto.
    var commentMenuId by remember(moment.id) { mutableStateOf<String?>(null) }
    var pendingCommentDelete by remember(moment.id) { mutableStateOf<String?>(null) }
    val cardShape = RoundedCornerShape(dimens.cardRadius)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.surface)
            .border(dimens.hairline, colors.divider, cardShape)
            .testTag(MiniPhoneMomentsTags.moment(moment.id))
            .padding(CardPadding),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            MiniPhoneChatAvatar(
                name = moment.authorName,
                avatarUri = moment.authorAvatarUri,
                size = dimens.avatarList,
                modifier = Modifier.testTag(MiniPhoneMomentsTags.author(moment.id)),
            )
            Spacer(Modifier.width(AvatarGap))
            Column(verticalArrangement = Arrangement.spacedBy(ContentGap)) {
                MomentHeader(moment)
                if (moment.content.isNotBlank()) MomentBody(moment)
                if (moment.images.isNotEmpty()) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        MomentImageGrid(
                            momentId = moment.id,
                            images = moment.images,
                            maxWidth = maxWidth,
                            onOpen = { index -> onOpenImage(moment.images, index) },
                        )
                    }
                }
                MomentActionRow(moment = moment, onToggleLike = onToggleLike, onDelete = onDelete)
                if (moment.likes.isNotEmpty()) MomentLikers(moment)
                if (moment.comments.isNotEmpty()) {
                    MomentComments(
                        comments = moment.comments,
                        menuFor = commentMenuId,
                        onReply = { target -> replyTarget = target },
                        onAskMenu = { commentId -> commentMenuId = commentId },
                        onDismissMenu = { commentMenuId = null },
                        onDeleteRequested = { commentId ->
                            commentMenuId = null
                            pendingCommentDelete = commentId
                        },
                    )
                }
                MomentCommentInput(
                    momentId = moment.id,
                    replyTargetName = replyTarget?.senderName,
                    onSend = { content ->
                        onComment(replyTarget?.commentId, content)
                        replyTarget = null
                    },
                )
            }
        }
    }
    val pending = pendingCommentDelete
    if (pending != null) {
        CommentDeleteConfirmDialog(
            onDismiss = { pendingCommentDelete = null },
            onConfirm = {
                pendingCommentDelete = null
                onDeleteComment(pending)
            },
        )
    }
}

/** Nombre del autor y hora:El tiempo se calcula como \"justo ahora / Hace N minutos / HH:mm\",El mismo calibre que la lista de sesiones. */
@Composable
private fun MomentHeader(moment: MomentUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = moment.authorName,
            style = typography.caption.copy(color = colors.accent),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = relativeTimeOf(moment.createdAt),
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier.testTag(MiniPhoneMomentsTags.time(moment.id)),
        )
    }
}

/** Texto. */
@Composable
private fun MomentBody(moment: MomentUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = moment.content,
        style = typography.body.copy(color = colors.textPrimary),
        modifier = Modifier.testTag(MiniPhoneMomentsTags.content(moment.id)),
    )
}

/** Me gusta y eliminar:La eliminación solo aparece en las publicaciones publicadas por usted. */
@Composable
private fun MomentActionRow(moment: MomentUi, onToggleLike: () -> Unit, onDelete: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(ActionCorner))
                .clickable(onClick = onToggleLike)
                .testTag(MiniPhoneMomentsTags.likeButton(moment.id))
                .padding(horizontal = ActionPaddingH, vertical = ActionPaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Heart,
                contentDescription = "Me gusta",
                tint = if (moment.likedByMe) colors.unreadDot else colors.textSecondary,
                modifier = Modifier.size(ActionIconSize),
            )
            Spacer(Modifier.width(ActionIconGap))
            Text(
                text = moment.likes.size.toString(),
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier.testTag(MiniPhoneMomentsTags.likeCount(moment.id)),
            )
        }
        if (moment.isMine) {
            Spacer(Modifier.width(ActionGap))
            Text(
                text = DELETE_LABEL,
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier
                    .clip(RoundedCornerShape(ActionCorner))
                    .clickable(onClick = onDelete)
                    .testTag(MiniPhoneMomentsTags.delete(moment.id))
                    .padding(horizontal = ActionPaddingH, vertical = ActionPaddingV),
            )
        }
        // El rango visible solo se escribe pero no se lee.,Pero el autor debe poder ver que se ha elegido "sólo a sí mismo".
        if (moment.isMine && moment.visibleToJson.isNotBlank()) {
            Spacer(Modifier.width(ActionGap))
            Text(
                text = ONLY_SELF_LABEL,
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier.testTag(MiniPhoneMomentsTags.visibleSelf(moment.id)),
            )
        }
    }
}

/**
 * Como una fila de personas:Encadenarlos según el tiempo de los me gusta.,Terminado en una línea,Sin desplazamiento.
 *
 * Cuando haya demasiada gente, deja de hablar y di "❤️ A,B,…, esperando a 12 personas",No en una gran pila en la tarjeta.
 */
@Composable
private fun MomentLikers(moment: MomentUi) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Text(
        text = likersLineOf(moment.likes),
        style = typography.timestamp.copy(color = colors.textSecondary),
        softWrap = true,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneMomentsTags.likers(moment.id)),
    )
}

/**
 * Área de comentarios:línea de comentario de nivel superior,Su respuesta está un espacio más abajo.
 *
 * Haga clic en cualquier línea para cambiar el cuadro de entrada a "Responder a TA";Los comentarios escritos por usted mismo también se pueden mantener presionados para que aparezca "eliminar",
 * Los comentarios de otras personas no darán esta entrada. —— Entonces la lista de acciones en ese momento está vacía.
 * Las respuestas solo se presionan en una capa.,El prefijo indica quién está en el siguiente nivel.
 */
@Composable
private fun MomentComments(
    comments: List<MomentCommentUi>,
    menuFor: String?,
    onReply: (CommentTarget) -> Unit,
    onAskMenu: (String) -> Unit,
    onDismissMenu: () -> Unit,
    onDeleteRequested: (String) -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(CommentCorner)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.background)
            .border(MiniPhoneTheme.dimens.hairline, colors.divider, shape)
            .padding(CommentBlockPadding),
        verticalArrangement = Arrangement.spacedBy(CommentGap),
    ) {
        comments.forEach { comment ->
            Box {
                CommentLine(
                    line = CommentLineModel(
                        id = comment.id,
                        text = "${comment.senderName}:${comment.content}",
                        createdAt = comment.createdAt,
                        indent = 0.dp,
                        canDelete = comment.isMine,
                        isReply = false,
                    ),
                    onReply = { onReply(CommentTarget(commentId = comment.id, senderName = comment.senderName)) },
                    onAskDelete = { onAskMenu(comment.id) },
                )
                if (menuFor == comment.id) {
                    CommentDeleteMenu(onDismiss = onDismissMenu, onDelete = { onDeleteRequested(comment.id) })
                }
            }
            comment.replies.forEach { reply ->
                Box {
                    CommentLine(
                        line = CommentLineModel(
                            id = reply.id,
                            text = replyText(reply),
                            createdAt = reply.createdAt,
                            indent = ReplyIndent,
                            canDelete = reply.isMine,
                            isReply = true,
                        ),
                        onReply = { onReply(CommentTarget(commentId = reply.id, senderName = reply.senderName)) },
                        onAskDelete = { onAskMenu(reply.id) },
                    )
                    if (menuFor == reply.id) {
                        CommentDeleteMenu(onDismiss = onDismissMenu, onDelete = { onDeleteRequested(reply.id) })
                    }
                }
            }
        }
    }
}

/**
 * Comentario o respuesta de una línea:El texto ocupa una línea.,Hay un tiempo relativo en la oración.
 *
 * Solo las líneas que puedes eliminar se pueden mantener presionadas:Los comentarios de otros no están ligados a una pulsación larga.,No pasa nada si mantienes presionado ——
 * La entrada no existe, lo cual es más directo que abrir un menú emergente que solo se puede cancelar.
 */
@Composable
private fun CommentLine(line: CommentLineModel, onReply: () -> Unit, onAskDelete: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = line.indent)
            .combinedClickable(
                onClick = onReply,
                onLongClick = if (line.canDelete) onAskDelete else null,
            )
            .testTag(line.rowTag()),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.text,
            style = typography.caption.copy(color = colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = relativeTimeOf(line.createdAt),
            style = typography.timestamp.copy(color = colors.textSecondary),
            modifier = Modifier
                .padding(start = CommentTimeGap)
                .testTag(line.timeTag()),
        )
    }
}

/** Comentario de una línea sobre qué dibujar.:Texto, hora, sangría, si se puede eliminar y si es una respuesta. */
@Immutable
private data class CommentLineModel(
    val id: String,
    val text: String,
    val createdAt: Long,
    val indent: Dp,
    val canDelete: Boolean,
    val isReply: Boolean,
) {
    fun rowTag(): String = if (isReply) MiniPhoneMomentsTags.replyRow(id) else MiniPhoneMomentsTags.commentRow(id)

    fun timeTag(): String = if (isReply) MiniPhoneMomentsTags.replyTime(id) else MiniPhoneMomentsTags.commentTime(id)
}

/** Mantenga presionado su propio comentario para que aparezca la superposición de acción.:Sólo hay una opción \"Eliminar\". */
@Composable
private fun CommentDeleteMenu(onDismiss: () -> Unit, onDelete: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val density = LocalDensity.current
    val gapPx = with(density) { MenuGap.roundToPx() }
    val marginPx = with(density) { dimens.pageMargin.roundToPx() }
    val positionProvider = remember(gapPx, marginPx) {
        ChatMenuPositionProvider(alignEnd = false, gapPx = gapPx, marginPx = marginPx)
    }
    val shape = RoundedCornerShape(dimens.cardRadius)
    Popup(
        onDismissRequest = onDismiss,
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = true),
    ) {
        Text(
            text = DELETE_COMMENT_LABEL,
            style = typography.body.copy(color = colors.unreadDot),
            modifier = Modifier
                .testTag(MiniPhoneMomentsTags.COMMENT_MENU_ACTION)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .clickable {
                    onDelete()
                    onDismiss()
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

/** Confirmar antes de eliminar un comentario:Escribe una frase y la respuesta seguirá. */
@Composable
private fun CommentDeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneMomentsTags.COMMENT_DELETE_DIALOG)
                .width(CommentDialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = COMMENT_DELETE_TITLE, style = typography.title.copy(color = colors.textPrimary))
            Spacer(Modifier.height(8.dp))
            Text(text = COMMENT_DELETE_HINT, style = typography.caption.copy(color = colors.textSecondary))
            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = COMMENT_DELETE_CANCEL_LABEL,
                    style = typography.body.copy(color = colors.textSecondary),
                    modifier = Modifier
                        .testTag(MiniPhoneMomentsTags.COMMENT_DELETE_CANCEL)
                        .clip(RoundedCornerShape(CancelCorner))
                        .clickable(onClick = onDismiss)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = DELETE_COMMENT_LABEL,
                    style = typography.body.copy(color = colors.unreadDot),
                    modifier = Modifier
                        .testTag(MiniPhoneMomentsTags.COMMENT_DELETE_CONFIRM)
                        .clip(RoundedCornerShape(CancelCorner))
                        .clickable(onClick = onConfirm)
                        .heightIn(min = DialogActionHeight)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Texto de respuesta;Al responder a otra respuesta,Añade el prefijo \"a quién responder\". */
internal fun replyText(reply: MomentReplyUi): String {
    val to = reply.replyToName
    return if (to == null) {
        "${reply.senderName}:${reply.content}"
    } else {
        "${reply.senderName} Responder $to:${reply.content}"
    }
}

/** Entrada de comentarios:Una línea de cuadros de entrada más un botón de enviar,No se publicará contenido en blanco. */
@Composable
private fun MomentCommentInput(momentId: String, replyTargetName: String?, onSend: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(CommentCorner)
    var draft by rememberSaveable(momentId, replyTargetName) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(colors.background)
                .border(MiniPhoneTheme.dimens.hairline, colors.divider, shape)
                .padding(horizontal = InputPaddingH, vertical = InputPaddingV),
        ) {
            if (draft.isEmpty()) {
                Text(
                    text = replyTargetName?.let { "Responder $it" } ?: COMMENT_PLACEHOLDER,
                    style = typography.timestamp.copy(color = colors.textSecondary),
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = typography.caption.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MiniPhoneMomentsTags.commentInput(momentId)),
            )
        }
        Spacer(Modifier.width(CommentSendGap))
        Box(
            modifier = Modifier
                .size(CommentSendSize)
                .clip(RoundedCornerShape(CommentCorner))
                .clickable(enabled = draft.isNotBlank()) {
                    onSend(draft)
                    draft = ""
                }
                .testTag(MiniPhoneMomentsTags.commentSend(momentId)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiniPhoneIcons.Send,
                contentDescription = "Enviar un comentario",
                tint = if (draft.isNotBlank()) colors.accent else colors.textSecondary,
                modifier = Modifier.size(CommentSendIcon),
            )
        }
    }
}

private val CardPadding = 12.dp
private val AvatarGap = 10.dp
private val ContentGap = 6.dp
private val ActionCorner = 8.dp
private val ActionPaddingH = 2.dp
private val ActionPaddingV = 2.dp
private val ActionGap = 12.dp
private val ActionIconSize = 14.dp
private val ActionIconGap = 4.dp
private val CommentCorner = 8.dp
private val CommentBlockPadding = 8.dp
private val CommentGap = 4.dp
private val ReplyIndent = 10.dp
private val InputPaddingH = 10.dp
private val InputPaddingV = 6.dp
private val CommentSendGap = 8.dp
private val CommentSendSize = 24.dp
private val CommentSendIcon = 16.dp
private val CommentTimeGap = 8.dp
private val MenuGap = 6.dp
private val CommentDialogWidth = 280.dp
private val CancelCorner = 8.dp
private val DialogActionHeight = 36.dp

private const val DELETE_COMMENT_LABEL: String = "Eliminar"
private const val COMMENT_DELETE_TITLE: String = "Eliminar este comentario?"
private const val COMMENT_DELETE_HINT: String = "Este comentario y las respuestas a continuación serán eliminados."
private const val COMMENT_DELETE_CANCEL_LABEL: String = "Cancelar"

private const val DELETE_LABEL: String = "Eliminar"
private const val ONLY_SELF_LABEL: String = "Visible solo para ti"
private const val COMMENT_PLACEHOLDER: String = "di algo"
