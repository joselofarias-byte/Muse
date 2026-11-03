package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import kotlinx.coroutines.flow.Flow

/** Lectura y escritura de líneas de tiempo dinámicas. */
@Dao
interface MpMomentDao {
    /** Pasar las páginas en orden inverso(nuevo → viejo):[offset] = Número de artículos cargados. */
    @Query("SELECT * FROM mp_moment ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun pageTimeline(limit: Int, offset: Int): List<MpMomentEntity>

    /** Siempre que una página de noticias de un determinado autor(nuevo → viejo):\"Mis Momentos\" lo usa para bloquear las actualizaciones de otras personas en la capa de consulta. */
    @Query(
        "SELECT * FROM mp_moment WHERE authorId = :authorId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun pageTimelineByAuthor(authorId: String, limit: Int, offset: Int): List<MpMomentEntity>

    @Query("SELECT * FROM mp_moment ORDER BY createdAt DESC, id DESC")
    fun observeTimeline(): Flow<List<MpMomentEntity>>

    /** Observa únicamente la dinámica de un determinado personaje.(Para la página de inicio del personaje). */
    @Query("SELECT * FROM mp_moment WHERE authorId = :authorId ORDER BY createdAt DESC, id DESC")
    fun observeByAuthor(authorId: String): Flow<List<MpMomentEntity>>

    @Query("SELECT * FROM mp_moment WHERE id = :momentId LIMIT 1")
    suspend fun getMoment(momentId: String): MpMomentEntity?

    /** Una publicación reciente de un personaje.;Si no ha sido enviado es nulo. */
    @Query("SELECT * FROM mp_moment WHERE authorId = :authorId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun lastMomentByAuthor(authorId: String): MpMomentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(moment: MpMomentEntity)

    @Query("DELETE FROM mp_moment WHERE id = :momentId")
    suspend fun delete(momentId: String)

    @Query("SELECT COUNT(*) FROM mp_moment")
    suspend fun count(): Int
}
