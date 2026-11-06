package io.zer0.muse.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.x cierre del legado:channel_media Prueba única de función pura de estrategia de limpieza(No toques el sistema de archivos.).
 */
class ChannelMediaCleanerTest {

    private val now = 1_000_000_000_000L

    private fun meta(name: String, ageMs: Long, size: Long) =
        ChannelMediaCleaner.FileMeta(path = name, lastModified = now - ageMs, size = size)

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun emptyInputDeletesNothing() {
        assertEquals(
            emptyList<String>(),
            ChannelMediaCleaner.selectForDeletion(emptyList(), now),
        )
    }

    @Test
    fun deletesOnlyFilesOlderThanTtl() {
        val files = listOf(
            meta("fresh.bin", ageMs = 1 * day, size = 10),
            meta("edge.bin", ageMs = 30 * day, size = 10), // exactamente igual a TTL,no borrar(Eliminar sólo si es estrictamente mayor que)
            meta("stale.bin", ageMs = 31 * day, size = 10),
        )

        val deleted = ChannelMediaCleaner.selectForDeletion(
            files,
            now = now,
            ttlMs = 30 * day,
            maxTotalBytes = Long.MAX_VALUE,
        )

        assertEquals(listOf("stale.bin"), deleted)
    }

    @Test
    fun sizeCapEvictsLeastRecentlyModifiedFirst() {
        // No hay archivos caducados,tamaño total 300 > límite superior 250 → Eliminar a los más viejos hasta ≤ 250
        val files = listOf(
            meta("a_old.bin", ageMs = 5 * day, size = 100),
            meta("b_mid.bin", ageMs = 3 * day, size = 100),
            meta("c_new.bin", ageMs = 1 * day, size = 100),
        )

        val deleted = ChannelMediaCleaner.selectForDeletion(
            files,
            now = now,
            ttlMs = 30 * day,
            maxTotalBytes = 250,
        )

        assertEquals(listOf("a_old.bin"), deleted)
    }

    @Test
    fun sizeCapEvictsMultipleOldestUntilUnderLimit() {
        // total 500 > límite superior 150 → Eliminar los dos más antiguos.(100+100),quedan 300 >150 → borrar otro → quedan 200>150
        // Continuar eliminando → izquierda 100 ≤150 detener. En realidad, los más antiguos se eliminan primero uno por uno.
        val files = listOf(
            meta("f1.bin", ageMs = 5 * day, size = 100),
            meta("f2.bin", ageMs = 4 * day, size = 100),
            meta("f3.bin", ageMs = 3 * day, size = 100),
            meta("f4.bin", ageMs = 2 * day, size = 100),
            meta("f5.bin", ageMs = 1 * day, size = 100),
        )

        val deleted = ChannelMediaCleaner.selectForDeletion(
            files,
            now = now,
            ttlMs = 30 * day,
            maxTotalBytes = 150,
        )

        // Eliminar del más antiguo:Eliminar f1..f4 y salir 100 ≤ 150 detenerse → Reservar f5
        assertEquals(listOf("f1.bin", "f2.bin", "f3.bin", "f4.bin"), deleted)
    }

    @Test
    fun ttlAndSizeCapCombine() {
        // stale Eliminado por TTL primero;total restante 300 > límite superior 250 → Luego elimina el más viejo. fresh_old
        val files = listOf(
            meta("stale.bin", ageMs = 40 * day, size = 999),
            meta("fresh_old.bin", ageMs = 5 * day, size = 150),
            meta("fresh_mid.bin", ageMs = 3 * day, size = 100),
            meta("fresh_new.bin", ageMs = 1 * day, size = 50),
        )

        val deleted = ChannelMediaCleaner.selectForDeletion(
            files,
            now = now,
            ttlMs = 30 * day,
            maxTotalBytes = 250,
        )

        assertEquals(listOf("stale.bin", "fresh_old.bin"), deleted)
    }

    @Test
    fun protectedReferencedMediaIsNeverSelectedEvenWhenExpired() {
        val files = listOf(
            meta("referenced.bin", ageMs = 90 * day, size = 999),
            meta("orphan.bin", ageMs = 90 * day, size = 999),
        )

        val deleted = ChannelMediaCleaner.selectForDeletion(
            files,
            now = now,
            protectedPaths = setOf("referenced.bin"),
        )

        assertEquals(listOf("orphan.bin"), deleted)
    }

    @Test
    fun keepsEverythingWhenUnderBothLimits() {
        val files = listOf(
            meta("x.bin", ageMs = 1 * day, size = 10),
            meta("y.bin", ageMs = 2 * day, size = 20),
        )

        val deleted = ChannelMediaCleaner.selectForDeletion(
            files,
            now = now,
            ttlMs = 30 * day,
            maxTotalBytes = 1024,
        )

        assertTrue(deleted.isEmpty())
    }
}
