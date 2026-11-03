package io.zer0.memory.ai

/**
 * v1.0.52 P2-3: Generador de palabras de solicitud de extracción de IA de memoria.
 *
 * usado para [MemoryAutoSaveScheduler.extractEntities] —— Extraiga la memoria estructurada del historial de conversaciones:
 *  - entidad(hechos atómicos)+ relación entre entidades(Borde del gráfico de conocimiento)
 *  - Actualización de hechos existentes + fusión de memoria similar
 *  - Actualización de Markdown del retrato del usuario
 *
 * Generar un objeto JSON estricto(conformarse a [ParsedAnalysis] estructura),Sin vallas de rebajas.
 *
 * con [io.zer0.memory.prompt.FactExtractionPrompt] la diferencia:
 *  - FactExtractionPrompt: Extraiga datos atómicos del resumen de diferencias(No importa/fusionar/retrato)
 *  - MemoryExtractPrompt: Extraiga recuerdos estructurados de conversaciones completas(Contiene relación/fusionar/retrato),Más funciones
 * Ambos pueden coexistir:FactExtraction usado para daily deep pipeline,MemoryExtract en tiempo real autoSave
 */
object MemoryExtractPrompt {

    // D3: Suplemento de directrices de énfasis y límites(claro"Que vale la pena recordar"criterio) → v3
    const val TEMPLATE_VERSION = "memory-extract.v3"
    const val CACHE_GROUP = "memory.extract_analysis"

