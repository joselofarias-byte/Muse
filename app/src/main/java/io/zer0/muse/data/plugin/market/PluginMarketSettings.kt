package io.zer0.muse.data.plugin.market

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.museSettingsDataStore
import io.zer0.muse.data.plugin.PluginSecurityGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Configuración del mercado de complementos: URL del directorio y clave pública raíz de confianza del directorio.
 *
 * El valor predeterminado proviene del directorio oficial integrado en el paquete de instalación. [PluginMarketDefaults], por lo que el mercado funciona de inmediato y no requiere configuración;
 * El usuario aún puede anular la dirección del directorio (directorio de prueba o interruptor de emergencia), pero**La raíz de confianza incorporada siempre participa en la verificación de firmas y no se puede eliminar**,
 * Evite malas configuraciones que invaliden el catálogo oficial. Cuando no se configura una raíz de confianza, la verificación del directorio se rechaza directamente y no degenerará en "confiar en ningún directorio".
 * La raíz de la clave pública de confianza solo puede ser configurada internamente por la aplicación o explícitamente por el usuario, y nunca se lee del contenido del directorio.
 */
/** Derivar la deduplicación del flujo de configuración:DataStore.data Elimine únicamente los duplicados de todas las Preferencias.map Luego, cada uno se deduplica para suprimir la emisión repetida del mismo valor provocada por claves irrelevantes. */
private fun <T> Flow<Preferences>.mapDistinct(transform: suspend (Preferences) -> T): Flow<T> = map(transform).distinctUntilChanged()

class PluginMarketSettings(private val context: Context) {

    private val store get() = context.museSettingsDataStore

    /** URL del directorio efectivo: la anulación del usuario tiene prioridad; de lo contrario, se utiliza el directorio oficial integrado. */
    val catalogUrlFlow: Flow<String> = store.data.mapDistinct { prefs ->
        prefs[KEY_CATALOG_URL]?.trim()?.takeIf { it.isNotEmpty() } ?: PluginMarketDefaults.CATALOG_URL
    }

    /** Valor de anulación de directorio configurado explícitamente por el usuario; vacío significa que se está utilizando el directorio oficial incorporado. */
    val catalogUrlOverrideFlow: Flow<String> = store.data.mapDistinct { prefs ->
        prefs[KEY_CATALOG_URL].orEmpty().trim()
    }

    /** Raíz de confianza de directorio efectiva: raíz de confianza oficial incorporada + raíz de confianza agregada por el usuario. Los elementos integrados no se pueden sobrescribir ni eliminar. */
    val catalogRootKeysFlow: Flow<Map<String, String>> = store.data.mapDistinct { prefs ->
        effectiveRootKeys(decodeRootKeys(prefs[KEY_CATALOG_ROOT_KEYS]))
    }

    suspend fun catalogUrl(): String = catalogUrlFlow.first()

    suspend fun catalogUrlOverride(): String = catalogUrlOverrideFlow.first()

    suspend fun catalogRootKeys(): Map<String, String> = catalogRootKeysFlow.first()

    suspend fun saveCatalogUrl(url: String) {
        val trimmed = url.trim()
        store.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(KEY_CATALOG_URL) else prefs[KEY_CATALOG_URL] = trimmed
        }
    }

    /** Borre las anulaciones de usuario y regrese al directorio oficial integrado. */
    suspend fun resetCatalogUrl() = saveCatalogUrl("")

    /**
     * guardar/Actualice una clave pública raíz de confianza del directorio.
     *
     * La clave pública está estandarizada y verificada antes de escribirla; la entrada ilegal devuelve un error y no modifica la configuración.
     * keyId Deniegue la escritura si tiene el mismo nombre que la raíz de confianza oficial incorporada para evitar sobrescribir la raíz de confianza incorporada.
     */
    suspend fun saveCatalogRootKey(keyId: String, publicKeyBase64: String): Result<String> {
        val id = keyId.trim()
        if (id.isEmpty()) return Result.failure(IllegalArgumentException("keyId no puede estar vacío"))
        if (id in PluginMarketDefaults.catalogRootKeys) {
            return Result.failure(IllegalArgumentException("No se puede anular la raíz de confianza del directorio oficial integrado"))
        }
        val normalized = PluginSecurityGate.canonicalPublicKey(publicKeyBase64.trim())
            .getOrElse { return Result.failure(IllegalArgumentException("La clave pública no es una clave pública Base64 X.509 válida")) }
        // Solo se conserva la raíz de confianza agregada por el usuario: los elementos integrados siempre los proporciona el paquete de instalación y no están escritos en la configuración.
        val next = userRootKeys() + (id to normalized)
        store.edit { prefs ->
            prefs[KEY_CATALOG_ROOT_KEYS] = encodeRootKeys(next)
        }
        return Result.success(normalized)
    }

    /** Elimina una raíz de directorio de confianza agregada por el usuario; la raíz de confianza oficial incorporada no se puede eliminar. */
    suspend fun removeCatalogRootKey(keyId: String) {
        val id = keyId.trim()
        if (id in PluginMarketDefaults.catalogRootKeys) return
        val next = userRootKeys() - id
        store.edit { prefs ->
            if (next.isEmpty()) prefs.remove(KEY_CATALOG_ROOT_KEYS) else prefs[KEY_CATALOG_ROOT_KEYS] = encodeRootKeys(next)
        }
    }

    /** Raíces de confianza agregadas explícitamente por el usuario (excluyendo las integradas). */
    private suspend fun userRootKeys(): Map<String, String> = decodeRootKeys(store.data.first()[KEY_CATALOG_ROOT_KEYS])

    /** raíz efectiva de confianza = Los elementos agregados por el usuario se combinan con elementos integrados; si tienen la misma denominación, prevalecerán los elementos incorporados. */
    private fun effectiveRootKeys(configured: Map<String, String>): Map<String, String> = configured + PluginMarketDefaults.catalogRootKeys

    private fun decodeRootKeys(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            AppJson.decodeFromString(ROOT_KEYS_SERIALIZER, raw)
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotBlank() }
        }.getOrElse { error ->
            Logger.w(TAG, "La resolución de la raíz de confianza del directorio falló y se maneja como no configurada.", error)
            emptyMap()
        }
    }

    private fun encodeRootKeys(keys: Map<String, String>): String = AppJson.encodeToString(ROOT_KEYS_SERIALIZER, keys)

    private companion object {
        private const val TAG = "PluginMarketSettings"
        private val KEY_CATALOG_URL = stringPreferencesKey("plugin_market_catalog_url")
        private val KEY_CATALOG_ROOT_KEYS = stringPreferencesKey("plugin_market_catalog_root_keys")
        private val ROOT_KEYS_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
