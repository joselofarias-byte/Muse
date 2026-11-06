package io.zer0.memory.fact

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v13 (T4-1): expediente de revisión de hechos — Cambiar el historial de recuerdos clave,Admite auditoría y reversión.
 *
 * Condiciones de grabación(FactStore Determinar al escribir):
 *  - importancia ≥ 1(importante/hechos clave)actualizar y fusionar
 *  - o pulsación de tecla física(fusionarse con entidad)operación de fusión
 * hechos ordinarios(importance=0)Las actualizaciones diarias no se registran,Evite la hinchazón de la mesa.
 *
 * Propósito:
 *  - Los usuarios pueden ver el historial de evolución de una determinada memoria.
 *  - fusionar/Puede volver al valor anterior después de un error de actualización.
 *  - Al fusionar tareas de reflexión, conserve el texto original de la parte fusionada(rastreable)
 */
@Entity(
    tableName = "fact_revisions",
    indices = [
        Index(value = ["fact_id"], name = "idx_fact_revisions_fact_id"),
        Index(value = ["changed_at"], name = "idx_fact_revisions_changed_at"),
    ],
)
data class FactRevisionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** hechos modificados id(facts.id,No agregue claves externas para evitar pérdidas en cascada). */
    @ColumnInfo(name = "fact_id")
    val factId: Long,

    /** Contenido antes de la modificación(Puede ser nulo: Se agregó un nuevo escenario de fusión sin el valor anterior.). */
    @ColumnInfo(name = "old_content")
    val oldContent: String,

    /** Contenido modificado. */
    @ColumnInfo(name = "new_content")
    val newContent: String,

    /** Fecha de cambio ISO 8601. */
    @ColumnInfo(name = "changed_at")
    val changedAt: String,

    /** Razón del cambio(update/merge/reflection espera). */
    @ColumnInfo(name = "reason")
    val reason: String,
)
