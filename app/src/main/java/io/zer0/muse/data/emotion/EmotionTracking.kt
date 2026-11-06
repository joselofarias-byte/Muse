package io.zer0.muse.data.emotion

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5 5C: seguimiento del estado de ánimo — basado en mensajes `<mood>` Etiquetas y datos de opinión anotados manualmente por los usuarios.
 *
 * fuente de datos:
 * - AI en el mensaje `<mood>` XML etiqueta(Análisis automático)
 * - Marcado manual de usuario(Mensaje de pulsación larga → elige la emoción)
 *
 * mostrar:
 * - gráfico de líneas: X=tiempo, Y=emociones(-2 llegar +2)
 * - semana/mes/vista anual
 * - AI Resumen de humor semanal
 */
@Serializable
data class EmotionEntry(
    val id: String,
    val sessionId: String,
    val messageId: String? = null,
    /** valor emocional: -2(Muy negativo) llegar +2(Muy positivo). */
    val value: Float,
    /** Etiquetas de emoción: positive / negative / neutral / mixed. */
    val label: String = "neutral",
    /** Fuente: auto(mood tag) / manual(user marking). */
    val source: String = "auto",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val dateStr: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAt))
}

/**
 * Phase 5 5C: analizador de sentimientos — Mensajes de la IA `<mood>` Extraiga valores de sentimiento de las etiquetas.
 *
 * Formato: `<mood value="0.5" label="positive">feeling happy today</mood>`
 */
object MoodParser {

    private val MOOD_REGEX = Regex("""<mood\s+value="([^"]+)"\s+label="([^"]+)"[^>]*>([^<]*)</mood>""")

    /**
     * Analiza las etiquetas de estado de ánimo del texto del mensaje.
     *
     * @return Información emocional analizada,Devolución sin etiqueta null
     */
    fun parse(messageContent: String): MoodParseResult? {
        val match = MOOD_REGEX.find(messageContent) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        val label = match.groupValues[2]
        val description = match.groupValues[3].trim()
        return MoodParseResult(
            value = value.coerceIn(-2f, 2f),
            label = label,
            description = description,
        )
    }

    /**
     * Eliminar la etiqueta de estado de ánimo de los mensajes(Para exhibición).
     */
    fun stripMoodTags(content: String): String {
        return MOOD_REGEX.replace(content, "").trim()
    }
}

/** Resultados del análisis de sentimiento. */
data class MoodParseResult(
    val value: Float,
    val label: String,
    val description: String,
)

/**
 * Phase 5 5C: Estadísticas de sentimiento(por semana/mes/resumen del año).
 */
@Serializable
data class EmotionStats(
    val period: String,
    val entries: List<EmotionEntry> = emptyList(),
    val average: Float = 0f,
    val highest: Float = 0f,
    val lowest: Float = 0f,
    val positiveCount: Int = 0,
    val neutralCount: Int = 0,
    val negativeCount: Int = 0,
) {
    companion object {
        fun fromEntries(entries: List<EmotionEntry>, period: String): EmotionStats {
            if (entries.isEmpty()) return EmotionStats(period = period)
            val values = entries.map { it.value }
            return EmotionStats(
                period = period,
                entries = entries,
                average = values.average().toFloat(),
                highest = values.max(),
                lowest = values.min(),
                positiveCount = values.count { it > 0.3f },
                neutralCount = values.count { it in -0.3f..0.3f },
                negativeCount = values.count { it < -0.3f },
            )
        }
    }
}
