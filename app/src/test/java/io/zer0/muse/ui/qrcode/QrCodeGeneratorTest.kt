package io.zer0.muse.ui.qrcode

import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QrCodeGeneratorTest {
    private val provider = ProviderConfig(
        id = "shared-provider",
        displayName = "Shared provider",
        baseUrl = "https://example.com/v1",
        apiKey = "test-api-secret",
        oauthConfig = OAuthConfig(
            authorizeUrl = "https://example.com/authorize",
            tokenUrl = "https://example.com/token",
            clientId = "public-client",
            clientSecret = "test-client-secret",
        ),
    )

    @Test
    fun defaultEncodingExcludesApiKeyAndOAuthSecret() {
        val result = roundTrip(provider)

        assertEquals(provider.copy(apiKey = "", oauthConfig = provider.oauthConfig?.copy(clientSecret = null)), result)
        assertTrue(QrCodeGenerator.isProviderQr(QrCodeGenerator.encodeProvider(provider)))
    }

    @Test
    fun defaultEncodingExcludesNestedGeminiPrivateKey() {
        val specific = ProviderSpecificConfig.Gemini(
            useVertexAI = true,
            useServiceAccount = true,
            privateKey = "test-private-secret",
            projectId = "public-project",
        )
        val result = roundTrip(provider.copy(type = ProviderType.GEMINI, specific = specific))

        assertEquals(specific.copy(privateKey = ""), result.specific)
    }

    @Test
    fun defaultEncodingExcludesOpenAiHeadersAndNestedBody() {
        val specific = ProviderSpecificConfig.OpenAI(
            customHeaders = mapOf("Authorization" to "Bearer test-secret"),
            customBody = mapOf("auth" to JsonObject(mapOf("token" to JsonPrimitive("test-body-secret")))),
            useResponseApi = true,
        )
        val result = roundTrip(provider.copy(specific = specific))

        assertEquals(specific.copy(customHeaders = emptyMap(), customBody = emptyMap()), result.specific)
    }

    @Test
    fun defaultEncodingExcludesCustomHeadersBodyAndTemplate() {
        val specific = ProviderSpecificConfig.Custom(
            customHeaders = mapOf("x-api-key" to "test-header-secret"),
            customBody = mapOf("auth" to JsonObject(mapOf("token" to JsonPrimitive("test-body-secret")))),
            requestTemplate = """{"token":"test-template-secret"}""",
            responsePath = "$.response",
        )
        val result = roundTrip(provider.copy(specific = specific))

        assertEquals(
            specific.copy(customHeaders = emptyMap(), customBody = emptyMap(), requestTemplate = ""),
            result.specific,
        )
    }

    @Test
    fun explicitConsentPreservesSecretsAndImportContract() {
        val originals = listOf(
            provider,
            provider.copy(specific = ProviderSpecificConfig.Gemini(privateKey = "test-private-secret")),
            provider.copy(
                specific = ProviderSpecificConfig.OpenAI(customHeaders = mapOf("Authorization" to "test-secret")),
            ),
            provider.copy(
                specific = ProviderSpecificConfig.Custom(requestTemplate = """{"token":"test-secret"}"""),
            ),
        )

        originals.forEach { original ->
            assertEquals(original, roundTrip(original, includeSecrets = true))
        }
    }

    @Test
    fun nonSecretSpecificConfigurationIsPreserved() {
        val specific = ProviderSpecificConfig.Anthropic(promptCaching = true)
        val result = roundTrip(provider.copy(specific = specific))

        assertEquals(specific, result.specific)
        assertEquals(provider.baseUrl, result.baseUrl)
    }

    private fun roundTrip(config: ProviderConfig, includeSecrets: Boolean = false): ProviderConfig = requireNotNull(
        QrCodeGenerator.parseProviderQr(
            if (includeSecrets) {
                QrCodeGenerator.encodeProvider(config, includeSecrets = true)
            } else {
                QrCodeGenerator.encodeProvider(config)
            },
        ),
    )
}
