package io.zer0.muse.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Regression coverage for the single-session export entry point.
 *
 * Full backup export remains available; this guard verifies the settings page
 * also wires a session-scoped export that can load complete history.
 */
class BackupSessionExportWiringTest {

    @Test
    fun `backup settings exposes single session export using complete history`() {
        val backupSource = locate("src/main/java/io/zer0/muse/ui/settings/BackupSection.kt")
        val pageSource = locate("src/main/java/io/zer0/muse/ui/settings/SettingsSubPages.kt")

        assertTrue(backupSource.contains("settings_backup_export_session"))
        assertTrue(backupSource.contains("getAllMessagesForBackfill"))
        assertTrue(pageSource.contains("sessionRepository = sessionRepository"))
        assertTrue(backupSource.contains("sessionExportQuery"))
        assertTrue(backupSource.contains("filteredExportSessions"))
        assertTrue(!backupSource.contains("exportableSessions.take(50)"))
        assertTrue("La lista de sesiones exportadas debe llenar el ancho del contenido de la ventana emergente para evitar que el título se mida con un ancho cero y solo muestre elipses.", backupSource.contains(".fillMaxWidth()"))
        assertTrue("Las listas de sesiones de exportación aún deben conservar un límite de altura", backupSource.contains(".heightIn(max = 420.dp)"))
        assertTrue("Los títulos de las sesiones de exportación muestran al menos dos líneas, los títulos largos aún se pueden distinguir", backupSource.contains("titleMaxLines = 2"))

        val itemRowSource = locate("src/main/java/io/zer0/muse/ui/common/settings/SettingsItemRow.kt")
        assertTrue("SettingsItemRow Se debe permitir que las listas de exportación relajen el número de filas de encabezado", itemRowSource.contains("titleMaxLines: Int = 1"))
    }

    private fun locate(relativePath: String): String {
        val candidates =
            listOf(
                Path.of(relativePath),
                Path.of("app").resolve(relativePath.removePrefix("src/")),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
