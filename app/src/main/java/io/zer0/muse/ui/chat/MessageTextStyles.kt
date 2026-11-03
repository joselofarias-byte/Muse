package io.zer0.muse.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import io.zer0.muse.ui.theme.MoodSkinColors

/**
 * Función auxiliar de estilo de texto de mensaje.
 *
 * Separado de MessageBubble.kt, céntrese en los efectos especiales en línea del estado de ánimo y el resaltado de búsqueda.
 * Evite seguir inflando la burbuja del mensaje en una sola fila.
 */

/** B6-02: poner [glow]/[big]/[shake] Espere a que los efectos en línea se conviertan al estilo AnnotatedString. */
internal fun buildMoodSkinAnnotated(text: String): AnnotatedString {
    val regex = Regex("""\[(glow|big|huge|whisper|red|shake|blur|glitch)\]([\s\S]*?)\[/\1\]""", RegexOption.IGNORE_CASE)
    val matches = regex.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var last = 0
        for (m in matches) {
            append(text, last, m.range.first)
            withStyle(moodSkinEffectStyle(m.groupValues[1].lowercase())) {
                append(m.groupValues[2])
            }
            last = m.range.last + 1
        }
        append(text, last, text.length)
    }
}

// Los efectos especiales emocionales utilizan tamaños de fuente fijos, son estilos decorativos en línea y no ingresan al nivel de diseño del texto.
private fun moodSkinEffectStyle(effect: String): SpanStyle = when (effect) {
    "glow" -> SpanStyle(color = MoodSkinColors.glow, fontWeight = FontWeight.SemiBold)
    "big" -> SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold) // mood effect
    "huge" -> SpanStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold) // mood effect
    "whisper" -> SpanStyle(fontSize = 13.sp, color = MoodSkinColors.whisper) // mood effect
    "red" -> SpanStyle(color = MoodSkinColors.red, fontWeight = FontWeight.Bold)
    "shake" -> SpanStyle(color = MoodSkinColors.shake, letterSpacing = 1.sp)
    "blur" -> SpanStyle(color = MoodSkinColors.blur)
    "glitch" -> SpanStyle(color = MoodSkinColors.glitch, letterSpacing = 2.sp)
    else -> SpanStyle()
}

/** Función1: Construye una AnnotatedString con resaltado. */
@Composable
internal fun buildHighlightedText(text: String, query: String): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer
    return buildAnnotatedString {
        var start = 0
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        while (true) {
            val index = lowerText.indexOf(lowerQuery, start)
            if (index < 0) {
                append(text.substring(start))
                break
            }
            append(text.substring(start, index))
            withStyle(SpanStyle(background = highlightColor, color = onHighlight)) {
                append(text.substring(index, index + query.length))
            }
            start = index + query.length
        }
    }
}
