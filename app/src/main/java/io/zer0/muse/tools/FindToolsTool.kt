package io.zer0.muse.tools

/**
 * v2.x Etapa de adelgazamiento de herramientas.3:find_tools Metaherramientas — Búsqueda de biblioteca de herramientas + descripción general de capacidades.
 *
 * Después de reducir las capas predeterminadas,El modelo solo ve NÚCLEO/ESTÁNDAR + familia de impactos + herramienta de permanencia en la sesión en cada ronda;
 * Cuando se necesitan otras habilidades,Utilice esta herramienta para buscar por palabra clave**Todas las herramientas registradas**(Nombre+Descripción),
 * Hit escritura de resultados [SessionToolLoadRegistry],Las rondas siguientes se pueden convocar directamente.
 *
 * v2.2.1: Nuevo"Descripción general de capacidades"modo —— La palabra clave vacía devuelve una lista agrupada de todas las herramientas(No cargando),
 * Deja que el modelo responda todo en detalle."tu/¿Qué puede hacer esta plataforma?".
 *
 * Búsqueda de solo lectura:No ejecute ninguna herramienta ni cambie permisos;La carga solo permite que las herramientas"visible",La aprobación sigue siendo la misma.
 */
object FindToolsTool {
    /** Constante de nombre de herramienta(Para referencia en casos especiales de la lista blanca, etc.). */
    const val TOOL_NAME = "find_tools"

