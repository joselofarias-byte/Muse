package io.zer0.memory.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

class FactExtractionPromptTest {

    @Test
    fun promptExcludesAssistantVibeAndCommitmentsFromUserFacts() {
        val prompt = FactExtractionPrompt.buildSystemPrompt(locale = "es")

        assertTrue(prompt.contains("Léame de Vibe del asistente"))
        assertTrue(prompt.contains("Promesa del asistente"))
        assertTrue(prompt.contains("No es un hecho de usuario"))
    }
}
