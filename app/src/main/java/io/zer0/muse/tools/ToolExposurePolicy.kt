package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition

/**
 * Política de exposición de la herramienta de diálogo principal.
 *
 * Determine si cerrar la herramienta en esta ronda y establecer tool_choice en required;
 * La autorización real de la herramienta todavía está determinada por ToolPermissionResolver.
 */
object ToolExposurePolicy {
    /**
     * Razonamiento repetido para determinar si la solicitud actual es adecuada para cerrar la rueda de herramientas.
     *
     * Las solicitudes de herramientas simples y claras no requieren mucha reflexión o planificación antes de escribirlas.
     */
    fun isSimpleToolRequest(userText: String): Boolean {
        val text = userText.trim()
        if (text.isBlank() || text.length > SIMPLE_REQUEST_MAX_CHARS) return false
        return !text.containsAny(COMPLEXITY_KEYWORDS)
    }

    /**
     * Determine si el usuario solicitó explícitamente una acción.
     *
     * Solo aparecen herramientas/OpenAI tool_choice se establece en obligatorio solo cuando se pretende realizar la acción.
     * El chat ordinario aún mantiene la selección de modelos gratuita.,evitar por“tener herramientas”Y llame a herramientas irrelevantes.
     */

    /**
     * El usuario solicitó explícitamente solo respuesta./Al realizar un manifiesto sin ejecutar la herramienta, la herramienta debe cerrarse en el nivel de solicitud.
     * Este juicio no se puede dar a la palabra de aviso del modelo; de lo contrario, el modelo aún puede emitir una llamada a la herramienta por sí solo.
     */
    fun shouldDisableTools(userText: String): Boolean {
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized.containsAny(NO_TOOL_KEYWORDS)
    }

    fun shouldRequireTool(userText: String, tools: List<ToolDefinition>): Boolean {
        if (tools.isEmpty() || shouldDisableTools(userText)) return false
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return false
        if (tools.any { it.name.isNotBlank() && normalized.contains(it.name.lowercase()) }) return true
        // v2.6.2(Auditoría de herramientas): Antes de forzarlo, confirme que exista una herramienta con capacidades coincidentes. ——
        // La implementación anterior solo analizaba las palabras clave y las herramientas relacionadas estaban en la lista blanca./Reducir el filtro obligará al modelo a elegir una de las herramientas irrelevantes.
        // Agregue una llamada de herramienta sin sentido. Esto requiere que al menos una herramienta expuesta pertenezca a la misma familia (o que exista una herramienta MCP).
        if (normalized.containsAny(EXPLICIT_TOOL_KEYWORDS)) {
            return hasMatchingExposedTool(normalized, tools)
        }
        val hasMcpTools = tools.any { it.name.startsWith("mcp_") }
        return hasMcpTools &&
            !normalized.containsAny(QUESTION_KEYWORDS) &&
            normalized.containsAny(MCP_ACTION_KEYWORDS)
    }

    /** Expone la presencia o ausencia de capacidades en la herramienta que coinciden con la intención del usuario (accesos de hermanos/presencia de herramientas MCP). */
    private fun hasMatchingExposedTool(normalized: String, tools: List<ToolDefinition>): Boolean {
        val names = tools.mapTo(mutableSetOf()) { it.name }
        if (names.any { it.startsWith("mcp_") }) return true
        return TOOL_FAMILIES
            .filter { family -> family.keywords.any { normalized.contains(it.lowercase()) } }
            .any { family -> family.toolNames.any { it in names } }
    }

    /**
     * v2.0: Determinar si el usuario solicitó explícitamente una acción(intención instrumental).
     *
     * con [isSimpleToolRequest] diferente:Este último sólo describe"frases cortas",para estrechamiento de herramientas;
     * Esta función proporciona"Si comprimir esta ronda de pensamiento/Truncar maxTokens"juez,Solo debe devolver verdadero cuando el usuario realmente quiera realizar una acción.
     * De lo contrario, el usuario empezará a pensar profundamente y enviará una frase."Hola",El pensamiento puede confundirse con"Rueda de herramientas Razonamiento repetitivo"Y trágalo.
     */
    fun isDirectToolRequest(userText: String, tools: List<ToolDefinition> = emptyList()): Boolean {
        val normalized = userText.trim().lowercase()
        if (normalized.isBlank()) return false
        if (normalized.containsAny(EXPLICIT_TOOL_KEYWORDS)) return true
        val hasMcpTools = tools.any { it.name.startsWith("mcp_") }
        return hasMcpTools &&
            !normalized.containsAny(QUESTION_KEYWORDS) &&
            normalized.containsAny(MCP_ACTION_KEYWORDS)
    }

