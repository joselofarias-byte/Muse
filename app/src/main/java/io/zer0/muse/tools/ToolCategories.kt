package io.zer0.muse.tools

/**
 * v1.0.53: Registro de clasificación de herramientas incorporado.
 *
 * CORE      — La eliminación destruirá las capacidades del modelo.,El usuario no puede cerrar,No se muestra en la lista de herramientas
 * STANDARD  — Herramientas integradas normalmente abiertas,No mostrar interruptor(El comportamiento es estable.)
 * OPTIONAL  — Los usuarios pueden configurar→interruptor de herramienta
 * GLOBAL    — Administrado por la página de configuración de permisos globales(red/Archivo/clase de ejecución)
 * LEGACY    — Transferencia abandonada,Sólo compatibles,No registrado en la superficie de la herramienta
 *
 * iniciar afirmación:Cada herramienta integrada registrada debe y solo puede pertenecer a una categoría,
 * De lo contrario, el registro de inicio de la aplicación informará un error.(debug La compilación se niega a comenzar),Forzar clasificación de registro de nueva herramienta.
 */
enum class ToolCategory { CORE, STANDARD, OPTIONAL, GLOBAL, LEGACY }

object ToolCategories {
    /** Base de capacidades del modelo:La eliminación destruye la conversación/Llamada de herramienta. */
    val CORE: Set<String> =
        setOf(
            "get_current_time",
            "calculator",
            "get_device_info",
            // v2.x escenario3:Búsqueda de biblioteca de herramientas bajo demanda — Canales de finalización jerárquicamente reducidos,debe ser constante
            "find_tools",
        )

    /** Herramienta de estabilización normalmente abierta. */
    val STANDARD: Set<String> =
        setOf(
            "get_weather",
            "web_search",
            "web_fetch",
            "get_battery_info",
            "get_network_info",
            // v2.x Etapa de adelgazamiento de herramientas.4:Parámetros de equipos impopulares(memoria/almacenamiento/mostrar/CPU/sensor)Degradado de Hengfa,
            // Clasificado como OPCIONAL y reducido al mismo calibre que la familia de información del dispositivo. Son seguros de solo lectura,Pero no es un requisito de alta frecuencia.,
            // Cada ronda de Hengfa ocupará el presupuesto del esquema en vano.;Utilice palabras clave del dispositivo para acceder cuando sea necesario,O recuperarlo con find_tools.
            "get_foreground_app",
            "clipboard_read",
            // v2.x: Autocomprobación del estado del canal — Sólo lectura segura,Mejorar ESTÁNDAR(De lo contrario"Justo cuando es necesario, es bloqueado por el estrechamiento GLOBAL")
            "screen_permission_status",
            // v2.x Tiempo de ejecución extendido(P0):Autoprueba integrada de la zona de pruebas de Node — Autocomprobación de estado de solo lectura,siempre visible
            "runtime_selfcheck",
            "platform_selfcheck",
            // v2.6.10 Mejoras en la carga de herramientas: los archivos de autoprueba/memoria/tarjeta/lectura se cargan desde OPCIONAL.
            "tool_status",
            "pin_memory",
            "unpin_memory",
            "save_memory",
            "delete_memory",
            "search_memory",
            "search_conversation",
            "recall_experience",
            "record_experience",
            "show_card",
            "render_data",
            "update_card_data",
            "read_file",
            "read_user_file",
            // v2.6.10 El segundo lote de carga y rectificación de herramientas: autoverificación de estado/notificación/organización de tareas/entrada de delegación.
            "current_status",
            "notify",
            "todo_write",
            "subagent_task",
            "subagent_run",
            // Envío de voz: Cloud TTS debe configurarse para que tenga sentido, pero la herramienta en sí siempre está visible ——
            // Anteriormente, era OPCIONAL y tenía que esperar a que el usuario dijera "voz/lectura/voz" antes de cargar.
            // Como resultado, el personaje aún no puede hablar después de configurar la tecla y el tono (el asistente personalizado ni siquiera puede buscarlo si la lista blanca de herramientas está configurada).
            "send_voice",
        )

