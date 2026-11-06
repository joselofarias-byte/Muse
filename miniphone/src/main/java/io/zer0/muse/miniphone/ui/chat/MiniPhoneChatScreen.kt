package io.zer0.muse.miniphone.ui.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.miniphone.ui.icons.MiniPhoneIcons
import io.zer0.muse.miniphone.ui.theme.MiniPhoneTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Entrada a la página de chat:Obtener sesión propia ViewModel,Cuelgue el valor de retorno en la clave de retorno del sistema,Y es responsable de abrir el selector de imágenes del sistema.
 *
 * La identificación de la sesión es el único parámetro de construcción de esta página.,VM Úselo como clave para almacenarlo en ViewModelStore —— Salir y volver a entrar en la misma sesión.
 * Reutilizará el mismo estado.,No volverá a ser retirado de la biblioteca.
 */
@Composable
internal fun MiniPhoneChatRoute(threadId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // Utilice la identificación de sesión como clave de ViewModel:Un estado para cada sesión,Salir y volver a entrar en la misma sesión.
    val viewModel: MiniPhoneChatViewModel = koinViewModel(key = threadId) { parametersOf(threadId) }
    val state by viewModel.uiState.collectAsState()
    // Selector de imagen del sistema:Sólo reconoce imágenes,Máximo MAX_PICKED_IMAGES a la vez;Los seleccionados se entregan a la VM para su almacenamiento uno por uno.
    val pickImages = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICKED_IMAGES),
    ) { uris -> viewModel.sendImages(uris) }
    // La página permanecerá sin leer mientras esté visible.:Limpia la página una vez,Después de eso, bórrelo nuevamente cada vez que llegue un mensaje nuevo.
    // Colgar sólo cuando esté visible —— Si está claro en otra parte,Los mensajes que vienen en segundo plano nunca verán el recordatorio de no leídos.
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    BackHandler(onBack = onBack)
    MiniPhoneChatScreen(
        state = state,
        onBack = onBack,
        onLoadOlder = viewModel::loadOlder,
        input = ChatInputActions(
            onSend = viewModel::send,
            onPickImages = {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTranscribeVoice = viewModel::transcribeVoice,
        ),
        messageActions = ChatMessageActions(
            onResend = viewModel::resend,
            onDelete = viewModel::deleteMessage,
        ),
        modifier = modifier,
        groupActions = GroupAdminActions(
            onRename = viewModel.groupAdmin::renameGroup,
            onAddMembers = viewModel.groupAdmin::addMembers,
            onRemoveMember = viewModel.groupAdmin::removeMember,
            onDisband = viewModel.groupAdmin::disbandGroup,
        ),
    )
}

/**
 * pagina de chat:Barra superior + lista de mensajes + campo de entrada,Y la capa visual superpuesta encima.
 *
 * apátrida:El estado y las devoluciones de llamada provienen del exterior.,cambio por uno [ChatUiState] Puede obtener una vista previa o afirmar desde cualquier lugar.
 * La imagen actualmente ampliada solo se encuentra en esta página.([rememberSaveable]),la pagina se ha ido,No entrar `ChatUiState`.
 *
 * Nuevas alertas de noticias también están disponibles en esta página.:Cuando llega otro mensaje después de que la persona sale del fondo,Un "N nuevos mensajes,
 * Haga clic para volver al último;No se muestra cuando la persona se detiene en el último elemento. La lógica de decisión es [NewMessagesEffect].
 *
 * @param input Tres devoluciones de llamada para el campo de entrada(Enviar / seleccionar imágenes / grabar y transcribir)
 * @param messageActions Reenviar y eliminar un solo mensaje
 * @param groupActions Acciones de gestión para páginas de miembros del grupo.(Cambiar nombre/Agregar personas/Eliminar/Disolver);No se puede utilizar en mensajes privados.
 */
