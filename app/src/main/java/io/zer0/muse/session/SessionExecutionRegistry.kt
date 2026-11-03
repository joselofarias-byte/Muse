package io.zer0.muse.session

import io.zer0.common.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Un tipo de recurso de ejecución que pueden utilizar generadores, herramientas y recursos en segundo plano. */
enum class ExecutionKind {
    LLM,
    TOOL,
    APPROVAL,
    BROWSER,
    TERMINAL,
    SUBAGENT,
    DEFERRED_RESULT,
    MEDIA,
    NOTIFICATION,
    CHECKPOINT,
}

/** Ejecutar el ciclo de vida del recurso; la cancelación de solicitudes y la cancelación de finalización están estrictamente separadas. */
enum class ExecutionState {
    REGISTERED,
    RUNNING,
    CANCEL_REQUESTED,
    CANCELLED,
    COMPLETED,
    FAILED,
    SUPPRESSED,
}

/**
 * Un recurso de ejecución externo que genera asociaciones de identidad.
 *
 * [cancel] Único responsable de enviar señales de cancelación cooperativa; el final real es por [SessionExecutionRegistry.finish]
 * o [SessionExecutionRegistry.fail] grabar para evitar“Solicitar cancelación”confundido con“Recurso detenido”.
 */
data class ExecutionRecord(
    val id: String,
    val identity: GenerationIdentity,
    val kind: ExecutionKind,
    val state: ExecutionState = ExecutionState.REGISTERED,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
)

