package io.zer0.memory.summary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Progreso diario de la compilación.
 *
 * para recuperación de accidentes: Realice el pedido inmediatamente después de cada paso exitoso,La próxima vez que falle y reinicie, no podrá volver a ejecutarlo.
 * context key = logicalDate + resetAt + factsMode,Cualquier cambio restablece el progreso.
 *
 * Phase 7: @Serializable para exportación de respaldo/Importar.
 */
@Serializable
@Entity(tableName = "daily_state")
data class DailyStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String = "default",

    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int = 2,

    /** fecha lógica(YYYY-MM-DD). */
    @ColumnInfo(name = "logical_date")
    val logicalDate: String,

    /** Marca de agua de restablecimiento de memoria(ISO),null Indica no restablecer. */
    @ColumnInfo(name = "reset_at")
    val resetAt: String? = null,

    /** facts modo: legacy(usar facts.md) / editable(usar editable-facts.md). */
    @ColumnInfo(name = "facts_mode")
    val factsMode: String = "legacy",

    /** Pasos completados JSON: {"compileDaily":"ISO","compileToday":"ISO",...}. */
    @ColumnInfo(name = "completed_steps")
    val completedSteps: String = "{}",

    /** El momento en que todo está hecho(ISO),null Significa que no he terminado de correr en todo ese día. */
    @ColumnInfo(name = "daily_completed_at")
    val dailyCompletedAt: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
