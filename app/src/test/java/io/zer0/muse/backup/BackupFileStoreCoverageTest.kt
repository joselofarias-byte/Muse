package io.zer0.muse.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupFileStoreCoverageTest {

    @Test
    fun `backup file store allowlist includes scoped pinned memory files`() {
        assertTrue("pinned_memory/pinned-memory.json" in BackupService.backupFileStorePaths)
        assertTrue("pinned_memory/pinned.md" in BackupService.backupFileStorePaths)
    }

    @Test
    fun `backup file store allowlist includes card data`() {
        // v4.1: CardDataStore filesDir/card_data.json fue excluido previamente de la lista blanca,
        // datos de la tarjeta(show_card/update_card_data escribir)Sin respaldo.
        assertTrue("card_data.json" in BackupService.backupFileStorePaths)
    }

    @Test
    fun `ndjson declares and counts file store records`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()

        assertTrue(source.contains("""put("fileStores", fileStores.size)"""))
        assertTrue(source.contains(""""fileStore" to "fileStores""""))
    }
}
