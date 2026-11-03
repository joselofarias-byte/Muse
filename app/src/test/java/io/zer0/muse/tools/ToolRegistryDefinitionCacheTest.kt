package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Caché de lista de definición de herramientas.
 *
 * La frecuencia de llamadas de este camino es mucho mayor que la intuición: verificación de capacidad antes de cada llamada de herramienta, solicitud de ensamblaje en cada ronda (dos veces),
 * Se ajustarán las tareas programadas y las respuestas automáticas del canal. `listToolsAsToolDefinitions()`. La semántica de invalidación de caché debe
 * con `_revision` Alineación estricta; de lo contrario, aparecerá "las herramientas recién registradas no se pueden ver en el esquema".
 * fracaso silencioso —— Por lo tanto, el enfoque aquí está en medir el fracaso, en lugar de medir la velocidad.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryDefinitionCacheTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun registry(): ToolRegistry = ToolRegistry(context)

    private fun echoTool(name: String) = ToolRegistry.ToolDef(
        name = name,
        description = "Herramientas para probar $name",
        parameters = mapOf("text" to "texto para hacer eco"),
        parameterTypes = mapOf("text" to "string"),
        required = setOf("text"),
        riskLevel = ToolRiskLevel.SAFE,
    )

    @Test
    fun repeat_calls_return_the_same_instances() {
        val registry = registry()
        registry.register(echoTool("cache_probe_a")) { args -> args["text"] ?: "" }

        val first = registry.listToolsAsToolDefinitions()
        val second = registry.listToolsAsToolDefinitions()
        assertSame("Se debe acceder al caché cuando el registro no ha cambiado", first, second)
    }

    @Test
    fun registering_a_tool_invalidates_the_cache() {
        val registry = registry()
        registry.register(echoTool("cache_probe_b")) { args -> args["text"] ?: "" }
        val before = registry.listToolsAsToolDefinitions()
        val beforeNames = before.map { it.name }

        registry.register(echoTool("cache_probe_c")) { args -> args["text"] ?: "" }
        val after = registry.listToolsAsToolDefinitions()

        assertNotSame("Debe reconstruirse después de registrar una nueva herramienta", before, after)
        assertTrue("Nuevas herramientas deben aparecer en la definición.", "cache_probe_c" in after.map { it.name })
        assertTrue("La reconstrucción no debe desechar herramientas viejas.", beforeNames.all { it in after.map { d -> d.name } })
    }

    @Test
    fun unregistering_a_tool_invalidates_the_cache() {
        val registry = registry()
        registry.register(echoTool("cache_probe_d")) { args -> args["text"] ?: "" }
        assertTrue(registry.listToolsAsToolDefinitions().any { it.name == "cache_probe_d" })

        registry.unregister("cache_probe_d")
        assertTrue(
            "Después de cerrar sesión, no debe volver a aparecer en la definición.",
            registry.listToolsAsToolDefinitions().none { it.name == "cache_probe_d" },
        )
    }

    @Test
    fun filtered_list_is_cached_and_matches_manual_filter() {
        val registry = registry()
        registry.register(echoTool("cache_probe_e")) { args -> args["text"] ?: "" }
        registry.register(echoTool("cache_probe_f")) { args -> args["text"] ?: "" }

        val filter = listOf("cache_probe_e")
        val first = registry.listToolsAsToolDefinitions(filter)
        val second = registry.listToolsAsToolDefinitions(filter)
        assertSame("La misma condición de filtro debe afectar al caché.", first, second)
        assertEquals(listOf("cache_probe_e"), first.map { it.name })

        val manual = registry.listToolsAsToolDefinitions().filter { it.name in filter }
        assertEquals("Los resultados almacenados en caché deben ser coherentes con el filtrado manual", manual.map { it.name }, first.map { it.name })
    }

    @Test
    fun filter_cache_is_dropped_when_registry_changes() {
        val registry = registry()
        registry.register(echoTool("cache_probe_g")) { args -> args["text"] ?: "" }
        val filter = listOf("cache_probe_g")
        assertEquals(1, registry.listToolsAsToolDefinitions(filter).size)

        registry.register(echoTool("cache_probe_h")) { args -> args["text"] ?: "" }
        val after = registry.listToolsAsToolDefinitions(filter)
        assertEquals("La caché del filtro debe invalidarse con la versión", listOf("cache_probe_g"), after.map { it.name })

        val both = registry.listToolsAsToolDefinitions(listOf("cache_probe_g", "cache_probe_h"))
        // El orden lo determina el mapa interno del registro (el mismo que antes del cambio), aquí solo se compara la colección.
        assertEquals(setOf("cache_probe_g", "cache_probe_h"), both.map { it.name }.toSet())
    }

    @Test
    fun schema_shape_is_unchanged_for_parameter_tools() {
        val registry = registry()
        registry.register(echoTool("cache_probe_i")) { args -> args["text"] ?: "" }
        val definition = registry.listToolsAsToolDefinitions().first { it.name == "cache_probe_i" }
        assertTrue("todavía genera tipo de objeto schema", definition.parametersJsonSchema.contains("\"type\":\"object\""))
        assertTrue("Las descripciones de los parámetros aún deben incluirse", definition.parametersJsonSchema.contains("texto para hacer eco"))
        assertTrue("required No puedo tirarlo", definition.parametersJsonSchema.contains("\"required\""))
    }
}
