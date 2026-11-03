package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hay dos pasos en la autoprueba de la zona de pruebas: el script debe entregar el valor de retorno y el analizador debe poder distinguir“Sin valor de retorno”y“el contenido esta mal”.
 *
 * Estas dos cosas estaban fuera de lugar al mismo tiempo antes: el script no tenía retorno y la devolución de llamada era nula.
 * El análisis nuevamente trata nulo como“Formato incorrecto”, por lo que se ha informado de un problema medioambiental como error de análisis.
 */
class JsSandboxVerificationTest {

    @Test
    fun `Los scripts de autoprueba deben devolver resultados explícitamente`() {
        // evaluateJavascript Se toma el valor de toda la expresión;IIFE Cuando la última oración se escribe como una declaración en lugar de retorno,
        // La devolución de llamada recibirá "null", la zona de pruebas se considera posteriormente no disponible.
        assertTrue(
            "VERIFY_JS Debe devolver la cadena JSON; de lo contrario, evaluaJavascript simplemente obtendrá null",
            JsSandbox.VERIFY_JS.contains("return JSON.stringify"),
        )
    }

    @Test
    fun `Cuando no hay ningún valor de retorno, no se devuelve ningún resultado`() {
        val (ok, issues) = JsSandbox.parseVerificationResult("null")
        assertFalse(ok)
        assertEquals(listOf("La autoprueba no arrojó resultados"), issues)
    }

    @Test
    fun `Una devolución de llamada vacía tampoco informa que no se devolvió ningún resultado`() {
        val (ok, issues) = JsSandbox.parseVerificationResult(null)
        assertFalse(ok)
        assertEquals(listOf("La autoprueba no arrojó resultados"), issues)
    }

    @Test
    fun `Los resultados de la autoprueba codificados doblemente se pueden analizar y aprobar`() {
        val payload = """{"verified":true,"issues":[]}"""
        val (ok, issues) = JsSandbox.parseVerificationResult(encode(payload))
        assertTrue(ok)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `Proporcionar razones específicas del fracaso`() {
        val payload = """{"verified":false,"issues":["window_open_not_blocked"]}"""
        val (ok, issues) = JsSandbox.parseVerificationResult(encode(payload))
        assertFalse(ok)
        assertEquals(listOf("window_open_not_blocked"), issues)
    }

    /** evaluateJavascript Lo que obtiene la devolución de llamada es una cadena codificada en JSON, y esa codificación se reproduce aquí. */
    private fun encode(json: String): String = "\"" + json.replace("\"", "\\\"") + "\""

    @Test
    fun `Devuelve un no objeto cuando falla el análisis en lugar de pasar en silencio`() {
        val (ok, issues) = JsSandbox.parseVerificationResult("\"just a string\"")
        assertFalse(ok)
        assertTrue(issues.single().startsWith("Error en el análisis del resultado de la autoprueba"))
    }
}
