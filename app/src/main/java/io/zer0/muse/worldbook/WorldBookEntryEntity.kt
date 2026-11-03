package io.zer0.muse.worldbook

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * P1-2: Worldbook(Libro del mundo dinámico)entidad.
 *
 * con existente [io.zer0.muse.data.lorebook.LorebookEntity] la diferencia:
 *  - Lorebook Pasa por el oleoducto Transformer.,Escanea solo el último mensaje de USUARIO,Los lugares de inyección son limitados(before/after_system/before_last)
 *  - Worldbook Sigue el sistema Hook(SystemPromptComposeHook + PromptFinalizeHook),apoyo:
 *    · alwaysActive Activación residente(No se requieren coincidencias de palabras clave)
 *    · scanDepth Escanea los últimos N niveles de mensajes de USUARIO(No sólo el último)
 *    · isRegex Palabras clave habituales(cooperar caseSensitive)
 *    · assistantId Vincular un ayudante específico(null = situación general)
 *    · AT_DEPTH inyección profunda(Insertar en el enésimo nivel de la historia.,No solo antes y después de las indicaciones del sistema.)
 *
 * Ambos coexisten,No interfieran el uno con el otro:LorebookTransformer ejecutar dentro de la canalización,WorldBookHook Ejecutado después del oleoducto.
 *
 * Todos los campos NO NULOS tienen @ColumnInfo(defaultValue=...),Alineado con MIGRATION_57_58 SQL.
 */
@Serializable
@Entity(tableName = "worldbook_entries")
data class WorldBookEntryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "[]") val keywordsJson: String = "[]",
    @ColumnInfo(defaultValue = "") val content: String = "",
    @ColumnInfo(defaultValue = "50") val priority: Int = 50,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    @ColumnInfo(defaultValue = "0") val caseSensitive: Boolean = false,
    /**
     * v2.x: coincidencia de palabras completas(Sólo el modo de subcadena tiene efecto;isRegex=true escrito por el usuario \b).
     * ingles/Las palabras clave numéricas deben ir precedidas y seguidas de límites de palabras para reducir los desencadenantes falsos.;Las palabras clave que contienen CJK degeneran automáticamente en coincidencias de subcadenas.
     */
    @ColumnInfo(defaultValue = "0") val wholeWord: Boolean = false,
    /** Si las palabras clave coinciden con expresiones regulares(isRegex=true Cada elemento de palabras claveJson se trata como una cadena fuente normal.). */
    @ColumnInfo(defaultValue = "0") val isRegex: Boolean = false,
    /** Activación residente:Se inyecta en el indicador del sistema sin coincidencias de palabras clave. */
    @ColumnInfo(defaultValue = "0") val alwaysActive: Boolean = false,
    /** Escanea los últimos N niveles de mensajes de USUARIO(1 = solo el ultimo,Consistente con el comportamiento del Lorebook). */
    @ColumnInfo(defaultValue = "3") val scanDepth: Int = 3,
    /**
     * inyectar objetivo(corresponder [WorldBookInjectTarget]):
     *  - system: Inyectar en el mensaje del SISTEMA(Predeterminado)
     *  - user:   Inyectado como prefijo de mensaje de USUARIO
     *  - assistant: Inyectar como prefijo de mensaje ASISTENTE
     */
    @ColumnInfo(defaultValue = "system") val injectTarget: String = "system",
    /**
     * Ubicación de la inyección(corresponder [WorldBookInjectPosition]):
     *  - prepend:  Insertar antes del mensaje de rol de destino
     *  - append:   Adjunto al mensaje del personaje objetivo.
     *  - at_depth:  Insertar en la historia [insertionDepth] Oficina de mensajes de capa USUARIO
     */
    @ColumnInfo(defaultValue = "append") val injectPosition: String = "append",
    /** injectPosition=at_depth tiempo,Insertar en el mensaje de USUARIO de enésimo nivel desde abajo(1 = última capa). */
    @ColumnInfo(defaultValue = "0") val insertionDepth: Int = 0,
    /** Vincular un ayudante específico(null = situación general,Todos los asistentes entran en vigor.). */
    @ColumnInfo(defaultValue = "NULL") val assistantId: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Inyecta el personaje objetivo.
 */
enum class WorldBookInjectTarget(val storage: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    ;

    companion object {
        fun fromStorage(raw: String?): WorldBookInjectTarget = entries.firstOrNull { it.storage == raw } ?: SYSTEM
    }
}

/**
 * Inyectar estrategia de ubicación.
 */
enum class WorldBookInjectPosition(val storage: String) {
    PREPEND("prepend"),
    APPEND("append"),
    AT_DEPTH("at_depth"),
    ;

    companion object {
        fun fromStorage(raw: String?): WorldBookInjectPosition = entries.firstOrNull { it.storage == raw } ?: APPEND
    }
}
