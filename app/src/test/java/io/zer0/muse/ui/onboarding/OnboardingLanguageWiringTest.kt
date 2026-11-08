package io.zer0.muse.ui.onboarding

import org.junit.Assert.assertFalse
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
    fun `Spanish appears first in language options and welcome stays Spanish`() {
        val source = onboardingSource()
        assertTrue(source.contains(".sortedBy { if (it == AppLocales.Language.ES) 0 else 1 }"))
        assertTrue(source.contains("private const val WELCOME_GREETING = \"Hola, encantado.\""))
        assertTrue(source.contains("text = WELCOME_GREETING"))
        assertFalse(source.contains("greetingIndex"))
        assertFalse(source.contains("WELCOME_GREETINGS"))
        assertFalse(source.contains("while (true)"))
    }

    private fun onboardingSource(): String {
        val relative = "src/main/java/io/zer0/muse/ui/onboarding/OnboardingScreen.kt"
        val path = listOf(Path.of(relative), Path.of("app", relative))
            .firstOrNull(Files::exists)
            ?: error("Unable to locate OnboardingScreen.kt from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
