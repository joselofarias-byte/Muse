package io.zer0.muse.miniphone.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.data.chat.GROUP_TITLE_MAX_LENGTH
import io.zer0.muse.miniphone.data.chat.MIN_GROUP_MEMBERS_AFTER_REMOVE
import io.zer0.muse.miniphone.data.chat.normalizeGroupTitle
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.shell.GroupPickHeader
import io.zer0.muse.miniphone.ui.shell.GroupPickList
import io.zer0.muse.miniphone.ui.shell.MiniPhoneContactSection
import io.zer0.muse.miniphone.ui.shell.contactSectionsOf
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme

/**
 * Cuatro cosas que puedes hacer en la página de miembros del grupo:Cambie el nombre del grupo, agregue personas, elimine a alguien, disuélvalo y salga.
 *
 * La página en sí no reconoce la biblioteca.,Tampoco determina permisos —— Si puedes entrar, significa que es una conversación grupal.(La barra superior del mensaje privado no tiene esta entrada en absoluto.),
 * Cada acción se da a ViewModel,Después de escribir el flujo de tablas de miembros y filas de sesión, recupere la interfaz.
 */
@Immutable
internal class GroupAdminActions(
    val onRename: (title: String) -> Unit = {},
    val onAddMembers: (characterIds: List<String>) -> Unit = {},
    val onRemoveMember: (characterId: String) -> Unit = {},
    val onDisband: () -> Unit = {},
)

/** Qué pueden hacer los miembros. */
internal enum class GroupMemberAction(
    val label: String,
) {
    REMOVE("Salir del chat grupal"),
}

/**
 * Elementos de acción en filas de miembros,Una lista vacía significa que no hay nada que hacer en esta línea.
 *
 * No des nada cuando solo quede una persona:Debe haber un personaje en el grupo que pueda hablar.,Si lo borras hasta dejarlo vacío, este grupo ya no podrá hablar.
 */
internal fun groupMemberActionsFor(memberCount: Int): List<GroupMemberAction> =
    if (memberCount <= MIN_GROUP_MEMBERS_AFTER_REMOVE) emptyList() else listOf(GroupMemberAction.REMOVE)

/** ¿Quién más se puede agregar?:Excluye todos los personajes proporcionados por el anfitrión que ya están en el grupo.,Los que no tienen nombre tampoco figuran en la lista. */
internal fun addableCharactersOf(characters: List<MpCharacter>, members: List<ChatMemberUi>): List<MpCharacter> {
    val inGroup = members.map { member -> member.characterId }.toSet()
    return characters.filter { character -> character.id !in inGroup && character.name.isNotBlank() }
}

/**
 * Página de miembros del grupo:Nombre del grupo, lista de miembros y cuatro acciones de gestión.
 *
 * El orden es la parte que le corresponde a Curry al unirse al grupo.(Al crear un grupo, siga el orden de selección.),No más colas aquí ——
 * El orden del mismo grupo de personas en la página de miembros del grupo y en la página de creación del grupo es el mismo.,Sólo entonces se pudo ver que "estas son las personas que acabamos de seleccionar".
 *
 * @param members miembros del grupo(Solo roles),Ya arreglado según el orden de incorporación al grupo.
 * @param title Nombre del grupo actual;Una vez completado el cambio, la alta dirección regresará con un nuevo nombre y lo rediseñará.
 * @param characters Todos los roles del anfitrión.,"¿A quién más puedo agregar?" Cuenta desde aquí
 * @param onBack Volver a la página de chat;Después de disolverse, debe regresar a la lista de mensajes.,Determinado por la persona que llama
 */
