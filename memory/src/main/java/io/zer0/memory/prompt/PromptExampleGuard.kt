package io.zer0.memory.prompt

import io.zer0.memory.ai.ParsedAnalysis

/**
 * v2.x: Consejos de ejemplos de palabras para la prevención de fugas.
 *
 * fondo: Extraer palabras clave([io.zer0.memory.ai.MemoryExtractPrompt] / [FactExtractionPrompt])de
 * Ejemplo de demostración de zona de formato de salida(Como por ejemplo"alérgico a la penicilina"),Los modelos débiles a veces copian contenido de ejemplo en la salida.,haciendo que el ejemplo sea tratado como
 * La memoria real del usuario almacenada en la base de datos.(Comentarios de los usuarios:"penicilina"Espere a que aparezcan ejemplos en la memoria.).
 *
 * Reglas de decisión(Cubierta a nivel de texto):
 *  - Lista de palabras de ejemplo de resultados de entrada,pero"Introduce el texto original esta vez.(dialogo/Resumen)"la palabra nunca se menciona → Ejemplo considerado copiado,descartar;
 *  - De hecho, se mencionó en la conversación. → tratado como un recuerdo real,mantener como de costumbre(Sin lesiones accidentales).
 *
 * La lista de vocabulario solo cubre las palabras patentadas que aparecen en cada ejemplo de palabra clave.,Evite afectar el contenido normal.
 */
object PromptExampleGuard {

    /** Vocabulario de ejemplo:Palabras adecuadas que aparecen en cada palabra del mensaje Ejemplo de formato de salida(comparación de minúsculas). */
    private val EXAMPLE_TERMS: List<String> = listOf(
        // MemoryExtractPrompt(zh / en)Ejemplo de salida
        "penicilina", "amoxicilina", "americano", "Sr. Zhang", "Zhang San",
        "penicillin", "amoxicillin", "american coffee", "mr. zhang", "zhang san",
        // FactExtractionPrompt(zh / en)Ejemplo de salida
        "Preparándose para mudarse", "Primer borrador del artículo.", "preparing to move", "thesis draft",
    )

    /**
     * es el texto"Copié el ejemplo de la palabra clave."(Presione la palabra de ejemplo y el texto ingresado nunca la mencionó).
     *
     * @param text Texto a comprobar(Cuerpo del hecho/título/punto final del enlace, etc.)
     * @param conversationText El texto original de entrada extraído esta vez.(Historial/resumen de la conversación+Instantánea)
     */
    fun isUngroundedExample(text: String, conversationText: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        val conv = conversationText.lowercase()
        return EXAMPLE_TERMS.any { term -> t.contains(term) && !conv.contains(term) }
    }

    /**
     * Limpiar todo el resultado de la extracción.:Eliminar entradas que copien ejemplos.(Entidad/Actualizar/Fusionar/Enlace/Fila de imagen).
     */
    fun sanitize(analysis: ParsedAnalysis, conversationText: String): ParsedAnalysis {
        fun leaked(vararg parts: String?): Boolean = parts.any { p -> !p.isNullOrBlank() && isUngroundedExample(p, conversationText) }

        return analysis.copy(
            mainProblem = analysis.mainProblem?.takeUnless { leaked(it.title, it.content) },
            extractedEntities = analysis.extractedEntities
                .filterNot { leaked(it.title, it.content) },
            updatedEntities = analysis.updatedEntities
                .filterNot { leaked(it.matchTitle, it.newContent) },
            mergedEntities = analysis.mergedEntities.filterNot {
                leaked(it.mergedTitle, it.mergedContent) ||
                    it.sourceTitles.any { t -> isUngroundedExample(t, conversationText) }
            },
            links = analysis.links.filterNot { leaked(it.sourceTitle, it.targetTitle) },
            profileMarkdown = scrubMarkdown(analysis.profileMarkdown, conversationText),
        )
    }

    /** Limpieza línea por línea de Image Markdown:Elimina toda la línea copiada del ejemplo. */
    private fun scrubMarkdown(markdown: String?, conversationText: String): String? {
        if (markdown.isNullOrBlank()) return markdown
        val kept = markdown.lines().filterNot { line -> isUngroundedExample(line, conversationText) }
        return kept.joinToString("\n").trim().ifBlank { null }
    }
}
