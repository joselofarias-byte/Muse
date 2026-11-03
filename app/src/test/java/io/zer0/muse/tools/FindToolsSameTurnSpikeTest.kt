package io.zer0.muse.tools

import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pico del lote 2.2:find_tools Después de cargar, ¿puede el**La siguiente ronda de la ronda del mismo usuario.**Tener un efecto.
 *
 * ## fondo
 *
 * Elemento 1 de autoevaluación del asistente: "Si la herramienta no está en el esquema de esta ronda, primero debe buscar_herramientas y luego esperar a la siguiente ronda. —— Múltiples rondas de saltos,
 * Una vez que el usuario publica otra frase en el medio, el contexto acumulado previamente se rompe fácilmente. Lo ideal es "recuperación y carga, disponibles en la misma rueda". "
 *
 * ## spike Dos preguntas para responder
 *
 * 1. `markLoaded` Después de escribir, al mismo tiempo. `runLoop` ¿Se volverá a leer en la próxima ronda?
 * 2. Después de volver a leer,`filterToolsForRequest` ¿Será realmente lanzado?
 *
 * ## Conclusión (esta prueba es la evidencia)
 *
 * Pregunta 1:**voluntad**.`ChatToolLoopRunner.requestRoundFlow` Antes de cada ronda de solicitudes, llame
 * `SessionToolLoadRegistry.loadedFor(sessionId)`, que se llama una vez por ronda dentro del bucle de herramientas.
 * Entonces "próxima ronda"= La siguiente solicitud de modelo dentro del mismo turno de usuario no es "el usuario envía otra frase".
 *
 * Pregunta 2:**voluntad**.`dynamicAllowed` contiene `loadedToolNames`,OPTIONAL/GLOBAL Herramientas
 * Mientras esté cargado, se libera.
 *
 * Por lo tanto, "la misma rueda está disponible" bajo la arquitectura actual.**Ya establecido**(misma rueda = ruedas de herramientas posteriores para la misma entrada del usuario).
 * La verdadera limitación es: el modelo es**mismo tiempo**No puede ajustar tanto find_tools como la herramienta recién recuperada en la salida del modelo. ——
 * Eso está determinado por el protocolo del proveedor (tool_calls Se determina cuando se devuelve la respuesta) y Muse no puede cambiarlo.
 *
 * Esta prueba concreta la conclusión anterior y evita que la refactorización posterior cambie la premisa de "releer cada ronda".
 *
 * Nota: Debe usar**verdaderamente pertenecer OPTIONAL** nombre de la herramienta. El uso de herramientas STANDARD/CORE hará que las pruebas sean imposibles
 * Poder de verificación —— Siempre están disponibles y son visibles antes de cargar. Afirmar "no visible antes de cargar" hará que la herramienta no esté en el conjunto de prueba.
 * Y pases en falso.
 */
class FindToolsSameTurnSpikeTest {

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = name,
        parametersJsonSchema = """{"type":"object","properties":{}}""",
    )

    /** Dos verdaderas herramientas OPCIONALES (visibles después de estrecharlas), además de un acolchado para empujar la superficie de la herramienta por encima del umbral. */
    private val optionalA = "set_alarm"
    private val optionalB = "toggle_flashlight"

    private fun largeToolSet(): List<ToolDefinition> = (1..30).map { tool("filler_tool_$it") } +
        listOf(tool(optionalA), tool(optionalB))

    init {
        // Afirmación previa: elegir la categoría incorrecta hará que todo el conjunto de pruebas carezca de sentido, así que bloquéelo durante la inicialización de la clase.
        require(optionalA in ToolCategories.OPTIONAL) { "$optionalA debe pertenecer a OPTIONAL" }
        require(optionalB in ToolCategories.OPTIONAL) { "$optionalB debe pertenecer a OPTIONAL" }
    }

    @Test
    fun `loaded tool is visible on the next round even though the user said nothing new`() {
        val tools = largeToolSet()
        // El mensaje original del usuario no contiene despertador./palabras clave de la linterna → Después de limitar, estas dos herramientas OPCIONALES no deberían aparecer.
        val userText = "ayudame con esto"

        val beforeLoad = ToolExposurePolicy.filterToolsForRequest(userText = userText, tools = tools)
        assertFalse(
            "No debe ser visible antes de la carga (de lo contrario, esta prueba no tiene poder de verificación)",
            beforeLoad.any { it.name == optionalA },
        )

        val afterLoad =
            ToolExposurePolicy.filterToolsForRequest(
                userText = userText,
                tools = tools,
                loadedToolNames = setOf(optionalA),
            )
        assertTrue(
            "Después de la carga, la misma frase de la entrada del usuario debe ser visible en la siguiente ronda. —— Esto está \"disponible en la misma rueda\".",
            afterLoad.any { it.name == optionalA },
        )
    }

    @Test
    fun `loading one tool does not widen the whole schema`() {
        val tools = largeToolSet()

        val afterLoad =
            ToolExposurePolicy.filterToolsForRequest(
                userText = "ayudame con esto",
                tools = tools,
                loadedToolNames = setOf(optionalA),
            )

        assertFalse(
            "Sólo se debe soltar la que está cargada, y otras herramientas OPCIONALES no se deben soltar de manera incidental.",
            afterLoad.any { it.name == optionalB },
        )
    }

    @Test
    fun `loaded tools accumulate across rounds`() {
        val tools = largeToolSet()
        val sessionId = "spike-session"
        SessionToolLoadRegistry.clear(sessionId)

        SessionToolLoadRegistry.markLoaded(sessionId, listOf(optionalA))
        val round2 =
            ToolExposurePolicy.filterToolsForRequest(
                "ayudame con esto",
                tools,
                loadedToolNames = SessionToolLoadRegistry.loadedFor(sessionId),
            )
        assertTrue(round2.any { it.name == optionalA })

        SessionToolLoadRegistry.markLoaded(sessionId, listOf(optionalB))
        val round3 =
            ToolExposurePolicy.filterToolsForRequest(
                "ayudame con esto",
                tools,
                loadedToolNames = SessionToolLoadRegistry.loadedFor(sessionId),
            )
        assertTrue("Los elementos cargados anteriormente no se pueden sobrescribir con otros posteriores.", round3.any { it.name == optionalA })
        assertTrue(round3.any { it.name == optionalB })

        SessionToolLoadRegistry.clear(sessionId)
    }

    @Test
    fun `small tool surface is never narrowed so loading is irrelevant`() {
        val small = listOf(tool("a"), tool("b"))

        val result = ToolExposurePolicy.filterToolsForRequest("solo di algo", small)

        assertTrue("La pequeña superficie de la herramienta se emite directamente, ya sea que esté cargada o no, no afecta", result.size == small.size)
    }

    @Test
    fun `global tool is not visible until loaded`() {
        // GLOBAL Las clases solo se publican en DynamicAllowed, y la diferencia antes y después de la carga es más obvia.
        val globalTool = "app_uninstall"
        require(globalTool in ToolCategories.GLOBAL) { "$globalTool debe pertenecer a GLOBAL" }
        val tools = (1..30).map { tool("filler_$it") } + tool(globalTool)

        val before = ToolExposurePolicy.filterToolsForRequest("ayudame con esto", tools)
        assertFalse(before.any { it.name == globalTool })

        val after =
            ToolExposurePolicy.filterToolsForRequest(
                "ayudame con esto",
                tools,
                loadedToolNames = setOf(globalTool),
            )
        assertTrue(after.any { it.name == globalTool })
    }
}