@Composable
internal fun GroupMembersPage(
    members: List<ChatMemberUi>,
    title: String,
    characters: List<MpCharacter>,
    onBack: () -> Unit,
    actions: GroupAdminActions = GroupAdminActions(),
    modifier: Modifier = Modifier,
) {
    val colors = MiniPhoneTheme.colors
    var editingTitle by rememberSaveable { mutableStateOf(false) }
    var addingMembers by rememberSaveable { mutableStateOf(false) }
    // Colección seleccionada para agregar página:Haga clic primero,Por tanto, el orden de unión es estable.
    var addSelection by rememberSaveable { mutableStateOf(listOf<String>()) }
    var menuFor by remember(members) { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<ChatMemberUi?>(null) }
    var confirmingDisband by rememberSaveable { mutableStateOf(false) }
    val removable = groupMemberActionsFor(members.size).isNotEmpty()

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(MiniPhoneChatTags.GROUP_MEMBERS_PAGE)
            .background(colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            GroupMembersHeader(count = members.size + 1, onBack = onBack)
            GroupTitleRow(title = title, onClick = { editingTitle = true })
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(items = members, key = { member -> member.characterId }) { member ->
                    Box {
                        GroupMemberRow(
                            member = member,
                            onLongPress = if (removable) {
                                { menuFor = member.characterId }
                            } else {
                                null
                            },
                        )
                        if (menuFor == member.characterId) {
                            MemberRowMenu(
                                onDismiss = { menuFor = null },
                                onRemove = {
                                    menuFor = null
                                    pendingRemove = member
                                },
                            )
                        }
                    }
                }
            }
            MemberAdminFooter(
                onAdd = {
                    addSelection = emptyList()
                    addingMembers = true
                },
                onDisband = { confirmingDisband = true },
            )
        }
        if (editingTitle) {
            GroupTitleEditDialog(
                initialTitle = title,
                onDismiss = { editingTitle = false },
                onSave = { newTitle ->
                    editingTitle = false
                    actions.onRename(newTitle)
                },
            )
        }
        if (addingMembers) {
            GroupAddMembersPage(
                sections = contactSectionsOf(addableCharactersOf(characters, members)),
                selectedIds = addSelection.toSet(),
                onToggle = { characterId ->
                    addSelection = if (characterId in addSelection) {
                        addSelection - characterId
                    } else {
                        addSelection + characterId
                    }
                },
                onBack = {
                    addingMembers = false
                    addSelection = emptyList()
                },
                onDone = {
                    actions.onAddMembers(addSelection)
                    addingMembers = false
                    addSelection = emptyList()
                },
            )
        }
        val removing = pendingRemove
        if (removing != null) {
            MemberRemoveDialog(
                name = removing.name,
                onDismiss = { pendingRemove = null },
                onConfirm = {
                    pendingRemove = null
                    actions.onRemoveMember(removing.characterId)
                },
            )
        }
        if (confirmingDisband) {
            DisbandGroupDialog(
                onDismiss = { confirmingDisband = false },
                onConfirm = {
                    confirmingDisband = false
                    actions.onDisband()
                },
            )
        }
    }
}

/** barra superior:Volver + \"Miembros del grupo\"+ Número de personas. */
@Composable
private fun GroupMembersHeader(count: Int, onBack: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(GroupMembersButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag(MiniPhoneChatTags.GROUP_MEMBERS_BACK),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = "Regresar",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(GroupMembersIconSize),
                )
            }
            Text(
                text = GROUP_MEMBERS_TITLE,
                style = typography.title.copy(color = colors.textPrimary),
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = memberCountLabelOf(count),
                style = typography.timestamp.copy(color = colors.textSecondary),
                modifier = Modifier.testTag(MiniPhoneChatTags.GROUP_MEMBERS_COUNT),
            )
        }
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
    }
}

/** Una línea del nombre del grupo:Haga clic para cambiar el nombre. La frase \"Haga clic para modificar\" a la derecha indica la entrada.,Nadie necesita adivinar. */
@Composable
private fun GroupTitleRow(title: String, onClick: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.GROUP_TITLE_ROW)
            .clickable(onClick = onClick)
            .padding(horizontal = dimens.pageMargin, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = GROUP_TITLE_LABEL,
            style = typography.timestamp.copy(color = colors.textSecondary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title.ifBlank { UNTITLED_GROUP_NAME },
            style = typography.body.copy(color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .testTag(MiniPhoneChatTags.GROUP_TITLE_VALUE),
        )
        Text(
            text = GROUP_TITLE_EDIT_HINT,
            style = typography.timestamp.copy(color = colors.accent),
        )
    }
}

