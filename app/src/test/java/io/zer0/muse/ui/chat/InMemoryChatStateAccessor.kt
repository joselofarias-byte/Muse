package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Phase 3 dispositivo de prueba:implementado en la memoria [ChatStateAccessor].
 *
 * Equivalente a un FakeAccessor privado dentro de cada prueba de Controlador,pero:
 *  - `update` Cerrar,Permitir la lectura y escritura simultáneas de rutinas de reintento y subprocesos de prueba.;
 *  - Personalización inyectable [CoroutineScope],Las pruebas se pueden cancelar de forma proactiva para verificar la propagación de la cancelación.
 */
class InMemoryChatStateAccessor(
    initial: ChatUiState = ChatUiState(),
    scope: CoroutineScope? = null,
) : ChatStateAccessor {

    private val ownScope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.Unconfined)
    private val lock = Any()

    @Volatile
    private var state: ChatUiState = initial

    override val snapshot: ChatUiState get() = state

    override fun update(transform: (ChatUiState) -> ChatUiState) {
        synchronized(lock) {
            state = transform(state)
        }
    }

    override val messagesSnapshot: List<UIMessage> get() = emptyList()

    override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) = Unit

    override val coroutineScope: CoroutineScope get() = ownScope
}
