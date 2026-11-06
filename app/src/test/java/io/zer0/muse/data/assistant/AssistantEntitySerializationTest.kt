package io.zer0.muse.data.assistant

import io.zer0.common.AppJson
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * R-TEST-16: Asistente de serialización de campo completo de ida y vuelta con muestras JSON incorrectas.
 */
class AssistantEntitySerializationTest {

    @Test
    fun `full assistant round trips through json`() {
        val entity = fullAssistant()
        val json = AppJson.encodeToString(AssistantEntity.serializer(), entity)
        val decoded = AppJson.decodeFromString(AssistantEntity.serializer(), json)
        assertEquals(entity, decoded)
    }

    @Test
    fun `malformed json fails parsing`() {
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(AssistantEntity.serializer(), "{broken")
        }
    }

    @Test
    fun `missing required id fails parsing`() {
        val json = """{"name":"ayudante de identificación faltante"}"""
        assertThrows(SerializationException::class.java) {
            AppJson.decodeFromString(AssistantEntity.serializer(), json)
        }
    }

    @Test
    fun `extra unknown fields are ignored`() {
        val json = """{"id":"a1","name":"asistente","futureField":123}"""
        val decoded = AppJson.decodeFromString(AssistantEntity.serializer(), json)
        assertEquals("a1", decoded.id)
        assertEquals("asistente", decoded.name)
    }

    private fun fullAssistant() = AssistantEntity(
        id = "orig-id",
        name = "Asistente de campo completo",
        sortIndex = 3,
        createdAt = 111L,
        updatedAt = 222L,
        modelId = "gpt-test",
        providerId = "openai",
        temperature = 0.7f,
        topP = 0.9f,
        maxTokens = 2048,
        contextMessageSize = 32,
        reasoningLevel = "HIGH",
        systemPrompt = "Eres un asistente de prueba.",
        messageTemplate = "{{content}}",
        presetMessagesJson = """[{"role":"system","content":"hello"}]""",
        toolIdsJson = """["web_search","calculator"]""",
        mcpServerIdsJson = """["server-1"]""",
        streamOutput = false,
        memoryEnabled = true,
        useGlobalMemory = false,
        enableRecentChatsReference = false,
        enableTimeReminder = false,
        avatarEmoji = "🤖",
        avatarImageUrl = "",
        tagsJson = """["prueba","chino"]""",
        capabilitiesJson = """["code","write"]""",
        quickMessageIdsJson = """["q1"]""",
        lorebookIdsJson = """["lb1"]""",
        modeInjectionIdsJson = """["m1"]""",
        skillIdsJson = """["s1"]""",
        toolModelId = "tool-model",
        customHeadersJson = """{"Authorization":"Bearer x"}""",
        customBodiesJson = """{"temperature":0.2}""",
        regexRulesJson = """[{"pattern":"a","replacement":"b"}]""",
        knowledgeBaseIdsJson = """["kb1"]""",
        ragConfigOverride = """{"topK":5}""",
        messageCount = 12,
        lastUsedAt = 333L,
        summary = "Introducción de una frase",
        useAssistantName = true,
        allowGroupChat = false,
    )
}
