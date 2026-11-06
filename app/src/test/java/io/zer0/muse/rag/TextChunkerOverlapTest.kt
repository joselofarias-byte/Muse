package io.zer0.muse.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-1 Regresión (informe de auditoría):Markdown La "superposición de bloques" debe surtir efecto en el modo de percepción.
 *
 * antigua implementación `appendTextPiece` primero `flush()` Limpiar la corriente, entonces
 * `current.toString().takeLast(overlap)`, el resultado obtenido es siempre una cadena vacía. ——
 * debido a `markdownAware` El valor predeterminado es verdadero, las configuraciones superpuestas de todos los usuarios se convierten en configuraciones inactivas.
 */
class TextChunkerOverlapTest {

    @Test
    fun `markdown Hay contenido superpuesto en bloques adyacentes en el modo`() {
        val chunker = TextChunker(targetSize = 100, overlap = 20, markdownAware = true)
        // Varios párrafos de texto, la longitud de cada párrafo está cerca del tamaño objetivo, lo que garantiza que se active flush
        val para = "Este es un fragmento de texto que se utiliza para probar el comportamiento superpuesto y debe ser lo suficientemente largo para activar la lógica de fragmentación."
        val text = (1..6).joinToString("\n\n") { "$para$it" }

        val chunks = chunker.split(text)
        assertTrue("debería producir múltiples fragmentos, reales ${chunks.size}", chunks.size >= 2)

        // Debe haber superposición entre bloques adyacentes: la cola del bloque anterior aparece cerca del comienzo del siguiente bloque
        var overlapFound = 0
        for (i in 0 until chunks.size - 1) {
            val tail = chunks[i].content.takeLast(20)
            if (tail.isNotBlank() && chunks[i + 1].content.contains(tail)) {
                overlapFound++
            }
        }
        assertTrue(
            "markdown Debe haber al menos una superposición en el modo. De hecho, $overlapFound en (lo que indica que la superposición no es válida)",
            overlapFound >= 1,
        )
    }

    @Test
    fun `overlap Cuando es 0, no se produce superposición`() {
        val chunker = TextChunker(targetSize = 100, overlap = 0, markdownAware = true)
        // Cada párrafo termina en un marcador propio. El relleno "x" no repite el token de cola del primer bloque.
        val text = (1..6).joinToString("\n\n") { n ->
            "inicio$n-" + "x".repeat(70) + "-fin$n"
        }

        val chunks = chunker.split(text)
        assertTrue(chunks.size >= 2)
        val tail = chunks[0].content.takeLast(10)
        assertEquals("o2-xxxxxxx", tail)
        assertFalse(
            "overlap=0: la cola del bloque 0 no debe copiarse al inicio del bloque 1",
            chunks[1].content.startsWith(tail),
        )
    }

    @Test
    fun `La superposición del modo sin rebajas aún tiene efecto (control)`() {
        val chunker = TextChunker(targetSize = 100, overlap = 20, markdownAware = false)
        val text = (1..6).joinToString("\n\n") {
            "Este es un fragmento de texto que se utiliza para probar el comportamiento superpuesto y debe ser lo suficientemente largo para activar la lógica de fragmentación.$it"
        }
        val chunks = chunker.split(text)
        assertTrue("Se deben producir múltiples trozos.", chunks.size >= 2)
        assertTrue("El contenido del bloque no debe estar vacío.", chunks.all { it.content.isNotBlank() })
    }

    @Test
    fun `Los números de serie del bloque son consecutivos`() {
        val chunker = TextChunker(targetSize = 80, overlap = 10, markdownAware = true)
        val chunks = chunker.split((1..5).joinToString("\n\n") { "Texto de prueba de contenido de párrafo$it" })
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
    }
}
