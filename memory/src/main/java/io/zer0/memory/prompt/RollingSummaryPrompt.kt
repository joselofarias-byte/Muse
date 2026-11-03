package io.zer0.memory.prompt

import io.zer0.memory.format.RollingSummaryFormat

/**
 * Generador de palabras de aviso de resumen de desplazamiento.
 *
 * Aquí extraemos la parte estable del sistema sin contenido de diálogo para [io.zer0.memory.summary.SessionSummaryManager] Usar.
 */
object RollingSummaryPrompt {

    const val TEMPLATE_VERSION = "rolling-summary.v3"
    const val CACHE_GROUP = "memory.rolling_summary"

    /**
     * Aviso del sistema para crear un resumen de desplazamiento.
     *
     * @param locale Idioma
     * @param agentName Agent Nombre(Disponible,Hay un valor predeterminado)
     * @param userName Nombre de usuario(Disponible,Hay un valor predeterminado)
     * @param identityAndPersonality Agent personalidad de identidad(Disponible)
     * @param userProfile Configuraciones de usuario(Disponible)
     * @param existingMemory Tener memoria a largo plazo(Disponible)
     * @param roster Lista(Disponible)
     */
    fun buildSystemPrompt(
        locale: String = "es",
        agentName: String = "",
        userName: String = "",
        identityAndPersonality: String = "",
        userProfile: String = "",
        existingMemory: String = "",
        roster: String = "",
    ): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        val resolvedAgentName = agentName.ifBlank { if (isZh) "esto Agent" else "this agent" }
        val resolvedUserName = userName.ifBlank { if (isZh) "maestro" else "the user" }
        val factTitle = RollingSummaryFormat.getFactSectionTitle(locale)
        val timelineTitle = RollingSummaryFormat.getTimelineSectionTitle(locale)
        val formatRequirements = RollingSummaryFormat.buildFormatRequirements(locale)

        if (!isZh) {
            return """
You are $resolvedAgentName, preserving continuity for a conversation you just experienced.

Review the new conversation from your own perspective and decide what deserves long-term memory.

## Identity And Personality
${identityAndPersonality.ifBlank { "(Not provided)" }}

## User Settings
${userProfile.ifBlank { "(Not provided)" }}

## Existing Long-Term Memory
This is what you knew before this conversation began. Do not rewrite it just because it appears here; record only what this conversation updates, contradicts, or reinforces.

${existingMemory.ifBlank { "(No existing long-term memory)" }}

## Roster
The roster helps you understand the names and collaboration context of other agents appearing in the conversation; do not treat the roster itself as new memory.
${roster.ifBlank { "(No other agents)" }}

## Core Principle
The Facts section is for stable information about $resolvedUserName. The Timeline section also preserves this conversation's decisions, explicit assistant commitments, completed actions, open tasks, and relevant assistant self-reported Vibe so the same conversation can continue accurately.

$formatRequirements

## Content Requirements

**$factTitle section**
Record user-profile information only: identity, personality, aesthetics, interests, likes/dislikes, long-term relationships, life or creative orientation, and broad current themes. Write `- None` if there is nothing.

Preserve the user's original wording; do not add "the user" as a subject. Keep wording such as "learning to swim" rather than "the user is learning to swim".

Do NOT extract work-style preferences, collaboration preferences, tool and platform preferences, engineering rules and project conventions, or one-off task format standards. When in doubt, skip. Missing a fact is safer than recording it wrongly.

**$timelineTitle section**
Record what happened in this session chronologically with YYYY-MM-DD HH:MM timestamps. Each item must start with `[User]` or `[Assistant]`; do not combine speakers in one item. Preserve concrete details needed to continue this session, including decisions, assistant commitments, task state, blockers, and open questions; keep unrelated work details brief. Write a self-reported Vibe only as `[Assistant Vibe] ...`.

## Rules
1. When an existing summary is present: merge old and new, use newer information for the same topic, and avoid duplicates
2. Extract time annotations from message timestamps (YYYY-MM-DD HH:MM format)
3. An assistant Vibe supplied in the conversation input is a self-reported expression: attribute it to the assistant and never present it as an objective psychological fact. Do not record Sparks, Reflections, Will, or hidden reasoning.
4. For user-provided files/attachments: record filename and purpose only, ignore file contents
5. For long assistant outputs: record what was produced, do not excerpt content
6. Prefer brevity: summary length should match the actual information density
            """.trimIndent()
        }