/** miembros de un partido:avatar + nombre;¿Se puede quitar de [onLongPress] ¿Se ha tomado una decisión? */
@Composable
private fun GroupMemberRow(member: ChatMemberUi, onLongPress: (() -> Unit)?) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MiniPhoneChatTags.member(member.characterId))
            .combinedClickable(
                onClick = { },
                onLongClick = onLongPress,
            )
            .padding(horizontal = dimens.pageMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        MiniPhoneChatAvatar(name = member.name, avatarUri = member.avatarUri, size = dimens.avatarList)
        Spacer(Modifier.width(12.dp))
        Text(
            text = member.name,
            style = typography.body.copy(color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** La acción superpuesta que aparece al presionar prolongadamente a un miembro:Sólo hay una opción para \"salir del chat grupal\". */
@Composable
private fun MemberRowMenu(onDismiss: () -> Unit, onRemove: () -> Unit) {
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
            text = GroupMemberAction.REMOVE.label,
            style = typography.body.copy(color = colors.unreadDot),
            modifier = Modifier
                .testTag(MiniPhoneChatTags.MEMBER_MENU_REMOVE)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .clickable {
                    onRemove()
                    onDismiss()
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}

/** Pie de página dos acciones:Agregar personas,y disolver el grupo. */
@Composable
private fun MemberAdminFooter(onAdd: () -> Unit, onDisband: () -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(Modifier.fillMaxWidth().background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
        Text(
            text = ADD_MEMBER_LABEL,
            style = typography.body.copy(color = colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MiniPhoneChatTags.GROUP_ADD_ENTRY)
                .clickable(onClick = onAdd)
                .padding(horizontal = dimens.pageMargin, vertical = 14.dp),
        )
        Box(Modifier.fillMaxWidth().height(dimens.hairline).background(colors.divider))
        Text(
            text = DISBAND_LABEL,
            style = typography.body.copy(color = colors.unreadDot),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MiniPhoneChatTags.GROUP_DISBAND_ENTRY)
                .clickable(onClick = onDisband)
                .padding(horizontal = dimens.pageMargin, vertical = 14.dp),
        )
    }
}

/** Cambiar nombre de grupo:una pequeña capa elástica,Hay un cuadro de entrada en el interior. Los nombres vacíos no se guardan,Truncamiento extralargo. */
@Composable
private fun GroupTitleEditDialog(initialTitle: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    var draft by rememberSaveable(initialTitle) { mutableStateOf(initialTitle) }
    val normalized = normalizeGroupTitle(draft)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_DIALOG)
                .width(GroupDialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = GROUP_TITLE_EDIT_TITLE, style = typography.title.copy(color = colors.textPrimary))
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WindowCorner))
                    .background(colors.background)
                    .border(dimens.hairline, colors.divider, RoundedCornerShape(WindowCorner))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = typography.caption.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MiniPhoneChatTags.GROUP_TITLE_EDIT_FIELD),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = titleHintOf(normalized.length),
                style = typography.timestamp.copy(color = colors.textSecondary),
            )
            Spacer(Modifier.height(14.dp))
            DialogActions(
                confirmColor = colors.accent,
                confirmTag = MiniPhoneChatTags.GROUP_TITLE_EDIT_SAVE,
                confirmEnabled = normalized.isNotEmpty(),
                confirmLabel = SAVE_LABEL,
                cancelTag = MiniPhoneChatTags.GROUP_TITLE_EDIT_CANCEL,
                onCancel = onDismiss,
                onConfirm = { onSave(normalized) },
            )
        }
    }
}

/** Confirmación antes de eliminar a un miembro:Deje claro que lo que dijo no desaparecerá. */
@Composable
private fun MemberRemoveDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        tag = MiniPhoneChatTags.MEMBER_REMOVE_DIALOG,
        title = "poner${name}Salir del chat grupal?",
        hint = REMOVE_HINT,
        confirmTag = MiniPhoneChatTags.MEMBER_REMOVE_CONFIRM,
        cancelTag = MiniPhoneChatTags.MEMBER_REMOVE_CANCEL,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** Confirmación antes de la disolución:todo el grupo se habrá ido,La copia debe indicar que no es recuperable. */
@Composable
private fun DisbandGroupDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        tag = MiniPhoneChatTags.GROUP_DISBAND_DIALOG,
        title = DISBAND_TITLE,
        hint = DISBAND_HINT,
        confirmTag = MiniPhoneChatTags.GROUP_DISBAND_CONFIRM,
        cancelTag = MiniPhoneChatTags.GROUP_DISBAND_CANCEL,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** La única diferencia entre los dos cuadros de confirmación es la copia y la etiqueta.,La forma es la misma que la ubicación del botón. */
