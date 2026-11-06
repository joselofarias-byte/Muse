package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.107 Registro de verificación de integridad de la base de datos.
 *
 * Ejecutado en cada inicio PRAGMA integrity_check,Registre los resultados en esta tabla.
 *  - status: "ok" / "error"
 *  - details: Comprobar salida(ok el tiempo es "ok",error es el mensaje de error completo)
 *  - dbSizeBytes: Tamaño del archivo de base de datos al inicio(Se utiliza para rastrear las tendencias de crecimiento.)
 *  - checkedAt: Comprobar marca de tiempo
 *
 * Si se encuentra error,UI La capa puede solicitar al usuario que restaure desde una copia de seguridad automática.
 */
@Entity(tableName = "db_integrity_log")
data class DbIntegrityLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "ok") val status: String = "ok",
    @ColumnInfo(defaultValue = "") val details: String = "",
    @ColumnInfo(defaultValue = "0") val dbSizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val checkedAt: Long = System.currentTimeMillis(),
)
