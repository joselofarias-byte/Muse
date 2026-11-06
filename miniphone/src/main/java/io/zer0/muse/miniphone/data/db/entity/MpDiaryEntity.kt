package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * El diario de un día.[date] para `yyyy-MM-dd` clave primaria de texto,Una pieza al día.
 */
@Entity(tableName = "mp_diary")
data class MpDiaryEntity(
    @PrimaryKey val date: String,
    val content: String,
    val updatedAt: Long,
)
