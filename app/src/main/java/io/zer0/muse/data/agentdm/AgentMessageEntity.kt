package io.zer0.muse.data.agentdm

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Agent Sistema de mensajería privada.
 *
 * Agent mensaje privado asincrónico,Room Persistencia.
 * Respuesta en modo canal de soporte (Responder como mensaje del canal en el chat grupal).
 */
@Serializable
@Entity(tableName = "agent_messages")
data class AgentMessageEntity(
    @PrimaryKey
    val id: String,
    /** remitente agent id */
    @ColumnInfo(name = "from_agent_id")
    val fromAgentId: String,
    /** Receptor agent id */
    @ColumnInfo(name = "to_agent_id")
    val toAgentId: String,
    /** Contenido del mensaje */
    val content: String,
    /** ¿Ha sido leído? */
    @ColumnInfo(name = "is_read", defaultValue = "0")
    val isRead: Boolean = false,
    /** Responder al mensaje original id (para hilos) */
    @ColumnInfo(name = "reply_to_id")
    val replyToId: String? = null,
    /** Crear marca de tiempo */
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface AgentMessageDao {
    @Query("SELECT * FROM agent_messages WHERE to_agent_id = :agentId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getInbox(agentId: String, limit: Int = 50): List<AgentMessageEntity>

    @Query("SELECT * FROM agent_messages WHERE from_agent_id = :agentId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getSent(agentId: String, limit: Int = 50): List<AgentMessageEntity>

    @Query(
        "SELECT * FROM agent_messages WHERE (from_agent_id = :a AND to_agent_id = :b) OR (from_agent_id = :b AND to_agent_id = :a) ORDER BY created_at ASC LIMIT :limit",
    )
    suspend fun getConversation(a: String, b: String, limit: Int = 100): List<AgentMessageEntity>

    @Query("SELECT COUNT(*) FROM agent_messages WHERE to_agent_id = :agentId AND is_read = 0")
    suspend fun getUnreadCount(agentId: String): Int

    @Query("UPDATE agent_messages SET is_read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE agent_messages SET is_read = 1 WHERE to_agent_id = :agentId")
    suspend fun markAllRead(agentId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentMessageEntity)

    @Query("DELETE FROM agent_messages WHERE created_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT * FROM agent_messages WHERE to_agent_id = :agentId AND is_read = 0 ORDER BY created_at DESC")
    fun observeUnread(agentId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_messages")
    suspend fun getAll(): List<AgentMessageEntity>

    @Query("DELETE FROM agent_messages")
    suspend fun deleteAll()
}
