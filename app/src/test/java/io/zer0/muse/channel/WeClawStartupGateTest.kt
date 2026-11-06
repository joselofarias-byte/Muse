package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ClawBot Control de acceso de inicio de sondeo largo + determinación de inicio y parada después de cambios de canal.
 *
 * El antiguo proceso de implementación comienza incondicionalmente. restart(),Cuando no hay canal, el registro se escribirá en el suelo y se vaciará en un bucle durante 30 segundos. Después de reparar
 * Dos cosas deben ser ciertas al mismo tiempo.:Cuando no hay canal, no puedo permitírmelo ni una sola vez.,Y "desactivar y luego habilitar" todavía recibe el mensaje ——
 * Este último se basa en [weClawPollAction] Sólo actuar cuando el estado realmente cambie.(en lugar de una sola vez decision).
 */
class WeClawStartupGateTest {

    private fun weClaw(id: String, enabled: Boolean = true, secret: String = "secret") = ChannelConfig(
        id = id,
        platform = ChannelPlatform.WECLAW,
        enabled = enabled,
        appSecret = secret,
    )

    @Test
    fun `no channel means the poll loop is not started`() {
        assertFalse(weClawChannelAvailable(emptyList()))
        assertEquals(WeClawPollAction.NONE, weClawPollAction(available = false, running = false))
    }

    @Test
    fun `enabled weclaw channel starts the poll loop`() {
        assertTrue(weClawChannelAvailable(listOf(weClaw("c1"))))
        assertEquals(WeClawPollAction.START, weClawPollAction(available = true, running = false))
    }

    @Test
    fun `disabled channel or channel without secret does not count`() {
        assertFalse(weClawChannelAvailable(listOf(weClaw("c1", enabled = false))))
        assertFalse(weClawChannelAvailable(listOf(weClaw("c1", secret = "  "))))
        assertFalse(
            weClawChannelAvailable(
                listOf(ChannelConfig(id = "c2", platform = ChannelPlatform.TELEGRAM, enabled = true)),
            ),
        )
        // Los canales en otras plataformas en la misma configuración no pueden hacer bucle en WECLAW
        assertTrue(
            weClawChannelAvailable(
                listOf(
                    ChannelConfig(id = "c2", platform = ChannelPlatform.TELEGRAM, enabled = true),
                    weClaw("c1"),
                ),
            ),
        )
    }

    @Test
    fun `same availability never triggers anything twice`() {
        // Funcionando y todavía tiene canales.:No puedo reiniciar(De lo contrario, se reconstruirá el sondeo largo cada vez que se lea la configuración.)
        assertEquals(WeClawPollAction.NONE, weClawPollAction(available = true, running = true))
        // Se ha detenido y todavía no hay canal.:No puedes iniciar el ciclo nuevamente debido a tu propia suscripción.(Anti-autodisparo)
        assertEquals(WeClawPollAction.NONE, weClawPollAction(available = false, running = false))
    }

    @Test
    fun `disabling stops and re-enabling starts again`() {
        // desactivar → detenerse
        assertEquals(WeClawPollAction.STOP, weClawPollAction(available = false, running = true))
        // Volver a habilitar → de(Aún debes recibir mensajes después de desactivar y reactivar)
        assertEquals(WeClawPollAction.START, weClawPollAction(available = true, running = false))
    }
}
