package io.zer0.muse.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-25: Verificación de función pura del límite superior del tamaño de copia de seguridad JSON único.
 *
 * Portada [BackupService.withinSingleJsonLimit](Lectura del tiempo acumulado línea por línea OOM)
 * con [BackupService.singleJsonTooLargeMessage](Borrar mensaje de error cuando se exceda el límite).
 */
class BackupJsonLimitTest {

    @Test
    fun `within limit returns true`() {
        // Exactamente igual al límite superior:se considera que no excede el límite
        assertTrue(
            BackupService.withinSingleJsonLimit(
                BackupService.MAX_SINGLE_JSON_BACKUP_BYTES - 1,
                1L,
            ),
        )
        assertTrue(BackupService.withinSingleJsonLimit(1024L, 100L))
    }

    @Test
    fun `over limit returns false`() {
        val cap = BackupService.MAX_SINGLE_JSON_BACKUP_BYTES
        assertFalse(BackupService.withinSingleJsonLimit(cap, 1L))
        assertFalse(BackupService.withinSingleJsonLimit(cap + 1, 0L))
        // La línea única excede el límite superior,Incluso si la acumulación anterior es 0, se debe considerar que excede el límite.
        assertFalse(BackupService.withinSingleJsonLimit(0L, cap + 1000L))
    }

    @Test
    fun `limit message includes upper bound`() {
        val msg = BackupService.singleJsonTooLargeMessage(70L * 1024 * 1024)
        assertTrue(msg.contains("64MB"))
        assertTrue(msg.contains("Importación rechazada"))
        assertEquals("El tamaño de la copia de seguridad JSON única de 70 MB supera el límite superior 64MB,Importación rechazada", msg)
    }
}
