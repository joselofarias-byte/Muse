package io.zer0.muse.data.subagent

import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.0.53: Libro mayor de hilos de subagente(Versión persistente).
 *
 * reemplazar viejo tools/SubagentThreadStore.kt(Versión de memoria). Dos rutas de subagente compartidas:
 *  - camino A: SubagentTool + SkillExecutor.delegateAgent nonBlocking
 *  - camino B: SubagentRunSkill + SubagentRunner
 *
 * API Compatibilidad:
 *  - viejo API(beginRun/getThread/closeThread/runSerialized/listActiveThreads)Reservar,
 *    SubagentTool Actualización sin sentido(Cambie solo el nombre del paquete de importación)
 *  - nuevo API(getOrCreate/recordRun/close/listOpen/appendMessages/loadMessages)
 *    suministro SubagentRunner / UI / subagent_close uso
 *
 * persistencia:
 *  - Thread libro mayor: Room mesa subagent_threads
 *  - Historial de subsesiones: JSONL Archivo filesDir/subagent_sessions/<threadId>.jsonl(por [SubagentSessionStore] gestión)
 *  - runSerialized: memoria Mutex Map(App Perdido después de reiniciar,Pero el estado del hilo persistente está en Room,Después de reiniciar, la nueva ejecución simultánea competirá por el bloqueo nuevamente sin conflictos.)
 */
