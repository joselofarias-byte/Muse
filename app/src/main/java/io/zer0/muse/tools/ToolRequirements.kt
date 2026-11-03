package io.zer0.muse.tools

/**
 * v2.6.7: Los canales del dispositivo requeridos por la herramienta.
 *
 * ## ¿Por qué necesitas esta mesa?
 *
 * La principal queja del autoexamen del Asistente es “Hay 206 herramientas, pero no sé cuál es realmente útil”:
 * `ToolDef` Solo existe RiskLevel (nivel de riesgo) y no hay ningún campo que indique "de qué canal depende esta herramienta".
 * Así que sólo pude probar los modelos uno por uno. Sólo cuando choqué contra una pared supe que no tenía permiso. —— que"no proporcionado en absoluto"Duele aún más la experiencia.
 *
 * Esta tabla hace explícita la "dependencia", lo que permite que tool_status filtre la lista verdaderamente invocable según los permisos actuales.
 *
 * ## mantener la disciplina
 *
 * Al agregar nuevas herramientas que requieren canales de dispositivos**debe**Regístrese aquí; de lo contrario, tool_status lo informará falsamente como disponible.
 * Las herramientas no registradas están predeterminadas en [Requirement.NONE] Procesamiento (herramienta en la aplicación, no se requieren permisos).
 */
object ToolRequirements {

    /** El tipo de canal del que depende la herramienta. */
    enum class Channel {
        /** Servicios de accesibilidad (lectura de pantalla, gestos, entrada). */
        ACCESSIBILITY,

        /** Shizuku / adb shell. */
        SHELL,

        /** Root. */
        ROOT,

        /** Acceso de uso (UsageStats). */
        USAGE_STATS,

        /** Notificación de derechos de uso. */
        NOTIFICATION_LISTENER,

        /** Contacto leído. */
        CONTACTS,

        /** SMS. */
        SMS,

        /** llamar. */
        PHONE_CALL,

        /** Calendario. */
        CALENDAR,

        /** Posicionamiento. */
        LOCATION,

        /** Permisos de tiempo de ejecución de cámara/micrófono, etc. */
        RUNTIME,
    }

    /**
     * Descripción de dependencia de una herramienta.
     *
     * @param anyOf satisfacerlo**cualquiera**Se puede llamar al canal (como operación de pantalla: sin barreras o Shell)
     * @param allOf debe**al mismo tiempo**Satisfecho (por ejemplo, la instalación silenciosa requiere Shell y leer la lista de paquetes)
     * @param note Instrucciones complementarias para modelos (cómo abrir cuando faltan permisos)
     */
    data class Requirement(
        val anyOf: Set<Channel> = emptySet(),
        val allOf: Set<Channel> = emptySet(),
        val note: String = "",
    ) {
        val isUnconditional: Boolean get() = anyOf.isEmpty() && allOf.isEmpty()
    }

    /** No se requiere ningún canal de dispositivo —— Herramientas en la aplicación (memoria/tarjeta/sesión/base de conocimientos, etc.). */
    private val NONE = Requirement()

    /** Funcionamiento de la pantalla: Disponible sin barreras o Shell. */
    private val SCREEN = Requirement(
        anyOf = setOf(Channel.ACCESSIBILITY, Channel.SHELL),
        note = "Requiere Servicios de Accesibilidad o Shizuku/ADB; también disponible en Configuración→Habilitar el servicio Muse en accesibilidad",
    )

    /** Shell Operación de nivel: Debe ser Shell o Root. */
    private val SHELL_ONLY = Requirement(
        anyOf = setOf(Channel.SHELL, Channel.ROOT),
        note = "Requiere Shizuku o Root;Shizuku Primero debe instalarse y autorizarse Muse",
    )

