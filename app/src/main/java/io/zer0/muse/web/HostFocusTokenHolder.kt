package io.zer0.muse.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F-39: Token de aislamiento de enfoque/serie multicliente.
 *
 * chat.send / session.select / settings.update Espere a que el comando de escritura cambie directamente el estado de la aplicación compartida.,Si no está aislado,
 * Dos navegadores pueden generar interrupciones cruzadas entre sí simultáneamente. El token de enfoque garantiza que como máximo una conexión mantenga este estado en cualquier momento,
 * Los comandos de escritura para las conexiones restantes devuelven un error explícito focus_not_held.
 */
internal class HostFocusTokenHolder {
    private val mutex = Mutex()
    private var holderConnectionId: String? = null
    private var holderSessionId: String? = null

    /** Aprovecha el centro de atención;Sólo tiene éxito cuando nadie lo posee actualmente o es esta conexión. */
    suspend fun acquire(connectionId: String, sessionId: String?): Boolean = mutex.withLock {
        if (holderConnectionId == null || holderConnectionId == connectionId) {
            holderConnectionId = connectionId
            if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
            true
        } else {
            false
        }
    }

    /**
     * Verificar antes de escribir el comando;Sólo pueden pasar conexiones enfocadas,Y actualice fácilmente el enfoque de la sesión.
     * Si nadie lo tiene actualmente(Por ejemplo, el titular ha sido desconectado.),El comando tiene prioridad cuando se escribe por primera vez.("Prioridad de primer envío").
     */
    suspend fun holds(connectionId: String, sessionId: String?): Boolean = mutex.withLock {
        when {
            holderConnectionId == connectionId -> {
                if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
                true
            }
            holderConnectionId == null -> {
                holderConnectionId = connectionId
                if (!sessionId.isNullOrBlank()) holderSessionId = sessionId
                true
            }
            else -> false
        }
    }

    /** Liberar el foco cuando se pierda la conexión(Sólo si la conexión aún se mantiene);Si una nueva conexión lo ha reemplazado anteriormente, no se moverá. */
    suspend fun release(connectionId: String) = mutex.withLock {
        if (holderConnectionId == connectionId) {
            holderConnectionId = null
            holderSessionId = null
        }
    }
}