@Composable
internal fun MiniPhoneChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    input: ChatInputActions = ChatInputActions(),
    messageActions: ChatMessageActions = ChatMessageActions(),
    modifier: Modifier = Modifier,
    groupActions: GroupAdminActions = GroupAdminActions(),
) {
    val colors = MiniPhoneTheme.colors
    var viewingUri by rememberSaveable { mutableStateOf<String?>(null) }
    // La página de miembro del grupo está superpuesta en la página de chat.:Leer solo un nivel,Cerrar para volver a la conversación.
    var membersOpen by rememberSaveable { mutableStateOf(false) }
    // Estado de la lista mencionado en esta página:El nuevo mensaje te pide que lo leas para deducir "¿Está la persona de abajo?"
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var newMessageCount by remember { mutableIntStateOf(0) }
    NewMessagesEffect(
        listState = listState,
        messageTotal = state.messages.size,
        onCountChanged = { count -> newMessageCount = count },
    )
    // Ver cuando la capa se superpone en la página de chat,Presione la tecla de retorno para cerrarlo primero.
    BackHandler(enabled = viewingUri != null) { viewingUri = null }
    BackHandler(enabled = membersOpen) { membersOpen = false }
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            ChatTopBar(
                name = state.title,
                avatarUri = if (state.isGroup) null else state.peerAvatarUri,
                onBack = onBack,
                // La columna superior del grupo significa que hay más personas en la fila.,Nadie en el mensaje privado sabe de esta entrada.
                memberCount = state.memberCount.takeIf { state.isGroup },
                onOpenMembers = if (state.isGroup) {
                    { membersOpen = true }
                } else {
                    null
                },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                ChatMessageList(
                    state = state,
                    listState = listState,
                    maxBubbleWidth = maxWidth * BUBBLE_MAX_WIDTH_RATIO,
                    onLoadOlder = onLoadOlder,
                    onOpenImage = { uri -> viewingUri = uri },
                    messageActions = messageActions,
                    modifier = Modifier.fillMaxSize(),
                )
                if (newMessageCount > 0) {
                    ChatNewMessagesPill(
                        count = newMessageCount,
                        onClick = {
                            // Recoge la cápsula primero y luego enróllala.:Durante el proceso de desplazamiento, la persona ya está de regreso al final.,No es necesario esperar a que termine la animación.
                            newMessageCount = 0
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = PillBottomGap),
                    )
                }
            }
            ChatInputBar(
                onSend = input.onSend,
                onPickImages = input.onPickImages,
                onTranscribeVoice = input.onTranscribeVoice,
                voiceInputEnabled = state.voiceInputAvailable,
            )
        }
        val viewing = viewingUri
        if (viewing != null) {
            MiniPhoneImageViewer(uri = viewing, onDismiss = { viewingUri = null })
        }
        if (membersOpen && state.isGroup) {
            GroupMembersPage(
                members = state.members,
                title = state.title,
                characters = state.characters,
                onBack = { membersOpen = false },
                actions = GroupAdminActions(
                    onRename = groupActions.onRename,
                    onAddMembers = groupActions.onAddMembers,
                    onRemoveMember = groupActions.onRemoveMember,
                    onDisband = {
                        // Después de disolverse, la sesión ya no existe.:Cerrar página de miembro,Volver a la lista de mensajes
                        groupActions.onDisband()
                        membersOpen = false
                        onBack()
                    },
                ),
            )
        }
    }
}

/**
 * barra superior:Retorno, avatar, nombre;Una persona más en el chat grupal.,Haga clic para ir a la página de miembros.
 *
 * Deje un espacio a la derecha que tenga el mismo ancho que la tecla de retorno,Los nombres no serán eliminados. El número de personas sigue el nombre en lugar de a la derecha.——
 * "Ayuan, Xiaoman, yo (3)" es un todo,Escribirlos por separado se leerá como dos mensajes no relacionados.
 */
@Composable
private fun ChatTopBar(
    name: String,
    avatarUri: String?,
    onBack: () -> Unit,
    memberCount: Int? = null,
    onOpenMembers: (() -> Unit)? = null,
) {
    val colors = MiniPhoneTheme.colors
    val dimens = MiniPhoneTheme.dimens
    val typography = MiniPhoneTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dimens.pageHeaderHeight)
                .padding(horizontal = 8.dp)
                .testTag(MiniPhoneChatTags.GROUP_TOP_BAR)
                .then(if (onOpenMembers != null) Modifier.clickable(onClick = onOpenMembers) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(TopBarButtonSize)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag(MiniPhoneChatTags.BACK_BUTTON),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MiniPhoneIcons.Back,
                    contentDescription = BACK_DESCRIPTION,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(TopBarIconSize),
                )
            }
            MiniPhoneChatAvatar(
                name = name,
                avatarUri = avatarUri,
                size = TopBarAvatarSize,
                modifier = Modifier.testTag(MiniPhoneChatTags.TOP_AVATAR),
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = typography.title.copy(color = colors.textPrimary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .testTag(MiniPhoneChatTags.PEER_NAME),
                )
                if (memberCount != null) {
                    Text(
                        text = memberCountLabelOf(memberCount),
                        style = typography.timestamp.copy(color = colors.textSecondary),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .testTag(MiniPhoneChatTags.GROUP_MEMBER_COUNT),
                    )
                }
            }
            Spacer(Modifier.width(TopBarButtonSize))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(dimens.hairline)
                .background(colors.divider),
        )
    }
}

