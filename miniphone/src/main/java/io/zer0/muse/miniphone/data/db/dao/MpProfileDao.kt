package io.zer0.muse.miniphone.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.zer0.muse.miniphone.data.db.entity.MpProfileEntity
import kotlinx.coroutines.flow.Flow

/** Lectura y escritura de datos de usuarios locales.(Toda la biblioteca en una sola línea). */
@Dao
interface MpProfileDao {
    @Query("SELECT * FROM mp_profile LIMIT 1")
    fun observeProfile(): Flow<MpProfileEntity?>

    @Query("SELECT * FROM mp_profile LIMIT 1")
    suspend fun getProfile(): MpProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: MpProfileEntity)
}
