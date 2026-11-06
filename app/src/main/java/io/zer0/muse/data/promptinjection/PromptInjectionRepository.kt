package io.zer0.muse.data.promptinjection

import android.content.Context
import io.zer0.muse.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * PromptInjection almacén — Phase 8.5.
 *
 * inyección de patrón: El usuario cambia a mitad de sesión"modo"(creative/coding/roleplay/...)tiempo,
 * Las entradas de inyección correspondientes al modo surten efecto inmediatamente,No se requiere activador de palabra clave.
 */
class PromptInjectionRepository(
    private val dao: PromptInjectionDao,
    private val context: Context,
) {
    // L-PID5: agregar distinctUntilChanged,Evite emisiones repetidas sin sentido desde aguas arriba que activen el rediseño de la interfaz de usuario.
    fun observeAll(): Flow<List<PromptInjectionEntity>> = dao.observeAll().distinctUntilChanged()

    suspend fun getEnabledByMode(mode: String): List<PromptInjectionEntity> = dao.getEnabledByMode(mode)

    suspend fun getByIdsEnabled(ids: List<String>): List<PromptInjectionEntity> =
        if (ids.isEmpty()) emptyList() else dao.getByIdsEnabled(ids)

    suspend fun getById(id: String): PromptInjectionEntity? = dao.getById(id)

    /**
     * Nuevo o actualizado.L-PID7: Posición de inserción correcta a un valor legal,Los valores ilegales vuelven a los valores predeterminados after_system
     * (PromptInjectionTransformer Sólo admite before_system/after_system,No compatible before_last).
     */
    suspend fun upsert(entity: PromptInjectionEntity) {
        val normalized = if (entity.insertionPosition in PromptInjectionEntity.VALID_INSERTION_POSITIONS) {
            entity
        } else {
            entity.copy(insertionPosition = PromptInjectionEntity.INSERTION_AFTER_SYSTEM)
        }
        dao.upsert(normalized)
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    /** Lista de modos preestablecidos(localización),Para uso de selectores de UI.mode Permitir personalización,Sin verificación sólida. */
    val presetModes: List<Pair<String, String>> = listOf(
        "default" to context.getString(R.string.prompt_injection_mode_default),
        "creative" to context.getString(R.string.prompt_injection_mode_creative),
        "coding" to context.getString(R.string.prompt_injection_mode_coding),
        "roleplay" to context.getString(R.string.prompt_injection_mode_roleplay),
        "translation" to context.getString(R.string.prompt_injection_mode_translation),
        "analysis" to context.getString(R.string.prompt_injection_mode_analysis),
    )
}
