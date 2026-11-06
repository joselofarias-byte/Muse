package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpThreadMemberEntity
import kotlinx.coroutines.flow.Flow

/** Lectura y escritura de los miembros del chat grupal. Las conversaciones de mensajes privados no tienen filas de miembros. */
@Dao
interface MpThreadMemberDao {
    /** miembro de un grupo,Pulsa para unirte al grupo(Al crear un grupo, siga el orden de selección.). */
    @Query("SELECT * FROM mp_thread_member WHERE threadId = :threadId ORDER BY joinedAt ASC, characterId ASC")
    fun observeMembers(threadId: String): Flow<List<MpThreadMemberEntity>>

    @Query("SELECT * FROM mp_thread_member WHERE threadId = :threadId ORDER BY joinedAt ASC, characterId ASC")
    suspend fun membersOf(threadId: String): List<MpThreadMemberEntity>

    @Query("SELECT COUNT(*) FROM mp_thread_member WHERE threadId = :threadId")
    suspend fun countInThread(threadId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<MpThreadMemberEntity>)

    @Query("DELETE FROM mp_thread_member WHERE threadId = :threadId")
    suspend fun deleteInThread(threadId: String)

    /** eliminar a una persona;Los miembros restantes permanecieron inmóviles. */
    @Query("DELETE FROM mp_thread_member WHERE threadId = :threadId AND characterId = :characterId")
    suspend fun deleteMember(threadId: String, characterId: String)

    /** Borrar todas las filas de miembros(La sesión grupal es eliminada por la persona que llama.). */
    @Query("DELETE FROM mp_thread_member")
    suspend fun deleteAll()
}
