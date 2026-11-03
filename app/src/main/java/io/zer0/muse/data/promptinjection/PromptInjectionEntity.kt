package io.zer0.muse.data.promptinjection

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * PromptInjection(inyección de patrón)entidad — Phase 8.5.
 *
 * Escritura e implementación independientes.: Inyecte las palabras clave correspondientes según el modo de conversación.
 * Diferencias con el libro de historia: Lorebook Es un desencadenante de palabras clave,PromptInjection ¿Está activado el interruptor de modo?
 * (Cuando el usuario cambia de modo a mitad de sesión,La inyección correspondiente surte efecto inmediatamente.,No se requieren palabras clave).
 *
 * Phase 8.5 Reparación: Todos los campos NO NULOS más @ColumnInfo(defaultValue=...) Alineado con MIGRATION_4_5 SQL.
 *
 * M-PID1: agregar @Index(mode, enabled) Acelerar la consulta de entradas habilitadas por patrón(getEnabledByMode / observeAll).
 * L-PID7: mode/insertionPosition por la libertad String,Las constantes legales para insertionPosition se proporcionan aquí;
 *         mode Por convención PromptInjectionRepository.PRESET_MODES(Permitir personalización,Sin verificación sólida).
 */
@Serializable
@Entity(
    tableName = "prompt_injections",
    indices = [Index(value = ["mode", "enabled"])],
)
data class PromptInjectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mode: String,
    @ColumnInfo(defaultValue = "") val displayName: String = "",
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "after_system") val insertionPosition: String = "after_system",
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** insertionPosition valor legal constante(L-PID7). Nota:PromptInjectionTransformer Sólo admite before_system/after_system,before_last no es compatible. */
        const val INSERTION_BEFORE_SYSTEM = "before_system"
        const val INSERTION_AFTER_SYSTEM = "after_system"

        /** insertionPosition conjunto de valores legales;Repository.upsert Los valores ilegales se corregirán en consecuencia. */
        val VALID_INSERTION_POSITIONS = setOf(INSERTION_BEFORE_SYSTEM, INSERTION_AFTER_SYSTEM)
    }
}
