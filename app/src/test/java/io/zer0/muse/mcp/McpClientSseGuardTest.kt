package io.zer0.muse.mcp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SSE Prueba de protección de desconexión.
 *
 * Concéntrese en cubrir el camino real del accidente:Después de que el oyente cierra el canal del punto final,Los que esperan se lo merecen. null,
 * En lugar de lanzar ClosedReceiveChannelException.
 */
class McpClientSseGuardTest {

    @Test
    fun `closed endpoint channel returns null`() = runTest {
        val channel = Channel<String>(Channel.CONFLATED)
        channel.close()

        assertNull(awaitSseEndpoint(channel, timeoutMs = 1_000L))
    }

    @Test
    fun `endpoint event is returned`() = runTest {
        val channel = Channel<String>(Channel.CONFLATED)
        channel.trySend("endpoint")

        assertEquals("endpoint", awaitSseEndpoint(channel, timeoutMs = 1_000L))
        channel.close()
    }
}
