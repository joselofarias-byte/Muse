package io.zer0.muse.data.miniphone

import io.zer0.muse.miniphone.api.MpReplyEvent
import kotlinx.coroutines.flow.FlowCollector

/**
 * La salida del modelo se corta en un segmento.:Para ser leído,O simplemente envíalo como mensaje de texto.
 *
 * [Speech] Es sólo que "el modelo requiere que leas este párrafo".,¿Puede finalmente convertirse [MpReplyEvent.Voice] ¿Quieres ver más?
 * ¿Está en chino? ¿La síntesis del host es exitosa? —— Ambos pueden ser degradados a [MpReplyEvent.Text].
 */
internal sealed interface MiniPhoneScriptPart {
    val text: String

    /** `[Voz]……[/Voz]` Un párrafo dentro de la etiqueta. */
    data class Speech(override val text: String) : MiniPhoneScriptPart

    /** Un párrafo fuera de la marca. */
    data class Plain(override val text: String) : MiniPhoneScriptPart
}

/**
 * Segmentación de respuestas de una ronda con etiquetas de voz.
 *
 * Se acuerda que solo hay dos marcas.:`[Voz]` comienzo,`[/Voz]` El fin. Un párrafo dentro de una etiqueta se convierte, en secuencia, [MiniPhoneScriptPart.Speech],
 * Todo lo que está fuera de la marca es siempre [MiniPhoneScriptPart.Plain],El marcado en sí no permanece en el texto de ningún párrafo.
 *
 * La mayoría en una sola ronda [MAX_VOICE_SEGMENTS] segmento de discurso:En ocasiones, el modelo estará marcado con más de diez párrafos seguidos.,Los segmentos excedentes se degradan a texto,
 * No dejes que una respuesta se convierta en una larga transmisión de voz.,Tampoco se traga lo que se suponía que debía decir el modelo.
 *
 * Las etiquetas no cerradas se tratan como texto normal.(`[Voz]` sólo para ser borrado,El contenido se envía como texto.):La media marca indica que el modelo no está terminado.,
 * En este momento, la mayoría de las palabras sintetizadas son solo media oración.
 */
internal object MiniPhoneScript {

    /** Una única ronda de respuesta puede sintetizar hasta varios segmentos de discurso. */
    const val MAX_VOICE_SEGMENTS: Int = 5

    private const val OPEN_TAG: String = "[Voz]"
    private const val CLOSE_TAG: String = "[/Voz]"

    /** Coincidencia no codiciosa de tokens emparejados;`[\s\S]` Deje que los saltos de línea dentro de los párrafos cuenten como contenido. */
    private val BLOCK: Regex = Regex("""\[Voz\]([\s\S]*?)\[/Voz\]""")

    /**
     * Cortar en secciones como se marca.
     *
     * @param raw Salida del modelo con etiquetas de pensamiento eliminadas
     * @param maxVoice ¿Cuántos párrafos se pueden producir como máximo este tiempo? [MiniPhoneScriptPart.Speech]
     * @return Párrafos ordenados por orden de aparición.;No se producen párrafos en blanco.,Devuelve una lista vacía si todos están en blanco
     */
    fun parse(raw: String, maxVoice: Int = MAX_VOICE_SEGMENTS): List<MiniPhoneScriptPart> {
        if (raw.isBlank()) return emptyList()
        val parts = mutableListOf<MiniPhoneScriptPart>()
        var cursor = 0
        var spoken = 0
        for (match in BLOCK.findAll(raw)) {
            parts.appendPlain(raw.substring(cursor, match.range.first))
            val inner = match.groupValues[1].trim()
            if (inner.isNotEmpty()) {
                if (spoken < maxVoice) {
                    spoken += 1
                    parts += MiniPhoneScriptPart.Speech(inner)
                } else {
                    parts += MiniPhoneScriptPart.Plain(inner)
                }
            }
            cursor = match.range.last + 1
        }
        parts.appendPlain(raw.substring(cursor))
        return parts
    }

    /** Un párrafo fuera de la marca.:Borra fácilmente la marca del pedido.,Luego presione si está en blanco para decidir si conservarlo o no. */
    private fun MutableList<MiniPhoneScriptPart>.appendPlain(chunk: String) {
        val text = chunk.replace(OPEN_TAG, "").replace(CLOSE_TAG, "").trim()
        if (text.isNotEmpty()) add(MiniPhoneScriptPart.Plain(text))
    }
}

/**
 * ¿Hay algo de chino en este texto?
 *
 * Los caracteres bilingües publicarán el texto original y el texto traducido juntos.:No hay caracteres chinos en la mitad inglesa.,Si se lo das a Chinese Sound Reading, solo obtendrás uno.
 * El sonido de hablar inglés torcido.,Sigue siendo un desperdicio de una cuota de síntesis. —— Entonces es el criterio de "si sintetizar o no".
 */
internal fun hasChineseCharacters(text: String): Boolean = text.any { char ->
    char.isLetter()
}

/**
 * Distribuya una ronda de respuestas en una serie de eventos.:Marcar texto saliente,Discurso sintetizado dentro de etiquetas,Si la síntesis falla, se enviará el texto.
 *
 * El orden es el orden de salida del modelo. —— Cada segmento de voz espera a que salga el resultado de la síntesis antes de continuar con el siguiente segmento.,Entonces la interfaz recibe
 * La secuencia de eventos es consistente con lo escrito en el modelo.(Lo que se dice primero aparece primero),El precio es que el texto posterior a este discurso tiene que esperar a que sea sintetizado.
 *
 * @param output Responder texto con la etiqueta think eliminada
 * @param synthesize Portal de síntesis de voz;Devolver nulo indica que la síntesis falló.
 */
internal suspend fun FlowCollector<MpReplyEvent>.emitVoiceScript(output: String, synthesize: suspend (String) -> Pair<String, Long>?) {
    val parts = MiniPhoneScript.parse(output)
    if (parts.isEmpty()) {
        emit(MpReplyEvent.Failed)
        return
    }
    for (part in parts) {
        when (part) {
            is MiniPhoneScriptPart.Plain -> emit(MpReplyEvent.Text(part.text))
            is MiniPhoneScriptPart.Speech -> emit(speechEvent(part.text, synthesize))
        }
    }
}

/** texto dentro de una etiqueta:Se puede sintetizar sólo si está en chino.,Si falla la síntesis o no hay chino, se reducirá a texto. */
private suspend fun speechEvent(text: String, synthesize: suspend (String) -> Pair<String, Long>?): MpReplyEvent {
    if (!hasChineseCharacters(text)) return MpReplyEvent.Text(text)
    val voice = synthesize(text) ?: return MpReplyEvent.Text(text)
    return MpReplyEvent.Voice(uri = voice.first, durationMs = voice.second)
}
