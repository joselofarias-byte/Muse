package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Session entidad resumen(JSON Archivo → Room).
 *
 * Un resumen por sesión,Pase rollingSummary() actualización continua(Cubierto,no agregar).
 * snapshot Una copia resumida del último procesamiento en memoria profunda del registro de campo.,Se utiliza para diff para extraer nuevos hechos.
 *
 * Phase 7: @Serializable para exportación de respaldo/Importar.
 */
@Serializable
@Entity(tableName = "session_summaries")
data class SessionSummaryEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    /** Texto abstracto(rolling summary,Formato de rebajas de dos secciones). */
    @ColumnInfo(name = "summary")
    val summary: String = "",

    /** Número total de mensajes cubiertos(Se utiliza para recuperar mensajes nuevos de forma incremental.). */
    @ColumnInfo(name = "message_count")
    val messageCount: Int = 0,

    /** source_time_range JSON(contiene start/end/timezone/localDates). */
    @ColumnInfo(name = "source_time_range")
    val sourceTimeRange: String? = null,

    /** Una copia del resumen de la última vez que se procesó la memoria profunda.(diff línea de base). */
    @ColumnInfo(name = "snapshot")
    val snapshot: String = "",

    /** Última hora de procesamiento de memoria profunda. */
    @ColumnInfo(name = "snapshot_at")
    val snapshotAt: String? = null,

    /** ID del asistente perteneciente (v0.22: para aislamiento de Facts.db por asistente). Vacío significa no asociado. */
    @ColumnInfo(name = "assistant_id")
    val assistantId: String = "",

    /** v2: El espacio de memoria al que pertenece; Los resúmenes antiguos son compatibles con los predeterminados. */
    @ColumnInfo(name = "space_id", defaultValue = "default")
    val spaceId: String = "default",
)
