package io.zer0.ai.image

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderKeyRotation
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T1.2: [AgnesImageProvider] 429 Corte la clave y vuelva a intentar la prueba.(MockWebServer).
 *
 * verificar:
 *  - Múltiples escenarios clave:Después de recibir 429 en la primera solicitud,Cambia automáticamente a la siguiente tecla y vuelve a intentarlo.;
 *  - Reintentar exitosamente(200)Devuelve la imagen cuando;
 *  - Inténtalo de nuevo 429(Si solo hay una tecla o la corriente aún está limitada después de la rotación)Se lanza RATE_LIMITED.
 *
 * Personalizado inyectando [ProviderKeyRotation] fábrica,Asegúrese de que los escenarios de múltiples claves lleguen a sk_a y
 * 429 y luego cambiar a sk_b,Haga que el encabezado de Autorización de ambas solicitudes sea deterministamente afirmable.
 */
class AgnesImageProvider429KeySwitchTest {

    @org.junit.Before
    fun setUp() {
        Logger.enabled = false
    }

    @org.junit.After
    fun tearDown() {
        Logger.enabled = true
    }

    private fun agnesConfig(apiKey: String, baseUrl: String) = ProviderConfig(
        id = "test-agnes",
        displayName = "agnes-test",
        type = ProviderType.OPENAI,
        baseUrl = baseUrl,
        apiKey = apiKey,
    )

    /**
     * Construir una fábrica de rotación de claves determinista:
     *  - Tecla única: devolver directamente lo recortado key,switchToNextKey constante false;
     *  - Clave múltiple: EffectiveApiKey devuelta por primera vez "sk_a",switchToNextKey regresar después "sk_b".
     */
    private fun deterministicKeyRotationFactory(): ((ProviderConfig) -> ProviderKeyRotation) {
        fun factory(config: ProviderConfig): ProviderKeyRotation {
            val multi = config.apiKey.contains(",")
            if (!multi) return ProviderKeyRotation(config)
            var switched = false
            return object : ProviderKeyRotation(config) {
                override fun effectiveApiKey(): String = if (switched) "sk_b" else "sk_a"

                override fun switchToNextKey(): Boolean {
                    switched = true
                    return true
                }
            }
        }
        return { cfg: ProviderConfig -> factory(cfg) }
    }

    @Test
    fun `429 switches to next key and retry succeeds`() {
        val server = MockWebServer()
        try {
            // el primero key(sk_a) 429,segundo key(sk_b) éxito
            val successBody = """{"data":[{"b64_json":"AAA"}]}"""
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody(successBody))

            val provider = AgnesImageProvider(
                OkHttpClient(),
                keyRotationFactory = deterministicKeyRotationFactory(),
            )
            val result = runBlocking {
                provider.submit(
                    ImageGenRequest(
                        prompt = "test",
                        model = AgnesImageProvider.DEFAULT_MODEL_ID,
                        size = "1:1",
                        config = agnesConfig("sk_a,sk_b", server.url("/").toString().trimEnd('/')),
                    ),
                )
            }

            assertEquals(1, result.images.size)
            assertEquals("AAA", result.images[0].base64)

            // Verificar la solicitud dos veces:La primera autorización es sk_a,La segunda vez es sk_b(Cortar la llave con éxito)
            assertEquals(2, server.requestCount)
            val firstReq = server.takeRequest()
            val secondReq = server.takeRequest()
            assertEquals("Bearer sk_a", firstReq.getHeader("Authorization"))
            assertEquals("Bearer sk_b", secondReq.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `single key 429 does not switch and throws rate limited`() {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))

            val provider = AgnesImageProvider(
                OkHttpClient(),
                keyRotationFactory = deterministicKeyRotationFactory(),
            )
            try {
                runBlocking {
                    provider.submit(
                        ImageGenRequest(
                            prompt = "test",
                            model = AgnesImageProvider.DEFAULT_MODEL_ID,
                            size = "1:1",
                            config = agnesConfig("sk_single", server.url("/").toString().trimEnd('/')),
                        ),
                    )
                }
                fail("expected IllegalStateException (RATE_LIMITED)")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("429"))
            }
            // Una sola llave no corta key,Enviar una solicitud solo una vez
            assertEquals(1, server.requestCount)
            assertEquals("Bearer sk_single", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `multi key both 429 throws rate limited after switch`() {
        val server = MockWebServer()
        try {
            // Ambas claves son 429: el reintento después de cortar la clave aún persiste 429
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(429))

            val provider = AgnesImageProvider(
                OkHttpClient(),
                keyRotationFactory = deterministicKeyRotationFactory(),
            )
            try {
                runBlocking {
                    provider.submit(
                        ImageGenRequest(
                            prompt = "test",
                            model = AgnesImageProvider.DEFAULT_MODEL_ID,
                            size = "1:1",
                            config = agnesConfig("sk_a,sk_b", server.url("/").toString().trimEnd('/')),
                        ),
                    )
                }
                fail("expected IllegalStateException (RATE_LIMITED)")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("429"))
            }
            // Hizo dos solicitudes(Primer reintento de sk_a + cortar sk_b)
            assertEquals(2, server.requestCount)
            assertEquals("Bearer sk_a", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer sk_b", server.takeRequest().getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
