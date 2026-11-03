package io.zer0.muse.ui.chat

import android.content.res.Resources
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.0.80: Metadatos visuales para llamadas a herramientas. — Icono + resumen de persona + etiqueta china.
 *
 * Cambie el estado original del ícono Build/Check original que comparten todas las herramientas.,Reemplazado con el ícono semántico propio de cada herramienta.,
 * y generar una oración para el estado plegado"que hizo"resumen chino de,Nombre de herramienta básica alternativa+antes40Resultados de palabras.
 *
 * Todos los iconos provienen de Tabler Icons(El proyecto depende de);Las herramientas perdidas vuelven al ícono de llave universal,No se informa ningún error.
 */
internal object ToolCallVisuals {

    /** Obtener icono de herramienta(Señorita recurrir a la llave universal). */
    fun iconFor(toolName: String): ImageVector = mapping[toolName] ?: prefixIcon(toolName) ?: MuseIcons.wrench

    /** Un resumen de una frase del estado plegado.I18N-01: Pasar en res para obtener recursos.(zh+en Ya entré strings_tools,Otros idiomas en lotes). */
    fun summaryFor(toolName: String, arguments: String, result: String, isSuccess: Boolean, res: Resources? = null): String {
        // v2.0.1: Utilice la semántica "nombre de operación + error" cuando se produzca un error — Deja de reutilizar verbos de éxito
        // (Comentarios de los usuarios: la tarjeta de herramientas para el tiempo de espera de aprobación todavía dice“Espacio de trabajo escrito xxx”).
        if (!isSuccess) {
            val failLabel = res?.let { r ->
                labelIds[toolName]?.let { r.getString(it) }
            } ?: labels[toolName] ?: prettify(toolName)
            // res==null El resultado final(Entorno no Android):Devolver solo etiquetas,Sin el sufijo de error.
            return res?.getString(R.string.tool_summary_failed, failLabel) ?: failLabel
        }
        val verb = res?.let { r ->
            successVerbIds[toolName]?.let { id -> r.getString(id) }
                ?: prefixVerbId(toolName)?.let { id -> r.getString(id) }
                ?: r.getString(R.string.tool_summary_default_success)
        } ?: (successVerb[toolName] ?: prefixVerb(toolName) ?: defaultVerb(true))
        var obj = targetFor(toolName, arguments, result)
        // v2.3.1: find_tools Sin consulta, significa "enumerar todas las herramientas".—— Profundizando en recursos de redacción publicitaria;
        // aquí(en vez de targetFor)tomar,Evite cambiar la firma targetFor, lo que causa una discrepancia en la firma de la línea base de Detekt.
        if (obj.isBlank() && toolName == "find_tools") {
            obj = res?.getString(R.string.tool_target_all_tools).orEmpty()
        }
        return if (obj.isBlank()) verb else "$verb $obj"
    }

    /** Etiqueta de herramienta ampliada(Resulta que el inglés se muestra directamente. toolName).I18N-01: Lo mismo [summaryFor]. */
    fun labelFor(toolName: String, res: Resources? = null): String {
        val localized = res?.let { r -> labelIds[toolName]?.let { id -> r.getString(id) } }
        return localized ?: labels[toolName] ?: prettify(toolName)
    }

    /**
     * Resumen del estado final: los tiempos de espera y las interrupciones son directamente visibles en el estado colapsado.
     *
     * Devolver nulo indica que el resultado no es el estado final sintetizado por el orquestador, y la persona que llama debe recurrir a [summaryFor].
     */
    fun terminalSummaryFor(result: String, res: Resources? = null): String? {
        val text = result.trimStart()
        return when {
            // I18N: La conclusión es sólo res==null(Vista previa/función pura)tiempo para alcanzar,El camino de la producción siempre requiere recursos.
            text.startsWith("[tiempo de espera]") -> res?.getString(R.string.tool_terminal_timeout) ?: ""
            text.startsWith("[interrumpir]") -> res?.getString(R.string.tool_terminal_interrupted) ?: ""
            else -> null
        }
    }

    // ── mapa de iconos ─────────────────────────────────────────────────────────

