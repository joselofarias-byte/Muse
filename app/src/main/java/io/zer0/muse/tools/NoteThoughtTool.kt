package io.zer0.muse.tools

import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveThoughtRepository
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtSource
import io.zer0.muse.data.proactive.ThoughtUrgency

/**
 * Herramientas contables preocupadas —— "Escribe algo que quieras preguntarle más tarde".
 *
 * Esta es la principal fuente de combustible para el nuevo mecanismo de mensajería proactiva. Los supuestos para todo el diseño son:
 * La asistente anota en las conversaciones cotidianas cosas que "harán que sea más humano volver a preguntar pasados ​​unos días".
 * Entonces tome la iniciativa de hablar sobre estos asuntos. Sin esta herramienta, el libro mayor siempre estará vacío,
 * Los mensajes activos se devuelven a "saludos programados".
 *
 * El umbral se eleva deliberadamente. Los mensajes del sistema enfatizan repetidamente "Preferiría no recordarlo":
 * Una vez que el libro de contabilidad esté lleno de asuntos triviales, la información no solicitada parecerá barata. ——
 * Toda la persuasión de este mecanismo se basa en el hecho de que "cada palabra que dice tiene una razón".
 */
object NoteThoughtTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "note_thought",
        description = "Anota algo sobre lo que valga la pena preguntar más tarde. Úselo cuando surja naturalmente en una conversación:" +
            "Habla de cosas que quiere hacer en el futuro, cosas que le molestan o expectativas que están claramente expresadas." +
            "No recuerdes las bromas cotidianas y los detalles intrascendentes. —— Sólo vale la pena recordar las cosas que parecen naturales cuando se vuelven a preguntar después de unos días.",
        parameters = mapOf(
            "seed" to "Requerido. ¿Qué es esa cosa? En una oración, escríbela con tus propias palabras. No copie las palabras originales.",
            "kind" to "Requerido. Tipo:reminder(Tiene cosas específicas que hacer)/ follow_up(Tema inacabado)/" +
                "care(su estado, como físico o emocional)/ share(Cosas que quieres compartir)/" +
                "milestone(El nodo entre ustedes).",
            "urgency" to "Opcional.immediate(Deja de tener sentido después de cierto momento)/ window(Puedes decir lo que quieras estos días, por defecto)/" +
                "evergreen(Puedes decirlo en cualquier momento).",
            "weight" to "Opcional. ¿Cuánto te importa esto?0 Decimal entre 1 y 1, por defecto 0,6." +
                "Sólo da 0,8 o más para lo que realmente importa.",
            "within_hours" to "Opcional. Si es inmediato o de ventana, cualquier número de horas es apropiado. Por defecto 168 (una semana).",
        ),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(
        args: Map<String, String>,
        thoughts: ProactiveThoughtRepository,
        executionContext: ToolExecutionContext,
    ): String {
        val parsed = ParsedArgs.from(args)
        if (parsed.seed.isBlank()) return "Error: seed no puede estar vacío"

        val now = System.currentTimeMillis()
        val assistantId = resolveAssistantId(executionContext)

        // Utilice el hash de la semilla como identificación de fuente: cuando lo mismo se menciona repetidamente, no se registrará como dos elementos.
        // No se utiliza ningún ID de sesión ni marca de tiempo, lo que generará una nueva "fuente" cada vez, y la deduplicación equivale a no realizarse.
        val ref = "note_${parsed.seed.hashCode().toUInt().toString(16)}"

        val recorded = thoughts.note(
            PendingThoughtEntity(
                id = "th_${now}_${ref.hashCode().toUInt().toString(16)}",
                assistantId = assistantId,
                kind = parsed.kind.name,
                seed = parsed.seed.take(200),
                source = ThoughtSource.CONVERSATION.name,
                sourceRef = ref,
                createdAt = now,
                expiresAt = if (parsed.urgency == ThoughtUrgency.EVERGREEN) {
                    null
                } else {
                    now + parsed.withinHours * 3_600_000L
                },
                urgency = parsed.urgency.name,
                weight = parsed.weight,
            ),
        )

        return if (recorded) {
            "Anotado. Lo preguntaré más tarde cuando sea el momento adecuado."
        } else {
            "Ya lo recordé, así que no necesito recordarlo otra vez."
        }
    }

    /** Ayudante de pertenencia: primero ejecute el contexto, luego el alcance y finalmente el valor predeterminado. */
    private fun resolveAssistantId(executionContext: ToolExecutionContext): String =
        executionContext.assistantId?.takeIf { it.isNotBlank() }
            ?: executionContext.scope.takeIf { it.isNotBlank() && it != "main" }
            ?: "default"

    /**
     * Análisis de parámetros.
     *
     * Lo seleccioné debido a las reglas de tolerancia a errores de los parámetros (¿en qué categoría debería clasificarse el tipo mal escrito?
     * ¿Debería limitarse el peso fuera de límites?) Merece separarse en una lógica legible.
     * Mezclarlo con el cuerpo de la herramienta hará que el cuerpo se ahogue en una cadena de cuando.
     */
    private data class ParsedArgs(
        val seed: String,
        val kind: ThoughtKind,
        val urgency: ThoughtUrgency,
        val weight: Float,
        val withinHours: Long,
    ) {
        companion object {
            fun from(args: Map<String, String>): ParsedArgs = ParsedArgs(
                seed = args["seed"]?.trim().orEmpty(),
                kind = parseKind(args["kind"]),
                urgency = parseUrgency(args["urgency"]),
                weight = args["weight"]?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.6f,
                withinHours = args["within_hours"]?.trim()?.toLongOrNull()?.coerceIn(1L, 720L) ?: 168L,
            )

            private fun parseKind(raw: String?): ThoughtKind =
                ThoughtKind.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                    ?: ThoughtKind.FOLLOW_UP

            private fun parseUrgency(raw: String?): ThoughtUrgency =
                ThoughtUrgency.entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                    ?: ThoughtUrgency.WINDOW
        }
    }
}
