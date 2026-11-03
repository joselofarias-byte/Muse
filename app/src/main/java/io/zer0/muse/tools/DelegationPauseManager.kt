package io.zer0.muse.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.201: Delegación suspendida/Cancelar administrador.
 *
 * Administre el estado de pausa, reanudación y cancelación de tareas delegadas en curso.
 * Funciona con DelegationPausePoint,Espere las decisiones de los usuarios en los nodos clave.
 */
class DelegationPauseManager {

    /** Tipo de decisión de suspensión. */
    enum class PauseDecision {
        APPROVE, // Aprobado para continuar
        MODIFY, // Continuar después de la modificación(llevar nueva entrada)
        REJECT, // rechazar y terminar
        CANCEL, // Cancelar toda la delegación
    }

    /** awaitPauseDecision de razones finales estructuradas. */
    enum class PauseOutcome {
        /** El usuario envió una decisión normal. */
        DECIDED,

        /** Esperó más que el tiempo de espera configurado por la política. */
        TIMED_OUT,

        /** La espera terminó debido a la cancelación de la delegación o la limpieza del gerente. */
        CANCELLED,
        ;

        companion object {
            /** Compatible con alias de tiempo de espera comúnmente nombrados. */
            val TIMEOUT: PauseOutcome get() = TIMED_OUT

            /** Unaliasing compatible con la ortografía estadounidense. */
            val CANCELED: PauseOutcome get() = CANCELLED
        }
    }

    /** solicitud de pausa(Mostrar al usuario). */
    data class PauseRequest(
        val requestId: String,
        val taskId: String,
        val taskTitle: String,
        val taskDescription: String,
        val targetType: String, // "assistant" | "team"
        val targetName: String,
        val reason: String, // ¿Por qué necesito confirmar?
        val intermediateResult: String? = null, // resultado intermedio(Opcional)
        val options: List<PauseOption> = listOf(PauseOption.APPROVE, PauseOption.REJECT),
        /** Propietaria de la delegación de padres requestId;La delegación raíz es nula. */
        val parentRequestId: String? = null,
    )

    /** Opción de pausa(Seleccionable por el usuario). */
    data class PauseOption(
        val id: String,
        val label: String,
        val decision: PauseDecision,
        val isDestructive: Boolean = false,
    ) {
        companion object {
            val APPROVE = PauseOption("approve", "Aprobado para continuar", PauseDecision.APPROVE)
            val REJECT = PauseOption("reject", "rechazar", PauseDecision.REJECT, isDestructive = true)
            val CANCEL = PauseOption("cancel", "Cancelar delegación", PauseDecision.CANCEL, isDestructive = true)
            val MODIFY = PauseOption("modify", "Continuar después de la modificación", PauseDecision.MODIFY)
        }
    }

    /** Respuesta del usuario. */
    data class PauseResponse(
        val decision: PauseDecision,
        val modifiedInput: String? = null, // MODIFY entrada modificada
        /** Razón final estructurada;La antigua llamada al constructor por defecto indicaba que el usuario envió una decisión. */
        val outcome: PauseOutcome = PauseOutcome.DECIDED,
        /** Información de diagnóstico comprobable sobre finales anormales, como tiempo de espera o cancelación. */
        val reason: String? = null,
    )

    /** Estrategia de configuración del punto de pausa. */
    @Serializable
    data class PausePolicy(
        val pauseBeforeTeam: Boolean = false, // Antes de la ejecución del flujo de trabajo del equipo
        val pauseBeforeEachMember: Boolean = false, // Antes de que cada miembro del equipo ejecute
        val pauseOnIntermediateResult: Boolean = false, // Después de que se produzcan los resultados intermedios
        val pauseOnHighRisk: Boolean = true, // Antes de una misión de alto riesgo(Habilitado por defecto)
        val autoTimeoutSec: Int = 300, // Pausar el tiempo de espera(segundos),Rechazar automáticamente después del tiempo de espera
    )

    /** Solicitudes de pausa actualmente activas(requestId -> PauseRequest). */
    private val _activePauses = MutableStateFlow<Map<String, PauseRequest>>(emptyMap())
    val activePauses: StateFlow<Map<String, PauseRequest>> = _activePauses.asStateFlow()

    /** esperando CompletableDeferred,Se utiliza para suspender la corrutina esperando la respuesta del usuario. */
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<PauseResponse>>()

    /** parentRequestId -> El ID de solicitud de pausa activa en la delegación principal actual. */
    private val activeRequestIdsByParent = ConcurrentHashMap<String, MutableSet<String>>()

    /** requestId -> parentRequestId,Elimine exactamente el mapeo cuando se use en espera finalmente. */
    private val parentRequestIdByRequest = ConcurrentHashMap<String, String>()

    /** Colección de ID de solicitud cancelados(para cancelar la etiqueta Delegation). */
    private val cancelledRequests = ConcurrentHashMap.newKeySet<String>()

    /** Registro serializado/limpiar,Evite que clearAll compita con nuevas solicitudes de pausa y provoque la pérdida del Diferido. */
    private val stateLock = Any()