    private val mapping: Map<String, ImageVector> = mapOf(
        // Búsqueda / Conocimiento
        "web_search" to MuseIcons.globe,
        "search_memory" to MuseIcons.search,
        "pin_memory" to MuseIcons.star,
        "unpin_memory" to MuseIcons.star,
        "recall_experience" to MuseIcons.book,
        "record_experience" to MuseIcons.book,
        // Archivo/espacio de trabajo
        "read_file" to MuseIcons.file,
        "write_file" to MuseIcons.edit,
        "list_files" to MuseIcons.folder,
        "workspace_write" to MuseIcons.edit,
        "workspace_edit" to MuseIcons.edit,
        "workspace_read" to MuseIcons.file,
        // código/terminal
        "execute_code" to MuseIcons.code,
        "execute_javascript" to MuseIcons.braces,
        "execute_node_script" to MuseIcons.terminal,
        "execute_shell" to MuseIcons.terminal,
        "run_command" to MuseIcons.terminal,
        "json_pretty" to MuseIcons.braces,
        // Navegador/Web
        "open_url" to MuseIcons.browser,
        "browser_navigate" to MuseIcons.browser,
        "browser_click" to MuseIcons.browser,
        "browser_type" to MuseIcons.browser,
        "browser_snapshot" to MuseIcons.browser,
        "ping_host" to MuseIcons.activity,
        "dns_lookup" to MuseIcons.activity,
        "get_public_ip" to MuseIcons.globe,
        "download" to MuseIcons.download,
        // Generar medios
        "generate_image" to MuseIcons.image,
        "generate_video" to MuseIcons.video,
        "cover_generation" to MuseIcons.image,
        // Hora/Calendario/Recordatorio
        "get_current_time" to MuseIcons.clock,
        "calendar_today" to MuseIcons.calendar,
        "add_calendar_event" to MuseIcons.calendar,
        "schedule_reminder" to MuseIcons.bell,
        "cancel_reminder" to MuseIcons.bell,
        "list_reminders" to MuseIcons.bell,
        "set_alarm" to MuseIcons.bell,
        "set_timer" to MuseIcons.clock,
        "scheduled_task_create" to MuseIcons.calendar,
        "scheduled_task_list" to MuseIcons.calendar,
        "scheduled_task_update" to MuseIcons.calendar,
        "scheduled_task_delete" to MuseIcons.calendar,
        "scheduled_task_execute" to MuseIcons.calendar,
        "scheduled_task_get_history" to MuseIcons.calendar,
        // Sistema/Dispositivo
        "get_device_info" to MuseIcons.server,
        "get_battery_info" to MuseIcons.server,
        "get_storage_info" to MuseIcons.server,
        "get_memory_info" to MuseIcons.server,
        "get_cpu_info" to MuseIcons.server,
        "get_display_info" to MuseIcons.server,
        "get_network_info" to MuseIcons.server,
        "get_wifi_info" to MuseIcons.wifi,
        "toggle_wifi" to MuseIcons.wifi,
        "toggle_bluetooth" to MuseIcons.rss,
        "get_bluetooth_devices" to MuseIcons.rss,
        "list_installed_apps" to MuseIcons.packageIcon,
        "open_app" to MuseIcons.packageIcon,
        "open_system_setting" to MuseIcons.sliders,
        "get_foreground_app" to MuseIcons.packageIcon,
        "get_sensors_list" to MuseIcons.activity,
        "get_brightness" to MuseIcons.sun,
        "set_brightness" to MuseIcons.sun,
        "get_volume" to MuseIcons.volume,
        "set_volume" to MuseIcons.volume,
        "toggle_flashlight" to MuseIcons.bulb,
        "vibrate" to MuseIcons.phone,
        "screen_time" to MuseIcons.clock,
        // Teléfono/SMS/Contactos
        "make_phone_call" to MuseIcons.phone,
        "send_sms" to MuseIcons.chat,
        "get_contacts_count" to MuseIcons.users,
        "get_contacts_list" to MuseIcons.users,
        "add_contact" to MuseIcons.users,
        "get_location" to MuseIcons.mapPin,
        "open_maps" to MuseIcons.mapPin,
        "share_text" to MuseIcons.send,
        "send_email" to MuseIcons.mail,
        "get_recent_notifications" to MuseIcons.bell,
        // portapapeles
        "clipboard_read" to MuseIcons.clipboard,
        "clipboard_write" to MuseIcons.clipboard,
        // Notas
        "quick_note_add" to MuseIcons.note,
        "quick_note_list" to MuseIcons.note,
        "quick_note_get" to MuseIcons.note,
        "quick_note_update" to MuseIcons.note,
        "quick_note_delete" to MuseIcons.note,
        "quick_note_pin" to MuseIcons.note,
        // Recursos/Base de conocimientos
        "resource_add" to MuseIcons.database,
        "resource_list" to MuseIcons.database,
        "resource_search" to MuseIcons.database,
        "resource_get" to MuseIcons.database,
        "resource_delete" to MuseIcons.database,
        // Multiagente/Delegación
        "delegate_agent" to MuseIcons.hierarchy,
        "subagent_task" to MuseIcons.hierarchy,
        "subagent_run" to MuseIcons.hierarchy,
        "subagent_close" to MuseIcons.hierarchy,
        "channel_pass" to MuseIcons.hierarchy,
        "channel_reply" to MuseIcons.hierarchy,
        "channel_read_context" to MuseIcons.hierarchy,
        // Habilidades/Complementos / MCP
        "skill_import" to MuseIcons.puzzle,
        "skill_run" to MuseIcons.puzzle,
        "plugin_market_search" to MuseIcons.search,
        "plugin_market_install" to MuseIcons.puzzle,
        "plugin_market_uninstall" to MuseIcons.trash,
        "plugin_market_set_enabled" to MuseIcons.power,
        "find_tools" to MuseIcons.search,
        "device_shell" to MuseIcons.terminal,
        "terminal_exec" to MuseIcons.terminal,
        "termux_exec" to MuseIcons.terminal,
        "mcp_tool" to MuseIcons.plug,
        // Notificaciones/Mensajes activos/Tarjetas
        "notify" to MuseIcons.bell,
        "proactive_message_wish" to MuseIcons.wand,
        "show_card" to MuseIcons.wand,
        "current_status" to MuseIcons.info,
        // Varios
        "calculator" to MuseIcons.calculator,
        "echo" to MuseIcons.chat,
        "translate" to MuseIcons.languages,
        "speak_text" to MuseIcons.microphone,
        "get_weather" to MuseIcons.cloud,
        "todo_write" to MuseIcons.check,
        "url_encode" to MuseIcons.code,
        "url_decode" to MuseIcons.code,
        "base64_encode" to MuseIcons.code,
        "base64_decode" to MuseIcons.code,
        "hash_text" to MuseIcons.shield,
        "generate_uuid" to MuseIcons.code,
        "generate_password" to MuseIcons.shield,
        "random_number" to MuseIcons.calculator,
        "wallet_balance" to MuseIcons.wallet,
        "take_photo" to MuseIcons.camera,
    )

