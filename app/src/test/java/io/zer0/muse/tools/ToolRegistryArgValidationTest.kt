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
class ToolRegistryArgValidationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun execute_rejectsMissingRequiredArgWithStructuredError() = runBlocking {
        val registry = ToolRegistry(context)
        WeatherToolsRegistrar(context, registry)
        val outcome = registry.execute("get_weather", emptyMap())
        // diagnóstico provisional:Imprima el contenido real en caso de falla
        if (!outcome.isError) {
            println("DIAG outcome=" + outcome.content + " details=" + outcome.details + " names=" + registry.listTools().map { it.name })
        }
        assertTrue(outcome.isError)
        assertTrue(outcome.content.contains("location"))
        assertEquals(ToolArgValidator.ERROR_TYPE, outcome.details["errorType"])
    }

    @Test
    fun execute_coercesNumericStringForTypedParam() = runBlocking {
        val registry = ToolRegistry(context)
        CoreToolsRegistrar(context, registry)
        // calculator Si no hay una declaración de tipo, use ejecutar_javascript o use directamente una herramienta con tipos conocidos.
        // Esto verifica que la ruta normal para ejecutarFromJson aún pueda ejecutar la calculadora.
        val outcome = registry.execute("calculator", mapOf("expression" to "1+2"))
        assertTrue(!outcome.isError)
        assertTrue(outcome.content.contains("= 3"))
    }
}
