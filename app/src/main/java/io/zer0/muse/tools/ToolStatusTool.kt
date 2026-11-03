package io.zer0.muse.tools

import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.tools.ToolRequirements.Channel

/**
 * v2.6.7: tool_status —— Dígale al modelo una vez "lo que realmente se puede utilizar ahora".
 *
 * ## por qué es necesario
 *
 * Las palabras originales de autoexamen del asistente: "206 herramienta y sin mapa. Al principio no tenía idea de lo que tenía;
 * Es a través de la consulta vacía find_tools que obtenemos la tabla completa. engranajes y'puedo usar'No importa. "
 *
 * El problema de la situación actual no es que haya pocas herramientas;**límites poco claros**:schema Hay más de 200 nombres en él,
 * pero que depende de la accesibilidad/Shizuku/Root El lote debe fallar cuando el canal no esté listo.
 * El modelo sólo puede saber cuándo choca contra una pared al llamarlo. Esto producirá la frase "Haré clic en él por ti" y luego fallará en el acto. ——
 * Daña más la experiencia que no ofrecerla en absoluto.
 *
 * Esta herramienta separa "registrados" y "ya disponibles" e informa:
 *  - usable: Se puede convocar esta ronda (incluidas las herramientas en la aplicación y los canales están listos)
 *  - blocked: Si te has registrado pero el canal no está listo, ¿qué falta y cómo abrirlo?
 *
 * De esta forma, el modelo puede saber hasta qué punto tiene las manos y los pies atados antes de actuar, en lugar de prometer primero y luego fracasar.
 */
object ToolStatusTool {

