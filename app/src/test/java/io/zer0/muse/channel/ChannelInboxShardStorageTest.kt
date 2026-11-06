package io.zer0.muse.channel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong

/**
 * Almacenamiento fragmentado de la bandeja de entrada (2026-11-01 mejoras de rendimiento).
 *
 * La antigua implementación era**Archivo de matriz JSON único**: durante el ciclo de vida de un mensaje de canal, recibir/guardar borradores /
 * Puntos de interrupción para cada llamada de herramienta (una vez antes y después de la ejecución)/ Una vez completado, se reescribe toda la tabla cada vez y se mantienen todos los bloqueos de objetos.
 * Cambiar a"Un elemento de cola y un fragmento"Después de eso, el volumen de colocación solo está relacionado con este registro.
 *
 * Cinco cosas para fijar aquí:
 *  1. una vez `record` Agregue solo un fragmento (no reescriba toda la tabla);
 *  2. La migración de estado no agrega ni elimina fragmentos (el mismo registro se reescribe en el lugar);
 *  3. El orden y el estado permanecen sin cambios después de reiniciar (el orden depende de la secuencia en el nombre del archivo);
 *  4. Los archivos individuales antiguos se pueden migrar en porciones y el orden es coherente con la matriz anterior;
 *  5. Los fragmentos defectuosos se aíslan y los fragmentos restantes se cargan como de costumbre (correspondiente a"Asesinado en medio de la escritura"escenario realista).
 */
class ChannelInboxShardStorageTest {

    private lateinit var directory: File
    private lateinit var inboxFile: File
    private lateinit var ledgerFile: File
    private lateinit var journal: ChannelInboxJournal
    private val nowMillis = AtomicLong(1_800_000_000_000L)

    private val shardDir: File get() = File(directory, ChannelInbox.INBOX_DIR_NAME)

    private fun shards(): List<File> = shardDir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("inbox-shard-test").toFile()
        inboxFile = File(directory, "inbox.json")
        ledgerFile = File(directory, "events.json")
        journal = newJournal()
        journal.attach(inboxFile, ledgerFile)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun newJournal(): ChannelInboxJournal = ChannelInboxJournal(nowMillis = { nowMillis.get() })

    private fun source(eventId: String) = ChannelInbox.Source(
        platform = "QQ",
        from = "user",
        channelId = "qq-a",
        eventId = eventId,
    )

    private fun inbound(eventId: String, summary: String) = ChannelInbox.Inbound(
        platform = "QQ",
        from = "user",
        summary = summary,
        sourceChannelId = "qq-a",
        sourceEventId = eventId,
        dispatchId = "dispatch-$eventId",
        dispatchState = ChannelInbox.DispatchState.PENDING,
    )

    @Test
    fun `record_writes_one_shard_per_item`() {
        journal.record(source("e1"), inbound("e1", "Artículo 1"))
        assertEquals(1, shards().size)

        journal.record(source("e2"), inbound("e2", "Artículo 2"))
        assertEquals("Un fragmento por evento", 2, shards().size)
        assertFalse("No se permite escribir más archivos individuales antiguos", inboxFile.exists())
    }

    @Test
    fun `state_transitions_rewrite_in_place`() {
        journal.record(source("e1"), inbound("e1", "Pendiente"))
        val claimed = journal.claimNextPending()!!
        journal.savePreparedReply(claimed.dispatchId, "Respuesta generada")
        journal.completeDispatch(claimed.dispatchId, ignored = false)

        assertEquals("La migración estatal no debe agregar ni eliminar fragmentos", 1, shards().size)
        val text = shards().single().readText()
        assertTrue("Deberías poder ver el estado final en los fragmentos.", text.contains("COMPLETED"))
        assertFalse("No deben quedar más borradores una vez finalizado.", text.contains("Respuesta generada"))
    }

    @Test
    fun `restart_preserves_order_and_state`() {
        listOf("a", "b", "c").forEach { id -> journal.record(source("e-$id"), inbound("e-$id", "contenido-$id")) }
        nowMillis.addAndGet(1_000)
        journal.claimNextPending()

        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        val order = restarted.snapshot().map { it.summary }
        // La semántica existente del orden de cola es**nuevos eventos primero**(record usar listOf(item) + lista antigua),
        // Para recibirlo, toma el primero en este orden. PENDING —— Entonces lo que se afirma aquí es"Completamente consistente antes y después del reinicio.".
        assertEquals("El reinicio no debe cambiar el orden de la cola (los nuevos eventos primero)", listOf("contenido-c", "contenido-b", "contenido-a"), order)
        assertEquals(
            "El que se ha recibido seguirá PROCESANDO después de reiniciar (el destino lo determinará recoveryInterruptedDispatches)",
            ChannelInbox.DispatchState.PROCESSING,
            restarted.snapshot().first().dispatchState,
        )
    }

