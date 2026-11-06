package io.zer0.muse.asr

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.WebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3: DashScope ASR Prueba de orientación de desconexión y reconexión.
 *
 * Portada:
 *  - Secuencias de retroceso exponencial y límites superiores(DashScopeReconnectPolicy);
 *  - Sentencia de acción de desconexión(Ignorar el estado final/Parada/dispose,Listening Reconectar,Rebaja por agotamiento);
 *  - DashScopeSession Semántica de notificación de desconexión(onFailure/onClosing Notificación,onClosed sin previo aviso);
 *  - Retírate, espera, no tragues. CancellationException(La corrutina en realidad termina después de la cancelación.);
 *  - Los usuarios que hayan agotado sus reconexiones verán la copia de degradación.
 *
 * correr: ./gradlew :app:testDebugUnitTest --tests "*DashScopeAsrReconnectTest*"
 */
class DashScopeAsrReconnectTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── estrategia de retroceso ─────────────────────────────────────────────────────────

    @Test
    fun `backoff doubles exponentially and caps at max delay`() {
        assertEquals(DashScopeReconnectPolicy.BASE_DELAY_MS, DashScopeReconnectPolicy.delayForAttempt(0))
        assertEquals(1_000L, DashScopeReconnectPolicy.delayForAttempt(1))
        assertEquals(2_000L, DashScopeReconnectPolicy.delayForAttempt(2))
        assertEquals(4_000L, DashScopeReconnectPolicy.delayForAttempt(3))
        assertEquals(8_000L, DashScopeReconnectPolicy.delayForAttempt(4))
        // Limitado después de exceder el límite superior,Y el gran intento no se volverá negativo debido al desbordamiento de turnos.
        assertEquals(DashScopeReconnectPolicy.MAX_DELAY_MS, DashScopeReconnectPolicy.delayForAttempt(10))
        assertTrue(DashScopeReconnectPolicy.delayForAttempt(Int.MAX_VALUE) > 0)
        // numero negativo/Intento ilegal de retroceder al primer retroceso.
        assertEquals(DashScopeReconnectPolicy.BASE_DELAY_MS, DashScopeReconnectPolicy.delayForAttempt(-3))
    }

    @Test
    fun `exhausted message is user visible degradation with manual retry hint`() {
        val message = DashScopeReconnectPolicy.EXHAUSTED_MESSAGE
        assertTrue(message.contains("Se volvió a conectar automáticamente 3 veces"))
        assertTrue(message.contains("retenido"))
        assertTrue(message.contains("comience a grabar nuevamente"))
    }

    // ── Sentencia de acción de desconexión ─────────────────────────────────────────────────────

    @Test
    fun `terminal and stopping states never trigger reconnect`() {
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Idle, isDisposed = false, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Error, isDisposed = false, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Stopping, isDisposed = false, reconnectAttempt = 0),
        )
        // Conexión por primera vez/La desconexión durante el establecimiento de la reconexión la maneja el propio establishmentConnection.,Evite el doble conteo
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Connecting, isDisposed = false, reconnectAttempt = 0),
        )
    }

    @Test
    fun `listening disconnect reconnects until attempts are exhausted`() {
        assertEquals(
            AsrDisconnectAction.RECONNECT,
            resolveAsrDisconnectAction(ASRStatus.Listening, isDisposed = false, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.RECONNECT,
            resolveAsrDisconnectAction(ASRStatus.Reconnecting, isDisposed = false, reconnectAttempt = 2),
        )
        assertEquals(
            AsrDisconnectAction.GIVE_UP,
            resolveAsrDisconnectAction(
                ASRStatus.Listening,
                isDisposed = false,
                reconnectAttempt = DashScopeReconnectPolicy.MAX_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `disposed controller never reconnects`() {
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Listening, isDisposed = true, reconnectAttempt = 0),
        )
        assertEquals(
            AsrDisconnectAction.IGNORE,
            resolveAsrDisconnectAction(ASRStatus.Reconnecting, isDisposed = true, reconnectAttempt = 1),
        )
    }

    // ── Notificación de desconexión de sesión ────────────────────────────────────────────────────

    @Test
    fun `session notifies disconnect on websocket failure`() = runBlocking {
        val session = DashScopeSession("task-1", json) { }
        var notified: String? = null
        session.onDisconnected = { notified = it }

        session.onFailure(mockk(relaxed = true), java.io.IOException("boom"), null)

        assertTrue("onFailure El motivo de la excepción debe llevarse", notified?.contains("boom") == true)
        // El canal esta cerrado:Espere a que el evento regrese inmediatamente. null(No se cuelga al intentar volver a conectarse)
        assertNull(session.waitForEvent("task-started", 100))
    }

    @Test
    fun `session notifies disconnect when server initiates close`() {
        val session = DashScopeSession("task-1", json) { }
        var notified: String? = null
        session.onDisconnected = { notified = it }
        val ws = mockk<WebSocket>(relaxed = true)

        session.onClosing(ws, 1001, "going away")

        assertTrue(notified?.contains("1001") == true)
        verify { ws.close(1001, "going away") }
    }

    @Test
    fun `local close does not notify disconnect`() {
        val session = DashScopeSession("task-1", json) { }
        var notified: String? = null
        session.onDisconnected = { notified = it }

        // locales close()/cleanupConnection solo disparador onClosed,Nunca autoactive la reconexión
        session.onClosed(mockk(relaxed = true), 1000, "session ended")

        assertNull(notified)
    }

    // ── Semántica de cancelación de retroceso ────────────────────────────────────────────────────

    @Test
    fun `backoff wait propagates cancellation without being swallowed`() = runBlocking {
        val job = launch { awaitReconnectBackoff(5) } // 8s,Tome la iniciativa de cancelar a continuación
        delay(50)
        job.cancel()
        job.join()

        assertTrue("La corrutina debe terminar después de la cancelación.(Retirarse y esperar sin tragar. CancellationException)", job.isCancelled)
    }
}
