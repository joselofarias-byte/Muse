package io.zer0.muse.web

import io.zer0.muse.feature.FeatureFlags

/**
 * Establecer la constante del nombre de la clave.
 *
 * El nombre de la clave también está en settings.snapshot [HostSettingsEntry.toggleKey] / [HostSettingToggle.key] valor;
 * El conjunto de claves grabables no está aquí, pero está representado por [HostSettingDefinitions] Derivado de la misma definición (el elemento de valor tiene el mismo origen que la lista blanca).
 */
internal object HostSettingKeys {
    const val PII_GUARD = "piiGuardEnabled"
    const val PROACTIVE_MESSAGE = "proactiveMessageEnabled"
    const val KEEP_ALIVE = "keepAliveEnabled"
    const val MINI_PHONE = "miniPhoneEnabled"
    const val MEMORY = "memoryEnabled"
    const val SCHEDULE_WORK = "scheduleWorkEnabled"
    const val PLUGINS = "pluginsGloballyEnabled"
    const val DYNAMIC_COLOR = "dynamicColor"
    const val HIGH_CONTRAST = "highContrast"
    const val TOKEN_ESTIMATE = "tokenEstimateEnabled"
    const val CHAT_ENTER_TO_SEND = "chatPreferences.enterToSend"
    const val CHAT_STREAM_RESPONSE = "chatPreferences.streamResponse"

    /** Interruptores de uso común:Configure las claves leídas en el área \"Interruptores de uso común\" de la página de inicio,El orden es el orden de presentación. */
    val readable: List<String> = listOf(
        PII_GUARD,
        PROACTIVE_MESSAGE,
        KEEP_ALIVE,
        MINI_PHONE,
        MEMORY,
        SCHEDULE_WORK,
        PLUGINS,
        CHAT_ENTER_TO_SEND,
        CHAT_STREAM_RESPONSE,
        DYNAMIC_COLOR,
        HIGH_CONTRAST,
        TOKEN_ESTIMATE,
    )
}

/**
 * Establezca la agrupación y la entrada de la lista (lado del protocolo).
 *
 * El orden y el título de la interfaz y el índice de búsqueda provienen de [io.zer0.muse.ui.settings.SettingsCatalog];
 * Este objeto es una proyección de la misma estructura para uso web. La coherencia entre ambos está determinada por `HostSettingsCatalogTest`
 * Comparación grupo por artículo —— Si se cambia la agrupación, el orden o las entradas en cualquiera de los lados, la prueba fallará.Web No tienes que (y no puedes)
 * Mantenga su propia lista de cosas que cambian con su aplicación.
 */
internal object HostSettingsCatalog {

