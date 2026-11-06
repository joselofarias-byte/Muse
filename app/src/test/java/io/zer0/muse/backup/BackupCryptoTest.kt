package io.zer0.muse.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-04: Cifrado de respaldo/Descifrado de ida y vuelta con reconocimiento de formato.
 */
class BackupCryptoTest {

    @Test
    fun `encrypt then decrypt restores original bytes`() {
        val plaintext = "Copia de seguridad de contenido con chino and apiKey=sk-secret-test".toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.encrypt(plaintext, "backup-password")
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertFalse(BackupCrypto.isEncrypted(plaintext))
        assertArrayEquals(plaintext, BackupCrypto.decrypt(encrypted, "backup-password"))
    }

    @Test
    fun `wrong password is rejected`() {
        val encrypted = BackupCrypto.encrypt("secret".toByteArray(), "correct")
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(encrypted, "wrong")
        }
    }

    @Test
    fun `empty password is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt("secret".toByteArray(), "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt("MENC".toByteArray(), "")
        }
    }
}
