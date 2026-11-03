package io.zer0.muse.ui

import io.zer0.ai.RefImageUrlValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsrfGuardTest {

    @Test
    fun blocksLoopbackPrivateAndLinkLocalAddresses() {
        assertTrue(SsrfGuard.isBlocked("http://127.0.0.1/"))
        assertTrue(SsrfGuard.isBlocked("http://10.0.0.1/"))
        assertTrue(SsrfGuard.isBlocked("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun rejectsNonHttpUrlsAndMalformedUrls() {
        assertTrue(SsrfGuard.isBlocked("about:blank"))
        assertTrue(SsrfGuard.isBlocked("file:///android_asset/test.html"))
        assertTrue(SsrfGuard.isBlocked("not a URL"))
    }

    @Test
    fun allowsKnownPublicLiteralAddress() {
        assertFalse(SsrfGuard.isBlocked("http://8.8.8.8/"))
    }

    // ── G4 suplemento:IPv4-mapped / ULA / hexadecimal IP ─────────────────────────────

    @Test
    fun blocksIpv4MappedPrivateAddresses() {
        // ::ffff:10.0.0.1 / ::ffff:127.0.0.1 / ::ffff:192.168.1.1 / ::ffff:169.254.1.1
        assertTrue(SsrfGuard.isBlocked("http://[::ffff:10.0.0.1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[::ffff:127.0.0.1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[::ffff:192.168.1.1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[::ffff:169.254.1.1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[::ffff:0.0.0.0]/"))
    }

    @Test
    fun allowsIpv4MappedPublicAddress() {
        assertFalse(SsrfGuard.isBlocked("http://[::ffff:8.8.8.8]/"))
    }

    @Test
    fun blocksUlaFec0AndFc00() {
        // fc00::/7 ULA(Java isSiteLocalAddress solo cubrir fec0::/10,No cubierto fc00::/7)
        assertTrue(SsrfGuard.isBlocked("http://[fc00::1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[fd12:3456::1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[fec0::1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[fe80::1]/")) // link-local
    }

    @Test
    fun allowsPublicIpv6Literal() {
        assertFalse(SsrfGuard.isBlocked("http://[2001:4860:4860::8888]/")) // Google DNS
        assertFalse(SsrfGuard.isBlocked("http://[2606:4700:4700::1111]/")) // Cloudflare DNS
    }

    @Test
    fun blocksIpv6LoopbackAndMulticast() {
        assertTrue(SsrfGuard.isBlocked("http://[::1]/"))
        assertTrue(SsrfGuard.isBlocked("http://[ff02::1]/")) // multicast
        assertTrue(SsrfGuard.isBlocked("http://[::]/")) // any-local
    }

    @Test
    fun blocksHexIntegerIpAndDecimalIntegerIp() {
        // Java InetAddress No analizar hexadecimal IP(0x7f000001)→ El análisis falló,rechazo conservador
        assertTrue(SsrfGuard.isBlocked("http://0x7f000001/"))
        // InetAddress analiza el literal IP entero como 127.0.0.1(Equivalente decimal con puntos 2130706433)
        assertTrue(SsrfGuard.isBlocked("http://2130706433/"))
        // Nota:Java No "0177.0.0.1" cuando octal,se resuelve 177.0.0.1(Red pública),liberar
        assertFalse(SsrfGuard.isBlocked("http://0177.0.0.1/"))
    }

    @Test
    fun blocksOtherPrivateIpv4Ranges() {
        assertTrue(SsrfGuard.isBlocked("http://172.16.0.1/"))
        assertTrue(SsrfGuard.isBlocked("http://172.31.255.254/"))
        assertTrue(SsrfGuard.isBlocked("http://192.168.0.1/"))
        assertTrue(SsrfGuard.isBlocked("http://10.255.255.255/"))
        assertTrue(SsrfGuard.isBlocked("http://0.0.0.0/"))
        assertTrue(SsrfGuard.isBlocked("http://239.1.1.1/")) // multicast 224-255
    }

    @Test
    fun allowsOtherPublicIpv4() {
        assertFalse(SsrfGuard.isBlocked("http://172.33.0.1/"))
        assertFalse(SsrfGuard.isBlocked("http://172.15.0.1/"))
        assertFalse(SsrfGuard.isBlocked("http://1.1.1.1/"))
        assertFalse(SsrfGuard.isBlocked("http://9.9.9.9/"))
    }

    @Test
    fun blocksLocalhostHostnameVariants() {
        // localhost analizado como 127.0.0.1 / ::1 → rechazar
        assertTrue(SsrfGuard.isBlocked("http://localhost/"))
        // subdominio *.localhost No se puede analizar(o resolver 127.0.0.1,Depende del sistema)→ rechazo conservador
        assertTrue(SsrfGuard.isBlocked("http://svc.localhost/"))
        assertTrue(SsrfGuard.isBlocked("http://[::1]/"))
    }

    @Test
    fun refImageUrlValidatorAllowsDataAndFileSchemes() {
        val validator: RefImageUrlValidator = SsrfGuard.refImageUrlValidator
        // data:/file: Liberación constante(No se requiere internet)
        assertTrue(validator.isAllowed("data:image/png;base64,AAA="))
        assertTrue(validator.isAllowed("file:///storage/emulated/0/Pic/1.png"))
        // http(s) Determinado por isBlocked
        assertFalse(validator.isAllowed("http://127.0.0.1/x.png"))
        assertFalse(validator.isAllowed("http://169.254.169.254/latest/meta-data/"))
        assertFalse(validator.isAllowed("http://[::ffff:192.168.1.1]/"))
        assertTrue(validator.isAllowed("http://8.8.8.8/x.png"))
    }
}