class SubagentThreadStore(
    private val dao: SubagentThreadDao,
    private val sessionStore: SubagentSessionStore,
) {
    companion object {
        private const val TAG = "SubagentThreadStore"

        /** v1.0.74: Determinación de subprocesos huérfanos — open Si no se ha actualizado durante más de 24 horas, se considerará heredado. */
        private const val ORPHAN_STALE_MS = 24 * 60 * 60 * 1000L
    }

    private val runLocks = ConcurrentHashMap<String, Mutex>()

    // ============ viejo API(Método equivalente,comisión nueva API,Mantenga la compatibilidad de SubagentTool)============

    /**
     * API antigua: registrar un nuevo hilo(memoria+persistencia).SubagentTool.doLaunch llamar.
     *
     * Nota:La versión anterior está sincronizada y no. suspend,La nueva versión se cambia a suspend(Necesito escribir Room).
     * SubagentTool.doLaunch ha sido llamado en la corrutina,suspend Sin impacto.
     */
    suspend fun beginRun(threadId: String, assistantId: String, parentSessionId: String?) {
        getOrCreate(
            threadId = threadId,
            parentSessionId = parentSessionId ?: "default",
            assistantId = assistantId,
        )
    }

    /** API antigua: obtener información del hilo(Leer desde la capa de persistencia).SubagentTool.doReply/doClose llamar. */
    suspend fun getThread(threadId: String): ThreadEntry? {
        val entity = dao.getById(threadId) ?: return null
        return entity.toThreadEntry()
    }

    /** API antigua: Cerrar hilo(persistencia).SubagentTool.doClose llamar. */
    suspend fun closeThread(threadId: String) {
        dao.close(threadId)
    }

    /** API antigua: listar hilos activos(desde la capa de persistencia).SubagentTool list llamar. */
    suspend fun listActiveThreads(): List<ThreadEntry> {
        return dao.listAllOpen().map { it.toThreadEntry() }
    }

    /** API antigua: ejecución serializada(Cola con threadId). */
    suspend fun <T> runSerialized(threadId: String, block: suspend () -> T): T {
        val mutex = runLocks.computeIfAbsent(threadId) { Mutex() }
        try {
            return mutex.withLock {
                val entity = dao.getById(threadId)
                if (entity?.status == "closed") {
                    throw IllegalStateException("Thread $threadId is closed")
                }
                block()
            }
        } finally {
            // Reparación de auditoría (C-08): Colección de candados movida fuera del bloque de retención de candados — El candado ya no se usa legítimamente cuando se cierra el hilo.,
            // finalmente(En este momento withLock ha liberado el bloqueo.)Eliminar del mapa según sea necesario,Evite que runLocks crezca ilimitadamente.
            // antes close() directamente dentro del bloque de bloqueo runLocks.remove,Competirá con el cálculo concurrente IfAbsent,
            // Posible creación de un nuevo Mutex para la serialización que rompe el mismo threadId;La extracción ahora está completamente desacoplada de la retención de la cerradura.
            // compare-and-check: Eliminar solo si el mapa todavía apunta al mismo mutex utilizado esta vez,
            // Evite eliminar accidentalmente nuevas entradas escritas por un nuevo ComputeIfAbsent.
            if (dao.getById(threadId)?.status == "closed") {
                runLocks.computeIfPresent(threadId) { _, current ->
                    if (current === mutex) null else current
                }
            }
        }
    }

    /** API antigua: limpiar hilos cerrados(Limpieza de bloqueo de memoria;La capa de persistencia no se mueve). */
    fun cleanupClosed() {
        // Reparación de auditoría (C-08): runLocks referencia no débil(fuerte referencia Map),Reciclaje por runSerialized
        // finally + cleanupOrphanThreads Completar bajo demanda;Esta implementación vacía sólo es compatible con versiones antiguas. API,No limpie la cerradura aquí.
    }

    // ============ nuevo API(suministro SubagentRunner / UI / subagent_close uso)============

    /** Reparación de auditoría (1.1) + A-11: threadId control de seguridad — Sólo se aceptan juegos de caracteres seguros,Evite los cruces de caminos.
     * La salida del threadId de la implementación original de LLM se escribe directamente File(sessionsDir, "$threadId.jsonl"),
     * valor malicioso(Como por ejemplo "../../databases/")Se puede escribir en cualquier lugar del directorio privado de la aplicación.
     * A-11: liberar ':' y relajar el límite superior de longitud para 128 — La clave estable del hilo de susurros del chat grupal tiene el formato
     * "whisper:<chatId>:<assistantId>"(Dos UUID ~80+ caracteres),Ser rechazado anteriormente resultó en todos los mensajes privados.
     * Crear nuevos hilos aleatorios(Dividir historial de chat privado + expansión de mesa). Los dos puntos son legales en el sistema de archivos de Android y no se pueden utilizar
     * cruce de camino,La liberación no debilita los objetivos de seguridad originales. */
    private val SAFE_THREAD_ID = Regex("^[a-zA-Z0-9_:-]{1,128}$")

    /** Consigue o crea un hilo. Regresar (threadId, Ya sea para crear un nuevo). */
    suspend fun getOrCreate(
        threadId: String?,
        parentSessionId: String,
        assistantId: String,
        label: String? = null,
        access: String = "read",
    ): Pair<String, Boolean> {
        if (threadId != null) {
            // ilegal threadId(Contiene separador de ruta/..espera):No entrar en la biblioteca ni escribir archivos,Utilice aleatorio en su lugar id
            val safe = SAFE_THREAD_ID.matches(threadId)
            if (!safe) {
                Logger.w(TAG, "getOrCreate: Id. de hilo ilegal rechazado: ${threadId.take(40)}")
            } else {
                val existing = dao.getById(threadId)
                if (existing != null) return threadId to false
            }
        }
        val newId = if (threadId != null && SAFE_THREAD_ID.matches(threadId)) {
            threadId
        } else {
            "thread-" + java.util.UUID.randomUUID().toString().take(12)
        }
        val now = System.currentTimeMillis()
        dao.upsert(
            SubagentThreadEntity(
                threadId = newId,
                parentSessionId = parentSessionId,
                childSessionId = null,
                childSessionPath = sessionStore.pathOf(newId).absolutePath,
                assistantId = assistantId,
                label = label,
                access = access,
                status = "open",
                runCount = 0,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return newId to true
    }

    /** Registra los resultados de una carrera.(actualizar runCount/lastRunStatus/lastSummary). */
    suspend fun recordRun(threadId: String, status: String, summary: String?, sessionPath: String?) {
        val entity = dao.getById(threadId) ?: run {
            Logger.w(TAG, "recordRun: thread $threadId not found, skipping")
            return
        }
        dao.upsert(
            entity.copy(
                runCount = entity.runCount + 1,
                lastRunStatus = status,
                lastSummary = summary,
                childSessionPath = sessionPath ?: entity.childSessionPath,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** cerrar hilo(Devuelve si el apagado fue exitoso:true=Originales open→closed;false=no existe o tiene closed). */
    suspend fun close(threadId: String): Boolean {
        val entity = dao.getById(threadId) ?: return false
        if (entity.status == "closed") return false
        dao.close(threadId)
        // Revisión de corrección (2.0 B-15): Sin ejecución, la entrada del bloqueo se recupera inmediatamente cuando se mantiene el bloqueo. — El C-08 original sólo se basó en
        // runSerialized El reciclaje final:Si no hay seguimiento después de cerrar el hilo, runSerialized,El candado nunca se recupera.
        // Mutex.isLocked Determinar si actualmente hay una ejecución que mantiene el bloqueo:
        //  - sin cerradura → Eliminar directamente(El hilo tiene closed,También se implementarán nuevos bloqueos posteriores para ComputeIfAbsent.
        //    Tírelo inmediatamente en la cerradura. "Thread is closed",No rompe la serialización);
        //  - hay un candado(recordRunAndMaybeClose Llame a este método dentro del bloque de retención de cerraduras.)→ dejar para
        //    runSerialized El reciclaje final,Evite la eliminación del bloqueo al romper la serialización(C-08 Razón).
        runLocks.computeIfPresent(threadId) { _, current ->
            if (current.isLocked) current else null
        }
        return true
    }

    /** Listar hilos abiertos para una sesión principal(Para uso de UI/subagent_close). */
    suspend fun listOpen(parentSessionId: String): List<SubagentThreadEntity> {
        return dao.listOpenBySession(parentSessionId)
    }

    /**
     * v1.0.74: Comience a limpiar el hilo huérfano — App Después de reiniciar,Abrir hilo sobrante del último proceso.
     * (proceso matado/El accidente provocó que no llegara subagent_close)será marcado como closed,
     * evitar"Tareas de subagente en segundo plano"mostrar siempre(Comentarios de los usuarios:N Las tareas de versiones anteriores siempre están colgadas).
     *
     * reglas:open y exceder [staleMs] Los hilos no actualizados se consideran huérfanos.(El hilo que realmente se está ejecutando.
     * Seguirá actualizándose updatedAt;Los hilos recién creados al inicio no se ven afectados).
     * @return Número de hilos a limpiar
     */
    suspend fun cleanupOrphanThreads(staleMs: Long = ORPHAN_STALE_MS): Int {
        val now = System.currentTimeMillis()
        val orphans = dao.listAllOpen().filter { now - it.updatedAt > staleMs }
        orphans.forEach { dao.close(it.threadId, now) }
        // Reparación de auditoría (C-08): Hilo huérfano(proceso matado/Legado de accidente,nunca vengas close)si runLocks
        // La principal fuente de crecimiento ilimitado — Nunca activan la colección finalmente de runSerialized.
        // Aquí, la entrada de bloqueo se elimina junto con la limpieza.,Evite la memoria residente en Mutex de dichos subprocesos abiertos permanentes.
        orphans.forEach { runLocks.remove(it.threadId) }
        if (orphans.isNotEmpty()) {
            Logger.i(TAG, "empezar a limpiar ${orphans.size} hilo de agente huérfano: ${orphans.joinToString { it.threadId.take(12) }}")
        }
        return orphans.size
    }

    /** Ruta del archivo de sesión(Para uso de SubagentRunner Result.sessionPath). */
    fun sessionPathOf(threadId: String): String = sessionStore.pathOf(threadId).absolutePath

    // ============ Lectura y escritura del historial de subsesiones.(confiar SessionStore)============

    suspend fun appendMessages(threadId: String, messages: List<UIMessage>) = sessionStore.append(threadId, messages)

    suspend fun loadMessages(threadId: String, maxContextTokens: Int = 6000): List<UIMessage> =
        sessionStore.load(threadId, maxContextTokens)

    // ============ Compatible con tipos ThreadEntry más antiguos(Evite la revisión de SubagentTool)============

    data class ThreadEntry(
        val threadId: String,
        val assistantId: String,
        val parentSessionId: String?,
        val createdAt: Long,
        var status: ThreadStatus = ThreadStatus.ACTIVE,
        var lastActivityAt: Long = System.currentTimeMillis(),
    )

    enum class ThreadStatus { ACTIVE, CLOSED }

    private fun SubagentThreadEntity.toThreadEntry() = ThreadEntry(
        threadId = threadId,
        assistantId = assistantId,
        parentSessionId = parentSessionId,
        createdAt = createdAt,
        status = if (status == "open") ThreadStatus.ACTIVE else ThreadStatus.CLOSED,
        lastActivityAt = updatedAt,
    )
}