    /** Los usuarios pueden configurar→Interruptor de herramienta. */
    val OPTIONAL: Set<String> =
        setOf(
            "open_url",
            "send_email",
            "send_sms",
            "add_contact",
            "set_alarm",
            "set_timer",
            "add_calendar_event",
            "toggle_wifi",
            "toggle_bluetooth",
            "toggle_flashlight",
            "set_brightness",
            "get_brightness",
            "set_volume",
            "get_volume",
            "vibrate",
            "open_app",
            "open_system_setting",
            // v2.6.2: Conjunto de herramientas de control de aplicaciones (app_* Familia, clasificación de registro previamente faltante → Ir a la sucursal nula Hengfa/invisible)
            "app_list",
            "app_launch",
            "app_settings",
            // v2.6.2: Lista de solo lectura de la base de conocimientos + organización diaria de conversaciones (NORMAL, no destructivo)
            "knowledge_list_docs",
            "knowledge_read_doc",
            "session_list",
            "session_read",
            "session_rename",
            "session_move_folder",
            "session_archive",
            // v2.6.2: Lista de habilidades (SAFE) + inicio y parada de habilidad (NORMAL) + lista de ayuda (SAFE)
            "skill_manage_list",
            "skill_manage_toggle",
            "assistant_manage_list",
            // v2.x: token Autocontrol de dosis — De solo lectura y de baja frecuencia, se puede ver presionando la familia de palabras clave o cargándola con find_tools.
            "get_token_usage",
            "clipboard_write",
            "screen_time",
            "get_location",
            "get_contacts_list",
            "get_contacts_count",
            "get_recent_notifications",
            "list_installed_apps",
            "share_text",
            "echo",
            "get_calendar_today",
            "calendar_today",
            "get_wifi_info",
            "get_bluetooth_devices",
            // v2.x Etapa de adelgazamiento de herramientas.4:Bajar de ESTÁNDAR — Parámetros de equipos impopulares,Limitar por palabras clave de familia de dispositivos
            "get_storage_info",
            "get_memory_info",
            "get_display_info",
            "get_cpu_info",
            "get_sensors_list",
            "make_phone_call",
            "open_maps",
            "url_encode",
            "url_decode",
            "base64_encode",
            "base64_decode",
            "hash_text",
            "generate_uuid",
            "random_number",
            "schedule_reminder",
            "cancel_reminder",
            "list_reminders",
            "resource_add",
            "resource_list",
            "resource_search",
            "resource_get",
            "resource_delete",
            "quick_note_add",
            "quick_note_list",
            "quick_note_search",
            "quick_note_get",
            "quick_note_update",
            "quick_note_delete",
            "quick_note_pin",
            "scheduled_task_create",
            "scheduled_task_list",
            "scheduled_task_update",
            "scheduled_task_delete",
            "scheduled_task_execute",
            "scheduled_task_get_history",
            "translate",
            "ping_host",
            "dns_lookup",
            "get_public_ip",
            "json_pretty",
            "generate_password",
            "speak_text",
            // Archivo/Enlace/Documentación(Previamente registrado pero no registrado para la categoría)
            // v2.6.7: SAF Leer archivos del dispositivo del usuario (requiere clic del usuario)
            "create_download",
            "parse_link",
            "parse_pdf",
            // generación de medios
            "generate_image",
            "generate_video",
            "generate_qr_code",
            // v2.x: El mensaje de voz enviado por el asistente se actualizó a ESTÁNDAR (ver arriba) y ya no aparece aquí.
            // memoria/experiencia
            "memory_config_get",
            "memory_config_set",
            // v2.6.7: Aprendizaje de preferencias del usuario (precipitando restricciones estrictas)
            "remember_preference",
            // Tarjetas de tareas/Notificaciones/Estado
            // Subagente/Equipo/Chat grupal
            "subagent_close",
            "delegate_agent",
            "proactive_message_wish",
            // v2.6.9: Preocupado por la contabilidad —— El principal combustible del nuevo mecanismo de mensajería proactiva
            "note_thought",
            "channel_pass",
            "channel_read_context",
            "channel_reply",
            "list_stickers",
            "send_sticker",
        )

