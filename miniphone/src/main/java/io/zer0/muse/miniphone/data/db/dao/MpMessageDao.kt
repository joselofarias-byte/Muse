package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.MpMessageStatus
import io.zer0.muse.miniphone.data.db.entity.MpMessageEntity
import kotlinx.coroutines.flow.Flow

/** Lectura y escritura de mensajes en una sola sesión. */
@Dao
interface MpMessageDao {
    /** Secuencia de mensajes dentro de la sesión(viejo → nuevo),Para que la interfaz se renderice directamente. */
    @Query("SELECT * FROM mp_message WHERE threadId = :threadId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(threadId: String): Flow<List<MpMessageEntity>>

    /** Pasar las páginas en orden inverso(nuevo → viejo):[offset] = Número de artículos cargados. */
    @Query(
        "SELECT * FROM mp_message WHERE threadId = :threadId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageMessages(threadId: String, limit: Int, offset: Int): List<MpMessageEntity>

    @Query("SELECT * FROM mp_message WHERE id = :messageId LIMIT 1")
    suspend fun getMessage(messageId: String): MpMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MpMessageEntity)

    /** Cambiar el estado de envío de un mensaje;Esto también se utiliza para volver a \"Enviar\" durante el reenvío. */
    @Query("UPDATE mp_message SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: MpMessageStatus)

    /** Eliminar uno localmente;La sesión no funciona,El texto y la hora de la vista previa permanecen como estaban antes de la eliminación. */
    @Query("DELETE FROM mp_message WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT COUNT(*) FROM mp_message WHERE threadId = :threadId")
    suspend fun countInThread(threadId: String): Int

    /** La hora del último mensaje enviado por un remitente en esta conversación.;Si no, nulo.`sender` pasar [io.zer0.muse.miniphone.data.db.MpActorType.value]. */
    @Query("SELECT MAX(createdAt) FROM mp_message WHERE threadId = :threadId AND sender = :sender")
    suspend fun lastMessageAt(threadId: String, sender: String): Long?

    @Query("DELETE FROM mp_message WHERE threadId = :threadId")
    suspend fun deleteInThread(threadId: String)

    /** Borrar todas las líneas de mensajes(La persona que llama también elimina las líneas de sesión). */
    @Query("DELETE FROM mp_message")
    suspend fun deleteAll()
}
