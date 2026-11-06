package io.zer0.muse.data.groupchat

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
 * B5-02: El chat grupal genera un libro de contabilidad.
 *
 * Se escribe un registro cada vez que se inicia la rotación del chat grupal, registrando el modo actual, la ronda, el subíndice de miembros y la lista ordenada de miembros;
 * Una vez finalizado el proceso, la lógica de recuperación de inicio continúa desde el punto de interrupción según el libro mayor en lugar de volver a activar a todos los miembros.
 */
@Entity(
    tableName = "group_chat_generation_ledger",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("chatId"),
        Index("status"),
    ],
)
data class GroupChatGenerationLedgerEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val mode: String,
    val round: Int,
    val memberIndex: Int,
    @ColumnInfo(defaultValue = "[]") val memberIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "running") val status: String = "running",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

@Dao
interface GroupChatGenerationLedgerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: GroupChatGenerationLedgerEntity)

    @Query("DELETE FROM group_chat_generation_ledger WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM group_chat_generation_ledger WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: String)

    @Query("SELECT * FROM group_chat_generation_ledger WHERE id = :id")
    suspend fun getById(id: String): GroupChatGenerationLedgerEntity?

    @Query("SELECT * FROM group_chat_generation_ledger WHERE status != 'completed' ORDER BY updatedAt ASC")
    suspend fun getPending(): List<GroupChatGenerationLedgerEntity>
}