    /**
     * Limitación jerárquica predeterminada(v2.x Etapa de adelgazamiento de herramientas.1,Actualizado desde v2.0 reducción de solicitudes simples).
     *
     * La superficie de la herramienta es mayor que [SIMPLE_TOOL_SUBSET_THRESHOLD] tiempo,prensa [ToolCategories] en capas:
     *  - CORE / STANDARD:Hengfa(Base de capacidad del modelo con herramienta de estabilización normalmente abierta);
     *  - OPTIONAL:Haga clic en la familia de palabras clave, utilizada en esta sesión.(pegajoso), autorizado dentro de la sesión,o solicitud de acción explícita(Bolsillo suelto)Sólo entonces;
     *  - GLOBAL:Enviar solo cuando se acceda a la familia de palabras clave, se utilice en esta sesión y se autorice en la sesión.(No autorizado/ningún golpe);
     *  - Herramientas no registradas en categorías(MCP / Complementos/habilidades, etc.)Mantener la visibilidad original.
     *
     * Superficie del dispositivo(≤umbral)Sin moverse en absoluto — Primero se conservan semánticamente pequeñas listas de configuraciones explícitas de ayudantes.
     * Reglas puras de palabras clave, sin efectos secundarios,Evite retiros falsos e inciertos que puedan ser causados ​​por la recuperación de vectores.
     *
     * @param stickyToolNames El nombre de la herramienta que se ha llamado en este historial de sesión.(Las herramientas utilizadas permanecerán visibles en rondas posteriores.)
     * @param authorizedToolNames El usuario hizo clic en esta sesión."Esta sesión permite"Una colección de nombres de herramientas.
     * @param loadedToolNames find_tools Nombre de la herramienta cargada dinámicamente(Continuamente visible después del golpe.)
     * @param skillToolNames La colección de nombres de habilidades en esta ronda de herramientas.(La habilidad no está registrada en la clasificación ToolCategories,
     *        Estrecho con el mismo diámetro que OPCIONAL:necesidad de golpear/cargar/pegajoso/Emitido sólo después de la autorización;MCP/Complementos y otros
     *        Las herramientas dinámicas para categorías no registradas seguirán conservando su visibilidad actual.)
     */
    @Suppress("LongParameterList")
    fun filterToolsForRequest(
        userText: String,
        tools: List<ToolDefinition>,
        stickyToolNames: Set<String> = emptySet(),
        authorizedToolNames: Set<String> = emptySet(),
        loadedToolNames: Set<String> = emptySet(),
        skillToolNames: Set<String> = emptySet(),
    ): List<ToolDefinition> {
        return if (tools.size <= SIMPLE_TOOL_SUBSET_THRESHOLD) {
            tools
        } else {
            val normalized = userText.trim().lowercase()
            if (normalized.isBlank()) {
                tools
            } else {
                val matched = TOOL_FAMILIES.filter { family -> family.keywords.any { normalized.contains(it.lowercase()) } }
                val familyAllowed = matched.flatMapTo(mutableSetOf()) { it.toolNames }
                val dynamicAllowed = familyAllowed + stickyToolNames + authorizedToolNames + loadedToolNames
                // Bolsillo suelto:Solicitud de acción clara y no una simple frase → OPTIONAL Liberar toda la cola larga,Evite perder herramientas a mitad de tareas de varios pasos
                val optionalRelaxed = !isSimpleToolRequest(userText) && isDirectToolRequest(userText, tools)
                tools.filter { tool ->
                    when (ToolCategories.categoryOf(tool.name)) {
                        // Categoría no registrada(MCP / Herramientas dinámicas como complementos/habilidades):
                        //  - Habilidades:Estrecho con el mismo diámetro que OPCIONAL(Anteriormente liberado incondicionalmente,Causa cada habilidad habilitada
                        //    Enviado con solicitud en cada ronda.,Es la mayor fuente de marcadores de posición de esquemas de herramientas para este proyecto.);
                        //  - el resto(MCP / complemento)Mantener la visibilidad existente.
                        null -> tool.name !in skillToolNames || tool.name in dynamicAllowed || optionalRelaxed
                        ToolCategory.CORE, ToolCategory.STANDARD, ToolCategory.LEGACY -> true
                        ToolCategory.OPTIONAL -> tool.name in dynamicAllowed || optionalRelaxed
                        ToolCategory.GLOBAL -> tool.name in dynamicAllowed
                    }
                }
            }
        }
    }

