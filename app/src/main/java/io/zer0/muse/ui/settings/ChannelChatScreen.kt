package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.channel.ChannelConversationStore
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flujo de conversación para un solo contacto.
 *
 * ¿Por qué aparece esta página? Anteriormente, todos los contactos se presentaban en una lista y cada mensaje era una línea de "Rol: Texto".
 * Después de charlar durante tanto tiempo, no podía decir cómo se conectaba el contexto. Cámbialo aquí a flujo de burbujas. —— ¿Quién lo dijo y en qué orden?
 * El tiempo que ha pasado se puede ver de un vistazo.
 *
 * Esta página es solo para visualización: la respuesta del canal se maneja mediante el enlace de respuesta automática y los usuarios responden a los mensajes aquí.
 * Pasará por alto el libro de distribución y, en su lugar, creará inconsistencias.
 */
@Composable
fun ChannelChatScreen(channelId: String, from: String, onBack: () -> Unit) {
    val conversations by ChannelConversationStore.conversations.collectAsStateWithLifecycle()
    val conversation = conversations["$channelId|$from"]
    val turns = conversation?.turns.orEmpty()

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = prettifyContactLabel(from),
                onBack = onBack,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = MusePaddings.screen,
                end = MusePaddings.screen,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (turns.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.channel_chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 32.dp),
                    )
                }
            } else {
                // No hay ningún cuadro de entrada en esta página: deje en claro que es solo para visualización; de lo contrario, los usuarios pensarán que pueden responder aquí.
                item(key = "hint") {
                    Text(
                        text = stringResource(R.string.channel_chat_readonly_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            itemsIndexed(
                items = turns,
                // No utilice at como clave: el mismo milisegundo puede repetirse y las claves repetidas harán que LazyColumn falle directamente.
                key = { index, _ -> "turn_$index" },
            ) { index, turn ->
                val previous = turns.getOrNull(index - 1)
                // Inserte un intervalo de tiempo solo después de un intervalo de más de 10 minutos; de lo contrario, toda la pantalla se llenará de marcas de tiempo.
                if (previous == null || turn.at - previous.at > TIME_GAP_MS) {
                    Text(
                        text = formatTime(turn.at),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                ChannelBubble(turn = turn)
            }
        }
    }
}

/** Burbuja de mensaje único. El asistente está a la izquierda, el usuario a la derecha y tiene la misma intuición visual que la interfaz de chat principal. */
@Composable
private fun ChannelBubble(turn: ChannelConversationStore.Turn) {
    val isAssistant = turn.role == "assistant"
    val body = remember(turn) { turnBody(turn) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(BUBBLE_WIDTH_FRACTION),
            horizontalAlignment = if (isAssistant) Alignment.Start else Alignment.End,
        ) {
            Text(
                text = stringResource(
                    if (isAssistant) {
                        R.string.channel_conversation_role_assistant
                    } else {
                        R.string.channel_conversation_role_user
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            androidx.compose.material3.Surface(
                shape = MuseShapes.medium,
                color = if (isAssistant) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isAssistant) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Texto de burbuja.
 *
 * Los mensajes multimedia se almacenan en base64 o rutas locales en el canal, y las imágenes no se restauran aquí. ——
 * Esta página es para "ver cómo termina la conversación", no una galería de fotos; una línea de descripción de marcador de posición para la imagen es suficiente.
 * Si hay una descripción de degradación visual, úsela primero. Originalmente es una versión de texto para que la lea el modelo.
 */
private fun turnBody(turn: ChannelConversationStore.Turn): String = when {
    turn.mediaDescription.isNotBlank() -> turn.mediaDescription
    turn.mediaKind == "image" -> "[fotos]"
    turn.mediaPath.isNotBlank() -> "[Accesorios]"
    turn.text.isNotBlank() -> turn.text
    else -> "[mensaje vacío]"
}

private fun formatTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/** La cantidad máxima de ancho de pantalla que ocupan las burbujas. Deje el lado opuesto en blanco para que pueda ver \"quién lo dijo\". */
private const val BUBBLE_WIDTH_FRACTION = 0.82f

/** Los intervalos de tiempo se insertan más allá de este intervalo. */
private const val TIME_GAP_MS = 10 * 60 * 1000L
