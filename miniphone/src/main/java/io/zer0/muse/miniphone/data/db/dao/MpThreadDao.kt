package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import kotlinx.coroutines.flow.Flow

/** Lectura y redacción de listas de sesiones. */
@Dao
interface MpThreadDao {
    /** traer al frente,El resto está en orden descendente desde el último mensaje. */
    @Query("SELECT * FROM mp_thread ORDER BY pinned DESC, lastMessageAt DESC, id ASC")
    fun observeThreads(): Flow<List<MpThreadEntity>>

    @Query("SELECT * FROM mp_thread WHERE id = :threadId LIMIT 1")
    suspend fun getThread(threadId: String): MpThreadEntity?

    /**
     * Buscar conversaciones por rol:Cuando haga clic en una persona en la libreta de direcciones, utilícela para determinar si "ya hay uno a uno" o "necesita abrir uno nuevo".
     *
     * Solo debe haber una sesión para el mismo rol.,Si hay varias entradas, se utilizará la primera creada.(orden ascendente de clave primaria),No dejes que el clic caiga en uno al azar.
     * El ID de carácter de la sesión grupal también pertenece a un carácter determinado. id(primer miembro),Pero no es "el mensaje privado de esta persona".,
     * Entonces los grupos deben ser excluidos aquí.:En caso contrario, haga clic en alguien que haya sido líder de grupo.,Caerás directamente en ese grupo.
     */
    @Query("SELECT * FROM mp_thread WHERE characterId = :characterId AND isGroup = 0 ORDER BY id ASC LIMIT 1")
    suspend fun findThreadByCharacter(characterId: String): MpThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: MpThreadEntity)

    @Query("UPDATE mp_thread SET lastMessagePreview = :preview, lastMessageAt = :at WHERE id = :threadId")
    suspend fun updateLastMessage(threadId: String, preview: String?, at: Long)

    /** Cambiar nombre de grupo,También escriba \"¿El usuario lo cambió él mismo esta vez?\":[customized] Después de configurarlo en 1, no se volverá a calcular automáticamente. */
    @Query("UPDATE mp_thread SET title = :title, titleCustomized = :customized WHERE id = :threadId")
    suspend fun updateTitle(threadId: String, title: String, customized: Boolean)

    /** Cámbialo a \"el personaje que usas para encontrar a alguien con quien hablar\".:Después de que se elimine a la primera persona que puede hablar en el grupo.,Para ser reemplazado por uno nuevo. */
    @Query("UPDATE mp_thread SET characterId = :characterId WHERE id = :threadId")
    suspend fun updateCharacter(threadId: String, characterId: String)

    @Query("SELECT unreadCount FROM mp_thread WHERE id = :threadId")
    suspend fun unreadCount(threadId: String): Int?

    @Query("UPDATE mp_thread SET unreadCount = unreadCount + 1 WHERE id = :threadId")
    suspend fun increaseUnread(threadId: String)

    @Query("UPDATE mp_thread SET unreadCount = :count WHERE id = :threadId")
    suspend fun setUnread(threadId: String, count: Int)

    @Query("UPDATE mp_thread SET unreadCount = 0 WHERE id = :threadId")
    suspend fun clearUnread(threadId: String)

    /** fijar hacia arriba/Desprender. La clasificación de listas lee esta columna. */
    @Query("UPDATE mp_thread SET pinned = :pinned WHERE id = :threadId")
    suspend fun setPinned(threadId: String, pinned: Boolean)

    @Query("DELETE FROM mp_thread WHERE id = :threadId")
    suspend fun delete(threadId: String)

    /** Borre todas las líneas de sesión. La tabla de mensajes debe borrarse juntos.,por [io.zer0.muse.miniphone.data.chat.MpConversationStore] Asuntos unificados. */
    @Query("DELETE FROM mp_thread")
    suspend fun deleteAll()
}
