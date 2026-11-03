package io.zer0.muse.web

/** Implementación del comando de escritura de sesión.,por [HostWebSocketGateway] Implementado usando ChatViewModel. */
internal interface HostSessionMutations {
    fun rename(sessionId: String, title: String)

    fun archive(sessionId: String, archived: Boolean)

    fun delete(sessionId: String)
}

/**
 * session.rename / session.archive / session.delete realización.
 *
 * Los tres comandos y session.select son comandos de escritura.:Pase primero el bloqueo de enfoque,Se recupera una conexión que no mantiene el foco
 * `focus_not_held` evento de error,Y no se tocarán datos de la sesión. El cliente es responsable de la confirmación secundaria de la eliminación.,
 * Solo se ejecuta el lado del servidor.
 */
internal class HostSessionCommands(
    private val focus: HostFocusTokenHolder,
    private val mutations: HostSessionMutations,
) {

    companion object {
        const val TYPE_RENAME = "session.rename"
        const val TYPE_ARCHIVE = "session.archive"
        const val TYPE_DELETE = "session.delete"

        const val EVENT_ERROR = "error"
        const val EVENT_ACCEPTED = "command.accepted"
        const val ERROR_FOCUS_NOT_HELD = "focus_not_held"

        val types: Set<String> = setOf(TYPE_RENAME, TYPE_ARCHIVE, TYPE_DELETE)
    }

    @Suppress("ThrowsCount")
    suspend fun handle(command: HostCommand, connectionId: String): HostEvent {
        val requestId = command.requestId
        if (!focus.holds(connectionId, command.sessionId)) {
            return HostEvent(type = EVENT_ERROR, requestId = requestId, error = ERROR_FOCUS_NOT_HELD)
        }
        val sessionId = command.sessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw HostProtocolException("${command.type} requires sessionId")
        return when (command.type) {
            TYPE_RENAME -> {
                val title = command.title?.trim().orEmpty()
                if (title.isEmpty()) throw HostProtocolException("session.rename requires title")
                mutations.rename(sessionId, title)
                accepted(requestId, sessionId)
            }
            TYPE_ARCHIVE -> {
                val archived = command.archived ?: throw HostProtocolException("session.archive requires archived")
                mutations.archive(sessionId, archived)
                accepted(requestId, sessionId)
            }
            TYPE_DELETE -> {
                mutations.delete(sessionId)
                accepted(requestId, sessionId)
            }
            else -> throw HostProtocolException("unsupported session command: ${command.type}")
        }
    }

    private fun accepted(requestId: String?, sessionId: String): HostEvent = HostEvent(
        type = EVENT_ACCEPTED,
        requestId = requestId,
        sessionId = sessionId,
    )
}
