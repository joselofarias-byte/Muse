package io.zer0.muse.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 Regresar:Guardia de caducidad de contraseña de respaldo [BackupService.isBackupPasswordUnavailable].
 *
 * Portada"La contraseña ha sido establecida + la contraseña está en blanco(Keystore Inválido)→ Debe considerarse no disponible y negarse a exportar./subir",
 * Evite que las copias de seguridad se degraden silenciosamente a escritura de texto sin formato en el disco. Determinación y exportación local([BackupService.export]/
 * [BackupService.exportStreaming])y carga en la nube([BackupService.exportToCloud])compartir,
 * Los tres se comportan igual.
 */
class BackupPasswordGuardTest {

    @Test
    fun passwordSetButBlank_isUnavailable() {
        // El usuario ha establecido una contraseña,Pero una vez que se pierde la clave del almacén de claves, decrypt vuelve a leer una cadena vacía.
        val config = CloudBackupConfig(backupPassword = "", backupPasswordSet = true)
        assertTrue(BackupService.isBackupPasswordUnavailable(config))
    }

    @Test
    fun passwordSetAndPresent_isAvailable() {
        val config = CloudBackupConfig(backupPassword = "secret", backupPasswordSet = true)
        assertFalse(BackupService.isBackupPasswordUnavailable(config))
    }

    @Test
    fun neverSetAndBlank_isAvailable() {
        // Contraseña nunca establecida:Permitir exportación de texto claro(semántica del producto)
        val config = CloudBackupConfig(backupPassword = "", backupPasswordSet = false)
        assertFalse(BackupService.isBackupPasswordUnavailable(config))
    }

    @Test
    fun blankWithWhitespace_isUnavailable() {
        // Las cadenas de espacio también se consideran no disponibles.(Consistente con la semántica isBlank,Bypass de cadena vacía anti-recorte)
        val config = CloudBackupConfig(backupPassword = "   ", backupPasswordSet = true)
        assertTrue(BackupService.isBackupPasswordUnavailable(config))
    }
}