    /** Tabla de mapeo: nombre de la herramienta → Dependencia. Los que no figuran en la lista se consideran [NONE]. */
    private val TABLE: Map<String, Requirement> = buildMap {
        // ── Operación de pantalla (accesible/shell cualquiera)──
        listOf(
            "screen_read", "screen_tap", "screen_tap_text", "screen_swipe", "screen_swipe_path",
            "screen_pinch", "screen_wait", "screen_back", "screen_home", "screen_current_app",
            "screen_input", "screen_launch_app", "screen_open_notifications",
            "ui_agent", "input_inject", "virtual_screen", "virtual_screen_input",
            "automation_workflow",
        ).forEach { put(it, SCREEN) }

        // ── Shell nivel ──
        listOf(
            "device_shell", "am_start", "app_force_stop", "app_clear_data", "app_uninstall",
            "list_packages", "list_installed_apps", "network_toggle", "toggle_wifi",
            "toggle_bluetooth", "logcat_tail", "set_brightness", "set_volume",
            "open_system_setting", "app_settings",
        ).forEach { put(it, SHELL_ONLY) }

        // ── Permisos de tiempo de ejecución individuales ──
        put("screen_time", Requirement(anyOf = setOf(Channel.USAGE_STATS), note = "Se requiere permiso de acceso"))
        put("get_foreground_app", Requirement(anyOf = setOf(Channel.USAGE_STATS), note = "Se requiere permiso de acceso"))
        put(
            "get_recent_notifications",
            Requirement(anyOf = setOf(Channel.NOTIFICATION_LISTENER), note = "Se requiere \"aviso de derechos de uso\""),
        )
        listOf("get_contacts_list", "get_contacts_count", "add_contact").forEach {
            put(it, Requirement(anyOf = setOf(Channel.CONTACTS), note = "Requiere permiso de \"Contactos\""))
        }
        put("send_sms", Requirement(allOf = setOf(Channel.SMS), note = "Requiere permiso \"SMS\""))
        put("make_phone_call", Requirement(allOf = setOf(Channel.PHONE_CALL), note = "Requiere permiso \"Teléfono\""))
        listOf("add_calendar_event", "calendar_today", "list_reminders").forEach {
            put(it, Requirement(anyOf = setOf(Channel.CALENDAR), note = "Requiere permiso \"Calendario\""))
        }
        listOf("get_location").forEach {
            put(it, Requirement(anyOf = setOf(Channel.LOCATION), note = "Requiere permiso de \"Ubicación\""))
        }

        // ── NINGUNO explícito (en la aplicación, escrito para que los lectores sepan que se han verificado)──
        listOf(
            "show_card", "update_card_data", "render_data", "notify", "todo_write",
            "save_memory", "pin_memory", "delete_memory", "unpin_memory", "search_memory",
            "recall_experience", "record_experience", "search_conversation",
            "current_status", "runtime_selfcheck", "platform_selfcheck", "find_tools",
            // v2.x: token Autocomprobación del uso (todos los datos de la aplicación, sin permisos del sistema)
            "get_token_usage",
            "calculator", "base64_encode", "base64_decode", "url_encode", "url_decode",
            "hash_text", "generate_uuid", "generate_password", "json_pretty", "random_number",
            "get_current_time", "translate", "speak_text", "share_text", "vibrate",
            "schedule_reminder", "cancel_reminder", "set_alarm", "set_timer",
            "scheduled_task_create", "scheduled_task_update", "scheduled_task_delete",
            "scheduled_task_list", "scheduled_task_get_history", "scheduled_task_execute",
            "session_list", "session_read", "session_rename", "session_delete", "session_archive",
            "session_move_folder", "channel_reply", "channel_pass", "channel_read_context",
            "subagent_task", "subagent_run", "subagent_close", "delegate_agent",
            "generate_image", "generate_video", "generate_qr_code",
            // v2.x: mensaje de voz——Síntesis TTS local + colgar mensajes propios,No toques los permisos del sistema.
            "send_voice",
        ).forEach { put(it, NONE) }
    }

    /** Consultar las dependencias de una herramienta. No registrado = NONE(herramienta en la aplicación). */
    fun of(toolName: String): Requirement = TABLE[toolName] ?: NONE

    /** Nombre de herramienta registrada (para autoprueba/Cobertura de verificación de prueba). */
    fun registeredNames(): Set<String> = TABLE.keys

    /**
     * Determine si la herramienta es realmente invocable en función de los canales disponibles actualmente.
     *
     * @param availableChannels La colección de canales actualmente listos.
     */
    fun isUsable(toolName: String, availableChannels: Set<Channel>): Boolean {
        val req = of(toolName)
        // Una sola expresión evita múltiples devoluciones (ReturnCount):
        // AllOf debe satisfacerse al mismo tiempo;anyOf Si no está vacío, al menos uno debe acertar; si ambos están vacíos, se puede utilizar incondicionalmente.
        val allSatisfied = req.allOf.all { it in availableChannels }
        val anySatisfied = req.anyOf.isEmpty() || req.anyOf.any { it in availableChannels }
        return allSatisfied && anySatisfied
    }
}
