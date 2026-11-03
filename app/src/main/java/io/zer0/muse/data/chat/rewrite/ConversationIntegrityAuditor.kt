package io.zer0.muse.data.chat.rewrite

import io.zer0.muse.data.session.GenerationCheckpointEntity
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageOutboxEntity

/** Resultados del diagnóstico de integridad de la sesión de solo lectura; no se reparará, eliminará ni renumerará ningún dato. */
data class ConversationIntegrityReport(
    val sessionId: String,
    val messageCount: Int,
    val duplicateSeqCount: Int,
    val zeroSeqCount: Int,
    val danglingParentMessageCount: Int,
    val danglingVariantGroupCount: Int,
    val pendingCheckpointCount: Int,
    val pendingOutboxCount: Int,
    val outboxOnDeletedSession: Boolean,
) {
    val ok: Boolean
        get() = duplicateSeqCount == 0 &&
            zeroSeqCount == 0 &&
            danglingParentMessageCount == 0 &&
            danglingVariantGroupCount == 0 &&
            !outboxOnDeletedSession
}

object ConversationIntegrityAuditor {
    fun audit(
        sessionId: String,
        messages: List<MessageEntity>,
        checkpoints: List<GenerationCheckpointEntity>,
        outboxes: List<MessageOutboxEntity>,
        sessionDeleted: Boolean,
    ): ConversationIntegrityReport {
        val seqCounts = messages.groupingBy { it.seq }.eachCount()
        val ids = messages.map { it.id }.toSet()
        val groups = messages.mapNotNull { it.variantGroupId }.toSet()
        val referencedParents = messages.mapNotNull { it.parentMessageId }
        val referencedGroups = messages.mapNotNull { it.variantGroupId }
        return ConversationIntegrityReport(
            sessionId = sessionId,
            messageCount = messages.size,
            duplicateSeqCount = seqCounts.values.count { it > 1 },
            zeroSeqCount = messages.count { it.seq == 0L },
            danglingParentMessageCount = referencedParents.count { it !in ids },
            danglingVariantGroupCount = referencedGroups.count { it !in groups },
            pendingCheckpointCount = checkpoints.count { it.sessionId == sessionId },
            pendingOutboxCount = outboxes.count { it.sessionId == sessionId },
            outboxOnDeletedSession = sessionDeleted && outboxes.any { it.sessionId == sessionId },
        )
    }
}
