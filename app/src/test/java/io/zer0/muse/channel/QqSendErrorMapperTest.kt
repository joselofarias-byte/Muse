package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ Fallo de transmisión legible.
 *
 * Estos códigos de error determinan lo que debe hacer el usuario:
 * El "tiempo de espera de respuesta pasiva" puede tener éxito la próxima vez, pero "el robot no está en el grupo" siempre fallará sin cambiar la configuración.
 * Si los dos se mezclan en la misma oración, el usuario sólo podrá seguir adivinando.
 */
class QqSendErrorMapperTest {

    @Test
    fun `Extraiga el código de error del cuerpo del error del prefijo HTTP`() {
        val raw = """HTTP 400: {"message":"Error en el mensaje no solicitado, Sin permiso","code":40034102}"""
        assertEquals(40034102, QqSendErrorMapper.extractCode(raw))
    }

    @Test
    fun `Los códigos de error también se pueden extraer de cuerpos de error JSON puros`() {
        // sin "HTTP xxx:" Al prefijar, no puede presionar dos puntos para cortar——Eso destruiría el propio JSON.
        val raw = """{"code":40034128,"message":"La respuesta pasiva ha expirado"}"""
        assertEquals(40034128, QqSendErrorMapper.extractCode(raw))
    }

    @Test
    fun `err_code Compatible con el campo`() {
        val raw = """HTTP 400: {"err_code":40034101}"""
        assertEquals(40034101, QqSendErrorMapper.extractCode(raw))
    }

    @Test
    fun `Devolver si no hay código de error null`() {
        assertNull(QqSendErrorMapper.extractCode("HTTP 500: gateway timeout"))
        assertNull(QqSendErrorMapper.extractCode(null))
        assertNull(QqSendErrorMapper.extractCode(""))
    }

    @Test
    fun `El tiempo de espera de respuesta pasiva significa que es demasiado tarde`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":40034128}", isGroup = true)
        assertTrue(text.contains("5 minutos"))
    }

    @Test
    fun `Si no está en el grupo, se le pedirá que se una primero al grupo.`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":40034101}", isGroup = true)
        assertTrue(text.contains("Únete al grupo"))
    }

    @Test
    fun `Si no hay permiso de mensaje activo, significa que el chat grupal no admite el envío activo.`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":40034102}", isGroup = true)
        assertTrue(text.contains("Toma la iniciativa"))
    }

    @Test
    fun `Transmita códigos de error desconocidos tal como están en lugar de inventar significados`() {
        val text = QqSendErrorMapper.describe("HTTP 400: {\"code\":999999,\"message\":\"weird\"}", isGroup = false)
        assertTrue(text.contains("999999") || text.contains("weird"))
    }

    @Test
    fun `Los errores de clase de configuración se marcan como fallas permanentes`() {
        assertTrue(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034101}"))
        assertTrue(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034102}"))
    }

    @Test
    fun `La caducidad de la ventana pasiva también falla permanentemente para este mensaje`() {
        // El tiempo solo pasará más tarde y no habrá posibilidad de ganar si intentas este mensaje nuevamente. —— Lo que se diferencia de la "clase de configuración permanente" es que
        // El siguiente mensaje nuevo aún se puede responder normalmente, por lo que solo se acepta el mensaje entrante actual y el canal no está condenado a muerte.
        assertTrue(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034128}"))
    }

    @Test
    fun `Aún puedes intentarlo nuevamente si no hay códigos de error o errores de control de frecuencia.`() {
        assertFalse(QqSendErrorMapper.isPermanent(null))
        assertFalse(QqSendErrorMapper.isPermanent("HTTP 500: gateway timeout"))
        assertFalse(QqSendErrorMapper.isPermanent("HTTP 400: {\"code\":40034105}"))
    }
}
