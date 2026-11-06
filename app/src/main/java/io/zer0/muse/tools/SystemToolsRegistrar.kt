package io.zer0.muse.tools

/**
 * P1-3b Desdomainización: Sistema/Registrador de herramientas del dispositivo.
 *
 * Registrarse screen_time / open_system_setting / toggle_wifi / toggle_bluetooth /
 * send_email / get_battery_info / get_recent_notifications / open_url /
 * list_installed_apps / get_network_info / get_storage_info / get_memory_info /
 * get_display_info / get_cpu_info / get_sensors_list / get_brightness /
 * set_brightness / get_volume / set_volume / toggle_flashlight / vibrate /
 * get_foreground_app / get_wifi_info / get_bluetooth_devices.
 * La implementación está ubicada en [SystemToolsImpl.kt].
 */
class SystemToolsRegistrar(
    private val context: android.content.Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = SystemToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "screen_time",
                description = "Obtenga las estadísticas de uso de pantalla de cada aplicación hoy(10 mejores). Requiere permiso PACKAGE_USAGE_STATS.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { impl.execScreenTime(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_system_setting",
                description = "Abra la página de configuración del sistema,Salto de categoría de soporte.",
                parameters = mapOf("category" to "Opcional,Establecer categorías: wifi/bluetooth/display/sound/app_settings espera,Predeterminado settings"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenSystemSetting(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_wifi",
                // A-02: Alinearse con la implementación — enabled Especifique directamente el estado objetivo;Si no se aprueba, solo leerá y consultará el estado actual.
                // (Android 10+ No se puede voltear mediante programación WiFi,Eliminar"voltear"Compromiso)
                description = "Activa y desactiva el WiFi.enabled Pase verdadero para activar/falso para desactivar;Si no se aprueba, se consultará el estado actual. encender/El cierre requiere la confirmación del usuario saltando a la página de configuración del sistema.",
                parameters = mapOf("enabled" to "Opcional,true=encender / false=cerrar;Si no se aprueba, consulta el estado actual.(Parámetros compatibles action: on/off/status)"),
                required = emptySet(),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Cambios irreversibles en el estado del sistema.
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execToggleWifi(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_bluetooth",
                // A-02: Alinearse con la implementación,Eliminar"voltear"Compromiso
                description = "Activa y desactiva Bluetooth.enabled Pase verdadero para activar/falso para desactivar;Si no se aprueba, se consultará el estado actual.Android 10+ Es necesario saltar a la página de configuración del sistema para que el usuario la confirme.",
                parameters = mapOf("enabled" to "Opcional,true=encender / false=cerrar;Si no se aprueba, consulta el estado actual.(Parámetros compatibles action: on/off/status)"),
                required = emptySet(),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Cambios irreversibles en el estado del sistema.
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execToggleBluetooth(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "send_email",
                description = "Envíe correos electrónicos a través de la aplicación de correo del sistema.",
                parameters = mapOf(
                    "to" to "Requerido,Correo electrónico del destinatario,Múltiples separados por comas",
                    "subject" to "Opcional,Tema",
                    "body" to "Opcional,Texto",
                ),
                required = setOf("to"),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Efectos secundarios de la comunicación entre dispositivos
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSendEmail(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_battery_info",
                description = "Obtenga energía de la batería y estado de carga.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBatteryInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_recent_notifications",
                description = "Reciba notificaciones de aplicaciones recibidas recientemente. Se requiere aviso de uso.",
                parameters = mapOf(
                    "limit" to "Opcional,Cantidad de devolución,Predeterminado 20",
                    "package_name" to "Opcional,Filtrar por nombre de paquete",
                    "query" to "Opcional,Busque por nombre del paquete de la aplicación, nombre de la aplicación, título, canal o texto",
                    "unread_only" to "Opcional,true/false,Solo devolver notificaciones que aún no se hayan visto en Muse",
                    "active_only" to "Opcional,true/false,Solo devolver notificaciones que todavía están actualmente en la barra de notificaciones del sistema",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetRecentNotifications(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_url",
                description = "Abra la URL en el navegador de su sistema.",
                parameters = mapOf("url" to "Requerido,http/https enlace"),
                required = setOf("url"),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Riesgos de privacidad de enlaces externos
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execOpenUrl(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_installed_apps",
                description = "Listar aplicaciones instaladas,Admite filtrado y limitación de cantidad.",
                parameters = mapOf(
                    "filter" to "Opcional,Palabra clave de filtro de nombre",
                    "limit" to "Opcional,Cantidad de devolución,Predeterminado 20",
                    "include_system" to "Opcional,Ya sea para incluir aplicaciones del sistema",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execListInstalledApps(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_network_info",
                description = "Obtenga información de conexión de red actual.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetNetworkInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_storage_info",
                description = "Obtenga uso del espacio de almacenamiento.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetStorageInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_memory_info",
                description = "Obtener uso de memoria.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetMemoryInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_display_info",
                description = "Obtenga información como la resolución de la pantalla y la frecuencia de actualización.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDisplayInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_cpu_info",
                description = "Obtenga información como el modelo de CPU y la cantidad de núcleos.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetCpuInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_sensors_list",
                description = "Enumera los sensores disponibles para el dispositivo.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execGetSensorsList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_brightness",
                description = "Obtenga el brillo actual de la pantalla.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBrightness(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_brightness",
                // A-03: La implementación ya admite la sucursal automática;required eliminar value(No requerido en modo automático)
                description = "Establecer el brillo de la pantalla. Se requiere permiso de escritura en la configuración del sistema.auto=true Habilitar brillo automático(ignorar en este momento value);De lo contrario pasar value(0-255)Configure el brillo manual.",
                parameters = mapOf(
                    "value" to "Opcional(El modo automático ignora),brillo manual 0-255",
                    "auto" to "Opcional,Pase verdadero para habilitar el brillo automático(En este momento se ignora el valor)",
                ),
                required = emptySet(),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Cambios irreversibles en el estado del sistema.
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetBrightness(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_volume",
                description = "Obtenga el volumen de transmisión de audio especificado.",
                parameters = mapOf("stream" to "Opcional,music/ring/alarm/notification/call/system"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execGetVolume(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_volume",
                description = "Establece el volumen de transmisión de audio especificado.",
                parameters = mapOf(
                    "value" to "Requerido,Volumen 0-100",
                    "stream" to "Opcional,music/ring/alarm/notification/call/system",
                ),
                required = setOf("value"),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Cambios irreversibles en el estado del sistema.
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetVolume(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_flashlight",
                // A-02: Alinearse con la implementación — enabled Especifique directamente el estado objetivo;Si no se aprueba, consulta el último estado conocido de esta sesión.
                description = "Cambiar linterna(Se requiere permiso de la cámara).enabled Pase verdadero para activar/falso para desactivar;Si no se aprueba, se consultará el estado actual.",
                parameters = mapOf("enabled" to "Opcional,true=encender / false=cerrar;Si no se aprueba, consultar el estado(Parámetros compatibles action: on/off/status)"),
                required = emptySet(),
                category = "built-in",
                // P0-3: Consistente con la tabla explícita ToolPermissionResolver(HIGH)— Cambios irreversibles en el estado del sistema.
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execToggleFlashlight(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "vibrate",
                description = "Active el dispositivo para que vibre.",
                parameters = mapOf(
                    "duration_ms" to "Opcional,Duración de la vibración,Predeterminado 500",
                    "repeat" to "Opcional,Ya sea para repetir",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execVibrate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_foreground_app",
                description = "Obtenga el nombre del paquete de aplicación en primer plano actual. Requiere permiso de estadísticas.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { impl.execGetForegroundApp(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_wifi_info",
                description = "Obtenga información actualizada sobre la conexión WiFi.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetWifiInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_bluetooth_devices",
                description = "Obtenga la lista de dispositivos Bluetooth emparejados.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBluetoothDevices(emptyMap()) }
    }
}
