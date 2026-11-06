package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindToolsToolTest {

    private fun def(name: String, desc: String) = ToolRegistry.ToolDef(
        name = name,
        description = desc,
        parameters = mapOf("x" to "param"),
        required = setOf("x"),
        // El nivel de riesgo del accesorio no es un foco de esta prueba (recuperar/No lo mires en grupos), pero no puedes simplemente escribir SEGURO:
        // Todos los nombres son nombres de herramientas reales o nombres de herramientas de terceros, y se utiliza el mismo conjunto de inferencias en el lado de producción para dar valores honestos.
        riskLevel = ToolPermissionResolver.riskLevelFor(name),
    )

    private val tools = listOf(
        def("browser_navigate", "Abra el navegador y visite la página web. open a URL in browser"),
        def("browser_click", "Haga clic en el elemento web click an element on the page"),
        def("workspace_write", "Escribir en el archivo del espacio de trabajo write a file into the workspace"),
        def("set_alarm", "Establecer alarma set an alarm"),
        def("find_tools", "search the tool library"),
    )

    @Test
    fun `search matches by name and description`() {
        val byName = FindToolsTool.search("browser", tools).map { it.name }.toSet()
        assertTrue("El nombre visitado debe contener browser_navigate", "browser_navigate" in byName)
        assertTrue("El nombre visitado debe contener browser_click", "browser_click" in byName)
        assertTrue("Las herramientas no relacionadas no deberían afectar", "set_alarm" !in byName)

        val byDesc = FindToolsTool.search("espacio de trabajo", tools).map { it.name }
        assertEquals(listOf("workspace_write"), byDesc)
    }

    @Test
    fun `search excludes the meta tool itself`() {
        val hits = FindToolsTool.search("tool library", tools).map { it.name }
        assertTrue("find_tools no debería aparecer en los resultados", "find_tools" !in hits)
    }

    @Test
    fun `search caps result count`() {
        val many = (1..20).map { def("tool_$it", "demo tool number $it") }
        val hits = FindToolsTool.search("demo", many)
        assertTrue("El resultado no debe estar vacío.", hits.isNotEmpty())
        assertTrue("El número de resultados debe estar sujeto a un límite superior.", hits.size <= 8)
    }

    @Test
    fun `empty query returns nothing`() {
        assertTrue(FindToolsTool.search("   ", tools).isEmpty())
    }

    @Test
    fun `loaded registry is session scoped and idempotent`() {
        SessionToolLoadRegistry.markLoaded("s1", listOf("browser_navigate", "set_alarm"))
        SessionToolLoadRegistry.markLoaded("s1", listOf("browser_navigate"))
        assertEquals(
            setOf("browser_navigate", "set_alarm"),
            SessionToolLoadRegistry.loadedFor("s1"),
        )
        assertTrue("Otras sesiones no se ven afectadas.", SessionToolLoadRegistry.loadedFor("s2").isEmpty())
        SessionToolLoadRegistry.clear("s1")
        assertTrue(SessionToolLoadRegistry.loadedFor("s1").isEmpty())
    }

    @Test
    fun `empty query renders full capability list grouped by category`() {
        val tools = listOf(
            def("calculator", "calculadora sencilla"),
            def("web_search", "búsqueda en internet"),
            def("generate_image", "Generar imágenes"),
            def("workspace_write", "Escribir en el espacio de trabajo"),
            def("mcp_x__do", "herramientas externas"),
            def("find_tools", "search the tool library"),
        )
        val list = FindToolsTool.fullList(tools)
        assertTrue("Se debe incluir una descripción del total.", list.contains("5 tools total"))
        assertTrue("Las herramientas principales deberían aparecer.", "calculator" in list)
        assertTrue("Deberían aparecer herramientas opcionales.", "generate_image" in list)
        assertTrue("La agrupación extendida debería aparecer.", "extension" in list)
        assertTrue("en sí mismo no debería aparecer en la lista", "find_tools:" !in list)
    }
}
