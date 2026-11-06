package io.zer0.ai.core

/**
 * Builds a safe terminal event for a malformed SSE frame.
 *
 * The frame body may contain user prompts, tool arguments, or provider data, so
 * diagnostics expose only its length and keep the original throwable attached
 * for local logging/observability.
 */
internal fun malformedStreamFrameError(provider: String, data: String, throwable: Throwable?): ChatStreamEvent.Error =
    ChatStreamEvent.Error(
        message = "$provider Excepción de formato de respuesta de transmisión (frameLength=${data.length}), esta ronda de solicitudes ha finalizado, inténtelo de nuevo.",
        throwable = throwable,
    )
