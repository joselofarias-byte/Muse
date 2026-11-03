package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpMomentCommentEntity
import kotlinx.coroutines.flow.Flow

/** Lectura y escritura de comentarios dinámicos. */
@Dao
interface MpMomentCommentDao {
    @Query("SELECT * FROM mp_moment_comment WHERE momentId = :momentId ORDER BY createdAt ASC, id ASC")
    fun observeComments(momentId: String): Flow<List<MpMomentCommentEntity>>

    /** Obtener todos los comentarios de una publicación a la vez:No es necesario abrir un flujo de observación cuando solo necesita leerlo una vez. */
    @Query("SELECT * FROM mp_moment_comment WHERE momentId = :momentId ORDER BY createdAt ASC, id ASC")
    suspend fun commentsOf(momentId: String): List<MpMomentCommentEntity>

    /** Un lote de comentarios dinámicos.:Solo puedes consultar este tiempo en una página de la línea de tiempo.,Luego, agrúpelos dinámicamente en la memoria. */
    @Query("SELECT * FROM mp_moment_comment WHERE momentId IN (:momentIds) ORDER BY createdAt ASC, id ASC")
    suspend fun commentsOfMomentIds(momentIds: List<String>): List<MpMomentCommentEntity>

    @Query("SELECT COUNT(*) FROM mp_moment_comment WHERE momentId = :momentId")
    suspend fun count(momentId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comment: MpMomentCommentEntity)

    @Query("DELETE FROM mp_moment_comment WHERE id = :commentId")
    suspend fun delete(commentId: String)

    /** Eliminar un lote a la vez:Al eliminar un comentario,Las respuestas que cuelgan debajo también deberían seguir. */
    @Query("DELETE FROM mp_moment_comment WHERE id IN (:commentIds)")
    suspend fun deleteByIds(commentIds: List<String>)

    @Query("DELETE FROM mp_moment_comment WHERE momentId = :momentId")
    suspend fun deleteAllOf(momentId: String)
}