    @Test
    fun `recovered_dispatch_survives_restart`() {
        // Retorno:recoverInterruptedDispatches una vez puesto"El estado acaba de ser cambiado por estandarización."Se considera que esas pocas líneas han sido cortadas,
        // entonces"Primero escriba un fragmento nuevo y luego elimine el fragmento con el mismo nombre." —— Está en la memoria pero no en el disco. Este evento desaparecerá la próxima vez que reinicies.
        journal.record(source("e1"), inbound("e1", "Envío interrumpido"))
        val claimed = journal.claimNextPending()!!
        assertEquals(ChannelInbox.DispatchState.PROCESSING, claimed.dispatchState)

        // Reinicio simulado: después de adjuntar la nueva instancia, regrese PROCESSING a PENDING
        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        restarted.recoverInterruptedDispatches()
        assertEquals(ChannelInbox.DispatchState.PENDING, restarted.snapshot().single().dispatchState)

        // Reinicie nuevamente: esta entrada aún debe estar en el disco (aquí se encontrarán 0 entradas antes de la reparación)
        val again = newJournal().also { it.attach(inboxFile, ledgerFile) }
        assertEquals("Los resultados de la recuperación deben guardarse en el disco; de lo contrario, los eventos se perderán en el siguiente inicio.", 1, again.snapshot().size)
        assertEquals("Envío interrumpido", again.snapshot().single().summary)
    }

    @Test
    fun `completed_entry_shard_is_kept_without_media_bytes`() {
        // Regresión: cuando se completa la distribución, se coloca la copia original después de la transformación (también con mediaBase64).
        // La memoria ha sido borrada por normalizar. —— La eliminación utilizando la igualdad de clases de datos eliminará este fragmento en conjunto.
        val withImage = inbound("e-media", "Artículos completos con imágenes.").copy(
            mediaKind = "image",
            mediaBase64 = "A".repeat(500),
        )
        journal.record(source("e-media"), withImage)
        val claimed = journal.claimNextPending()!!
        journal.completeDispatch(claimed.dispatchId, ignored = false)

        assertEquals("El fragmento del artículo completado aún debe existir.", 1, shards().size)
        val text = shards().single().readText()
        assertTrue("El estado debe conservarse en los fragmentos del estado final.", text.contains("COMPLETED"))
        assertFalse("No debería haber más bytes de imagen en el fragmento final.", text.contains("A".repeat(200)))
    }

    @Test
    fun `legacy_single_file_is_migrated_to_shards`() {
        // Cree una versión anterior y colóquela en el disco: tres registros, el orden de la matriz es el orden en que se agregan a la cola.
        val legacy = listOf("legacy-1", "legacy-2", "legacy-3").mapIndexed { index, summary ->
            ChannelInbox.Inbound(
                platform = "QQ",
                from = "user",
                summary = summary,
                dispatchId = "old-$index",
                dispatchState = ChannelInbox.DispatchState.COMPLETED,
            )
        }
        inboxFile.writeText(
            kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ChannelInbox.Inbound.serializer()),
                legacy,
            ),
        )

        val migrated = newJournal().also { it.attach(inboxFile, ledgerFile) }

        assertEquals("Cada uno de los tres registros antiguos debe escribir un fragmento.", 3, shards().size)
        assertEquals(
            "Después de la migración, el orden es coherente con la matriz anterior.",
            listOf("legacy-1", "legacy-2", "legacy-3"),
            migrated.snapshot().map { it.summary },
        )
        assertFalse("Los archivos individuales antiguos deberían ceder una vez completada la migración.", inboxFile.exists())
        assertTrue("Los archivos antiguos se conservan como .migrados para su revisión", File(directory, "inbox.json.migrated").exists())
    }

    @Test
    fun `corrupt_shard_is_quarantined_and_others_still_load`() {
        journal.record(source("e1"), inbound("e1", "bueno"))
        File(shardDir, "000000000099-deadbeef.json").writeText("{ A mitad de la escritura ")

        val restarted = newJournal().also { it.attach(inboxFile, ledgerFile) }
        assertEquals("Se deben omitir los fragmentos defectuosos y cargar los buenos como de costumbre.", 1, restarted.snapshot().size)
        assertEquals("bueno", restarted.snapshot().single().summary)
        assertTrue(
            "Los fragmentos defectuosos deben ponerse en cuarentena en lugar de eliminarse silenciosamente",
            shardDir.listFiles().orEmpty().any { it.name.contains("corrupt") },
        )
    }

    @Test
    fun `shard_directory_is_in_backup_whitelist`() {
        assertTrue(
            "El directorio fragmentado debe estar en la lista blanca de respaldo de tipo directorio; de lo contrario, todos los eventos que se distribuirán se perderán después de la recuperación.",
            io.zer0.muse.backup.BackupBinaryFiles.allowedPrefixes.contains("channel_inbox/"),
        )
        assertTrue(
            "Los archivos individuales antiguos permanecen en la lista blanca de archivos (fuente de migración + copia de seguridad antigua compatible)",
            io.zer0.muse.backup.BackupService.backupFileStorePaths.contains("channel_inbox.json"),
        )
    }

    @Test
    fun `clear_keeps_active_shards_and_drops_history`() {
        journal.record(source("e1"), inbound("e1", "Completado"))
        val claimed = journal.claimNextPending()!!
        journal.completeDispatch(claimed.dispatchId, ignored = false)
        journal.record(source("e2"), inbound("e2", "Pendiente"))

        assertEquals(2, shards().size)
        journal.clear()

        assertEquals("Borrar para conservar solo los elementos sin terminar", 1, shards().size)
        assertEquals("Pendiente", journal.snapshot().single().summary)
    }
}
