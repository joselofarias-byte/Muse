package io.zer0.muse.data.plugin.market

/**
 * v2.0.1: Límites de capacidad de las herramientas del mercado de complementos.
 *
 * capa de herramienta (`plugin_market_search` / `plugin_market_install`) solo se basa en esta interfaz,
 * No contactar con descargas dentro del mercado./Verificación/Instalar la implementación (la implementación es interna
 * [PluginMarketToolService]) para evitar la difusión de tipos de componentes internos fuera de los registros de herramientas y Koin.
 */
interface PluginMarketToolGateway {

    /** Recupere el directorio de complementos; devuelve una lista de texto para que el modelo lo lea. */
    suspend fun search(query: String): String

    /**
     * Descargue e instale el complemento especificado.
     *
     * @param entryId entrada de directorio id
     * @param trustPublisher Si el usuario ha confirmado explícitamente que confía en el editor del complemento (no se confía en la instalación por primera vez)
     *   Si el complemento del editor es falso, la herramienta devolverá información para confirmar sin realizar el pedido)
     */
    suspend fun install(entryId: String, trustPublisher: Boolean): String

    /** Desinstale los complementos instalados (también se limpian el registro, el directorio, las habilidades atribuidas y las versiones retenidas). Devuelve el resultado de la lectura del modelo. */
    suspend fun uninstall(pluginId: String): String

    /** habilitar/Desactive los complementos instalados (la desactivación preserva la instalación y se puede restaurar en cualquier momento). Devuelve el resultado de la lectura del modelo. */
    suspend fun setEnabled(pluginId: String, enabled: Boolean): String
}
