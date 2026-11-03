package io.zer0.muse.ui.speech

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Phase 3 (Fiabilidad P1): Qwen Prueba de tiempo de espera de descarga de audio secundario.
 *
 * Qwen(DashScope Interfaz nativa)POST primero para sintetizar output.audio.url,OBTÉN nuevamente para descargar el audio.
 * Solucione el problema de que no hay tiempo de espera de rutina en las dos primeras descargas.,Chat reutilizado OkHttpClient readTimeout más largo 300s —— Audio URL
 * La corrutina sintética se bloquea durante mucho tiempo mientras está suspendida. Después de la reparación, utilice withTimeoutOrNull para averiguarlo.(Predeterminado 30s,Inyectable),
 * Esta prueba inyecta 300 ms + MockWebServer retrasa el cuerpo de respuesta de 10 segundos,Las llamadas de autenticación fallan rápidamente en lugar de esperar una respuesta.
 */
class CloudTtsServiceQwenTimeoutTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `qwen second audio download is bounded by timeout`() = runBlocking {
        val audioUrl = server.url("/slow-audio.mp3")
        // primera solicitud: Volver a la dirección de descarga secundaria
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"output":{"audio":{"url":"$audioUrl"}}}"""),
        )
        // segunda solicitud: El cuerpo de respuesta se retrasa 10 segundos antes de regresar,Mucho más de 300 ms de tiempo de espera
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("mp3-bytes")
                .setBodyDelay(10, TimeUnit.SECONDS),
        )

        val service = CloudTtsService(client, audioDownloadTimeoutMs = 300)
        val out = File.createTempFile("muse-tts", ".mp3")
        try {
            val start = System.nanoTime()
            val ok = service.synthesizeToFile(
                text = "Hola",
                engine = "qwen",
                apiKey = "test-key",
                model = "",
                voice = "",
                endpoint = server.url("/").toString().removeSuffix("/"),
                outputFile = out,
            )
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            assertFalse("El segundo tiempo de espera de descarga debería devolver un error,en lugar de obtener audio vacío/Esperando indefinidamente", ok)
            assertTrue(
                "El tiempo de espera debe realizarse en la parte inferior del bolsillo.(300ms)Regreso cercano,real ${elapsedMs}ms",
                elapsedMs < 5_000,
            )
        } finally {
            out.delete()
        }
    }
}
