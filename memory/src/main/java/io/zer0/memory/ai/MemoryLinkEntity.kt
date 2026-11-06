package io.zer0.memory.ai

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * v1.0.52 P2-3: Memorización de entidades de borde del gráfico de conocimiento.
 *
 * expresar la relación entre dos hechos(como causa y efecto/contiene/contradicción),Gráfico de conocimiento para desarrollar la memoria del usuario.
 * con [io.zer0.memory.fact.FactEntity] Asociación a través de source_fact_id / target_fact_id.
 *
 * v10 schema: Se agregó la tabla de enlaces de memoria.
 *
 * diseño:
 *  - source_fact_id / target_fact_id: señalar a facts.id(clave externa,Pero sin restricciones de CLAVE EXTRANJERA,
 *    Evite la pérdida de rendimiento de las eliminaciones en cascada entre tablas;por capa de aplicación [MemoryLinkDao.deleteByFactId] Limpiar al eliminar hechos)
 *  - link_type: Tipo de relación(causes/explains/part_of/related_to/contradicts)
 *  - weight: fuerza de la relación 0.0~1.0
 *  - space_id / scope: Alinear con la tabla de hechos,Admite aislamiento multiespacio + multiagente
 *
 * source_title / target_title es un campo redundante,Se utiliza para seguir mostrando la semántica relacional después de eliminar los hechos.
 * (Evite que las relaciones se conviertan en números sin sentido después del fracaso de JOIN id).
 */
@Serializable
@Entity(
    tableName = "memory_links",
    indices = [
        Index(value = ["source_fact_id"], name = "idx_memory_links_source"),
        Index(value = ["target_fact_id"], name = "idx_memory_links_target"),
        Index(value = ["space_id"], name = "idx_memory_links_space"),
        Index(value = ["scope"], name = "idx_memory_links_scope"),
    ],
)
data class MemoryLinkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** hechos fuente id(señalar a facts.id). */
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: Long,

    /** hecho objetivo id(señalar a facts.id). */
    @ColumnInfo(name = "target_fact_id")
    val targetFactId: Long,

    /** título del hecho fuente(Redundancia,La semántica relacional aún se puede mostrar después de eliminar hechos). */
    @ColumnInfo(name = "source_title")
    val sourceTitle: String,

    /** título del hecho objetivo(Redundancia). */
    @ColumnInfo(name = "target_title")
    val targetTitle: String,

    /** Tipo de relación:causes / explains / part_of / related_to / contradicts. */
    @ColumnInfo(name = "link_type", defaultValue = "related_to")
    val linkType: String = "related_to",

    /** fuerza de la relación 0.0~1.0(Predeterminado 0.5). */
    @ColumnInfo(name = "weight", defaultValue = "0.5")
    val weight: Float = 0.5f,

    /** espacio de memoria id(Alinear con hechos.space_id,Aislamiento de múltiples espacios). */
    @ColumnInfo(name = "space_id", defaultValue = "default")
    val spaceId: String = "default",

    /** alcance de la memoria(Alinear con hechos.alcance,Aislamiento de múltiples agentes). */
    @ColumnInfo(name = "scope", defaultValue = "main")
    val scope: String = "main",

    /** Creado ISO 8601. */
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)
