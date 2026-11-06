package io.zer0.muse.data.chat.rewrite

/** Una parte estructurada de un mensaje en streaming. */
data class StreamingPart(
    val kind: String,
    val text: String,
    val metadataJson: String = "{}",
)

/** Instantáneas de borrador de transmisión inmutable. */
data class MessageDraft(
    val turnId: String,
    val assistantId: String,
    val parts: List<StreamingPart>,
    val finished: Boolean,
) {
    val visibleText: String
        get() = parts.filter { it.kind == "text" }.joinToString("") { it.text }

    val reasoningText: String
        get() = parts.filter { it.kind == "reasoning" }.joinToString("") { it.text }
}

enum class FallbackDecision {
    ReplacedEmptyDraft,
    IgnoredBecauseVisibleContentExists,
    IgnoredBecauseFinished,
}

/**
 * Acumulador de flujo único.
 *
 * Evento por turnId,streamId y secuenciaInStream para eliminar duplicados; rechazar deltas posteriores después de la finalización,
 * Esto evita transmisiones antiguas o finalizaciones repetidas que contaminen las nuevas respuestas.
 */
class StreamingTurnBuffer(
    val turnId: String,
    val assistantId: String,
    val streamId: String,
) {
    private val parts = mutableListOf<StreamingPart>()
    private val acceptedSequences = mutableSetOf<Long>()
    private var nextSequence = 0L
    private var finished = false

    /** Agrega el texto delta; devuelve falso para indicar que el evento fue rechazado o repetido. */
    fun appendText(sequenceInStream: Long, delta: String): Boolean = append(sequenceInStream, "text", delta)

    /** Compatible con proveedores antiguos sin secuencia, utilizando números de secuencia incrementales locales. */
    fun appendText(delta: String): Boolean = appendText(nextSequence, delta)

    /** Añadir delta de razonamiento. */
    fun appendReasoning(sequenceInStream: Long, delta: String): Boolean = append(sequenceInStream, "reasoning", delta)

    /** Agregar pieza de herramienta. */
    fun appendTool(sequenceInStream: Long, toolName: String, metadataJson: String): Boolean =
        append(sequenceInStream, "tool", toolName, metadataJson)

    /**
     * Aplicar respaldo sin transmisión.
     * Nunca empalmes la finalización completa como un delta ordinario cuando ya haya texto visible.
     */
    fun applyFallback(fullText: String): FallbackDecision {
        if (finished) return FallbackDecision.IgnoredBecauseFinished
        if (parts.any { it.kind == "text" && it.text.isNotEmpty() }) {
            return FallbackDecision.IgnoredBecauseVisibleContentExists
        }
        parts.removeAll { it.kind == "text" }
        parts.add(StreamingPart("text", fullText))
        return FallbackDecision.ReplacedEmptyDraft
    }

    fun snapshot(): MessageDraft = MessageDraft(turnId, assistantId, parts.toList(), finished)

    fun finish(): MessageDraft {
        finished = true
        return snapshot()
    }

    private fun append(sequenceInStream: Long, kind: String, text: String, metadataJson: String = "{}"): Boolean {
        if (finished || sequenceInStream < 0 || !acceptedSequences.add(sequenceInStream)) return false
        nextSequence = maxOf(nextSequence, sequenceInStream + 1)
        if (text.isEmpty() && kind != "tool") return true
        parts.add(StreamingPart(kind, text, metadataJson))
        return true
    }
}
