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

// v1.x Reparación: DataStore delegate Debe definirse en el nivel superior del archivo.(singleton).
// Cuando se define dentro de una clase,Cada instancia se registra de forma independiente. delegate;
// múltiples instancias(Koin Inyectar + directo new)activará
// "multiple DataStores active for the same file" colapsar.
private val Context.toolDataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "muse_tool_config")

/**
 * Almacenamiento persistente de la política de aprobación de herramientas.
 *
 * Guarde la política de aprobación en DataStore por nombre de herramienta; las herramientas que no están configuradas explícitamente de forma predeterminada son ALWAYS_ALLOW.
 * El formato de almacenamiento es objeto JSON,DataStore key fijo a `tool_policies`.
 */
class ToolConfigStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val TOOL_POLICIES_KEY = stringPreferencesKey("tool_policies")
    }

    @Serializable
    data class ToolPolicies(
        val policies: Map<String, ToolApprovalPolicy> = emptyMap(),
    )

    /** Flow of all tool policies. */
    val policiesFlow: Flow<Map<String, ToolApprovalPolicy>> =
        context.toolDataStore.data.map { prefs ->
            decodePolicies(prefs).policies
        }

    /**
     * Usuario**explícito**La política configurada; devuelve nulo si no está configurado.
     *
     * La revisión determinó que se debe utilizar este método: No configurado ≠ El usuario lo permite. Trate "no configurado" como [ToolApprovalPolicy.ALWAYS_ALLOW]
     * dejará [ToolPermissionResolver] Se considera que el usuario ha sido liberado explícitamente, saltándose el nivel de riesgo en modo ASK/STRICT.
     * Debidas aprobaciones (defectos históricos:ASK En el modo, las herramientas de alto riesgo en realidad no mostrarán la tarjeta de aprobación).
     */
    suspend fun getConfiguredPolicy(toolName: String): ToolApprovalPolicy? = policiesFlow.first()[toolName]

    /**
     * Accesores compatibles: respaldo cuando no están configurados [ToolApprovalPolicy.ALWAYS_ALLOW].
     *
     * Este respaldo hace que "no configurado" parezca "el usuario lo ha liberado", por lo que solo es adecuado para "solo le importa si el usuario lo deshabilitó explícitamente".
     * punto de llamada; cualquier decisión de revisión que determine "si se puede implementar" debe utilizar [getConfiguredPolicy] y poner null
     * entregar [ToolPermissionResolver] Determinar en función del modo de sesión + nivel de riesgo (la puerta de herramientas del subagente también sigue esta regla).
     */
    suspend fun getPolicy(toolName: String): ToolApprovalPolicy = getConfiguredPolicy(toolName) ?: ToolApprovalPolicy.ALWAYS_ALLOW

    /**
     * Establezca la política de aprobación para la herramienta especificada.
     *
     * [ToolApprovalPolicy.ALWAYS_ALLOW] voluntad**escribe como siempre**: Significa que el usuario libera explícitamente,
     * Debe distinguirse de "Nunca configurado", de lo contrario, el usuario no puede coexistir con el estado predeterminado después de hacer clic en "Permitir siempre".
     */
    suspend fun setPolicy(toolName: String, policy: ToolApprovalPolicy) {
        context.toolDataStore.edit { prefs ->
            val current = decodePolicies(prefs)
            val updated = current.copy(
                policies = current.policies.toMutableMap().apply { put(toolName, policy) },
            )
            prefs[TOOL_POLICIES_KEY] = encodePolicies(updated)
        }
    }

    /**
     * Según**salvado**La política analiza el estado de aprobación.
     *
     * Se devuelven tanto la versión no configurada como la explícita. [ToolApprovalState.Auto], es decir, este método solo maneja el veto explícito por parte del usuario
     * con solicitud explícita de aprobación; modo/La determinación predeterminada del nivel de riesgo se basa en [ToolPermissionResolver] Sea responsable.
     */
    suspend fun resolveApprovalState(toolName: String): ToolApprovalState {
        return when (getConfiguredPolicy(toolName)) {
            null, ToolApprovalPolicy.ALWAYS_ALLOW -> ToolApprovalState.Auto
            ToolApprovalPolicy.ALWAYS_DENY -> ToolApprovalState.Denied("Tool disabled by user")
            ToolApprovalPolicy.ASK_EVERY_TIME -> ToolApprovalState.Pending
        }
    }

    private fun decodePolicies(prefs: Preferences): ToolPolicies {
        val raw = prefs[TOOL_POLICIES_KEY] ?: return ToolPolicies()
        return try {
            json.decodeFromString<ToolPolicies>(raw)
        } catch (_: Exception) {
            ToolPolicies()
        }
    }

    private fun encodePolicies(policies: ToolPolicies): String = json.encodeToString(policies)
}
