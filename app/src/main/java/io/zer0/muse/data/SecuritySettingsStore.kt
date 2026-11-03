package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * P2-2 División: segura/Subrepositorio de cambio de comportamiento.
 *
 * Compartido con el repositorio de configuración `muse_settings` DataStore, llevando:
 * - Política de notificación/detección de ANR/mantener despierto/inicio automático al encender
 */
/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class SecuritySettingsStore(
    private val context: Context,
) {

    private val store get() = context.museSettingsDataStore

    val notificationPolicyFlow: Flow<String> = store.data.mapDistinct { prefs ->
        prefs[KEY_NOTIFICATION_POLICY] ?: "when_unfocused"
    }

    val anrDetectionFlow: Flow<Boolean> = store.data.mapDistinct { prefs ->
        prefs[KEY_ANR_DETECTION] ?: true
    }

    val keepAwakeFlow: Flow<Boolean> = store.data.mapDistinct { prefs ->
        prefs[KEY_KEEP_AWAKE] ?: false
    }

    val autoLaunchFlow: Flow<Boolean> = store.data.mapDistinct { prefs ->
        prefs[KEY_AUTO_LAUNCH] ?: false
    }

    suspend fun saveNotificationPolicy(policy: String) {
        store.edit { it[KEY_NOTIFICATION_POLICY] = policy }
    }

    suspend fun saveAnrDetection(enabled: Boolean) {
        store.edit { it[KEY_ANR_DETECTION] = enabled }
    }

    suspend fun saveKeepAwake(enabled: Boolean) {
        store.edit { it[KEY_KEEP_AWAKE] = enabled }
    }

    suspend fun saveAutoLaunch(enabled: Boolean) {
        store.edit { it[KEY_AUTO_LAUNCH] = enabled }
    }

    private companion object {
        private val KEY_NOTIFICATION_POLICY = stringPreferencesKey("notification_policy")
        private val KEY_ANR_DETECTION = booleanPreferencesKey("anr_detection_enabled")
        private val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        private val KEY_AUTO_LAUNCH = booleanPreferencesKey("auto_launch")
    }
}
