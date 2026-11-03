package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Rueda de herramientas persistente, que conserva la antigua herramientaCallInfoJson como campo de compatibilidad. */
@kotlinx.serialization.Serializable
@Entity(
    tableName = "tool_rounds",
    foreignKeys = [
        ForeignKey(
            entity = ConversationTurnEntity::class,
            parentColumns = ["turnId"],
            childColumns = ["turnId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["turnId", "roundIndex"], name = "idx_tool_rounds_turn_round"),
        Index(value = ["toolCallId"], name = "idx_tool_rounds_call"),
    ],
)
data class ToolRoundEntity(
    @PrimaryKey val id: String,
    val turnId: String,
    val roundIndex: Int,
    val toolCallId: String,
    val toolName: String,
    val argsJson: String,
    @ColumnInfo(defaultValue = "NULL") val resultJson: String? = null,
    val status: String,
    val startedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val finishedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val errorDetail: String? = null,
)

@Dao
interface ToolRoundDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(round: ToolRoundEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rounds: List<ToolRoundEntity>)

    @Query("SELECT * FROM tool_rounds WHERE turnId = :turnId ORDER BY roundIndex ASC, startedAt ASC")
    suspend fun getByTurn(turnId: String): List<ToolRoundEntity>

    @Query("DELETE FROM tool_rounds WHERE turnId = :turnId")
    suspend fun deleteByTurn(turnId: String)

    @Query("DELETE FROM tool_rounds WHERE turnId IN (SELECT turnId FROM conversation_turns WHERE sessionId = :sessionId)")
    suspend fun deleteBySession(sessionId: String)

    /** P0-10: Para copia de seguridad y exportación — Leer completo. */
    @Query("SELECT * FROM tool_rounds")
    suspend fun getAll(): List<ToolRoundEntity>

    /** P0-10: Para respaldo y recuperación — Borrar todo. */
    @Query("DELETE FROM tool_rounds")
    suspend fun deleteAll()
}
