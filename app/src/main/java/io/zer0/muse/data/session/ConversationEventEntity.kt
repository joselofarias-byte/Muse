package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Agregar eventos de sombra para enlaces de diálogo.
 *
 * Los eventos se utilizan para auditoría, deduplicación y diagnóstico de reproducción y no reemplazan la tabla de mensajes existente. De forma predeterminada, solo se guardan fragmentos estructurados,
 * longitud y hash, para evitar copiar cada delta de transmisión a la base de datos.
 */
@Entity(
    tableName = "conversation_events",
    primaryKeys = ["sessionId", "eventSeq"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "turnId", "eventSeq"], name = "idx_conversation_events_session_turn_seq"),
        Index(value = ["turnId", "streamId"], name = "idx_conversation_events_turn_stream"),
        Index(value = ["eventId"], unique = true, name = "idx_conversation_events_event_id"),
    ],
)
@kotlinx.serialization.Serializable
data class ConversationEventEntity(
    val sessionId: String,
    val eventSeq: Long,
    val eventId: String,
    val turnId: String,
    @ColumnInfo(defaultValue = "NULL") val streamId: String? = null,
    val type: String,
    val payloadJson: String,
    val payloadHash: String,
    val payloadLength: Int,
    @ColumnInfo(defaultValue = "NULL") val provider: String? = null,
    @ColumnInfo(defaultValue = "NULL") val modelId: String? = null,
    @ColumnInfo(defaultValue = "0") val sequenceInStream: Long = 0,
    @ColumnInfo(defaultValue = "0") val generationSerial: Long = 0,
    val createdAt: Long,
)

@Dao
interface ConversationEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ConversationEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<ConversationEventEntity>): List<Long>

    @Query("SELECT COALESCE(MAX(eventSeq), 0) + 1 FROM conversation_events WHERE sessionId = :sessionId")
    suspend fun nextEventSeq(sessionId: String): Long

    @Query("SELECT * FROM conversation_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: String): ConversationEventEntity?

    @Query("SELECT * FROM conversation_events WHERE turnId = :turnId ORDER BY eventSeq ASC")
    suspend fun getByTurn(turnId: String): List<ConversationEventEntity>

    @Query("SELECT * FROM conversation_events WHERE sessionId = :sessionId ORDER BY eventSeq ASC LIMIT :limit")
    suspend fun getBySession(sessionId: String, limit: Int): List<ConversationEventEntity>

    @Query("SELECT COUNT(*) FROM conversation_events")
    suspend fun count(): Int

    @Query("DELETE FROM conversation_events WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** P0-10: Para copia de seguridad y exportación — Leer completo. */
    @Query("SELECT * FROM conversation_events")
    suspend fun getAll(): List<ConversationEventEntity>

    /** P0-10: Para respaldo y recuperación — Borrar todo. */
    @Query("DELETE FROM conversation_events")
    suspend fun deleteAll()
}
