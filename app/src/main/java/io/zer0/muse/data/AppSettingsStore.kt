package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * P2-2 Dividir: configure subalmacenes a nivel de aplicación.
 *
 * Compartido con SettingsRepository/AppearanceSettingsStore `muse_settings` DataStore,
 * Actualmente aloja idiomas de interfaz y caché rápido de SharedPreferences; idiomas posteriores, semillas de temas, accesos directos de escritorio, etc.
 * Los campos a nivel de aplicación continúan convergiendo aquí.
 */
/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class AppSettingsStore(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    /** v1.131: Caché de sincronización rápida del idioma, para que el hilo principal adjuntoBaseContext lea sincrónicamente. */
    private val languageSyncCache = context.getSharedPreferences("muse_language_cache", Context.MODE_PRIVATE)
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val languageFlow: Flow<String> = store.data.mapDistinct { prefs -> prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE }

    init {
        // Cuando los usuarios históricos actualizan a v1.131+, el SP está vacío y debe copiarse del DataStore; La ejecución asincrónica no bloquea el inicio.
        cacheScope.launch {
            try {
                val dsLang = store.data.mapDistinct { prefs -> prefs[KEY_LANGUAGE] ?: DEFAULT_LANGUAGE }.first()
                if (languageSyncCache.getString(KEY_LANGUAGE_SP, null) == null) {
                    languageSyncCache.edit().putString(KEY_LANGUAGE_SP, dsLang).apply()
                }
            } catch (e: Exception) {
                Logger.w("AppSettingsStore", "language SP cache migration failed: ${e.message}", e)
            }
        }
    }

    suspend fun saveLanguage(lang: String) {
        store.edit { it[KEY_LANGUAGE] = lang }
        languageSyncCache.edit().putString(KEY_LANGUAGE_SP, lang).apply()
    }

    /** Lea la configuración de idioma de forma sincrónica para evitar que el subproceso principal lea DataStore durante el inicio en frío. */
    fun getLanguageSync(): String = languageSyncCache.getString(KEY_LANGUAGE_SP, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

    private companion object {
        private const val DEFAULT_LANGUAGE = "es"
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private const val KEY_LANGUAGE_SP = "language"
    }
}