    private fun prefixIcon(toolName: String): ImageVector? = when {
        toolName.startsWith("browser_") -> MuseIcons.browser
        toolName.startsWith("workspace_") -> MuseIcons.file
        toolName.startsWith("scheduled_task_") -> MuseIcons.calendar
        toolName.startsWith("quick_note_") -> MuseIcons.note
        toolName.startsWith("resource_") -> MuseIcons.database
        toolName.startsWith("subagent_") -> MuseIcons.hierarchy
        toolName.startsWith("channel_") -> MuseIcons.hierarchy
        toolName.startsWith("clipboard_") -> MuseIcons.clipboard
        toolName.startsWith("mcp_") -> MuseIcons.plug
        else -> null
    }

    // ── verbo ─────────────────────────────────────────────────────────────

    private val successVerb: Map<String, String> = mapOf(
        "web_search" to "Busqué en la web",
        "search_memory" to "memoria recuperada",
        "pin_memory" to "Memoria fijada",
        "unpin_memory" to "Cancelar memoria fijada",
        "read_file" to "leer archivo",
        "write_file" to "Archivo escrito",
        "list_files" to "archivos listados",
        "workspace_write" to "Espacio de trabajo escrito",
        "execute_code" to "código ejecutado",
        "execute_javascript" to "Guión ejecutado",
        "execute_shell" to "Ejecutó el comando",
        "run_command" to "ejecutó el comando",
        "open_url" to "Abrió la página web.",
        "generate_image" to "imagen generada",
        "generate_video" to "Vídeo generado",
        "get_current_time" to "consultar la hora",
        "calendar_today" to "Calendario visto",
        "add_calendar_event" to "Evento de calendario agregado",
        "schedule_reminder" to "Conjunto de recordatorio",
        "cancel_reminder" to "Recordatorio cancelado",
        "list_reminders" to "Recordatorios enumerados",
        "set_alarm" to "alarma configurada",
        "set_timer" to "temporizador iniciado",
        "get_device_info" to "Leer información del dispositivo",
        "get_battery_info" to "Leer el nivel de la batería",
        "get_storage_info" to "Leer el almacenamiento",
        "get_memory_info" to "leer memoria",
        "get_cpu_info" to "leer CPU",
        "get_network_info" to "leer la red",
        "list_installed_apps" to "Aplicaciones enumeradas",
        "open_app" to "Abrió la aplicación",
        "make_phone_call" to "hacer una llamada",
        "send_sms" to "mensaje de texto enviado",
        "get_contacts_list" to "Leer el contacto",
        "add_contact" to "Contacto añadido",
        "get_location" to "Tengo ubicación",
        "open_maps" to "Abrió el mapa",
        "share_text" to "texto compartido",
        "send_email" to "Correo electrónico enviado",
        "get_recent_notifications" to "Notificación leída",
        "clipboard_read" to "lectura del portapapeles",
        "clipboard_write" to "Escrito en el portapapeles",
        "quick_note_add" to "Creó una nueva nota",
        "quick_note_list" to "Notas enumeradas",
        "quick_note_get" to "leer la nota",
        "quick_note_update" to "Nota actualizada",
        "quick_note_delete" to "Nota eliminada",
        "quick_note_pin" to "nota fijada",
        "resource_add" to "Recursos agregados",
        "resource_list" to "recursos enumerados",
        "resource_search" to "Recursos recuperados",
        "resource_get" to "Lectura de recursos",
        "resource_delete" to "Recurso eliminado",
        "delegate_agent" to "Sub-Asistente Delegado",
        "subagent_task" to "Subtarea iniciada",
        "subagent_run" to "subtarea ejecutada",
        "subagent_close" to "Subtarea cerrada",
        "notify" to "Notificación enviada",
        "proactive_message_wish" to "Anota el mensaje proactivo",
        "show_card" to "tarjeta mostrada",
        "current_status" to "Comprobado el estado",
        "calculator" to "calcular",
        "translate" to "Traducción",
        "speak_text" to "leer texto en voz alta",
        "get_weather" to "comprobó el clima",
        "todo_write" to "Tareas pendientes actualizadas",
        "ping_host" to "Ping anfitrión",
        "dns_lookup" to "DNS Consulta",
        "get_public_ip" to "Consultar la red pública IP",
        "download" to "Archivo descargado",
        "json_pretty" to "formateado JSON",
        "hash_text" to "hash calculado",
        "generate_password" to "Contraseña generada",
        "record_experience" to "experiencias registradas",
        "recall_experience" to "Recordando la experiencia",
        "take_photo" to "tomó fotos",
        "cover_generation" to "Portada generada",
    )

