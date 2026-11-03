package io.zer0.muse.asr

import kotlinx.coroutines.flow.StateFlow

/**
 * Interfaz del controlador Streaming ASR.
 *
 * con versión antigua [AsrClient] la diferencia:
 * - versión antigua: recognize(pcmBytes) identificación única,Los resultados se publicarán después de la grabación.
 * - nueva versión: start(onTranscriptChange) Reconocimiento de transmisión,Graba, transmite y vuelve a llamar al mismo tiempo
 *
 * Controller Responsable internamente:
 * - Colección de grabaciones(AudioRecord + VOICE_COMMUNICATION)
 * - Carga de audio(WebSocket / HTTP)
 * - Gestión de estado(StateFlow<ASRState>)
 * - devolución de llamada de resultado(onTranscriptChange)
 *
 * ciclo de vida:
 * - start(): Empezar a grabar+subir,Estado Idle→Connecting→Listening
 * - stop(): dejar de grabar,Esperando el resultado final,Estado Listening→Stopping→Idle
 * - dispose(): Liberar todos los recursos(corrutina scope / WebSocket / AudioRecord)
 */
interface ASRController {
    /** Estado actual(observable). */
    val state: StateFlow<ASRState>

    /**
     * Empezar a grabar+Identificar.
     * @param onTranscriptChange Identificar devoluciones de llamada de cambio de texto(hilo principal),Nuevos resultados cada vez(intermedio o final)Todas las devoluciones de llamada
     */
    fun start(onTranscriptChange: ((String) -> Unit)? = null)

    /** dejar de grabar,Espere a que regrese el último resultado y luego vuelva a estar inactivo. */
    fun stop()

    /** Liberar todos los recursos(corrutina scope / WebSocket / AudioRecord),Ya no se puede iniciar. */
    fun dispose()
}
