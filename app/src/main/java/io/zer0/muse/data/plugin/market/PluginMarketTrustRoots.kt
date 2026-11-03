package io.zer0.muse.data.plugin.market

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v2.0.1: Caché en proceso de raíces de confianza del directorio (Koin singleton).
 *
 * Página de administración de complementos (UI) y herramientas en segundo plano (plugin_market_search / plugin_market_install) debe
 * Comparta la misma raíz de confianza para la verificación de firmas:[PluginCatalogRepository] de `trustRootKeys` es una lambda sincrónica,
 * Y la raíz de la confianza dice (DataStore) es una operación pendiente, por lo que este caché se utiliza como puente.
 *
 * Actualizar tiempo: ingrese a la página de administración de complementos y guarde/Elimine la raíz de confianza y antes de llamar a la herramienta ([reload]), llamada de baja frecuencia.
 */
class PluginMarketTrustRoots(
    /** La raíz de confianza establece la entrada de lectura y escritura; comparte el mismo paquete DataStore con otros componentes del mercado. */
    val settings: PluginMarketSettings,
) {
    private val _keys = MutableStateFlow(PluginMarketDefaults.catalogRootKeys)

    /** Raíz de confianza actualmente efectiva (raíz oficial incorporada + elementos adicionales del usuario);UI Puede suscribirse a este estado para actualizar la interfaz. */
    val keys: StateFlow<Map<String, String>> = _keys.asStateFlow()

    /** Lea sincrónicamente la raíz de confianza actual (por [PluginCatalogRepository] uso de lambda sincronizado). */
    fun current(): Map<String, String> = _keys.value

    /** Vuelva a cargar la raíz de confianza desde DataStore (se bloquea; se llama con poca frecuencia). */
    suspend fun reload() {
        _keys.value = settings.catalogRootKeys()
    }
}
