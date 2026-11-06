package io.zer0.muse.tools

import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatRequestMode
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v1.200: Agent Enrutamiento automático.
 *
 * Cuando una solicitud de usuario requiere la colaboración de varios agentes pero no especifica explícitamente un subasistente,
 * AgentRouter Basado en el texto de la tarea + etiquetas de capacidades de asistente disponibles + configuración del equipo,
 * Recomendar el asistente o equipo más adecuado.
 *
 * Enrutamiento predeterminado para reglas:Golpear etiquetas de habilidad por palabra clave,Gana el asistente con el mejor partido.
 * v2.x: Nuevo [routeWithLlm] —— Enrutamiento semántico LLM opcional,en [chatService] Disponible cuando no está vacío,
 * por [MultiAgentConfig.llmRoutingEnabled] Control de interruptor.LLM Bajar automáticamente al enrutamiento de reglas cuando falla el enrutamiento.
 */
class AgentRouter(
    private val assistantRepository: AssistantRepository,
    private val settingsRepository: SettingsRepository,
    /** v2.x: Opcional ChatService,Soporte cuando no está vacío [routeWithLlm] LLM Enrutamiento semántico. */
    private val chatService: ChatService? = null,
) {

    /**
     * Resultados de enrutamiento.
     *
     * @param targetType tipo de objetivo("assistant" | "team" | null)
     * @param targetId ID del asistente de destino o teamId
     * @param confidence 0..1 confianza del partido
     * @param reason Razón de enrutamiento(Para LLM/comprensión del usuario)
     * @param source Fuente de ruta:"rule"(Enrutamiento de reglas) | "llm"(LLM Enrutamiento semántico)
     */
    data class RouteResult(
        val targetType: String?,
        val targetId: String?,
        val targetName: String?,
        val confidence: Float,
        val reason: String,
        val source: String = "rule",
    ) {
        val isAvailable: Boolean get() = targetType != null && targetId != null
    }

    /**
     * Seleccione automáticamente el mejor agente/equipo según el texto de la tarea.
     *
     * @param task Descripción de la tarea del usuario
     * @param excludeAssistantId ayudante excluido id(Generalmente el asistente principal actual.,Evite la autodelegación)
     */
    suspend fun route(task: String, excludeAssistantId: String? = null): RouteResult {
        val assistants = assistantRepository.getAll()
            .filter { it.id != excludeAssistantId }
        val config = settingsRepository.multiAgentConfigCache

        if (assistants.isEmpty() && config.teams.isEmpty()) {
            return RouteResult(
                targetType = null,
                targetId = null,
                targetName = null,
                confidence = 0f,
                reason = "Actualmente no hay otros asistentes o equipos en los que delegar",
            )
        }

        val normalizedTask = task.lowercase()
        val taskCapabilities = extractCapabilities(normalizedTask)

        // 1. Empareja el equipo primero:Nombre del equipo/Describe la capacidad general del golpe o del equipo para cubrir la misión.
        val teamScores = config.teams.map { team ->
            val members = assistants.filter { it.id in team.memberIds }
            val teamCaps = members.flatMap { AgentCapability.fromEntity(it) }.distinct()
            val nameScore = if (team.name.isNotBlank() && normalizedTask.contains(team.name.lowercase())) 0.6f else 0f
            val descScore = if (team.description.isNotBlank() &&
                team.description.lowercase().splitAny(taskCapabilities).isNotEmpty()
            ) {
                0.3f
            } else {
                0f
            }
            val capScore = computeCoverageScore(teamCaps, taskCapabilities)
            team to (nameScore + descScore + capScore)
        }
        val bestTeam = teamScores.maxByOrNull { it.second }

        // 2. Luego combina un solo asistente
        val assistantScores = assistants.map { assistant ->
            val caps = AgentCapability.fromEntity(assistant)
            val nameScore = if (assistant.name.isNotBlank() &&
                normalizedTask.contains(assistant.name.lowercase())
            ) {
                0.8f
            } else {
                0f
            }
            val capScore = computeCoverageScore(caps, taskCapabilities)
            val systemPromptScore = if (assistant.systemPrompt.isNotBlank() &&
                assistant.systemPrompt.lowercase().splitAny(taskCapabilities).isNotEmpty()
            ) {
                0.2f
            } else {
                0f
            }
            assistant to (nameScore + capScore + systemPromptScore)
        }
        val bestAssistant = assistantScores.maxByOrNull { it.second }

        // 3. Equipo o asistente con mayor puntuación,Recomendado sólo si supera el umbral.
        val teamResult = bestTeam?.let { (team, score) ->
            if (score >= CONFIDENCE_THRESHOLD) {
                RouteResult(
                    targetType = "team",
                    targetId = team.id,
                    targetName = team.name,
                    confidence = score.coerceIn(0f, 1f),
                    reason = "Equipo \"${team.name}\"La capacidad cubre esta tarea.",
                )
            } else {
                null
            }
        }

        val assistantResult = bestAssistant?.let { (assistant, score) ->
            if (score >= CONFIDENCE_THRESHOLD) {
                RouteResult(
                    targetType = "assistant",
                    targetId = assistant.id,
                    targetName = assistant.name,
                    confidence = score.coerceIn(0f, 1f),
                    reason = "Asistente \"${assistant.name}La etiqueta de habilidad de ” coincide con la tarea.",
                )
            } else {
                null
            }
        }

        return when {
            teamResult != null && assistantResult != null ->
                if (teamResult.confidence >= assistantResult.confidence) teamResult else assistantResult
            teamResult != null -> teamResult
            assistantResult != null -> assistantResult
            else -> {
                // umbral de falta:registrando,Fácil de solucionar el problema de por qué se devuelve el valor predeterminado Agent
                val teamScore = bestTeam?.second ?: 0f
                val assistantScore = bestAssistant?.second ?: 0f
                val bestScore = maxOf(teamScore, assistantScore)
                Logger.d("AgentRouter", "Confianza en la ruta $bestScore por debajo del umbral $CONFIDENCE_THRESHOLD,Volver al valor predeterminado Agent")
                // umbral de falta,Devolver a los candidatos con la puntuación más alta para la toma de decisiones de nivel superior
                val fallback = assistantResult ?: assistantScores.maxByOrNull { it.second }
                    ?.let { (assistant, score) ->
                        RouteResult(
                            targetType = "assistant",
                            targetId = assistant.id,
                            targetName = assistant.name,
                            confidence = score.coerceIn(0f, 1f),
                            reason = "Ningún partido fuerte,Asistente de candidatos \"${assistant.name}\"",
                        )
                    }
                fallback ?: RouteResult(
                    targetType = null,
                    targetId = null,
                    targetName = null,
                    confidence = 0f,
                    reason = "No se encontró ningún asistente o equipo adecuado",
                )
            }
        }
    }

    /**
     * v2.x: LLM Enrutamiento semántico(Opcional).
     *
     * cuando [chatService] tiempo no vacío,Poner texto de tarea + ayudante disponible/La lista del equipo se deja a LLM para que la juzgue.,
     * LLM Responda únicamente al ID de objetivo más apropiado. Más preciso que el enrutamiento de reglas,Pero hay una llamada más de LLM.
     *
     * estrategia de degradación(Si algún enlace falla, se revertirá. [route]):
     *  - [chatService] Para nulo: vaya directamente al enrutamiento de reglas
     *  - LLM excepción de llamada:Siga la ruta habitual
     *  - LLM Ningún asistente puede coincidir con el ID de la respuesta./equipo:Siga la ruta habitual
     *
     * @param task Descripción de la tarea del usuario
     * @param excludeAssistantId ayudante excluido id(Generalmente el asistente principal actual.,Evite la autodelegación)
     * @return Resultados de enrutamiento,[RouteResult.source] = "llm" Indica acierto en la ruta LLM,"rule" Indica volver a la degradación al enrutamiento de reglas
     */
    suspend fun routeWithLlm(task: String, excludeAssistantId: String? = null): RouteResult {
        // chatService No inyectado:No se puede tomar la ruta LLM,Bajar de categoría directamente
        if (chatService == null) return route(task, excludeAssistantId)

        val assistants = assistantRepository.getAll()
            .filter { it.id != excludeAssistantId }
        val config = settingsRepository.multiAgentConfigCache

        // Cuando no haya ningún objetivo candidato, siga directamente el enrutamiento de reglas.(route regresará internamente"Ningún objetivo confiable")
        if (assistants.isEmpty() && config.teams.isEmpty()) {
            return route(task, excludeAssistantId)
        }

        // ayudante de construccion/Lista del equipo,Informe a LLM la identificación y la descripción de la capacidad de cada candidato.
        val roster = buildString {
            append("Asistentes disponibles:\n")
            assistants.forEach { a ->
                // prioridad summary,Caiga a las primeras 80 palabras de systemPrompt cuando esté vacío
                val desc = a.summary.ifBlank { a.systemPrompt.take(80) }
                append("- ${a.id} | ${a.name}${if (desc.isBlank()) "" else ": $desc"}\n")
            }
            if (config.teams.isNotEmpty()) {
                append("\nEquipos disponibles:\n")
                config.teams.forEach { t ->
                    append("- ${t.id} | ${t.name}${if (t.description.isBlank()) "" else ": ${t.description}"}\n")
                }
            }
        }

        val prompt = """
Eres un enrutador liviano. Sólo como asistente candidato./El equipo solo lo selecciona cuando está claro que encaja mejor que el asistente actual.
Solo genera un candidato ID;Si no hay una coincidencia obvia,Salida NINGUNO. No expliques.

Tareas de usuario:
$task

$roster

candidato ID:
        """.trimIndent()

        return try {
            // v2.x: La clasificación ligera da prioridad al archivo "gadget" de enrutamiento del modelo auxiliar(Déjelo en blanco para regresar al modelo de diálogo principal.)
            val routed = runCatching {
                io.zer0.muse.data.routing.UtilityModelRouter(settingsRepository)
                    .resolve(io.zer0.muse.data.routing.UtilityTier.SMALL)
            }.getOrNull()
            val completion = withTimeoutOrNull(4_000L) {
                chatService.completeText(
                    messages = listOf(
                        UIMessage(role = MessageRole.SYSTEM, content = "Sólo hacer clasificación ligera.,Generar solo ID de candidatos o NONE,No pienses en ensayos largos."),
                        UIMessage(role = MessageRole.USER, content = prompt),
                    ),
                    model = routed?.second,
                    providerConfig = routed?.first,
                    temperature = 0f,
                    maxTokens = 16,
                    reasoningLevel = io.zer0.ai.core.ReasoningLevel.OFF,
                    mode = ChatRequestMode.UTILITY,
                )
            } ?: return route(task, excludeAssistantId)
            val id = completion.text
                .trim()
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.removePrefix("`")
                ?.removeSuffix("`")
                .orEmpty()
            if (id.equals("NONE", ignoreCase = true)) return route(task, excludeAssistantId)
            // asistente de emparejamiento
            val matchedAssistant = assistants.find { it.id == id }
            if (matchedAssistant != null) {
                RouteResult(
                    targetType = "assistant",
                    targetId = matchedAssistant.id,
                    targetName = matchedAssistant.name,
                    confidence = 0.8f,
                    reason = "LLM Asistente de enrutamiento \"${matchedAssistant.name}\"",
                    source = "llm",
                )
            } else {
                // equipo del partido
                val matchedTeam = config.teams.find { it.id == id }
                if (matchedTeam != null) {
                    RouteResult(
                        targetType = "team",
                        targetId = matchedTeam.id,
                        targetName = matchedTeam.name,
                        confidence = 0.8f,
                        reason = "LLM Equipo de ataque de ruta \"${matchedTeam.name}\"",
                        source = "llm",
                    )
                } else {
                    // LLM El ID de respuesta no puede coincidir,Enrutamiento de reglas de degradación
                    Logger.d("AgentRouter", "LLM Respuesta de ruta \"$id』Ningún ayudante coincide/equipo,Enrutamiento de reglas de degradación")
                    route(task, excludeAssistantId)
                }
            }
        } catch (e: Exception) {
            Logger.w("AgentRouter", "LLM Excepción de enrutamiento,Enrutamiento de reglas de degradación", e)
            route(task, excludeAssistantId)
        }
    }

    /**
     * Extraiga los requisitos de capacidad implícitos en el texto de la tarea.
     */
    private fun extractCapabilities(task: String): List<String> {
        return AgentCapability.ALL_CAPABILITIES.filter { capability ->
            KEYWORD_MAP[capability]?.any { task.contains(it) } == true
        }
    }

    /**
     * Puntuación de cobertura de capacidad informática:
     * - Habilidad requerida para cada misión cumplida. +0.25
     * - No se deducirán puntos por las habilidades adicionales del asistente.,Pero más de 3 habilidades adicionales penalizan con 0,05 para evitar una extensión excesiva
     */
    private fun computeCoverageScore(agentCaps: List<String>, taskCaps: List<String>): Float {
        if (taskCaps.isEmpty()) return 0.1f
        val matched = taskCaps.count { it in agentCaps }
        val coverage = matched.toFloat() / taskCaps.size
        val extra = (agentCaps - taskCaps.toSet()).size
        val penalty = (extra.coerceAtMost(5) * 0.03f)
        return (coverage * 0.7f + matched * 0.25f - penalty).coerceIn(0f, 1f)
    }

    /**
     * Divida la cadena según el conjunto de tokens dado,Devuelve la ficha de impacto.
     */
    private fun String.splitAny(tokens: List<String>): List<String> {
        return tokens.filter { this.contains(it) }
    }

    companion object {
        /** Umbral recomendado,Por debajo de este valor se considera un error.,Volver a suplente. */
        private const val CONFIDENCE_THRESHOLD = 0.35f

        /** Habilidad → Mapeo de palabras clave. */
        private val KEYWORD_MAP = mapOf(
            AgentCapability.CODE to listOf("código", "Programación", "program", "code", "debug", "bug", "kotlin", "java", "python", "js", "javascript", "ts", "typescript", "compose", "android"),
            AgentCapability.MATH to listOf("Matemáticas", "calcular", "math", "calculate", "fórmula", "statistics", "probabilidad", "álgebra", "calculo"),
            AgentCapability.RESEARCH to listOf("Investigación", "Investigación", "research", "Buscar", "search", "investigar", "análisis", "Productos de la competencia", "mercado"),
            AgentCapability.WRITING to listOf("escribiendo", "escribir artículos", "write", "redacción", "essay", "draft", "polaco", "reescribir", "correo", "report", "historia"),
            AgentCapability.CREATIVE to listOf("creatividad", "brainstorm", "idea", "creatividad", "inspiración", "diseño", "plot", "papel"),
            AgentCapability.TRANSLATION to listOf("Traducción", "translate", "translation", "ingles", "chino", "japonés", "Idioma"),
            AgentCapability.REVIEW to listOf("revisión", "review", "comprobar", "Corrección", " critique", "Evaluación", "Calificación"),
            AgentCapability.DATA to listOf("datos", "data", "csv", "json", "mesa", "Estadísticas", "Visualización", "chart", "análisis"),
            AgentCapability.LEGAL to listOf("ley", "legal", "contrato", "law", "Términos", "Cumplimiento"),
            AgentCapability.MEDICAL to listOf("medico", "Medicina", "medical", "salud", "Síntomas", "medicina", "Diagnóstico"),
            AgentCapability.FINANCE to listOf("Finanzas", "Finanzas", "finance", "inversión", "acciones", "fondo", "gestión financiera", "presupuesto"),
            AgentCapability.EDUCATION to listOf("educación", "aprender", "teach", "tutorial", "explicar", "cursos", "Puntos de conocimiento"),
            AgentCapability.IMAGE to listOf("fotos", "imagen", "image", "hacer un dibujo", "Generar gráfico", "ilustración", "photo", "dall"),
            AgentCapability.VIDEO to listOf("vídeo", "video", "Generar vídeo", "clip", "animación"),
            AgentCapability.WEB_SEARCH to listOf("Redes", "Buscar", "Lo último", "Noticias", "web", "search", "Información"),
            AgentCapability.MEMORY to listOf("memoria", "organizar", "memory", "Archivo", "Revisión", "Resumen"),
            AgentCapability.SCHEDULE to listOf("horario", "Sincronización", "schedule", "recordatorio", "Tarea", "todo", "planear", "despertador"),
            AgentCapability.KNOWLEDGE to listOf("base de conocimientos", "Documentación", "knowledge", "pdf", "Notas", "información"),
            AgentCapability.CHAT to listOf("charlar", "charlar", "chat", "dialogo", "hablar de corazón a corazón"),
            AgentCapability.REASONING to listOf("razonamiento", "lógica", "reasoning", "Preguntas de lógica", "probar", "Derivación"),
        )
    }
}