    // I18N-01: recursos verbales ID(La vía de producción tiene prioridad;successVerb para res==null Redacción de textos publicitarios consecutivos,Las nuevas herramientas solo registran recursos;es/ko/ja/pt/ru La traducción se completa en lotes.).
    private val successVerbIds: Map<String, Int> = mapOf(
        "web_search" to R.string.tool_summary_web_search,
        "search_memory" to R.string.tool_summary_search_memory,
        "pin_memory" to R.string.tool_summary_pin_memory,
        "unpin_memory" to R.string.tool_summary_unpin_memory,
        "read_file" to R.string.tool_summary_read_file,
        "write_file" to R.string.tool_summary_write_file,
        "list_files" to R.string.tool_summary_list_files,
        "workspace_write" to R.string.tool_summary_workspace_write,
        "workspace_edit" to R.string.tool_summary_workspace_edit,
        "execute_code" to R.string.tool_summary_execute_code,
        "execute_javascript" to R.string.tool_summary_execute_javascript,
        "execute_node_script" to R.string.tool_summary_execute_javascript,
        "execute_shell" to R.string.tool_summary_execute_shell,
        "run_command" to R.string.tool_summary_run_command,
        "open_url" to R.string.tool_summary_open_url,
        "generate_image" to R.string.tool_summary_generate_image,
        "generate_video" to R.string.tool_summary_generate_video,
        "get_current_time" to R.string.tool_summary_get_current_time,
        "calendar_today" to R.string.tool_summary_calendar_today,
        "add_calendar_event" to R.string.tool_summary_add_calendar_event,
        "schedule_reminder" to R.string.tool_summary_schedule_reminder,
        "cancel_reminder" to R.string.tool_summary_cancel_reminder,
        "list_reminders" to R.string.tool_summary_list_reminders,
        "set_alarm" to R.string.tool_summary_set_alarm,
        "set_timer" to R.string.tool_summary_set_timer,
        "get_device_info" to R.string.tool_summary_get_device_info,
        "get_battery_info" to R.string.tool_summary_get_battery_info,
        "get_storage_info" to R.string.tool_summary_get_storage_info,
        "get_memory_info" to R.string.tool_summary_get_memory_info,
        "get_cpu_info" to R.string.tool_summary_get_cpu_info,
        "get_network_info" to R.string.tool_summary_get_network_info,
        "list_installed_apps" to R.string.tool_summary_list_installed_apps,
        "open_app" to R.string.tool_summary_open_app,
        "make_phone_call" to R.string.tool_summary_make_phone_call,
        "send_sms" to R.string.tool_summary_send_sms,
        "get_contacts_list" to R.string.tool_summary_get_contacts_list,
        "add_contact" to R.string.tool_summary_add_contact,
        "get_location" to R.string.tool_summary_get_location,
        "open_maps" to R.string.tool_summary_open_maps,
        "share_text" to R.string.tool_summary_share_text,
        "send_email" to R.string.tool_summary_send_email,
        "get_recent_notifications" to R.string.tool_summary_get_recent_notifications,
        "clipboard_read" to R.string.tool_summary_clipboard_read,
        "clipboard_write" to R.string.tool_summary_clipboard_write,
        "quick_note_add" to R.string.tool_summary_quick_note_add,
        "quick_note_list" to R.string.tool_summary_quick_note_list,
        "quick_note_get" to R.string.tool_summary_quick_note_get,
        "quick_note_update" to R.string.tool_summary_quick_note_update,
        "quick_note_delete" to R.string.tool_summary_quick_note_delete,
        "quick_note_pin" to R.string.tool_summary_quick_note_pin,
        "resource_add" to R.string.tool_summary_resource_add,
        "resource_list" to R.string.tool_summary_resource_list,
        "resource_search" to R.string.tool_summary_resource_search,
        "resource_get" to R.string.tool_summary_resource_get,
        "resource_delete" to R.string.tool_summary_resource_delete,
        "delegate_agent" to R.string.tool_summary_delegate_agent,
        "subagent_task" to R.string.tool_summary_subagent_task,
        "subagent_run" to R.string.tool_summary_subagent_run,
        "subagent_close" to R.string.tool_summary_subagent_close,
        "notify" to R.string.tool_summary_notify,
        "proactive_message_wish" to R.string.tool_summary_proactive_message_wish,
        "show_card" to R.string.tool_summary_show_card,
        "current_status" to R.string.tool_summary_current_status,
        "calculator" to R.string.tool_summary_calculator,
        "translate" to R.string.tool_summary_translate,
        "speak_text" to R.string.tool_summary_speak_text,
        "get_weather" to R.string.tool_summary_get_weather,
        "todo_write" to R.string.tool_summary_todo_write,
        "ping_host" to R.string.tool_summary_ping_host,
        "dns_lookup" to R.string.tool_summary_dns_lookup,
        "get_public_ip" to R.string.tool_summary_get_public_ip,
        "download" to R.string.tool_summary_download,
        "json_pretty" to R.string.tool_summary_json_pretty,
        "hash_text" to R.string.tool_summary_hash_text,
        "generate_password" to R.string.tool_summary_generate_password,
        "record_experience" to R.string.tool_summary_record_experience,
        "recall_experience" to R.string.tool_summary_recall_experience,
        "take_photo" to R.string.tool_summary_take_photo,
        "cover_generation" to R.string.tool_summary_cover_generation,
        "plugin_market_search" to R.string.tool_summary_plugin_market_search,
        "plugin_market_install" to R.string.tool_summary_plugin_market_install,
        "plugin_market_uninstall" to R.string.tool_summary_plugin_market_uninstall,
        "plugin_market_set_enabled" to R.string.tool_summary_plugin_market_set_enabled,
        "find_tools" to R.string.tool_summary_find_tools,
        "device_shell" to R.string.tool_summary_device_shell,
        "terminal_exec" to R.string.tool_summary_terminal_exec,
        "termux_exec" to R.string.tool_summary_termux_exec,
    )