/**
 * Session-scoped Ejecutar registro de recursos.
 *
 * Este es un coordinador del ciclo de vida y no posee herramientas específicas ni implementaciones de red. La persona que llama registra un recurso que puede cancelarse,
 * Filtrar por sesión/turno/generación y emitir una señal de cancelación al detenerse; los resultados tardíos deben pasar primero
 * por [isCurrent] Verifique antes de actualizar la interfaz de usuario actual o escribir el estado de generación actual.
 *
 * Phase 3 Fiabilidad(Realizar limpieza del libro mayor):
 *  - registro activo([ExecutionState.REGISTERED]/[ExecutionState.RUNNING]/CANCEL_REQUESTED)
 *    y la historia final([ExecutionState.CANCELLED]/COMPLETED/FAILED/SUPPRESSED)almacenamiento separado:
 *    Cancelar el escaneo y el juicio generacional solo atraviesa la tabla activa, y el crecimiento infinito de la tabla histórica ya no ralentizará estos caminos calientes.
 *  - El registro estatal final está sujeto a TTL([historyRetentionMs])y capacidad([maxHistoryRecords])restricciones,
 *    Automáticamente después de cada transición de estado final [prune];La persona que llama también puede recortarlo explícitamente.
 *  - register/start/finish/fail/markCancelled La semántica externa permanece sin cambios.(Agregar solo recorte histórico).
 */
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
class SessionExecutionRegistry(
    /** Tiempo de retención del récord final(milisegundos),después de exceder [prune] Eliminado de la historia. */
    private val historyRetentionMs: Long = DEFAULT_HISTORY_RETENTION_MS,
    /** Límite superior de capacidad histórica del estado final;Cuando se exceda, presione FinishAt para eliminar de antiguo a nuevo. */
    private val maxHistoryRecords: Int = DEFAULT_MAX_HISTORY_RECORDS,
    /** fuente de tiempo(Se pueden inyectar pruebas,Verificación determinista TTL);Reloj del sistema de producción. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private data class Registered(
        val record: ExecutionRecord,
        val cancel: (() -> Unit)?,
    )

    /** registro activo(estado no final);La ruta activa solo atraviesa esta tabla. */
    private val active = ConcurrentHashMap<String, Registered>()

    /** Historia del estado final;Mantenga solo la ventana limitada requerida para el diagnóstico,por [prune] Cultivo. */
    private val history = ConcurrentHashMap<String, Registered>()

    /** La generación registrada más recientemente para cada sesión; solía rechazar los eventos de llegada tardía de la vieja generación. */
    private val latestGenerationBySession = ConcurrentHashMap<String, String>()

    /** Registre un recurso de ejecución y devuelva el ID del recurso estable. */
    fun register(identity: GenerationIdentity, kind: ExecutionKind, cancel: (() -> Unit)? = null): String {
        val id = "exec-${UUID.randomUUID()}"
        active[id] = Registered(
            record = ExecutionRecord(id = id, identity = identity, kind = kind, startedAt = nowMs()),
            cancel = cancel,
        )
        latestGenerationBySession[identity.sessionId] = identity.generationId
        return id
    }

    /** Marque el recurso para comenzar a ejecutar. */
    fun start(id: String): Boolean = update(id) { record ->
        if (record.state != ExecutionState.REGISTERED) return@update null
        record.copy(state = ExecutionState.RUNNING)
    } != null

    /** Solicita la cancelación de un recurso; las llamadas repetidas siguen siendo idempotentes. */
    fun requestCancel(id: String, reason: String = "cancelled"): Boolean {
        val registered = active[id] ?: return false
        val updated = update(id) { record ->
            if (record.state in TERMINAL_STATES || record.state == ExecutionState.CANCEL_REQUESTED) return@update null
            record.copy(state = ExecutionState.CANCEL_REQUESTED)
        }
        val accepted = updated != null
        if (accepted) {
            try {
                registered.cancel?.invoke()
            } catch (error: Exception) {
                Logger.w(TAG, "No se pudo ejecutar la señal de cancelación de recursos: id=$id kind=${registered.record.kind} reason=$reason", error)
            }
        }
        return accepted
    }

    /** Solicitud de cancelación de todos los recursos no finales bajo la sesión especificada. */
    fun requestCancelForSession(sessionId: String, reason: String = "session_cancelled"): List<String> = active.values
        .map { it.record }
        .filter { it.identity.sessionId == sessionId && it.state !in TERMINAL_STATES }
        .mapNotNull { record -> record.id.takeIf { requestCancel(it, reason) } }

    /**
     * Sólo la generación actual puede actualizar el estado actual; el evento de llegada tardía de la generación anterior devuelve falso.
     *
     * ENG-10 Conclusión de la revisión: este método**Actualmente no hay personas que llamen a producción.**, el resultado tardío de la herramienta viene dado por
     * `ChatMediaGenHost` Token generacional capturado(`ChatGenerationState.toolGenerationToken`)Interceptación.
     * ambos**No son equivalentes y no pueden sustituirse entre sí.**:
     *  - El token es un contador único global, recién generado.(Incluye cambiar a otras conversaciones.)hará**Todas las sesiones**El resultado de la herramienta en tránsito no es válido;
     *    Este método divide las generaciones según el ID de sesión y solo invalida la generación anterior de la misma sesión.
     *  - Este método también requiere adicionalmente que la generación todavía tenga registros activos.(`ACTIVE_STATES` no contiene CANCEL_REQUESTED),
     *    El control de acceso al token no tiene esto“supervivencia de recursos”Condición; este método siempre es falso cuando el registro es nulo.
     *  - El espacio de entrega no contiene GenerationIdentity. Cambiar este método equivale a cambiar el criterio, no cerrarlo.
     * Por lo tanto, este método se conserva como juicio intergeneracional y diagnóstico de cancelación en el lado del libro mayor de ejecución, y está cubierto por pruebas.
     * ([SessionExecutionRegistryTest] / [SessionExecutionRegistryPruningTest]);
     * Para conectar la ruta de reescritura de la herramienta, primero unifique las tres diferencias anteriores y luego hable sobre el cableado.
     */
    fun isCurrent(identity: GenerationIdentity): Boolean = latestGenerationBySession[identity.sessionId] == identity.generationId &&
        active.values.any { registered ->
            registered.record.identity.sessionId == identity.sessionId &&
                registered.record.identity.generationId == identity.generationId &&
                registered.record.state in ACTIVE_STATES
        }

    /** Determinar si dos eventos pertenecen a la misma generación. */
    fun matches(left: GenerationIdentity, right: GenerationIdentity): Boolean =
        left.sessionId == right.sessionId && left.generationId == right.generationId

    /**
     * Marque el recurso para su finalización normal y mantenga registros con fines de diagnóstico.
     *
     * Si el recurso ha recibido previamente una solicitud de cancelación, incluso si la llamada subyacente finaliza con un retorno normal, solo puede converger a
     * [ExecutionState.CANCELLED]; De lo contrario, los resultados de llegadas tardías durante el período de cancelación se informarán erróneamente como éxito.
     */
    fun finish(id: String): Boolean = update(id) { record ->
        when {
            record.state in TERMINAL_STATES -> record
            record.state == ExecutionState.CANCEL_REQUESTED ->
                record.copy(state = ExecutionState.CANCELLED, finishedAt = nowMs())
            else -> record.copy(state = ExecutionState.COMPLETED, finishedAt = nowMs())
        }
    } != null

    /** Marque los recursos como fallidos y mantenga registros con fines de diagnóstico. Las excepciones durante la solicitud de cancelación se clasifican en el estado final de cancelación. */
    fun fail(id: String): Boolean = update(id) { record ->
        when {
            record.state in TERMINAL_STATES -> record
            record.state == ExecutionState.CANCEL_REQUESTED ->
                record.copy(state = ExecutionState.CANCELLED, finishedAt = nowMs())
            else -> record.copy(state = ExecutionState.FAILED, finishedAt = nowMs())
        }
    } != null

    /** Marca el recurso como cancelado; se aplica para cancelar la devolución de llamada de finalización del identificador. */
    fun markCancelled(id: String): Boolean = update(id) { record ->
        if (record.state in TERMINAL_STATES) {
            record
        } else {
            record.copy(state = ExecutionState.CANCELLED, finishedAt = nowMs())
        }
    } != null

    /** Obtener el estado actual de los recursos; recursos desconocidos(Incluye historial recortado.)Devuelve nulo. */
    fun state(id: String): ExecutionState? = (active[id] ?: history[id])?.record?.state

    /** Obtenga una instantánea de recursos de la sesión especificada(Historial de estado final activo + sin recortar). */
    fun snapshot(sessionId: String? = null): List<ExecutionRecord> = (active.values + history.values)
        .map { it.record }
        .filter { sessionId == null || it.identity.sessionId == sessionId }
        .sortedBy { it.startedAt }

    /** Phase 3: Instantáneas de registros activos únicamente(Cancelar escaneo/para diagnóstico). */
    fun activeSnapshot(sessionId: String? = null): List<ExecutionRecord> = active.values
        .map { it.record }
        .filter { sessionId == null || it.identity.sessionId == sessionId }
        .sortedBy { it.startedAt }

    /** Phase 3: Instantánea de la historia final del estado únicamente(para diagnóstico;puede haber sido [prune] Cultivo). */
    fun historySnapshot(sessionId: String? = null): List<ExecutionRecord> = history.values
        .map { it.record }
        .filter { sessionId == null || it.identity.sessionId == sessionId }
        .sortedBy { it.startedAt }

    /**
     * Phase 3: Recorte la historia del estado final.
     *
     * 1. TTL: finishedAt(Reserva si falta startedAt)antes que `now - historyRetentionMs` registros eliminados;
     * 2. Capacidad: todavía exceder [maxHistoryRecords] Cuando termine, At se elimina de antiguo a nuevo.
     *
     * Los registros activos nunca se recortan;Cada transición de estado final([finish]/[fail]/[markCancelled])llamado automáticamente más tarde.
     *
     * @return Número de registros eliminados esta vez
     */
    fun prune(now: Long = nowMs()): Int {
        var removed = 0
        val cutoff = now - historyRetentionMs
        history.values.forEach { registered ->
            val finishedAt = registered.record.finishedAt ?: registered.record.startedAt
            if (finishedAt < cutoff && history.remove(registered.record.id, registered)) {
                removed++
            }
        }
        val overflow = history.size - maxHistoryRecords
        if (overflow > 0) {
            history.values
                .sortedBy { it.record.finishedAt ?: it.record.startedAt }
                .take(overflow)
                .forEach { oldest ->
                    if (history.remove(oldest.record.id, oldest)) removed++
                }
        }
        if (removed > 0) {
            Logger.d(TAG, "Realizar recorte de historial: Quitar $removed Artículo(restante ${history.size} Artículo)")
        }
        return removed
    }

    /**
     * Actualizar atómicamente un registro.
     *
     * Los registros pueden estar en la tabla activa o en la tabla de historial.;Una vez que la transformación produce un estado final, los registros se migran de la tabla activa a la tabla de historial.
     * (El error de migración indica que las llamadas simultáneas se han migrado de forma preventiva y el reintento del bucle llega a la rama histórica.)y activar el recorte histórico.
     */
    private fun update(id: String, transform: (ExecutionRecord) -> ExecutionRecord?): ExecutionRecord? {
        while (true) {
            val currentActive = active[id]
            if (currentActive != null) {
                val next = transform(currentActive.record) ?: return null
                val updated = currentActive.copy(record = next)
                if (next.state in TERMINAL_STATES) {
                    // transición de estado final:Eliminar de la lista activa(en lugar de reemplazar),Luego escribe en la tabla de historial.
                    if (!active.remove(id, currentActive)) continue
                    history[id] = updated
                    prune()
                    return next
                }
                if (active.replace(id, currentActive, updated)) return next
                continue
            }
            val currentHistory = history[id] ?: return null
            val next = transform(currentHistory.record) ?: return null
            if (history.replace(id, currentHistory, currentHistory.copy(record = next))) return next
        }
    }

    private companion object {
        const val TAG = "SessionExecutionRegistry"

        /** Los registros del estado final se conservan durante 10 minutos de forma predeterminada,Es suficiente para el diagnóstico post mortem sin ocupar memoria ilimitada. */
        const val DEFAULT_HISTORY_RETENTION_MS = 10 * 60 * 1000L

        /** Límite superior de capacidad predeterminada del historial de estado final(Los superados se eliminan por los acabados más antiguos.). */
        const val DEFAULT_MAX_HISTORY_RECORDS = 256

        val ACTIVE_STATES = setOf(
            ExecutionState.REGISTERED,
            ExecutionState.RUNNING,
        )
        val TERMINAL_STATES = setOf(
            ExecutionState.CANCELLED,
            ExecutionState.COMPLETED,
            ExecutionState.FAILED,
            ExecutionState.SUPPRESSED,
        )
    }
}
