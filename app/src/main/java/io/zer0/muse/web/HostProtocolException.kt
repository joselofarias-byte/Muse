package io.zer0.muse.web

/**
 * Errores de protocolo que puede generar directamente la capa de comando.
 *
 * serve bucle para capturarlo y devolver un `type = "error"` evento,El texto del mensaje es el campo de error.
 */
internal class HostProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