    /** Cada línea de la lista de bloqueados puede enumerar como máximo algunos nombres de herramientas para evitar que una línea sea demasiado larga. */
    private const val BLOCKED_PREVIEW_LIMIT = 14

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = "tool_status",
        description = "Informar las herramientas que realmente se pueden llamar en este momento.,Hay dos categorías: \"disponible\" y \"bloqueado por permisos\"." +
            "Cualquier operación a nivel de dispositivo confirmada(Haga clic, deslice, lea la pantalla,shell)ajustarlo antes," +
            "Evite reclamar algo que no puede hacer. Devolver el estado listo de cada canal,Y qué herramientas hay que abrir y que están bloqueadas.",
        parameters = mapOf(
            "detail" to "Optional. 'summary' (default) = counts plus blocked list; 'full' = also list every usable tool name.",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * Ejecutar.
     *
     * @param args Parámetros (detail)
     * @param registry Registro de herramientas (utilizado para enumerar herramientas registradas)
     * @param automation Administrador de automatización (para leer el estado del canal)
     * @param runtimePermissionChecks Sonda de permisos de tiempo de ejecución único (contacts/sms/notification etc)
     */
    suspend fun execute(
        args: Map<String, String>,
        registry: ToolRegistry,
        automation: AutomationManager,
        runtimePermissionChecks: Map<Channel, Boolean> = emptyMap(),
    ): String {
        val detail = args["detail"]?.trim()?.lowercase() ?: "summary"
        val state = automation.refreshPermissions()
        val channels = buildChannels(state, runtimePermissionChecks)
        val registered = registry.listTools().map { it.name }
        val (usable, blocked) = registered.partition { ToolRequirements.isUsable(it, channels) }

        return buildString {
            appendLine("## Disponibilidad de herramientas (tiempo real)")
            appendLine()
            appendChannelSection(state, runtimePermissionChecks)
            appendLine()
            appendLine("Herramienta registrada ${registered.size} :Disponible ${usable.size}, debido a que los permisos están bloqueados ${blocked.size}.")
            if (blocked.isNotEmpty()) {
                appendLine()
                appendLine("### Actualmente no se puede invocar (no esté de acuerdo todavía y no lo intente)")
                appendBlockedSection(blocked, channels)
                appendLine()
                append("Abra cualquier canal y vuelva a llamar a la herramienta para ver las actualizaciones.")
            }
            if (detail == "full" && usable.isNotEmpty()) {
                appendLine()
                appendLine("### Actualmente disponible")
                append(usable.joinToString(", "))
            }
        }
    }

    /** Combine tres capas de automatización + permisos de tiempo de ejecución individuales en un conjunto de canales disponibles. */
    private fun buildChannels(state: AutomationManager.PermissionState, runtimePermissionChecks: Map<Channel, Boolean>): Set<Channel> =
        buildSet {
            if (state.accessibilityEnabled) add(Channel.ACCESSIBILITY)
            if (state.shellEnabled) add(Channel.SHELL)
            if (state.rootEnabled) add(Channel.ROOT)
            runtimePermissionChecks.forEach { (ch, granted) -> if (granted) add(ch) }
        }

    /** Segmento de estado de canal listo. */
    private fun StringBuilder.appendChannelSection(
        state: AutomationManager.PermissionState,
        runtimePermissionChecks: Map<Channel, Boolean>,
    ) {
        appendLine("Estado del canal:")
        appendLine("- Accesibilidad: ${if (state.accessibilityEnabled) "Disponible" else "No encendido"}")
        appendLine(
            "- Shell(Shizuku/adb): " +
                if (state.shellEnabled) {
                    "Disponible"
                } else {
                    "No disponible (${state.shizukuMessage})"
                },
        )
        appendLine("- Root: ${if (state.rootEnabled) "Disponible" else "No obtenido"}")
        val granted = runtimePermissionChecks.filterValues { it }.keys
        val missing = runtimePermissionChecks.filterValues { !it }.keys
        if (granted.isNotEmpty()) appendLine("- Se ha concedido el permiso único.: ${granted.joinToString(",") { it.label() }}")
        if (missing.isNotEmpty()) appendLine("- Falta el permiso único: ${missing.joinToString(",") { it.label() }}")
        appendLine("- Nivel más alto disponible: ${state.highestLevelName()}")
    }

    /** Botón de herramienta bloqueado"lo que falta"Agrúpelos para que sea más fácil saber qué elemento puede desbloquear cuánto a la vez. */
    private fun StringBuilder.appendBlockedSection(blocked: List<String>, channels: Set<Channel>) {
        blocked.groupBy { missingChannelsOf(it, channels) }
            .entries
            .sortedByDescending { it.value.size }
            .forEach { (missing, tools) ->
                val label = if (missing.isEmpty()) "Otros" else missing.joinToString(" o ") { it.label() }
                val tail = if (tools.size > BLOCKED_PREVIEW_LIMIT) " …" else ""
                appendLine(
                    "- necesitar $label (${tools.size} ): " +
                        tools.take(BLOCKED_PREVIEW_LIMIT).joinToString(", ") + tail,
                )
            }
    }

    /** Calcule qué canales faltan en una herramienta (para visualización grupal). Expresión única, evite devoluciones múltiples. */
    private fun missingChannelsOf(toolName: String, available: Set<Channel>): Set<Channel> {
        val req = ToolRequirements.of(toolName)
        val missingAll = req.allOf.filterNot { it in available }.toSet()
        val anyUnsatisfied = req.anyOf.isNotEmpty() && req.anyOf.none { it in available }
        return when {
            missingAll.isNotEmpty() -> missingAll
            anyUnsatisfied -> req.anyOf
            else -> emptySet()
        }
    }

    private fun Channel.label(): String = when (this) {
        Channel.ACCESSIBILITY -> "Accesibilidad"
        Channel.SHELL -> "Shizuku/ADB"
        Channel.ROOT -> "Root"
        Channel.USAGE_STATS -> "acceso de uso"
        Channel.NOTIFICATION_LISTENER -> "Aviso de derechos de uso"
        Channel.CONTACTS -> "Persona de contacto"
        Channel.SMS -> "SMS"
        Channel.PHONE_CALL -> "telefono"
        Channel.CALENDAR -> "Calendario"
        Channel.LOCATION -> "Posicionamiento"
        Channel.RUNTIME -> "permisos de tiempo de ejecución"
    }
}
