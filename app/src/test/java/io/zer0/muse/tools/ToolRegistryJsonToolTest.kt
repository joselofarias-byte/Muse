package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolRegistryJsonToolTest {

    @Test
    fun `JSON tool receives numeric and boolean values without string coercion`() = runBlocking {
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        val schema = """{"type":"object","properties":{"count":{"type":"integer"},"enabled":{"type":"boolean"},"filters":{"type":"array","items":{"type":"string"}}},"required":["count","enabled"]}"""
        registry.registerJson(
            ToolRegistry.ToolDef(
                name = "mcp_test__typed",
                description = "typed test tool",
                parameters = mapOf(
                    "count" to "number",
                    "enabled" to "boolean",
                ),
                required = setOf("count", "enabled"),
                category = "mcp",
                riskLevel = ToolRiskLevel.NORMAL,
                parameterTypes = mapOf("count" to "integer", "enabled" to "boolean"),
                rawParametersJsonSchema = schema,
            ),
        ) { args ->
            "count=${args["count"]};enabled=${args["enabled"]}"
        }

        val result = registry.executeFromJson(
            "mcp_test__typed",
            """{"count":3,"enabled":true}""",
        )

        assertEquals("count=3;enabled=true", result)
        assertTrue(result.contains("count=3"))
        val exposed = registry.listToolsAsToolDefinitions()
            .first { it.name == "mcp_test__typed" }
        assertTrue(exposed.parametersJsonSchema.contains("\"items\""))
    }

    @Test
    fun `nested object passed as escaped JSON string is decoded for the tool`() = runBlocking {
        // Retorno:render_data Estas herramientas requieren parámetros de objetos anidados, pero el modelo suele pasar el esquema como una cadena.
        // Escapar de JSON ("{\"labels\":[\"a\"]}"). Para implementación anterior toString().trim('"') Quite solo las comillas más externas,
        // capa interna \" El residuo de escape provoca un error en el análisis (informe“data Debe ser un objeto JSON”).
        val registry = ToolRegistry(ApplicationProvider.getApplicationContext<Context>())
        registry.register(
            ToolRegistry.ToolDef(
                name = "nested_object_probe",
                description = "probe",
                parameters = mapOf("data" to "object payload"),
                required = setOf("data"),
                parameterTypes = mapOf("data" to "object"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            // La herramienta analiza internamente datos como JSON como render_data y devuelve la cantidad de etiquetas resueltas si tiene éxito.
            val parsed = io.zer0.common.AppJson.parseToJsonElement(args["data"].orEmpty())
            val labels = (parsed as? kotlinx.serialization.json.JsonObject)?.get("labels")
            val count = (labels as? kotlinx.serialization.json.JsonArray)?.size ?: -1
            "ok:$count"
        }

        val escaped = """{"data":"{\"labels\":[\"a\",\"b\",\"c\"],\"values\":[1,2,3]}"}"""
        assertEquals("ok:3", registry.executeFromJson("nested_object_probe", escaped))

        // También está disponible pasar objetos directamente.
        val direct = """{"data":{"labels":["x"],"values":[1]}}"""
        assertEquals("ok:1", registry.executeFromJson("nested_object_probe", direct))
    }
}
