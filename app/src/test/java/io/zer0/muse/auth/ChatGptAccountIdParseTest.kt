package io.zer0.muse.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * v2.6.2(Codex Solución de inicio de sesión): JWT claim Analizar pruebas de regresión.
 *
 * Bloqueado dos errores:
 *  1. `chatgpt_account_id` anidado en `https://api.openai.com/auth` Debajo del espacio de nombres,
 *     No es la cima —— La implementación anterior solo leía el nivel superior y siempre era nula, lo que provocaba que el encabezado ChatGPT-Account-ID nunca se escribiera.
 *  2. base64url payload Sin relleno, la implementación anterior no está rellena,Android Base64 La decodificación falló.
 */
class ChatGptAccountIdParseTest {

    /** Cree un JWT falso con la carga útil JSON dada (el segmento de firma es un marcador de posición, el análisis no verifica la firma). */
    private fun jwtOf(payloadJson: String): String {
        val header = """{"alg":"RS256","typ":"JWT"}"""
        fun b64(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray(Charsets.UTF_8))
        return "${b64(header)}.${b64(payloadJson)}.fakesignature"
    }

    @Test
    fun `account id is read from nested auth namespace`() {
        val jwt = jwtOf(
            """{"sub":"u1","https://api.openai.com/auth":{"chatgpt_account_id":"acct-123","chatgpt_plan_type":"plus"}}""",
        )
        assertEquals("acct-123", parseChatGptAccountId(jwt))
    }

    @Test
    fun `top level claim is still honored as fallback`() {
        val jwt = jwtOf("""{"chatgpt_account_id":"acct-top"}""")
        assertEquals("acct-top", parseChatGptAccountId(jwt))
    }

    @Test
    fun `nested wins over top level`() {
        val jwt = jwtOf(
            """{"chatgpt_account_id":"top","https://api.openai.com/auth":{"chatgpt_account_id":"nested"}}""",
        )
        assertEquals("nested", parseChatGptAccountId(jwt))
    }

    @Test
    fun `unpadded base64url payload decodes`() {
        // Construya una carga útil cuya longitud no sea múltiplo de 4 para garantizar que el relleno surta efecto
        val jwt = jwtOf("""{"https://api.openai.com/auth":{"chatgpt_account_id":"x"}}""")
        assertEquals("x", parseChatGptAccountId(jwt))
    }

    @Test
    fun `missing claim returns null`() {
        assertNull(parseChatGptAccountId(jwtOf("""{"sub":"u1"}""")))
    }

    @Test
    fun `malformed token returns null`() {
        assertNull(parseChatGptAccountId("not-a-jwt"))
        assertNull(parseChatGptAccountId(""))
        assertNull(parseChatGptAccountId(null))
    }

    @Test
    fun `blank account id treated as absent`() {
        val jwt = jwtOf("""{"https://api.openai.com/auth":{"chatgpt_account_id":"  "}}""")
        assertNull(parseChatGptAccountId(jwt))
    }
}
