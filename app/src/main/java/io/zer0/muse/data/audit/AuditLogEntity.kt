package io.zer0.muse.data.audit

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P2-4 Entidad de registro de auditoría.
 *
 * Registrar operaciones clave dentro de la aplicación.(API Llamada/Acción del usuario/Autenticación/Sistema)Se utiliza para auditorías de seguridad y seguimiento de problemas.
 *  - [category]: "api_call" / "user_action" / "auth" / "system"
 *  - [action]: nombre de acción específica,Como por ejemplo "send_message" / "delete_chat" / "login"
 *  - [target]: Identificador de objetivo de operación(chatId / providerId espera,Disponible)
 *  - [detail]: JSON cuerda,Almacenar campos adicionales(model / tokens / latency_ms espera)
 *  - [success]: ¿La operación es exitosa?(Registrar fallos para una fácil resolución de problemas)
 *
 * La estructura de la tabla solo agrega,No modifique las tablas existentes. La estrategia de almacenamiento en búfer en anillo consiste en [AuditLogger] Mantenimiento.
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    @ColumnInfo(defaultValue = "") val category: String,
    @ColumnInfo(defaultValue = "") val action: String,
    @ColumnInfo(defaultValue = "") val target: String = "",
    @ColumnInfo(defaultValue = "") val detail: String = "",
    @ColumnInfo(defaultValue = "1") val success: Boolean = true,
)
