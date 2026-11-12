package io.zer0.muse.schedule

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.memory.fact.FactEntity
import io.zer0.muse.R
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class GreetingHintGeneratorLocalizationTest {
    @Test
    fun generation_prompt_explicitly_requests_the_resource_locale() = runTest {
        val resources = mockk<Resources>()
        val configuration = mockk<Configuration>()
        val locales = mockk<LocaleList>()
        every { resources.configuration } returns configuration
        every { configuration.locales } returns locales
        every { locales[0] } returns Locale.forLanguageTag("es")
        every { resources.getString(R.string.greeting_event_exam, *anyVararg()) } returns "examen"
        every { resources.getString(R.string.greeting_tomorrow_event, *anyVararg()) } returns "Mañana: examen"
        every { resources.getString(R.string.greeting_hint_generation_prompt) } returns "Genera un recordatorio en español"
        val service = mockk<ChatService>()
        coEvery {
            service.completeText(messages = any(), temperature = any(), maxTokens = any())
        } returns ChatCompletion(text = "Mañana: examen")
        val facts = listOf(
            FactEntity(
                id = 1L,
                fact = "考试",
                time = LocalDate.now().plusDays(1).toString(),
                createdAt = "2026-08-07T12:00:00",
            ),
        )

        assertEquals("Mañana: examen", GreetingHintGenerator(service).generate(facts, resources))
        coVerify(exactly = 1) {
            service.completeText(
                messages = match { it.first().content.contains("Output language: es.") },
                temperature = any(),
                maxTokens = any(),
            )
        }
    }

    @Test
    fun failed_generation_uses_localized_fallback() = runTest {
        val resources = mockk<Resources>()
        val configuration = mockk<Configuration>()
        val locales = mockk<LocaleList>()
        every { resources.configuration } returns configuration
        every { configuration.locales } returns locales
        every { locales[0] } returns Locale.forLanguageTag("es")
        every { resources.getString(R.string.greeting_event_exam, *anyVararg()) } returns "examen"
        every { resources.getString(R.string.greeting_tomorrow_event, *anyVararg()) } returns "Mañana: examen"
        every { resources.getString(R.string.greeting_hint_generation_prompt) } returns "Genera un recordatorio en español"
        val service = mockk<ChatService>()
        coEvery {
            service.completeText(messages = any(), temperature = any(), maxTokens = any())
        } throws IllegalStateException("offline")
        val facts = listOf(
            FactEntity(
                id = 1L,
                fact = "考试",
                time = LocalDate.now().plusDays(1).toString(),
                createdAt = "2026-08-07T12:00:00",
            ),
        )

        assertEquals("Mañana: examen", GreetingHintGenerator(service).generate(facts, resources))
    }
}
