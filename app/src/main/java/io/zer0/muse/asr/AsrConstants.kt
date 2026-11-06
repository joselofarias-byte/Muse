package io.zer0.muse.asr

/**
 * v1.73: ASR Constantes compartidas por el cliente — Tres clientes ASR(StepAsrClient / DashScopeAsrClient /
 * DashScopeFileAsrClient)Originalmente, el tiempo de espera de OkHttp y el tiempo de espera de identificación estaban codificados por separado.,Los valores se repiten y las modificaciones deben sincronizarse en varios lugares.
 *
 * Centralice aquí para una sintonización unificada.
 */
object AsrConstants {
    /** OkHttp Tiempo de espera de conexión(segundos). */
    const val CONNECT_TIMEOUT_SEC = 15L

    /** OkHttp Tiempo de espera de lectura(segundos). */
    const val READ_TIMEOUT_SEC = 60L

    /** Se agotó el tiempo de espera del proceso de reconocimiento general(milisegundos)— Evite que el ASR se congele. */
    const val RECOGNIZE_TIMEOUT_MS = 60_000L

    /** Número máximo de intentos cuando falla HTTP de segmentación de audio breve. */
    const val MAX_HTTP_ATTEMPTS = 2

    /** HTTP Reintentar la base de retroceso(milisegundos). */
    const val HTTP_RETRY_BACKOFF_MS = 350L

    /**
     * F-34: Número máximo de intentos de desconexión y reconexión(Límite de reintentos fallidos de solicitudes HTTP para el cliente por lotes Paso/Susurro).
     * Presiona cada vez que falle. [HTTP_RETRY_BACKOFF_MS] Retransmita el mismo segmento de audio después de un retroceso lineal.
     */
    const val RECONNECT_MAX_ATTEMPTS = 3
}