/** Cómo escribir el número de personas en un grupo.:Los corchetes angulares completos siguen inmediatamente al nombre. */
internal fun memberCountLabelOf(memberCount: Int): String = "($memberCount)"

/**
 * Lista de mensajes.
 *
 * `reverseLayout = true` Viene con un "nuevo → datos antiguos:El subíndice 0 cae al final,Entrar en la página equivale a detenerse en la última,
 * Y las noticias anteriores están arriba.,Desplazarse hacia arriba es la dirección para pasar la página. Las sugerencias en generación también se insertan en el índice 0.
 */
@Composable
private fun ChatMessageList(
    state: ChatUiState,
    listState: LazyListState,
    maxBubbleWidth: Dp,
    onLoadOlder: () -> Unit,
    onOpenImage: (String) -> Unit,
    messageActions: ChatMessageActions,
    modifier: Modifier = Modifier,
) {
    val displayed = remember(state.items) { state.items.asReversed() }
    // Las identidades de ambos lados sólo cambian con el estatus.,No es necesario volver a calcular cada fila
    val self = remember(state.selfName, state.selfAvatarUri) { ChatPersona(state.selfName, state.selfAvatarUri) }
    val peer = remember(state.peerName, state.peerAvatarUri) { ChatPersona(state.peerName, state.peerAvatarUri) }
    val activity = state.activity
    LazyColumn(
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = modifier.testTag(MiniPhoneChatTags.MESSAGE_LIST),
    ) {
        if (activity != null) {
            // Las indicaciones para hacer cosas ocupan el espacio de "escritura":Saber en qué está ocupada la otra persona es más específico que tres puntos
            item(key = ACTIVITY_ITEM_KEY, contentType = ACTIVITY_ITEM_KEY) {
                ChatActivityRow(kind = activity)
            }
        } else if (state.isGenerating) {
            item(key = TYPING_ITEM_KEY, contentType = TYPING_ITEM_KEY) {
                ChatTypingRow(
                    peerName = state.typingName ?: state.peerName,
                    peerAvatarUri = if (state.typingName != null) state.typingAvatarUri else state.peerAvatarUri,
                    // Dos personajes del grupo se turnan para hablar.:Esa línea debería indicar claramente quién juega ahora.
                    showName = state.isGroup,
                )
            }
        }
        items(items = displayed, key = { it.key }, contentType = ::contentTypeOf) { item ->
            when (item) {
                is ChatItem.Time -> ChatTimeDividerRow(at = item.at)
                is ChatItem.Message -> ChatMessageRow(
                    message = item.message,
                    self = self,
                    peer = peer,
                    senderLabel = if (state.isGroup) state.memberNames[item.message.characterId] else null,
                    maxBubbleWidth = maxBubbleWidth,
                    onOpenImage = onOpenImage,
                    messageActions = messageActions,
                )
            }
        }
    }
    LoadOlderEffect(listState = listState, hasMore = state.hasMore, onLoadOlder = onLoadOlder)
}

/** Desplácese hasta el principio de la lista(En el orden inverso, es el último subíndice.)cuando esté cerca,Ir a la página anterior. */
@Composable
private fun LoadOlderEffect(listState: LazyListState, hasMore: Boolean, onLoadOlder: () -> Unit) {
    val loadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState, hasMore) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - LOAD_OLDER_THRESHOLD
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { loadOlder() }
    }
}

private fun contentTypeOf(item: ChatItem): String = when (item) {
    is ChatItem.Time -> "time"
    is ChatItem.Message -> "message"
}

/** Puede seleccionar hasta nueve imágenes a la vez:No importa cuántos haya, no es fácil leerlos todos en una sola pantalla.,Y cada uno debe contener un mensaje separado. */
private const val MAX_PICKED_IMAGES: Int = 9

/** La relación entre la burbuja más ancha y el ancho de la pantalla.:Si es más ancho, empújalo hacia el lado opuesto.,Las columnas izquierda y derecha ya no son visibles. */
private const val BUBBLE_MAX_WIDTH_RATIO: Float = 0.72f

/** Cuando queden algunos elementos al final de la lista, comience a ir a la página anterior. */
private const val LOAD_OLDER_THRESHOLD: Int = 2

private const val TYPING_ITEM_KEY: String = "mp-chat-typing-item"
private const val ACTIVITY_ITEM_KEY: String = "mp-chat-activity-item"
private const val BACK_DESCRIPTION: String = "Regresar"

private val TopBarButtonSize = 40.dp
private val TopBarIconSize = 20.dp
private val TopBarAvatarSize = 28.dp

/** La distancia entre la cápsula y el campo de entrada.:Flota un poco contra el borde inferior del área del mensaje.,No suprimas la última burbuja. */
private val PillBottomGap = 12.dp
