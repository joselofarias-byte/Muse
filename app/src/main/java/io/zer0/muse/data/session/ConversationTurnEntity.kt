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

/**
 * Estado de giro de construcción persistente.
 *
 * turnId Es finalizar idempotente; Los eventos del antiguo StreamId o del antiguo GenerationSerial no deben anular la nueva ronda.
 */
@Entity(
    tableName = "conversation_turns",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId", "startedAt"], name = "idx_conversation_turns_session_started"),
        Index(value = ["sessionId", "phase"], name = "idx_conversation_turns_session_phase"),
        Index(value = ["assistantMessageId"], name = "idx_conversation_turns_assistant"),
    ],
)
@kotlinx.serialization.Serializable
data class ConversationTurnEntity(
    @PrimaryKey val turnId: String,
    val sessionId: String,
    val inputUserMessageId: String,
    val assistantMessageId: String,
    val phase: String,
    @ColumnInfo(defaultValue = "NULL") val streamId: String? = null,
    @ColumnInfo(defaultValue = "0") val generationSerial: Long = 0,
    val startedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val finishedAt: Long? = null,
    val updatedAt: Long,
)

@Dao
interface ConversationTurnDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(turn: ConversationTurnEntity)

    @Query("SELECT * FROM conversation_turns WHERE turnId = :turnId LIMIT 1")
    suspend fun getById(turnId: String): ConversationTurnEntity?

    @Query("SELECT * FROM conversation_turns WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    suspend fun getBySession(sessionId: String): List<ConversationTurnEntity>

    @Query("SELECT * FROM conversation_turns WHERE sessionId = :sessionId AND finishedAt IS NULL ORDER BY startedAt ASC")
    suspend fun getPendingBySession(sessionId: String): List<ConversationTurnEntity>

    @Query("UPDATE conversation_turns SET phase = :phase, updatedAt = :updatedAt WHERE turnId = :turnId")
    suspend fun updatePhase(turnId: String, phase: String, updatedAt: Long)

    @Query(
        "UPDATE conversation_turns SET phase = :phase, finishedAt = :finishedAt, updatedAt = :finishedAt WHERE turnId = :turnId AND finishedAt IS NULL",
    )
    suspend fun finishIfOpen(turnId: String, phase: String, finishedAt: Long): Int

    /**
     * A9: Deje el proceso colgado cuando el proceso finalice, gire (`finishedAt IS NULL`) converge al estado final de inmediato.
     *
     * Una sola declaración SQL completa toda la lectura del lote+En cambio, la idempotencia proviene de escribir la condición misma:`finishedAt IS NULL` Es una condición de filtro.
     * La condición previa también se actualiza y la segunda ejecución coincide con 0 filas. Ya `finishedAt` Las filas nunca se modifican y no se revertirán
     * phase O anular el tiempo de finalización.`updatedAt` con `finishedAt` Tómese el mismo tiempo que [finishIfOpen] coherente.
     *
     * @param startedBefore solo convergencia `startedAt` Las líneas anteriores a este momento dejan una ventana para el giro actual de este proceso.
     * @return El número de filas convergió esta vez.
     */
    @Query(
        "UPDATE conversation_turns SET phase = :phase, finishedAt = :finishedAt, updatedAt = :finishedAt " +
            "WHERE finishedAt IS NULL AND startedAt < :startedBefore",
    )
    suspend fun closeStaleOpenTurns(phase: String, startedBefore: Long, finishedAt: Long): Int

    @Query("DELETE FROM conversation_turns WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** P0-10: Exportación de copia de seguridad/para la recuperación — Leer completo. */
    @Query("SELECT * FROM conversation_turns")
    suspend fun getAll(): List<ConversationTurnEntity>

    /** P0-10: Para respaldo y recuperación — Borrar todo. */
    @Query("DELETE FROM conversation_turns")
    suspend fun deleteAll()
}