    /** Gestión de páginas de permisos globales(ejecución de alto riesgo/Navegador/espacio de trabajo/UI Automatización). */
    val GLOBAL: Set<String> =
        setOf(
            "execute_javascript",
            "execute_node_script",
            "execute_python_script",
            "execute_shell",
            "workspace_write",
            "workspace_edit",
            "workspace_list",
            "workspace_read",
            "workspace_grep",
            "workspace_delete",
            "workspace_mkdir",
            "workspace_move",
            "workspace_copy",
            "workspace_mounts",
            "browser_navigate",
            "browser_click",
            "browser_type",
            "browser_extract",
            "browser_scroll_bottom",
            "browser_get_html",
            "browser_snapshot",
            "browser_screenshot",
            // canal de mensajes(v1.0.92)
            "send_channel_message",
            "channel_list",
            // OAuth conector(v2.0)
            "connector_list",
            "call_connector",
            // mercado de complementos(v2.0.1)——La recuperación e instalación de complementos externos son capacidades de extensión.
            "plugin_market_search",
            "plugin_market_install",
            // v2.x: Desinstalar/Iniciar y detener complementos
            "plugin_market_uninstall",
            "plugin_market_set_enabled",
            // v2.x Automatización Fase I:Canal de línea de comando del dispositivo(Shizuku/Root Ejecución jerárquica)
            "device_shell",
            // v2.x fase terminal uno:Aplicar comandos de terminal sandbox
            "terminal_exec",
            // v2.2.1: Termux canal(Comandos completos del entorno Linux,alto riesgo)
            "termux_exec",
            // v2.2.1: GUI Agent anillo(Manipulación de pantalla de varios pasos impulsada por la visión,alto riesgo)
            "ui_agent",
            // pantalla virtual(Shizuku/Root canal;Previamente registrado pero no registrado para la categoría)
            "virtual_screen", "virtual_screen_input",
            "automation_workflow",
            // UI Automatización(Accesibilidad requerida,alto riesgo)
            "ui_get_page_info",
            "ui_click",
            "ui_long_press",
            "ui_swipe",
            "ui_set_text",
            "ui_screenshot",
            "ui_back",
            "ui_home",
            "ui_global_action",
            "ui_get_current_app",
            // automatización de pantalla(Accesibilidad requerida,alto riesgo)
            "screen_read",
            "screen_current_app",
            "screen_back",
            "screen_home",
            "screen_tap",
            "screen_tap_text",
            "screen_swipe",
            "screen_input",
            "screen_launch_app",
            "screen_open_notifications",
            "screen_wait",
            // Suplemento de automatización de pantalla(Previamente registrado pero no registrado para la categoría)
            "screen_pinch",
            "screen_swipe_path",
            // Root-level system tools (requires root)
            "settings_get",
            "settings_put",
            "am_start",
            "list_packages",
            "logcat_tail",
            "input_inject",
            // root conmutador de red(Previamente registrado pero no registrado para la categoría)
            "network_toggle",
            // v2.6.2: Control de aplicaciones destructivas + MCP Management Suite (esta clasificación de registro de fugas)
            "app_force_stop",
            "app_clear_data",
            "app_uninstall",
            "mcp_mgmt_list",
            "mcp_mgmt_configure",
            "mcp_mgmt_remove",
            "mcp_mgmt_bind_assistant",
            "mcp_mgmt_reconnect",
            // v2.6.2: Gestión de la base de conocimientos + gestión de sesiones (las operaciones de escritura están sujetas a aprobación)
            "knowledge_delete_doc",
            "knowledge_create_kb",
            "knowledge_delete_kb",
            // v2.6.2: Gestión de sesiones (la eliminación requiere aprobación)
            "session_delete",
            // v2.6.2: Gestión de habilidades (la eliminación requiere aprobación, la función integrada no se puede eliminar)
            "skill_manage_delete",
            // v2.6.2: Autogestión asistente (todo diseño aprobado y con permiso restringido)
            "assistant_manage_create",
            "assistant_manage_update_persona",
            "assistant_manage_delete",
        )

