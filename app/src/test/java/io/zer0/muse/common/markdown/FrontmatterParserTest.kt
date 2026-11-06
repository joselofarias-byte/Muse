package io.zer0.muse.common.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.53: FrontmatterParser Pruebas unitarias(Guía de casos de uso de aceptación de la fase 0).
 */
class FrontmatterParserTest {

    @Test
    fun `Los tres campos completos del front-matter se analizan correctamente`() {
        val md = """
            ---
            title: Mis documentos
            cover: covers/hero.jpg
            description: Descripción de una frase
            ---
            # Título del texto
            Contenido del texto
        """.trimIndent()

        val fm = FrontmatterParser.parse(md)
        assertTrue(fm != null)
        assertEquals("Mis documentos", fm?.title)
        assertEquals("covers/hero.jpg", fm?.cover)
        assertEquals("Descripción de una frase", fm?.description)
        assertEquals(3, fm?.raw?.size)
    }

    @Test
    fun `Ningún texto inicial devuelve nulo y la tira devuelve sin cambios`() {
        val md = "# solo titulo\n\nsin metadatos"
        assertNull(FrontmatterParser.parse(md))
        assertEquals(md, FrontmatterParser.strip(md))
    }

    @Test
    fun `frontmatter devolución no cerrada null`() {
        val md = """
            ---
            title: no cerrado
        """.trimIndent()
        assertNull(FrontmatterParser.parse(md))
    }

    @Test
    fun `Análisis normal con BOM`() {
        val md = "\uFEFF---\ntitle: BOMDocumentación\n---\nTexto"
        val fm = FrontmatterParser.parse(md)
        assertTrue(fm != null)
        assertEquals("BOMDocumentación", fm?.title)
    }

    @Test
    fun `Eliminación del valor de las comillas`() {
        val md = "---\ntitle: \"Título citado\"\ncover: 'covers/x.jpg'\n---\n"
        val fm = FrontmatterParser.parse(md)
        assertEquals("Título citado", fm?.title)
        assertEquals("covers/x.jpg", fm?.cover)
    }

    @Test
    fun `El campo de valor nulo es null`() {
        val md = "---\ntitle:\ncover: \n---\nTexto"
        val fm = FrontmatterParser.parse(md)
        assertNull(fm?.title)
        assertNull(fm?.cover)
    }

    @Test
    fun `Las claves no distinguen entre mayúsculas y minúsculas y se eliminan los espacios`() {
        val md = "---\n  Title : tecla mayúscula\n---\n"
        val fm = FrontmatterParser.parse(md)
        assertEquals("tecla mayúscula", fm?.title)
    }

    @Test
    fun `Las líneas de sintaxis no admitidas se ignoran`() {
        val md = """
            ---
            title: normales
            tags:
              - a
              - b
            nested: { x: 1 }
            ---
            Texto
        """.trimIndent()
        val fm = FrontmatterParser.parse(md)
        assertTrue(fm != null)
        assertEquals("normales", fm?.title)
        // tags/nested No es una clave válida: valor de una sola línea,debe ser ignorado
        assertNull(fm?.raw?.get("tags"))
    }

    @Test
    fun `strip Conserva solo el texto`() {
        val md = "---\ntitle: Documentación\n---\n# Título del texto\nprimer párrafo"
        val body = FrontmatterParser.strip(md)
        assertTrue(body.startsWith("# Título del texto"))
        assertTrue(!body.contains("title:"))
    }

    @Test
    fun `withCover Crear nuevo y extraer del encabezado cuando no haya ningún tema inicial title`() {
        val md = "# mi articulo\n\nTexto"
        val out = FrontmatterParser.withCover(md, "covers/gen_001.jpg")
        val fm = FrontmatterParser.parse(out)
        assertEquals("mi articulo", fm?.title)
        assertEquals("covers/gen_001.jpg", fm?.cover)
        // Texto reservado
        assertTrue(out.contains("Texto"))
    }

    @Test
    fun `withCover Reemplazar la línea de portada cuando ya exista el front-matter`() {
        val md = "---\ntitle: documentos antiguos\ncover: covers/old.jpg\n---\nTexto"
        val out = FrontmatterParser.withCover(md, "covers/new.jpg")
        val fm = FrontmatterParser.parse(out)
        assertEquals("covers/new.jpg", fm?.cover)
        assertEquals("documentos antiguos", fm?.title)
        // No genere líneas de portada duplicadas
        val coverLines = out.lineSequence().filter { it.trimStart().startsWith("cover:") }.count()
        assertEquals(1, coverLines)
    }

    @Test
    fun `withCover Insertar cuando ya hay frontmatter y no hay portada`() {
        val md = "---\ntitle: Sin documento de portada\n---\nTexto"
        val out = FrontmatterParser.withCover(md, "covers/added.jpg")
        val fm = FrontmatterParser.parse(out)
        assertEquals("covers/added.jpg", fm?.cover)
        assertEquals("Sin documento de portada", fm?.title)
    }
}