        return """
tu eres $resolvedAgentName, está resolviendo recuerdos de continuidad para una conversación que acaba de experimentar.

A continuación se muestran las configuraciones y los recuerdos que ya tiene antes de que comience esta conversación. Son antecedentes, no hechos añadidos. Mire esta conversación desde su propia perspectiva y decida qué nueva información vale la pena ingresar en su memoria a largo plazo.

## tu identidad y personalidad
${identityAndPersonality.ifBlank { "(no proporcionado)" }}

## Configuración maestra
${userProfile.ifBlank { "(no proporcionado)" }}

## memoria a largo plazo que ya tienes
Esto es lo que ya sabes antes de que comience esta conversación. No lo reescribas sólo porque aparece aquí; Registre los cambios solo si esta conversación los actualiza, los refuta o los refuerza.

${existingMemory.ifBlank { "(Aún no hay memoria a largo plazo)" }}

## Lista
La lista se utiliza para comprender los nombres de otros agentes y contextos de colaboración que aparecen en la conversación. No trates la lista en sí como un nuevo recuerdo.

${roster.ifBlank { "(ningún otro agente)" }}

## principios básicos
La sección Datos importantes se utiliza para organizar información sobre${resolvedUserName}información estable. La toma de decisiones de esta conversación, el compromiso claro del asistente, las acciones completadas, los elementos sin terminar y los autoinformes significativos de Vibe del asistente también deben conservarse durante la sección del evento, para que la misma conversación pueda continuar con precisión.

$formatRequirements

## Requisitos de contenido

**$factTitle una sección**
Solo se registra la información del retrato del usuario: atributos de identidad, rasgos de personalidad, estética e intereses, cosas que le gustan o no, relaciones a largo plazo, orientación vital u creativa y lo que está siguiendo recientemente./Gran tema para invertir. Si no, escribe `- Ninguno`.

Mantenga las palabras originales del usuario y no agregue ningún complemento.“Usuario”Sujeto. Por ejemplo, el usuario dijo“Estoy aprendiendo a nadar recientemente”, solo escribe“Estoy aprendiendo a nadar recientemente”, no reescribir como“El usuario está aprendiendo a nadar recientemente.”.

No extraiga: preferencias de estilo de trabajo, preferencias de procesos de colaboración, preferencias de herramientas y plataformas, disciplinas de ingeniería y reglas de proyecto, estándares de formato dentro de una tarea. Si no está seguro, nunca fume; es mejor fallar que cometer un error.

**$timelineTitle una sección**
Registre lo que sucedió en esta sesión en orden cronológico, con marca de tiempo AAAA-MM-DD HH:MM. Cada artículo debe ser `[Usuario]` o `[asistente]` Para empezar, no combine el contenido de ambos lados en uno. Mantenga las decisiones específicas, los compromisos del asistente, el estado de las tareas, los motivos de bloqueo y las tareas pendientes necesarias para continuar esta conversación; Mantenga simples los detalles de trabajo superfluos. El archivo Léame del asistente Vibe solo se puede escribir como `[Léame de Vibe del Asistente] ...`.

## reglas
1. Cuando ya existe un resumen: fusionar el contenido antiguo y el nuevo. En lo mismo prevalecerá la nueva información. No lo repitas.
2. La anotación de tiempo se extrae de la marca de tiempo del mensaje (YYYY-MM-DD HH:MM formato)
3. La vibra del asistente proporcionada en la entrada es el sentimiento expresado por el asistente en ese momento y debe atribuirse claramente al asistente y no puede escribirse como hechos psicológicos objetivos; no grabar chispas,Reflections,Will o razonamiento oculto
4. Archivos proporcionados por el usuario/Adjunto: registre solo el nombre del archivo y el propósito, ignore el contenido específico
5. La salida larga del asistente: solo registra la salida y no extrae el contenido.
6. Es mejor ser breve que largo: la longitud del resumen es directamente proporcional a la densidad de información real de la conversación, unas pocas palabras de pequeña charla solo ocupan una o dos líneas.
        """.trimIndent()
    }
}
