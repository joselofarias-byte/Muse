package io.zer0.muse.data.artifact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.Serializable

/**
 * artefacto de conversación(Artifact)entidad.
 *
 * Fragmentos independientes de contenido extraídos de respuestas de IA(código/Documentación/HTML/SVG/Imágenes, etc.),
 * Insertar conversaciones como tarjetas,Evite desplazarse por artículos largos.
 */
@Entity(
    tableName = "artifacts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("messageId")],
)
@Serializable
data class ArtifactEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String,
    val title: String,
    val type: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    val language: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
