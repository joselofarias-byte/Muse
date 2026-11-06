package io.zer0.muse.ui.chat

import io.zer0.ai.core.UIMessage
import io.zer0.muse.ui.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * v1.105 ChatViewModel dividir: Una interfaz estrecha que permite al Coordinador leer y escribir el estado.
 *
 * No transfiera directamente todo el ChatViewModel,Evite la dependencia inversa del Coordinador del anfitrión.
 * Coordinator solo necesito:
 *  - Leer la instantánea del estado actual [snapshot]
 *  - Actualizaciones atómicas state [update]
 *  - alcance de rutina [coroutineScope](proporcionado por el implementador viewModelScope)
 *
 * implementador(ChatViewModel)mantener [MutableStateFlow] e implementar esta interfaz;
 * Cada Coordinador recibe esta interfaz en el constructor.,No sostiene ViewModel directamente.
 */
interface ChatStateAccessor {
    /** Instantánea del estado actual(solo lectura). */
    val snapshot: ChatUiState

    /** Actualizaciones atómicas state(entrante transform,volver nuevo state). */
    fun update(transform: (ChatUiState) -> ChatUiState)

    /** B2-01: Instantánea de la lista de mensajes actual(solo lectura). */
    val messagesSnapshot: List<UIMessage>

    /** B2-01: Lista de mensajes de actualización atómica. */
    fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>)

    /** alcance de rutina(launch usar),ViewModelScope lo proporciona la implementación. */
    val coroutineScope: kotlinx.coroutines.CoroutineScope
}

/**
 * Adjuntar texto de entrada atómico para evitar ventanas emergentes asincrónicas/OCR/ASR La devolución de llamada sobrescribe lo que el usuario acaba de ingresar con la instantánea anterior.
 */
fun ChatStateAccessor.appendInputAtomically(insertion: String, separator: String = "\n\n") {
    if (insertion.isEmpty()) return
    update { state ->
        val current = state.input
        val merged = if (current.isBlank()) insertion else current + separator + insertion
        state.copy(input = merged)
    }
}
