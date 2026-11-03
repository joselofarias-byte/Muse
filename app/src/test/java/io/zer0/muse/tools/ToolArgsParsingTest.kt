package io.zer0.muse.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.6.2(auditoría de usuario): hook / Rutas de aprobación y ejecución parametrizadas**Análisis de parámetros de la misma fuente.**Devolver.
 *
 * Anteriormente, parseToolCallArgs usaba análisis estándar simple:JSON con formato incorrecto (que une varios objetos) devuelve vacío map,
 * En la ejecución real, se puede utilizar parseArgumentsLenient para guardarlo. —— hook Visto con ParamPolicies
 * Los parámetros no son consistentes con la ejecución real (file:// Dicho rechazo a nivel de parámetro también quedará invalidado).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolArgsParsingTest {

    private val registry: ToolRegistry
        get() = ToolRegistry(ApplicationProvider.getApplicationContext<Context>()).also {
            ParamPolicies.registerBuiltIn()
        }

    private fun parse(json: String): Map<String, Any> = toolArgsToMap(registry.parseArgumentsLenient(json))

    @Test
    fun `malformed concatenated json still yields the same args as execution`() {
        // Mismo origen que ToolRegistry.parseArgumentsLenient:Empalmar varios objetos también puede resolver claves.
        val args = parse("{}" + """{"url":"http://example.com"}""")
        assertEquals("http://example.com", args["url"])
    }

    @Test
    fun `file url is visible to param policies after lenient parse`() {
        // Devoluciones de seguridad críticas:file:// URL Debe ser visible en estrategias a nivel de parámetro (previamente mal formadas). JSON → vacío map → Negarse a fracasar).
        val args = parse("""{"url":"file:///etc/passwd"}""")
        val state = ParamPolicies.evaluate("open_url", args)
        assertTrue("file:// Debe ser rechazado por la política de parámetros.", state is ToolApprovalState.Denied)
    }

    @Test
    fun `numeric and boolean args keep their json types`() {
        val args = parse("""{"count":3,"ratio":1.5,"enabled":true,"off":false,"name":"x"}""")
        assertEquals(3L, args["count"])
        assertEquals(1.5, args["ratio"])
        assertEquals(true, args["enabled"])
        assertEquals(false, args["off"])
        assertEquals("x", args["name"])
    }

    @Test
    fun `nested object is passed as json text`() {
        val args = parse("""{"data":{"labels":["a","b"]}}""")
        val text = args["data"] as String
        assertTrue("Los objetos anidados deben pasarse como texto JSON", text.contains("\"labels\""))
    }
}
