package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.chat.ConversationTree
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v1.x: ChatViewModel contenedor de estado compartido.
 *
 * oso _state / _messages / _conversationTree tres porciones StateFlow,hacer cada uno controller
 * No es necesario tener un ChatViewModel para leer y escribir la misma fuente de verdad.ChatViewModel Aún vía solo lectura
 * StateFlow expuesto al mundo exterior,Y delegarlo a este contenedor a través de getter MutableStateFlow,mantener
 * existente `_state.xxx` / `_messages.xxx` / `_conversationTree.xxx` La referencia se mantiene sin cambios.
 */
class ChatStateStore {
    val state = MutableStateFlow(ChatUiState())
    val messages = MutableStateFlow<List<UIMessage>>(emptyList())
    val conversationTree = MutableStateFlow(ConversationTree())
}
