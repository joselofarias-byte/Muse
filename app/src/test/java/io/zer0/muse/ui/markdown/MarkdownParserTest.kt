package io.zer0.muse.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11.4: MarkdownParser Pruebas unitarias.
 *
 * Anular tipos de nivel de bloque de claves:Paragraph / Heading / CodeBlock / ListItem / Quote / Divider / Blank / Formula.
 * formato en línea(Negrita/cursiva/código en línea/enlace)Procesado al renderizar con MarkdownText Composable,
 * Parser Solo responsable de la segmentación a nivel de bloque,Por lo tanto, aquí no hay ninguna prueba.
 */
class MarkdownParserTest {

    @Test
    fun `plain paragraph parses as Paragraph`() {
        val blocks = parseMarkdown("Hello world")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertEquals("Hello world", (blocks[0] as MarkdownBlock.Paragraph).text)
    }

    @Test
    fun `heading level 1 2 3 parses correctly`() {
        val blocks = parseMarkdown("# H1\n## H2\n### H3")
        assertEquals(3, blocks.size)
        val h1 = blocks[0] as MarkdownBlock.Heading
        val h2 = blocks[1] as MarkdownBlock.Heading
        val h3 = blocks[2] as MarkdownBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("H1", h1.text)
        assertEquals(2, h2.level)
        assertEquals("H2", h2.text)
        assertEquals(3, h3.level)
        assertEquals("H3", h3.text)
    }

