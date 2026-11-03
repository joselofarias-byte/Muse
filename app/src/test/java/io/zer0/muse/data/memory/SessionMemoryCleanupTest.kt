package io.zer0.muse.data.memory

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.compile.MemoryCompileTarget
import io.zer0.memory.compile.MemoryCompiler
import io.zer0.memory.compile.MemoryFileWriter
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.reflection.MemoryContradictionStore
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import io.zer0.memory.summary.SessionSummaryEntity
import io.zer0.muse.data.groupchat.GroupChatMemoryEntity
import io.zer0.muse.data.session.MessageFtsDdl
import io.zer0.muse.data.session.MessageFtsRuntime
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * sesión/Prueba de regresión de limpieza de memoria después de la eliminación del asistente.
 *
 * Cubra tres elementos que se degradan fácilmente en la naturaleza.:
 *  - Eliminar una conversación solo borra el resumen y los hechos de la conversación.,Otras sesiones no se ven afectadas.;
 *  - Limpiar idempotente —— Elimina dos veces sin tirar ni contar repetidamente.;
 *  - Eliminar y escribir una lápida —— La próxima ronda de compilación no tratará el contenido eliminado como"Resumen huérfano"Rellena el formulario.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionMemoryCleanupTest {

    private lateinit var context: Context
    private lateinit var memoryDb: MemoryDb
    private lateinit var museDb: MuseDb
    private lateinit var factProvider: FactDbProvider
    private lateinit var filesRoot: File
    private lateinit var contradictions: MemoryContradictionStore
    private lateinit var cleanup: SessionMemoryCleanup
    private var previousFtsMode: Boolean = false

    private class NoopLlm : MemoryLlmClient {
        override suspend fun callText(
            systemPrompt: String,
            userContent: String,
            model: Model?,
            temperature: Float,
            maxTokens: Int,
            timeoutMs: Long,
        ): String = ""
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        previousFtsMode = MessageFtsRuntime.useFts5
        MessageFtsRuntime.useFts5 = false
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        museDb = Room.inMemoryDatabaseBuilder(context, MuseDb::class.java)
            .addCallback(
                object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(MessageFtsDdl.createSql(useFts5 = false))
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
            )
            .build()
        factProvider = FactDbProvider(context)
        filesRoot = File(context.cacheDir, "memory-cleanup-${UUID.randomUUID()}")
        contradictions = MemoryContradictionStore(File(filesRoot, "memory_contradictions.json"))
        cleanup = SessionMemoryCleanup(
            summaryDao = memoryDb.sessionSummaryDao(),
            checkpointDao = memoryDb.contextCheckpointDao(),
            scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            groupChatMemoryDao = museDb.groupChatMemoryDao(),
            factDbProvider = factProvider,
            fileWriter = MemoryFileWriter(filesRoot),
            contradictionStore = contradictions,
        )
    }

    @After
    fun tearDown() {
        factProvider.releaseAll()
        museDb.close()
        memoryDb.close()
        filesRoot.deleteRecursively()
        MessageFtsRuntime.useFts5 = previousFtsMode
    }

    @Test
    fun `cleanSessions drops only the deleted session summaries and facts`() = runTest {
        val deleted = "session-deleted"
        val kept = "session-kept"
        memoryDb.sessionSummaryDao().upsert(summary(deleted, assistantId = "default"))
        memoryDb.sessionSummaryDao().upsert(summary(kept, assistantId = "default"))

        // Dos entradas en la base de datos principal + una en la base de datos de la sucursal pertenecen a sesiones eliminadas; el otro en la base de datos de la sucursal pertenece a sesiones reservadas.
        val mainStore = factProvider.getFactStore("default")
        val subStore = factProvider.getFactStore("assistant_a")
        mainStore.add(FactStore.Fact(fact = "A los usuarios les gusta el tema azul", sessionId = deleted), scope = "main")
        mainStore.add(FactStore.Fact(fact = "El usuario tiene examen el próximo miércoles", sessionId = deleted), scope = "main")
        subStore.add(FactStore.Fact(fact = "Preferencias anotadas por el subasistente", sessionId = deleted), scope = "assistant_a")
        subStore.add(FactStore.Fact(fact = "Preservar datos de la sesión", sessionId = kept), scope = "assistant_a")

        cleanup.cleanSessions(listOf(deleted))

        assertEquals("Las conversaciones eliminadas no pueden salir de resúmenes", 1, memoryDb.sessionSummaryDao().getAll().size)
        assertEquals(kept, memoryDb.sessionSummaryDao().getAll().single().sessionId)
        assertEquals("Se deben borrar los datos de la sesión en la base de datos principal.", 0, mainStore.getBySession(deleted).size)
        assertEquals("También se deberán borrar los hechos de la sesión en la base de datos de la sucursal.", 0, subStore.getBySession(deleted).size)
        assertEquals("Los hechos de otras conversaciones no se ven afectados.", 1, subStore.getBySession(kept).size)
    }

    @Test
    fun `cleanSessions is idempotent`() = runTest {
        val sessionId = "session-twice"
        memoryDb.sessionSummaryDao().upsert(summary(sessionId, assistantId = "default"))
        val store = factProvider.getFactStore("default")
        store.add(FactStore.Fact(fact = "El usuario tiene un gato llamado Tuanzi.", sessionId = sessionId), scope = "main")
        store.add(FactStore.Fact(fact = "Los usuarios se despiertan a las siete de la mañana todos los días.", sessionId = sessionId), scope = "main")

        cleanup.cleanSessions(listOf(sessionId))
        val tombstonesAfterFirst = store.getTombstones("main", "default").size
        // La segunda llamada no debe generar una excepción.,ni contar/Voltea la lápida otra vez.
        cleanup.cleanSessions(listOf(sessionId))

        assertEquals(0, store.getBySession(sessionId).size)
        assertEquals("La limpieza repetida no debería reescribir las lápidas", tombstonesAfterFirst, store.getTombstones("main", "default").size)
        assertEquals(2, tombstonesAfterFirst)
    }

    @Test
    fun `cleaned facts stay deleted through the next compile pass`() = runTest {
        val sessionId = "session-tombstone"
        val deletedLine = "A los usuarios les gusta el tema azul"
        val target = MemoryCompileTarget(assistantId = "default", scope = "main", spaceId = "default")
        val store = factProvider.getFactStore("default")
        store.add(FactStore.Fact(fact = deletedLine, sessionId = sessionId), scope = "main")
        // Esta línea aún permanece en el producto compilado antes de su eliminación. —— La entrada a la resurrección es exactamente"El contenido del segmento se trata como un resumen huérfano y se vuelve a agregar a la tabla.".
        memoryDb.scopedCompiledSectionDao().upsert(
            ScopedCompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                scope = "main",
                spaceId = "default",
                content = "- $deletedLine",
                updatedAt = "2026-01-01T00:00:00Z",
            ),
        )

        cleanup.cleanSessions(listOf(sessionId))

        assertTrue(
            "Eliminar hechos debe dejar una lápida,De lo contrario, el lado de compilación lo volverá a agregar.",
            store.getTombstones("main", "default").any { it.contains("tema azul") },
        )

        // Simular la siguiente ronda de compilación y conciliación.
        val compiler = MemoryCompiler(
            sectionDao = memoryDb.compiledSectionDao(),
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = store,
            scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            factDbProvider = factProvider,
        )
        compiler.reconcileFactsSectionWithStore(emptyList(), target)

        assertFalse(
            "Los hechos eliminados después de haber sido golpeados por una lápida no se pueden recuperar.",
            store.getAll("main").any { it.fact.contains("tema azul") },
        )
    }

    @Test
    fun `cleanAssistant drops only the target assistant memory`() = runTest {
        val doomed = "assistant_doomed"
        val kept = "assistant_kept"
        memoryDb.sessionSummaryDao().upsert(summary("s-doomed", assistantId = doomed))
        memoryDb.sessionSummaryDao().upsert(summary("s-kept", assistantId = kept))
        memoryDb.scopedCompiledSectionDao().upsert(section(doomed))
        memoryDb.scopedCompiledSectionDao().upsert(section(kept))
        museDb.groupChatMemoryDao().insert(groupChatMemory("g-doomed", doomed))
        museDb.groupChatMemoryDao().insert(groupChatMemory("g-kept", kept))
        contradictions.save(doomed, "default", listOf("viejo dicho" to "nueva declaración"))
        contradictions.save(kept, "default", listOf("declaración reservada" to "otra forma de decir"))

        cleanup.cleanAssistant(doomed)

        assertEquals(
            "Eliminar solo el resumen del asistente de destino.",
            listOf(kept),
            memoryDb.sessionSummaryDao().getAll().map { it.assistantId },
        )
        assertEquals(
            "Eliminar solo los párrafos compilados del alcance de destino.",
            listOf(kept),
            memoryDb.scopedCompiledSectionDao().getAll().map { it.scope },
        )
        assertEquals(
            "Eliminar solo la memoria del chat grupal del asistente objetivo",
            listOf(kept),
            museDb.groupChatMemoryDao().getAll().map { it.assistantId },
        )
        assertTrue("Se debe borrar la lista de contradicciones en el asistente de destino.", contradictions.list(doomed, "default").isEmpty())
        assertTrue("Se deben mantener listas de conflictos de otros asistentes", contradictions.list(kept, "default").isNotEmpty())
    }

    @Test
    fun `cleanAssistant deletes the scope directory but keeps memory md`() = runTest {
        val doomed = "assistant_dir"
        val memoryDir = File(filesRoot, "memory")
        val rootMd = File(memoryDir, "memory.md").apply {
            parentFile?.mkdirs()
            writeText("# memoria principal")
        }
        val doomedDir = File(memoryDir, "$doomed/default").apply {
            mkdirs()
            File(this, "memory.md").writeText("# Se borrará la memoria del asistente")
            File(this, "daily").mkdirs()
            File(this, "daily/2026-01-01.md").writeText("## 2026-01-01\n\nTexto")
        }
        val keptDir = File(memoryDir, "assistant_keep/default").apply {
            mkdirs()
            File(this, "memory.md").writeText("# Preservar la memoria del asistente")
        }

        cleanup.cleanAssistant(doomed)

        assertFalse("El directorio de alcance de destino debe eliminarse", doomedDir.exists())
        assertTrue("Memory.md en la raíz no pertenece a ningún scope,debe ser retenido", rootMd.exists())
        assertTrue("Otros directorios de alcance no se ven afectados", keptDir.exists())
    }

    private fun summary(sessionId: String, assistantId: String) = SessionSummaryEntity(
        sessionId = sessionId,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        summary = "### hechos importantes\n- A los usuarios les gusta el tema azul",
        assistantId = assistantId,
    )

    private fun section(assistantId: String) = ScopedCompiledSectionEntity(
        sectionKey = MemoryCompiler.Section.FACTS.key,
        scope = assistantId,
        spaceId = "default",
        content = "- un hecho",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun groupChatMemory(id: String, assistantId: String) = GroupChatMemoryEntity(
        id = id,
        groupChatId = "group-1",
        assistantId = assistantId,
        summary = "Cosas discutidas en el chat grupal.",
    )
}
