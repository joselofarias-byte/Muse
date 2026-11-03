package io.zer0.muse.data.miniphone

import io.zer0.ai.core.ToolDefinition
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.tools.ToolCategories
import io.zer0.muse.tools.ToolCategory
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRiskLevel

/**
 * Una superficie de herramienta que pueden utilizar los personajes de teléfonos celulares pequeños.
 *
 * La mensajería privada en un teléfono celular pequeño es una escena de juego de roles,No es un banco de trabajo auxiliar:Sólo aquellos directamente relacionados con la "vida del personaje" son de sólo lectura./Las herramientas livianas permiten,
 * Ejecución de código, terminal, lectura y escritura de archivos, navegador, subagente,UI Las herramientas dinámicas de automatización, complementos y MCP no están disponibles para los modelos.
 *
 * tres puertas,en orden:
 *  1. lista blanca de nombres [ALLOWED](Memoria/Recordatorio de calendario/Consulta de información y clima/Notas/Biblioteca de recursos Cinco clanes);
 *  2. Puerta de clasificación:debe estar en [ALLOWED_CATEGORIES] adentro —— GLOBAL(Clase de ejecución, navegador,UI Automatización),
 *     LEGACY,y herramientas dinámicas para categorías no registradas(complemento `plugin_*` / MCP `mcp_*__*`)bloqueado aquí;
 *  3. puerta de riesgo [ToolPermissionResolver.riskLevelFor] No HIGH —— Mismo calibre que las tareas en segundo plano,
 *     Herramientas para escribir semántica de nombres de bajo riesgo pero de alto riesgo en el libro de registro(Como por ejemplo `save_memory`)Detente como siempre.
 */
internal object MiniPhoneToolPolicy {

    /** Categorías permitidas:Núcleo, normalmente abierto, opcional.GLOBAL y LEGACY no están entre ellos.;No registrado(complemento/MCP)El retorno nulo también está bloqueado. */
    private val ALLOWED_CATEGORIES: Set<ToolCategory> = setOf(
        ToolCategory.CORE,
        ToolCategory.STANDARD,
        ToolCategory.OPTIONAL,
    )

    /** Categoría de memoria. */
    private val MEMORY_TOOLS: Set<String> = setOf(
        "save_memory",
        "search_memory",
        "delete_memory",
        "pin_memory",
        "unpin_memory",
        "recall_experience",
        "record_experience",
        "search_conversation",
        "remember_preference",
        "memory_config_get",
        "memory_config_set",
    )

    /** Categoría Calendario/Recordatorio. */
    private val CALENDAR_TOOLS: Set<String> = setOf(
        "get_calendar_today",
        "calendar_today",
        "add_calendar_event",
        "set_alarm",
        "set_timer",
        "schedule_reminder",
        "cancel_reminder",
        "list_reminders",
    )

    /** clima. */
    private val WEATHER_TOOLS: Set<String> = setOf("get_weather")

    /** Otras consultas de información:Búsqueda externa y autocomprobación del estado actual. */
    private val QUERY_TOOLS: Set<String> = setOf(
        "web_search",
        "web_fetch",
        "get_current_time",
        "current_status",
        "translate",
    )

    /** Categoría de notas. */
    private val NOTES_TOOLS: Set<String> = setOf(
        "quick_note_add",
        "quick_note_list",
        "quick_note_search",
        "quick_note_get",
        "quick_note_update",
        "quick_note_delete",
        "quick_note_pin",
    )

    /** Clase de biblioteca de recursos. */
    private val RESOURCE_TOOLS: Set<String> = setOf(
        "resource_add",
        "resource_list",
        "resource_search",
        "resource_get",
        "resource_delete",
    )

    private val ALLOWED: Set<String> =
        MEMORY_TOOLS + CALENDAR_TOOLS + WEATHER_TOOLS + QUERY_TOOLS + NOTES_TOOLS + RESOURCE_TOOLS

    /**
     * Nombres familiares e individuales explícitamente prohibidos.
     *
     * La mayoría de estos no estaban allí para empezar. [ALLOWED] adentro,Se enumeran para que los límites sean visibles en el código.:
     * Al agregar nombres a la lista blanca en el futuro,No traeré a ninguno de ellos de vuelta fácilmente.
     */
    private val DENIED_PREFIXES: List<String> = listOf(
        "execute_",
        "terminal_",
        "termux_",
        "device_shell",
        "workspace_",
        "browser_",
        "ui_",
        "screen_",
        "subagent_",
        "delegate_agent",
        "plugin_",
        "mcp_",
    )

    /** Artículos prohibidos seleccionados individualmente:El nombre no se parece a ninguno de los clanes anteriores.,Pero ambos caen bajo el límite de \"archivo de contacto/dispositivo cruzado\". */
    private val DENIED: Set<String> = setOf(
        "read_file",
        "read_user_file",
        "virtual_screen",
        "virtual_screen_input",
        "automation_workflow",
    )

    /** Conserve únicamente las definiciones de herramientas que se puedan enviar al modelo. */
    fun filter(definitions: List<ToolDefinition>): List<ToolDefinition> = definitions.filter { isAllowed(it.name) }

    /** Sólo está permitido después de pasar las tres puertas. */
    fun isAllowed(name: String): Boolean {
        if (isDenied(name) || name !in ALLOWED) return false
        val category = ToolCategories.categoryOf(name) ?: return false
        if (category !in ALLOWED_CATEGORIES) return false
        return ToolPermissionResolver.riskLevelFor(name) != ToolRiskLevel.HIGH
    }

    /** Nombre de la herramienta → El tipo de redacción publicitaria para esa línea de interfaz. El nombre solo se lee aquí,No dejará al anfitrión. */
    fun activityKindOf(name: String): MpActivityKind = when (name) {
        in MEMORY_TOOLS -> MpActivityKind.REMEMBERING
        in CALENDAR_TOOLS -> MpActivityKind.CALENDAR
        in WEATHER_TOOLS -> MpActivityKind.WEATHER
        in QUERY_TOOLS -> MpActivityKind.SEARCHING
        in NOTES_TOOLS -> MpActivityKind.NOTES
        else -> MpActivityKind.OTHER
    }

    private fun isDenied(name: String): Boolean = name in DENIED || DENIED_PREFIXES.any { name.startsWith(it) }
}
