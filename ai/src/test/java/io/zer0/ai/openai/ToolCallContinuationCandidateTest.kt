package io.zer0.ai.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * llamada de herramienta"El regreso de la secuela"Prueba de decisión.
 *
 * ## Accidente real correspondiente
 *
 * Registro de medición real (SenseTime/Transmisión de estación de transferencia) divide una llamada lógica en"Primera película titulada + múltiples secuelas paramétricas sin nombre":
 *
 * ```
 * index=0  {id, function:{name:"search_memory"}}      ← La primera pieza tiene un nombre y los parámetros están vacíos.
 * index=1  {function:{arguments:"{\"limit\": 15, "}}  ← Secuela sin título
 * index=3  {function:{arguments:"query\": \"Yanlin"}}    ← Secuela sin título
 * index=4  {function:{arguments:"ven\"}"}}              ← Secuela sin título
 * ```
 *
 * Antes de la solución: las secuelas sin título se convierten en llamadas independientes → Falta el parámetro y la longitud del parámetro de llamada con un nombre real es 0 →
 * periódico aguas abajo"Falta el parámetro requerido: query", y el parámetro JSON se envía al usuario como texto.
 *
 * Bloquear aquí"¿Se puede devolver a su lugar y a quién?"Sentencia:**Solo se devolverá un candidato, no se adivinarán varios candidatos.**.
 */
class ToolCallContinuationCandidateTest {

    /** hacer uno"Nombrado, los parámetros están vacíos"de llamadas pendientes. */
    private fun namedPending(name: String = "search_memory"): ToolCallAccState =
        ToolCallAccState().apply { this.name = name }

    /** hacer uno"Nombrado y tiene parámetros."llamada (ya no esperando continuación). */
    private fun namedWithArgs(name: String = "search_memory"): ToolCallAccState =
        ToolCallAccState().apply {
            this.name = name
            args.append("""{"query":"Yanlin"}""")
        }

    /** hacer uno"Solo se recibieron los parámetros, el nombre no llegó"llamar. */
    private fun anonymousWithArgs(arguments: String): ToolCallAccState =
        ToolCallAccState().apply { args.append(arguments) }

    @Test
    fun `single named call waiting for arguments is adopted`() {
        val map = mapOf(0 to namedPending())
        assertEquals(0, pendingArgsCandidate(map))
    }

    @Test
    fun `no candidate when nothing is waiting`() {
        assertNull("Las mesas vacías no deberían tener candidatos.", pendingArgsCandidate(emptyMap()))
        assertNull("Las llamadas con parámetros ya no son candidatas", pendingArgsCandidate(mapOf(0 to namedWithArgs())))
        assertNull("Solo cuando lo llaman sin nombre no regresa (aún no llega el nombre)", pendingArgsCandidate(mapOf(1 to anonymousWithArgs("""{"a":1}"""))))
    }

    @Test
    fun `two waiting calls are ambiguous and never guessed`() {
        val map = mapOf(0 to namedPending(), 1 to namedPending())
        assertNull("Las llamadas paralelas deben abandonarse y regresar cuando se esperan parámetros.", pendingArgsCandidate(map))
    }

    @Test
    fun `candidate disappears once delta was emitted`() {
        val emitted = namedPending().apply { hasEmitted = true }
        assertNull("Una vez emitido ToolCallDelta, los parámetros se han finalizado y no se pueden agregar.", pendingArgsCandidate(mapOf(0 to emitted)))
    }

    @Test
    fun `blank function name is a continuation rather than a new call`() {
        assertEquals(
            "Sólo los identificadores o nombres de funciones que no están vacíos son llamadas a nuevas herramientas;name=\"\" Es la secuela de The Transfer Station.",
            false,
            isNewToolCall(id = null, name = ""),
        )
        assertEquals(true, isNewToolCall(id = "call-1", name = ""))
        assertEquals(true, isNewToolCall(id = null, name = "search_memory"))
    }

    @Test
    fun `latest open named call receives a remapped blank-name continuation`() {
        val first = namedPending().apply {
            hasEmitted = true
            args.append("""{"query":"first""")
        }
        val second = namedPending("record_experience").apply {
            hasEmitted = true
            args.append("""{"category":"second""")
        }
        assertEquals(
            "Cuando la estación de transferencia reescribe el índice de continuación, se debe utilizar la llamada nombrada más reciente que aún no se ha cerrado.",
            1,
            continuationTarget(
                accMap = mapOf(0 to first, 1 to second),
                activeLocalIndex = 1,
            ),
        )
    }

    @Test
    fun `same api index reuses its existing logical call`() {
        val indexMap = mutableMapOf(0 to 4)

        val assignment = assignToolCallLocalIndex(
            apiIndex = 0,
            id = "call-1",
            name = "search_memory",
            indexMap = indexMap,
            nextLocalIndex = 5,
        )

        assertEquals(4, assignment.localIndex)
        assertEquals("existing mapping must not consume another local index", 5, assignment.nextLocalIndex)
    }

    @Test
    fun `remapped named fragment follows active incomplete call when name matches`() {
        val first = namedPending().apply {
            hasEmitted = true
            args.append("""{"query":"first""")
        }

        assertEquals(
            0,
            continuationTarget(
                accMap = mapOf(0 to first),
                activeLocalIndex = 0,
                incomingName = "search_memory",
            ),
        )
    }

    @Test
    fun `fragments merge into a legal json object`() {
        // La secuela es ensamblada por el acumulador después de ser devuelta a su posición; esta línea está bloqueada"deletrealo**se puede analizar en JSON** Y los campos están completos.",
        // Es decir, los tres párrafos del diario. {"limit": 15, " / query": "Yan Lin/Lai"} entrada equivalente.
        // Tenga en cuenta que sólo se afirma la semántica (campos analizados), no la forma de la cadena. —— Es normal que el acumulador conserve el espacio en blanco original.
        val merged = mergeFragments(listOf("""{"limit": 15, """, """"query": "Yanlin""", """ven"}"""))
        val parsed = Json.parseToJsonElement(merged).jsonObject
        assertEquals("15", parsed["limit"].toString())
        assertEquals("\"Yan Linlai\"", parsed["query"].toString())
    }

    @Test
    fun `fragments arriving as whole objects merge without duplication`() {
        // Otra forma conocida: el modelo divide los parámetros en múltiples fragmentos de objetos JSON completos.
        val merged = mergeFragments(listOf("""{"limit":20}""", """{"query":"clima"}"""))
        val parsed = Json.parseToJsonElement(merged).jsonObject
        assertEquals("20", parsed["limit"].toString())
        assertEquals("\"clima\"", parsed["query"].toString())
    }
}

/** Verificación de empalmes de continuación con acumuladores reales. */
private fun mergeFragments(fragments: List<String>): String {
    val acc = ToolCallArgsAccumulator()
    fragments.forEach { acc.append(it) }
    return acc.current()
}
