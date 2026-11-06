package io.zer0.muse.data.milestone

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Phase 2 2B: entidad hito — Registre hitos en la relación de un usuario con un socio de IA.
 *
 * type: "auto" (El sistema se activa automáticamente) / "manual" (Creado manualmente por el usuario)
 * condition: Identificador de condición de activación (e.g. "first_message", "day_7", "msg_100")
 * triggerValue: Valor real cuando se activa (e.g. Número de mensajes, días)
 * dismissedAt: Hora de cierre del usuario (null = no cerrado)
 */
@Serializable
@Entity(tableName = "milestones")
data class MilestoneEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "type")
    val type: String = "auto",
    @ColumnInfo(name = "condition_type")
    val conditionType: String,
    @ColumnInfo(name = "trigger_value")
    val triggerValue: Long = 0,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "message")
    val message: String,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String? = null,
    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "dismissed_at")
    val dismissedAt: Long? = null,
)
