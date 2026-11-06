package io.zer0.muse.ui.groupchat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.ui.RegionErrorBoundary
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.museAnimateItem
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.surface.MuseCardPress
import io.zer0.muse.ui.theme.MuseAvatarSize
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MuseHaptics
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Página de lista de chat grupal — Mostrar todos los chats grupales,Haga clic en la tarjeta para ingresar los detalles.
 *
 * diseño(warm-paper estilo):
 *  - barra de título superior(Título + Nuevo botón de chat grupal,No hay necesidad FAB)
 *  - LazyColumn Mostrar tarjeta de chat grupal(Nombre del chat grupal + fila de avatar del miembro + vista previa de las últimas noticias + hora)
 *  - Estado vacío:icono central + "Aún no hay chat grupal" + "Crear un nuevo chat grupal"botón
 *
 * @param onOpenChat Haga clic en la devolución de llamada de la tarjeta de chat grupal(Los parámetros son chatId)
 * @param viewModel chat grupal ViewModel
 */
@Composable
fun GroupChatListScreen(onOpenChat: (String) -> Unit, viewModel: GroupChatViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // P2-1: pantalla grande(Expanded)Centrar el área de contenido inferior y limitar el ancho. 720dp
    val widthClass = rememberWindowWidthClass()
    // Correcciones en la interfaz (Rendimiento-4): Tiempo para compartir a nivel de página ticker — Una sola corrutina se transmite cada 60 segundos.
    // marca de tiempo actual,Compartido por todas las tarjetas de chat grupal;Reemplaza el bucle infinito original de un produceState por tarjeta.
    val timeTicker by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    // I3: Cuando los datos de la lista de chat grupal son anormales, solo se degradará el actual. Tab,No afecta a otras áreas de la página de inicio.
    RegionErrorBoundary(
        regionName = "groupChat",
        data = { state.chats },
    ) {
        // P2-1: Box paquete,Expanded Centrar y limitar el ancho en modo
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (widthClass == WindowWidthClass.Expanded) {
                            Modifier.widthIn(max = 720.dp)
                        } else {
                            Modifier
                        },
                    )
                    .navigationBarsPadding(),
            ) {
                // v2.1: Eliminar barra de título independiente(Tab marcado"chat grupal"),Mostrar lista directamente
                // v1.72: Se muestra en la primera carga loading,evitar el flash"Aún no hay chat grupal"Estado vacío
                // CHAT-13/14: Tres estados avanzan juntos MuseLoadingState / MuseEmptyState(CircularProgressIndicator previamente desnudo + mosaico autodibujado)
                if (state.isChatsLoading) {
                    MuseLoadingState(
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (state.chats.isEmpty()) {
                    // Estado vacío:unificar MuseEmptyState,Reservar"Crear un nuevo chat grupal"Entrada de operación
                    MuseEmptyState(
                        icon = MuseIcons.plus,
                        title = stringResource(R.string.groupchat_empty_hint),
                        actionText = stringResource(R.string.groupchat_create_cd),
                        onAction = { showCreateDialog = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = MusePaddings.screen,
                            end = MusePaddings.screen,
                            top = MusePaddings.itemGap,
                            bottom = 88.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // v2.2: Título de la página"mi chat grupal"
                        item(key = "page_title") {
                            Text(
                                text = stringResource(R.string.groupchat_list_title),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                        items(
                            items = state.chats,
                            key = { it.id },
                        ) { chat ->
                            // E5 (H4): Entrada de elemento de la lista de chat grupal/animación de desplazamiento
                            Box(museAnimateItem()) {
                                val memberIds = remember(chat.memberIdsJson) { viewModel.parseMemberIds(chat) }
                                val members = remember(memberIds, state.assistants) {
                                    memberIds.mapNotNull { id -> state.assistants.find { it.id == id } }
                                }
                                GroupChatCard(
                                    chat = chat,
                                    members = members,
                                    memberCount = memberIds.size,
                                    now = timeTicker,
                                    viewModel = viewModel,
                                    onClick = { onOpenChat(chat.id) },
                                    onTogglePin = { viewModel.togglePin(chat.id) },
                                    onDelete = { viewModel.deleteChat(chat.id) },
                                    onClearMemory = { viewModel.clearChatMemory(chat.id) },
                                    onToggleArchive = { archived -> viewModel.toggleArchive(chat.id, archived) },
                                )
                            }
                        }
                        // v2.2: abajo"Crear un nuevo chat grupal"botón(Imagen de referencia:Círculo verde con el signo más a la izquierda + texto verde en el centro)
                        item(key = "create_new") {
                            MuseCardPress(
                                onClick = { showCreateDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MuseShapes.extraLarge,
                                containerColor = MaterialTheme.colorScheme.surface,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = MuseIcons.plus,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.groupchat_create_cd),
                                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Crear un nuevo cuadro de diálogo de chat grupal
    if (showCreateDialog) {
        CreateGroupChatDialog(
            // Assistant.allowGroupChat ¿Es un cambio de candidato a miembro?;P2-6: Los asistentes desactivados no entran en la lista de candidatos.
            assistants = state.assistants.filter { it.allowGroupChat && it.enabled },
            teams = state.teams,
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, memberIds, teamId, initMode, initLength ->
                showCreateDialog = false
                scope.launch {
                    val newChatId = viewModel.createChat(name, memberIds, teamId)
                    // v2.x: Ajustes preestablecidos de plantilla — Aplicar el modo de discusión y la duración del discurso después de la creación.
                    if (initMode != null || initLength != null) {
                        viewModel.updateChat(
                            chatId = newChatId,
                            discussionMode = initMode,
                            replyLengthMode = initLength,
                        )
                    }
                }
            },
        )
    }
}

/**
 * v2.2: Tarjeta de chat grupal — Estilo de dibujo de referencia(Avatares de miembros de mosaicos | Nombre del grupo+mensaje de prefijo del remitente | tiempo+flecha).
 */
@Composable
private fun GroupChatCard(
    chat: GroupChatEntity,
    members: List<AssistantEntity>,
    memberCount: Int,
    /** Correcciones en la interfaz (Rendimiento-4): Marca de tiempo del ticker compartido a nivel de página,Se utiliza para la actualización de tiempo relativo. */
    now: Long,
    viewModel: GroupChatViewModel,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onClearMemory: () -> Unit,
    onToggleArchive: (Boolean) -> Unit,
) {
    // Cargue de forma asincrónica una vista previa de las últimas noticias.
    // Correcciones en la interfaz (Rendimiento-4): produceState Tome chat.id como key,La corrutina recién se ensambla por primera vez. /
    // chat.id Empezar con el cambio;La reorganización provocada por el ticker a nivel de página no reiniciará la rutina,La base de datos no se comprobará repetidamente.
    val latestMessage by produceState<GroupChatMessageEntity?>(
        initialValue = null,
        chat.id,
    ) {
        value = viewModel.getLatestMessage(chat.id)
    }

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showClearMemoryConfirm by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    MuseCardPress(
        onClick = onClick,
        onLongClick = {
            MuseHaptics.medium(hapticFeedback)
            showMenu = true
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // lado izquierdo:Mosaico de avatar de miembro(la mayoría4un,40dp,espaciado4dp)
            MemberAvatarRow(
                members = members,
                memberCount = memberCount,
                avatarSize = MuseAvatarSize.list,
            )
            Spacer(Modifier.width(14.dp))
            // medio:Nombre del grupo + últimas noticias
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = LatestMessagePreview(latestMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            // lado derecho:tiempo + chevron
            Text(
                text = formatRelativeTime(chat.updatedAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            ChevronRight()
        }
    }

    // Menú de pulsación larga:fijar hacia arriba/Desanclar, eliminar
    if (showMenu) {
        MuseDialog(
            onDismissRequest = { showMenu = false },
            title = chat.name,
            content = {
                Column {
                    MuseCapsuleButton(
                        text = if (chat.pinned) stringResource(R.string.groupchat_unpin) else stringResource(R.string.groupchat_pin),
                        onClick = {
                            showMenu = false
                            onTogglePin()
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    // v2.x: Archivo/Desarchivar
                    MuseCapsuleButton(
                        text = if (chat.isArchived) {
                            stringResource(
                                R.string.groupchat_unarchive,
                            )
                        } else {
                            stringResource(R.string.groupchat_archive)
                        },
                        onClick = {
                            showMenu = false
                            onToggleArchive(!chat.isArchived)
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.groupchat_delete_chat),
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                    // v1.0.72: Borrar la memoria del chat grupal(Limpieza de residuos de estilo,Independiente del sistema de memoria principal)
                    MuseCapsuleButton(
                        text = stringResource(R.string.groupchat_clear_memory),
                        onClick = {
                            showMenu = false
                            showClearMemoryConfirm = true
                        },
                        variant = IosCapsuleButtonVariant.Text,
                        fillWidth = false,
                    )
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showMenu = false },
        )
    }

    // v1.0.72: Borrar confirmación de memoria de chat grupal
    if (showClearMemoryConfirm) {
        MuseDialog(
            onDismissRequest = { showClearMemoryConfirm = false },
            title = stringResource(R.string.groupchat_clear_memory), // Correcciones en la interfaz (i18n-2)
            content = {
                Text(
                    text = stringResource(R.string.groupchat_clear_memory_confirm), // Correcciones en la interfaz (i18n-2)
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.groupchat_clear_memory_btn), // Correcciones en la interfaz (i18n-2)
            onConfirm = {
                showClearMemoryConfirm = false
                onClearMemory()
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showClearMemoryConfirm = false },
        )
    }

    // Eliminar confirmación
    if (showDeleteConfirm) {
        MuseDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = stringResource(R.string.groupchat_delete_chat),
            content = {
                Text(
                    text = stringResource(R.string.groupchat_delete_chat_confirm, chat.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.groupchat_delete),
            onConfirm = {
                showDeleteConfirm = false
                onDelete()
            },
            dismissText = stringResource(R.string.groupchat_cancel),
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * v2.2: Fila de mosaicos de avatar de miembro — Mostrar más4un,Disposición compacta.
 */
@Composable
private fun MemberAvatarRow(members: List<AssistantEntity>, memberCount: Int, avatarSize: androidx.compose.ui.unit.Dp) {
    val visibleCount = minOf(4, members.size)
    Row(
        horizontalArrangement = Arrangement.spacedBy((-6).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until visibleCount) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(avatarSize),
            ) {
                AssistantAvatar(
                    assistant = members[i],
                    avatarSize = avatarSize,
                )
            }
        }
        if (memberCount > visibleCount) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(avatarSize),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+${memberCount - visibleCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * v2.2: Vista previa de las últimas noticias — Abreviatura del remitente + dos puntos + contenido.
 */
@Composable
private fun LatestMessagePreview(message: GroupChatMessageEntity?): String {
    if (message == null) return stringResource(R.string.groupchat_no_messages)
    val prefix = when (message.senderType) {
        "user" -> stringResource(R.string.groupchat_sender_user)
        else -> message.senderName.take(1).ifBlank { "A" }
    }
    return "$prefix: ${message.body}"
}

/**
 * Formatear tiempo relativo(Hace apenas / N minutos / HH:mm / MM-dd).
 *
 * Correcciones en la interfaz (Rendimiento-4): now Pasado por ticker compartido a nivel de página,Las tarjetas ya no se originan individualmente
 * 60s Corrutina produceState de bucle infinito;ticker El texto de la hora se recalcula uniformemente al actualizar.
 */
@Composable
private fun formatRelativeTime(timestamp: Long, now: Long): String {
    // v1.71: Almacenamiento en caché con recordar SimpleDateFormat(Debe llamarse antes de la rama condicional.)
    val timeFmt = remember { SimpleDateFormat(MuseDateFormats.TIME_SHORT, Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat(MuseDateFormats.DATE_SHORT, Locale.getDefault()) }
    if (timestamp <= 0) return ""
    val diff = now - timestamp
    val justNow = stringResource(R.string.groupchat_just_now)
    val minutesAgoFmt = stringResource(R.string.groupchat_minutes_ago)
    return when {
        diff < 60_000 -> justNow
        diff < 3_600_000 -> minutesAgoFmt.format(diff / 60_000)
        diff < 86_400_000 -> timeFmt.format(Date(timestamp))
        else -> dateFmt.format(Date(timestamp))
    }
}
