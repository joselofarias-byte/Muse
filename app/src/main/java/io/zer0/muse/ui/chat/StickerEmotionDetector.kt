package io.zer0.muse.ui.chat

import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage

/**
 * Detector de emociones emoji(v1.0.52) — Puntuación de palabras clave locales,Sin dependencias de red.
 *
 * Propósito:Detectar la intensidad emocional de los mensajes de los usuarios en conversaciones recientes,Para modulación ChatStreamCoordinator
 * Probabilidad de exposición de la herramienta de emoticonos.(Cuando las emociones son fuertes, es más probable que la IA envíe emoticones).
 *
 * diseño:
 *  - Las funciones puras no tienen estado,Conveniente para pruebas individuales
 *  - Diccionario de palabras clave chino e inglés + bonificación de símbolo de estado de ánimo(! ! !/😭/😂 espera)
 *  - Salida emoción dominante + intensidad(0..1),NEUTRAL La intensidad es 0
 *
 * Nota:Esta es una heurística ligera.,No hacer PNL real. Solo cubre expresiones habladas comunes en el diccionario.,
 * El costo del error de juicio es bajo(La probabilidad de afectar el paquete de emoticones como máximo una vez.),Por tanto, no se realiza ninguna desambiguación compleja.
 */
object StickerEmotionDetector {

    enum class EmotionType { ANGRY, SAD, JOYFUL, EXCITED, NEUTRAL }

    data class EmotionResult(
        val dominant: EmotionType,
        /** 0..1,NEUTRAL Siempre 0. */
        val intensity: Float,
    )

    // ── diccionario de palabras clave(recuento de visitas) ──────────────────────────────────────────
    // Agrupar por categoría,Las entradas de cada grupo tienen el mismo peso.;Los símbolos de humor se ponderan individualmente.

    private val ANGRY_WORDS = listOf(
        "enojado", "enojado", "tan enojado", "Molesto hasta la muerte", "molesto", "odio", "Sin palabras", "asqueroso", "rodar", "cállate",
        "Ruidoso", "irritable", "fuego", "insatisfecho", "quejarse", "Mala reseña", "basura", "pozo", "escandaloso", "excesivo",
        "¿Qué diablos?", "¿Qué estás haciendo?", "Aceptado", "entumecido", "explotado", "no puedo soportarlo", "Suficiente", "Jaja", "Ja",
        "fuck", "angry", "wtf", "enojado", "molesto",
    )

    private val SAD_WORDS = listOf(
        "triste", "triste", "triste", "agraviado", "perdido", "frustrado", "abajo", "emo", "colapsar", "quiero llorar",
        "llorar", "Guau, guau", "¡Ay!", "Oye", "suspiro", "solitario", "solitario", "confundido", "ansiedad", "presión",
        "cansado", "cansado", "aburrido", "Aburrido", "Olvídalo", "eso es todo", "rendirse", "desesperación", "cansado", "fricción interna",
        "sad", "cry", "depressed", "lágrimas",
    )

    private val JOYFUL_WORDS = listOf(
        "Jaja", "Je, je", "feliz", "feliz", "feliz", "No está mal", "genial", "bueno", "genial", "Satisfecho",
        "cómodo", "genial", "Me gusta", "me gusta", "amor", "lindo", "divertido", "interesante", "interesante", "fragante",
        "happy", "great", "nice", "love", "si", "Ja",
    )

    private val EXCITED_WORDS = listOf(
        "Maldita sea", "impresionante", "vaca", "Absolutamente", "impresionante", "demasiado fuerte", "Arriba", "prisa", "despegar", "quemar",
        "estallar", "Conmocionado", "Impresionante", "inmortal", "yyds", "awesome", "amazing", "Invencible", "Superó las expectativas",
        "impresionante", "Excelente", "guau", "Guau", "Ahhhhh", "tan fuerte", "tan fragante",
    )

    // marcadores de humor:Anotado por separado,Mejora todas las emociones NO NEUTRALES
    private val STRONG_PUNCTUATION = listOf("! ! !", "!!!", "? ? ?", "???", "😡", "😭", "😂", "🤣", "🔥", "💢", "😤")

    // ── interfaz pública ─────────────────────────────────────────────────────────

    /**
     * Detecta el sentimiento de los mensajes recientes de los usuarios.
     *
     * @param messages Lista de mensajes de usuario(Obtener solo el rol de USUARIO,Internamente, se toma el más cercano en el orden en que se pasa. [limit] Artículo)
     * @param limit El número máximo de mensajes que participan en la detección.(Predeterminado 6)
     */
    fun detectEmotion(messages: List<UIMessage>, limit: Int = 6): EmotionResult {
        val userTexts = messages
            .filter { it.role == MessageRole.USER && it.content.isNotBlank() }
            .takeLast(limit)
            .joinToString("\n") { it.content.lowercase() }
        if (userTexts.isBlank()) return EmotionResult(EmotionType.NEUTRAL, 0f)

        val angry = score(ANGRY_WORDS, userTexts)
        val sad = score(SAD_WORDS, userTexts)
        val joyful = score(JOYFUL_WORDS, userTexts)
        val excited = score(EXCITED_WORDS, userTexts)

        val punctuationBoost = STRONG_PUNCTUATION.count { userTexts.contains(it) } * 0.15f

        val candidates = listOf(
            EmotionType.ANGRY to angry,
            EmotionType.SAD to sad,
            EmotionType.JOYFUL to joyful,
            EmotionType.EXCITED to excited,
        )
        val (dominant, rawScore) = candidates.maxByOrNull { it.second } ?: return EmotionResult(EmotionType.NEUTRAL, 0f)

        val total = rawScore + punctuationBoost
        if (total <= 0f) return EmotionResult(EmotionType.NEUTRAL, 0f)

        // normalización:3 Los puntos y más se consideran fuerza completa.(Aproximadamente igual a 3 aciertos de palabras clave potentes)
        val intensity = (total / 3f).coerceIn(0f, 1f)
        return EmotionResult(dominant, intensity)
    }

    // ── implementación interna ─────────────────────────────────────────────────────────

    /** Cuente la cantidad de palabras del texto que llegaron al diccionario. */
    private fun score(dictionary: List<String>, text: String): Float {
        return dictionary.count { text.contains(it) }.toFloat()
    }
}
