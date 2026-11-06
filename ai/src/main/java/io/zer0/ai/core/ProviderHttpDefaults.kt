package io.zer0.ai.core

/**
 * v1.73: AI Provider HTTP Valores predeterminados compartidos por el cliente — tres Provider(OpenAI / Gemini / Anthropic)
 * Originalmente, cada uno tenía tiempos de espera de OkHttp codificados(30s connect / 300s read / 0s callTimeout),Los valores se repiten y
 * Las modificaciones deben sincronizarse en tres lugares que son fáciles de pasar por alto. Centralice aquí para una sintonización unificada.
 *
 * v1.52 Ajustar fondo:pensamiento profundo(reasoning)Es posible que el modelo no se lance durante mucho tiempo. token,readTimeout elevar a 300s;
 * callTimeout=0 Sin tiempo de espera total,La transmisión se basa en la cancelación de señales.
 */
object ProviderHttpDefaults {
    /** OkHttp Tiempo de espera de conexión(segundos). */
    const val CONNECT_TIMEOUT_SEC = 30L

    /** OkHttp Tiempo de espera de lectura(segundos)— Mejorar para dar cabida a modelos de pensamiento profundo para tareas largas. */
    const val READ_TIMEOUT_SEC = 300L

    /** OkHttp escribir tiempo de espera(segundos)— El cuerpo de la solicitud multimodal base64 es grande,60 por defecto. */
    const val WRITE_TIMEOUT_SEC = 60L

    /** OkHttp Tiempo de espera total de llamada(segundos)— 0 Indica que no hay tiempo de espera total,La transmisión se basa en la cancelación de señales. */
    /** OkHttp Tiempo de espera total de llamada(segundos)— R-AI-03: 10 Minutos para guardar,El bloqueo extremo ya no espera indefinidamente. */
    const val CALL_TIMEOUT_SEC = 600L

    /**
     * v2.5.1: Tiempo de espera total específico de la transmisión(segundos).
     *
     * Prueba real del usuario: glm-5.3 Espere a que se considere el modelo de pensamiento profundo en la estación de transferencia.(10min+)no vomites nada delta,
     * El callTimeout de 600 del cliente compartido acabará con toda la transmisión.,Respuesta truncada.
     * La transmisión se cambia a sin tiempo de espera total(0):La suspensión silenciosa todavía la realiza readTimeout(300s) atrapar,
     * La cancelación del usuario es manejada por abortSignal —— Ambos han cubierto todos los caminos anormales.
     */
    const val STREAM_CALL_TIMEOUT_SEC = 0L
}
