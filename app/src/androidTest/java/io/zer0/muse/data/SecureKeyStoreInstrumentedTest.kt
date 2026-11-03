package io.zer0.muse.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.zer0.muse.backup.BackupCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R-TEST-03/04: MuMu Verificación del dispositivo.
 *
 * - SecureKeyStore Realice el verdadero viaje de ida y vuelta de cifrado y descifrado del almacén de claves de Android
 * - BackupCrypto Dispositivo real AES-GCM ida y vuelta
 * - SettingsSnapshotPolicy Excluir claves sensibles en un entorno real
 */
@RunWith(AndroidJUnit4::class)
class SecureKeyStoreInstrumentedTest {

    @Test
    fun keystoreEncryptDecryptRoundTrip() = runBlocking {
        val plain = "sk-test-${System.currentTimeMillis()}"
        val encrypted = SecureKeyStore.encrypt(plain)
        assertTrue("Debe tener el prefijo enc_v1:", encrypted.startsWith("enc_v1:"))
        assertFalse("El texto cifrado no debe contener texto sin formato.", encrypted.contains(plain))
        assertEquals(plain, SecureKeyStore.decrypt(encrypted))
    }

    @Test
    fun keystorePassthroughForLegacyPlainAndEmpty() = runBlocking {
        assertEquals("", SecureKeyStore.decrypt(""))
        assertEquals("legacy", SecureKeyStore.decrypt("legacy"))
        assertEquals("", SecureKeyStore.encrypt(""))
    }

    @Test
    fun backupCryptoRoundTripOnDevice() {
        val plain = "backup-json-${System.currentTimeMillis()}"
        val encrypted = BackupCrypto.encrypt(plain.toByteArray(Charsets.UTF_8), "pass-123")
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        assertEquals(plain, String(BackupCrypto.decrypt(encrypted, "pass-123"), Charsets.UTF_8))
    }

    @Test
    fun snapshotPolicyExcludesSensitiveKeysOnDevice() {
        val raw = mapOf(
            "active_provider_id" to "openai",
            "provider_1_api_key" to "sk-secret",
            "mcp_token_x" to "jwt-secret",
            "theme_mode" to "mono",
        )
        val safe = SettingsSnapshotPolicy.sanitize(raw)
        assertEquals(setOf("active_provider_id", "theme_mode"), safe.keys)
    }
}
