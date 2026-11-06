package io.zer0.muse.ui.chat

import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.x: sesión/Protección del número de serie para cambio de modo.
 *
 * fondo(2026-09-28 Comentarios de los usuarios:Agent pagina de dialogo esporádico — El contenido de otra sesión parpadea y se restaura después de cerrar y reabrir.):
 * [io.zer0.muse.ui.chat.ChatSessionController.setAgentMode] /
 * [io.zer0.muse.ui.chat.ChatSessionController.switchSession] /
 * ChatViewModel.switchAgentAssistant Carga asincrónica de(Chaku → Enviar mensajes + estado de la interfaz de usuario)No antes
 * cheque de vencimiento:Cuando se realiza una conmutación rápida, la carga iniciada primero puede completarse más tarde.,Sobrescriba los mensajes y el estado de la "conversación anterior" en la interfaz actual.
 *
 * Uso:Cambiar llamada síncrona de entrada [begin] Obtenga este número de serie;Se están enviando tareas asincrónicas(escribir messages/state)llamado antes
 * [isStale],Si es cierto, descarta este resultado.(Si es necesario, libere primero la referencia de sesión de esta adquisición.).
 * invariante:El último cambio iniciado siempre se puede enviar,Las tareas caducadas nunca lo sobrescriben.
 *
 * El número de serie es el estado de la instancia.,Cada instancia de interfaz contiene una copia.:Lo que hay que ordenar son "cambios iniciados continuamente en la misma interfaz".,
 * Ordenar entre instancias no tiene sentido. Previamente escrito como un singleton a nivel de proceso,Una tarea que hace que una instancia se retrase provocará el retraso de otra instancia.
 * El cambio se considera caducado(En la prueba se descartó la recuperación del foco.).
 */
internal class SessionSwitchGuard {
    private val seq = AtomicInteger(0)

    /** Iniciar un cambio,Devuelve el número de secuencia de este conmutador. */
    fun begin(): Int = seq.incrementAndGet()

    /** Si el número de secuencia ha sido reemplazado por un interruptor más nuevo. */
    fun isStale(token: Int): Boolean = token != seq.get()

    /**
     * Sólo para pruebas: restablezca el número de serie a cero.
     *
     * El estado de la instancia se destruye junto con la instancia. Este método está reservado para casos de uso que necesitan reutilizar la misma instancia para restablecerla explícitamente.
     */
    @androidx.annotation.VisibleForTesting
    fun resetForTest() {
        seq.set(0)
    }
}
