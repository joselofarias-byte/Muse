package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.stats.AutoBackupLogDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v4.1: Copia de seguridad de archivos de directorio([BackupBinaryFiles])con eso [BackupService] El protector de cableado en el interior.
 *
 * Fondo de defecto:anteriormente [BackupService.backupFileStorePaths] Sobrescribir solo la lista blanca de un solo archivo,
 * `note_attachments/`(NoteAttachmentStore,Mezclando binario y texto)con `avatar/`
 * (AvatarStorage copia privada de)No hay ninguna copia de seguridad;`card_data.json` También excluido de la lista blanca.
 * Después del reemplazo y recuperación. QuickNoteEntity.attachmentsJson / AssistantEntity.avatarImageUrl
 * Todos apuntan a archivos que no existen.,Se pierden todos los datos de la tarjeta.
 *
 * Cubre cinco cosas:
 *  1. Consistencia de bytes de ida y vuelta(Exportar → Base64 → restaurar,Contiene un lado de exportación real de writeNdJson);
 *  2. El recorrido de ruta se deniega y nunca se incluye en la lista blanca(Contiene `..`, ruta absoluta, barra invertida, segmento vacío);
 *  3. Límite de capacidad(Archivo único 2 MB/directorio 16MB)Saltar el límite y contar,Los archivos restantes se restauran como de costumbre.;
 *  4. Los errores de escritura se propagan hacia arriba(Libro mayor de recuperación compartido,fail-fast Para activar la reversión del punto de recuperación);
 *  5. compatible con versiones anteriores(Las copias de seguridad antiguas sin el campo archivos binarios aún se pueden analizar y restaurar).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupBinaryFilesTest {

    private lateinit var context: Context
    private lateinit var museDb: MuseDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var factDb: FactDb
    private lateinit var imageStore: MessageImageStore
    private lateinit var service: BackupService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cleanFilesDir()
        museDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java).allowMainThreadQueries().build()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java).allowMainThreadQueries().build()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java).allowMainThreadQueries().build()
        imageStore = MessageImageStore(Files.createTempDirectory("binary-backup-images").toFile())
        service = newService()
    }

    @After
    fun tearDown() {
        museDb.close()
        memoryDb.close()
        factDb.close()
        cleanFilesDir()
    }

    private fun cleanFilesDir() {
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
        File(context.filesDir, "note_attachments").deleteRecursively()
        File(context.filesDir, "avatar").deleteRecursively()
        File(context.filesDir, "card_data.json").delete()
    }

    // ── comportamiento del módulo(Sistema de archivos puro) ──────────────────────────────────────────────

    private fun tempRoot(): File = Files.createTempDirectory("muse-binary-backup").toFile()

    @Test
    fun `note attachment and avatar bytes round trip unchanged`() = runBlocking {
        val source = tempRoot()
        try {
            val text = "Texto adjunto de nota\nsegunda linea".toByteArray(Charsets.UTF_8)
            val binary = ByteArray(64) { (it * 7).toByte() } + byteArrayOf(0x00, 0xFF.toByte(), 0x7F)
            File(source, "note_attachments").mkdirs()
            File(source, "avatar").mkdirs()
            File(source, "note_attachments/a.txt").writeBytes(text)
            File(source, "avatar/b.bin").writeBytes(binary)

            val snapshot = BackupBinaryFiles.read(source)
            assertEquals(0, snapshot.skipped.skippedFiles)
            assertEquals(listOf("avatar/b.bin", "note_attachments/a.txt"), snapshot.items.map { it.path })

            // Simular copia de seguridad en disco/volver a leer:Base64 El texto es el único medio,Los bytes deben ser consistentes después del viaje de ida y vuelta.
            val encoded = snapshot.asBase64Map()

            val target = tempRoot()
            try {
                val result = BackupBinaryFiles.restore(encoded, target)
                assertEquals(2, result.writtenFiles)
                assertEquals(0, result.skippedFiles)
                assertArrayEquals(text, File(target, "note_attachments/a.txt").readBytes())
                assertArrayEquals(binary, File(target, "avatar/b.bin").readBytes())
            } finally {
                target.deleteRecursively()
            }
        } finally {
            source.deleteRecursively()
        }
    }

    @Test
    fun `restore rejects traversal keys and never writes outside files dir`() = runBlocking {
        val root = tempRoot()
        try {
            val outside = File(root.parentFile, "muse-escaped-${System.nanoTime()}.txt")
            val payload = Base64.getEncoder().encodeToString("evil".toByteArray())
            val malicious = mapOf(
                "../evil.txt" to payload,
                "note_attachments/../../evil.txt" to payload,
                "avatar/../../../evil.txt" to payload,
                "/absolute/evil.txt" to payload,
                "note_attachments\\..\\evil.txt" to payload,
                "note_attachments//nested.txt" to payload,
                "note_attachments/./dot.txt" to payload,
                "note_attachments/../evil.txt" to payload,
                // entrada legal:Todavía debe poder escribir,Demostrar que la lógica del rechazo no es"fracaso total"
                "note_attachments/ok.txt" to payload,
            )

            val result = BackupBinaryFiles.restore(malicious, root)

            assertEquals("Solo se permiten rutas legales en la lista blanca para reescribir", 1, result.writtenFiles)
            assertEquals("Los 8 caminos ilegales restantes deben ser rechazados.", 8, result.skippedFiles)
            assertFalse("No debe escribir archivos externos en Dir.", outside.exists())
            assertFalse(File(root.parentFile, "evil.txt").exists())
            assertFalse(File(root, "evil.txt").exists())
            assertFalse(File(root, "absolute/evil.txt").exists())
            assertFalse(File(root, "nested.txt").exists())
            assertFalse(File(root, "dot.txt").exists())
            assertTrue(File(root, "note_attachments/ok.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `path allowlist rejects every non whitelisted or ambiguous form`() {
        listOf(
            "",
            "card_data.json",
            "note_attachments",
            "note_attachments/",
            "avatars/x.png",
            "note_attachment/x.png",
            "../note_attachments/x.png",
            "/note_attachments/x.png",
            "note_attachments/../x.png",
            "note_attachments/../../x.png",
            "note_attachments/sub/../../x.png",
            "note_attachments\\x.png",
            "note_attachments//x.png",
            "note_attachments/./x.png",
            "note_attachments/x\u0000.png",
        ).forEach { path ->
            assertFalse("debe negarse: $path", BackupBinaryFiles.isAllowedPath(path))
            assertNull("Las rutas ilegales no deben resolver el archivo de destino: $path", BackupBinaryFiles.resolveTarget(tempRoot(), path))
        }
        assertTrue(BackupBinaryFiles.isAllowedPath("note_attachments/a.txt"))
        assertTrue(BackupBinaryFiles.isAllowedPath("avatar/deep/nested.jpg"))
    }

    @Test
    fun `oversized single file is skipped with counts while others still restore`() = runBlocking {
        val root = tempRoot()
        try {
            val tooBig = ByteArray((BackupBinaryFiles.MAX_FILE_BYTES + 1).toInt()) { 1 }
            val small = "small".toByteArray()
            File(root, "note_attachments").mkdirs()
            File(root, "avatar").mkdirs()
            File(root, "note_attachments/huge.png").writeBytes(tooBig)
            File(root, "note_attachments/small.txt").writeBytes(small)
            File(root, "avatar/a.jpg").writeBytes(small)

            val snapshot = BackupBinaryFiles.read(root)
            assertEquals("No se realizarán copias de seguridad de los archivos excesivos", 1, snapshot.skipped.skippedFiles)
            assertEquals(tooBig.size.toLong(), snapshot.skipped.skippedBytes)
            assertEquals(listOf("avatar/a.jpg", "note_attachments/small.txt"), snapshot.items.map { it.path })

            // También hay un límite superior en el lado de la recuperación.:Insertar manualmente una entrada que excede el límite,Debe omitirse sin afectar los archivos restantes.
            val encoded = snapshot.asBase64Map() +
                ("note_attachments/injected.png" to Base64.getEncoder().encodeToString(tooBig))
            val target = tempRoot()
            try {
                val result = BackupBinaryFiles.restore(encoded, target)
                assertEquals(2, result.writtenFiles)
                assertEquals(1, result.skippedFiles)
                assertFalse(File(target, "note_attachments/injected.png").exists())
                assertArrayEquals(small, File(target, "note_attachments/small.txt").readBytes())
                assertArrayEquals(small, File(target, "avatar/a.jpg").readBytes())
            } finally {
                target.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `directory total cap stops further files and reports skipped bytes`() {
        val root = tempRoot()
        try {
            val dir = File(root, "avatar").apply { mkdirs() }
            // 1,5 MB cada uno: el undécimo archivo excederá el límite total del directorio de 16 MB.
            val chunk = ByteArray(1536 * 1024) { 2 }
            repeat(12) { index ->
                File(dir, "avatar_%02d.jpg".format(index)).writeBytes(chunk)
            }

            val snapshot = BackupBinaryFiles.read(root)

            assertEquals("16MB / 1.5MB = Se pueden realizar copias de seguridad de hasta 10 archivos", 10, snapshot.items.size)
            assertEquals(2, snapshot.skipped.skippedFiles)
            assertEquals(chunk.size.toLong() * 2, snapshot.skipped.skippedBytes)
            assertTrue(snapshot.items.all { it.path.startsWith("avatar/") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `read ignores non whitelisted directories and regular file stores`() {
        val root = tempRoot()
        try {
            File(root, "note_attachments").mkdirs()
            File(root, "channel_configs.json").writeText("{}")
            File(root, "messages_images").mkdirs()
            File(root, "messages_images/x.png").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "note_attachments/keep.txt").writeText("keep")

            val snapshot = BackupBinaryFiles.read(root)

            assertEquals(listOf("note_attachments/keep.txt"), snapshot.items.map { it.path })
            assertEquals(0, snapshot.skipped.skippedFiles)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore propagates write failure so the recovery point can roll back`() {
        val root = tempRoot()
        try {
            val payload = Base64.getEncoder().encodeToString("x".toByteArray())
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    BackupBinaryFiles.restore(
                        files = mapOf("note_attachments/a.txt" to payload),
                        filesDir = root,
                    ) { _, _ -> error("synthetic binary write failure") }
                }
            }
            assertFalse("Un archivo a medio escribir no debe quedar atrás cuando falla la escritura", File(root, "note_attachments/a.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `import buffer drops traversal and oversized entries before they reach memory`() {
        val buffer = BackupBinaryFiles.Buffer()
        val small = Base64.getEncoder().encodeToString("ok".toByteArray())
        val big = Base64.getEncoder().encodeToString(ByteArray((BackupBinaryFiles.MAX_FILE_BYTES + 1).toInt()))

        buffer.offer("note_attachments/../evil.txt", small)
        buffer.offer("card_data.json", small)
        buffer.offer("avatar/huge.jpg", big)
        buffer.offer("avatar/ok.jpg", small)

        assertEquals(setOf("avatar/ok.jpg"), buffer.toMap().keys)
        assertEquals(3, buffer.stats.skippedFiles)
    }

    // ── BackupService Cableado(archivos realesDir + biblioteca de memoria) ─────────────────────────

    @Test
    fun `ndjson restore writes note attachments and avatars byte for byte`() = runBlocking {
        val session = SessionEntity(id = "s1", title = "t", createdAt = 1L, updatedAt = 1L)
        val attachmentBytes = "Contenido del archivo adjunto".toByteArray(Charsets.UTF_8)
        val avatarBytes = byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0x7F)

        invokeNdJsonRecovery(
            service,
            sequenceOf(
                metaLine(sessions = 1, messages = 0, binaryFiles = 2),
                dataLine("session", SessionEntity.serializer(), session),
                binaryFileLine("note_attachments/a.txt", attachmentBytes),
                binaryFileLine("avatar/b.bin", avatarBytes),
            ),
        )

        assertArrayEquals(attachmentBytes, File(context.filesDir, "note_attachments/a.txt").readBytes())
        assertArrayEquals(avatarBytes, File(context.filesDir, "avatar/b.bin").readBytes())
    }

    @Test
    fun `ndjson restore drops traversal keys without failing the restore`() = runBlocking {
        val session = SessionEntity(id = "s2", title = "t", createdAt = 1L, updatedAt = 1L)
        val outside = File(context.filesDir.parentFile, "binary-escaped-${System.nanoTime()}.txt")
        val payload = "evil".toByteArray()

        invokeNdJsonRecovery(
            service,
            sequenceOf(
                // meta Declaración 2:Se deben contar ambos artículos del lado de la distribución.,Sólo a través de la conciliación B-34.
                metaLine(sessions = 1, messages = 0, binaryFiles = 2),
                dataLine("session", SessionEntity.serializer(), session),
                binaryFileLine("../evil.txt", payload),
                binaryFileLine("note_attachments/../../evil.txt", payload),
            ),
        )

        assertFalse("Se debe negar el cruce de caminos.,No debe escribir archivos externos en Dir.", outside.exists())
        assertFalse(File(context.filesDir, "evil.txt").exists())
        assertFalse(File(context.filesDir.parentFile, "evil.txt").exists())
        // La recuperación misma debe ser exitosa:De hecho, la sesión está perdida.
        assertEquals(1, museDb.sessionDao().observeAll().first().size)
    }

    @Test
    fun `card data file store round trips through the allowlisted file stores`() = runBlocking {
        assertTrue(
            "card_data.json Debe estar en la lista blanca de tipos de archivos.(Anteriormente excluido de la lista blanca,Los datos de la tarjeta no están respaldados)",
            "card_data.json" in BackupService.backupFileStorePaths,
        )
        val file = File(context.filesDir, "card_data.json")
        val payload = """{"card-1":"{\"value\":42}"}"""
        file.writeText(payload)

        @Suppress("UNCHECKED_CAST")
        val snapshot = callSuspend(service, "readFileStores", emptyArray(), emptyArray()) as Map<String, String>
        assertEquals(payload, snapshot["card_data.json"])

        file.delete()
        callSuspend(service, "writeFileStores", arrayOf(Map::class.java), arrayOf<Any>(snapshot))
        assertEquals(payload, file.readText())
    }

    @Test
    fun `backup json carries binary files and old backups without the field still parse`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = Base64.getEncoder().encodeToString("bytes".toByteArray())
        val backup = BackupService.Backup(
            exportedAt = 1L,
            sessions = emptyList(),
            messages = emptyList(),
            binaryFiles = mapOf("note_attachments/a.txt" to encoded),
        )
        val restored = json.decodeFromString(
            BackupService.Backup.serializer(),
            json.encodeToString(BackupService.Backup.serializer(), backup),
        )
        assertEquals(encoded, restored.binaryFiles["note_attachments/a.txt"])

        // compatible con versiones anteriores:La copia de seguridad anterior no tiene el campo archivos binarios → Vacío predeterminado map,No se informa ningún error.
        val oldStyle = """{"version":3,"exportedAt":1,"sessions":[],"messages":[]}"""
        val old = json.decodeFromString(BackupService.Backup.serializer(), oldStyle)
        assertEquals(emptyMap<String, String>(), old.binaryFiles)
    }

    @Test
    fun `real ndjson export then restore round trips directory files`() = runBlocking {
        // Camine realmente por el lado de las exportaciones. writeNdJson → Lado de importación applyNdJsonStreamingWithRecovery,
        // en lugar de escribir manualmente:Asegúrese de que el metaconteo, el tipo de fila y el cableado de distribución de recuperación sean consistentes.
        val attachment = "Bytes adjuntos".toByteArray(Charsets.UTF_8)
        val avatar = ByteArray(32) { (it * 5).toByte() }
        File(context.filesDir, "note_attachments").mkdirs()
        File(context.filesDir, "avatar").mkdirs()
        File(context.filesDir, "note_attachments/a.txt").writeBytes(attachment)
        File(context.filesDir, "avatar/b.bin").writeBytes(avatar)
        File(context.filesDir, "card_data.json").writeText("""{"c1":"{}"}""")

        val exported = exportNdJsonLines()
        assertTrue("La exportación debe tener una línea de archivo binario", exported.any { it.contains("\"binaryFile\"") })
        assertTrue("La exportación debe tener la línea fileStore con card_data.json", exported.any { it.contains("card_data.json") })

        // Borrar archivos fuente,Simule el directorio vacío después de cambiar de máquina.
        File(context.filesDir, "note_attachments").deleteRecursively()
        File(context.filesDir, "avatar").deleteRecursively()
        File(context.filesDir, "card_data.json").delete()

        invokeNdJsonRecovery(service, exported.asSequence())

        assertArrayEquals(attachment, File(context.filesDir, "note_attachments/a.txt").readBytes())
        assertArrayEquals(avatar, File(context.filesDir, "avatar/b.bin").readBytes())
        assertEquals("""{"c1":"{}"}""", File(context.filesDir, "card_data.json").readText())
    }

    // ── herramienta de reflexión ──────────────────────────────────────────────────────────

    private fun newService(): BackupService {
        val settings = mockk<SettingsRepository>(relaxed = true)
        coEvery { settings.exportSettingsSnapshot(any()) } returns emptyMap()
        every { settings.cloudBackupConfigFlow } returns flowOf(CloudBackupConfig())
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        coEvery { sessionRepository.rebuildFtsIndex() } just Runs
        return BackupService(
            db = museDb,
            memoryDb = memoryDb,
            factDb = factDb,
            cloudBackupService = mockk(relaxed = true),
            settings = settings,
            autoBackupLogDao = mockk<AutoBackupLogDao>(relaxed = true),
            sessionRepository = sessionRepository,
            restoreJournal = RestoreJournal(context),
            restoreStagingStore = RestoreStagingStore(context),
            context = context,
            factStore = mockk<FactStore>(relaxed = true),
            factDbProvider = mockk<FactDbProvider>(relaxed = true),
            messageImageStore = imageStore,
        )
    }

    private fun metaLine(sessions: Int, messages: Int, binaryFiles: Int): String = buildJsonObject {
        put("type", "meta")
        put("version", 4)
        put("sessions", sessions)
        put("messages", messages)
        put("binaryFiles", binaryFiles)
    }.toString()

    private fun binaryFileLine(path: String, bytes: ByteArray): String = buildJsonObject {
        put("type", "binaryFile")
        put("path", path)
        put("content", Base64.getEncoder().encodeToString(bytes))
    }.toString()

    private val backupJson = Json { encodeDefaults = true }

    private fun <T> dataLine(type: String, serializer: kotlinx.serialization.KSerializer<T>, value: T): String = buildJsonObject {
        put("type", type)
        put("data", backupJson.encodeToJsonElement(serializer, value))
    }.toString()

    private suspend fun invokeNdJsonRecovery(service: BackupService, lines: Sequence<String>): Pair<Int, Int> =
        suspendCancellableCoroutine { continuation ->
            val method = BackupService::class.java.getDeclaredMethod(
                "applyNdJsonStreamingWithRecovery",
                Sequence::class.java,
                Continuation::class.java,
            ).apply { isAccessible = true }
            try {
                val result = method.invoke(service, lines, continuation)
                if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as Pair<Int, Int>)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    private suspend fun callSuspend(service: BackupService, name: String, argTypes: Array<Class<*>>, args: Array<Any>): Any? =
        suspendCancellableCoroutine { continuation ->
            val method = BackupService::class.java.getDeclaredMethod(name, *argTypes, Continuation::class.java)
                .apply { isAccessible = true }
            try {
                val result = method.invoke(service, *args, continuation)
                if (result !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) continuation.resume(result)
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    /**
     * llamada a la reflexión `writeNdJson` y recoger las líneas escritas.
     *
     * No puede utilizar la continuación de suspendCancellableCoroutine para pasarla directamente al método de destino.:
     * Una vez suspendido el método de destino, se reanudará utilizando NdJsonExportSummary,y nuestro continuation
     * El tipo de recuperación es List<String>,ClassCastException ocurrirá al reanudar.
     * Aquí se utiliza una continuación interna separada para recibir el resultado objetivo.,Restaura la capa exterior después de la conversión.
     */
    private suspend fun exportNdJsonLines(): List<String> = suspendCancellableCoroutine { outer ->
        val method = BackupService::class.java.getDeclaredMethod(
            "writeNdJson",
            java.io.BufferedWriter::class.java,
            Boolean::class.javaPrimitiveType,
            Continuation::class.java,
        ).apply { isAccessible = true }
        val sink = java.io.StringWriter()
        val writer = java.io.BufferedWriter(sink)
        val inner = object : Continuation<Any?> {
            override val context: kotlin.coroutines.CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Any?>) {
                result.onSuccess {
                    writer.flush()
                    outer.resume(sink.toString().lines().filter { it.isNotBlank() })
                }.onFailure { outer.resumeWithException(it) }
            }
        }
        try {
            method.invoke(service, writer, false, inner)
        } catch (error: InvocationTargetException) {
            outer.resumeWithException(error.targetException)
        } catch (error: Throwable) {
            outer.resumeWithException(error)
        }
    }
}
