package io.zer0.muse.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * P2-2 Dividir: subrepositorio de configuración de comportamiento de chat.
 *
 * Incluye estimación de tokens, pegado en archivo, restricciones de contexto mínimo, entrada de texto enriquecido y preferencia de chat JSON.
 */
/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class ChatSettingsStore(private val context: Context) {
    private val store get() = context.museSettingsDataStore

    val tokenEstimateEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_TOKEN_ESTIMATE_ENABLED] ?: false
        }
    val pasteAsFileEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_PASTE_AS_FILE_ENABLED] ?: true
        }
    val pasteAsFileThresholdFlow: Flow<Int> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_PASTE_AS_FILE_THRESHOLD] ?: 2000
        }
    val floorLimiterEnabledFlow: Flow<Boolean> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_FLOOR_LIMITER_ENABLED] ?: false
        }
    val floorLimitFlow: Flow<Int> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_FLOOR_LIMIT] ?: 16
        }

    /** v2.x: Límite de ronda de herramientas(0=Ilimitado;Ilimitado por defecto,Las tareas largas ya no se verán interrumpidas por rondas.). */
    val toolLoopMaxRoundsFlow: Flow<Int> =
        store.data.mapDistinct { prefs ->
            prefs[KEY_TOOL_LOOP_MAX_ROUNDS] ?: 0
        }
    val chatPreferencesFlow: Flow<ChatPreferences> =
        store.data.mapDistinct { prefs ->
            decodeChatPreferences(prefs[KEY_CHAT_PREFERENCES])
        }

    /**
     * Active "Visualización de tarjeta" y "Salida de tarjeta preferida" a la vez.
     *
     * Historia: Esta fue originalmente una migración inversa (migrateCardOutputOffOnce), ponga todos los usuarios
     * preferCardOutput Cerrado incondicionalmente porque el enlace de representación de la tarjeta aún se está rehaciendo. Esa migración ha sido
     * Lo he ejecutado en muchos dispositivos y lo que se almacena en el disco es false —— Simplemente cambiando el valor predeterminado de la clase de datos no se pueden cambiar estos dispositivos.
     *
     * Ahora que la cadena de renderizado puede seguir las dimensiones del contenido y tener dimensiones declaradas por el modelo, es hora de devolverle el cambio al usuario.
     * Así que simplemente escriba la migración al revés en lugar de agregar una migración "abierta":
     * Uno se cancela y el otro se cancela. El estado final depende de la orden de ejecución. Este tipo de dependencia secuencial no debería existir.
     *
     * Utilice nuevas claves de etiqueta para garantizar que los dispositivos que se han migrado también se abran esta vez;
     * Sólo se mueven estos dos bits booleanos y no se tocan otras preferencias del usuario.
     */
    suspend fun migrateCardOutputOnOnce() {
        if (store.data.first()[KEY_CARD_OUTPUT_ON_MIGRATED] == true) return
        store.edit { prefs ->
            val raw = prefs[KEY_CHAT_PREFERENCES]
            backupIfCorrupt(raw)
            val current = decodeChatPreferences(raw)
            if (!current.preferCardOutput || !current.generativeUiEnabled) {
                prefs[KEY_CHAT_PREFERENCES] = AppJson.encodeToString(
                    ChatPreferences.serializer(),
                    current.copy(preferCardOutput = true, generativeUiEnabled = true),
                )
            }
            prefs[KEY_CARD_OUTPUT_ON_MIGRATED] = true
        }
    }

    /** C3: Lista de ID de sesiones de navegación recientes(Más reciente primero,Ir a restablecer arriba,la mayoría [RECENT_SESSIONS_CAP] Artículo). */
    val recentSessionsFlow: Flow<List<String>> =
        store.data.mapDistinct { prefs ->
            decodeRecentSessions(prefs[KEY_RECENT_SESSIONS])
        }

    suspend fun getChatPreferences(): ChatPreferences = chatPreferencesFlow.first()

    /**
     * v2.5.3 (P1-1): Actualización funcional ChatPreferences —— Fuente de verdad única en memoria + lecturas atómicas-cambiar-Escribir.
     *
     * Clave: Los tres pasos de leer el valor anterior, aplicar el bloqueo y volver a escribir se completan dentro de la misma transacción de edición.
     * DataStore Se garantiza que el bloque de edición obtendrá el último valor confirmado, por lo que las escrituras simultáneas no se sobrescribirán entre sí.
     * este es el reemplazo“Pase toda la clase de datos para volver a escribir”La entrada correcta: la persona que llama sólo describe“¿Qué debo cambiar?”,
     * En lugar de tomar una instantánea primero y luego escribir la copia completa (este último usará la instantánea desactualizada para sobrescribir las modificaciones en otros lugares).
     */
    suspend fun updateChatPreferences(block: (ChatPreferences) -> ChatPreferences) {
        store.edit { prefs ->
            val raw = prefs[KEY_CHAT_PREFERENCES]
            // P1-2: Detectar corrupción antes de escribir —— Cuando el JSON existente no esté vacío pero no se pueda decodificar, primero haga una copia de seguridad del contenido original.
            // Evite que los valores predeterminados se vuelvan a escribir como datos del usuario y borre silenciosamente todas las configuraciones.
            backupIfCorrupt(raw)
            val current = decodeChatPreferences(raw)
            prefs[KEY_CHAT_PREFERENCES] = AppJson.encodeToString(ChatPreferences.serializer(), block(current))
        }
    }

    /**
     * v2.5.3 (P1-2): Cuando el JSON de preferencia almacenado está dañado, se realiza una copia de seguridad de la cadena original para su posterior recuperación.
     *
     * solo en“El texto original no está vacío pero no se puede resolver.”Activado cuando; la copia de seguridad se escribe en una clave independiente y no sobrescribe el valor actual.
     */
    private fun backupIfCorrupt(raw: String?) {
        if (raw.isNullOrBlank()) return
        val ok = runCatching { AppJson.decodeFromString(ChatPreferences.serializer(), raw) }.isSuccess
        val prefs = context.getSharedPreferences(CORRUPT_BACKUP_PREFS, Context.MODE_PRIVATE)
        // Escriba solo si está dañado y no se ha realizado una copia de seguridad (conserve el sitio original del problema y no lo sobrescriba repetidamente)
        if (!ok && !prefs.contains(CORRUPT_BACKUP_KEY)) {
            prefs
                .edit()
                .putString(CORRUPT_BACKUP_KEY, raw)
                .putLong(CORRUPT_BACKUP_TS_KEY, System.currentTimeMillis())
                .apply()
            io.zer0.common.Logger.w(
                "ChatSettingsStore",
                "chat_preferences_json Contenido original corrupto respaldado(${raw.length} personaje)llegar $CORRUPT_BACKUP_PREFS/$CORRUPT_BACKUP_KEY",
            )
        }
    }

    /**
     * @Deprecated v2.5.3 (P1-1): La reescritura completa de la clase de datos tomará la instantánea desactualizada y sobrescribirá las modificaciones en otros lugares.
     * Usar en su lugar [updateChatPreferences] Realizar actualizaciones funcionales. Este método está reservado únicamente para pruebas y llamadas de stock de compatibilidad.
     */
    @Deprecated("Usar en su lugar updateChatPreferences(block) Realice actualizaciones funcionales atómicas para evitar que toda la reescritura cubra modificaciones simultáneas")
    suspend fun saveChatPreferences(prefs: ChatPreferences) {
        store.edit { it[KEY_CHAT_PREFERENCES] = AppJson.encodeToString(ChatPreferences.serializer(), prefs) }
    }

    suspend fun saveTokenEstimateEnabled(enabled: Boolean) {
        store.edit { it[KEY_TOKEN_ESTIMATE_ENABLED] = enabled }
    }

    suspend fun savePasteAsFileEnabled(enabled: Boolean) {
        store.edit { it[KEY_PASTE_AS_FILE_ENABLED] = enabled }
    }

    suspend fun savePasteAsFileThreshold(threshold: Int) {
        store.edit { it[KEY_PASTE_AS_FILE_THRESHOLD] = threshold }
    }

    suspend fun saveFloorLimiterEnabled(enabled: Boolean) {
        store.edit { it[KEY_FLOOR_LIMITER_ENABLED] = enabled }
    }

    suspend fun saveFloorLimit(limit: Int) {
        store.edit { it[KEY_FLOOR_LIMIT] = limit }
    }

    /** v2.x: Guardar límite de ronda de herramienta(0=Ilimitado). */
    suspend fun saveToolLoopMaxRounds(limit: Int) {
        store.edit { it[KEY_TOOL_LOOP_MAX_ROUNDS] = limit }
    }

    /**
     * C3: Grabar una sesión de navegación — Ir a restablecer arriba(Mover la misma identificación al frente),Cola recortada de ultra capacidad.
     * punto de llamada: ChatViewModel.switchSession(Embudo unificado de cambio de sesión).
     */
    suspend fun recordSessionViewed(sessionId: String) {
        store.edit { prefs ->
            val current = decodeRecentSessions(prefs[KEY_RECENT_SESSIONS])
            val updated = listOf(sessionId) + current.filterNot { it == sessionId }
            prefs[KEY_RECENT_SESSIONS] =
                AppJson.encodeToString(ListSerializer(String.serializer()), updated.take(RECENT_SESSIONS_CAP))
        }
    }

    private fun decodeChatPreferences(value: String?): ChatPreferences {
        if (value.isNullOrBlank()) return ChatPreferences()
        return runCatching {
            AppJson.decodeFromString(ChatPreferences.serializer(), value)
        }.getOrElse { ChatPreferences() }
    }

    private fun decodeRecentSessions(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        // Volver a la lista vacía cuando los datos históricos están dañados,No afecta el proceso principal.(El historial de navegación es una función de accesibilidad)
        return runCatching {
            AppJson.decodeFromString(ListSerializer(String.serializer()), value)
        }.getOrElse { emptyList() }
    }

    private companion object {
        private const val RECENT_SESSIONS_CAP = 10

        // v2.5.3 (P1-2): Ubicación de copia de seguridad para JSON de preferencia corrupto(SharedPreferences, independiente de DataStore,
        // Evite la pérdida de copias de seguridad cuando el propio DataStore esté dañado).
        private const val CORRUPT_BACKUP_PREFS = "muse_settings_corrupt_backup"
        private const val CORRUPT_BACKUP_KEY = "chat_preferences_json_corrupt"
        private const val CORRUPT_BACKUP_TS_KEY = "chat_preferences_json_corrupt_at"
        private val KEY_RECENT_SESSIONS = stringPreferencesKey("recent_sessions_json")
        private val KEY_TOKEN_ESTIMATE_ENABLED = booleanPreferencesKey("token_estimate_enabled")
        private val KEY_PASTE_AS_FILE_ENABLED = booleanPreferencesKey("paste_as_file_enabled")
        private val KEY_PASTE_AS_FILE_THRESHOLD = intPreferencesKey("paste_as_file_threshold")
        private val KEY_FLOOR_LIMITER_ENABLED = booleanPreferencesKey("floor_limiter_enabled")
        private val KEY_FLOOR_LIMIT = intPreferencesKey("floor_limit")
        private val KEY_TOOL_LOOP_MAX_ROUNDS = intPreferencesKey("tool_loop_max_rounds")
        private val KEY_CHAT_PREFERENCES = stringPreferencesKey("chat_preferences_json")

        /** Marca de migración única: \"Salida de tarjeta de preferencias\" se ha desactivado a la fuerza una vez. */
        private val KEY_CARD_OUTPUT_ON_MIGRATED = booleanPreferencesKey("card_output_on_migrated")
    }
}