    private fun prefixVerb(toolName: String): String? = when {
        toolName.startsWith("browser_") -> "Operado el navegador"
        toolName.startsWith("workspace_") -> "Trabajó en el espacio de trabajo."
        toolName.startsWith("scheduled_task_") -> "Tareas programadas gestionadas"
        toolName.startsWith("quick_note_") -> "notas administradas"
        toolName.startsWith("resource_") -> "recursos gestionados"
        toolName.startsWith("subagent_") -> "Subtareas gestionadas"
        toolName.startsWith("channel_") -> "Canal administrado"
        toolName.startsWith("clipboard_") -> "Portapapeles operado"
        toolName.startsWith("mcp_") -> "herramienta MCP llamada"
        else -> null
    }

    // I18N-01: Recursos verbales de prefijo ID(Correspondencia uno a uno con prefijoVerbo).
    private fun prefixVerbId(toolName: String): Int? = when {
        toolName.startsWith("browser_") -> R.string.tool_summary_prefix_browser
        toolName.startsWith("workspace_") -> R.string.tool_summary_prefix_workspace
        toolName.startsWith("scheduled_task_") -> R.string.tool_summary_prefix_scheduled_task
        toolName.startsWith("quick_note_") -> R.string.tool_summary_prefix_quick_note
        toolName.startsWith("resource_") -> R.string.tool_summary_prefix_resource
        toolName.startsWith("subagent_") -> R.string.tool_summary_prefix_subagent
        toolName.startsWith("channel_") -> R.string.tool_summary_prefix_channel
        toolName.startsWith("clipboard_") -> R.string.tool_summary_prefix_clipboard
        toolName.startsWith("mcp_") -> R.string.tool_summary_prefix_mcp
        else -> null
    }

