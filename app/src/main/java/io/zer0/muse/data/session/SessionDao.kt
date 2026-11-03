package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Objeto de acceso a datos de sesión.
 */
@Dao
interface SessionDao {

    /** Mira todas las conversaciones(Máxima prioridad,Luego presione actualizado en en orden descendente). Contiene sesiones archivadas y eliminadas temporalmente,Sólo para uso de copia de seguridad y exportación. Para la lista principal [observeActive]. */
    @Query("SELECT * FROM sessions ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** v0.45: Observar conversaciones no archivadas(Máxima prioridad,Luego presione actualizado en en orden descendente). Para la lista principal. */
    @Query("SELECT * FROM sessions WHERE archived = 0 AND deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeActive(): Flow<List<SessionEntity>>

    /**
     * v1.28: Observar sesiones de tareas no archivadas(Excluir sesiones de la pestaña Agente). Para listas de tareas.
     */
    @Query(
        "SELECT * FROM sessions WHERE archived = 0 AND isAgentSession = 0 " +
            "AND deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC",
    )
    fun observeTaskSessions(): Flow<List<SessionEntity>>

    /** v1.28: Observar sesiones de la pestaña Agente(Agent charla diaria). */
    @Query("SELECT * FROM sessions WHERE isAgentSession = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAgentSessions(): Flow<List<SessionEntity>>

    /** v1.28: Obtenga la sesión del Agente más reciente(Desechable,Se utiliza para reanudar automáticamente las conversaciones del agente.). */
    @Query("SELECT * FROM sessions WHERE isAgentSession = 1 AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestAgentSession(): SessionEntity?

    /** v1.0.54: Presione Asistente para ver las sesiones recientes del Agente(Restaurar la sala de conversación del asistente al cambiar de asistente). */
    @Query(
        "SELECT * FROM sessions WHERE assistantId = :assistantId AND deletedAt IS NULL " +
            "AND isAgentSession = 1 ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun getRecentAgentByAssistant(assistantId: String, limit: Int): List<SessionEntity>

    /** v0.45: Observe conversaciones archivadas.v1.67: Clasificación consistente con la lista principal(pinned DESC, updatedAt DESC). */
    @Query("SELECT * FROM sessions WHERE archived = 1 AND deletedAt IS NULL ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeArchived(): Flow<List<SessionEntity>>

    /** v0.45: Alternar el estado de archivo de la sesión. */
    @Query("UPDATE sessions SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    // ── v2.0: eliminación suave ──

    /** v2.0: Sesión de eliminación temporal(Establecer eliminado en la marca de tiempo). */
    @Query("UPDATE sessions SET deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long)

    /** v2.0: Restaurar sesiones eliminadas temporalmente. */
    @Query("UPDATE sessions SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)

    /** v2.0: Observe las sesiones eliminadas temporalmente dentro de los 7 días. */
    @Query("SELECT * FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt >= :cutoff ORDER BY deletedAt DESC")
    fun observeDeleted(cutoff: Long): Flow<List<SessionEntity>>

    /** v2.0: Elimine permanentemente las sesiones eliminadas temporalmente de más de 7 días. */
    @Query("DELETE FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun permanentlyDeleteOldSessions(cutoff: Long)

    /** v2.0: Conversaciones eliminadas temporalmente que se eliminarán permanentemente id — La limpieza de la memoria requiere primero saber qué sesiones han desaparecido. */
    @Query("SELECT id FROM sessions WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun listIdsDeletedBefore(cutoff: Long): List<String>

    /** Recupérelo antes de borrar todas las sesiones. id,Para fines de limpieza de memoria. */
    @Query("SELECT id FROM sessions")
    suspend fun listAllIds(): List<String>

    /** Alternar estado superior de la conversación(Phase 8.2 L13 Apoyando). */
    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    /** Cambiar enlace de sesión Assistant(Phase 8.2 H6). */
    @Query("UPDATE sessions SET assistantId = :assistantId WHERE id = :id")
    suspend fun setAssistantId(id: String, assistantId: String)

    /** v1.0.72: Configurar sesión"Sin referencia a la memoria"logo. */
    @Query("UPDATE sessions SET ignoreMemory = :ignore WHERE id = :id")
    suspend fun setIgnoreMemory(id: String, ignore: Boolean)

    /**
     * Guarde los borradores de entrada no enviados.
     *
     * Cada sesión se guarda de forma independiente, por lo que los borradores no se pierden al cambiar de sesión. Una cadena vacía significa borrada (enviada o eliminada por el usuario).
     * No actualizado updatedAt —— Los borradores son solo un búfer de entrada y no deben colocar las sesiones en la parte superior de la lista.
     */
    @Query("UPDATE sessions SET input_draft = :draft WHERE id = :id")
    suspend fun updateInputDraft(id: String, draft: String)

    /** v2.x Importar calentamiento:ajustes/Borra todas las marcas del historial de la primera ronda de la sesión. */
    @Query("UPDATE sessions SET warmupPending = :pending WHERE id = :id")
    suspend fun setWarmupPending(id: String, pending: Boolean)

    /** v2.5.3 (P3-2): Escribir una instantánea de configuración a nivel de sesión JSON(null = borrar instantánea). */
    @Query("UPDATE sessions SET configSnapshotJson = :snapshotJson WHERE id = :id")
    suspend fun setConfigSnapshot(id: String, snapshotJson: String?)

    /** Phase 9.1 (M13): Cambiar la carpeta de la sesión(null = Mover carpeta a desagrupada). */
    @Query("UPDATE sessions SET folderId = :folderId WHERE id = :id")
    suspend fun setFolderId(id: String, folderId: String?)

    /** Al eliminar una carpeta, borre las sesiones debajo de ella en lotes folderId(Mover a desagrupado).Android 16 Deshabilite el DML ejecutivo de SQL. */
    @Query("UPDATE sessions SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderId(folderId: String)

    /** Obtener una única sesión por id(Desechable). */
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    /**
     * v2.6.7 fix (AUDIT F16): Todos los ID de sesión bajo el nombre de un asistente.
     *
     * Sólo se proyecta la columna id; se utiliza para un filtrado único para la recuperación entre sesiones para evitar verificar las sesiones una por una.
     */
    @Query("SELECT id FROM sessions WHERE assistantId = :assistantId AND deletedAt IS NULL")
    suspend fun listIdsByAssistant(assistantId: String): List<String>

    /** Obtener sesiones en lotes por ID,Se utiliza para asociar títulos de resumen en el centro de memoria. */
    @Query("SELECT * FROM sessions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SessionEntity>

    /** Insertar nueva sesión(Ignorar en caso de conflicto). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: SessionEntity)

    /** sesión de actualización(Título/updatedAt/lastMessagePreview). */
    @Update
    suspend fun update(session: SessionEntity)

    /**
     * H-SESS2: Sesión de cambio de nombre atómico(evitar leer-cambiar-Escribir actualizaciones perdidas de simultaneidad).
     * Mantenimiento sincrónico actualizadoEn.
     */
    @Query("UPDATE sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, now: Long)

    /**
     * H-SESS2: Vista previa de la sesión de actualización atómica + updatedAt(Eliminar el último mensaje y rellenar la nueva vista previa).
     */
    @Query("UPDATE sessions SET lastMessagePreview = :preview, updatedAt = :now WHERE id = :id")
    suspend fun updatePreview(id: String, preview: String, now: Long)

    /**
     * H-SESS2: Vista previa de la actualización atómica + updatedAt,y mientras el título sigue siendo el predeterminado y el mensaje actual es del usuario
     * Sobrescribir automáticamente los encabezados con el prefijo del contenido del mensaje(El primer mensaje de usuario recibe un nombre automáticamente.).
     *
     * Utilice CASE para completar actualizaciones de títulos condicionales en una sola ACTUALIZACIÓN,evitar completamente la lectura-cambiar-Escribe una condición de carrera.
     * :isUser Pasar 1 indica que la función del mensaje actual es USER,De lo contrario 0.
     */
    @Query(
        """
        UPDATE sessions SET
            lastMessagePreview = :preview,
            updatedAt = :now,
            title = CASE
                WHEN title = :defaultTitle AND :isUser = 1 THEN :autoTitle
                ELSE title
            END
        WHERE id = :id
    """,
    )
    suspend fun updatePreviewAndTitle(id: String, preview: String, now: Long, defaultTitle: String, isUser: Int, autoTitle: String)

    /**
     * Actualizar solo la vista previa de la sesión + updatedAt,No cambies el título.
     * Nomenclatura unificada por resumen de IA al salir de la conversación,El primer mensaje ya no sobrescribe automáticamente el título.
     */
    @Query(
        """
        UPDATE sessions SET
            lastMessagePreview = :preview,
            updatedAt = :now
        WHERE id = :id
    """,
    )
    suspend fun updatePreviewOnly(id: String, preview: String, now: Long)

    /** Eliminar sesión por id(Eliminación de mensajes en cascada). */
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Borrar todas las conversaciones(Se utiliza para realizar copias de seguridad y restaurar,Android 16 prohibido execSQL DML). */
    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /** Número total de sesiones(para estadísticas). */
    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int

    /** Transmisiones totales de sesión(Para panel de estadísticas). */
    @Query("SELECT COUNT(*) FROM sessions")
    fun observeCount(): Flow<Int>

    // ── v1.107 Método de mantenimiento de campo redundante ──

    /** v1.107: Incremento atómico y disminución del recuento de mensajes de sesión.(delta Puede ser positivo o negativo). */
    @Query("UPDATE sessions SET messageCount = messageCount + :delta WHERE id = :id")
    suspend fun incrementMessageCount(id: String, delta: Int)

    /** v1.107: Establecer directamente el recuento de mensajes de sesión(relleno/Para calibración). */
    @Query("UPDATE sessions SET messageCount = :count WHERE id = :id")
    suspend fun setMessageCount(id: String, count: Int)

    /** v5: Incrementar el recuento de sesiones secundarias de la bifurcación. */
    @Query("UPDATE sessions SET childCount = childCount + 1 WHERE id = :id")
    suspend fun incrementChildCount(id: String)

    /** v5: Consultar la subsesión bifurcada de la sesión especificada(Orden descendente por actualizadoEn). */
    @Query("SELECT * FROM sessions WHERE parentSessionId = :parentId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    suspend fun getChildSessions(parentId: String): List<SessionEntity>

    /** v5: Consulta el título de la sesión principal de la sesión especificada. */
    @Query("SELECT title FROM sessions WHERE id = :parentId")
    suspend fun getParentSessionTitle(parentId: String): String?

    /**
     * v1.0.52: Consultar las N conversaciones más recientes de un asistente(Para inyección de referencia de chats recientes system prompt).
     *
     * Filtrar reglas y [observeTaskSessions] Alineación:
     *  - excluir archivado(archived=1)
     *  - Excluir eliminación temporal(deletedAt IS NOT NULL)
     *  - Excluir sesiones de la pestaña Agente(isAgentSession=1,Agent No participes en chats diarios como referencia.)
     * Ordenar por actualizadoEn DESC,LIMIT :limit.
     */
    @Query(
        "SELECT * FROM sessions WHERE assistantId = :assistantId " +
            "AND archived = 0 AND deletedAt IS NULL AND isAgentSession = 0 " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    suspend fun getRecentByAssistant(assistantId: String, limit: Int): List<SessionEntity>

    @Query("UPDATE sessions SET lastReadMessageId = :messageId, lastReadCount = :readCount WHERE id = :sessionId")
    suspend fun updateLastReadMessage(sessionId: String, messageId: String, readCount: Int)

    @Query("UPDATE sessions SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    /** B8-01: Actualizar el cronograma de mensajería proactiva a nivel de sesión(null=Horario claro). */
    @Query("UPDATE sessions SET proactiveNextTriggerAt = :nextTriggerAt WHERE id = :id")
    suspend fun updateProactiveNextTriggerAt(id: String, nextTriggerAt: Long?)

    /**
     * v2.6.13: Obtenga la sesión por el valor centinela de ID de carpeta (para la reutilización de la sesión dedicada a la recopilación de tarjetas).
     *
     * No es necesario hacer coincidir el título: el título es redacción localizada y cambiará a otro valor después de cambiar de idioma, lo que dará como resultado que se cree una nueva sesión cada vez que se cambie de idioma.
     *
     * Colóquelo al final de la interfaz: la línea base de ktlint de este módulo coincide con el número de línea. Insertar en el medio hará que todas las entradas posteriores se desvíen.
     */
    @Query("SELECT * FROM sessions WHERE folderId = :folderId AND deletedAt IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getByFolderId(folderId: String): SessionEntity?

    /**
     * Punto de colocación de mensaje activo:Recientemente, el asistente hizo que un usuario hablara y escribiera una conversación legal.(Tome el primer elemento en orden descendente según el tiempo de la última actividad.).
     *
     * Escribir condiciones de exclusión SQL,No es necesario recuperarlo y filtrarlo en la memoria.:Solo toma uno aquí,El filtrado de memoria es igual a
     * "Primero encontrar fallas y luego arreglarlas.",Después de que el excluido falle, degenerará en una nueva sesión para toda la ronda.,El juicio es un desperdicio.
     *  - deletedAt No vacío:Eliminación suave
     *  - archived = 1:Archivado
     *  - No hay mensajes de USUARIO no eliminados:El usuario no ha hablado aquí.,No es un “lugar para terminar de charlar”
     *
     * capa exterior caminando idx_sessions_assistantId_updatedAt(assistantId + updatedAt),
     * EXISTS La subconsulta va idx_messages_sessionId_createdAt_role;Detente en el primer golpe,
     * No cambie ninguna consulta o índice utilizado por la lista de conversaciones principal.
     *
     * Ponlo al final de la interfaz.:La línea base de ktlint de este módulo coincide por número de línea,La inserción intermedia hará que todas las entradas posteriores se desvíen.
     */
    @Query(
        "SELECT * FROM sessions s WHERE s.assistantId = :assistantId " +
            "AND s.deletedAt IS NULL AND s.archived = 0 " +
            "AND EXISTS (SELECT 1 FROM messages m WHERE m.sessionId = s.id " +
            "AND m.role = 'USER' AND m.deletedAt IS NULL) " +
            "ORDER BY s.updatedAt DESC LIMIT 1",
    )
    suspend fun getRecentActiveByAssistant(assistantId: String): SessionEntity?

    /**
     * El asistente ha hecho hablar recientemente a los usuarios.**conversación normal**(Excluyendo las sesiones de la pestaña Agente).
     *
     * Úselo primero para mensajes proactivos:Agent Tab Es una interfaz independiente y el usuario que habla allí no significa
     * Los mensajes no solicitados deberían aparecer en la página del Agente. Sólo recurra a la sesión del Agente cuando no haya una sesión normal.
     *
     * El motivo para colocarlo al final de la interfaz es el mismo que el anterior (ktlint Las líneas base coinciden con el número de línea).
     */
    @Query(
        "SELECT * FROM sessions s WHERE s.assistantId = :assistantId " +
            "AND s.deletedAt IS NULL AND s.archived = 0 AND s.isAgentSession = 0 " +
            "AND EXISTS (SELECT 1 FROM messages m WHERE m.sessionId = s.id " +
            "AND m.role = 'USER' AND m.deletedAt IS NULL) " +
            "ORDER BY s.updatedAt DESC LIMIT 1",
    )
    suspend fun getRecentChatByAssistant(assistantId: String): SessionEntity?
}
