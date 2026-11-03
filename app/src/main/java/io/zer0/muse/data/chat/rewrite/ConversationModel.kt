package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.ConversationEventEntity
import java.security.MessageDigest

/** Agregar tipo de evento de conversación. */
enum class ConversationEventType {
    USER_SUBMITTED,
    ASSISTANT_STARTED,
    CONTENT_DELTA,
    REASONING_DELTA,
    TOOL_CALLED,
    TOOL_RESULT,
    TURN_FINISHED,
    TURN_INTERRUPTED,
    TURN_FAILED,
    MESSAGE_EDITED,
    MESSAGE_DELETED,
    BRANCH_CREATED,
    BRANCH_SELECTED,
}

/** Estado redondo persistente. */
enum class ConversationTurnPhase {
    CREATED,
    STREAMING,
    TOOL_ROUND,
    COMMITTING,
    COMPLETED,
    INTERRUPTED,
    FAILED,
    CANCELLED,
}

/** borrador de evento de memoria,eventSeq Asignado atómicamente por sesión por la base de datos. */
data class ConversationEventDraft(
    val sessionId: String,
    val turnId: String,
    val type: ConversationEventType,
    val payloadJson: String,
    val streamId: String? = null,
    val sequenceInStream: Long = 0,
    val generationSerial: Long = 0,
    val provider: String? = null,
    val modelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(turnId.isNotBlank()) { "turnId must not be blank" }
        require(sequenceInStream >= 0) { "sequenceInStream must not be negative" }
        require(generationSerial >= 0) { "generationSerial must not be negative" }
    }

    /** Reemplace las claves confidenciales en el cuerpo del evento con marcadores de posición fijos; la tabla de eventos no guarda las credenciales. */
    fun redactedPayload(): String = ConversationEventSanitizer.redact(payloadJson)

    /** Stable id for retry-safe event append; excludes createdAt so a retried logical event deduplicates. */
    fun deterministicEventId(): String = sha256(
        listOf(
            sessionId,
            turnId,
            type.name,
            streamId.orEmpty(),
            sequenceInStream.toString(),
            generationSerial.toString(),
            provider.orEmpty(),
            modelId.orEmpty(),
            redactedPayload(),
        ).joinToString("\u001f"),
    )

    fun toEntity(eventSeq: Long, eventId: String): ConversationEventEntity {
        val payload = redactedPayload()
        return ConversationEventEntity(
            sessionId = sessionId,
            eventSeq = eventSeq,
            eventId = eventId,
            turnId = turnId,
            streamId = streamId,
            type = type.name,
            payloadJson = payload,
            payloadHash = sha256(payload),
            payloadLength = payload.length,
            provider = provider,
            modelId = modelId,
            sequenceInStream = sequenceInStream,
            generationSerial = generationSerial,
            createdAt = createdAt,
        )
    }
}

/** Desensibilizador de resumen de eventos; solo maneja claves de credenciales comunes y no cambia el cuerpo del usuario normal. */
object ConversationEventSanitizer {
    private val secretPattern = Regex(
        """(?i)("(?:api[_-]?key|authorization|cookie|password|token|secret)"\s*:\s*")[^"]*(")""",
    )

    fun redact(payload: String): String = secretPattern.replace(payload) { match ->
        "${match.groupValues[1]}[REDACTED]${match.groupValues[2]}"
    }
}

/** Utilice SHA-256 para registrar resúmenes de eventos para evitar escribir el delta completo en el registro oculto. */
fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
