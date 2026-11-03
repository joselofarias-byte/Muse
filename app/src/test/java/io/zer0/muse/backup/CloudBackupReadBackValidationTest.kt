package io.zer0.muse.backup

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 (Fiabilidad P1): Verificación de lectura de respaldo en la nube — Estructura NDJSON descifrada/meta Prueba de calibración.
 *
 * Portada [validateCloudReadBackNdjson]:
 *  - legales NDJSON(meta + fila de datos)Pase;
 *  - carga vacía/No JSON/primera línea no meta/recuento no válido/Todo cuenta cero/Ninguna fila de datos rechazada explícitamente;
 *  - Copia de seguridad cifrada: Contraseña correcta pasada,Las contraseñas incorrectas y las faltantes dan razones claras del fracaso.
 */
class CloudBackupReadBackValidationTest {

    private val validMeta =
        """{"type":"meta","version":3,"sessions":1,"messages":2,"facts":0,"settings":0}"""
    private val validRecord = """{"type":"session","data":{"id":"s1"}}"""

    private fun ndjson(vararg lines: String): ByteArray = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `valid plaintext ndjson passes`() {
        assertNull(validateCloudReadBackNdjson(ndjson(validMeta, validRecord), ""))
    }

    @Test
    fun `plaintext downgrade is rejected when backup encryption was configured`() {
        val plaintext = ndjson(validMeta, validRecord)

        assertNotNull(validateCloudReadBackNdjsonWithPolicy(plaintext, "configured-password", passwordWasSet = false))
        assertNotNull(validateCloudReadBackNdjsonWithPolicy(plaintext, "", passwordWasSet = true))
    }

    @Test
    fun `empty payload is rejected`() {
        assertNotNull(validateCloudReadBackNdjson(ByteArray(0), ""))
    }

    @Test
    fun `non json first line is rejected`() {
        val error = validateCloudReadBackNdjson(ndjson("not-json", validRecord), "")
        assertNotNull(error)
        assertTrue("El motivo debe indicar que la primera línea es ilegal.: $error", error!!.contains("not a JSON object"))
    }

    @Test
    fun `non meta first line is rejected`() {
        val error = validateCloudReadBackNdjson(ndjson("""{"type":"session"}"""), "")
        assertNotNull(error)
        assertTrue("La razón debe indicar la falta de meta: $error", error!!.contains("expected 'meta'"))
    }

    @Test
    fun `meta with all zero counts is rejected`() {
        val error = validateCloudReadBackNdjson(
            ndjson("""{"type":"meta","version":3,"sessions":0,"messages":0}"""),
            "",
        )
        assertNotNull(error)
        assertTrue("Las copias de seguridad vacías deben rechazarse: $error", error!!.contains("no data"))
    }

    @Test
    fun `meta without record lines is rejected`() {
        val error = validateCloudReadBackNdjson(ndjson(validMeta), "")
        assertNotNull(error)
        assertTrue("Las filas sin datos deben rechazarse: $error", error!!.contains("no record lines"))
    }

    @Test
    fun `illegal meta count is rejected`() {
        val error = validateCloudReadBackNdjson(
            ndjson("""{"type":"meta","sessions":"many"}""", validRecord),
            "",
        )
        assertNotNull(error)
        assertTrue("Los recuentos ilegales deben rechazarse: $error", error!!.contains("not an integer"))
    }

    @Test
    fun `encrypted ndjson passes with correct password`() {
        val encrypted = BackupCrypto.encrypt(ndjson(validMeta, validRecord), "pass-123")
        assertNull(validateCloudReadBackNdjson(encrypted, "pass-123"))
    }

    @Test
    fun `encrypted ndjson with wrong password reports decrypt failure`() {
        val encrypted = BackupCrypto.encrypt(ndjson(validMeta, validRecord), "pass-123")
        val error = validateCloudReadBackNdjson(encrypted, "wrong-password")
        assertNotNull(error)
        assertTrue("La contraseña incorrecta debería indicar el motivo del error de descifrado: $error", error!!.contains("decrypt failed"))
    }

    @Test
    fun `encrypted ndjson without password reports missing password`() {
        val encrypted = BackupCrypto.encrypt(ndjson(validMeta, validRecord), "pass-123")
        val error = validateCloudReadBackNdjson(encrypted, "")
        assertNotNull(error)
        assertTrue("La contraseña faltante debería fallar explícitamente: $error", error!!.contains("password is empty"))
    }
}
