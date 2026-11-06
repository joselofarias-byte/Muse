package io.zer0.muse.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupNdJsonRestoreContractTest {

    @Test
    fun `ndjson restore must propagate fts rebuild failure`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()
        val start = source.indexOf("private suspend fun applyNdJsonStreaming(")
        val end = source.indexOf(
            "private suspend fun applyNdJsonStreamingWithImageCleanup",
            startIndex = start,
        )
        require(start >= 0 && end > start) { "No se puede localizar el límite del método de restauración NDJSON" }
        val ndJsonRestoreMethod = source.substring(start, end)

        assertFalse(
            "NDJSON La recuperación no puede aceptar las excepciones de reconstrucción de FTS; de lo contrario, no se activará la reversión del punto de recuperación.",
            ndJsonRestoreMethod.contains("resultOf { sessionRepository.rebuildFtsIndex() }"),
        )
        assertTrue(ndJsonRestoreMethod.contains("sessionRepository.rebuildFtsIndex()"))
    }

    @Test
    fun `internal ndjson recovery accepts an empty pre-restore snapshot`() {
        val source = File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()

        assertTrue(
            "La importación del punto de recuperación debe tener la entrada enableEmptyBackup",
            source.contains("allowEmptyBackup"),
        )
        assertTrue(
            "NDJSON La reversión fallida debe permitir explícitamente puntos de recuperación vacíos",
            source.contains("applyNdJsonStreaming(recoveryLines, allowEmptyBackup = true)"),
        )
    }
}
