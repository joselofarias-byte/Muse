package io.zer0.muse.data.card

import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.backup.BackupBinaryFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Los datos de la tarjeta se almacenan en fragmentos.
 *
 * Antecedentes: la implementación anterior era de una sola fila. `card_data.json`, cada vez `put`/`remove` La tabla completa (hasta 50 entradas,
 * Hasta 64k caracteres por línea) reserialización + escrituras atómicas + fsync. modelo continuo `update_card_data` cuando,
 * Estoy reescribiendo otras cartas cada vez —— El tamaño de una única ubicación puede alcanzar varios MB. La cantidad de una sola escritura después de la fragmentación solo está relacionada con esta tarjeta.
 *
 * Aquí están bloqueadas cuatro cosas: escribir solo su propio segmento, eliminar tarjetas y segmentos, codificar y decodificar de ida y vuelta,**El directorio de fragmentos debe estar en la lista blanca de respaldo**
 * (De lo contrario, los datos de la tarjeta ya no estarán en el rango de respaldo después de la fragmentación y todo el contenido de show_card se perderá después de la restauración).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CardDataStoreShardTest {

    private val filesDir: File get() = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    private val shardDir: File get() = File(filesDir, "card_data")

    private fun shards(): List<File> = shardDir.listFiles().orEmpty().filter { it.isFile }

    private fun newStore(): CardDataStore = CardDataStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `card removed during restore_is_not_resurrected`() {
        // P28 La condición de carrera introducida después de mover la recuperación al subproceso en segundo plano: el subproceso de recuperación tiene una instantánea del disco en la mano.
        // Si el usuario elimina una tarjeta durante este período, directamente restaurar + actual la revivirá.
        // Utilice 200 datos antiguos para ampliar la ventana de migración y luego elimine uno de ellos inmediatamente.
        val legacy = File(filesDir, "card_data.json")
        legacy.parentFile?.mkdirs()
        val entries = (1..200).joinToString(separator = ",") { i ->
            "\"c-$i\":\"{\\\"v\\\":$i}\""
        }
        legacy.writeText("{" + entries + "}")
        shardDir.deleteRecursively()

        val store = newStore()
        store.remove("c-7")   // Eliminado antes de que se complete la recuperación

        val deadline = System.currentTimeMillis() + 10_000
        while (store.get("c-1") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals("La recuperación debería completarse", """{"v":1}""", store.get("c-1"))
        assertNull("Las tarjetas eliminadas dentro de la ventana de recuperación no se pueden reactivar.", store.get("c-7"))
        assertTrue("Otras tarjetas se restaurarán como de costumbre.", store.get("c-200") != null)
    }

    @Test
    fun `legacy_file_is_migrated_in_background`() {
        // P28:Restaurar/La migración se cambia al hilo de fondo (la medida real del dispositivo es 216 ms para la migración de 50 tarjetas, que originalmente ocupaban el hilo principal).
        // Aquí se bloquean tres cosas: el fondo se migra, los archivos antiguos se renombran a .migrados y los fragmentos se mueven al disco.
        val legacy = File(filesDir, "card_data.json")
        legacy.parentFile?.mkdirs()
        legacy.writeText("""{"legacy-1":"{\"v\":1}","legacy-2":"{\"v\":2}"}""")
        shardDir.deleteRecursively()

        val store = newStore()
        val deadline = System.currentTimeMillis() + 5_000
        while (store.get("legacy-1") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertEquals("""{"v":1}""", store.get("legacy-1"))
        assertEquals("""{"v":2}""", store.get("legacy-2"))
        assertTrue("Se debe cambiar el nombre de los archivos antiguos .migrated", File(filesDir, "card_data.json.migrated").exists())
        assertEquals("Dos registros antiguos deben escribir un fragmento cada uno", 2, shards().size)
    }

    @Test
    fun `put_writes_only_the_touched_card`() {
        val store = newStore()
        store.put("card-a", """{"v":1}""")
        val fileA = File(shardDir, CardDataFiles.shardName("card-a"))
        assertTrue("Los fragmentos deben colocarse en el disco.", fileA.exists())
        val bytesA = fileA.length()

        store.put("card-b", """{"v":2}""")
        assertEquals("Escribir B no debe cambiar los fragmentos de A", bytesA, fileA.length())
        assertTrue("Ambas cartas deben tener un fragmento cada una.", shards().size >= 2)
    }

    @Test
    fun `get_reads_back_in_memory_value`() {
        val store = newStore()
        store.put("card-c", """{"title":"tarjeta"}""")
        assertEquals("""{"title":"tarjeta"}""", store.get("card-c"))
        assertNull(store.get("card-missing"))
    }

    @Test
    fun `remove_deletes_the_shard`() {
        val store = newStore()
        store.put("card-d", """{"v":4}""")
        val file = File(shardDir, CardDataFiles.shardName("card-d"))
        assertTrue(file.exists())

        store.remove("card-d")
        assertTrue("remove Se deben eliminar los fragmentos posteriores.", !file.exists())
        assertNull(store.get("card-d"))
    }

    @Test
    fun `shard_content_round_trips_through_envelope`() {
        val store = newStore()
        store.put("card-e", """{"nested":{"a":[1,2,3]}}""")
        val text = File(shardDir, CardDataFiles.shardName("card-e")).readText()
        val decoded = CardDataFiles.decode(text)
        assertEquals("card-e", decoded?.first)
        assertEquals("""{"nested":{"a":[1,2,3]}}""", decoded?.second)
    }

    @Test
    fun `shard_name_is_stable_and_path_safe`() {
        val name = CardDataFiles.shardName("1780000000000/../evil")
        assertEquals(name, CardDataFiles.shardName("1780000000000/../evil"))
        assertTrue("No se permiten separadores de ruta", !name.contains("/") && !name.contains("\\"))
        assertEquals(64 + 5, name.length)
    }

    @Test
    fun `corrupt_shard_is_skipped`() {
        assertNull(CardDataFiles.decode("{not json"))
        assertNull(CardDataFiles.decode(""))
    }

    @Test
    fun `card_data_directory_is_in_backup_whitelist`() {
        assertTrue(
            "El directorio fragmentado debe estar en la lista blanca de respaldo de tipo directorio; de lo contrario, todos los datos de la tarjeta se perderán después de la recuperación.",
            "card_data/" in BackupBinaryFiles.allowedPrefixes,
        )
        assertTrue(
            "Los archivos individuales antiguos aún deben permanecer en la lista blanca de archivos (fuente de migración + copia de seguridad antigua compatible)",
            "card_data.json" in io.zer0.muse.backup.BackupService.backupFileStorePaths,
        )
    }
}
