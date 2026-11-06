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
 * Mensaje enviado a la entidad de la bandeja de salida(Cola de envío persistente).
 *
 * resolver"El usuario sale justo después de hacer clic en enviar App,El proceso es cancelado por el sistema provocando la pérdida de mensajes."pregunta:
 * - [enqueueSend] escritura sincrónica outbox,Asegúrese de que el proceso aún se pueda recuperar después de ser eliminado.
 * - El lado del consumidor comenzó con éxito [launchStream] Luego elimine el registro de la bandeja de salida correspondiente.
 * - App Escanear registros residuales de la bandeja de salida al iniciar,Volver a enviar a la cola de envío
 */
@Entity(
    tableName = "message_outbox",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("createdAt"),
    ],
)
@kotlinx.serialization.Serializable
data class MessageOutboxEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val text: String,
    @ColumnInfo(defaultValue = "[]") val imageBase64Json: String = "[]",
    val userMessageId: String,
    val assistantMessageId: String,
    val createdAt: Long,
)

@Dao
interface MessageOutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageOutboxEntity)

    @Query("DELETE FROM message_outbox WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM message_outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<MessageOutboxEntity>

    @Query("SELECT * FROM message_outbox WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySessionId(sessionId: String): List<MessageOutboxEntity>

    @Query("SELECT COUNT(*) FROM message_outbox")
    suspend fun count(): Int

    /** Al eliminar temporalmente una sesión, las tareas de envío restantes se limpian sincrónicamente para evitar que la recuperación de inicio vuelva a escribir. */
    @Query("DELETE FROM message_outbox WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** P0-10: Para respaldo y recuperación — Borrar todo. */
    @Query("DELETE FROM message_outbox")
    suspend fun deleteAll()
}
