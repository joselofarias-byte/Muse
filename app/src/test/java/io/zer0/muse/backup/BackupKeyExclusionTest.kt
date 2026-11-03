package io.zer0.muse.backup

import io.zer0.muse.data.MediaConfig
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.data.SecureKeyStore
import io.zer0.muse.data.SettingsSnapshotPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-04: Escaneo de productos de respaldo — El JSON de copia de seguridad serializado no debe contener ningún texto sin cifrar de clave API.
 */
class BackupKeyExclusionTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `serialized backup does not contain api key plaintext`() {
        val rawSettings = mapOf(
            "providers_json" to """[{"id":"openai","apiKey":"sk-fake-123"}]""",
            "web_server_config_json" to """{"password":"secret-pin"}""",
            "media_config_json" to """{"ttsApiKey":"sk-tts-456","ttsEngine":"openai"}""",
            "theme_id" to "mono",
        )
        val backup = BackupService.Backup(
            version = 3,
            exportedAt = 0L,
            sessions = emptyList(),
            messages = emptyList(),
            settingsSnapshot = SettingsSnapshotPolicy.sanitize(rawSettings),
        )
        val text = json.encodeToString(BackupService.Backup.serializer(), backup)
        assertFalse(text.contains("sk-fake-123"))
        assertFalse(text.contains("secret-pin"))
        assertFalse("El JSON de respaldo no debe contener texto sin formato de clave TTS", text.contains("sk-tts-456"))
        assertTrue(text.contains("mono"))
    }

    @Test
    fun `media config encrypted does not leak tts api key plaintext`() = runBlocking {
        // P0-2: MediaConfig.encrypted() ttsApiKey debe convertirse en texto cifrado(memoria doble),No hay texto claro después de la serialización
        val original = SecureKeyStore.delegate
        try {
            SecureKeyStore.delegate = object : SecureKeyCipher {
                override suspend fun encrypt(plain: String): String = if (plain.isEmpty()) plain else "enc_v1:${plain.reversed()}"
                override suspend fun decrypt(stored: String): String =
                    if (stored.startsWith("enc_v1:")) stored.removePrefix("enc_v1:").reversed() else stored
            }
            val config = MediaConfig(ttsApiKey = "sk-tts-789", ttsEngine = "openai")
            val stored = json.encodeToString(MediaConfig.serializer(), config.encrypted())
            assertFalse("JSON persistente no debe contener texto sin formato de clave TTS", stored.contains("sk-tts-789"))
            assertTrue("JSON persistente debe contener un prefijo de cifrado", stored.contains("enc_v1:"))

            // Leer de nuevo el descifrado para recuperar texto sin formato(Viaje de ida y vuelta consistente)
            val decrypted = json.decodeFromString(MediaConfig.serializer(), stored).decrypted()
            assertTrue(decrypted.ttsApiKey == "sk-tts-789")
        } finally {
            SecureKeyStore.delegate = original
        }
    }
}
