package io.zer0.muse.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Phase 9.1 (M13): Entidad de carpeta de sesión.
 *
 * Se utiliza para agrupar conversaciones de la barra lateral en carpetas.
 * - [id] clave primaria,cuerda UUID
 * - [name] nombre de la carpeta(Editable por el usuario)
 * - [sortIndex] índice de clasificación(El más joven está arriba.)
 * - [createdAt] / [updatedAt] Marca de tiempo
 * - [expanded] Estado expandido de la barra lateral(persistencia local,Persistencia entre sesiones)
 *
 * sesión pasada [SessionEntity.folderId] Carpetas asociadas(null = No agrupado,Mostrado en"No agrupado"Distrito).
 */
@Serializable
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "1") val expanded: Boolean = true,
    /** v1.107 Redundancia: Número de sesiones en la carpeta(Evite las páginas de lista COUNT,Repository Mantenimiento de doble escritura). */
    @ColumnInfo(defaultValue = "0") val sessionCount: Int = 0,
)
