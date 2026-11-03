package io.zer0.memory.compile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.zer0.ai.core.Model
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.summary.MemoryDb
import io.zer0.memory.summary.ScopedCompiledSectionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Rastreo de subcadena del resumen compilado.
 *
 * Cobertura: los párrafos en prosa se pueden clasificar por palabra, de qué párrafo proviene el resultado, ordenarlos por el número de palabras destacadas,facts Los párrafos también participan en
 * Y las consultas de agujeros no producen resultados.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CompiledSectionSearchTest {

    private lateinit var db: MemoryDb
    private lateinit var compiler: MemoryCompiler

    /** El proceso de compilación no ajusta LLM en esta prueba, solo proporciona una implementación vacía. */
    private class NoLlm : MemoryLlmClient {
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
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        compiler = MemoryCompiler(
            sectionDao = db.compiledSectionDao(),
            llmClient = NoLlm(),
            scopedSectionDao = db.scopedCompiledSectionDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun writeSection(key: String, content: String, scope: String = "main", spaceId: String = "default") {
        db.scopedCompiledSectionDao().upsert(
            ScopedCompiledSectionEntity(
                sectionKey = key,
                scope = scope,
                spaceId = spaceId,
                content = content,
                fingerprint = null,
                updatedAt = Instant.now().toString(),
            ),
        )
    }

    @Test
    fun `scanned hits carry the section they came from`() = runTest {
        writeSection("today", "- Ziqi fue de excursión a las montañas hoy.\n- Acabo de comprar un bastón de senderismo nuevo.")

        val hits = compiler.searchCompiledSections("Montañismo Ziqi", "main", "default", limit = 10)

        assertEquals("Ambas líneas deberían chocar: $hits", 2, hits.size)
        assertTrue("Las visitas deben marcar el segmento en el que se encuentran.: $hits", hits.all { it.sectionKey == "today" })
        assertEquals("Las líneas con más palabras exitosas ocupan el primer lugar.", "Ziqi fue de excursión a las montañas hoy.", hits.first().text)
    }

    @Test
    fun `facts section participates in the scan`() = runTest {
        writeSection("facts", "- Ziqi es un desarrollador independiente")
        writeSection("week", "- Esta semana estoy ordenando los enlaces de búsqueda del módulo de memoria.")

        val hits = compiler.searchCompiledSections("desarrollador independiente", "main", "default", limit = 10)

        assertEquals(1, hits.size)
        assertEquals("facts", hits.single().sectionKey)
        assertEquals("Ziqi es un desarrollador independiente", hits.single().text)
    }

    @Test
    fun `other scope or space is not scanned`() = runTest {
        writeSection("today", "- Ziqi hizo algo más en otro espacio hoy", scope = "main", spaceId = "work")

        val hits = compiler.searchCompiledSections("Ziqi", "main", "default", limit = 10)

        assertTrue("No deberías leer resúmenes de otros espacios: $hits", hits.isEmpty())
    }

    @Test
    fun `blank query yields no hits`() = runTest {
        writeSection("today", "- Ziqi fue de excursión a las montañas hoy.")

        assertTrue(compiler.searchCompiledSections("   ", "main", "default").isEmpty())
    }

    @Test
    fun `scanner ranks lines by how many words they contain`() {
        val sections = listOf(
            "today" to "- Ziqi está escribiendo código\n- Ziqi es un desarrollador independiente,Normalmente escribo códigos y documentos.",
        )

        val hits = scanCompiledSections(sections, "Documentación del código Ziqi", limit = 10)

        assertEquals(2, hits.size)
        assertTrue("Las líneas que contengan tres palabras deben ir primero: $hits", hits.first().text.contains("Documentación"))
    }
}
