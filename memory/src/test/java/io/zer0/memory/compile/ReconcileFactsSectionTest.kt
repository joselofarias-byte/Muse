package io.zer0.memory.compile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.fact.FactDb
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.CompiledSectionDao
import io.zer0.memory.summary.CompiledSectionEntity
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * v12 (T2-1): Prueba de conciliación de tablas de hechos y productos compilados.
 * Edición de usuario/Después de fusionar los hechos,FACTS section Las filas correspondientes de se reemplazan automáticamente con valores de representación de hechos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReconcileFactsSectionTest {

    private lateinit var memoryDb: MemoryDb
    private lateinit var sectionDao: CompiledSectionDao
    private lateinit var factDb: FactDb
    private lateinit var factStore: FactStore
    private lateinit var compiler: MemoryCompiler
    private lateinit var scopedCompiler: MemoryCompiler

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
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        sectionDao = memoryDb.compiledSectionDao()
        factDb = Room.inMemoryDatabaseBuilder(context, FactDb::class.java)
            .allowMainThreadQueries()
            .build()
        factStore = FactStore(factDb.factDao(), factDb)
        compiler = MemoryCompiler(
            sectionDao = sectionDao,
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = factStore,
        )
        scopedCompiler = MemoryCompiler(
            sectionDao = sectionDao,
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = null,
            scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            compileContext = MemoryCompileContext(
                getScope = { "main" },
                getSpaceId = { "work" },
            ),
        )
    }

    @After
    fun tearDown() {
        memoryDb.close()
        factDb.close()
    }

    @Test
    fun emptyFactStoreClearsExistingFactsSection() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "hechos eliminados",
                fingerprint = "old",
                updatedAt = java.time.Instant.now().toString(),
            ),
        )

        val noStoreCompiler = MemoryCompiler(
            sectionDao = sectionDao,
            llmClient = NoopLlm(),
            fileWriter = null,
            factStore = null,
        )
        val changed = noStoreCompiler.reconcileFactsSectionWithStore(emptyList())

        assertEquals("Una tabla de hechos vacía debería borrar el antiguo segmento FACTS.", 1, changed)
        assertEquals("", noStoreCompiler.readSection(MemoryCompiler.Section.FACTS))
    }

    @Test
    fun `scoped compiled sections do not leak between spaces`() = runTest {
        val scopedDao = memoryDb.scopedCompiledSectionDao()
        scopedDao.upsert(
            ScopedCompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                scope = "main",
                spaceId = "work",
                content = "work-only",
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        scopedDao.upsert(
            ScopedCompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                scope = "main",
                spaceId = "life",
                content = "life-only",
                updatedAt = java.time.Instant.now().toString(),
            ),
        )

        assertEquals("work-only", scopedCompiler.readSection(MemoryCompiler.Section.FACTS))
        assertEquals("", scopedCompiler.readSection(MemoryCompiler.Section.TODAY))
    }

    @Test
    fun `reading scoped compiled memory writes its file to the scoped directory`() = runTest {
        val root = Files.createTempDirectory("muse-scoped-memory").toFile()
        try {
            val target = MemoryCompileTarget(assistantId = "assistant-b", scope = "assistant-b", spaceId = "work")
            memoryDb.scopedCompiledSectionDao().upsert(
                ScopedCompiledSectionEntity(
                    sectionKey = MemoryCompiler.Section.FACTS.key,
                    scope = target.normalizedScope,
                    spaceId = target.normalizedSpaceId,
                    content = "assistant-b private fact",
                    updatedAt = java.time.Instant.now().toString(),
                ),
            )
            val writer = MemoryFileWriter(root)
            val isolatedCompiler = MemoryCompiler(
                sectionDao = sectionDao,
                llmClient = NoopLlm(),
                fileWriter = writer,
                scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
            )

            val rendered = isolatedCompiler.readCompiledMemoryMarkdown(target = target)

            assertTrue(rendered.contains("assistant-b private fact"))
            assertNull("scoped render must not overwrite shared root memory.md", writer.readMemoryMd())
            assertEquals(rendered, writer.readMemoryMd(target))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `assistant scoped tombstone prevents stale compiled fact resurrection`() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val assistantId = "tombstone-test-${System.nanoTime()}"
        val databaseName = "facts_$assistantId.db"
        context.deleteDatabase(databaseName)
        val provider = FactDbProvider(context)
        try {
            val childStore = provider.getFactStore(assistantId)
            val deletedId = childStore.add(
                FactStore.Fact(fact = "deleted child-only preference"),
                scope = assistantId,
                spaceId = "work",
            )
            assertTrue(childStore.delete(deletedId, assistantId, assistantId, "work"))

            val target = MemoryCompileTarget(assistantId = assistantId, scope = assistantId, spaceId = "work")
            memoryDb.scopedCompiledSectionDao().upsert(
                ScopedCompiledSectionEntity(
                    sectionKey = MemoryCompiler.Section.FACTS.key,
                    scope = assistantId,
                    spaceId = "work",
                    content = "deleted child-only preference",
                    updatedAt = java.time.Instant.now().toString(),
                ),
            )
            val providerCompiler = MemoryCompiler(
                sectionDao = sectionDao,
                llmClient = NoopLlm(),
                fileWriter = null,
                factStore = factStore,
                scopedSectionDao = memoryDb.scopedCompiledSectionDao(),
                factDbProvider = provider,
            )

            val changed = providerCompiler.reconcileFactsSectionWithStore(emptyList(), target)

            assertEquals(1, changed)
            assertEquals("", providerCompiler.readSection(MemoryCompiler.Section.FACTS, target))
            assertTrue(childStore.getByScopeAndSpace(assistantId, "work").isEmpty())
            assertTrue(
                "deleted child facts must not be absorbed into the default store",
                factStore.getByScopeAndSpace("main", "work").isEmpty(),
            )
        } finally {
            provider.release(assistantId)
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `edited fact projects table value and absorbs orphan lines`() = runTest {
        // Productos compilados preestablecidos(Simular resultados de compilación de LLM)
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "Usuario le gusta tomar café americano.\nEl usuario se está preparando actualmente para moverse.",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        // facts Este hecho en la tabla ha sido editado por el usuario en variaciones de redacción.(Ir al tema+Diferencia total de la mitad del ancho,Equivalente después de la normalización)
        factStore.add(FactStore.Fact(fact = "le gusta tomar café americano.", entityKey = "usuario"))

        val changed = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        assertEquals("Debería haber actualizaciones", 1, changed)
        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("Los productos deben contener nuevas expresiones para los valores de la tabla.", content.contains("le gusta tomar café americano."))
        assertTrue("Las líneas de asunto antiguas deben reemplazarse por proyecciones.", !content.contains("Usuario le gusta tomar café americano."))
        // D3-P2: Fila de huérfanos("Preparándose para mudarse")absorbido en la mesa primero,Entonces guárdalo con la proyección.(Prevención de pérdidas)
        assertTrue("Las filas huérfanas deben absorberse y conservarse", content.contains("El usuario se está preparando actualmente para moverse."))
    }

    @Test
    fun `full width variant replaced by normalized match`() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "usuario le gusta ｚｈａｎｇｓａｎ",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        // facts Valor de presentación como variante de ancho completo
        factStore.add(FactStore.Fact(fact = "usuario le gusta zhangsan"))

        val changed = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))
        assertEquals("Las variantes de ancho medio completo deberían activar actualizaciones de proyección", 1, changed)
        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("El producto debe tener un valor de tabla(medio tamaño)", content.contains("usuario le gusta zhangsan"))
        assertTrue("Las variantes de ancho completo deben reemplazarse", !content.contains("ｚｈａｎｇｓａｎ"))
    }

    @Test
    fun `unmatched orphan line is absorbed then projected with table facts`() = runTest {
        sectionDao.upsert(
            CompiledSectionEntity(
                sectionKey = MemoryCompiler.Section.FACTS.key,
                content = "A los usuarios les gusta la fotografía.",
                fingerprint = null,
                updatedAt = java.time.Instant.now().toString(),
            ),
        )
        factStore.add(FactStore.Fact(fact = "A los usuarios les gusta tomar té."))

        val changed = compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        assertEquals("absorber+La proyección debería provocar una actualización.", 1, changed)
        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("Los hechos en la tabla deben estar en", content.contains("A los usuarios les gusta tomar té."))
        assertTrue("Las filas huérfanas deben absorberse y conservarse(ya no estoy perdido)", content.contains("A los usuarios les gusta la fotografía."))
    }

    /**
     * v18: Proyectar significa inyectar contenido —— Apareció(puntuación por debajo del umbral)Ni los recuerdos antiguos ni los que han sido reemplazados se escriben en la sección HECHOS.,
     * Pero la fila todavía está en la biblioteca.(página de memoria visible).
     */
    @Test
    fun `projection skips facts that left recall`() = runTest {
        factStore.add(FactStore.Fact(fact = "A los usuarios les gusta el café americano", source = "user_explicit"))
        factDb.factDao().insert(
            io.zer0.memory.fact.FactEntity(
                fact = "El usuario solía beber moca.",
                tags = "[]",
                createdAt = java.time.Instant.now().toString(),
                importance = 0,
                suppressedAt = java.time.Instant.now().toString(),
            ),
        )

        compiler.reconcileFactsSectionWithStore(factStore.getByScopeAndSpace("main", "default"))

        val content = compiler.readSection(MemoryCompiler.Section.FACTS)
        assertTrue("La memoria de la presencia debe ser proyectada.", content.contains("americano"))
        assertTrue("Los recuerdos caducados no deben escribirse en el contexto.", !content.contains("moca"))
        assertEquals("Ambos todavía están en la biblioteca.", 2, factStore.getByScopeAndSpace("main", "default").size)
    }
}
