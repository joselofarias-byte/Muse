package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * Aprendizaje de preferencias del usuario —— Forme hábitos como "No utilice estímulos vacíos" y "No haga resúmenes de tres puntos" en restricciones estrictas.
 *
 * ## ¿Por qué guardarlo por separado en lugar de meterlo en ChatPreferences
 *
 * ChatPreferences es un grupo**campos fijos**mostrar/Cambio de comportamiento, para agregar una nueva preferencia, debe cambiar la clase de datos,
 * Cambiar la página de migración y configuración. Y la preferencia es**colección abierta**: Los usuarios seguirán diciendo "No…",
 * La cantidad es impredecible y el contenido no es adecuado para cambios de interfaz de usuario. Así que guárdalo como una lista.
 *
 * ## Método eficaz: inyecte la palabra solicitada y márquela como "Solicitud explícita del usuario"
 *
 * No basta con escribir las palabras del mensaje del sistema —— El modelo trata las preferencias como "sugerencias" más que como reglas.
 * Por lo tanto, utilice una redacción de restricción fuerte al inyectar y colóquela después del segmento de personalidad y antes de la disciplina de la herramienta para garantizar que la prioridad sea mayor que la pauta de estilo general.
 *
 * ## La diferencia con la "memoria"
 *
 * La memoria es la observación que el modelo hace del usuario (que puede ser incorrecta), y las preferencias son las del usuario.**propuesto personalmente**requisitos (deben cumplirse).
 * Mezclar los dos hará que al modelo le resulte difícil distinguir cuáles son hechos y cuáles son instrucciones, por lo que se almacenan por separado.
 */
@Serializable
data class UserPreference(
    /** Identificación estable, utilizada para eliminación y deduplicación. */
    val id: String,
    /** Prefiere el texto del cuerpo y escríbelo en una oración imperativa (\"No respondas con estímulos vacíos\"). */
    val rule: String,
    /** Las palabras originales del usuario (para rastrear fácilmente por qué se incluye esto). */
    val source: String = "",
    /** Tiempo de creación. */
    val createdAt: Long = 0L,
    /** Ya sea para habilitar —— Es posible que los usuarios quieran cerrar temporalmente un elemento sin eliminarlo. */
    val enabled: Boolean = true,
)

/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class PreferenceStore(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    /** Todas las preferencias (incluidas las de discapacitados). */
    val preferencesFlow: Flow<List<UserPreference>> = store.data.mapDistinct { prefs ->
        decode(prefs[KEY_PREFERENCES].orEmpty())
    }

    /** Sólo preferencias habilitadas —— Utilice esto para la inyección rápida de palabras. */
    val enabledPreferencesFlow: Flow<List<UserPreference>> = preferencesFlow.map { list ->
        list.filter { it.enabled && it.rule.isNotBlank() }
    }

    /**
     * Añade una nueva preferencia.
     *
     * Reglas de deduplicación: si el texto es el mismo después de la normalización, no se realizarán adiciones duplicadas (los usuarios pueden decir lo mismo en diferentes rondas).
     *
     * @return true Indica que realmente se agrega una nueva incorporación;false Indica que ya existe.
     */
    suspend fun add(rule: String, source: String = ""): Boolean {
        val trimmed = rule.trim()
        if (trimmed.isEmpty()) return false
        var added = false
        store.edit { prefs ->
            val current = decode(prefs[KEY_PREFERENCES].orEmpty())
            val normalized = normalize(trimmed)
            if (current.none { normalize(it.rule) == normalized }) {
                val next =
                    current + UserPreference(
                        id = "pref_${System.currentTimeMillis().toString(36)}_${current.size}",
                        rule = trimmed,
                        source = source.trim(),
                        createdAt = System.currentTimeMillis(),
                    )
                prefs[KEY_PREFERENCES] = AppJson.encodeToString(serializer(), next)
                added = true
            }
        }
        return added
    }

    /** Eliminar por identificación. */
    suspend fun remove(id: String) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_PREFERENCES].orEmpty())
            val next = current.filterNot { it.id == id }
            prefs[KEY_PREFERENCES] = AppJson.encodeToString(serializer(), next)
        }
    }

    /** habilitar por identificación/Desactivar. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = decode(prefs[KEY_PREFERENCES].orEmpty())
            val next = current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            prefs[KEY_PREFERENCES] = AppJson.encodeToString(serializer(), next)
        }
    }

    /** Borrar (para \"restablecer\" en la página de configuración). */
    suspend fun clear() {
        store.edit { it.remove(KEY_PREFERENCES) }
    }

    /** Lea una vez sincrónicamente para una rápida construcción de palabras (hay un contexto suspendido). */
    suspend fun enabledPreferences(): List<UserPreference> = enabledPreferencesFlow.first()

    /**
     * La normalización se utiliza para eliminar duplicados: eliminar espacios en blanco, eliminar puntuación final y eliminar letras minúsculas.
     *
     * Sin fusión semántica —— "No utilices estímulos" y "No utilices elogios vacíos" se ven diferentes del código. Son juicios modelo.
     * No debes confiar en las reglas de cadenas para adivinar.
     */
    private fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("""\s+"""), "").trimEnd('。', '.', '！', '!', '？', '?')

    private fun decode(json: String): List<UserPreference> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            AppJson.decodeFromString(serializer(), json)
        }.getOrDefault(emptyList())
    }

    private fun serializer() = kotlinx.serialization.builtins.ListSerializer(UserPreference.serializer())

    private companion object {
        private val KEY_PREFERENCES = stringPreferencesKey("user_preferences")
    }
}