@Composable
private fun ConfirmDialog(
    tag: String,
    title: String,
    hint: String,
    confirmTag: String,
    cancelTag: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    val shape = RoundedCornerShape(dimens.cardRadius)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .testTag(tag)
                .width(GroupDialogWidth)
                .clip(shape)
                .background(colors.surface)
                .border(dimens.hairline, colors.divider, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(text = title, style = typography.title.copy(color = colors.textPrimary))
            Spacer(Modifier.height(8.dp))
            Text(text = hint, style = typography.caption.copy(color = colors.textSecondary))
            Spacer(Modifier.height(18.dp))
            DialogActions(
                confirmColor = colors.unreadDot,
                confirmTag = confirmTag,
                confirmEnabled = true,
                confirmLabel = DELETE_LABEL,
                cancelTag = cancelTag,
                onCancel = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

/** Dos botones de texto en la parte inferior del cuadro de confirmación:Cancelar a la izquierda,El movimiento peligroso está en la derecha. */
@Composable
private fun DialogActions(
    confirmColor: Color,
    confirmTag: String,
    confirmEnabled: Boolean,
    confirmLabel: String,
    cancelTag: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    val typography = MiniPhoneTheme.typography
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = CANCEL_LABEL,
            style = typography.body.copy(color = colors.textSecondary),
            modifier = Modifier
                .testTag(cancelTag)
                .clip(RoundedCornerShape(DialogActionCorner))
                .clickable(onClick = onCancel)
                .heightIn(min = DialogActionHeight)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = confirmLabel,
            style = typography.body.copy(color = if (confirmEnabled) confirmColor else colors.textSecondary),
            modifier = Modifier
                .testTag(confirmTag)
                .clip(RoundedCornerShape(DialogActionCorner))
                .clickable(enabled = confirmEnabled, onClick = onConfirm)
                .heightIn(min = DialogActionHeight)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * Agregar página de miembro:Reutilizar la lista de selección múltiple para la creación de grupos,Enumere solo las personas que aún no están en el grupo.
 *
 * Seleccione una persona y haga clic en "Listo"—— No existe un umbral de "al menos dos" para agregar personas,El umbral es "dejar al menos una persona en el grupo".
 */
@Composable
private fun GroupAddMembersPage(
    sections: List<MiniPhoneContactSection>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = MiniPhoneTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(MiniPhoneChatTags.GROUP_ADD_PAGE)
            .background(colors.background),
    ) {
        GroupPickHeader(
            title = ADD_MEMBER_TITLE,
            hint = ADD_MEMBER_HINT_PREFIX + selectedIds.size + " gente",
            canFinish = selectedIds.isNotEmpty(),
            tagPrefix = GROUP_ADD_TAG_PREFIX,
            onBack = onBack,
            onDone = onDone,
        )
        GroupPickList(
            sections = sections,
            selectedIds = selectedIds,
            emptyTitle = ADD_MEMBER_EMPTY_TITLE,
            emptyHint = ADD_MEMBER_EMPTY_HINT,
            onToggle = onToggle,
        )
    }
}

/** La línea debajo del cuadro de entrada del nombre del grupo:Recuento de palabras actual y límite superior. */
internal fun titleHintOf(length: Int): String = "$length / $GROUP_TITLE_MAX_LENGTH"

private val GroupMembersButtonSize = 40.dp
private val GroupMembersIconSize = 20.dp
private val MenuGap = 6.dp
private val GroupDialogWidth = 280.dp
private val WindowCorner = 8.dp
private val DialogActionCorner = 8.dp
private val DialogActionHeight = 36.dp

private const val GROUP_MEMBERS_TITLE: String = "miembros del grupo"
private const val GROUP_TITLE_LABEL: String = "Nombre del grupo"
private const val GROUP_TITLE_EDIT_HINT: String = "Haga clic para modificar"
private const val GROUP_TITLE_EDIT_TITLE: String = "Modificar el nombre del grupo"
private const val ADD_MEMBER_LABEL: String = "+ Agregar miembro"
private const val ADD_MEMBER_TITLE: String = "Agregar miembro"
private const val ADD_MEMBER_HINT_PREFIX: String = "Seleccionado "
private const val ADD_MEMBER_EMPTY_TITLE: String = "No hay ningún rol para unirse."
private const val ADD_MEMBER_EMPTY_HINT: String = "Todos los usuarios de la libreta de direcciones ya están en el grupo."
private const val DISBAND_LABEL: String = "Eliminar y salir"
private const val DISBAND_TITLE: String = "Eliminar y salir?"
private const val DISBAND_HINT: String = "Todo el grupo y todos los mensajes que contiene se eliminarán de este teléfono.,No se puede recuperar."
private const val REMOVE_HINT: String = "Lo que dijo antes permanecerá en el grupo después de ser eliminado."
private const val UNTITLED_GROUP_NAME: String = "chat grupal"
private const val SAVE_LABEL: String = "guardar"
private const val CANCEL_LABEL: String = "Cancelar"
private const val DELETE_LABEL: String = "Eliminar"

/** Agregar prefijo de etiqueta a la página de miembro:Separado de la página del grupo,Puede saber de un vistazo en qué página se encuentra la prueba. */
private const val GROUP_ADD_TAG_PREFIX: String = "mp-group-add-member"
