package io.zer0.ai.core

import io.zer0.ai.anthropic.AnthropicProvider
import io.zer0.ai.gemini.GeminiProvider
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for cancellation racing with the first retryable SSE response.
 *
 * A real server response is required here: the old EventSource implementation could schedule a
 * reconnect from a stale callback after the caller had already cancelled the request.
 */
class ProviderRetryAbortRaceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `Anthropic abort during retry backoff prevents reconnect`() = runBlocking {
        assertNoReconnectAfterRetryableResponse(
            ProviderConfig(
                id = "anthropic-race",
                displayName = "Anthropic Race",
                type = ProviderType.ANTHROPIC,
                baseUrl = server.url("/v1").toString(),
                apiKey = "test-key",
            ),
            provider = { config -> AnthropicProvider(config) },
            modelId = "claude-race",
            abortAtBackoff = true,
        )
    }

    @Test
    fun `Anthropic abort cancels long rate limit backoff promptly`() = runBlocking {
        assertNoReconnectAfterRetryableResponse(
            ProviderConfig(
                id = "anthropic-long-backoff",
                displayName = "Anthropic Rate Limit",
                type = ProviderType.ANTHROPIC,
                baseUrl = server.url("/v1").toString(),
                apiKey = "test-key",
            ),
            provider = { config -> AnthropicProvider(config) },
            modelId = "claude-race",
            abortAtBackoff = true,
            statusCode = 429,
        )
    }

    @Test
    fun `Gemini abort during retry backoff prevents reconnect`() = runBlocking {
        assertNoReconnectAfterRetryableResponse(
            ProviderConfig(
                id = "gemini-race",
                displayName = "Gemini Race",
                type = ProviderType.GEMINI,
                baseUrl = server.url("/v1beta").toString(),
                apiKey = "test-key",
                specific = ProviderSpecificConfig.Gemini(),
            ),
            provider = { config -> GeminiProvider(config) },
            modelId = "gemini-race",
        )
    }

    @Test
    fun `Gemini abort cancels long retry backoff promptly`() = runBlocking {
        assertNoReconnectAfterRetryableResponse(
            ProviderConfig(
                id = "gemini-race",
                displayName = "Gemini Race",
                type = ProviderType.GEMINI,
                baseUrl = server.url("/v1beta").toString(),
                apiKey = "test-key",
                specific = ProviderSpecificConfig.Gemini(),
            ),
            provider = { config -> GeminiProvider(config) },
            modelId = "gemini-race",
            abortAtBackoff = true,
        )
    }

    private suspend fun assertNoReconnectAfterRetryableResponse(
        config: ProviderConfig,
        provider: (ProviderConfig) -> Provider,
        modelId: String,
        abortAtBackoff: Boolean = false,
        statusCode: Int = 503,
    ) = coroutineScope {
        val firstRequest = CountDownLatch(1)
        server.start()
        server.delegate.dispatcher = object : Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                firstRequest.countDown()
                return MockResponse.Builder()
                    .code(statusCode)
                    .clearHeaders()
                    .addHeader("Retry-After", "60")
                    .addHeader("Content-Type", "text/event-stream")
                    .body("""{"error":{"message":"temporary"}}""")
                    .build()
            }
        }

        val signal = AbortSignal()
        val request = ChatRequest(
            messages = listOf(UIMessage(role = MessageRole.USER, content = "retry then cancel")),
            model = Model(id = modelId, providerId = config.id),
            abortSignal = signal,
        )
        val backoffStarted = CountDownLatch(1)
        val previousSink = Logger.sink
        val previousLoggingEnabled = Logger.enabled
        if (abortAtBackoff) {
            Logger.enabled = true
            Logger.sink = { _, tag, message, _ ->
                if (tag == "${config.type.name.lowercase().replaceFirstChar { it.uppercase() }}Provider" &&
                    message.startsWith("streamChat retryable failure(code=$statusCode")
                ) {
                    // Abort synchronously inside the retry coroutine, after the failed SSE was cancelled.
                    signal.abort()
                    backoffStarted.countDown()
                }
            }
        }
        val collector = launch(Dispatchers.IO) {
            provider(config).streamChat(request).collect()
        }

        try {
            assertTrue("first retryable response should arrive", firstRequest.await(5, TimeUnit.SECONDS))
            if (abortAtBackoff) {
                assertTrue("retry backoff should start", backoffStarted.await(5, TimeUnit.SECONDS))
            } else {
                signal.abort()
            }
            withTimeout(if (abortAtBackoff) 2_000 else 5_000) { collector.join() }

            // Allow the normal 1s retry backoff plus callback delivery time to elapse.
            delay(1_500)
            assertEquals("cancelled retry must not reconnect", 1, server.requestCount)
            assertEquals("abort listener must be removed", 0, signal.listenerCount)
        } finally {
            collector.cancel()
            if (abortAtBackoff) {
                Logger.sink = previousSink
                Logger.enabled = previousLoggingEnabled
            }
        }
    }
}
