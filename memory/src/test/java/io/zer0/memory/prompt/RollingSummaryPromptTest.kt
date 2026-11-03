package io.zer0.memory.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

class RollingSummaryPromptTest {

    @Test
    fun promptKeepsAssistantCommitmentsAndVibeInsideConversationTimeline() {
        val prompt = RollingSummaryPrompt.buildSystemPrompt(locale = "es")

        assertTrue(prompt.contains("El compromiso claro del asistente"))
        assertTrue(prompt.contains("Vibe"))
        assertTrue(prompt.contains("Los sentimientos expresados por el asistente en ese momento."))
        assertTrue(prompt.contains("Sparks"))
        assertTrue(prompt.contains("Reflections"))
        assertTrue(prompt.contains("Will"))
    }
}
