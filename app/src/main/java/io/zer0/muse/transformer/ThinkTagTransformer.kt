package io.zer0.muse.transformer

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.util.MusePatterns

/**
 * v1.131: <think>...</think> La etiqueta regex se ha migrado a io.zer0.muse.util.MusePatterns.THINK_TAG_REGEX.
 * este documento [stripThinkTags] / [ThinkTagTransformer] MusePatterns compartidos.THINK_TAG_REGEX.
 */

/**
 * v1.99: Quitar todo del texto `<think>...</think>` etiqueta,Devuelve texto puro.
 *
 * para la ruta de traducción([TranslateViewModel] / [ChatViewModel.translateMessage]):
 * Algunos modelos de razonamiento incorporan cadenas de pensamiento en el texto.(En lugar de pasar por el canal de contenido de razonamiento),
 * Los resultados de la traducción serán traídos. `<think>...</think>` etiqueta,Mostrarlo directamente al usuario tiene una mala experiencia.
 * Esta función se utiliza para [ThinkTagTransformer] Misma regularidad,Devuelve el texto restante después de eliminar todos los bloques de pensamiento.
 *
 * - `<think>pensar</think>Responder` → "Responder"
 * - `<think>pensar</think>` (Sin texto) → ""
 * - No pensar en etiqueta → Texto original
 */
fun stripThinkTags(text: String): String {
    if (text.isBlank()) return ""
    if (!text.contains("<think", ignoreCase = true)) return text
    val matches = MusePatterns.THINK_TAG_REGEX.findAll(text).toList()
    if (matches.isEmpty()) {
        // v1.0.74: No hay bloque cerrado pero existe <think> comienzo — no cerrado/truncar escena
        // (Algunos modelos solo emiten <think> El contenido de la inferencia se trunca.). En este momento, se trunca desde la etiqueta hasta el final.,
        // Evite que el contenido inferencial quede como texto principal(Título/diario/El contenido visible para el usuario, como las actualizaciones, está contaminado.).
        val idx = text.indexOf("<think", ignoreCase = true)
        return if (idx >= 0) text.substring(0, idx).trim() else text
    }
    val sb = StringBuilder(text.length)
    var lastEnd = 0
    for (m in matches) {
        sb.append(text, lastEnd, m.range.first)
        lastEnd = m.range.last + 1
    }
    sb.append(text, lastEnd, text.length)
    var result = sb.toString()
    // v1.0.74: Es posible que aún queden residuos sin cerrar después de retirar el bloque cerrado.(Como por ejemplo <think>A</think>Texto<think>B)
    val leftover = result.indexOf("<think", ignoreCase = true)
    if (leftover >= 0) {
        result = result.substring(0, leftover)
    }
    return result.trim()
}

/**
 * Extracción de un solo viaje [content] todos los partidos en [regex] de bloques.
 *
 * - Un único findAll recopila todas las coincidencias,Concatenar cadenas en una sola pasada(Evite el bucle removeRange O(n*k) copiar)
 * - Elimina el caparazón de la etiqueta del contenido independientemente de si el grupo de captura está vacío(Las etiquetas vacías también se eliminan)
 * - Varias piezas de contenido están conectadas con saltos de línea.,Filtrado de bloques vacíos
 *
 * @return Pair(Extraer contenido, texto despojado);Cuando el contenido extraído está vacío, la primera mitad se null
 */
private fun extractTagBlocks(content: String, regex: Regex): Pair<String?, String> {
    val matches = regex.findAll(content).toList()
    if (matches.isEmpty()) return null to content
    val extracted = matches
        .map { it.groupValues[1] }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
        .ifBlank { null }
    val sb = StringBuilder(content.length)
    var lastEnd = 0
    for (m in matches) {
        sb.append(content, lastEnd, m.range.first)
        lastEnd = m.range.last + 1
    }
    sb.append(content, lastEnd, content.length)
    return extracted to sb.toString()
}

