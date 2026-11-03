package io.zer0.muse.tools

import io.zer0.muse.data.plugin.market.PluginMarketToolGateway

/**
 * v2.0.1: Registrador de herramientas de mercado de complementos — Deje que el asistente busque directamente el mercado de complementos e instale los complementos según la aprobación.
 *
 *  - [TOOL_MARKET_SEARCH] Búsqueda de sólo lectura (SAFE): enumera los complementos y las instalaciones del mercado./estado de confianza;
 *  - [TOOL_MARKET_INSTALL] Descargue e instale (HIGH, se requiere aprobación de la sesión): cuando no se confía en el emisor
 *    Devuelva la información que se va a confirmar primero y luego envíela después de que el usuario la confirme. trust_publisher=true rever;
 *  - [TOOL_MARKET_UNINSTALL] Desinstalar complementos instalados (HIGH): El registro, el directorio, las habilidades atribuidas y las versiones retenidas se limpian juntos;
 *  - [TOOL_MARKET_SET_ENABLED] habilitar/Desactivar complementos instalados (HIGH): Desactívelo para conservar la instalación y puede restaurarse en cualquier momento.
 *
 * Seguridad: instale el mismo conjunto de enlaces de revisión (verificación de directorio, firma del editor, resumen de contenido,
 * La instalación del Coordinador) no proporciona ninguna ruta para eludir la aprobación del usuario o la raíz de confianza. ver
 * [io.zer0.muse.data.plugin.market.PluginMarketToolGateway].
 */
class PluginMarketToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val marketService: PluginMarketToolGateway,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_SEARCH,
                description = "Busque complementos en el mercado de complementos de Muse (herramientas externas/Paquete de piel declarativo). Devuelve el ID del complemento, nombre, versión, descripción," +
                    "Editor e instalación/Estado de confianza. Después de encontrar el complemento apropiado, muestre la información clave al usuario y luego use plugin_market_install para instalarlo." +
                    "(La tarjeta de aprobación aparecerá durante la instalación).",
                parameters = mapOf("query" to "Opcional,Palabra clave (id de coincidencia/nombre/Descripción); dejar en blanco para enumerar todos"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            marketService.search(args["query"].orEmpty())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_INSTALL,
                description = "Instale complementos del mercado de complementos (plugin_id Obtenido usando plugin_market_search). La ejecución descargará el paquete de instalación," +
                    "Verifique la firma del editor y el resumen de contenido antes de la instalación, y la tarjeta de aprobación del usuario aparecerá antes de la instalación." +
                    "Cuando aún no se confía en el emisor, esta herramienta devolverá información para confirmar: primero muestre la información al usuario y luego tráigasela después de la confirmación. " +
                    "trust_publisher=true Llama de nuevo.",
                parameters = mapOf(
                    "plugin_id" to "Requerido,ID del complemento (ver plugin_market_search)",
                    "trust_publisher" to "Opcional,true/false: pasa verdadero cuando el usuario ha confirmado que confía en el editor del complemento (obligatorio para la instalación por primera vez de complementos de editores que no son de confianza)",
                ),
                required = setOf("plugin_id"),
                category = "built-in",
                parameterTypes = mapOf("trust_publisher" to "boolean"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pluginId = args["plugin_id"].orEmpty()
            if (pluginId.isBlank()) return@register "Error de instalación: falta plugin_id."
            val trustPublisher = args["trust_publisher"]?.equals("true", ignoreCase = true) == true
            marketService.install(pluginId, trustPublisher)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_UNINSTALL,
                description = "Desinstale un complemento instalado (el registro, el directorio de complementos, las habilidades atribuidas y las versiones retenidas también se limpian y no se pueden restaurar)." +
                    "plugin_id Se puede obtener con plugin_market_search o dejar que el usuario lo confirme desde la página de administración de complementos. La sesión de ejecución mostrará la tarjeta de aprobación.",
                parameters = mapOf("plugin_id" to "Requerido,Complementos instalados id"),
                required = setOf("plugin_id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            marketService.uninstall(args["plugin_id"].orEmpty())
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = TOOL_MARKET_SET_ENABLED,
                description = "Habilite o deshabilite los complementos instalados. desactivar(enabled=false) sólo congela los complementos y sus capacidades, la instalación se conserva y se puede restaurar en cualquier momento;" +
                    "Utilice plugin_market_uninstall si no lo desea en absoluto. La sesión de ejecución mostrará la tarjeta de aprobación.",
                parameters = mapOf(
                    "plugin_id" to "Requerido,Complementos instalados id",
                    "enabled" to "Requerido,true=habilitar,false=desactivar",
                ),
                required = setOf("plugin_id", "enabled"),
                category = "built-in",
                parameterTypes = mapOf("enabled" to "boolean"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val pluginId = args["plugin_id"].orEmpty()
            if (pluginId.isBlank()) return@register "Error en la operación: falta plugin_id."
            val enabled = args["enabled"]?.equals("true", ignoreCase = true) == true
            marketService.setEnabled(pluginId, enabled)
        }
    }

    companion object {
        /** Recuperar el mercado de complementos (solo lectura). */
        const val TOOL_MARKET_SEARCH = "plugin_market_search"

        /** Instalar complemento (HIGH, pasar por el proceso de aprobación). */
        const val TOOL_MARKET_INSTALL = "plugin_market_install"

        /** Desinstalar complementos instalados (HIGH, pasar por el proceso de aprobación). */
        const val TOOL_MARKET_UNINSTALL = "plugin_market_uninstall"

        /** habilitar/Desactivar complementos instalados (HIGH, pasar por el proceso de aprobación). */
        const val TOOL_MARKET_SET_ENABLED = "plugin_market_set_enabled"
    }
}
