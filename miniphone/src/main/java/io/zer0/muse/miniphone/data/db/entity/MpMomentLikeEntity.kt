package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import io.zer0.muse.miniphone.data.db.MpActorType

/**
 * Me gusta dinámicos. Una misma persona sólo puede hacer clic en la misma publicación una vez,Por lo tanto, se utiliza una clave primaria compuesta de tres columnas.
 */
@Entity(
    tableName = "mp_moment_like",
    primaryKeys = ["momentId", "likerType", "likerId"],
    indices = [Index("momentId")],
)
data class MpMomentLikeEntity(
    val momentId: String,
    val likerType: MpActorType,
    val likerId: String,
    val likerName: String,
    val createdAt: Long,
)