    /** La clave del grupo es el nombre del recurso de cadena del título del grupo.,Organizado por interfaz de arriba a abajo. */
    val groups: List<HostSettingsGroup> = listOf(
        group(
            "settings_group_assistants",
            link("user_profile", "settings_user_profile_title", "settings_screen_desc_user_profile", "user"),
            link("assistants", "settings_screen_assistant", "settings_screen_assistant_desc", "atom"),
            link("assistant_resources", "settings_screen_assistant_resources", "settings_screen_assistant_resources_desc", "stars"),
            link("agent", "chat_agent_tab_title", "settings_screen_agent_desc", "users"),
        ),
        group(
            "settings_group_chat",
            link("chat", "settings_screen_chat", "settings_screen_chat_desc", "chat"),
            link("stickers", "settings_chat_sticker_section", "settings_screen_sticker_desc", "image"),
            link("quick_notes", "settings_screen_quick_notes", "settings_screen_quick_notes_desc", "bulb"),
        ),
        group(
            "settings_group_models",
            link("provider", "settings_screen_provider", "settings_screen_provider_desc", "provider"),
            link("task_routing", "settings_task_routing_title", "settings_screen_task_routing_desc", "taskRouting"),
            link("provider_plugins", "provider_plugins_title", "settings_screen_desc_provider_plugins", "affiliate"),
        ),
        group(
            "settings_group_tools",
            link("tools", "settings_screen_tools", "settings_screen_tools_desc", "wrench"),
            link("permission_wizard", "permission_wizard_title", "permission_wizard_desc", "shieldCheck"),
            link("web_search", "settings_screen_web_search", "settings_screen_web_search_desc", "globe"),
        ),
        group(
            "settings_group_voice",
            link("tts", "settings_screen_media", "settings_screen_media_desc", "microphone"),
            link("asr", "settings_screen_asr", "settings_screen_asr_desc", "microphone"),
            link("voice_cloning", "voice_cloning_title", "settings_screen_desc_voice_cloning", "play"),
        ),
        group(
            "settings_group_image",
            link("image_video_gen", "video_gen_title", "settings_screen_desc_video_gen", "video"),
            link("vision", "settings_vision_title", "settings_screen_desc_vision", "image"),
            link("translate", "settings_screen_translate", "settings_screen_translate_desc", "languages"),
        ),
        group(
            "settings_group_memory",
            link("memory", "settings_screen_memory_notification", "settings_screen_memory_notification_desc", "atom"),
            link("rag", "settings_screen_rag", "settings_screen_rag_desc", "book"),
        ),
        group(
            "settings_group_connection",
            link("connection_center", "connection_center_title", "connection_center_desc", "link"),
            link("channel", "channel_page_title", "settings_screen_desc_channel", "wifi"),
            link("web_access", "settings_screen_web_access", "settings_screen_web_access_desc", "router"),
            link("mcp", "settings_screen_mcp", "settings_screen_mcp_desc", "plug"),
            // La descripción de la entrada del agente la escribe el tiempo de ejecución.(No habilitado / type host:port),No hay recursos fijos.
            link("proxy", "proxy_title", null, "proxy"),
        ),
        group(
            "settings_group_automation",
            link("proactive", "settings_agent_proactive_title", "settings_agent_proactive_subtitle", "bell"),
            link("scheduled_tasks", "schedule_title", "settings_screen_desc_schedule", "clock"),
            link(
                "notification_listener",
                "settings_screen_notification_listener",
                "settings_screen_notification_listener_desc",
                "bell",
            ),
            link("ui_automation", "automation_settings_title", "automation_settings_subtitle", "computer"),
            link("keep_alive", "settings_keep_alive_title", "settings_keep_alive_subtitle", "battery"),
        ),
        group(
            "settings_group_appearance",
            link("appearance", "settings_screen_appearance_label", "settings_screen_appearance_desc", "palette"),
            link(
                "miniphone",
                "settings_miniphone_title",
                "settings_screen_miniphone_desc",
                "deviceMobile",
                enabled = FeatureFlags.MINI_PHONE,
            ),
        ),
        group(
            "settings_group_data_storage",
            link("data_management", "data_management_entry", "data_management_entry_desc", "database"),
            link("workspace", "workspace_title", "workspace_desc", "folder"),
            link("backup", "settings_screen_data_backup", "settings_screen_data_backup_desc", "cloud"),
            link("data_import", "settings_screen_data_import", "settings_screen_data_import_desc", "cloudUpload"),
            link("archived_chats", "settings_screen_entry_archived", "settings_screen_archived_desc", "archive"),
        ),
        group(
            "settings_group_privacy_security",
            toggle(
                "pii_guard",
                "settings_screen_pii_guard",
                "settings_screen_pii_guard_desc",
                "shield",
                HostSettingKeys.PII_GUARD,
            ),
            link("share", "settings_screen_share", "settings_screen_security_desc", "lock"),
        ),
        group(
            "settings_group_about",
            link("tutorial", "settings_screen_tutorial", "settings_screen_tutorial_desc", "school"),
            link("about", "settings_screen_about", "settings_screen_about_desc", "info"),
            action("check_update", "settings_screen_check_update", "settings_screen_check_update_desc", "refresh"),
        ),
        group(
            "settings_group_developer",
            link("stats", "settings_screen_stats", "settings_screen_stats_desc", "chartBar"),
            link("experiments", "settings_screen_experiments", "settings_screen_experiments_desc", "flask"),
            link("debug_log", "settings_screen_debug_log", "settings_screen_debug_log_desc", "bug"),
            link("crash_report", "settings_crash_page_title", "settings_screen_desc_crash_report", "shieldCheck"),
            link("audit_log", "settings_audit_log", "settings_screen_desc_audit_log", "history"),
        ),
    )

    /** Vista plana de todas las entradas.,El orden es coherente con la representación de la interfaz. */
    val entries: List<HostSettingsEntry> = groups.flatMap { it.entries }

    private fun group(key: String, vararg entries: HostSettingsEntry): HostSettingsGroup =
        HostSettingsGroup(key = key, entries = entries.map { it.copy(groupKey = key) })

    private fun link(
        id: String,
        titleKey: String,
        descKey: String?,
        iconKey: String,
        enabled: Boolean = true,
    ): HostSettingsEntry = HostSettingsEntry(
        id = id,
        titleKey = titleKey,
        descKey = descKey,
        groupKey = "",
        iconKey = iconKey,
        kind = KIND_LINK,
        enabled = enabled,
    )

    private fun action(
        id: String,
        titleKey: String,
        descKey: String?,
        iconKey: String,
    ): HostSettingsEntry = HostSettingsEntry(
        id = id,
        titleKey = titleKey,
        descKey = descKey,
        groupKey = "",
        iconKey = iconKey,
        kind = KIND_ACTION,
    )

    private fun toggle(
        id: String,
        titleKey: String,
        descKey: String?,
        iconKey: String,
        settingKey: String,
    ): HostSettingsEntry = HostSettingsEntry(
        id = id,
        titleKey = titleKey,
        descKey = descKey,
        groupKey = "",
        iconKey = iconKey,
        kind = KIND_SWITCH,
        toggleKey = settingKey,
    )

    const val KIND_LINK = "link"
    const val KIND_SWITCH = "switch"
    const val KIND_ACTION = "action"
}
