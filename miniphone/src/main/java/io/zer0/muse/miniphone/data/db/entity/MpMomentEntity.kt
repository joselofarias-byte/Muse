package io.zer0.muse.miniphone.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.zer0.muse.miniphone.data.db.MpMomentSource

/**
 * Una actualización.
 *
 * [imagesJson] es el texto JSON de la matriz uri de la imagen(La imagen vacía es `[]`),La decodificación se realiza a nivel de almacén.,La capa de base de datos no se analiza.
 *
 * [visibleToJson] es el rango visible:Lista separada por comas de objetos visibles id,cadena vacía = Visible para todos.
 * En esta etapa sólo se requiere escribir, no leer. —— La consulta de línea de tiempo no se filtra,Las actualizaciones que son "visibles sólo para usted" seguirán apareciendo en su propia lista.,
 * No lo consideres como si estuvieras en cuarentena.
 */
@Entity(
    tableName = "mp_moment",
    indices = [Index("createdAt"), Index("authorId")],
)
data class MpMomentEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorName: String,
    val content: String,
    val imagesJson: String = "[]",
    @ColumnInfo(defaultValue = "") val visibleToJson: String = "",
    val createdAt: Long,
    val source: MpMomentSource,
)
