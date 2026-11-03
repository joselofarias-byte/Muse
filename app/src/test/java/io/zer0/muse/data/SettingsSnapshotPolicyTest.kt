package io.zer0.muse.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-TEST-04: Política de selección de claves para instantáneas de configuración de respaldo.
 */
class SettingsSnapshotPolicyTest {

    @Test
    fun `sensitive keys are excluded from backup snapshot`() {
        val raw = mapOf(
            "providers_json" to """[{"apiKey":"sk-fake-123"}]""",
            "web_server_config_json" to """{"password":"secret"}""",
            "mcp_servers_json" to """[{"authToken":"token"}]""",
            "cloud_backup_config_json" to """{"webdavPassword":"p"}""",
            "theme_id" to "mono",
        )
        val safe = SettingsSnapshotPolicy.sanitize(raw)
        assertFalse(safe.containsKey("providers_json"))
        assertFalse(safe.containsKey("web_server_config_json"))
        assertFalse(safe.containsKey("mcp_servers_json"))
        assertFalse(safe.containsKey("cloud_backup_config_json"))
        assertTrue(safe["theme_id"] == "mono")
        assertFalse(safe.values.any { it.contains("sk-fake-123") || it.contains("secret") || it.contains("token") })
    }

    @Test
    fun `whitelisted keys pass through`() {
        assertTrue(SettingsSnapshotPolicy.isSafeKey("theme_id"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("bool:keep_awake"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("int:default_home_page"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("long:account_login_at"))
    }

    @Test
    fun `stringSet favourites are whitelisted`() {
        // AUDIT §11.3-7: Estos dos elementos son activos del usuario.,Se requiere una copia de seguridad.
        assertTrue(SettingsSnapshotPolicy.isSafeKey("stringSet:moment_favorite_ids"))
        assertTrue(SettingsSnapshotPolicy.isSafeKey("stringSet:mini_album_favorite_image_ids"))
        // Tipo sensible:sin prefijo = string Tipo,Por lo tanto, los nombres en la lista blanca de stringSet no se pueden usar como cadenas.
        // (De lo contrario, una copia de seguridad dañada puede escribir la clave con el mismo nombre en otro tipo. DataStore).
        assertFalse(SettingsSnapshotPolicy.isSafeKey("moment_favorite_ids"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("bool:theme_id"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("int:keep_awake"))
    }

    @Test
    fun `scheduling and view state stringSets are excluded`() {
        // Tres personas que deliberadamente no ingresan a las copias de seguridad stringSet:
        //  - daily_summary_*: Programación del estado de deduplicación,Restaurar el antiguo valor haría que hoy/El resumen en este momento se considera completado.;
        //  - miniphone_hidden_apps: "¿Qué aplicaciones están ocultas?"¿Es el estado de vista lateral del dispositivo?;
        //  - mini_album_hidden_image_ids: Ocultar lista,Junto con datos derivados del álbum,Sin sentido en todos los dispositivos.
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:daily_summary_claimed_slots"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:daily_summary_completed_slots"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:miniphone_hidden_apps"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("stringSet:mini_album_hidden_image_ids"))
    }

    @Test
    fun `sensitive fragments block future additions`() {
        assertFalse(SettingsSnapshotPolicy.isSafeKey("asr_config_json"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("web_search_config_json"))
        assertFalse(SettingsSnapshotPolicy.isSafeKey("api_keys_json"))
    }

    @Test
    fun `media config excluded from backup snapshot`() {
        // P0-2: MediaConfig Contiene credenciales ttsApiKey,media_config_json No debe aparecer en las instantáneas de respaldo
        assertFalse("media_config_json Debe excluirse de la instantánea de respaldo", SettingsSnapshotPolicy.isSafeKey("media_config_json"))
        val raw = mapOf("media_config_json" to """{"ttsApiKey":"sk-tts-123","ttsEngine":"openai"}""")
        val safe = SettingsSnapshotPolicy.sanitize(raw)
        assertFalse(safe.containsKey("media_config_json"))
        assertFalse(safe.values.any { it.contains("sk-tts-123") })
    }
}
