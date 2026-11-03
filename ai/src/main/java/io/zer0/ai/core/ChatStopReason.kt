package io.zer0.ai.core

/**
 * Provider Respuesta irrelevante para detener la determinación del motivo.
 *
 * Diferentes pares de API“La salida alcanza el límite superior”La denominación es diferente:OpenAI Comúnmente visto como `length` / `max_tokens`,
 * Anthropic para `max_tokens`,Gemini para `MAX_TOKENS`.UI y la orquestación de capa superior no debe mantener tablas de cadenas por separado.
 */
object ChatStopReason {

    private val lengthLimitedReasons = setOf(
        "length",
        "max_tokens",
        "max_output_tokens",
        "max_output_token",
        "max-tokens",
        "max-output-tokens",
        "max_tokens_exceeded",
        "token_limit",
        "token_limit_exceeded",
        "max_token_limit",
    )

    /** Determine si el motivo de detención devuelto por el proveedor indica que la respuesta finalizó porque la longitud de salida alcanzó el límite superior. */
    fun isLengthLimited(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase()?.replace(' ', '_') ?: return false
        return normalized in lengthLimitedReasons
    }

    private val toolCallStopReasons = setOf(
        "tool_calls",
        "tool_call",
        "function_call",
        "function_calls",
    )

    /**
     * v2.6.1 (#5): Determinar si el motivo de detención devuelto por el proveedor indica“El modelo se detiene para llamar a la herramienta.”.
     *
     * La salida en este caso pertenece al protocolo de la herramienta en lugar del cuerpo visible para el usuario: incluso si el cuerpo está vacío y la llamada a la herramienta no se analiza,
     * Tampoco se puede utilizar el razonamiento como respuesta en la pantalla (de lo contrario, el usuario verá el proceso de pensamiento)./Se incorporan al texto fragmentos del protocolo).
     */
    fun isToolCallStop(reason: String?): Boolean {
        val normalized = reason?.trim()?.lowercase()?.replace(' ', '_') ?: return false
        return normalized in toolCallStopReasons
    }
}
