package io.zer0.muse.ai

import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.ProviderHttpSupport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProviderHttpSupport HTTP Pruebas unitarias de clasificación de errores.
 *
 * prueba [ProviderHttpSupport] companion object en:
 *  - [ProviderHttpSupport.classifyHttpCode]:HTTP código de estado → cadena de clasificación legible por humanos
 *  - [ProviderHttpSupport.buildHttpErrorMessage]:Crear mensaje de error completo con categoría + resumen del cuerpo
 *  - [ProviderHttpSupport.readBodySafely]:lectura segura Response body(IOException / IllegalStateException no tirar)
 *  - [ProviderHttpSupport.readBodyCapped]:Leer el cuerpo y truncarlo a la longitud especificada
 *
 * Asignación de código de estado anulado:
 *  - 401 → "authentication failed"(Error de autenticación)
 *  - 403 → "permission denied"(Permiso denegado)
 *  - 408 → "request timeout"(Solicitar tiempo de espera)
 *  - 429 → "rate limited"(Limitación de corriente)
 *  - 500~599 → "server error"(error del servidor)
 *  - Otros → null(No se requiere clasificación especial)
 */
class ProviderHttpSupportTest {

    // ── classifyHttpCode:HTTP código de estado → Cadena de clasificación ────────────────────────

    @Test
    fun `401 Error en la asignación como autenticación`() {
        assertEquals("authentication failed", ProviderHttpSupport.classifyHttpCode(401))
    }

    @Test
    fun `403 Mapa como permiso denegado`() {
        assertEquals("permission denied", ProviderHttpSupport.classifyHttpCode(403))
    }

    @Test
    fun `429 Asignar al límite actual`() {
        assertEquals("rate limited", ProviderHttpSupport.classifyHttpCode(429))
    }

    @Test
    fun `408 Mapas para solicitar tiempo de espera`() {
        assertEquals("request timeout", ProviderHttpSupport.classifyHttpCode(408))
    }

