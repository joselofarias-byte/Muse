package io.zer0.muse.miniphone.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpActorType

/**
 * Comentarios dinámicos.
 *
 * [parentId] Vacío significa comentar directamente la noticia.;Si no está vacío, significa responder a otro comentario bajo la misma actualización.
 */
@Entity(
    tableName = "mp_moment_comment",
    indices = [Index("momentId"), Index("parentId")],
)
data class MpMomentCommentEntity(
    @PrimaryKey val id: String,
    val momentId: String,
    val parentId: String? = null,
    val senderType: MpActorType,
    val senderId: String,
    val senderName: String,
    val content: String,
    val createdAt: Long,
)
