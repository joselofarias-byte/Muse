package io.zer0.muse.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.session.MessageEntity
import io.zer0.muse.data.session.MessageImageStore
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.stats.AutoBackupLogDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * La protección en el lado de la recuperación y en el lado de la migración tiene el mismo calibre: las conversaciones de mensajes privados en teléfonos celulares antiguos no se ingresan en la base de datos.
 *
 * 121→122 La migración ya ha movido la biblioteca `isMiniPhone = 1` Las conversaciones se eliminan junto con los mensajes y la lista principal ya no los filtra;
 * Si la ruta de recuperación vuelve a escribir estas líneas en la copia de seguridad anterior, el usuario verá un montón de"Conversaciones que aparecen de la nada".
 * Aquí se utilizan dos rutas de recuperación reales (NDJSON Verificación de Streaming/JSON único): filtrado antes de escribir, las sesiones ordinarias no se ven afectadas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupRestoreSkipsMiniPhoneTest {

    private lateinit var context: Context
    private lateinit var museDb: MuseDb
    private lateinit var memoryDb: MemoryDb
    private lateinit var factDb: FactDb
    private lateinit var imageStore: MessageImageStore
    private lateinit var tempDir: File

    private val normalSession = SessionEntity(id = "s-normal", title = "conversación normal", createdAt = 1L, updatedAt = 1L)
    private val miniSession = SessionEntity(
        id = "s-mini",
        title = "Mensaje privado del viejo celular pequeño",
        createdAt = 1L,
        updatedAt = 1L,
        isMiniPhone = true,
    )
    private val normalMessage = MessageEntity(
        id = "m-normal",
        sessionId = "s-normal",
        role = "USER",
        content = "Mensajes de conversación generales",
        createdAt = 1L,
    )
    private val miniMessage = MessageEntity(
        id = "m-mini",
        sessionId = "s-mini",
        role = "USER",
        content = "Mensajes de mensajes privados en teléfonos celulares pequeños y antiguos.",
        createdAt = 1L,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
        museDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .allowMainThreadQueries()
            .build()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        tempDir = Files.createTempDirectory("backup-miniphone-restore").toFile()
        imageStore = MessageImageStore(tempDir)
    }

    @After
    fun tearDown() {
        museDb.close()
        memoryDb.close()
        factDb.close()
        tempDir.deleteRecursively()
        File(context.filesDir, "restore-journal.json").delete()
        File(context.filesDir, "restore-staging").deleteRecursively()
    }

    @Test
    fun `NDJSON La recuperación omite pequeñas conversaciones telefónicas antiguas y sus mensajes.`() = runBlocking {
        val service = newService()

        invokeNdJsonRecovery(
            service,
            sequenceOf(
                // meta Lo que se declara es el número original de entradas en la copia de seguridad.(2/2), el almacenamiento real después de la recuperación debe ser 1/1
                metaLine(sessions = 2, messages = 2),
                dataLine("session", SessionEntity.serializer(), normalSession),
                dataLine("session", SessionEntity.serializer(), miniSession),
                dataLine("message", MessageEntity.serializer(), normalMessage),
                dataLine("message", MessageEntity.serializer(), miniMessage),
            ),
        )

        assertOnlyNormalSessionRestored()
    }

    @Test
    fun `La restauración de la copia de seguridad JSON única omite las pequeñas sesiones telefónicas antiguas con sus mensajes`() = runBlocking {
        val service = newService()
        val backup = BackupService.Backup(
            exportedAt = 1L,
            sessions = listOf(normalSession, miniSession),
            messages = listOf(normalMessage, miniMessage),
        )

        invokeApplyBackupInternal(service, backup)

        assertOnlyNormalSessionRestored()
    }

    private suspend fun assertOnlyNormalSessionRestored() {
        assertEquals(
            "Solo se deben restaurar las conversaciones normales, no se permite ingresar a la base de datos conversaciones de mensajes privados en teléfonos celulares antiguos.",
            listOf("s-normal"),
            museDb.sessionDao().observeAll().first().map { it.id },
        )
        assertEquals(
            "Los mensajes de sesiones normales deben restaurarse intactos.",
            "Mensajes de conversación generales",
            museDb.messageDao().getByMessageId("m-normal")?.content,
        )
        assertNull(
            "No se permite ingresar a la base de datos mensajes de pequeñas conversaciones antiguas por teléfono celular",
            museDb.messageDao().getByMessageId("m-mini"),
        )
    }

    // ── accesorio ────────────────────────────────────────────────────────────

    private fun newService(): BackupService {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.cloudBackupConfigFlow } returns flowOf(CloudBackupConfig())
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
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

    private fun metaLine(sessions: Int, messages: Int): String = buildJsonObject {
        put("type", "meta")
        put("version", 4)
        put("sessions", sessions)
        put("messages", messages)
    }.toString()

    private val backupJson = Json { encodeDefaults = true }

    private fun <T> dataLine(type: String, serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
        buildJsonObject {
            put("type", type)
            put("data", backupJson.encodeToJsonElement(serializer, value))
        }.toString()

    /** Tome la entrada de recuperación real de NDJSON (incluido el libro de recuperación y el paquete de reversión). */
    private suspend fun invokeNdJsonRecovery(service: BackupService, lines: Sequence<String>): Pair<Int, Int> =
        suspendCoroutine { continuation ->
            val method = BackupService::class.java.getDeclaredMethod(
                "applyNdJsonStreamingWithRecovery",
                Sequence::class.java,
                Continuation::class.java,
            ).apply { isAccessible = true }
            try {
                val result = method.invoke(service, lines, continuation)
                if (result !== COROUTINE_SUSPENDED) {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as Pair<Int, Int>)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    /** Tome la ruta de recuperación JSON única y real. */
    private suspend fun invokeApplyBackupInternal(service: BackupService, backup: BackupService.Backup): Pair<Int, Int> =
        suspendCoroutine { continuation ->
            val method = BackupService::class.java.getDeclaredMethod(
                "applyBackupInternal",
                BackupService.Backup::class.java,
                Continuation::class.java,
            ).apply { isAccessible = true }
            try {
                val result = method.invoke(service, backup, continuation)
                if (result !== COROUTINE_SUSPENDED) {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(result as Pair<Int, Int>)
                }
            } catch (error: InvocationTargetException) {
                continuation.resumeWithException(error.targetException)
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
}
