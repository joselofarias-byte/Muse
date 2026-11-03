package io.zer0.memory.space

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * v1.0.52 P2-2: entidad de espacio de memoria(Aislamiento de múltiples espacios).
 *
 * Cada Espacio es un banco de memoria independiente,Los usuarios pueden utilizar diferentes escenarios.(trabajo/vida/aprender)cambiar,
 * No interfieran entre sí.facts La tabla está relacionada con la [MemorySpaceEntity].
 *
 * con [io.zer0.memory.fact.FactEntity.scope] relación:
 *  - scope: Cuarentena por agente(asistente principal "main" / Asistente de subasistenteId/miembro del equipo)
 *  - space_id: Aislar por escenario de usuario(trabajo/vida/estudio)
 *  - Ambos son ortogonales,Un hecho pertenece a algo Agent scope,también pertenece a Space
 *
 * v9 schema: Se agregó la tabla de espacios de memoria.,Asociado a la tabla de hechos.
 *
 * @param id Space solo id(Como por ejemplo "default" / "work" / "life")
 * @param name nombre para mostrar(Como por ejemplo "Predeterminado" / "trabajo" / "vida")
 * @param icon logotipo del icono(Opcional,para visualización de interfaz de usuario,Como por ejemplo "work" / "home" / "school")
 * @param description Descripción(Opcional,El usuario puede completar el propósito del espacio.)
 * @param createdAt tiempo de creación ISO 8601
 * @param sortIndex número de clasificación(Orden de espacio ajustable por el usuario,uno pequeño primero)
 */
@Serializable
@Entity(
    tableName = "memory_spaces",
    indices = [
        Index(value = ["sort_index"], name = "idx_memory_spaces_sort"),
    ],
)
data class MemorySpaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "icon")
    val icon: String? = null,

    @ColumnInfo(name = "description", defaultValue = "")
    val description: String = "",

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "sort_index", defaultValue = "0")
    val sortIndex: Int = 0,
) {
    companion object {
        /** Predeterminado Space id(Inicio por primera vez + migración de datos existentes). */
        const val DEFAULT_SPACE_ID = "default"

        /** Nombre del espacio predeterminado. */
        const val DEFAULT_SPACE_NAME = "Predeterminado"

        /** Icono de espacio predeterminado. */
        const val DEFAULT_SPACE_ICON = "bookmark"
    }
}

/**
 * Space + número de hechos relacionados(LEFT JOIN COUNT resultado).
 *
 * Se utiliza para listas de UI para mostrar la cantidad de hechos para cada espacio.,No persistente.
 */
data class MemorySpaceWithCount(
    @androidx.room.ColumnInfo(name = "id")
    val id: String,
    @androidx.room.ColumnInfo(name = "name")
    val name: String,
    @androidx.room.ColumnInfo(name = "icon")
    val icon: String?,
    @androidx.room.ColumnInfo(name = "description")
    val description: String,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: String,
    @androidx.room.ColumnInfo(name = "sort_index")
    val sortIndex: Int,
    @androidx.room.ColumnInfo(name = "fact_count")
    val factCount: Int,
)
