package io.zer0.muse.schedule

import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Semaphore

/**
 * B-22: Puerta de limitación de simultaneidad compartida en proceso generada por mensajes activos/chats grupales.
 *
 * fondo: mensajes no solicitados(ProactiveMessageRunner)chatear con grupo(GroupChatScheduler)Iniciar individualmente LLM
 * llamar,Sin límite actual para compartir,El disparador 429 puede superponerse, provocando que el discurso del chat grupal se pierda en el silencio.
 *
 * Semáforo en proceso,Simultaneidad máxima [MAX_CONCURRENT_LLM] camino;Proceso cruzado(Worker)No cubierto,
 * La ventana de tiempo de cada corredor se encarga de ello. Puede registrarlo en Koin como singleton o utilizar el objeto directamente.
 * (object Singleton natural en proceso,Evite perder el cableado Koin).
 */
object GenerationGate {

    /** Número máximo de rutas de generación de LLM simultáneas(Mensajería proactiva + intercambio de chat grupal). */
    private const val MAX_CONCURRENT_LLM = 2

    private val semaphore = Semaphore(MAX_CONCURRENT_LLM)

    /**
     * Ejecutar dentro del permiso del semáforo [block].
     * No se mantienen bloqueos mientras se bloquea en espera de permiso.,No bloquea la programación de otras corrutinas.
     *
     * Revisión de corrección (2.0 A-12): acquire usar [runInterruptible] paquete — Java Semaphore.acquire()
     * es una llamada de bloqueo,La cancelación de rutina no puede interrumpir el hilo de bloqueo;llamador(ProactiveMessageRunner)usar
     * withTimeoutOrNull envolver este método,Cuando se sature el límite actual, la implementación original permanecerá estancada hasta que se libere la licencia.,
     * tiempo de espera/La parada no es válida.runInterruptible Deje que la cancelación salga de la espera inmediatamente mediante la interrupción del hilo.
     */
    suspend fun <T> withPermit(block: suspend () -> T): T {
        runInterruptible { semaphore.acquire() }
        try {
            return block()
        } finally {
            semaphore.release()
        }
    }
}
