package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginPackageLoader
import io.zer0.muse.data.plugin.PluginSecurityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de regresión de contratos cruzados del paquete de muestra de almacenamiento (v2.0.0 bloqueo de accidentes del mercado).
 *
 * Las dos muestras bloquean respectivamente un tipo de secuencia de bytes:
 *  · official catalog muestra (color-toolkit 1.0.0, el producto real del catálogo en línea, producido por la versión antigua de la herramienta de firma)——
 *    Los paquetes de stock siempre deben ser verificados por la App;App Una vez que el comportamiento de serialización del manifiesto lateral cambia (como la codificación de campos predeterminados vacíos),
 *    Esta prueba falla inmediatamente;
 *  · regenerated tool Muestra (versión reparada `tools/market-signer/sign.py` salida, incluyendo contributes
 *    Normalización omitida con tarjetas de herramientas vacías)——Bloquee la ruta de reconstrucción de "La herramienta de firma es literalmente consistente con la serialización de la aplicación".
 *
 * Regenerar muestras regeneradas: usar `tools/market-signer/sign.py pack` toma una foto contributes
 * (configuration (Elemento 1) Reemplace el archivo de recursos con el paquete toolCards vacío y mantenga el nombre del archivo sin cambios.
 */
class MarketSamplePackageTest {

    // ── Muestra 1: Paquete real del catálogo oficial online (compatible con stock) ──

    @Test
    fun `official catalog sample passes app signature gate`() {
        val loaded = loadSample("color-toolkit-1.0.0.muse-plugin")

        // Sin raíz de confianza local: debe pasar la verificación criptográfica (v2.0.0 Es en este paso que informo“La firma no coincide con el contenido”).
        val untrusted = PluginSecurityGate.review(loaded)
        assertEquals(
            "El paquete de muestra oficial no pudo verificar la firma: ${untrusted.reason}",
            PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
            untrusted.signature.status,
        )
        assertTrue(untrusted.allowed)

        // Puede instalarse después de acceder a la clave pública de su emisor. El resumen del contenido debe ser coherente con el manifiestoSha256 registrado en el directorio en línea.
        val envelope = checkNotNull(loaded.manifest.signature)
        val trusted = PluginSecurityGate.review(
            loaded,
            trustedPublisherKeys = mapOf(envelope.publisherId to envelope.publicKey),
        )
        assertTrue("El paquete de muestra oficial debería ser instalable.: ${trusted.reason}", trusted.isInstallable)
        assertEquals("museai", trusted.preview.publisherId)
        assertEquals(
            "El resumen del contenido debe ser coherente con el manifiesto del directorio en líneaSha256",
            "1601d031755e91f920d80a25263abebca024e633444ef0c0f33bff47f33f23ec",
            trusted.preview.contentSha256,
        )
    }

    // ── Muestra 2: Salida de la herramienta de firma reparada (reconstruida consistentemente) ──

    @Test
    fun `regenerated signing tool sample passes app signature gate`() {
        val loaded = loadSample("signer-selftest-b-1.0.0.muse-plugin")

        val untrusted = PluginSecurityGate.review(loaded)
        assertEquals(
            "La herramienta de firma no pudo reconstruir la verificación de la firma del paquete: ${untrusted.reason}",
            PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
            untrusted.signature.status,
        )

        val envelope = checkNotNull(loaded.manifest.signature)
        val trusted = PluginSecurityGate.review(
            loaded,
            trustedPublisherKeys = mapOf(envelope.publisherId to envelope.publicKey),
        )
        assertTrue(trusted.isInstallable)

        // contributes La normalización (orden de campos + llenado de valores predeterminados) también está bloqueada.
        val contributes = loaded.manifest.contributes
        assertNotNull("Las muestras deben declarar contributes", contributes)
        assertEquals(listOf("token"), checkNotNull(contributes).configuration.map { it.key })

        // Las tarjetas de herramientas vacías no se ingresan en secuencias de bytes:App Lo que se lee es la asignación vacía predeterminada.
        assertTrue(loaded.manifest.toolCards.isEmpty())
    }

    private fun loadSample(name: String): PluginPackageLoader.LoadedPluginPackage {
        val stream = checkNotNull(javaClass.getResourceAsStream("/market/$name")) {
            "Muestras de prueba faltantes /market/$name"
        }
        val bytes = stream.use { it.readBytes() }
        val loaded = PluginPackageLoader.loadFromZip(bytes)
        val ok = loaded as? PluginPackageLoader.Result.Ok
        checkNotNull(ok) { "El paquete de muestra no se pudo cargar: $name — $loaded" }
        return ok.package_
    }
}
