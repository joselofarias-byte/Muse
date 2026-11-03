package io.zer0.muse.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0: Ed25519 prueba de corrección — Utilice la documentación oficial de la plataforma abierta QQ para probar vectores.
 *
 * Portada:seed Derivar clave pública/certeza de firma(Consistente con el vector oficial) / Verificación de firma aprobada/manipulación rechazada.
 */
class Ed25519Test {

    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** DEMOSTRACIÓN oficial: la semilla se agrega repetidamente a 32 bytes para derivar la clave pública especificada.;La parte de la clave pública es coherente con el vector oficial. */
    @Test
    fun `seed derives expected public key from official demo`() {
        val secret = "naOC0ocQE3shWLAfffVLB1rhYPG7"
        val seed = Ed25519.seedFromSecret(secret)
        assertEquals("naOC0ocQE3shWLAfffVLB1rhYPG7naOC", String(seed, Charsets.UTF_8))
        val publicKey = Ed25519.seedToPublicKey(seed)
        val expected = bytes(
            215, 195, 98, 254, 120, 174, 248, 31, 242, 50, 135, 180, 147, 98, 139, 93,
            176, 42, 60, 79, 227, 11, 33, 94, 77, 25, 96, 155, 93, 118, 103, 58,
        )
        assertArrayEquals(expected, publicKey)
    }

    /**
     * vector de firma — con Node.js crypto(Implementación autorizada Ed25519 del mismo modelo que el documento oficial.)Generación de referencia;
     * Igual que la semilla y el mismo mensaje de salida byte a byte.
     *
     * Nota:La salida DEMO de la firma de la página "Seguridad y autorización" del documento oficial no se puede reproducir después de la enumeración de 96 variantes de mensajes.,
     * Juzgado como un valor de indicación del documento.;Aquí prevalecerá el vector de referencia de implementación del nodo.
     */
    @Test
    fun `signature matches node js reference vector`() {
        val secret = "naOC0ocQE3shWLAfffVLB1rhYPG7"
        val seed = Ed25519.seedFromSecret(secret)
        val message = "1725442341{ \"op\": 0,\"d\": {}, \"t\": \"GATEWAY_EVENT_NAME\"}"
            .toByteArray(Charsets.UTF_8)
        val signature = Ed25519.sign(seed, message)
        val expected = hexToBytes(
            "2eb9983ebb8bb209e78fd095942f58e442656656e7975d01e64f9023a84b7c96" +
                "4290fdd40e5500c33867ccfe9563b7e0b6bac0e1d42c13e787b304fd51f71102",
        )
        assertArrayEquals(expected, signature)
        assertTrue(Ed25519.verify(Ed25519.seedToPublicKey(seed), message, signature))
        assertFalse(
            Ed25519.verify(Ed25519.seedToPublicKey(seed), "tampered".toByteArray(Charsets.UTF_8), signature),
        )
    }

    @Test
    fun `sign and verify roundtrip`() {
        val seed = Ed25519.seedFromSecret("test-secret-0123456789abcdef")
        val publicKey = Ed25519.seedToPublicKey(seed)
        val message = "hello ed25519".toByteArray(Charsets.UTF_8)
        val signature = Ed25519.sign(seed, message)
        assertTrue(Ed25519.verify(publicKey, message, signature))
    }

    @Test
    fun `verify rejects tampered message`() {
        val seed = Ed25519.seedFromSecret("test-secret-0123456789abcdef")
        val publicKey = Ed25519.seedToPublicKey(seed)
        val signature = Ed25519.sign(seed, "authentic".toByteArray(Charsets.UTF_8))
        assertFalse(Ed25519.verify(publicKey, "tampered".toByteArray(Charsets.UTF_8), signature))
    }

    @Test
    fun `verify rejects truncated signature`() {
        val seed = Ed25519.seedFromSecret("test-secret-0123456789abcdef")
        val publicKey = Ed25519.seedToPublicKey(seed)
        val message = "hello".toByteArray(Charsets.UTF_8)
        val signature = Ed25519.sign(seed, message)
        assertFalse(Ed25519.verify(publicKey, message, signature.copyOf(63)))
    }
}
