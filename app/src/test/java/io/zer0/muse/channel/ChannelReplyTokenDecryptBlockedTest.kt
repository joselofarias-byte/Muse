package io.zer0.muse.channel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-CHANNEL-F7: El descifrado del token de contexto de devolución falla y debe pasar al estado final BLOCKED,No se permiten reintentos ilimitados.
 *
 * La implementación original no puede descifrar y regresa Unavailable → HandleResult.Retry → retryDispatch Sin límite superior
 * (Limitado a 5 minutos,nunca termina),Ocupar el ritmo del despachador compartido y vaciar los registros;con Agent checkpoint
 * El calibre existente de ilegible para BLOQUEADO es inconsistente.
 */
class ChannelReplyTokenDecryptBlockedTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/channel/ChannelAutoReply.kt").readText()

    @Test
    fun `decrypt failure maps to blocked not retry`() {
        val src = source()
        assertTrue(
            "Las ramas que no puedan descifrarse deben redirigirse HandleResult.Blocked",
            src.contains("is ContextTokenOverride.DecryptFailed -> HandleResult.Blocked"),
        )
        assertTrue(
            "No disponible ya no debería asignarse a Retry",
            !src.contains("ContextTokenOverride.Unavailable -> HandleResult.Retry"),
        )
    }

    @Test
    fun `resolve distinguishes absent from decrypt failure`() {
        val src = source()
        assertTrue(
            "Ir sin ficha Absent(lanzamiento normal)",
            src.contains("ContextTokenOverride.Absent"),
        )
        assertTrue(
            "Retorno después de detectar la excepción de descifrado DecryptFailed",
            src.contains("return ContextTokenOverride.DecryptFailed(\"context_token_unreadable\")"),
        )
    }
}