/**
 * B10: Una función pura y liviana para eliminar en tiempo real la transmisión de ThinkTag.
 *
 * lógico Y [ThinkTagTransformer.visualTransform] completamente consistente,Extracto como función pura sin efectos secundarios.,
 * Para que la ruta de actualización de la interfaz de usuario de limitación de transmisión de ChatViewModel se llame directamente(Ya no use la tubería completa de TransformerPipeline):
 *  - cerrado `<think>...</think>` Extraer todo como reasoning
 *  - no cerrado en flujo `<think>foo`(todavía no `</think>`)Trata a foo como reasoning,
 *    Permita que los usuarios vean el proceso de pensamiento en tiempo real
 *
 * idempotente: [existingReasoning] Si no está vacío, devuélvelo como está.(Contenido del canal de inferencia existente,Sin extracción repetida).
 * Ninguno `<think>` Devolver como está cuando está marcado(camino rápido,Cero cambios de comportamiento).
 *
 * Nota: guardamonte(`<think>`)con [ThinkTagTransformer.visualTransform] ser consistente;
 * `<thinking>` Variantes como estas se manejan mediante la ruta de limpieza completa de updateAssistant,Esta función no asume el control.
 *
 * @return Pair(reasoning, content);reasoning Cuando no hay contenido, es null
 */
fun splitThinkTagsForStreaming(content: String, existingReasoning: String? = null): Pair<String?, String> {
    if (existingReasoning != null) return existingReasoning to content
    if (!content.contains("<think>", ignoreCase = true)) return null to content

    // 1. Saque primero los cerrados. <think>...</think> bloquear
    val (extractedClosed, remainingAfterClosed) = extractTagBlocks(content, MusePatterns.THINK_TAG_REGEX)

    // 2. Manejo especial de streaming: Detectar abierto <think>foo(Ninguno </think>),Mostrar foo también como razonamiento
    val unclosedIdx = remainingAfterClosed.indexOf("<think>", ignoreCase = true)
    val extractedUnclosed: String?
    val finalContent: String
    if (unclosedIdx >= 0) {
        extractedUnclosed = remainingAfterClosed
            .substring(unclosedIdx + "<think>".length)
            .trim()
            .ifBlank { null }
        finalContent = remainingAfterClosed.substring(0, unclosedIdx)
    } else {
        extractedUnclosed = null
        finalContent = remainingAfterClosed
    }

    val combinedReasoning = listOfNotNull(extractedClosed, extractedUnclosed)
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
        .ifBlank { null }

    if (combinedReasoning == null && finalContent == content) return null to content
    return combinedReasoning to finalContent.trim()
}

/**
 * Think etiqueta Transformer(Phase 8.1 H1).
 *
 * Coloque el contenido del mensaje ASISTENTE en `<think>...</think>` Etiqueta extraída a [UIMessage.reasoning] campo,
 * Haga que la interfaz de usuario sea plegable para mostrar la cadena de pensamiento,content Sólo se muestran las respuestas finales.
 *
 * tiempo de procesamiento: Mensajes históricos del ASISTENTE(persistió)Preprocesamiento antes de enviar a LLM.
 * El proveedor envía directamente el procesamiento mental de la respuesta de transmisión. ReasoningDelta,No camines con este Transformer.
 *
 * compatibles:
 *  - `<think>pensar</think>Responder` → reasoning="pensar", content="Responder"
 *  - `<think>pensar</think>` (Sin texto) → reasoning="pensar", content=""
 *  - `<think></think>` (etiqueta vacía) → reasoning=null, content=texto restante(Las etiquetas vacías también se eliminarán)
 *  - Etiquetas de pensamiento inigualables(Sin cierre) → Déjalo como está
 *  - Ya tengo campo de razonamiento. → → saltar(Sin extracción repetida)
 *
 * Nota: Este Transformer no admite etiquetas de pensamiento anidadas,La expresión regular no codiciosa solo coincidirá con la etiqueta de cierre más interna.
 * Muse Los productos no se anidarán,Por lo tanto, no se realiza ningún procesamiento de máquina de estados.
 *
 * Consejos de seguridad: reasoning como texto original,El contenido no está desinfectado.,UI La capa de renderizado debe escaparse por sí sola.(protegerse contra XSS).
 *
 * Escrito de forma independiente(Operaciones regulares + cadenas). Desacoplar la lógica de extracción de MoodTagTransformer,Cada uno implementa el mismo extractTag.
 */
