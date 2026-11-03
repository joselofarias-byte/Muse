package io.zer0.muse.doc

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R-TEST-07: Ruta de error de indexación de documentos de la base de conocimientos — markdown / Análisis de doble extensión .md.txt/docx.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class DocumentParserMarkdownDocxTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val parser = DocumentParser()

    @Test
    fun `markdown file parses as text`() {
        val file = writeTempFile("note.md", "# Título\n\nContenido del texto")
        val result = parser.parseResult(Uri.fromFile(file), context)
        assertTrue("markdown El análisis falló: $result", result.isSuccess)
        assertEquals("# Título\n\nContenido del texto", result.getOrNull())
    }

    @Test
    fun `double extension md txt parses as text`() {
        val file = writeTempFile("note.md.txt", "Contenido de doble extensión")
        val result = parser.parseResult(Uri.fromFile(file), context)
        assertTrue("md.txt El análisis falló: $result", result.isSuccess)
        assertEquals("Contenido de doble extensión", result.getOrNull())
    }

    @Test
    fun `minimal docx extracts paragraph text`() {
        val file = createMinimalDocx("primer párrafo", "Segundo párrafo")
        val result = parser.parseResult(Uri.fromFile(file), context)
        assertTrue("docx El análisis falló: $result", result.isSuccess)
        val text = result.getOrNull() ?: ""
        assertTrue(text.contains("primer párrafo"))
        assertTrue(text.contains("Segundo párrafo"))
    }

    private fun writeTempFile(name: String, content: String): File {
        val file = File.createTempFile(name.replace('.', '_'), ".tmp", context.cacheDir)
        file.writeText(content)
        val renamed = File(context.cacheDir, name)
        file.renameTo(renamed)
        return renamed
    }

    private fun createMinimalDocx(vararg paragraphs: String): File {
        val file = File.createTempFile("docx_test_", ".docx", context.cacheDir)
        val xml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
            append("<w:body>")
            paragraphs.forEach { append("<w:p><w:r><w:t>").append(it).append("</w:t></w:r></w:p>") }
            append("</w:body></w:document>")
        }
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return file
    }
}
