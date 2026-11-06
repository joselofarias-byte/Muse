package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConversationStore
import io.zer0.muse.channel.ChannelInbox
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.0.1: Página de conversación del canal — Ver el historial de conversaciones y el estado contextual de cada contacto por canal.
 *
 * fuente de datos:[ChannelConversationStore](Almacenado en la misma copia que la respuesta automática.);
 * Una conversación por contacto,Ampliación de soporte para ver noticias recientes,si"reiniciar contexto"(Borrar el historial y el resumen de la conversación).
 */
@Composable
fun ChannelConversationsScreen(
    channelId: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val entries = remember(conversations, channelId) {
        conversations
            .filterKeys { key -> key.startsWith("$channelId|") }
            .map { (key, conversation) -> key.substringAfter("|") to conversation }
            .sortedByDescending { (_, conversation) -> conversation.updatedAt }
    }
    var resetTarget by remember { mutableStateOf<String?>(null) }
    // La última entrega fallida: anteriormente el código de error solo se registraba y no se mostraba, y los usuarios sólo podían adivinarlo.“¿Por qué no responde?”.
    // El manejo de "no en el grupo" y "tiempo de espera de respuesta pasiva" en los chats grupales es completamente diferente y debe mostrarse.
    val inbox by ChannelInbox.messages.collectAsStateWithLifecycle()
    val lastFailure = remember(inbox, channelId) {
        inbox
            .filter { item -> item.sourceChannelId == channelId && item.dispatchErrorCode.isNotBlank() }
            .maxByOrNull { item -> item.timestamp }
            ?.dispatchErrorCode
            .orEmpty()
    }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.channel_conversations_title),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // v2.0.1: Heredar el modo antivibración existente en el campo de configuración(BoxWithConstraints Altura de cierre limitada).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val boundedHeight = boundedSettingsScrollHeight(
                parentMaxHeight = maxHeight,
                windowHeight = LocalConfiguration.current.screenHeightDp.dp,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = boundedHeight),
                contentPadding = PaddingValues(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    top = 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (lastFailure.isNotBlank()) {
                    item(key = "last_failure") { FailureBanner(lastFailure) }
                }
                if (entries.isEmpty()) {
                    item(key = "empty") { EmptyHint() }
                }
                items(entries, key = { (from, _) -> from }) { (from, conversation) ->
                    ConversationCard(
                        from = from,
                        conversation = conversation,
                        onOpen = { onOpenConversation(from) },
                        onReset = { resetTarget = from },
                    )
                }
            }
        }
    }

    ResetContextDialog(
        target = resetTarget,
        onConfirm = { from ->
            ChannelConversationStore.clear(channelId, from)
            resetTarget = null
        },
        onDismiss = { resetTarget = null },
    )
}

/** Reiniciar la confirmación del contexto. La extracción es solo para controlar la duración de la función principal y la lógica en sí no tiene ramas. */
@Composable
private fun ResetContextDialog(target: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val from = target ?: return
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.channel_conversation_reset_confirm_title),
        content = { Text(stringResource(R.string.channel_conversation_reset_confirm_text)) },
        confirmText = stringResource(R.string.channel_conversation_reset),
        onConfirm = { onConfirm(from) },
        destructive = true,
        dismissText = stringResource(R.string.settings_common_cancel),
        onDismiss = onDismiss,
    )
}

/**
 * Tarjeta de entrada para un solo contacto.
 *
 * Responda sólo tres cosas: quién, qué se dijo por última vez y cuándo. Haga clic para ver los detalles del texto. ——
 * Distribuirlo en una lista hace imposible leer el contexto y convierte la lista en un muro de palabras.
 */
@Composable
private fun ConversationCard(
    from: String,
    conversation: ChannelConversationStore.Conversation,
    onOpen: () -> Unit,
    onReset: () -> Unit,
) {
    val turns = conversation.turns
    val lastTurn = turns.lastOrNull()
    val timeText = remember(conversation.updatedAt) {
        if (conversation.updatedAt <= 0L) {
            ""
        } else {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(conversation.updatedAt))
        }
    }

    CardGroup(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpen)
                    .padding(MusePaddings.cardInner),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = prettifyContactLabel(from),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.channel_conversation_reset),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        // LAYOUT-01: La acción de la derecha es el dimensionamiento corto de ancho fijo.(El ancho restante se ha asignado al nombre del contacto a la izquierda. weight),Limitado a 1 línea.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable(onClick = onReset)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.channel_conversation_meta, turns.size, timeText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp),
                )
                LastTurnPreview(lastTurn)
                if (conversation.summary.isNotBlank()) {
                    SummaryLine(conversation.summary)
                }
            }
        }
    }
}

/**
 * Embellecimiento de la pantalla de identificación de contacto.
 *
 * Almacenamiento de destino de grupo `group:<openid>`, la visualización directa tendrá un prefijo y será demasiado larga.
 * Aquí, el prefijo se convierte en una etiqueta legible y se conservan el principio y el final de la identificación larga.
 */
internal fun prettifyContactLabel(raw: String): String {
    val isGroup = raw.startsWith("group:")
    val core = raw.removePrefix("group:")
    val shown = if (core.length <= 20) core else core.take(10) + "…" + core.takeLast(6)
    return if (isGroup) "grupo $shown" else shown
}

/** El último envío falló. Pon la lista en la parte superior porque es"¿Por qué no responde?"respuesta directa. */
@Composable
private fun FailureBanner(message: String) {
    Text(
        text = stringResource(R.string.channel_last_failure, message),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Estado vacío. Convertirla en una función separada es solo para evitar que la función principal se alargue. */
@Composable
private fun EmptyHint() {
    Text(
        text = stringResource(R.string.channel_conversations_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 32.dp),
    )
}

/** Vista previa del último mensaje. */
@Composable
private fun LastTurnPreview(turn: ChannelConversationStore.Turn?) {
    val current = turn ?: return
    val roleLabel = stringResource(
        if (current.role == "assistant") {
            R.string.channel_conversation_role_assistant
        } else {
            R.string.channel_conversation_role_user
        },
    )
    Text(
        text = stringResource(
            R.string.channel_conversation_turn_line,
            roleLabel,
            current.text.ifBlank { "[Accesorios]" },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Un resumen desplazable de conversaciones anteriores. */
@Composable
private fun SummaryLine(summary: String) {
    Text(
        text = stringResource(R.string.channel_conversation_summary, summary),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}
