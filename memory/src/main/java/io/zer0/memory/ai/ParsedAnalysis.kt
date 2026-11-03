package io.zer0.memory.ai

import kotlinx.serialization.Serializable

/**
 * v1.0.52 P2-3: AI Resultados del análisis de la memoria.
 *
 * por [MemoryExtractPrompt] Impulsar la salida LLM,por [MemoryAutoSaveScheduler.extractEntities]
 * analizado en esta estructura,Luego applyAnalysis elimina la biblioteca.(crear/actualizar/Fusionar hechos + crear bordes de gráficos de conocimiento).
 *
 * principios de diseño:
 *  - mainProblem: La pregunta principal de esta conversación.(Opcional,para título abstracto)
 *  - extractedEntities: Entidades recién extraídas(Escribir en la tabla de hechos)
 *  - links: relación entre entidades(Escribir en la tabla Memory_links,Borde del gráfico de conocimiento)
 *  - updatedEntities: Actualizaciones de hechos existentes(Coincidencia por título)
 *  - mergedEntities: Instrucciones de fusión de memoria similares(Eliminar duplicados)
 *  - profileMarkdown: Actualización de Markdown del retrato del usuario(Opcional,actualización de superposición)
 *
 * Todos los campos pueden estar vacíos/lista vacía,LLM Solo se puede generar una parte(Si solo se extraen entidades sin fusionar).
 */
@Serializable
data class ParsedAnalysis(
    /** La pregunta principal de esta conversación.(resumen de una frase,para el título de la sesión/Resumen). */
    val mainProblem: ParsedEntity? = null,
    /** Lista de entidades recién extraídas(se escribirá como un hecho nuevo). */
    val extractedEntities: List<ParsedEntity> = emptyList(),
    /** relación entre entidades(Borde del gráfico de conocimiento). */
    val links: List<ParsedLink> = emptyList(),
    /** Actualizaciones de hechos existentes(Datos de objetivos de coincidencia aproximada por título). */
    val updatedEntities: List<ParsedUpdate> = emptyList(),
    /** Instrucciones de fusión de memoria similares(Combine varios hechos similares en uno). */
    val mergedEntities: List<ParsedMerge> = emptyList(),
    /** Actualización de Markdown del retrato del usuario(Opcional,actualización de superposición). */
    val profileMarkdown: String? = null,
)

/** La base de datos de usuarios solo acepta entradas explícitamente atribuidas al USUARIO,Evite que la desalineación del sujeto y el objeto contamine la imagen. */
internal fun ParsedAnalysis.userFactsOnly(): List<ParsedEntity> =
    extractedEntities.filter { it.speaker.equals("USER", ignoreCase = true) }

/**
 * Entidades extraídas(hechos atómicos).
 *
 * con [io.zer0.memory.fact.FactStore.Fact] mapeo:
 *  - title → para hacer coincidir/Eliminar duplicados/mostrar título
 *  - content → fact Texto
 *  - credibility → confidence(0.0~1.0)
 *  - importance → importance(0/1/2,mapeado por flotador: <0.4→0, <0.7→1, else 2)
 *  - folderPath → category(Como por ejemplo "preference"/"identity"/"event"...)
 *  - tags → tags
 *  - entityKey → Clave de normalización de entidad(v12,Nombres canónicos escritos de diferentes formas para una misma entidad,Como por ejemplo"Zhang San")
 */
@Serializable
data class ParsedEntity(
    // LLM A veces, los campos opcionales se muestran explícitamente como nulos; los campos no nulos reciben valores predeterminados para que coincidan
    // MemoryAutoSaveScheduler Los coerceInputValues ​​permiten que un solo campo incorrecto no destruya toda la recuperación.
    val title: String = "",
    val content: String = "",
    /**
     * orador de hecho: USER para ingresar a la base de datos de datos del usuario; ASSISTANT/OTHER Se conserva como información de no usuario.
     *
     * El valor predeterminado debe ser cerrado a prueba de fallos: cuando el modelo pierde el altavoz de salida, el archivo Léame del asistente no se puede registrar por error como datos del usuario.
     */
    val speaker: String = "OTHER",
    val credibility: Float = 0.5f,
    val importance: Float = 0.5f,
    val folderPath: String? = null,
    val tags: List<String> = emptyList(),
    val entityKey: String? = null,
)

/**
 * relación entre entidades(Borde del gráfico de conocimiento).
 *
 * linkType valor:
 *  - causes: A causa/causa B
 *  - explains: A explicar/Descripción B
 *  - part_of: A es parte de B
 *  - related_to: asociación general(Predeterminado)
 *  - contradicts: A Contradictorio con B(Se utiliza al actualizar para marcar hechos antiguos como anulados.)
 *
 * weight 0.0~1.0,Indica fortaleza de la relación(0.5 Predeterminado).
 */
@Serializable
data class ParsedLink(
    val sourceTitle: String = "",
    val targetTitle: String = "",
    val linkType: String = "related_to",
    val weight: Float = 0.5f,
)

/**
 * Actualizar instrucciones para hechos existentes.
 *
 * Coincidencia difusa de hechos existentes por matchTitle,Reemplazar el cuerpo con nuevo contenido,
 * Y, opcionalmente, actualice importancia/categoría/etiquetas.
 */
@Serializable
data class ParsedUpdate(
    val matchTitle: String = "",
    val newContent: String = "",
    val newImportance: Float? = null,
    val newFolderPath: String? = null,
    val newTags: List<String>? = null,
)

/**
 * Instrucciones similares para fusionar memoria.
 *
 * Combine los hechos enumerados en sourceTitles en uno,toma el texto mergedContent,
 * Mantener el título fusionado como el nuevo título.,Hecho fuente marcado como fusionado(Eliminación temporal o eliminación definitiva).
 */
@Serializable
data class ParsedMerge(
    val sourceTitles: List<String> = emptyList(),
    val mergedTitle: String = "",
    val mergedContent: String = "",
)
