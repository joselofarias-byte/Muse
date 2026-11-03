package io.zer0.memory.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.79 (B-20): Pruebas recortadas por prioridad de segmento.
 *
 * Tres determinaciones que cubren la auditoría B-20:
 *  ① Cuando el presupuesto total sea suficiente, se mantendrán las cuatro secciones.(Volver al texto original)
 *  ② a largo plazo/hechos reservados cuando el presupuesto es insuficiente,today/week recortado
 *  ③ Generar registros cuando hay truncamiento(Logger.w)sin lanzar una excepción
 *
 * El formato de rebajas de muestra es coherente con MemoryCompiler.assembleCompiledMarkdown:
 * Secuencia de cuatro párrafos facts → today → week → longterm,línea en blanco entre párrafos,Todo termina con un salto de línea.
 *
 * Solidez de las opciones presupuestarias:high segmento(facts/longterm)texto muy corto,El costo es muy pequeño.;
 * today/week Utilice una gran cantidad de líneas para soportar grandes costos.,hacer"high costo < presupuesto < Costo del texto completo"La gama es muy amplia,
 * Incluso si el BPE difiere ligeramente de la estimación heurística de caracteres,Los trazados de recorte por segmentos también pueden activarse de forma estable.
 */
class LlmBudgetSegmentTest {

    private fun factsBody() = "Hecho A:a los usuarios les gusta Kotlin\nHecho B:Desarrollo de usuarios Android"

    private fun longtermBody() = "Es muy importante conservar la memoria A a largo plazo por completo.\nLa memoria a largo plazo B es igualmente importante"

    private fun hugeBody(prefix: String, lines: Int = 80): String = (1..lines).joinToString("\n") { "$prefix llenar fila $it Se utiliza para ampliar este párrafo mucho más allá del presupuesto." }

    /** Construya una rebaja de 4 segmentos que sea coherente con el formato de salida de ensamblarCompiledMarkdown. */
    private fun sampleMarkdown(): String = listOf(
        "## hechos importantes",
        factsBody(),
        "## hoy",
        hugeBody("hoy"),
        "## a principios de esta semana",
        hugeBody("esta semana"),
        "## situación a largo plazo",
        longtermBody(),
    ).joinToString("\n\n") + "\n"

    // ── ① Presupuesto suficiente:Las cuatro secciones están reservadas. ───────────────────────────────────────────

    @Test
    fun `Volver al texto original cuando el presupuesto sea suficiente`() {
        val md = sampleMarkdown()
        val result = LlmBudget.truncateToTokenBudget(md, 2_000_000)
        // El presupuesto es mucho mayor que el texto.,debe devolverse sin cambios(sin marca de truncamiento,El orden es consistente)
        assertEquals(md, result)
        assertFalse("No debe haber ninguna marca de truncamiento cuando el presupuesto es suficiente", result.contains("(memory truncated)"))
    }

    @Test
    fun `Los cuatro títulos se conservan cuando el presupuesto esté disponible`() {
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 2_000_000)
        listOf("## hechos importantes", "## hoy", "## a principios de esta semana", "## situación a largo plazo").forEach { heading ->
            assertTrue("Se debe conservar un presupuesto suficiente $heading: $result", result.contains(heading))
        }
    }

    // ── ② Presupuesto insuficiente:longterm/facts reserva,today/week recortado ────────────────

    @Test
    fun `Mantenga los datos completos a largo plazo cuando el presupuesto sea bajo`() {
        // Es necesario cumplir el presupuesto: high(facts+longterm) < budget < Costo del texto completo
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 300)
        // longterm es la ultima seccion(memoria a largo plazo),Debe mantenerse intacto
        assertTrue("La memoria a largo plazo debe conservarse intacta: $result", result.contains(longtermBody()))
        // facts es el primer parrafo,alta prioridad,también debe mantenerse intacto
        assertTrue("facts debe conservarse intacto: $result", result.contains(factsBody()))
    }

    @Test
    fun `Cuando el presupuesto es insuficiente, hoy al menos se recorta y se mantiene longterm`() {
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 300)
        // today Es la sección del medio la que ha sido estirada.(baja prioridad),Se le debe cortar la cola
        assertTrue("Después de estirar la sección media, se debe activar la marca de truncamiento.: $result", result.contains("(memory truncated)"))
        // longterm El título se mantiene estable en
        assertTrue("longterm El título aún debería ser: $result", result.contains("## situación a largo plazo"))
        // longterm El texto esta completo.,No truncado por la cola y descartado.
        assertTrue("longterm El texto debe estar completo.: $result", result.contains(longtermBody()))
    }

    @Test
    fun `Cuando el presupuesto es insuficiente, el largo plazo no se quedará en cola hasta el primero y se descartará`() {
        // retorno del núcleo:B-20 Anteriormente, si la cola se truncaba, se descartaba primero. longterm;Ahora el plazo final debería estar todavía al final del texto.
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 300)
        val longtermIdx = result.indexOf("## situación a largo plazo")
        assertTrue("longterm El titulo debe existir: $result", longtermIdx >= 0)
        // longterm El titulo no debe faltar,Y el contenido está completo.(en lugar de solo un título vacío)
        assertTrue(result.contains(longtermBody()))
    }

    // ── ③ Genere registros cuando se produzca un truncamiento y no arroje excepciones ────────────────────────────────────

    @Test
    fun `Si el presupuesto excede el presupuesto, el resultado se truncará sin generar una excepción`() {
        // Por pequeño que sea el presupuesto,debería devolver de forma segura resultados que no estén en blanco,No lanzar excepción
        val result = LlmBudget.truncateToTokenBudget(sampleMarkdown(), 3)
        assertTrue("El resultado no puede ser una cadena vacía.", result.isNotBlank())
    }

    @Test
    fun `El texto vacío y un presupuesto de 0 mantienen el comportamiento original`() {
        assertEquals("", LlmBudget.truncateToTokenBudget("", 100))
        val md = sampleMarkdown()
        // budget=0 Tratado como sin restricciones,Volver al texto original
        assertEquals(md, LlmBudget.truncateToTokenBudget(md, 0))
    }

    // ── Lógica de corte/segmentación de funciones puras ────────────────────────────────────────────

    @Test
    fun `splitSegments Las funciones puras se dividen correctamente en cuatro segmentos`() {
        val segments = LlmBudget.splitSegments(sampleMarkdown())
        assertEquals(4, segments.size)
        assertEquals("## hechos importantes", segments[0].heading)
        assertEquals("## situación a largo plazo", segments[3].heading)
        assertTrue("El último párrafo debe ser un texto de larga duración.", segments[3].body.contains("memoria A a largo plazo"))
    }

    @Test
    fun `truncateBySegments La función pura solo corta la sección intermedia y deja la sección de alta prioridad sin cambios`() {
        val segments = LlmBudget.splitSegments(sampleMarkdown())
        val result = LlmBudget.truncateBySegments(segments, budget = 300)
        // La primera sección de hechos y la última sección de largo plazo se mantienen intactas con alta prioridad.
        assertEquals("## hechos importantes", result.first().heading)
        assertEquals("## situación a largo plazo", result.last().heading)
        assertEquals(longtermBody(), result.last().body)
    }
}
