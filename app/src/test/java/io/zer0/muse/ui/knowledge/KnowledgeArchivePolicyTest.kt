package io.zer0.muse.ui.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Archive import policy tests; this class has no Android dependencies. */
class KnowledgeArchivePolicyTest {

    @Test
    fun `format detection recognizes zip 7z and rar without case sensitivity`() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.fromFileName("bundle.ZIP"))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveFormat.fromFileName("library.7z"))
        assertEquals(ArchiveFormat.RAR, ArchiveFormat.fromFileName("información.RAR"))
        assertNull(ArchiveFormat.fromFileName("library.tar"))
    }

    @Test
    fun `classify separates text parsed and skip`() {
        assertEquals(ArchiveEntryKind.TEXT, KnowledgeArchivePolicy.classify("docs/a.md"))
        assertEquals(ArchiveEntryKind.TEXT, KnowledgeArchivePolicy.classify("READ_ME.TXT"))
        assertEquals(ArchiveEntryKind.TEXT, KnowledgeArchivePolicy.classify("data/x.csv"))
        assertEquals(ArchiveEntryKind.PARSED, KnowledgeArchivePolicy.classify("libro.pdf"))
        assertEquals(ArchiveEntryKind.PARSED, KnowledgeArchivePolicy.classify("legacy.doc"))
        assertEquals(ArchiveEntryKind.PARSED, KnowledgeArchivePolicy.classify("a/b/c.docx"))
        assertEquals(ArchiveEntryKind.SKIP, KnowledgeArchivePolicy.classify("nested.zip"))
        assertEquals(ArchiveEntryKind.SKIP, KnowledgeArchivePolicy.classify("app.exe"))
        assertEquals(ArchiveEntryKind.SKIP, KnowledgeArchivePolicy.classify("photo.png"))
        assertEquals(ArchiveEntryKind.SKIP, KnowledgeArchivePolicy.classify("noext"))
    }

    @Test
    fun `junk filter covers dirs hidden and macosx`() {
        assertTrue(KnowledgeArchivePolicy.isJunk("dir/"))
        assertTrue(KnowledgeArchivePolicy.isJunk("__MACOSX/x.txt"))
        assertTrue(KnowledgeArchivePolicy.isJunk(".hidden"))
        assertTrue(KnowledgeArchivePolicy.isJunk("a/.DS_Store"))
        assertTrue(KnowledgeArchivePolicy.isJunk(""))
        assertTrue(KnowledgeArchivePolicy.isJunk("nested\\.hidden"))
        assertFalse(KnowledgeArchivePolicy.isJunk("documento normal.pdf"))
        assertFalse(KnowledgeArchivePolicy.isJunk("dir/file.txt"))
    }

    @Test
    fun `temp name keeps extension and strips path`() {
        val name = KnowledgeArchivePolicy.sanitizeTempName("Directorio/informe v2.pdf")
        assertTrue(name.endsWith(".pdf"))
        assertFalse(name.contains('/'))
        val noExt = KnowledgeArchivePolicy.sanitizeTempName("README")
        assertEquals("entry_README", noExt)
    }

    @Test
    fun `archive guard leaves supported bundles available and rejects other archive formats`() {
        assertFalse(KnowledgeArchivePolicy.isArchiveFile("bundle.zip"))
        assertFalse(KnowledgeArchivePolicy.isArchiveFile("dir/paquete.7z"))
        assertFalse(KnowledgeArchivePolicy.isArchiveFile("a.rar"))
        assertTrue(KnowledgeArchivePolicy.isArchiveFile("copia de seguridad.TAR"))
        assertTrue(KnowledgeArchivePolicy.isArchiveFile("x.tgz"))
        assertFalse(KnowledgeArchivePolicy.isArchiveFile("documento ordinario.txt"))
        assertFalse(KnowledgeArchivePolicy.isArchiveFile("sin extensión"))
    }

    @Test
    fun `file type helpers match single file import convention`() {
        assertEquals("md", KnowledgeArchivePolicy.textFileType("a.MD"))
        assertEquals("txt", KnowledgeArchivePolicy.textFileType("a.txt"))
        assertEquals("pdf", KnowledgeArchivePolicy.parsedFileType("a.pdf"))
        assertEquals("docx", KnowledgeArchivePolicy.parsedFileType("a.doc"))
    }
}