    /** Familia de herramientas:palabras clave → Colección de herramientas relacionadas. */
    private data class ToolFamily(val keywords: Set<String>, val toolNames: Set<String>)

    private const val SIMPLE_TOOL_SUBSET_THRESHOLD = 20

    private val TOOL_FAMILIES =
        listOf(
            ToolFamily(
                keywords = setOf("Buscar", "Buscar", "Compruébalo", "Consultar información", "Redes", "Lo último", "Noticias", "search", "latest news"),
                toolNames = setOf("web_search", "web_fetch", "parse_link"),
            ),
            ToolFamily(
                keywords = setOf("clima", "temperatura", "lloviendo", "weather", "forecast"),
                toolNames = setOf("get_weather"),
            ),
            ToolFamily(
                keywords = setOf("despertador", "recordatorio", "cuenta regresiva", "Calendario", "horario", "reminder", "alarm", "timer", "calendar", "Posicionamiento", "ubicación", "where"),
                toolNames =
                setOf(
                    "set_alarm",
                    "get_location",
                    "set_timer",
                    "add_calendar_event",
                    "get_calendar_today",
                    "calendar_today",
                    "schedule_reminder",
                    "cancel_reminder",
                    "list_reminders",
                ),
            ),
            ToolFamily(
                keywords = setOf("tareas programadas", "Tareas programadas", "regularmente", "ciclo", "todos los dias", "semanal", "scheduled task", "schedule"),
                toolNames =
                setOf(
                    "scheduled_task_create",
                    "scheduled_task_list",
                    "scheduled_task_update",
                    "scheduled_task_delete",
                    "scheduled_task_execute",
                    "scheduled_task_get_history",
                ),
            ),
            ToolFamily(
                keywords = setOf("poder", "bateria", "cargando", "battery"),
                toolNames = setOf("get_battery_info", "screen_time"),
            ),
            ToolFamily(
                keywords = setOf("memoria", "almacenamiento", "Parámetros de pantalla", "resolución", "cpu", "Procesador", "sensor", "Información del dispositivo", "modelo de teléfono celular", "memory", "storage"),
                toolNames =
                setOf(
                    "get_storage_info",
                    "get_memory_info",
                    "get_display_info",
                    "get_cpu_info",
                    "get_sensors_list",
                    "get_network_info",
                ),
            ),
            ToolFamily(
                keywords = setOf("bluetooth", "wifi", "red inalámbrica", "estado de la red", "bluetooth"),
                toolNames = setOf("get_wifi_info", "get_bluetooth_devices", "toggle_wifi", "toggle_bluetooth"),
            ),
            ToolFamily(
                keywords = setOf("linterna", "destello", "brillo", "Volumen", "Vibración", "silenciar", "brightness", "volume", "flashlight", "vibrate"),
                toolNames =
                setOf(
                    "toggle_flashlight",
                    "set_brightness",
                    "get_brightness",
                    "set_volume",
                    "get_volume",
                    "vibrate",
                ),
            ),
            ToolFamily(
                keywords = setOf("¿Qué aplicaciones están instaladas?", "Aplicaciones instaladas", "Lista de aplicaciones", "notificaciones recientes", "barra de notificaciones", "Configuración del sistema", "installed apps"),
                toolNames = setOf("list_installed_apps", "get_recent_notifications", "open_system_setting"),
            ),
            ToolFamily(
                keywords = setOf("Abrir aplicación", "Iniciar aplicación", "open app", "launch app"),
                toolNames = setOf("open_app"),
            ),
            ToolFamily(
                keywords = setOf("enviar mensaje de texto", "Enviar correo electrónico", "llamar", "marcar", "send sms", "send email", "make a call"),
                toolNames = setOf("send_sms", "send_email", "make_phone_call"),
            ),
            ToolFamily(
                keywords = setOf("Navegación", "mapa", "donde ir", "ruta", "maps", "navigate to"),
                toolNames = setOf("open_maps"),
            ),
            ToolFamily(
                keywords = setOf("Persona de contacto", "libreta de direcciones", "tarjeta de visita", "contact"),
                toolNames = setOf("add_contact", "get_contacts_list", "get_contacts_count"),
            ),
            ToolFamily(
                keywords = setOf("Abrir URL", "enlace abierto", "open url", "Compartir", "share"),
                toolNames = setOf("open_url", "share_text"),
            ),
            ToolFamily(
                keywords = setOf("portapapeles", "Copiar", "Pegar", "clipboard", "Aplicación en primer plano", "foreground"),
                toolNames = setOf("clipboard_read", "clipboard_write", "get_foreground_app"),
            ),
            ToolFamily(
                keywords = setOf(
                    "memoria", "recuerda", "recuerda esto", "recuerdos", "experiencia", "memory", "olvidar", "olvidar", "forget",
                    // v2.6.7 fix (AUDIT F4): Los usuarios realizan solicitudes a largo plazo (“Nunca más…”) debería resaltar la herramienta de precipitación de preferencia.
                    "mas tarde", "no más", "no más", "hábito", "Preferencia", "preference",
                ),
                toolNames =
                setOf(
                    "pin_memory",
                    "unpin_memory",
                    "recall_experience",
                    "record_experience",
                    "search_memory",
                    "search_conversation",
                    "save_memory",
                    "delete_memory",
                    "remember_preference",
                ),
            ),
            ToolFamily(
                // v2.6.2(comentarios de los usuarios): El asistente hojea otras conversaciones —— session_* Familia de herramientas
                keywords = setOf(
                    "sesión", "Registro de conversación", "Historial de chat", "Sesión de historia", "otra conversación", "otras conversaciones", "conversaciones anteriores",
                    "mirar a través", "dale la vuelta", "charlar antes", "dicho antes", "hablamos", "session",
                ),
                toolNames =
                setOf(
                    "session_list",
                    "session_read",
                    "session_rename",
                    "session_move_folder",
                    "session_archive",
                    "session_delete",
                ),
            ),
            ToolFamily(
                keywords = setOf("Notas", "taquigrafía", "base de conocimientos", "Toma nota de ello", "note", "note down"),
                toolNames =
                setOf(
                    "quick_note_add",
                    "quick_note_list",
                    "quick_note_search",
                    "quick_note_get",
                    "quick_note_update",
                    "quick_note_delete",
                    "quick_note_pin",
                ),
            ),
            ToolFamily(
                keywords = setOf("Recursos", "Biblioteca de recursos", "Base de datos", "Guardar un recurso", "resource"),
                toolNames =
                setOf(
                    "resource_add",
                    "resource_list",
                    "resource_search",
                    "resource_get",
                    "resource_delete",
                ),
            ),
            ToolFamily(
                keywords = setOf("prueba", "echo", "eco", "test tool"),
                toolNames = setOf("echo"),
            ),
            ToolFamily(
                keywords = setOf("Notificación", "tarjeta", "Progreso", "todo", "recuérdame", "llámame", "renderizado", "render"),
                toolNames =
                setOf(
                    "notify",
                    "show_card",
                    "update_card_data",
                    "todo_write",
                    "current_status",
                    "render_data",
                    "proactive_message_wish",
                ),
            ),
            ToolFamily(
                // v2.x: token Autocontrol de dosis —— Sólo es necesario cuando el usuario pregunta "cuánto costó/cuánto contexto queda"
                keywords = setOf(
                    "token", "Dosis", "consumir", "cuanto costó", "cuanto se uso", "contexto", "ocupar", "Saldo", "costo", "usage",
                ),
                toolNames = setOf("get_token_usage"),
            ),
            ToolFamily(
                keywords = setOf("codificación", "decodificar", "base64", "picadillo", "uuid", "Contraseña", "Traducción", "translate", "encode", "decode", "hash"),
                toolNames =
                setOf(
                    "url_encode", "url_decode", "base64_encode", "base64_decode", "hash_text",
                    "generate_uuid", "random_number", "json_pretty", "generate_password", "translate",
                ),
            ),
            ToolFamily(
                // v2.x: La palabra clave debe contener "voz/voz/voz"—— find_tools presione en blanco/Utilice comas para separar palabras y luego utilice contiene.
                // Los usuarios dicen“enviar una voz”“Léelo para mí”Sólo entonces se incorporará send_voice a la herramienta de sesión actual.
                keywords = setOf(
                    "hacer un dibujo", "dibujar", "fotos", "Generar gráfico", "vídeo", "código qr", "leer en voz alta", "imagen",
                    "Voz", "hacer una voz", "Léelo", "mensaje de voz",
                    "image", "video", "qr code", "voice",
                ),
                toolNames = setOf("generate_image", "generate_video", "generate_qr_code", "speak_text", "send_voice"),
            ),
            ToolFamily(
                keywords = setOf("subagente", "chat grupal", "canal", "Emoticones", "subagent", "channel"),
                toolNames =
                setOf(
                    "subagent_task", "subagent_run", "subagent_close", "delegate_agent",
                    "channel_pass", "channel_read_context", "channel_reply", "list_stickers", "send_sticker",
                    "send_channel_message", "relay_channel_message", "channel_list",
                ),
            ),
            ToolFamily(
                keywords = setOf("Archivo", "Documentación", "pdf", "enlace", "Descargar", "file", "document", "download"),
                // v2.6.7 fix (AUDIT F4): read_user_file si“Leer un archivo en el dispositivo del usuario”entrada,
                // Cuando no complementas tu propio clan, dices“Leer este archivo”No puedo sacarlo.
                toolNames =
                setOf(
                    "read_file",
                    "read_user_file",
                    "create_download",
                    "parse_pdf",
                    "parse_link",
                    // v2.6.10 Rectificación de carga de herramientas: la herramienta de archivos del lado de habilidades no estaba registrada antes.
                    // Al decir "Descargar este archivo" no se activará la capacidad con el mismo nombre.
                    "file_download",
                    "save_to_downloads",
                ),
            ),
            ToolFamily(
                keywords = setOf("ping", "dns", "Red pública ip", "public ip"),
                toolNames = setOf("ping_host", "dns_lookup", "get_public_ip"),
            ),
            ToolFamily(
                keywords = setOf("espacio de trabajo", "Proyecto", "Ingeniería", "código", "almacén", "guión", "workspace"),
                toolNames =
                setOf(
                    "workspace_list",
                    "workspace_read",
                    "workspace_write",
                    "workspace_edit",
                    "workspace_delete",
                    "workspace_mkdir",
                    "workspace_move",
                ),
            ),
            ToolFamily(
                keywords = setOf("Navegador", "pagina web", "Abrir sitio web", "URL", "browser", "webpage"),
                toolNames =
                setOf(
                    "browser_navigate",
                    "browser_click",
                    "browser_type",
                    "browser_extract",
                    "browser_scroll_bottom",
                    "browser_get_html",
                    "browser_snapshot",
                    "browser_screenshot",
                ),
            ),
            ToolFamily(
                keywords = setOf("complemento", "plugin"),
                toolNames =
                setOf(
                    "plugin_market_search",
                    "plugin_market_install",
                    "plugin_market_uninstall",
                    "plugin_market_set_enabled",
                ),
            ),
            ToolFamily(
                keywords = setOf("terminales", "línea de comando", "shell", "adb", "termux", "Comandos del sistema", "ejecutar comando"),
                toolNames = setOf("device_shell", "terminal_exec", "termux_exec"),
            ),
            ToolFamily(
                // v2.6.1(comentarios de los usuarios): Python La solicitud no afecta a ningún clan antes. Las herramientas integradas de Python pertenecen a la categoría GLOBAL,
                // Si falta, no se enviará con la solicitud. El modelo solo ve terminal_exec/ejecutar_shell, así que vaya al shell.
                // Busque el intérprete de Python (no existe tal comando cuando se ejecuta en línea,bin vacío), ralentí repetidamente.
                keywords =
                setOf(
                    "guión",
                    "código",
                    "javascript",
                    "node",
                    "js guión",
                    "Ejecutar código",
                    "script",
                    "python",
                    "python3",
                    "python guión",
                    "pythonguión",
                ),
                toolNames =
                setOf(
                    "execute_javascript",
                    "execute_node_script",
                    "execute_python_script",
                    "execute_shell",
                ),
            ),
            ToolFamily(
                keywords = setOf("Configuración del sistema", "settings", "Iniciar sesión", "logcat", "Lista de instalación", "root Operación", "conmutador de red", "Inyección de entrada"),
                toolNames =
                setOf(
                    "settings_get",
                    "settings_put",
                    "am_start",
                    "list_packages",
                    "logcat_tail",
                    "input_inject",
                    "network_toggle",
                ),
            ),
            ToolFamily(
                keywords = setOf("pantalla", "interfaz", "Solicitud actual", "Aplicación en primer plano", "leer pantalla", "en pantalla", "screen content", "what's on screen"),
                toolNames =
                setOf(
                    "ui_get_page_info",
                    "ui_screenshot",
                    "ui_get_current_app",
                    "screen_read",
                    "screen_current_app",
                    "screen_permission_status",
                ),
            ),
            ToolFamily(
                keywords =
                setOf(
                    "clic automático", "toque la pantalla", "haga clic en el botón", "ayudame", "desliza la pantalla",
                    "Operación de interfaz", "Operación de pantalla", "Introducir texto", "Botón", "Accesibilidad",
                    "auto click", "tap", "swipe", "accessibility",
                ),
                toolNames =
                setOf(
                    "ui_click", "ui_long_press", "ui_swipe", "ui_set_text", "ui_back", "ui_home", "ui_global_action",
                    "screen_back", "screen_home", "screen_tap", "screen_tap_text", "screen_swipe", "screen_input",
                    "screen_launch_app", "screen_open_notifications", "screen_wait", "screen_pinch", "screen_swipe_path",
                ),
            ),
            ToolFamily(
                keywords = setOf("operación automática", "Proceso automatizado", "pantalla virtual", "operación de varios pasos", "agent Operación", "virtual screen", "automation workflow"),
                toolNames = setOf("automation_workflow", "ui_agent", "virtual_screen", "virtual_screen_input"),
            ),
            ToolFamily(
                keywords = setOf("conector", "oauth", "connector"),
                toolNames = setOf("connector_list", "call_connector"),
            ),
        )

