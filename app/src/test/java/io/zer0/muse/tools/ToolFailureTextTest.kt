package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * v2.2.1: Prueba única de redacción de retroalimentación de falla de herramienta.
 */
class ToolFailureTextTest {

    @Test
    fun `La clasificación del tiempo de espera incluye conexión o lectura y dirección.`() {
        val text = ToolFailureText.httpFailure("HTTP GET", "https://example.com/a", SocketTimeoutException("timeout"))
        assertTrue(text.contains("tiempo de espera"))
        assertTrue(text.contains("https://example.com/a"))

        val callTimeout = ToolFailureText.httpFailure("http_post", "https://example.com/b", InterruptedIOException("timeout"))
        assertTrue(callTimeout.contains("tiempo de espera"))
    }

    @Test
    fun `Clasificación de fallas en la resolución de nombres de dominio`() {
        val text = ToolFailureText.httpFailure("HTTP GET", "https://nope.invalid", UnknownHostException("Unresolved"))
        assertTrue(text.contains("Falló la resolución del nombre de dominio"))
        assertTrue(text.contains("https://nope.invalid"))
    }

    @Test
    fun `TLS Clasificación de fallas de apretón de manos`() {
        val text = ToolFailureText.httpFailure("HTTP GET", "https://bad.example", SSLHandshakeException("bad cert"))
        assertTrue(text.contains("TLS"))
    }

    @Test
    fun `El resto de excepciones tienen información y direcciones originales.`() {
        val text = ToolFailureText.httpFailure("HTTP POST", "https://example.com/c", IOException("connection reset"))
        assertTrue(text.contains("https://example.com/c"))
        assertTrue(text.contains("connection reset"))
    }

    @Test
    fun `Cubierta de excepción vacía`() {
        val text = ToolFailureText.httpFailure("HTTP GET", "https://example.com", null)
        assertTrue(text.contains("Anomalía de red desconocida"))
        assertEquals(true, text.contains("https://example.com"))
    }
}
