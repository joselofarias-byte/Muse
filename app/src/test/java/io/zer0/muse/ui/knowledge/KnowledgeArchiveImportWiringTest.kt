package io.zer0.muse.ui.knowledge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgeArchiveImportWiringTest {

    @Test
    fun `main knowledge import routes supported archive bundles before file size and binary guards`() {
        val source = File("src/main/java/io/zer0/muse/ui/knowledge/KnowledgeScreen.kt").readText()
        val archiveRoute = source.indexOf("val archiveFormat = ArchiveFormat.fromFileName(lowerName)")

        assertTrue("La importación de la base de conocimientos principal debe identificar de manera uniforme los formatos de paquetes de compresión admitidos.", archiveRoute >= 0)

        val bundleImporter = source.indexOf("importArchiveBundle(", archiveRoute)
        val sizeGuard = source.indexOf("if (fileSize > maxFileSizeBytes", archiveRoute)
        val binaryGuard = source.indexOf("val unsupportedTextCandidate", archiveRoute)

        assertTrue("Los archivos admitidos deben entregarse al importador de archivos compartidos.", bundleImporter > archiveRoute)
        assertTrue("archive La descarga debe preceder al límite de tamaño del archivo", sizeGuard > bundleImporter)
        assertTrue("archive El desvío debe preceder a la interceptación binaria universal", binaryGuard > bundleImporter)
    }
}
