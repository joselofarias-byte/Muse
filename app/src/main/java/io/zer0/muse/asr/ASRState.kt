package io.zer0.muse.asr

/**
 * ASR Enumeración de máquinas de estados.
 * - Idle: gratis
 * - Connecting: Conectando(WebSocket Preparación de protocolo de enlace/HTTP)
 * - Listening: Grabación(Recopilando y cargando)
 * - Stopping: Parada(Refinamiento,Esperando el último resultado)
 * - Reconnecting: Desconectado y reconectándose(WebSocket Retroceso exponencial y reconexión después de la desconexión,Continúe grabando y almacenando en búfer fotogramas de audio durante)
 * - Error: error
 */
enum class ASRStatus {
    Idle,
    Connecting,
    Listening,
    Stopping,
    Reconnecting,
    Error,
}

/**
 * ASR Clase de datos de estado.
 * - status: Estado actual
 * - isAvailable: Provider ¿Está disponible?(Configurado apiKey)
 * - transcript: Texto de reconocimiento acumulativo actual(Contiene resultados intermedios)
 * - errorMessage: mensaje de error(Sólo estado de error;Reconnecting Llevar también durante"Reconectando…"copia inmediata)
 * - amplitudes: Historial de amplitud de volumen(normalización 0-1f,para formas de onda de UI),hasta 32
 */
data class ASRState(
    val status: ASRStatus = ASRStatus.Idle,
    val isAvailable: Boolean = false,
    val transcript: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
) {
    /** ¿Estás grabando?(Contiene conexiones/Grabación/detenerse/estado de transición de reconexión). */
    val isRecording: Boolean
        get() = status == ASRStatus.Connecting || status == ASRStatus.Listening ||
            status == ASRStatus.Stopping || status == ASRStatus.Reconnecting
}
