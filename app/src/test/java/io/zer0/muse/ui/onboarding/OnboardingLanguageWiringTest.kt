package io.zer0.muse.ui.onboarding

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class OnboardingLanguageWiringTest {

    @Test
    fun `Spanish is selected initially regardless of device locale`() {
        val source = onboardingSource()
        assertTrue(source.contains("var selectedLanguage by rememberSaveable { mutableStateOf(\"es\") }"))
        assertTrue(source.contains("selected = selectedLanguage == code"))
    }

    @Test
    fun `Spanish appears first in language options and welcome greetings`() {
        val source = onboardingSource()
        assertTrue(source.contains(".sortedBy { if (it == AppLocales.Language.ES) 0 else 1 }"))
        assertTrue(source.contains("private val WELCOME_GREETINGS = listOf(\n    \"Hola, encantado.\""))
    }

    private fun onboardingSource(): String {
        val relative = "src/main/java/io/zer0/muse/ui/onboarding/OnboardingScreen.kt"
        val path = listOf(Path.of(relative), Path.of("app", relative))
            .firstOrNull(Files::exists)
            ?: error("Unable to locate OnboardingScreen.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
