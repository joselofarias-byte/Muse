package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Compilar sección de producto(memory Archivo .md en el directorio → Room).
 *
 * 4 fijo section: facts / today / week / longterm.
 * assemble Al empalmar en Memory.md, inyecte el mensaje del sistema.
 *
 * Phase 7: @Serializable para exportación de respaldo/Importar.
 */
@Serializable
@Entity(tableName = "compiled_sections")
data class CompiledSectionEntity(
    @PrimaryKey
    @ColumnInfo(name = "section_key")
    val sectionKey: String, // facts / today / week / longterm

    @ColumnInfo(name = "content")
    val content: String = "",

    @ColumnInfo(name = "fingerprint")
    val fingerprint: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
