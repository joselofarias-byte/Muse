package io.zer0.muse.data.groupchat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Objeto de acceso a datos de chat grupal.
 *
 * Responsable de CRUD + Observación de flujo de entidades de chat grupal,y consultas de agregación entre tablas
 * (Vista previa de las últimas noticias, recuento de mensajes),Para uso en páginas de lista de chat grupal.
 */
@Dao
interface GroupChatDao {

    /** Mira todos los chats grupales(traer al frente,Ordenar por actualizado en orden descendente con estado fijado,Excluir archivo) */
    @Query("SELECT * FROM group_chats WHERE isArchived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<GroupChatEntity>>

    /** Observar chats grupales archivados */
    @Query("SELECT * FROM group_chats WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<GroupChatEntity>>

    /** Observar un chat de un solo grupo(Se utiliza para actualizar metadatos en tiempo real en la página de detalles). */
    @Query("SELECT * FROM group_chats WHERE id = :id")
    fun observeById(id: String): Flow<GroupChatEntity?>

    /** Obtenga un chat grupal único por identificación(Desechable). */
    @Query("SELECT * FROM group_chats WHERE id = :id")
    suspend fun getById(id: String): GroupChatEntity?

    /** Insertar o actualizar un chat grupal(Reemplazar en caso de conflicto). */
    @Upsert
    suspend fun upsert(chat: GroupChatEntity)

    /** Insertar nuevo chat grupal(Ignorar en caso de conflicto). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(chat: GroupChatEntity)

    /** Eliminar chat grupal por identificación. */
    @Query("DELETE FROM group_chats WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Actualice la marca de tiempo actualizada en el chat grupal(Llaman cada vez que hay un mensaje nuevo.). */
    @Query("UPDATE group_chats SET updatedAt = :timestamp WHERE id = :id")
    suspend fun touchUpdatedAt(id: String, timestamp: Long = System.currentTimeMillis())

    // ── Consulta de agregación entre tablas(Consulta la tabla group_chat_messages)──

    /** Obtenga el último mensaje del chat grupal especificado(Para obtener una vista previa de la página de lista). */
    @Query("SELECT * FROM group_chat_messages WHERE chatId = :chatId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(chatId: String): GroupChatMessageEntity?

    /** Cuente la cantidad de mensajes en el chat grupal especificado. */
    @Query("SELECT COUNT(*) FROM group_chat_messages WHERE chatId = :chatId")
    suspend fun countMessages(chatId: String): Int

    // ── v1.107 Método de mantenimiento de campo redundante ──

    /** v1.107: Actualización atómica de campos redundantes en el chat grupal.(Vista previa del último mensaje + recuento + tiempo de la última actividad). */
    @Query(
        "UPDATE group_chats SET lastMessagePreview = :preview, messageCount = messageCount + :delta, lastActivityAt = :timestamp, updatedAt = :timestamp WHERE id = :id",
    )
    suspend fun updateLastMessageAndCount(id: String, preview: String, delta: Int, timestamp: Long)

    @Query("SELECT * FROM group_chats")
    suspend fun getAll(): List<GroupChatEntity>

    @Query("DELETE FROM group_chats")
    suspend fun deleteAll()

    /** v2.x: Actualizar el estado del archivo del chat grupal */
    @Query("UPDATE group_chats SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)
}
