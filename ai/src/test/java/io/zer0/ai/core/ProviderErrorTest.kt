package io.zer0.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class ProviderErrorTest {
    @Test
    fun payloadTooLargeIsNonRetryableInvalidRequest() {
        val error = ProviderError.from(413, "request entity too large")

        assertEquals(ProviderError.InvalidRequest::class, error::class)
        assertFalse(error.isRetryable)
        assertEquals(413, error.httpCode)
    }

    @Test
    fun retryAfterParserRejectsNegativeAndMalformedValues() {
        assertEquals(120, ProviderError.parseRetryAfter("120"))
        assertNull(ProviderError.parseRetryAfter("-1"))
        assertNull(ProviderError.parseRetryAfter("not-a-duration"))
        assertNull(ProviderError.parseRetryAfter(null))
    }

    @Test
    fun retryAfterParserAcceptsHttpDate() {
        val target = Instant.now().plus(90, ChronoUnit.SECONDS)
            .atZone(ZoneOffset.UTC)
            .format(DateTimeFormatter.RFC_1123_DATE_TIME)

        val parsed = ProviderError.parseRetryAfter(target)

        assertTrue("HTTP-date should resolve to a non-negative delay", parsed != null && parsed in 0..91)
    }

    @Test
    fun displayMessageRedactsProviderBodySecrets() {
        val error = ProviderError.from(
            400,
            """{"error":{"message":"invalid api key sk-live-super-secret","type":"invalid_request_error"}}""",
        )

        assertFalse(error.displayMessage.contains("sk-live-super-secret"))
        assertTrue(error.displayMessage.contains("HTTP 400"))
        assertTrue(error.displayMessage.contains("[REDACTED]"))
    }

    @Test
    fun throwableMessagesDoNotCopyCredentialQueryParameters() {
        val error = ProviderError.from(
            java.io.IOException("https://provider.example/v1?api_key=sk-live-query-secret"),
        )

        assertFalse(error.displayMessage.contains("sk-live-query-secret"))
        assertTrue(error.displayMessage.contains("[REDACTED]"))
    }

    @Test
    fun insufficientQuotaIsClassifiedNotAsInvalidRequest() {
        // v2.6.8 Los comentarios de los usuarios regresan:402 / insufficient_quota Fue clasificado como Solicitud Inválida,
        // La copia rápida guía a los usuarios para ajustar los parámetros, pero lo que realmente necesitan hacer es recargar.
        val byCode = ProviderError.from(402, "insufficient_quota")
        assertEquals(ProviderError.InsufficientQuota::class, byCode::class)
        assertFalse("Saldo insuficiente, no puedo volver a intentarlo", byCode.isRetryable)

        // No es 402 pero el organismo dice claramente que la cuota es insuficiente, por lo que hay que identificarla.
        val byBody = ProviderError.from(400, "{\"error\":{\"type\":\"insufficient_quota\"}}")
        assertEquals(ProviderError.InsufficientQuota::class, byBody::class)

        // Cómo escribir una estación de transferencia china (copia real en la captura de pantalla del usuario)
        val zh = ProviderError.from(403, "用户额度不足, 剩余额度: -0.08")
        assertEquals(ProviderError.InsufficientQuota::class, zh::class)
    }

    @Test
    fun normalInvalidRequestStillClassifiedAsInvalidRequest() {
        // Protección inversa: los errores de parámetros ordinarios no pueden juzgarse erróneamente como una cuota insuficiente
        val error = ProviderError.from(400, "invalid model parameter temperature")
        assertEquals(ProviderError.InvalidRequest::class, error::class)
    }
}