    /**
     * Indicador del sistema de compilación.
     *
     * @param locale Idioma(zh-CN / en-US ...)
     * @param existingFactsPreview Vista previa de hechos ya disponible(Opcional,Se utiliza para eliminar duplicados./fusionar/Referencia de actualización)
     *        Formato: "- {title}: {content}" uno por línea,Hasta 20 artículos
     */
    fun buildSystemPrompt(locale: String = "es", existingFactsPreview: String? = null): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }

        val existingSection = if (!existingFactsPreview.isNullOrBlank()) {
            if (isZh) {
                """
## ya tengo memoria(Se utiliza para eliminar duplicados./fusionar/Referencia de actualización)

$existingFactsPreview

Al recuperar, verifique si hay duplicaciones con memorias existentes./conflicto:
 - Si la nueva entidad duplica semánticamente una memoria existente,Poner fusionadasEntidades fusionadas
 - Si la nueva entidad corrige/Memoria existente actualizada,poner en updatedEntities
 - Si no hay duplicación con la memoria existente,poner en extractedEntities
                """.trimIndent()
            } else {
                """
## Existing Memories (for dedup/merge/update reference)

$existingFactsPreview

Check for duplicates/conflicts with existing memories when extracting:
 - If a new entity semantically duplicates an existing memory, put it in mergedEntities
 - If a new entity corrects/updates an existing memory, put it in updatedEntities
 - If no duplicate with existing, put it in extractedEntities
                """.trimIndent()
            }
        } else {
            ""
        }

        if (isZh) {
            return """
Eres un analista de la memoria. Analizar conversaciones de usuarios con asistentes,Recuperación de la memoria estructurada.

$existingSection

## Reglas de extracción

1. mainProblem: Resume la pregunta principal de esta conversación en una oración.(¿Qué quieren resolver los usuarios?),Opcional.

2. extractedEntities: Datos atómicos recién extraídos(Recuerda solo una cosa por entrada.).
   - title: título corto(≤20palabra,para mostrar y combinar)
   - content: texto fáctico(Mantener la expresión original.,sin sujeto)
   - speaker: orador de hecho,solo puede ser USER / ASSISTANT / OTHER;Los datos del usuario deben estar marcados. USER
   - credibility: 0.0~1.0,El usuario declara explícitamente que 1.0,inferir 0.5~0.8
   - importance: 0.0~1.0,<0.4 ordinario,0.4~0.7 importante,>0.7 clave(medico/Finanzas/seguro);
      Los usuarios enfatizan, establecen o expresan límites claramente./tabú("no lo hagas…""no me gusta ser…")El contenido de al menos 0.4
   - folderPath: Clasificación(preference/identity/event/relationship/goal/medical/other)
   - tags: 2~5 Palabras clave distintivas
   - entityKey: Clave de normalización de la entidad. Cuando el sujeto del hecho es el nombre de una persona específica/título(Como por ejemplo"Sr. Zhang")tiempo,
     Complete el nombre más estandarizado de la entidad.(Como por ejemplo"Zhang San");La misma entidad debe usarse en diferentes entradas con exactamente el mismo entityKey;
     Complete null en otros casos.

3. links: relación entre entidades(Borde del gráfico de conocimiento).
   - sourceTitle / targetTitle Debe ser consistente con el título de una entidad.
   - linkType: causes(causa y efecto)/ explains(explicar)/ part_of(contiene)/ related_to(asociación)/ contradicts(contradicción)
   - weight: 0.0~1.0 fuerza de la relación

4. updatedEntities: Actualizaciones de recuerdos existentes.(Partido difuso por título de partido).
   - matchTitle: ya recordado title(Puede coincidir parcialmente)
   - newContent: Texto reemplazado
   - newImportance / newFolderPath / newTags: Actualizaciones opcionales(no actualizar null)

5. mergedEntities: Fusión de memoria similar(Eliminar duplicados).
   - sourceTitles: Ya hay elementos para fusionar/Nueva lista de títulos de memoria
   - mergedTitle / mergedContent: Nuevo título y texto del cuerpo fusionados

6. profileMarkdown: Actualización de Markdown del retrato del usuario(Opcional,Cubierto).
   Contiene identidad/Personalidad/Interés/objetivos a largo plazo, etc.,El formato es como:
   ## identidad
   - nombre: Zhang San
   ## Interés
   - sistema de memoria,AI Agent

7. Extracción prohibida:
   - No escriba la autodescripción, los compromisos, las preferencias y los planes temporales del asistente en los datos del usuario.;Este tipo de orador de contenidos debe ser ASSISTANT,La base de datos del usuario no se escribe posteriormente en
   - Preferencias de flujo de trabajo, preferencias de herramientas, reglas de ingeniería, detalles de implementación
   - Actividades internas del asistente e información de depuración temporal
   - Detalles de la misión única(Como por ejemplo"Línea modificada y del archivo xxx")
   - Contenido de demostración en el siguiente ejemplo.(El ejemplo solo demuestra el formato JSON.,La copia está estrictamente prohibida.;Todas las entradas deben ser de esta conversación.)

8. Si la conversación no vale la pena recordar,Devolver estructura vacía(Todas las matrices están vacías.,mainProblem para null).

## Formato de salida

Objeto JSON estricto,Sin bloques de código de rebajas:
{
  "mainProblem": {"title": "...", "content": "...", "credibility": 0.8, "importance": 0.5, "folderPath": "event", "tags": ["..."]},
  "extractedEntities": [
    {"title": "alérgico a la penicilina", "content": "alérgico a la penicilina", "speaker": "USER", "credibility": 1.0, "importance": 0.9, "folderPath": "medical", "tags": ["medico","Alergia"], "entityKey": null},
    {"title": "Al Sr. Zhang le gusta el café americano.", "content": "Al Sr. Zhang le gusta el café americano.", "credibility": 1.0, "importance": 0.5, "folderPath": "preference", "tags": ["cafe"], "entityKey": "Zhang San"}
  ],
  "links": [
    {"sourceTitle": "alérgico a la penicilina", "targetTitle": "Tratar con amoxicilina", "linkType": "contradicts", "weight": 0.9}
  ],
  "updatedEntities": [],
  "mergedEntities": [],
  "profileMarkdown": null
}
            """.trimIndent()
        }

        return """
You are a memory analyst. Analyze the conversation between the user and the assistant, and extract structured memories.

$existingSection

## Extraction Rules

1. mainProblem: A one-sentence summary of the main problem in this conversation (what the user wanted to solve), optional.

2. extractedEntities: Newly extracted atomic facts (one fact per entry).
   - title: Short title (≤20 chars, for display and matching)
   - content: Fact body (preserve original wording, do not add subject)
   - speaker: Fact speaker, one of USER / ASSISTANT / OTHER; user facts must be USER
   - credibility: 0.0~1.0, 1.0 for explicit user statements, 0.5~0.8 for inference
   - importance: 0.0~1.0, <0.4 normal, 0.4~0.7 important, >0.7 critical (medical/financial/safety);
      content the user explicitly emphasizes, states seriously, or expresses as boundaries/taboos ("don't...", "I don't like being...") should be at least 0.4
   - folderPath: Category (preference/identity/event/relationship/goal/medical/other)
   - tags: 2-5 distinctive keywords
   - entityKey: Entity normalization key. When the fact's subject is a specific person's name/title
     (e.g. "Mr. Zhang"), set it to the canonical name (e.g. "Zhang San"); the same entity MUST use
     exactly the same entityKey across entries. Use null otherwise.

3. links: Relationships between entities (knowledge graph edges).
   - sourceTitle / targetTitle must match an entity's title
   - linkType: causes / explains / part_of / related_to / contradicts
   - weight: 0.0~1.0 relationship strength

4. updatedEntities: Updates to existing memories (matched by matchTitle, fuzzy match allowed).
   - matchTitle: Title of existing memory (partial match allowed)
   - newContent: Replacement body
   - newImportance / newFolderPath / newTags: Optional updates (null to skip)

5. mergedEntities: Similar memory merges (deduplication).
   - sourceTitles: List of existing/new memory titles to merge
   - mergedTitle / mergedContent: New title and body after merge

6. profileMarkdown: User profile Markdown update (optional, overwrites).
   Includes identity/personality/interests/long-term goals, formatted like:
   ## Identity
   - Name: John
   ## Interests
   - Memory systems, AI Agents

7. Do NOT extract:
   - Do not turn the assistant's self-statements, promises, preferences, or temporary plans into user facts; mark them ASSISTANT so they are rejected from the user fact store
   - Workflow preferences, tool preferences, engineering rules, execution details
   - Assistant's inner thoughts, temporary debugging info
   - One-off task details (e.g. "modified line Y of file X")
   - Demo content from the examples below (examples illustrate JSON format only; never copy them; every entry must come from this conversation)

8. If nothing in the conversation is worth remembering, return an empty structure (all arrays empty, mainProblem null).

## Output Format

Strict JSON object, no markdown code fences:
{
  "mainProblem": {"title": "...", "content": "...", "credibility": 0.8, "importance": 0.5, "folderPath": "event", "tags": ["..."]},
  "extractedEntities": [
    {"title": "allergic to penicillin", "content": "allergic to penicillin", "speaker": "USER", "credibility": 1.0, "importance": 0.9, "folderPath": "medical", "tags": ["medical","allergy"], "entityKey": null},
    {"title": "Mr. Zhang prefers American coffee", "content": "Mr. Zhang prefers American coffee", "credibility": 1.0, "importance": 0.5, "folderPath": "preference", "tags": ["coffee"], "entityKey": "Zhang San"}
  ],
  "links": [
    {"sourceTitle": "allergic to penicillin", "targetTitle": "treated with amoxicillin", "linkType": "contradicts", "weight": 0.9}
  ],
  "updatedEntities": [],
  "mergedEntities": [],
  "profileMarkdown": null
}
        """.trimIndent()
    }

    /**
     * construir autoCategorize(Clasificación automática de recuerdos no clasificados.)aviso del sistema.
     *
     * usado para [MemoryAutoSaveScheduler.autoCategorizeMemories] —— La ruta de la carpeta del lote está vacía
     * Clasificación de asignación de memoria.
     *
     * @param locale Idioma
     */
    fun buildCategorizeSystemPrompt(locale: String = "es"): String {
        val isZh = locale.let { it.startsWith("es") || it.startsWith("zh") }
        if (isZh) {
            return """
Eres un clasificador de memoria. Asignar cada memoria sin categorizar folderPath(Categorías).

## Reglas de clasificación

folderPath valor:
 - preference: Preferencia/odio(Como por ejemplo"como el cafe americano""Odio el cilantro")
 - identity: identidad/Personalidad(Como por ejemplo"Nombre Zhang San""introversión")
 - event: evento/planear(Como por ejemplo"Informe entregado el miércoles""Mudarse la próxima semana")
 - relationship: relación(Como por ejemplo"El nombre de mi esposa es Li Si.""Colega Wang Wu")
 - goal: objetivo/soñar(Como por ejemplo"¿Quieres aprender japonés?""Planea correr para perder peso.")
 - medical: medico/salud(Como por ejemplo"alergia a la penicilina""hipertensión")
 - other: Otros(No se puede clasificar en las categorías anteriores.)

## Formato de salida

Matriz JSON estricta,Cada entrada contiene el título original y el nombre asignado. folderPath:
[
  {"title": "...", "folderPath": "preference"},
  {"title": "...", "folderPath": "medical"}
]

Sin bloques de código de rebajas. Si no se puede clasificar,asignar "other".
            """.trimIndent()
        }
        return """
You are a memory categorizer. Assign a folderPath (category directory) to each uncategorized memory.

## Categories

folderPath values:
 - preference: Likes/dislikes (e.g. "likes americano", "hates cilantro")
 - identity: Identity/personality (e.g. "name is John", "introverted")
 - event: Events/plans (e.g. "report due Wednesday", "moving next week")
 - relationship: Relationships (e.g. "wife is Jane", "coworker Bob")
 - goal: Goals/dreams (e.g. "wants to learn Japanese", "plans to run for weight loss")
 - medical: Medical/health (e.g. "allergic to penicillin", "high blood pressure")
 - other: Other (cannot fit above categories)

## Output Format

Strict JSON array, each entry contains the original title and assigned folderPath:
[
  {"title": "...", "folderPath": "preference"},
  {"title": "...", "folderPath": "medical"}
]

No markdown code fences. If unable to categorize, assign "other".
        """.trimIndent()
    }
}
