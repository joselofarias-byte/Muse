package io.zer0.muse.ui.chat

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.ui.PendingMessage
import io.zer0.muse.ui.common.feedback.MuseToast

/** v1.205 B2: El límite superior de la cola que se enviará. */
internal const val MAX_PENDING_SEND_QUEUE = 8

/**
 * v1.x: Entrada extraída de ChatViewModel/borrador/Cola para enviar/Ingrese al controlador de historial.
 *
 * Límites de responsabilidad:
 *  - Texto del cuadro de entrada, marca de borrador, cola de envío(Únete al equipo/quitar la cola/Editar/Borrar/enviar), ingrese a la navegación histórica.
 *  - Todo pasó [ChatStateAccessor] compartir lectura-escritura state,No mantiene el estado por sí mismo.
 *  - "enviar"Pases de acción [onEnqueueSend] Devolución de llamada al anfitrión(Tome el canal de envío),Esta clase no tiene lógica generacional.
 */
class ChatInputController(
    private val accessor: ChatStateAccessor,
    private val appContext: Context,
    private val onEnqueueSend: (text: String, images: List<String>, sessionId: String) -> Unit,
) {

    fun updateInput(text: String) {
        // v1.0.47 P5: Salir de la navegación del historial cuando el usuario edita manualmente la entrada,restablecer inputHistoryIndex
        // v2.6.7: hasDraft devolver“Borrador guardado”significado original —— Solo el contenido restaurado a partir de la persistencia se considera borrador.
        //         La versión anterior fue cambiada a text.isNotBlank() está mal: cualquier carácter escrito de esa manera se iluminará
        //         Pestaña "Borrador" y ventana emergente“El borrador ha sido restaurado.”Consejo, lo que ve el usuario es“Todo lo que escribo es un borrador.”.
        //         La entrada es entrada, no estado.
        accessor.update { it.copy(input = text, inputHistoryIndex = null) }
    }

    /** v1.205 B2: Agregue la entrada actual a la cola para enviar(En cola durante la compilación,No interrumpas la generación actual.). */
    fun enqueuePendingSend() {
        val st = accessor.snapshot
        val text = st.input.trim()
        if (text.isBlank() && st.pendingImages.isEmpty()) return
        if (st.sendQueue.size >= MAX_PENDING_SEND_QUEUE) {
            MuseToast.show(appContext.getString(R.string.chat_pending_queue_full))
            return
        }
        // Anota la conversación al unirte a la cola.:El canal de interrupción depende de él para confirmar a qué conversación pertenece este mensaje.,Si no puedes conseguirlo, guárdalo. null
        // (Las entradas que no se puedan recuperar de la conversación no se eliminarán mediante interrupción.,Todavía sigue la cola original./Enviar ruta individualmente).
        val sessionId = if (st.isAgentMode) st.agentSessionId else st.currentSessionId
        accessor.update {
            it.copy(
                input = "",
                pendingImages = emptyList(),
                sendQueue = it.sendQueue + PendingMessage(text, st.pendingImages, sessionId),
            )
        }
    }

    /** v1.205 B2: Eliminar el artículo de la cola [index] artículo. */
    fun removePendingSend(index: Int) {
        accessor.update { it.copy(sendQueue = it.sendQueue.filterIndexed { i, _ -> i != index }) }
    }

    /** v1.205 B2: Envía el primero en la cola por separado. [index] Artículo;Quitar la cola primero y luego enviar,Evite el reingreso y el envío repetido. */
    fun sendPendingSend(index: Int) {
        val st = accessor.snapshot
        val item = st.sendQueue.getOrNull(index) ?: return
        // P0-7: El ID de sesión se utiliza de manera uniforme effectiveSessionId(Agent modo tomar agentSessionId)——
        // Para implementación anterior currentSessionId,Agent En modo, los mensajes se enviarán a la sesión de tareas.,Mensajes de cadena.
        val sessionId = (if (st.isAgentMode) st.agentSessionId else st.currentSessionId) ?: return
        // P0-7: Transmisión"Enviar por separado"Cancelará la generación actual en serie a través de launchGeneration(interrumpir al usuario
        // Ver respuestas). barandilla:Está prohibido enviar por separado durante la generación.,Solicitar detenerse primero;El mensaje permanece en la cola.
        if (st.isStreaming) {
            MuseToast.show(appContext.getString(R.string.chat_pending_send_busy))
            return
        }
        accessor.update { it.copy(sendQueue = it.sendQueue.filterIndexed { i, _ -> i != index }) }
        onEnqueueSend(item.text, item.images, sessionId)
    }

    /** v1.205 B2: Pon el primero en la cola. [index] Complete el cuadro de entrada(Poner en cola después de editar/enviar),Y sal del equipo. */
    fun editPendingSend(index: Int) {
        val st = accessor.snapshot
        val item = st.sendQueue.getOrNull(index) ?: return
        accessor.update {
            it.copy(
                input = item.text,
                pendingImages = item.images,
                sendQueue = it.sendQueue.filterIndexed { i, _ -> i != index },
            )
        }
    }

    /** v1.205 B2: Borre toda la cola de envíos. */
    fun clearPendingQueue() {
        accessor.update { it.copy(sendQueue = emptyList()) }
    }

    /**
     * v1.0.47 P5: en el cuadro de entrada/Devolución de llamada de flecha hacia abajo,Recorra el historial de entrada de esta sesión.
     * Acuerdo:inputHistory prensa"nuevo→viejo"almacenamiento(index 0 = último).
     */
    fun navigateInputHistory(direction: Int) {
        val st = accessor.snapshot
        val target = historyNavigationTarget(direction, st.inputHistory, st.inputHistoryIndex) ?: return
        if (target == -1) {
            accessor.update { it.copy(input = "", inputHistoryIndex = null) }
        } else {
            accessor.update { it.copy(input = st.inputHistory[target], inputHistoryIndex = target) }
        }
    }

    /**
     * Calcular los objetivos de navegación históricos de entrada. Entrada a la convenciónHistoria de la prensa"nuevo→viejo"almacenamiento(index 0 = último).
     *
     * @return índice objetivo(>=0);-1 expresar"Salir de la navegación,Borrar entrada";null Indica que no hay operación.
     */
    private fun historyNavigationTarget(direction: Int, history: List<String>, current: Int?): Int? {
        if (history.isEmpty() || direction == 0) return null
        return when {
            // flecha arriba:a mayor
            direction < 0 -> {
                val candidate = (current ?: -1) + 1
                if (candidate >= history.size) null else candidate
            }
            // flecha hacia abajo:actualizar;No moverse en la navegación,Ya en la parte más antigua, sal de navegación.
            current == null -> null
            current <= 0 -> -1
            else -> current - 1
        }
    }
}
