package io.zer0.muse.data.session

import androidx.room.Dao
import androidx.room.Query
import androidx.room.SkipQueryVerification

/**
 * R-DB-05: messages_fts raw FTS DAO.
 *
 * messages_fts No en la lista de entidades @Database(Room KSP No puedo hacerlo para FTS5/raw vtable schema
 * verificar),Muy consistente con KnowledgeChunkFtsDao,Todos los métodos utilizan @SkipQueryVerification.
 */
@Dao
@Suppress("TooManyFunctions")
interface MessageFtsDao {

    /** FTS4 ngram Búsqueda de texto completo(JOIN messages + sessions). */
    @SkipQueryVerification
    @Query(
        """
        SELECT
            m.id as messageId,
            m.sessionId as sessionId,
            m.content as content,
            m.role as role,
            m.createdAt as createdAt,
            s.title as sessionTitle
        FROM messages_fts
        JOIN messages m ON messages_fts.message_id = m.id
        JOIN sessions s ON m.sessionId = s.id
        WHERE content_ngram MATCH :matchQuery
        ORDER BY m.createdAt DESC
        LIMIT 50
    """,
    )
    suspend fun searchFts(matchQuery: String): List<MessageSearchJoin>

    /** FTS4 ngram search restricted to one session and user-visible conversation roles. */
    @SkipQueryVerification
    @Query(
        """
        SELECT
            m.id as messageId,
            m.sessionId as sessionId,
            m.content as content,
            m.role as role,
            m.createdAt as createdAt,
            s.title as sessionTitle
        FROM messages_fts
        JOIN messages m ON messages_fts.message_id = m.id
        JOIN sessions s ON m.sessionId = s.id
        WHERE content_ngram MATCH :matchQuery
          AND m.sessionId = :sessionId
          AND m.role IN ('USER', 'ASSISTANT')
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchFtsInSession(matchQuery: String, sessionId: String, limit: Int): List<MessageSearchJoin>

    /** FTS5 Búsqueda de texto completo original(JOIN messages rowid + sessions). */
    @SkipQueryVerification
    @Query(
        """
        SELECT
            m.id as messageId,
            m.sessionId as sessionId,
            m.content as content,
            m.role as role,
            m.createdAt as createdAt,
            s.title as sessionTitle
        FROM messages_fts
        JOIN messages m ON messages_fts.rowid = m.rowid
        JOIN sessions s ON m.sessionId = s.id
        WHERE messages_fts MATCH :matchQuery
        ORDER BY m.createdAt DESC
        LIMIT 50
    """,
    )
    suspend fun searchFts5(matchQuery: String): List<MessageSearchJoin>

    /** FTS5 search restricted to one session and user-visible conversation roles. */
    @SkipQueryVerification
    @Query(
        """
        SELECT
            m.id as messageId,
            m.sessionId as sessionId,
            m.content as content,
            m.role as role,
            m.createdAt as createdAt,
            s.title as sessionTitle
        FROM messages_fts
        JOIN messages m ON messages_fts.rowid = m.rowid
        JOIN sessions s ON m.sessionId = s.id
        WHERE messages_fts MATCH :matchQuery
          AND m.sessionId = :sessionId
          AND m.role IN ('USER', 'ASSISTANT')
        ORDER BY m.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchFts5InSession(matchQuery: String, sessionId: String, limit: Int): List<MessageSearchJoin>

    /** Inserte el índice de ngram FTS4. */
    @SkipQueryVerification
    @Query("INSERT INTO messages_fts(message_id, content_ngram) VALUES(:messageId, :contentNgram)")
    suspend fun insertFts(messageId: String, contentNgram: String)

    /** Elimine el índice FTS para el mensaje especificado. */
    @SkipQueryVerification
    @Query("DELETE FROM messages_fts WHERE message_id = :messageId")
    suspend fun deleteFts(messageId: String)

    /** Elimine el índice FTS de todos los mensajes en la sesión especificada. */
    @SkipQueryVerification
    @Query("DELETE FROM messages_fts WHERE message_id IN (SELECT id FROM messages WHERE sessionId = :sessionId)")
    suspend fun deleteFtsBySession(sessionId: String)

    /** Eliminar la sesión especificada createdAt >= fromCreatedAt Índice de mensajes FTS. */
    @SkipQueryVerification
    @Query(
        """
        DELETE FROM messages_fts 
        WHERE message_id IN (
            SELECT id FROM messages WHERE sessionId = :sessionId AND createdAt >= :fromCreatedAt
        )
        """,
    )
    suspend fun deleteFtsBySessionAndCreatedAt(sessionId: String, fromCreatedAt: Long)

    /** Borrar índice FTS(rebuild usar). */
    @SkipQueryVerification
    @Query("DELETE FROM messages_fts")
    suspend fun clearFts()

    /** messages_fts Número de filas de la tabla(ensureFtsIndexConsistent Más útil). */
    @SkipQueryVerification
    @Query("SELECT COUNT(*) FROM messages_fts")
    suspend fun countFts(): Int

    /** FTS5 external content Reconstruya completamente el índice. */
    @SkipQueryVerification
    @Query("INSERT INTO messages_fts(messages_fts) VALUES('rebuild')")
    suspend fun rebuildFts5()
}