    /** Transferencia abandonada,Sólo compatibles. */
    val LEGACY: Set<String> = emptySet()

    /**
     * v2.x: Tabla de requisitos de permisos del entorno de ejecución de herramientas(Requiere Shizuku/Root/Accesibilidad/Termux trabajar).
     *
     * semántica de valores:dentro de la colección"ya sea listo"Listo para usar(Como por ejemplo screen_* Transitable y sin barreras,También se puede degradar Shizuku/Root).
     * herramientas no presentes = No se requiere autorización adicional. Propósito:find_tools Anotación, marca de aviso del sistema y verificación previa a la ejecución.
     */
    val PERMISSIONS: Map<String, Set<ToolPermission>> =
        buildMap {
            // Canal de comando del dispositivo(Shizuku o Rootear)
            listOf(
                "device_shell", "settings_get", "settings_put", "am_start", "list_packages",
                "logcat_tail", "input_inject", "network_toggle", "virtual_screen", "virtual_screen_input",
            ).forEach { put(it, setOf(ToolPermission.SHELL_TIER)) }
            put("automation_workflow", setOf(ToolPermission.ACCESSIBILITY, ToolPermission.SHELL_TIER))
            // automatización de pantalla(Sin barreras,Canal de dispositivo degradable;screen_pinch Sólo accesibilidad)
            listOf(
                "screen_read", "screen_current_app", "screen_back", "screen_home", "screen_tap",
                "screen_tap_text", "screen_swipe", "screen_input", "screen_launch_app",
                "screen_open_notifications", "screen_wait", "screen_swipe_path",
            ).forEach { put(it, setOf(ToolPermission.ACCESSIBILITY, ToolPermission.SHELL_TIER)) }
            put("screen_pinch", setOf(ToolPermission.ACCESSIBILITY))
            // UI Herramientas(Accesibilidad)
            listOf(
                "ui_get_page_info", "ui_click", "ui_long_press", "ui_swipe", "ui_set_text",
                "ui_screenshot", "ui_back", "ui_home", "ui_global_action", "ui_get_current_app",
            ).forEach { put(it, setOf(ToolPermission.ACCESSIBILITY)) }
            // GUI Agent anillo:Cualquier canal puede tomar capturas de pantalla.
            put("ui_agent", setOf(ToolPermission.SHELL_TIER, ToolPermission.ACCESSIBILITY))
            // Termux canal
            put("termux_exec", setOf(ToolPermission.TERMUX))
        }

    /** Requisitos de permiso para herramientas de consulta(No registrado = No se requiere autorización adicional). */
    fun permissionOf(toolName: String): Set<ToolPermission> = PERMISSIONS[toolName].orEmpty()

    private val ALL: Map<String, ToolCategory> =
        buildMap {
            CORE.forEach { put(it, ToolCategory.CORE) }
            STANDARD.forEach { put(it, ToolCategory.STANDARD) }
            OPTIONAL.forEach { put(it, ToolCategory.OPTIONAL) }
            GLOBAL.forEach { put(it, ToolCategory.GLOBAL) }
            LEGACY.forEach { put(it, ToolCategory.LEGACY) }
        }

    /** Clasificación de consultas(Regreso sin registro null). */
    fun categoryOf(toolName: String): ToolCategory? = ALL[toolName]

    /**
     * iniciar afirmación:Compruebe que todas las herramientas integradas registradas tengan categorías.
     *
     * @param registeredNames Colección de nombres de herramientas registradas
     * @return Lista de nombres de herramientas para categorías no registradas(vacío = Todo cubierto)
     */
    fun assertCoverage(registeredNames: Set<String>): List<String> = registeredNames.filter { it !in ALL }.sorted()
}
