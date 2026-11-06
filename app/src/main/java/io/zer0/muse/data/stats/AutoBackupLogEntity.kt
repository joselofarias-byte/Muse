package io.zer0.muse.data.stats

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * v1.107 Realizar copias de seguridad de los registros automáticamente.
 *
 * Registre los resultados de cada copia de seguridad automática.,Se utiliza para ver y recuperar el historial de copias de seguridad.
 *  - backupPath: Ruta absoluta del archivo de copia de seguridad
 *  - fileSizeBytes: Tamaño del archivo de respaldo
 *  - status: "success" / "failed"
 *  - errorMessage: Mensaje de error en caso de falla
 *  - messageCount: Número total de mensajes durante la copia de seguridad(Se utiliza para realizar un seguimiento del crecimiento de los datos.)
 *  - createdAt: Marca de tiempo de respaldo
 *
 * Estrategia de respaldo(WorkManager Tareas periódicas):
 *  - Frecuencia: 1 vez por día(Solo replicar cuando realmente haya un mensaje nuevo)
 *  - Reservar: últimos 7,Limpia automáticamente los más antiguos
 *  - camino: app Directorio/copias de seguridad de almacenamiento interno
 */
@Entity(tableName = "auto_backup_log")
data class AutoBackupLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "") val backupPath: String = "",
    @ColumnInfo(defaultValue = "0") val fileSizeBytes: Long = 0,
    @ColumnInfo(defaultValue = "success") val status: String = "success",
    @ColumnInfo(defaultValue = "") val errorMessage: String = "",
    @ColumnInfo(defaultValue = "0") val messageCount: Long = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
)
