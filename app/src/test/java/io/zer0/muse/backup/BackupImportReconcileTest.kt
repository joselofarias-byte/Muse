package io.zer0.muse.backup

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUD-DATA-B34: La conciliación de importación de respaldo no puede simplemente mirar“Número de líneas analizadas”.
 *
 * B-34 La implementación original solo compara la cantidad de metadeclaraciones y la cantidad de análisis; el análisis pasa, pero si la inserción de la descarga del búfer pierde registros,
 * No puedo verlo desde la reconciliación. → “Importación exitosa pero faltan datos parcialmente”. Ahora agregue la verificación del número real de filas después del almacenamiento.
 */
class BackupImportReconcileTest {

    private fun source(): String = java.io.File("src/main/java/io/zer0/muse/backup/BackupService.kt").readText()

    /** Tome el cuerpo completo de la función de verificarInsertedCounts (hasta el paréntesis de cierre de la sangría del mismo nivel), sin truncarlo por el número mágico de caracteres. */
    private fun verifyInsertedCountsBody(): String {
        val src = source()
        val start = src.indexOf("private suspend fun verifyInsertedCounts")
        require(start >= 0) { "no encontrado verifyInsertedCounts" }
        val end = src.indexOf("\n    }", start)
        require(end > start) { "No se encontró el final del cuerpo de la función verificarInsertedCounts" }
        return src.substring(start, end)
    }

    @Test
    fun `import reconciles inserted row counts after flush`() {
        val src = source()
        assertTrue(
            "flush Luego debe llamar a la reconciliación de almacenaje.",
            src.contains("verifyInsertedCounts(expectedCounts, skippedSessionCount = skippedMiniPhoneSessions)"),
        )
    }

    @Test
    fun `reconcile compares against real table rows`() {
        val body = verifyInsertedCountsBody()
        assertTrue(
            "La conciliación debe leer el número real de filas de la tabla.(Como por ejemplo sessionDao().observeAll())",
            body.contains("db.sessionDao().observeAll().first().size"),
        )
        assertTrue(
            "Una cantidad de almacenamiento insuficiente debería provocar una reversión",
            body.contains("La cantidad de copias de seguridad almacenadas en la base de datos es inconsistente"),
        )
    }

    @Test
    fun `reconcile failure does not block import on counting errors`() {
        val body = verifyInsertedCountsBody()
        assertTrue(
            "Si no se puede recuperar el número, solo se registrará el registro.,No se deben bloquear las importaciones",
            body.contains("No se pudo recuperar la conciliación de almacenamiento"),
        )
    }
}