    private fun String.containsAny(values: Set<String>): Boolean {
        val haystack = lowercase()
        return values.any { haystack.contains(it.lowercase()) }
    }

    private const val SIMPLE_REQUEST_MAX_CHARS = 120

    private val COMPLEXITY_KEYWORDS =
        setOf(
            "por qué",
            "análisis",
            "analiza",
            "comparar",
            "Planificar",
            "diseño",
            "explicar",
            "Detallado",
            "planificación",
            "how",
            "why",
            "analyze",
            "compare",
            "design",
            "explain",
        )
    private val NO_TOOL_KEYWORDS =
        setOf(
            "No llames a ninguna herramienta", "No llame a ninguna", "No llames a las herramientas", "No uses ninguna herramienta", "No uses herramientas",
            "Deshabilitar herramientas de llamadas", "Herramientas prohibidas", "Solo haz una lista, no llames", "Solo lista, no llames",
            "Solo haz una lista", "Sólo lista", "No ejecutar herramienta", "No ejecute ninguna herramienta",
            "do not call any tools", "don't call any tools", "do not use tools", "without using tools",
            "no tool calls", "no tools",
        )
    private val EXPLICIT_TOOL_KEYWORDS =
        setOf(
            "llamar",
            "hazlo",
            "utilizar herramientas",
            "ejecutar",
            "Buscar",
            "Consultar información",
            "Compruébalo",
            "Redes",
            "Lo último",
            "Noticias",
            "clima",
            "recuerda",
            "guardar en la memoria",
            "Configurar el despertador",
            "Establecer recordatorio",
            "cuenta regresiva",
            "enviar mensaje de texto",
            "Enviar correo electrónico",
            "Abrir aplicación",
            "Traducción",
            "hacer un dibujo",
            "Generar imágenes",
            "eco",
            "echo",
            "calculator",
            "calculate",
            "current time",
            "what time",
        )
    private val QUESTION_KEYWORDS = setOf("como", "como", "que es", "puede", "Ya sea", "por qué", "how", "what", "why")
    private val MCP_ACTION_KEYWORDS =
        setOf(
            "crear", "Nuevo", "Nuevo", "agregar", "crear", "construir uno", "Consulta", "Compruébalo", "encontrar", "leer", "léelo",
            "lista", "Obtener", "actualizar", "Modificar", "Editar", "Eliminar", "Enviar", "sincronizar", "enviar", "publicar", "escribir",
            "create", "list", "get", "read", "update", "delete", "send", "open", "add", "edit", "publish", "sync",
        )
}
