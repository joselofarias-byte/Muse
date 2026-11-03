package io.zer0.muse.miniphone.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Configuración de fondo a nivel de módulo:El cambio de publicación automática a Momentos y la hora de última generación,Así como el cambio y última hora del mensaje privado activo del personaje.
 *
 * Estos se colocan en el DataStore propio del módulo.:`mp_profile` Es la tabla de negocios de datos de usuarios locales.,
 * Agregar un campo de programación combinará "quién es el usuario" y "¿se ha escapado el fondo" en una tabla?,También es necesario cambiar el esquema de la habitación.
 * Simplemente agregue claves al mismo DataStore —— Declarar uno más `preferencesDataStore` Se bloqueará al abrir una segunda instancia de un archivo con el mismo nombre.
 *
 * @see miniPhoneMomentAutoStore realización de la producción
 */
interface MpMomentAutoStore {
    /** estado del interruptor,Activado de forma predeterminada. */
    val enabledFlow: Flow<Boolean>

    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)

    /** El momento de la última construcción exitosa.(milisegundos);Rentabilidad nunca generada `null`. */
    suspend fun lastGeneratedAt(): Long?

    /** Tenga en cuenta el tiempo de esta generación.,Utilizado para la siguiente ronda de antivibración. */
    suspend fun markGenerated(atMillis: Long)

    /** Interruptor \"Carácter de mensaje privado activo\",Desactivado predeterminado:No me molestes hasta que no haya nadie. */
    val chatProactiveEnabledFlow: Flow<Boolean>

    suspend fun isChatProactiveEnabled(): Boolean

    suspend fun setChatProactiveEnabled(enabled: Boolean)

    /** La hora en la que se envió correctamente el último mensaje privado activo(milisegundos);Nunca envié una devolución `null`. */
    suspend fun lastProactiveChatAt(): Long?

    /** Anota la hora de este mensaje privado proactivo,Dé un intervalo de 4 horas para fines de evaluación. Escriba solo después de que realmente se haya caído. */
    suspend fun markProactiveChat(atMillis: Long)
}

/** Nombre del archivo DataStore dentro del módulo:Almacenar solo configuraciones relacionadas con el teléfono,No compartido con el anfitrión. */
private const val STORE_NAME: String = "miniphone_settings"

private val Context.miniPhoneSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = STORE_NAME)

/** Obtener la configuración en el módulo,La misma copia dentro del proceso. DataStore(DataStore No se permite abrir dos instancias del mismo archivo.). */
fun miniPhoneMomentAutoStore(context: Context): MpMomentAutoStore =
    DataStoreMpMomentAutoStore(context.applicationContext.miniPhoneSettingsDataStore)

/**
 * DataStore Darse cuenta.
 *
 * Error de lectura(El disco está defectuoso, los archivos están ocupados.)Cuando se procesa "usar valor predeterminado":El interruptor para publicar automáticamente en Momentos vuelve al valor predeterminado activado.
 * El mensaje privado activo del rol vuelve al nivel predeterminado.,La última vez fue tratada como si no hubiera tiempo.,Las tareas en segundo plano no deben suspenderse permanentemente debido a una lectura fallida.
 */
internal class DataStoreMpMomentAutoStore(
    private val dataStore: DataStore<Preferences>,
) : MpMomentAutoStore {

    private val preferences: Flow<Preferences> = dataStore.data.catch { throwable ->
        if (throwable is IOException) emit(emptyPreferences()) else throw throwable
    }

    override val enabledFlow: Flow<Boolean> = preferences.map { prefs ->
        prefs[KEY_AUTO_ENABLED] ?: DEFAULT_AUTO_ENABLED
    }

    override suspend fun isEnabled(): Boolean = enabledFlow.first()

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_ENABLED] = enabled }
    }

    override suspend fun lastGeneratedAt(): Long? = preferences.first()[KEY_LAST_GENERATED_AT]?.takeIf { it > 0L }

    override suspend fun markGenerated(atMillis: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_GENERATED_AT] = atMillis }
    }

    override val chatProactiveEnabledFlow: Flow<Boolean> = preferences.map { prefs ->
        prefs[KEY_CHAT_PROACTIVE_ENABLED] ?: DEFAULT_CHAT_PROACTIVE_ENABLED
    }

    override suspend fun isChatProactiveEnabled(): Boolean = chatProactiveEnabledFlow.first()

    override suspend fun setChatProactiveEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_CHAT_PROACTIVE_ENABLED] = enabled }
    }

    override suspend fun lastProactiveChatAt(): Long? = preferences.first()[KEY_LAST_PROACTIVE_CHAT_AT]?.takeIf { it > 0L }

    override suspend fun markProactiveChat(atMillis: Long) {
        dataStore.edit { prefs -> prefs[KEY_LAST_PROACTIVE_CHAT_AT] = atMillis }
    }

    companion object {
        private const val DEFAULT_AUTO_ENABLED: Boolean = true

        /** La mensajería privada activa está desactivada de forma predeterminada:Es más intrusivo que Moments,Debe ser abierto por el propio usuario. */
        private const val DEFAULT_CHAT_PROACTIVE_ENABLED: Boolean = false

        private val KEY_AUTO_ENABLED = booleanPreferencesKey("auto_moment_enabled")

        private val KEY_LAST_GENERATED_AT = longPreferencesKey("last_auto_moment_at")

        private val KEY_CHAT_PROACTIVE_ENABLED = booleanPreferencesKey("chat_proactive_enabled")

        private val KEY_LAST_PROACTIVE_CHAT_AT = longPreferencesKey("last_proactive_chat_at")
    }
}
