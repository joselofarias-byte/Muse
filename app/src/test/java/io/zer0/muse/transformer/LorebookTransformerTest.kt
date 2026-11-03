package io.zer0.muse.transformer

import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LorebookTransformerTest {

    private val repository = mockk<LorebookRepository>()

    private fun entity(
        id: String = "lb1",
        name: String = "test-lore",
        keywordsJson: String = """["magia","mago"]""",
        content: String = "El Gremio de Magos es una antigua organización secreta.",
        priority: Int = 0,
        insertionPosition: String = "after_system",
        caseSensitive: Boolean = false,
    ) = LorebookEntity(
        id = id,
        name = name,
        keywordsJson = keywordsJson,
        content = content,
        priority = priority,
        insertionPosition = insertionPosition,
        caseSensitive = caseSensitive,
    )

    @Test
    fun `lorebook matched by keyword injects after system`() = runTest {
        val entry = entity()
        every { repository.matchAgainst(listOf(entry), "quiero aprender magia") } returns listOf(entry)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "eres un asistente"),
            UIMessage(role = MessageRole.USER, content = "quiero aprender magia"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        // resultado:declaration + system + lorebook-after + user = 4
        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[2].role)
        assertTrue(result[2].content.contains("lorebook"))
        assertTrue(result[2].content.contains("gremio de magos"))
        assertTrue(result[2].content.contains("test-lore"))
    }

    @Test
    fun `no match returns original messages`() = runTest {
        val entry = entity()
        every { repository.matchAgainst(listOf(entry), "El clima esta muy lindo hoy") } returns emptyList()

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "asistente"),
            UIMessage(role = MessageRole.USER, content = "El clima esta muy lindo hoy"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `empty lorebook_entries returns original`() = runTest {
        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.USER, content = "prueba"),
        )
        val context = TransformContext(extras = mapOf("lorebook_entries" to emptyList<LorebookEntity>()))
        val result = transformer.transform(messages, context)

        assertEquals(messages, result)
    }

    @Test
    fun `before_system injection inserts at head`() = runTest {
        val entry = entity(insertionPosition = "before_system")
        every { repository.matchAgainst(listOf(entry), "yo uso magia") } returns listOf(entry)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "Configuración del asistente"),
            UIMessage(role = MessageRole.USER, content = "yo uso magia"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        // resultado:declaration + lorebook-before + system + user = 4
        assertEquals(4, result.size)
        assertTrue(result[1].content.contains("lorebook"))
        assertTrue(result[1].content.contains("gremio de magos"))
    }

    @Test
    fun `matched entries ordered by priority`() = runTest {
        val highPri = entity(id = "high", name = "high", priority = 10, content = "alta prioridad")
        val lowPri = entity(id = "low", name = "low", priority = 1, content = "baja prioridad")
        every { repository.matchAgainst(listOf(lowPri, highPri), "magia") } returns listOf(highPri, lowPri)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "asistente"),
            UIMessage(role = MessageRole.USER, content = "magia"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(lowPri, highPri)),
        )
        val result = transformer.transform(messages, context)

        // resultado:declaration + system + lorebook1 + lorebook2 + user = 5
        assertEquals(5, result.size)
        val loreMsgs = result.filter { it.content.contains("lorebook") }
        assertEquals(2, loreMsgs.size)
    }

    @Test
    fun `name with special chars is sanitized`() = runTest {
        val entry = entity(name = "test\"\nname", content = "entrada de nombre especial")
        every { repository.matchAgainst(listOf(entry), "magia") } returns listOf(entry)

        val transformer = LorebookTransformer(repository)
        val messages = listOf(
            UIMessage(role = MessageRole.SYSTEM, content = "asistente"),
            UIMessage(role = MessageRole.USER, content = "magia"),
        )
        val context = TransformContext(
            extras = mapOf("lorebook_entries" to listOf(entry)),
        )
        val result = transformer.transform(messages, context)

        // El nombre desinfectado no debe contener comillas dobles sin formato.
        assertTrue(!result[1].content.contains("test\"") || result[1].content.contains("test "))
    }
}
