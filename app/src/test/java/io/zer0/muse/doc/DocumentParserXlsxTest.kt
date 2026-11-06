package io.zer0.muse.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.133: DocumentParser XLSX Análisis de pruebas unitarias.
 *
 * prueba [DocumentParser.parseSharedStrings] con [DocumentParser.parseWorksheet] dos
 * Método de procesamiento XML puro(parseResult Usar ContentResolver es inconveniente para pruebas unitarias,tan inesperado).
 *
 * Escena de portada:
 *  - Tabla de cadenas compartida:vacío/soltero <t>/mucho <t> texto enriquecido/prefijo de espacio de nombres
 *  - hoja de trabajo:número de celda/Celdas de cadena compartidas/cadena en línea/Booleano/linea en blanco
 *
 * Nota:usar RobolectricTestRunner — XmlPullParserFactory.newInstance() en Android stub jar
 * Lanzar por defecto RuntimeException,Robolectric necesita proporcionar una implementación real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentParserXlsxTest {

    private val parser = DocumentParser()

    // ── parseSharedStrings ──

    @Test
    fun `sharedStrings La entrada vacía devuelve una lista vacía`() {
        assertTrue(parser.parseSharedStrings(null).isEmpty())
        assertTrue(parser.parseSharedStrings("").isEmpty())
        assertTrue(parser.parseSharedStrings("   ").isEmpty())
    }

    @Test
    fun `sharedStrings Las etiquetas t individuales se analizan secuencialmente`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="3" uniqueCount="3">
                <si><t>nombre</t></si>
                <si><t>edad</t></si>
                <si><t>ciudad</t></si>
            </sst>
        """.trimIndent()
        val list = parser.parseSharedStrings(xml)
        assertEquals(3, list.size)
        assertEquals("nombre", list[0])
        assertEquals("edad", list[1])
        assertEquals("ciudad", list[2])
    }

    @Test
    fun `sharedStrings Fusión de múltiples etiquetas t de texto enriquecido`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <si>
                    <r><rPr><b/></rPr><t>Negrita</t></r>
                    <r><t>ordinario</t></r>
                </si>
                <si><t>texto plano</t></si>
            </sst>
        """.trimIndent()
        val list = parser.parseSharedStrings(xml)
        assertEquals(2, list.size)
        assertEquals("Negritaordinario", list[0])
        assertEquals("texto plano", list[1])
    }

    @Test
    fun `sharedStrings Con entidad XML escapando`() {
        val xml = """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <si><t>A &amp; B &lt;tag&gt; &quot;quote&quot;</t></si>
            </sst>
        """.trimIndent()
        val list = parser.parseSharedStrings(xml)
        assertEquals(1, list.size)
        assertEquals("A & B <tag> \"quote\"", list[0])
    }

    // ── parseWorksheet ──

    @Test
    fun `worksheet Las celdas numéricas puras generan directamente el valor v`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1"><v>123</v></c>
                        <c r="B1"><v>45.6</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        val output = sb.toString().trim()
        assertEquals("123\t45.6", output)
    }

    @Test
    fun `worksheet Tabla de búsqueda de celdas de cadena compartida por índice`() {
        val shared = listOf("nombre", "Zhang San", "edad", "25")
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>1</v></c>
                    </row>
                    <row r="2">
                        <c r="A2" t="s"><v>2</v></c>
                        <c r="B2" t="s"><v>3</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, shared, sb)
        val lines = sb.toString().trim().lines()
        assertEquals(2, lines.size)
        assertEquals("nombre\tZhang San", lines[0])
        assertEquals("edad\t25", lines[1])
    }

    @Test
    fun `worksheet Lectura de cadenas en línea is_t`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="inlineStr"><is><t>valor en línea</t></is></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        assertEquals("valor en línea", sb.toString().trim())
    }

    @Test
    fun `worksheet Las celdas booleanas se convierten en TRUE_FALSE`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="b"><v>1</v></c>
                        <c r="B1" t="b"><v>0</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        assertEquals("TRUE\tFALSE", sb.toString().trim())
    }

    @Test
    fun `worksheet Varias líneas separadas por nuevas líneas`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1"><c r="A1"><v>1</v></c></row>
                    <row r="2"><c r="A2"><v>2</v></c></row>
                    <row r="3"><c r="A3"><v>3</v></c></row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        val lines = sb.toString().trim().lines()
        assertEquals(3, lines.size)
        assertEquals("1", lines[0])
        assertEquals("2", lines[1])
        assertEquals("3", lines[2])
    }

    @Test
    fun `worksheet Volver al valor original cuando el índice de cadena compartida se sale de los límites`() {
        // El índice 99 está fuera del rango de cadenas compartidas(Sólo 1 artículo),La salida debe revertirse. "99"
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1" t="s"><v>0</v></c>
                        <c r="B1" t="s"><v>99</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = listOf("artículo único"), sb = sb)
        // Índice 0 acierto,Índice 99 Retroceso transfronterizo "99"
        assertEquals("artículo único\t99", sb.toString().trim())
    }

    @Test
    fun `worksheet Se omiten las celdas vacías`() {
        // <c> Ninguno <v>,cellBuffer esta vacio,display es una cadena vacía pero aún se agrega rowCells
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="1">
                        <c r="A1"><v>1</v></c>
                        <c r="B1"></c>
                        <c r="C1"><v>3</v></c>
                    </row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sb = StringBuilder()
        parser.parseWorksheet(xml, sharedStrings = emptyList(), sb = sb)
        // Las celdas vacías se conservan como cadenas vacías,tab La separación aún mantiene su posición
        assertEquals("1\t\t3", sb.toString().trim())
    }
}
