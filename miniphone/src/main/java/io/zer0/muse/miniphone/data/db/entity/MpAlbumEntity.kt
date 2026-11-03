package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpAlbumSource

/**
 * Una imagen de un álbum de fotos.
 *
 * [hidden] esconderse(no en la pared), [favorite] para colección.
 */
@Entity(
    tableName = "mp_album",
    indices = [Index("createdAt"), Index("source")],
)
data class MpAlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val uri: String,
    val source: MpAlbumSource,
    val hidden: Boolean = false,
    val favorite: Boolean = false,
    val createdAt: Long,
)
