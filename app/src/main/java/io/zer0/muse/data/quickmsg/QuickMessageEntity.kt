package io.zer0.muse.data.quickmsg

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * QuickMessage(mensaje rapido)entidad — Phase 8.5.
 *
 * Escritura e implementación independientes.: Plantilla de mensaje predeterminada,Los usuarios pueden insertar cuadros de entrada con un solo clic.
 * - Mensaje rápido global(scope="global"): Compartido por todos los asistentes.
 * - Assistant Vincular mensaje rápido(scope="assistant"): Relevante a través de Assistant.quickMessageIdsJson
 *
 * Phase 8.5 Reparación: Todos los campos NO NULOS más @ColumnInfo(defaultValue=...) Alineado con MIGRATION_4_5 SQL.
 *
 * M-QM1: agregar @Index(scope, assistantId, enabled) Acelerar las consultas de observeForAssistant
 * (WHERE scope='global' OR (scope='assistant' AND assistantId=:id)) AND enabled=1.
 */
@Serializable
@Entity(
    tableName = "quick_messages",
    indices = [Index(value = ["scope", "assistantId", "enabled"])],
)
data class QuickMessageEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "global") val scope: String = "global",
    @ColumnInfo(defaultValue = "") val assistantId: String = "",
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)