    private fun defaultVerb(isSuccess: Boolean) = if (isSuccess) "Herramienta llamada" else "Falló la llamada a la herramienta"

    // ── público objetivo(Extraer palabras clave de parámetros o resultados) ───────────────────────────────

    private fun targetFor(toolName: String, arguments: String, result: String): String {
        val args = parseArgs(arguments)
        val raw: String? = when (toolName) {
            "web_search" -> argString(args, "query")
            "search_memory" -> argString(args, "query")
            "read_file", "write_file", "workspace_write", "workspace_edit", "workspace_read" ->
                argString(args, "path") ?: argString(args, "file_path")
            "list_files" -> argString(args, "path") ?: argString(args, "directory")
            "open_url" -> argString(args, "url")
            "generate_image" -> argString(args, "prompt")
            "generate_video" -> argString(args, "prompt")
            "execute_shell", "run_command" -> argString(args, "command")
            "execute_code" -> argString(args, "language")
            "translate" -> argString(args, "text")
            "get_weather" -> argString(args, "city") ?: argString(args, "location")
            "open_app" -> argString(args, "package_name") ?: argString(args, "app")
            "make_phone_call" -> argString(args, "phone_number") ?: argString(args, "number")
            "send_sms" -> argString(args, "phone_number") ?: argString(args, "to")
            "send_email" -> argString(args, "to")
            "add_calendar_event", "scheduled_task_create" -> argString(args, "title")
            "schedule_reminder" -> argString(args, "content") ?: argString(args, "text")
            "set_alarm" -> argString(args, "label")
            "share_text" -> argString(args, "text")
            "notify" -> argString(args, "title")
            "quick_note_add", "quick_note_update" -> argString(args, "content") ?: argString(args, "title")
            "delegate_agent", "subagent_task" -> argString(args, "task") ?: argString(args, "prompt")
            "calculator" -> argString(args, "expression")
            "speak_text" -> argString(args, "text")
            "plugin_market_search" -> argString(args, "query")
            "plugin_market_install" -> argString(args, "plugin_id")
            "ping_host" -> argString(args, "host")
            "dns_lookup" -> argString(args, "domain")
            "download" -> argString(args, "url")
            "take_photo" -> ""
            "pin_memory", "unpin_memory" -> argString(args, "query") ?: result.take(30)
            "save_memory" -> argString(args, "content") ?: result.take(30)
            "delete_memory" -> argString(args, "match") ?: argString(args, "id") ?: result.take(30)
            "find_tools" -> argString(args, "query")
            "device_shell" -> argString(args, "command")
            "terminal_exec" -> argString(args, "command")
            "termux_exec" -> argString(args, "command")
            else -> genericTarget(args)
        }
        return raw?.let { clean(it) }.orEmpty()
    }