    @Test
    fun `500 error asignado al servidor`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(500))
    }

    @Test
    fun `502 error asignado al servidor`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(502))
    }

    @Test
    fun `503 error asignado al servidor`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(503))
    }

    @Test
    fun `599 error asignado al servidor(Límite superior)`() {
        assertEquals("server error", ProviderHttpSupport.classifyHttpCode(599))
    }

    @Test
    fun `499 No es un error del servidor(fuera del límite inferior)`() {
        // 4xx Sólo 401/403/408/429 están clasificados,499 Regresar null
        assertNull(ProviderHttpSupport.classifyHttpCode(499))
    }

    @Test
    fun `400 Regresar null(Sin clasificación especial)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(400))
    }

    @Test
    fun `404 Regresar null(Sin clasificación especial)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(404))
    }

    @Test
    fun `200 Regresar null(Código de estado de éxito sin categoría)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(200))
    }

    @Test
    fun `301 Regresar null(Redirigir sin clasificación)`() {
        assertNull(ProviderHttpSupport.classifyHttpCode(301))
    }

    // ── buildHttpErrorMessage:Generar mensaje de error completo ──────────────────────────

    @Test
    fun `buildHttpErrorMessage Contiene prefijo y código de estado`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("OpenAI", 500, "")
        assertTrue("debe contener prefijo: $msg", msg.contains("OpenAI"))
        assertTrue("Debe contener código de estado: $msg", msg.contains("500"))
        assertTrue("debe contener categorías: $msg", msg.contains("server error"))
    }

    @Test
    fun `buildHttpErrorMessage Contiene clasificación de certificación 401`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Gemini", 401, "")
        assertTrue("debe contener authentication failed: $msg", msg.contains("authentication failed"))
    }

    @Test
    fun `buildHttpErrorMessage Contiene 429 categorías limitantes actuales`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Anthropic", 429, "")
        assertTrue("debe contener rate limited: $msg", msg.contains("rate limited"))
    }

    @Test
    fun `buildHttpErrorMessage Contiene un resumen del cuerpo truncado`() {
        val body = "Invalid API key provided"
        val msg = ProviderHttpSupport.buildHttpErrorMessage("OpenAI", 401, body)
        assertTrue("Debe contener resumen corporal insensibilizado.: $msg", msg.contains("API key=[REDACTED]"))
        assertFalse("Los campos de credenciales no deben repetirse sin cambios: $msg", msg.contains(body))
    }

    @Test
    fun `buildHttpErrorMessage Truncar cuerpo extralargo`() {
        // Construcción extra larga body(>200 personaje),La autenticación se trunca a 200
        val longBody = "x".repeat(500)
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Prefix", 500, longBody)
        // Después del truncamiento, la parte del cuerpo no debe exceder los 200 caracteres.
        val bodyPart = msg.substringAfter(": ", "")
        assertTrue("body Debe truncarse a menos de 200 caracteres.,real ${bodyPart.length}", bodyPart.length <= 200)
    }

    @Test
    fun `buildHttpErrorMessage No incluir corchetes cuando no exista clasificación`() {
        // 400 Sin categoría,El mensaje no debe contener [category]
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Prefix", 400, "bad request")
        assertTrue("Debe contener código de estado: $msg", msg.contains("400"))
        // 400 Sin categoría,no debería [xxx]
        assertTrue("No deben incluirse corchetes cuando no exista clasificación: $msg", !msg.contains("["))
    }

    @Test
    fun `buildHttpErrorMessage No se añaden dos puntos cuando el cuerpo está vacío`() {
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Prefix", 401, "")
        // body No debe estar presente cuando esté vacío. ": " delimitador
        assertTrue("El cuerpo vacío no debe contener delimitadores de dos puntos.: $msg", !msg.endsWith(": "))
    }

    // ── readBodySafely:lectura segura Response body ──────────────────────────

    /** Construye una respuesta okhttp con el cuerpo especificado. */
    private fun buildResponse(body: String, code: Int = 200): Response {
        return Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody("text/plain".toMediaType()))
            .build()
    }

    @Test
    fun `readBodySafely Leer el contenido del cuerpo normalmente`() {
        val response = buildResponse("hello world")
        val body = ProviderHttpSupport.readBodySafely(response)
        assertEquals("hello world", body)
    }

    @Test
    fun `readBodySafely body Devuelve una cadena vacía cuando es nula`() {
        // estructura body=null de Response
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(204)
            .message("No Content")
            .build()
        assertEquals("", ProviderHttpSupport.readBodySafely(response))
    }

    @Test
    fun `readBodySafely Lanza IOException al leer y devuelve una cadena vacía`() {
        // Construye uno usando simulacro body.string() Lanzar IOException Response
        val mockBody = mockk<okhttp3.ResponseBody>()
        every { mockBody.string() } throws java.io.IOException("connection reset")
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(mockBody)
            .build()
        assertEquals("IOException debería devolver una cadena vacía", "", ProviderHttpSupport.readBodySafely(response))
    }

    @Test
    fun `readBodySafely Lanza IllegalStateException al leer y devuelve una cadena vacía`() {
        // v1.97: EventSourceListener El cuerpo en la devolución de llamada ha sido stripped,leer tirar IllegalStateException
        val mockBody = mockk<okhttp3.ResponseBody>()
        every { mockBody.string() } throws IllegalStateException("body is stripped")
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(mockBody)
            .build()
        assertEquals("IllegalStateException debería devolver una cadena vacía", "", ProviderHttpSupport.readBodySafely(response))
    }

    // ── readBodyCapped:leer y truncar body ──────────────────────────────────

    @Test
    fun `readBodyCapped El cuerpo demasiado largo se trunca a la longitud especificada`() {
        val longBody = "abcdefghij".repeat(100) // 1000 personaje
        val response = buildResponse(longBody)
        val capped = ProviderHttpSupport.readBodyCapped(response, maxLen = 50)
        assertEquals("Debe truncarse a 50 caracteres.", 50, capped.length)
        assertEquals("abcdefghij".repeat(5), capped)
    }

    @Test
    fun `readBodyCapped El cuerpo corto se devuelve tal cual`() {
        val shortBody = "short"
        val response = buildResponse(shortBody)
        val capped = ProviderHttpSupport.readBodyCapped(response, maxLen = 500)
        assertEquals(shortBody, capped)
    }

    @Test
    fun `readBodyCapped El valor predeterminado se trunca a ERROR_BODY_MAX_LEN`() {
        // Predeterminado maxLen = 500(ERROR_BODY_MAX_LEN)
        val longBody = "x".repeat(1000)
        val response = buildResponse(longBody)
        val capped = ProviderHttpSupport.readBodyCapped(response)
        assertEquals("El valor predeterminado debe truncarse a 500 caracteres.", ProviderHttpSupport.ERROR_BODY_MAX_LEN, capped.length)
    }

    @Test
    fun `readBodyCapped Devuelve una cadena vacía al leer una excepción`() {
        val mockBody = mockk<okhttp3.ResponseBody>()
        every { mockBody.string() } throws java.io.IOException("read error")
        val response = Response.Builder()
            .request(Request.Builder().url("http://test.example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(mockBody)
            .build()
        assertEquals("", ProviderHttpSupport.readBodyCapped(response, maxLen = 100))
    }

    // ── Escena completa:código de error → Clasificación → Enlace completo del mensaje de error ────────────────────────

    @Test
    fun `401 Error enlace completo clasificado en mensaje de error`() {
        val code = 401
        val category = ProviderHttpSupport.classifyHttpCode(code)
        assertEquals("authentication failed", category)
        val msg = ProviderHttpSupport.buildHttpErrorMessage("OpenAI", code, "invalid api key")
        assertTrue(msg.contains("HTTP 401"))
        assertTrue(msg.contains("authentication failed"))
        assertTrue(msg.contains("invalid api key"))
    }

    @Test
    fun `503 Error enlace completo clasificado en mensaje de error`() {
        val code = 503
        val category = ProviderHttpSupport.classifyHttpCode(code)
        assertEquals("server error", category)
        val msg = ProviderHttpSupport.buildHttpErrorMessage("Gemini", code, "service unavailable")
        assertTrue(msg.contains("HTTP 503"))
        assertTrue(msg.contains("server error"))
        assertTrue(msg.contains("service unavailable"))
    }
}
