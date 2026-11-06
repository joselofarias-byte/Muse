package io.zer0.muse.ui.markdown

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable

/**
 * v1.0.53: Análisis de tarjetas de datos de diálogo.(Corresponde a la herramienta show_card).
 *
 * en el contenido del mensaje ```card bloque de código:
 * ```
 * ```card
 * {"type":"bar","title":"Volumen de noticias esta semana","labels":["uno","dos","tres"],"values":[12,30,18]}
 * ```
 * ```
 *
 * Devolver si falla el análisis null(La capa de renderizado se degrada para mostrar bloques de código tal como están).
 */
@Serializable
data class DataCard(
    val type: String, // bar | line | donut
    val title: String = "",
    val labels: List<String> = emptyList(),
    val values: List<Float> = emptyList(),
) {
    val isValid: Boolean
        get() = type in SUPPORTED_TYPES && labels.size == values.size && values.isNotEmpty() && labels.isNotEmpty()

    companion object {
        val SUPPORTED_TYPES = setOf("bar", "line", "donut")
    }
}

object DataCardParser {

    private const val CARD_BLOCK_START = "```card"

    /**
     * Extrae el primero del texto de rebajas. ```card bloquear y analizar.
     *
     * @return Analizando devoluciones exitosamente [DataCard];Sin bloques de cartas/Formato ilegal/Los datos se devuelven ilegalmente. null
     */
    fun parse(markdown: String): DataCard? {
        val startIdx = markdown.indexOf(CARD_BLOCK_START)
        if (startIdx < 0) return null
        val jsonStart = markdown.indexOf('\n', startIdx)
        if (jsonStart < 0) return null
        val jsonEnd = markdown.indexOf("```", jsonStart + 1)
        if (jsonEnd < 0) return null
        val jsonText = markdown.substring(jsonStart + 1, jsonEnd).trim()
        if (jsonText.isEmpty()) return null

        return parseJson(jsonText)
    }

    /** Analizar desde JSON simple(Para pruebas y llamadas independientes). */
    fun parseJson(jsonText: String): DataCard? {
        val card = runCatching {
            AppJson.decodeFromString(DataCard.serializer(), jsonText)
        }.getOrNull() ?: return null
        return if (card.isValid) card else null
    }

    /** Detectar si el texto contiene bloques de tarjetas(Para renderizar descarga). */
    fun containsCardBlock(markdown: String): Boolean = markdown.contains(CARD_BLOCK_START)
}
