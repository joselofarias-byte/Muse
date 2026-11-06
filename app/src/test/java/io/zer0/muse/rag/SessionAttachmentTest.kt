package io.zer0.muse.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionAttachmentTest {

    @Test
    fun `session attachment creates public knowledge metadata for retrieval`() {
        val attachment = SessionAttachment(
            id = "attachment-1",
            sessionId = "session-1",
            docId = SessionAttachment.buildDocId("session-1", "attachment-1"),
            name = "contrato.txt",
            status = SessionAttachmentStatus.READY,
        )

        val doc = attachment.toKnowledgeDoc("Texto del contrato")

        assertEquals(attachment.docId, doc.id)
        assertEquals("contrato.txt", doc.title)
        assertEquals("Texto del contrato", doc.content)
        assertEquals("session_attachment", doc.fileType)
        assertFalse(doc.isInternal)
    }
}
