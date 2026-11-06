package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * Compilar productos aislados por alcance + espacio.
 * Las secciones_compiladas antiguas se conservan como respaldo compatible con la actualización y no se eliminan en las migraciones.
 */
@Serializable
@Entity(
    tableName = "compiled_sections_scoped",
    primaryKeys = ["section_key", "scope", "space_id"],
)
data class ScopedCompiledSectionEntity(
    @ColumnInfo(name = "section_key")
    val sectionKey: String,
    @ColumnInfo(name = "scope")
    val scope: String = "main",
    @ColumnInfo(name = "space_id")
    val spaceId: String = "default",
    @ColumnInfo(name = "content")
    val content: String = "",
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