    private const val MAX_RESULTS = 8
    private const val DESCRIPTION_SNIPPET = 140
    private const val LIST_DESCRIPTION_SNIPPET = 80

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description =
        "Search the full tool library, or get a complete capability list. " +
            "With keywords: matching tools become available from your next call in this session. " +
            "With an empty query: returns the full tool list with a channel-status summary. " +
            "Entries marked * need extra system authorization (Shizuku/Root, Accessibility or Termux); " +
            "when a required channel is not ready, calling the tool fails fast with a clear error. " +
            "Use this when you need a tool that is not in your current list, or when asked what you can do.",
        parameters =
        mapOf(
            "query" to "Optional. Keywords describing the capability you need. " +
                "Leave empty to get the full tool list (capability overview).",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * Búsqueda de funciones puras:por nombre/Descripción de concordancia de palabras clave,Las visitas a nombres tienen mayor peso;Se priorizan las visitas a los nombres.
     *
     * Pulsar palabra clave en blanco/comas chinas e inglesas/división de punto y coma,longitud ≥2 las palabras participan en el emparejamiento;Cuando toda la cadena sea de un solo carácter, utilice la cadena completa.
     */
    fun search(query: String, tools: List<ToolRegistry.ToolDef>, excludeName: String = TOOL_NAME): List<ToolRegistry.ToolDef> {
        val raw = query.trim().lowercase()
        if (raw.isEmpty()) return emptyList()
        val terms =
            raw.split(Regex("[\\s,,,;;]+"))
                .filter { it.length >= 2 }
                .ifEmpty { listOf(raw) }
        return tools
            .asSequence()
            .filter { it.name != excludeName }
            .map { def ->
                val name = def.name.lowercase()
                val desc = def.description.lowercase()
                var score = 0
                terms.forEach { term ->
                    if (name.contains(term)) score += 3
                    if (desc.contains(term)) score += 1
                }
                def to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ToolRegistry.ToolDef, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
            .take(MAX_RESULTS)
            .toList()
    }

    /**
     * v2.2.1: función pura —— Representar una lista agrupada de todas las herramientas.(Descripción general de capacidades).
     *
     * La lista es solo"Directorio",No hay herramientas cargadas;Después de que el modelo vea el objetivo, verifíquelo nuevamente usando palabras clave antes de cargarlo.,
     * Evite restaurar todas las superficies de la herramienta a su capacidad total de un solo vistazo.
     *
     * v2.x: Bandas de nombres de herramientas que requieren autorización adicional * marca;entrante [status] Adjunto está la línea de estado del canal en ejecución.
     */
    fun fullList(tools: List<ToolRegistry.ToolDef>, excludeName: String = TOOL_NAME, status: ToolPermissionStatus? = null): String {
        val visible = tools.filter { it.name != excludeName }
        if (visible.isEmpty()) return "No tools registered."
        val grouped = visible.groupBy { ToolCategories.categoryOf(it.name) }
        val sb = StringBuilder()
        sb.appendLine(
            "Full tool capability list (${visible.size} tools total, session-visible: " +
                "the per-turn schema may expose fewer under the current request and approval policy):",
        )
        if (visible.any { ToolCategories.permissionOf(it.name).isNotEmpty() }) {
            sb.appendLine(
                "* = needs extra system authorization (Shizuku/Root, Accessibility or Termux). " +
                    "Unready channels fail fast with a clear error instead of a misleading success.",
            )
        }
        val sections =
            listOf(
                ToolCategory.CORE to "core núcleo",
                ToolCategory.STANDARD to "standard de uso común",
                ToolCategory.OPTIONAL to "optional Opcional",
                ToolCategory.GLOBAL to "global Avanzado(Se requiere autorización/Aprobación)",
                null to "extension Expandir(complemento/MCP/Habilidades)",
            )
        sections.forEach { (category, label) ->
            val items = grouped[category].orEmpty().sortedBy { it.name }
            if (items.isEmpty()) return@forEach
            sb.appendLine()
            sb.appendLine("【$label】${items.size}")
            items.forEach { def ->
                val snippet = def.description.replace('\n', ' ').take(LIST_DESCRIPTION_SNIPPET)
                sb.appendLine("- ${markName(def.name)}: $snippet")
            }
        }
        if (status != null) {
            sb.appendLine()
            sb.appendLine(
                "Channel status now — Accessibility=${readyLabel(status.accessibility)}, " +
                    "Shizuku/Root=${readyLabel(status.shellTier)}, Termux=${readyLabel(status.termux)}",
            )
        }
        sb.appendLine()
        sb.appendLine(
            "Lo anterior es el índice completo. Para utilizar una de estas herramientas:Llámalo nuevamente con su nombre o palabra clave find_tools," +
                "La herramienta de golpe se puede invocar directamente desde la siguiente ronda. Está disponible una descripción general de las funciones a nivel de aplicación knowledge_search(include_internal=true) Consulta.",
        )
        return sb.toString().trimEnd()
    }

    /** Se agregan nombres de herramientas que requieren autorización adicional. * marca(Corresponde a la leyenda de la Lista completa). */
    private fun markName(name: String): String = if (ToolCategories.permissionOf(name).isEmpty()) name else "$name*"

    private fun readyLabel(ready: Boolean): String = if (ready) "READY" else "NOT READY"

    /** Notas de permiso en los resultados de búsqueda(Contiene el estado de preparación actual,Fácil de juzgar de antemano). */
    private fun permissionHint(name: String, status: ToolPermissionStatus?): String {
        val required = ToolCategories.permissionOf(name)
        if (required.isEmpty()) return ""
        val needs = required.joinToString(" or ") { it.enLabel() }
        return when (status?.satisfiedAny(required)) {
            true -> " [needs $needs; ready]"
            false -> " [needs $needs; NOT READY]"
            null -> " [needs $needs]"
        }
    }

    /**
     * ejecutar:vacío query = Descripción general de capacidades(solo lectura,No cargando);hay palabras clave = recuperar y cargar(Disponible a partir de la siguiente ronda después del golpe.).
     *
     * v2.6.1: El directorio de búsqueda se limita a“Dentro de la configuración actual de la herramienta asistente”herramientas. Anteriormente, busqué en todos los formularios de registro.
     * Se comprometerá después de acceder a una herramienta fuera de la lista blanca."available from your next call", pero está en
     * resolveToolsAndModel se cortará en la etapa de filtrado de la lista blanca y solo obtendrá
     * `tool 'X' is not exposed in this turn` —— Cheque en blanco. Ahora sólo se cargan las herramientas que realmente pueden ingresar al esquema.
     * Si la herramienta no logra acertar, diga la verdad con sinceridad por qué.
     */
    suspend fun execute(args: Map<String, String>, toolRegistry: ToolRegistry, executionContext: ToolExecutionContext): String {
        val query = args["query"]?.trim().orEmpty()
        // Catálogo con capacidad de búsqueda = Herramientas de registro locales + habilidades de sesión actual(Obtener por ID de sesión,Prevenir la contaminación entre sesiones).
        val sessionSkills = toolRegistry.searchableSkills(executionContext.sessionId)
        val allRegistered = toolRegistry.listTools() + sessionSkills
        val status = toolRegistry.permissionStatusProvider?.invoke()
        // v2.6.1: Limitar al conjunto realmente visible para el asistente(No registrado = Sin restricciones,Compatible con sitios de llamadas antiguos).
        val visibleNames = toolRegistry.visibleTools(executionContext.sessionId)
        val searchable = ToolSchemaRules.visibleSubset(allRegistered, visibleNames)
        if (query.isEmpty()) {
            return fullList(searchable, status = status)
        }
        val matches = search(query, searchable)
        if (matches.isEmpty()) {
            // distinguir“No existe en absoluto”con“Existe pero no está dentro de la configuración de esta herramienta asistente.”, este último proporciona orientación práctica,
            // ya no se usa"No tools matched"Oculta la verdadera razón.
            val blockedByAssistant =
                if (visibleNames == null) {
                    emptyList()
                } else {
                    search(query, allRegistered).filterNot { it.name in visibleNames }
                }
            if (blockedByAssistant.isNotEmpty()) {
                val names = blockedByAssistant.joinToString(", ") { it.name }
                return "Found $names, but the current assistant is not configured to use them, " +
                    "so they cannot be loaded here. Enable them in Assistant settings → Tools, then try again."
            }
            return "No tools matched \"$query\". Try different or broader keywords."
        }
        executionContext.sessionId?.let { sessionId ->
            SessionToolLoadRegistry.markLoaded(sessionId, matches.map { it.name })
        }
        return buildString {
            appendLine("Matched ${matches.size} tool(s), now available from your next call in this session:")
            matches.forEach { def ->
                val snippet = def.description.replace('\n', ' ').take(DESCRIPTION_SNIPPET)
                appendLine("- ${def.name}${permissionHint(def.name, status)}: $snippet")
            }
        }.trimEnd()
    }
}
