package io.zer0.memory.summary

import androidx.room.withTransaction

/**
 * Lectura y determinación de validez de los puntos de control del contexto de la sesión.
 *
 * El punto de control en sí es sólo una fila de datos; lo que realmente determina"¿Todavía cuenta?"Lo que importa es si la historia a la que hace referencia sigue ahí.
 * Aquí, los juicios convergen en un solo lugar para evitar que cada persona que llama implemente un conjunto separado (y falten casos extremos en cada uno).
 */
class ContextCheckpointReader(
    private val db: MemoryDb,
) {
    private fun dao(): ContextCheckpointDao = db.contextCheckpointDao()

    /** Leer el punto de control de una sesión (nulo si no hay ninguno). */
    suspend fun get(sessionId: String?): ContextCheckpointEntity? = sessionId?.takeIf { it.isNotBlank() }?.let { dao().get(it) }

    /**
     * Lea el punto de control y verifique que el mensaje al que hace referencia todavía esté en el historial dado.
     *
     * - El punto de control no existe → null
     * - [ContextCheckpointEntity.lastCoveredMessageId] esta vacio → No se puede verificar, devolver"por límites secuenciales"uso
     *   (Es posible que la identificación del mensaje no sea un UUID en datos muy antiguos. En este caso [ContextCheckpointEntity.coveredSeq] Sigue siendo un límite superior válido)
     * - Los mensajes fronterizos ya no están en la historia → **Sentencia inválida y eliminada**: El historial ha sido cambiado (eliminado)/Borrar/revertir),
     *   Si continúa utilizando este resumen,"Contenido que ya no está en contexto"También dijo que sucedió antes, lo cual es una pérdida de historia y preferiría no usarlo.
     *
     * @param knownMessageIds ID de mensaje visible en el historial de la sesión actual (como una cadena)
     */
    suspend fun getValid(sessionId: String?, knownMessageIds: Collection<String>): ContextCheckpointEntity? {
        val entity = get(sessionId)
        val boundaryId = entity?.lastCoveredMessageId.orEmpty()
        // La frontera está vacía → No se puede verificar, devolver"por límites secuenciales"usando (la identificación del mensaje puede no ser UUID en datos muy antiguos,
        // En este punto, coverSeq sigue siendo un límite superior válido); El límite ya no está en la historia. → Anular y suprimir.
        val boundaryStillPresent = boundaryId.isBlank() || boundaryId in knownMessageIds
        if (entity != null && !boundaryStillPresent) {
            dao().deleteById(entity.sessionId)
        }
        return if (entity != null && boundaryStillPresent) entity else null
    }

    /**
     * Escribir punto de control.
     *
     * Sobrescritura: solo se conserva una sesión de la misma sesión y la nueva compresión reescribe los puntos de control antiguos y los nuevos intervalos en otros nuevos.
     */
    suspend fun put(entity: ContextCheckpointEntity) {
        db.withTransaction { dao().upsert(entity) }
    }

    /** Eliminar el punto de control de una sesión (llamado cuando se elimina la sesión/se borra el historial). */
    suspend fun clear(sessionId: String) {
        db.withTransaction { dao().deleteById(sessionId) }
    }
}