class ThinkTagTransformer : Transformer {

    override val name: String = "ThinkTag"

    /**
     * Partido normal `<think>...</think>` etiqueta(no codicioso,a través de líneas,ignorar caso).
     * Usar el modo de una sola línea(dotAll)Dejar . coincidir con nuevas líneas.
     */
    private val thinkRegex = MusePatterns.THINK_TAG_REGEX

    /**
     * Extracción de etiquetas universales:de [content] Descúbrelo en [regex] Todos los bloques que coinciden.
     *  - [existing] Saltar directamente si no está vacío.(Volver al original content),Mantener los valores de campo existentes
     *  - De lo contrario, findAll recopila todas las coincidencias de una sola vez.:Eliminar etiquetas del contenido independientemente de si el grupo está vacío(Las etiquetas vacías también se eliminan),
     *    Varias piezas de contenido están conectadas con saltos de línea.,Evite el bucle removeRange O(n*k) copiar
     *
     * @return Pair(extracted valor, nuevo después del desmontaje content)
     */
    private fun extractTag(content: String, regex: Regex, existing: String?): Pair<String?, String> {
        if (existing != null) return existing to content
        return extractTagBlocks(content, regex)
    }

    override suspend fun transform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> = messages.map { msg ->
        if (msg.role != MessageRole.ASSISTANT) return@map msg
        if (msg.reasoning != null) return@map msg // Ya reasoning,saltar
        if (!msg.content.contains("<think>", ignoreCase = true)) return@map msg // camino rápido

        val (extracted, remaining) = extractTag(msg.content, thinkRegex, null)
        if (extracted == null && remaining == msg.content) {
            msg // Ninguna etiqueta de pensamiento completa coincide,Volver como está
        } else {
            msg.copy(
                reasoning = extracted,
                content = remaining.trim(),
            )
        }
    }

    /**
     * Transformación visual en streaming:Retire la etiqueta de pensamiento en el mensaje ASISTENTE al campo de razonamiento en tiempo real,
     * Permitir que la interfaz de usuario colapse y muestre la cadena de pensamiento durante el proceso de transmisión(content Mostrar solo texto).
     *
     * con [transform] diferencia:
     *  - [transform] Sólo proceso cerrado `<think>...</think>`(Preprocesamiento antes de enviar a LLM)
     *  - [visualTransform] Manejo adicional de flujos no cerrados `<think>foo`(Ninguno `</think>`),
     *    Mostrar foo también como razonamiento,Permitir a los usuarios ver el proceso de pensamiento de LLM en tiempo real
     *
     * Nota: Este método devuelve una nueva lista.(Para renderizado de interfaz de usuario),El mensaje real en poder de la persona que llama no se modifica.
     * Se omiten los mensajes que ya tienen campos de razonamiento.(Evite sobrescribir contenido ya extraído).
     */
    override suspend fun visualTransform(messages: List<UIMessage>, context: TransformContext): List<UIMessage> = messages.map { msg ->
        if (msg.role != MessageRole.ASSISTANT) return@map msg
        if (msg.reasoning != null) return@map msg // Ya reasoning,saltar
        // Función pura extraída por el delegado B10,Comparte la misma lógica de eliminación con la ruta de transmisión de ChatViewModel
        val (reasoning, content) = splitThinkTagsForStreaming(msg.content, msg.reasoning)
        if (reasoning == msg.reasoning && content == msg.content) {
            msg // Nada que extraer,Volver como está
        } else {
            msg.copy(reasoning = reasoning, content = content)
        }
    }
}
