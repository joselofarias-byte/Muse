package io.zer0.ai.core

import io.zer0.ai.util.KeyRoulette
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.6.1: [ProviderHttpSupport.effectiveApiKey] Solicitar devoluciones de verificación de seguridad del encabezado.
 *
 * fondo(Prueba real del usuario):Poner el nombre del proveedor desde la interfaz china/Al copiar y pegar el título junto con la clave API,
 * Key Se mezclarán caracteres chinos y otros caracteres no ASCII.,OkHttp Lanzar antes de enviar la solicitud
 * `Unexpected char 0x767e at 7 in Authorization value`,La ubicación del error no está en contacto con la causa real.,
 * Es difícil para los usuarios comprobarse a sí mismos. Ahora bloquee temprano y proporcione una razón legible.
 *
 * Portada:
 *  - Tecla única: incluyendo no ASCII → tirar [ProviderException] Y el mensaje contiene posición y caracteres.
 *  - Tecla única: pura ASCII → Regresar normalmente
 *  - Múltiples claves: una de ellas contiene no ASCII → también interceptar
 *  - Tecla vacía: soltar(Procesado por lógica de capa superior como enableMissingApiKey)
 */
class ProviderHttpSupportApiKeyHeaderTest {

    /** subclase mínima:Solo la EffectiveApiKey protegida está expuesta para llamadas de prueba. */
    private class Probe(config: ProviderConfig) : ProviderHttpSupport(config) {
        override val id: String get() = config.id
        override val displayName: String get() = "probe"
        override fun streamChat(request: ChatRequest): Flow<ChatStreamEvent> = emptyFlow()
        fun apiKeyForTest(): String = effectiveApiKey()
    }

    @Before
    fun setUp() {
        Logger.enabled = false
    }

    @org.junit.After
    fun tearDown() {
        Logger.enabled = true
    }

    private fun config(apiKey: String, id: String = "probe-provider") = ProviderConfig(
        id = id,
        displayName = "probe",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
    )

    @Test
    fun `plain ascii key passes through trimmed`() {
        val probe = Probe(config("  sk-abc123XYZ  "))
        assertEquals("sk-abc123XYZ", probe.apiKeyForTest())
    }

    @Test
    fun `non-ascii key is rejected with readable message`() {
        // El primer carácter es chino.“cien”(U+767E),Reproducir la forma típica de un usuario pegando un título.
        val probe = Probe(config("ciensk-abc"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue("debe ser arrojado ProviderException", error is ProviderException)
        val message = (error as ProviderException).message.orEmpty()
        assertTrue("Cabe señalar posiciones de carácter ilegal", message.contains("No. 1"))
        assertTrue("Se debe dar una razón legible", message.contains("ASCII"))
        assertTrue("Debe llevar la categoría InvalidRequest", error.providerError is ProviderError.InvalidRequest)
    }

    @Test
    fun `non-ascii in the middle is located`() {
        val probe = Probe(config("sk-chino-key"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue(error is ProviderException)
        val message = (error as ProviderException).message.orEmpty()
        assertTrue("Cabe señalar que el 4to.", message.contains("No. 4"))
    }

    @Test
    fun `whitespace inside key is rejected`() {
        // espacio(0x20)No dentro de 0x21..0x7E —— Pasta a menudo arrastrada
        val probe = Probe(config("sk-abc def"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue(error is ProviderException)
    }

    @Test
    fun `multi key validates the selected entry`() {
        // En un escenario de múltiples claves, la verificación es**El seleccionado para esta rotación. key**, en lugar de la lista completa.
        // Compensaciones de diseño: no se puede hacer que todo el proveedor no esté disponible solo porque hay una clave incorrecta en la lista;
        // Una vez que se selecciona una clave incorrecta para su rotación, aún recibirá un error legible antes de enviarla.
        // Bloqueado con toda la lista mala aquí“Seleccionar y verificar”semántica.
        val probe = Probe(config("cienbad,enbad"))
        val error = runCatching { probe.apiKeyForTest() }.exceptionOrNull()
        assertTrue("Cuando la clave seleccionada contiene caracteres que no son ASCII, debe interceptarse.", error is ProviderException)
    }

    @Test
    fun `multi key with all-good entries passes`() {
        val probe = Probe(config("sk-good1,sk-good2"))
        val key = probe.apiKeyForTest()
        assertTrue("debe devolver uno de los legales key", key == "sk-good1" || key == "sk-good2")
    }

    @Test
    fun `empty key is allowed through`() {
        // Las claves vacías se manejan mediante la lógica de respaldo/allowMissingApiKey,No debería ser arrojado aquí.
        val probe = Probe(config(""))
        assertEquals("", probe.apiKeyForTest())
    }

    @Test
    fun `all ascii visible range is accepted`() {
        // Anular los límites 0x21..0x7E
        val probe = Probe(config("!" + "~" + "sk-0123456789"))
        assertEquals("!~sk-0123456789", probe.apiKeyForTest())
    }

    @Test
    fun `key rotation helper stays independent of header validation`() {
        // ProviderKeyRotation Es una clase reutilizable que no hereda ProviderHttpSupport.,
        // Este lote solo agrega verificación del encabezado de solicitud en el lado de ProviderHttpSupport. —— registrar esta diferencia,
        // Evite la idea errónea de que dos caminos se comportan de manera idéntica.
        val rotation = ProviderKeyRotation(config("sk-plain"), keyRoulette = KeyRoulette())
        assertEquals("sk-plain", rotation.effectiveApiKey())
    }
}