    /**
     * Espere la decisión del usuario sobre el punto de pausa en la rutina.
     * llamador(suspend función)colgará aquí,Hasta que el usuario responda o se agote el tiempo.
     *
     * Regreso del tiempo de espera [PauseOutcome.TIMED_OUT] + [PauseDecision.REJECT];La cancelación externa se mantendrá como está.
     * Volver a lanzar [CancellationException],No disfrace la cancelación de la rutina como un rechazo del usuario.
     */
    suspend fun awaitPauseDecision(request: PauseRequest, policy: PausePolicy): PauseResponse {
        val deferred = CompletableDeferred<PauseResponse>()
        val parentRequestId = resolveParentRequestId(request)
        val cancelledBeforeRegistration = synchronized(stateLock) {
            if (isCancellationRequested(request.requestId, parentRequestId)) {
                true
            } else {
                val previous = pendingResponses.putIfAbsent(request.requestId, deferred)
                require(previous == null) { "La solicitud de pausa está pendiente: ${request.requestId}" }
                if (parentRequestId != null) {
                    activeRequestIdsByParent.computeIfAbsent(parentRequestId) {
                        ConcurrentHashMap.newKeySet<String>()
                    }.add(request.requestId)
                    parentRequestIdByRequest[request.requestId] = parentRequestId
                }
                _activePauses.value = _activePauses.value + (request.requestId to request)
                false
            }
        }
        if (cancelledBeforeRegistration) {
            return cancelledResponse("Delegación cancelada")
        }

        return try {
            val timeoutMs = policy.autoTimeoutSec.coerceAtLeast(0).toLong() * MILLIS_PER_SECOND
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: PauseResponse(
                decision = PauseDecision.REJECT,
                outcome = PauseOutcome.TIMED_OUT,
                reason = "Pausar el tiempo de espera,Rechazar automáticamente",
            )
        } catch (e: CancellationException) {
            throw e
        } finally {
            removePendingRequest(request.requestId, deferred)
        }
    }

    /** El usuario envía decisiones. */
    fun submitDecision(requestId: String, response: PauseResponse) {
        synchronized(stateLock) {
            pendingResponses[requestId]?.complete(response)
        }
    }

    /**
     * Cancelar tarea(El usuario cancela activamente).
     *
     * [requestId] Se puede pausar requestId,También puede ser el ID de solicitud de delegación principal. Cuando se cancela la delegación de padres,
     * Todos los diferidos actualmente en espera en pausa bajo esta delegación principal recibirán una CANCELACIÓN.
     */
    fun cancelDelegation(requestId: String) {
        synchronized(stateLock) {
            cancelledRequests.add(requestId)
            val requestIds = linkedSetOf(requestId) + collectActiveRequestIds(requestId)
            requestIds.forEach { activeRequestId ->
                pendingResponses[activeRequestId]?.complete(cancelledResponse("Delegación cancelada"))
            }
        }
    }

    /** Comprueba si la delegación ha sido cancelada. */
    fun isCancelled(requestId: String): Boolean = synchronized(stateLock) {
        isCancellationRequested(requestId, parentRequestIdByRequest[requestId])
    }

    /** Limpiar los marcadores de cancelación completados. */
    fun clearCancellation(requestId: String) {
        synchronized(stateLock) {
            cancelledRequests.remove(requestId)
        }
    }

    /** Borrar todo el estado(Llamado al cambiar de sesión.). */
    fun clearAll() {
        synchronized(stateLock) {
            // Despierta a todos los que esperan primero.,Limpiar el índice nuevamente.;De lo contrario, borrar las respuestas pendientes hará que la rutina se bloquee permanentemente.
            pendingResponses.values.forEach { deferred ->
                deferred.complete(cancelledResponse("Administrador de pausa limpio"))
            }
            _activePauses.value = emptyMap()
            pendingResponses.clear()
            activeRequestIdsByParent.clear()
            parentRequestIdByRequest.clear()
            cancelledRequests.clear()
        }
    }

    private fun removePendingRequest(requestId: String, deferred: CompletableDeferred<PauseResponse>) {
        synchronized(stateLock) {
            pendingResponses.remove(requestId, deferred)
            _activePauses.value = _activePauses.value - requestId
            val parentRequestId = parentRequestIdByRequest.remove(requestId)
            if (parentRequestId != null) {
                activeRequestIdsByParent[parentRequestId]?.let { requestIds ->
                    requestIds.remove(requestId)
                    if (requestIds.isEmpty()) {
                        activeRequestIdsByParent.remove(parentRequestId, requestIds)
                    }
                }
            }
        }
    }

    /** Resolver padre explícito id;Cuando la persona que llama anteriormente no pasa el taskId, es el ID de delegación principal. */
    private fun resolveParentRequestId(request: PauseRequest): String? {
        request.parentRequestId?.takeIf { it.isNotBlank() }?.let { return it }
        val separator = request.taskId.lastIndexOf('/')
        return if (separator > 0 && separator < request.taskId.lastIndex) {
            request.taskId.substring(0, separator)
        } else {
            request.taskId.takeIf { it.isNotBlank() }
        }
    }

    /** Determine si él mismo o su cadena de delegación principal ha sido cancelada. */
    private fun isCancellationRequested(requestId: String, parentRequestId: String?): Boolean {
        if (cancelledRequests.contains(requestId)) return true
        var currentParent = parentRequestId
        while (currentParent != null) {
            if (cancelledRequests.contains(currentParent)) return true
            currentParent = parentRequestIdByRequest[currentParent]
        }
        return cancelledRequests.any { cancelledId ->
            requestId.startsWith("$cancelledId/")
        }
    }

    /** Recopila los ID de solicitud de pausa activa de la delegación principal y de todos los descendientes. */
    private fun collectActiveRequestIds(parentRequestId: String): Set<String> {
        val result = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(parentRequestId)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            activeRequestIdsByParent[current].orEmpty().forEach { childRequestId ->
                if (result.add(childRequestId)) queue.addLast(childRequestId)
            }
        }
        return result
    }

    private fun cancelledResponse(reason: String): PauseResponse = PauseResponse(
        decision = PauseDecision.CANCEL,
        outcome = PauseOutcome.CANCELLED,
        reason = reason,
    )

    private companion object {
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
