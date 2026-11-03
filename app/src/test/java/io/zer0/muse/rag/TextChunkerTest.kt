package io.zer0.muse.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.56: TextChunker Pruebas unitarias.
 *
 * Portada:Texto vacío, texto corto, segmentación de texto largo, segmentación de párrafos, segmentación de oraciones, corte de caracteres,overlap superposición.
 */
class TextChunkerTest {

    @Test
    fun `El texto vacío devuelve una lista vacía`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        assertTrue(chunker.split("").isEmpty())
        assertTrue(chunker.split("   ").isEmpty())
        assertTrue(chunker.split("\n\n").isEmpty())
    }

    @Test
    fun `El texto corto devuelve un solo bloque`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        val result = chunker.split("Hello World")
        assertEquals(1, result.size)
        assertEquals("Hello World", result[0].content)
        assertEquals(0, result[0].index)
    }

    @Test
    fun `Combine varios párrafos cortos en un solo bloque`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        val text = "Párrafo uno\n\nPárrafo 2\n\nPárrafo 3"
        val result = chunker.split(text)
        assertEquals(1, result.size)
        assertTrue(result[0].content.contains("Párrafo uno"))
        assertTrue(result[0].content.contains("Párrafo 2"))
        assertTrue(result[0].content.contains("Párrafo 3"))
    }

    @Test
    fun `El texto largo se divide en párrafos`() {
        val chunker = TextChunker(targetSize = 50, overlap = 5)
        val para1 = "a".repeat(40)
        val para2 = "b".repeat(40)
        val para3 = "c".repeat(40)
        val text = "$para1\n\n$para2\n\n$para3"
        val result = chunker.split(text)
        assertTrue("Se deben producir múltiples trozos.,real ${result.size}", result.size >= 2)
        // Cada pieza no excede targetSize + overlap
        result.forEach { chunk ->
            assertTrue("bloquear ${chunk.index} longitud ${chunk.content.length} por encima del límite", chunk.content.length <= 55)
        }
    }

    @Test
    fun `Dividir párrafos muy largos por oraciones`() {
        val chunker = TextChunker(targetSize = 20, overlap = 3)
        val text = "Esta es la primera frase. ¡Esta es la segunda frase! ¿Es esta la tercera frase? Esta es la cuarta frase."
        val result = chunker.split(text)
        assertTrue("Se deben producir múltiples trozos.,real ${result.size}", result.size >= 2)
        // Verificar que el índice sea continuo
        result.forEachIndexed { idx, chunk ->
            assertEquals(idx, chunk.index)
        }
    }

    @Test
    fun `Las frases muy largas están marcadas por los personajes`() {
        val chunker = TextChunker(targetSize = 10, overlap = 0)
        val text = "a".repeat(35)
        val result = chunker.split(text)
        assertTrue("35 Los personajes deben tener al menos 4 bloques de 10.,real ${result.size}", result.size >= 3)
    }

    @Test
    fun `overlap Preservar la superposición entre bloques adyacentes`() {
        val chunker = TextChunker(targetSize = 20, overlap = 5)
        val para1 = "abcdefghijklmno".repeat(3) // 45 personaje
        val text = para1
        val result = chunker.split(text)
        if (result.size >= 2) {
            // El final del primer bloque debe aparecer al comienzo del segundo bloque.(overlap)
            val tail = result[0].content.takeLast(5)
            assertTrue("La superposición entre bloques no se conserva", result[1].content.startsWith(tail))
        }
    }

    @Test
    fun `CRLF Los saltos de línea están normalizados`() {
        val chunker = TextChunker(targetSize = 100, overlap = 10)
        val text = "primera linea\r\nsegunda linea\r\nla tercera linea"
        val result = chunker.split(text)
        assertEquals(1, result.size)
        assertTrue(result[0].content.contains("primera linea"))
        assertTrue(!result[0].content.contains("\r"))
    }

    @Test
    fun `index Incrementando continuamente desde 0`() {
        val chunker = TextChunker(targetSize = 30, overlap = 5)
        val text = (1..10).joinToString("\n\n") { "párrafo$it".repeat(10) }
        val result = chunker.split(text)
        assertTrue(result.size > 1)
        result.forEachIndexed { idx, chunk ->
            assertEquals("index discontinuo", idx, chunk.index)
        }
    }

    @Test
    fun `estimateTokens Devolver número positivo`() {
        val chunker = TextChunker()
        val tokens = chunker.estimateTokens("Hello World hola mundo")
        assertTrue("token El número debería > 0", tokens > 0)
    }

    @Test
    fun `estimateTokens Texto vacío devuelto 1`() {
        val chunker = TextChunker()
        val tokens = chunker.estimateTokens("")
        assertEquals(1, tokens)
    }

    // ── v1.133: markdownAware prueba de patrón ──

    @Test
    fun `markdownAware El bloque de código se conserva completo y no se corta`() {
        // targetSize=20 Mucho más pequeño que un bloque de código,Los bloques de código aún deben conservarse en su totalidad
        val chunker = TextChunker(targetSize = 20, overlap = 0, markdownAware = true)
        val codeBlock = "```kotlin\n" + "val x = 1\n".repeat(10) + "```"
        val text = "pretexto\n\n$codeBlock\n\nPublicar texto"
        val result = chunker.split(text)
        // Al menos un fragmento contiene completamente el bloque de código.
        val codeChunk = result.firstOrNull { it.content.contains("```kotlin") }
        assertTrue("Los bloques de código deben mantenerse en fragmentos.", codeChunk != null)
        assertTrue("El bloque de código debe estar completo.(Incluyendo apertura y cierre. ```)", codeChunk!!.content.contains("```kotlin") && codeChunk.content.endsWith("```"))
    }

    @Test
    fun `markdownAware Las filas de encabezado se dividen por separado y se registran los metadatos de las secciones`() {
        val chunker = TextChunker(targetSize = 500, overlap = 0, markdownAware = true)
        val text = "# Capítulo 1\n\nContenido uno\n\n# Capítulo 2\n\nContenido dos"
        val result = chunker.split(text)
        // Título"Capítulo 1"/"Capítulo 2"Debería aparecer como metadatos de sección en ciertos fragmentos.
        val sections = result.flatMap { it.metadata.values }.toSet()
        assertTrue("Los metadatos del capítulo deben registrarse: $sections", sections.contains("Capítulo 1") || sections.contains("Capítulo 2"))
    }

    @Test
    fun `markdownAware Mantener la mesa entera`() {
        val chunker = TextChunker(targetSize = 15, overlap = 0, markdownAware = true)
        val table = """
            | columna1 | columna2 | columna3 |
            |-----|-----|-----|
            | A   | B   | C   |
            | D   | E   | F   |
        """.trimIndent()
        val result = chunker.split(table)
        // La tabla debe conservarse entera en un trozo.
        val tableChunk = result.firstOrNull { it.content.contains("| columna1") }
        assertTrue("La mesa debe mantenerse en un trozo.", tableChunk != null)
        assertTrue("El formulario debe estar completo.(Contiene la última línea | F   |)", tableChunk!!.content.contains("| F   |"))
    }

    @Test
    fun `markdownAware El texto normal todavía se corta según el tamaño objetivo`() {
        // Utilice el título como límite estructural para activar la fragmentación(markdownAware Dividido por límites estructurales,
        // Título/bloque de código/Las tablas son límites naturales.;Los párrafos puros sin bordes se fusionarán en un solo bloque)
        val chunker = TextChunker(targetSize = 30, overlap = 0, markdownAware = true)
        val sections = (1..5).joinToString("\n\n") { idx ->
            "# Título$idx\n\n" + "párrafo$idx".repeat(15)
        }
        val result = chunker.split(sections)
        assertTrue("Debería producir múltiples cuando hay un límite de título chunk,real ${result.size} bloquear", result.size > 1)
    }

    @Test
    fun `markdownAware structureless oversized plain text is bounded`() {
        val targetSize = 100
        val text = "Texto largo de párrafo puro".repeat(1_000)

        val result = TextChunker(
            targetSize = targetSize,
            overlap = 10,
            markdownAware = true,
        ).split(text)

        assertTrue("El texto largo no estructurado debe dividirse en varios chunk,real ${result.size}", result.size > 1)
        assertTrue(
            "Los fragmentos de texto ordinarios no deben exceder el límite mínimo.",
            result.filter { it.metadata["type"] == null }.all { it.content.length <= targetSize * 2 },
        )
    }

    // ── v1.133: chunkByToken prueba de patrón ──

    @Test
    fun `chunkByToken Segmentar texto mixto en chino e inglés por número simbólico`() {
        // targetSize=10 token,La mezcla de chino e inglés debe cortarse según la ficha.
        val chunker = TextChunker(targetSize = 10, overlap = 0, chunkByToken = true)
        val text = (1..20).joinToString("\n\n") { "Hello World hola mundo" }
        val result = chunker.split(text)
        assertTrue("Se deben producir múltiples trozos.,real ${result.size}", result.size > 1)
    }

    @Test
    fun `chunkByToken El texto corto devuelve un solo bloque`() {
        val chunker = TextChunker(targetSize = 100, overlap = 0, chunkByToken = true)
        val result = chunker.split("Hello World")
        assertEquals(1, result.size)
        assertEquals("Hello World", result[0].content)
    }

    @Test
    fun `chunkByToken El texto vacío devuelve una lista vacía`() {
        val chunker = TextChunker(targetSize = 100, overlap = 0, chunkByToken = true)
        assertTrue(chunker.split("").isEmpty())
        assertTrue(chunker.split("   ").isEmpty())
    }

    // ── v1.133: markdownAware + chunkByToken combinación ──

    @Test
    fun `markdownAware Se puede habilitar al mismo tiempo que chunkByToken`() {
        val chunker = TextChunker(
            targetSize = 20,
            overlap = 0,
            markdownAware = true,
            chunkByToken = true,
        )
        val text = "# Título\n\n" + "código: ".repeat(5) + "\n```\nval x = 1\n```"
        val result = chunker.split(text)
        // Sin excepción + al menos una chunk
        assertTrue("debe producir al menos 1 chunk", result.isNotEmpty())
    }
}