    private fun genericTarget(args: JSONObject?): String? {
        if (args == null) return null
        val keys = listOf(
            "query", "path", "url", "title", "name", "text", "content",
            "prompt", "command", "keyword", "id",
        )
        for (k in keys) {
            if (args.has(k)) {
                val v = args.opt(k)
                if (v is String && v.isNotBlank()) return v
            }
        }
        return null
    }

    private fun argString(args: JSONObject?, key: String): String? {
        if (args == null || !args.has(key)) return null
        return when (val v = args.opt(key)) {
            is String -> v
            is Number, is Boolean -> v.toString()
            is JSONArray -> {
                val sb = StringBuilder("[")
                val n = minOf(v.length(), 3)
                for (i in 0 until n) {
                    if (i > 0) sb.append(", ")
                    sb.append(v.opt(i))
                }
                if (v.length() > 3) sb.append("…")
                sb.append("]").toString()
            }
            is JSONObject -> v.toString()
            null, JSONObject.NULL -> null
            else -> v.toString()
        }
    }

    private fun parseArgs(arguments: String): JSONObject? {
        val t = arguments.trim()
        if (t.isEmpty() || !t.startsWith("{")) return null
        return runCatching { JSONObject(t) }.getOrNull()
    }

    private fun clean(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length > 40) one.take(40) + "…" else one
    }

    // ── etiqueta china ─────────────────────────────────────────────────────────

    private val labels: Map<String, String> = mapOf(
        "web_search" to "búsqueda web",
        "search_memory" to "recuperación de memoria",
        "read_file" to "leer archivo",
        "write_file" to "escribir archivo",
        "list_files" to "Listar archivos",
        "execute_code" to "ejecución de código",
        "execute_javascript" to "Ejecución de script",
        "execute_shell" to "Shell comando",
        "open_url" to "Abrir página web",
        "generate_image" to "generación de imágenes",
        "generate_video" to "generación de vídeo",
        "get_current_time" to "hora actual",
        "calendar_today" to "Ver calendario",
        "schedule_reminder" to "recordatorio",
        "set_alarm" to "despertador",
        "set_timer" to "minutero",
        "get_device_info" to "Información del dispositivo",
        "list_installed_apps" to "Lista de aplicaciones",
        "open_app" to "Abrir aplicación",
        "make_phone_call" to "hacer una llamada",
        "send_sms" to "Enviar SMS",
        "get_location" to "Obtener ubicación",
        "clipboard_read" to "leer portapapeles",
        "clipboard_write" to "Escribir en el portapapeles",
        "quick_note_add" to "Crear nueva nota",
        "quick_note_list" to "Lista de notas",
        "delegate_agent" to "Delegar subayudantes",
        "subagent_task" to "subtarea",
        "notify" to "Enviar notificación",
        "calculator" to "calculadora",
        "translate" to "Traducción",
        "speak_text" to "Lectura de voz",
        "get_weather" to "Consulta meteorológica",
        "ping_host" to "Ping",
        "dns_lookup" to "DNS Consulta",
        "mcp_tool" to "MCP Herramientas",
        "show_card" to "mostrar tarjeta",
    )

    // I18N-01: Etiquetar recursos ID(Prioridad de la ruta de producción;labels para res==null Redacción de textos publicitarios consecutivos,Las nuevas herramientas solo registran recursos).
    private val labelIds: Map<String, Int> = mapOf(
        "web_search" to R.string.tool_label_web_search,
        "search_memory" to R.string.tool_label_search_memory,
        "read_file" to R.string.tool_label_read_file,
        "write_file" to R.string.tool_label_write_file,
        "workspace_write" to R.string.tool_label_workspace_write,
        "workspace_edit" to R.string.tool_label_workspace_edit,
        "list_files" to R.string.tool_label_list_files,
        "execute_code" to R.string.tool_label_execute_code,
        "execute_javascript" to R.string.tool_label_execute_javascript,
        "execute_node_script" to R.string.tool_label_execute_javascript,
        "execute_shell" to R.string.tool_label_execute_shell,
        "open_url" to R.string.tool_label_open_url,
        "generate_image" to R.string.tool_label_generate_image,
        "generate_video" to R.string.tool_label_generate_video,
        "get_current_time" to R.string.tool_label_get_current_time,
        "calendar_today" to R.string.tool_label_calendar_today,
        "schedule_reminder" to R.string.tool_label_schedule_reminder,
        "set_alarm" to R.string.tool_label_set_alarm,
        "set_timer" to R.string.tool_label_set_timer,
        "get_device_info" to R.string.tool_label_get_device_info,
        "list_installed_apps" to R.string.tool_label_list_installed_apps,
        "open_app" to R.string.tool_label_open_app,
        "make_phone_call" to R.string.tool_label_make_phone_call,
        "send_sms" to R.string.tool_label_send_sms,
        "get_location" to R.string.tool_label_get_location,
        "clipboard_read" to R.string.tool_label_clipboard_read,
        "clipboard_write" to R.string.tool_label_clipboard_write,
        "quick_note_add" to R.string.tool_label_quick_note_add,
        "quick_note_list" to R.string.tool_label_quick_note_list,
        "delegate_agent" to R.string.tool_label_delegate_agent,
        "subagent_task" to R.string.tool_label_subagent_task,
        "notify" to R.string.tool_label_notify,
        "calculator" to R.string.tool_label_calculator,
        "translate" to R.string.tool_label_translate,
        "speak_text" to R.string.tool_label_speak_text,
        "get_weather" to R.string.tool_label_get_weather,
        "ping_host" to R.string.tool_label_ping_host,
        "dns_lookup" to R.string.tool_label_dns_lookup,
        "mcp_tool" to R.string.tool_label_mcp_tool,
        "show_card" to R.string.tool_label_show_card,
        "plugin_market_search" to R.string.tool_label_plugin_market_search,
        "plugin_market_install" to R.string.tool_label_plugin_market_install,
        "plugin_market_uninstall" to R.string.tool_label_plugin_market_uninstall,
        "plugin_market_set_enabled" to R.string.tool_label_plugin_market_set_enabled,
        "find_tools" to R.string.tool_label_find_tools,
        "device_shell" to R.string.tool_label_device_shell,
        "terminal_exec" to R.string.tool_label_terminal_exec,
        "termux_exec" to R.string.tool_label_termux_exec,
    )

    private fun prettify(name: String): String = name.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}