    @Test
    fun `heading level 4 5 6 parses correctly`() {
        val blocks = parseMarkdown("#### H4\n##### H5\n###### H6")

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Heading(4, "H4"), blocks[0])
        assertEquals(MarkdownBlock.Heading(5, "H5"), blocks[1])
        assertEquals(MarkdownBlock.Heading(6, "H6"), blocks[2])
    }

    @Test
    fun `fenced code block with language parses`() {
        val md = "```kotlin\nfun foo() = 1\n```"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("fun foo() = 1", code.code)
    }

    @Test
    fun `fenced code block without language parses`() {
        val md = "```\nplain code\n```"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals(null, code.language)
        assertEquals("plain code", code.code)
    }

    @Test
    fun `unordered list items parse`() {
        val md = "- apple\n- banana\n- cherry"
        val blocks = parseMarkdown(md)
        assertEquals(3, blocks.size)
        // El campo de índice de la lista desordenada no incrementa(siempre 0);La lista ordenada se incrementa
        blocks.forEach { block ->
            val item = block as MarkdownBlock.ListItem
            assertEquals(false, item.ordered)
        }
        assertEquals("apple", (blocks[0] as MarkdownBlock.ListItem).text)
        assertEquals("banana", (blocks[1] as MarkdownBlock.ListItem).text)
        assertEquals("cherry", (blocks[2] as MarkdownBlock.ListItem).text)
    }

    @Test
    fun `quote block parses`() {
        val md = "> This is a quote"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val quote = blocks[0] as MarkdownBlock.Quote
        assertEquals("This is a quote", quote.text)
    }

    @Test
    fun `horizontal divider parses`() {
        val md = "---"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Divider)
    }

    @Test
    fun `blank line preserves as Blank`() {
        val md = "paragraph 1\n\nparagraph 2"
        val blocks = parseMarkdown(md)
        // Expectativa:Paragraph / Blank / Paragraph
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
        assertTrue(blocks[1] is MarkdownBlock.Blank)
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `multi-line code block preserves internal newlines`() {
        val md = "```python\nline1\nline2\nline3\n```"
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MarkdownBlock.CodeBlock
        assertEquals("line1\nline2\nline3", code.code)
    }

    @Test
    fun `mixed content parses in order`() {
        val md = buildString {
            appendLine("# Title")
            appendLine()
            appendLine("Some paragraph text.")
            appendLine()
            appendLine("- item 1")
            appendLine("- item 2")
            appendLine()
            appendLine("```kotlin")
            appendLine("val x = 1")
            appendLine("```")
        }
        val blocks = parseMarkdown(md.trimEnd())
        // orden esperado:Heading / Blank / Paragraph / Blank / ListItem / ListItem / Blank / CodeBlock
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Blank)
        assertTrue(blocks[2] is MarkdownBlock.Paragraph)
        assertTrue(blocks[3] is MarkdownBlock.Blank)
        assertTrue(blocks[4] is MarkdownBlock.ListItem)
        assertTrue(blocks[5] is MarkdownBlock.ListItem)
        assertTrue(blocks[6] is MarkdownBlock.Blank)
        assertTrue(blocks[7] is MarkdownBlock.CodeBlock)
    }

    @Test
    fun `formula block parses`() {
        val md = "\$\$\nE = mc^2\n\$\$"
        val blocks = parseMarkdown(md)
        // Análisis de bloques de fórmulas(Phase 8.6)
        val formula = blocks.firstOrNull { it is MarkdownBlock.Formula } as? MarkdownBlock.Formula
        assertTrue(formula != null)
        assertTrue(formula!!.latex.contains("E = mc^2"))
    }

    @Test
    fun `formula block Cierre de pares recortado para comenzar y terminar con doble dólar`() {
        // fórmula cerrada entre pares:$$ E = mc^2 $$(a $$ comenzando con $$ finalizando,longitud > 4)
        val md = "\$\$ E = mc^2 \$\$"
        val blocks = parseMarkdown(md)
        // no debe confundirse con Paragraph,debe ser soltero Formula
        assertEquals("La fórmula de cierre de pares debe producir solo 1 bloque,real ${blocks.size}", 1, blocks.size)
        val formula = blocks[0] as? MarkdownBlock.Formula
        assertTrue("Debe ser de tipo Fórmula,real ${blocks[0]::class.simpleName}", formula != null)
        assertEquals("E = mc^2", formula!!.latex)
    }

    @Test
    fun `formula block El cierre paralelo contiene el signo igual y letras griegas`() {
        val md = "\$\$ \\alpha + \\beta = \\gamma \$\$"
        val blocks = parseMarkdown(md)
        val formula = blocks.firstOrNull { it is MarkdownBlock.Formula } as? MarkdownBlock.Formula
        assertTrue("debe ser identificado como Formula", formula != null)
        assertTrue(formula!!.latex.contains("\\alpha"))
        assertTrue(formula.latex.contains("\\gamma"))
    }

    @Test
    fun `dollar El texto sin formato con prefijo no se reconoce erróneamente como una fórmula`() {
        // M2 Reparación:solo $$ comienzo + $$ final y longitud>4 esa es la formula;simplemente $$ El texto normal al principio se procesa como párrafos.
        // Usado aquí "$$ El precio es 100 yuanes." Simulación:a $$ comenzando pero no con $$ finalizando,debe usarse como Paragraph
        val md = "\$\$ El precio es 100 yuanes."
        val blocks = parseMarkdown(md)
        // El bloque Fórmula no debería aparecer
        val hasFormula = blocks.any { it is MarkdownBlock.Formula }
        assertFalse("No debe reconocerse como un bloque de fórmula", hasFormula)
        // El primer bloque debe ser Paragraph,Mantener el texto original.(trim después)
        val para = blocks.firstOrNull { it is MarkdownBlock.Paragraph } as? MarkdownBlock.Paragraph
        assertTrue("Debe manejarse como párrafo", para != null)
        assertTrue(para!!.text.contains("precio"))
    }

    @Test
    fun `La fila de un solo dólar no se reconoce como fórmula`() {
        // Sólo toda la fila es exactamente $$ Simplemente ingrese el bloque de fórmulas;soltero $ debería ser Paragraph
        val md = "\$100"
        val blocks = parseMarkdown(md)
        val hasFormula = blocks.any { it is MarkdownBlock.Formula }
        assertFalse("soltero \$ no debe ser reconocido como una fórmula", hasFormula)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Paragraph)
    }

    @Test
    fun `Los bloques de fórmulas de varias líneas abarcan varias líneas de contenido`() {
        // fórmula de varias líneas:$$ empezar,Varias líneas en el medio latex,hasta contener $$ final de línea
        val md = buildString {
            append("\$\$")
            append("\n\\begin{aligned}")
            append("\nx &= 1 + 2 \\\\")
            append("\ny &= 3 + 4")
            append("\n\\end{aligned}")
            append("\n\$\$")
        }
        val blocks = parseMarkdown(md)
        val formula = blocks.firstOrNull { it is MarkdownBlock.Formula } as? MarkdownBlock.Formula
        assertTrue("Debe reconocerse como varias líneas. Formula", formula != null)
        assertTrue(formula!!.latex.contains("aligned"))
        assertTrue(formula.latex.contains("x &= 1 + 2"))
        assertTrue(formula.latex.contains("y &= 3 + 4"))
    }

    @Test
    fun `empty string returns empty list`() {
        val blocks = parseMarkdown("")
        // cadena vacía split("\n") Regresar [""],Puede resolverse a 1 en blanco o Paragraph
        // Siempre que no contenga otros tipos
        assertTrue(blocks.all { it is MarkdownBlock.Blank || it is MarkdownBlock.Paragraph })
    }

    // ── A3: El análisis incremental es semánticamente equivalente al análisis completo ────────────────────────────────

    @Test
    fun `Análisis incremental, agregar carácter por carácter, equivalente a la cantidad total`() {
        val md = buildString {
            appendLine("# Título")
            appendLine()
            appendLine("texto del párrafo **Negrita**.")
            appendLine()
            appendLine("- un")
            appendLine("- b")
            appendLine()
            appendLine("```kotlin")
            appendLine("val x = 1")
            appendLine("```")
            appendLine()
            appendLine("| A | B |")
            appendLine("|---|---|")
            appendLine("| 1 | 2 |")
            appendLine()
            appendLine("\$\$")
            appendLine("E = mc^2")
            appendLine("\$\$")
        }.trimEnd()
        val parser = IncrementalMarkdownParser()
        var acc = ""
        for (ch in md) {
            acc += ch
            assertEquals(
                "La adición carácter por carácter es inconsistente, len=${acc.length}",
                parseMarkdown(acc),
                parser.parse(acc),
            )
        }
        assertEquals("El resultado final equivale al importe total.", parseMarkdown(md), parser.parse(md))
    }

    @Test
    fun `Análisis incremental Agregar por fila, equivalente al monto total, incluidos los estados intermedios no cerrados`() {
        val lines = listOf(
            "Prefacio",
            "",
            "# Título",
            "",
            "```kotlin",
            "fun a() = 1",
            "fun b() = 2",
            "```",
            "",
            "- item1",
            "- item2",
            "",
            "> Cotización",
            "",
            "| C1 | C2 |",
            "|---|---|",
            "| x | y |",
            "| z | w |",
            "",
            "sección de cola",
        )
        val parser = IncrementalMarkdownParser()
        var acc = ""
        for (line in lines) {
            acc += line + "\n"
            assertEquals(
                "Agregar por fila es inconsistente, OK=$line",
                parseMarkdown(acc),
                parser.parse(acc),
            )
        }
        assertEquals("El resultado final equivale al importe total.", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `Análisis incremental Agregar dentro del mismo párrafo equivale al monto total`() {
        // Los formatos fluidos suelen agregarse dentro de la misma línea de un párrafo. token(Sin nuevos saltos de línea),El último bloque debe volverse a escanear en lugar de reutilizarse directamente.
        val parser = IncrementalMarkdownParser()
        var acc = "primer párrafo"
        assertEquals(parseMarkdown(acc), parser.parse(acc))
        acc += "continuar"
        assertEquals("El párrafo adjunto es inconsistente", parseMarkdown(acc), parser.parse(acc))
        acc += "Continuar"
        assertEquals(parseMarkdown(acc), parser.parse(acc))
        // Ingrese un nuevo bloque después de agregar una nueva línea
        acc += "\nSegundo párrafo"
        assertEquals(parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `Análisis incremental la valla no cerrada se cierra gradualmente, lo que equivale al importe total`() {
        val parser = IncrementalMarkdownParser()
        var acc = "comienzo"
        acc += "\n```kotlin"
        assertEquals("Valla abierta abierta", parseMarkdown(acc), parser.parse(acc))
        acc += "\nval a = 1"
        assertEquals("fence Agregar líneas de código dentro", parseMarkdown(acc), parser.parse(acc))
        acc += "\nval b = 2"
        assertEquals("fence Añadir más dentro", parseMarkdown(acc), parser.parse(acc))
        acc += "\n```"
        assertEquals("fence cerrado", parseMarkdown(acc), parser.parse(acc))
        acc += "\npárrafo final"
        assertEquals("Agregar párrafo después de cerrar", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `Análisis incremental las fórmulas de varias líneas no cerradas se cierran gradualmente y equivalen al monto total`() {
        val parser = IncrementalMarkdownParser()
        var acc = "\$\$"
        assertEquals("Fórmula abierta", parseMarkdown(acc), parser.parse(acc))
        acc += "\nx &= 1 + 2"
        assertEquals("Agregar en fórmula", parseMarkdown(acc), parser.parse(acc))
        acc += "\n\$\$"
        assertEquals("cierre de fórmula", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `Análisis incremental la tabla agrega gradualmente filas de datos, lo que equivale a la cantidad total`() {
        val parser = IncrementalMarkdownParser()
        var acc = "| A | B |\n|---|---|"
        assertEquals("encabezado+divisor", parseMarkdown(acc), parser.parse(acc))
        acc += "\n| 1 | 2 |"
        assertEquals("Agregar filas de datos", parseMarkdown(acc), parser.parse(acc))
        acc += "\n| 3 | 4 |"
        assertEquals("Agregar filas de datos", parseMarkdown(acc), parser.parse(acc))
        acc += "\nPárrafo después de la tabla"
        assertEquals("Después de cerrar el formulario", parseMarkdown(acc), parser.parse(acc))
    }

    @Test
    fun `Análisis incremental, reemplazo de texto, retroceso al volumen completo`() {
        val parser = IncrementalMarkdownParser()
        parser.parse("contenido antiguo contenido antiguo")
        // apéndice impuro(El texto nuevo no tiene el prefijo del texto antiguo)→ Análisis completo,El resultado es consistente con el monto total directo.
        val fresh = "# Título nuevo\n\nContenido nuevo"
        assertEquals(parseMarkdown(fresh), parser.parse(fresh))
    }

    @Test
    fun `Resolución incremental El bloque de estabilización de la cabeza sigue siendo el mismo`() {
        // Afirmaciones principales para caché de nodo estable:Al agregar la cola,La instancia del bloque de encabezado cerrado permanece sin cambios.,
        // Compose para omitir el reensamblaje de estos bloques
        val parser = IncrementalMarkdownParser()
        val first = parser.parse("Título de encabezado fijo #\n\n```kotlin\nval x = 1\n```")
        assertEquals(3, first.size) // Paragraph / Blank / CodeBlock
        val second = parser.parse("Título de encabezado fijo #\n\n```kotlin\nval x = 1\n```\n\nAgregar nuevo párrafo")
        assertEquals(5, second.size)
        // Cabeza 3 piezas(Paragraph/Blank/CodeBlock)Debería ser la misma instancia
        assertTrue("Paragraph Las instancias deben reutilizarse", first[0] === second[0])
        assertTrue("Blank Las instancias deben reutilizarse", first[1] === second[1])
        assertTrue("CodeBlock Las instancias deben reutilizarse", first[2] === second[2])
    }
}
