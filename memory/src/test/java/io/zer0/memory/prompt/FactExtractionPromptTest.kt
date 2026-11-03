package io.zer0.memory.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

class FactExtractionPromptTest {

    @Test
    fun promptExcludesAssistantVibeAndCommitmentsFromUserFacts() {
        val prompt = FactExtractionPrompt.buildSystemPrompt(locale = "es")

        assertTrue(prompt.contains("vibración del asistente"))
        assertTrue(prompt.contains("compromisos del asistente"))
        assertTrue(prompt.contains("datos del usuario"))
    }
}
