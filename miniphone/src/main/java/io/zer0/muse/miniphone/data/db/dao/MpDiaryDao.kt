package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpDiaryEntity
import kotlinx.coroutines.flow.Flow

/** Lectura y escritura del diario.:Una pieza al día,prensa `yyyy-MM-dd` Inserción de clave primaria. */
@Dao
interface MpDiaryDao {
    @Query("SELECT * FROM mp_diary WHERE date = :date LIMIT 1")
    fun observe(date: String): Flow<MpDiaryEntity?>

    @Query("SELECT * FROM mp_diary WHERE date = :date LIMIT 1")
    suspend fun get(date: String): MpDiaryEntity?

    @Query("SELECT * FROM mp_diary ORDER BY date DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MpDiaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(diary: MpDiaryEntity)

    @Query("DELETE FROM mp_diary WHERE date = :date")
    suspend fun delete(date: String)
}
