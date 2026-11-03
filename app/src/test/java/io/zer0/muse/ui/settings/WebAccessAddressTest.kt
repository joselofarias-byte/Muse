package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas de montaje para direcciones de acceso web.
 *
 * La línea de "dirección" y el código QR de la página solo consumen [resolveWebAccessAddress] el resultado de
 * Entonces, cada afirmación aquí corresponde a una copia que los usuarios pueden ver: ya sea una dirección completa o un mensaje de "IP no obtenida".
 */
class WebAccessAddressTest {

    @Test
    fun `ip and port become a plain http address`() {
        assertEquals("http://192.168.1.23:8080", buildWebAccessUrl("192.168.1.23", 8080))
    }

    @Test
    fun `https config switches the scheme`() {
        assertEquals("https://192.168.1.23:8765", buildWebAccessUrl("192.168.1.23", 8765, https = true))
    }

    @Test
    fun `missing ip yields null so the caller shows the hint`() {
        assertNull(buildWebAccessUrl(null, 8080))
        assertNull(buildWebAccessUrl("   ", 8080))
    }

    /** El acceso LAN está cerrado: la dirección se retrocede y se marca como \"Sólo esta máquina\" y, en consecuencia, la página muestra \"Sólo esta máquina puede acceder\". */
    @Test
    fun `lan off resolves to loopback and marks local only`() {
        val address = resolveWebAccessAddress(allowLan = false, lanIp = "192.168.1.23", port = 8765)
        assertEquals("http://127.0.0.1:8765", address.url)
        assertTrue(address.localOnly)
    }

    /** El acceso LAN está habilitado pero no se obtiene la IP: la dirección es nula (tome la rama del indicador) y ya no afirma ser solo la máquina local. */
    @Test
    fun `lan on without ip yields no url`() {
        val address = resolveWebAccessAddress(allowLan = true, lanIp = null, port = 8765)
        assertNull(address.url)
        assertEquals(false, address.localOnly)
    }

    @Test
    fun `lan on with ip resolves to the lan address`() {
        val address = resolveWebAccessAddress(allowLan = true, lanIp = "192.168.1.23", port = 8765)
        assertEquals("http://192.168.1.23:8765", address.url)
        assertEquals(false, address.localOnly)
    }

    private fun candidate(
        name: String,
        address: String,
        siteLocal: Boolean = true,
        loopback: Boolean = false,
    ) = LanIpCandidate(interfaceName = name, address = address, siteLocal = siteLocal, loopback = loopback)

    /** Cuando hay varias tarjetas de red, se le da prioridad a la conexión inalámbrica: incluso si wlan está más adelante en el orden de enumeración. */
    @Test
    fun `wlan is preferred over later interfaces`() {
        val picked = pickLanIpAddress(
            listOf(
                candidate("docker0", "172.17.0.1"),
                candidate("eth0", "10.0.0.8"),
                candidate("wlan0", "192.168.1.23"),
            ),
        )
        assertEquals("192.168.1.23", picked)
    }

    /** Dirección de bucle invertido y segmento de red no privada (como red pública/enlace local) no participa en la selección. */
    @Test
    fun `loopback and non site local addresses are skipped`() {
        val picked = pickLanIpAddress(
            listOf(
                candidate("lo", "127.0.0.1", siteLocal = false, loopback = true),
                candidate("rmnet0", "100.64.1.2", siteLocal = false),
                candidate("wlan0", "192.168.1.23"),
            ),
        )
        assertEquals("192.168.1.23", picked)
    }

    /** Si todas las direcciones no están disponibles, se devolverá nulo y aparecerá el mensaje \"IP de LAN no obtenida\" en la página. */
    @Test
    fun `no usable candidate yields null`() {
        assertNull(pickLanIpAddress(emptyList()))
        assertNull(pickLanIpAddress(listOf(candidate("lo", "127.0.0.1", siteLocal = false, loopback = true))))
    }
}
