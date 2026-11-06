package io.zer0.muse.tools

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistencia del modo de permiso a nivel de sesión.
 *
 * Almacene el modo de permiso de herramienta de la sesión actual por sessionId,Se reanuda automáticamente al cambiar de sesión.
 *
 * v1.x: Nuevo"Esta sesión permite"caché temporal([sessionAllowedTools])— estado de memoria,No persistente,
 * Cambio de sesión/Caduca automáticamente al final. proporcionar"siempre permitido"(duradero)con"Aprobar esto"(soltero)término medio entre:
 * El usuario hace clic en la tarjeta de aprobación."Esta sesión permite"después,Esta herramienta ya no mostrará la tarjeta de aprobación en esta sesión.,
 * Vuelva automáticamente al estado de consulta después de cambiar de sesión o iniciar en frío.
 */
class SessionPermissionStore(private val context: Context) {

    private val Context.permissionDataStore by preferencesDataStore(name = "muse_session_permission")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Se permite el almacenamiento en caché temporal a nivel de sesión:sessionId → Se permite la recopilación de nombres de herramientas.
     *
     * Estado de memoria pura([Volatile] + [Synchronized] proteger),no persistió en DataStore,
     * App El proceso se pierde naturalmente cuando muere. Por la persona que llama al cambiar de sesión(ChatViewModel)Borre explícitamente las entradas de sesiones antiguas.
     */
    @Volatile
    private var sessionAllowedTools: Map<String, Set<String>> = emptyMap()

    companion object {
        private val PERMISSION_MODES_KEY = stringPreferencesKey("session_permission_modes")
    }

    @Serializable
    data class StoredModes(
        val modes: Map<String, SessionPermissionMode> = emptyMap(),
    )

    /** Todo el modo de permisos de sesión fluye. */
    val modesFlow: Flow<Map<String, SessionPermissionMode>> =
        context.permissionDataStore.data.map { prefs ->
            parseModes(prefs).modes
        }

    /** Leer el modo de permiso de la sesión especificada.,Devuelve si no está configurado [defaultMode](Predeterminado ASK). */
    suspend fun getMode(sessionId: String, defaultMode: SessionPermissionMode = SessionPermissionMode.ASK): SessionPermissionMode {
        return modesFlow.first()[sessionId] ?: defaultMode
    }

    /** Establece el modo de permiso para la sesión especificada. */
    suspend fun setMode(sessionId: String, mode: SessionPermissionMode) {
        context.permissionDataStore.edit { prefs ->
            val current = parseModes(prefs).modes.toMutableMap()
            current[sessionId] = mode
            prefs[PERMISSION_MODES_KEY] = json.encodeToString(StoredModes(current))
        }
    }

    /** Borrar la configuración de permisos para una sesión específica(Llamado cuando se elimina la sesión.). */
    suspend fun clearMode(sessionId: String) {
        context.permissionDataStore.edit { prefs ->
            val current = parseModes(prefs).modes.toMutableMap()
            current.remove(sessionId)
            prefs[PERMISSION_MODES_KEY] = json.encodeToString(StoredModes(current))
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // v1.x: Se permite el almacenamiento en caché temporal a nivel de sesión(estado de memoria,No persistente)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Determinar si una herramienta ha sido permitida temporalmente en la sesión actual(No vuelvas a preguntar en esta conversación.).
     *
     * @return true Indica que el usuario ha hecho clic en esta sesión antes"Esta sesión permite",Auto debe ejecutarse directamente sin que aparezca la tarjeta de aprobación.
     */
    fun isAllowedThisSession(sessionId: String, toolName: String): Boolean {
        return sessionAllowedTools[sessionId]?.contains(toolName) == true
    }

    /**
     * Leer el conjunto temporal de nombres de herramientas permitidos para la sesión especificada(Herramientas para exponer capas.);Es un conjunto vacío cuando no hay registros.
     *
     * v2.x Etapa de adelgazamiento de herramientas.1:Herramientas autorizadas(GLOBAL clase)Permanezca visible en solicitudes posteriores.
     */
    fun allowedToolsThisSession(sessionId: String): Set<String> = sessionAllowedTools[sessionId].orEmpty()

    /**
     * Agrega la herramienta al conjunto temporal permitido para la sesión actual.
     *
     * Por ChatViewModel al hacer clic en el usuario"Esta sesión permite"Se llama cuando se presiona el botón.
     * Sólo afecta a esta sesión,Cambiar de sesión/Falla automáticamente después del arranque en frío(por [clearSession] O procesar la limpieza de la muerte).
     */
    @Synchronized
    fun allowToolForSession(sessionId: String, toolName: String) {
        val current = sessionAllowedTools.toMutableMap()
        val set = (current[sessionId] ?: emptySet()).toMutableSet()
        set.add(toolName)
        current[sessionId] = set
        sessionAllowedTools = current
    }

    /**
     * Borra el conjunto temporal permitido para la sesión especificada.
     *
     * Por ChatViewModel al cambiar de sesión/Llamado en la sesión anterior al crear una nueva sesión.,darse cuenta"Caduca automáticamente al final de la sesión".
     */
    @Synchronized
    fun clearSession(sessionId: String) {
        if (sessionAllowedTools.isEmpty()) return
        val current = sessionAllowedTools.toMutableMap()
        current.remove(sessionId)
        sessionAllowedTools = current
    }

    /**
     * Borrar el conjunto temporal permitido para todas las sesiones(Para limpieza a nivel de proceso de aplicación,Generalmente no es necesario,Muerte del proceso y pérdida natural.).
     */
    @Synchronized
    fun clearAllSessions() {
        sessionAllowedTools = emptyMap()
    }

    private fun parseModes(prefs: Preferences): StoredModes {
        val raw = prefs[PERMISSION_MODES_KEY] ?: return StoredModes()
        return try {
            json.decodeFromString<StoredModes>(raw)
        } catch (_: Exception) {
            StoredModes()
        }
    }
}
