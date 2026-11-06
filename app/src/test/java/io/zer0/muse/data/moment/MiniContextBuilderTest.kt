package io.zer0.muse.data.moment

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.UserProfile
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.lorebook.LorebookEntity
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionEntity
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.transformer.SystemPromptAssembler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 Regresión: la generación de omisión, como pequeños teléfonos celulares/momentos/diarios, debe pasar por el enlace de generación completo.
 *
 * Defectos antes de solucionarlos: estos escenarios solo hacen `"tu eres $name" + Materiales` Tres secciones de empalme, bypass.
 * [SystemPromptAssembler], lo que hace que el libro mundial/la memoria/el retrato del usuario/la inyección rápida de palabras no surta efecto.
 * Esta prueba está bloqueada"De hecho, las entradas del Libro Mundial se inyectan en el mensaje final."Esta invariante.
 */
class MiniContextBuilderTest {

    private val assembler = mockk<SystemPromptAssembler>()
    private val lorebookRepository = mockk<LorebookRepository>(relaxed = true)
    private val promptInjectionRepository = mockk<PromptInjectionRepository>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private fun builder() = MiniContextBuilder(
        systemPromptAssembler = assembler,
        lorebookRepository = lorebookRepository,
        promptInjectionRepository = promptInjectionRepository,
        settingsRepository = settingsRepository,
    )

    private fun assistant(lorebookIds: String = """["lb1"]""", injectionIds: String = "[]", memoryEnabled: Boolean = true) =
        AssistantEntity(
            id = "asst-1",
            name = "Muse",
            systemPrompt = "Eres un personaje gentil.",
            memoryEnabled = memoryEnabled,
            lorebookIdsJson = lorebookIds,
            modeInjectionIdsJson = injectionIds,
        )

    private fun lorebookEntry() = LorebookEntity(
        id = "lb1",
        name = "gremio de magos",
        keywordsJson = """["magia"]""",
        content = "El Gremio de Magos es una antigua organización secreta.",
    )

    @Test
    fun `buildMessages Utilice SystemPromptAssembler en lugar de personalidad desnuda`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "Aviso completo del sistema"))

        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        val result = builder().buildMessages(assistant(), "Quiero enviar un mensaje a Momentos")

        // El mensaje del sistema proviene de Assembler, no de su propia ortografía."tu eres XX"
        assertTrue(
            "Las indicaciones del sistema deben provenir de SystemPromptAssembler",
            result.any { it.role == MessageRole.SYSTEM && it.content == "Aviso completo del sistema" },
        )
        // El último elemento es material de usuario.
        assertEquals(MessageRole.USER, result.last().role)
        assertTrue(result.last().content.contains("Momentos"))
    }

    @Test
    fun `La palabra clave del libro mundial vinculada por el asistente se inyecta en la etiqueta del libro de historia después de presionarla`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "Aviso del sistema"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        val entry = lorebookEntry()
        coEvery { lorebookRepository.getByIdsEnabled(listOf("lb1")) } returns listOf(entry)
        // matchAgainst Es una función pura y devuelve directamente el resultado del hit.
        every { lorebookRepository.matchAgainst(any(), any()) } returns listOf(entry)

        val result = builder().buildMessages(assistant(), "Hoy quiero hablar de magia.")

        val joined = result.joinToString("\n") { it.content }
        assertTrue(
            "Las entradas del Libro Mundial deben insertarse en la lista de mensajes.\ncontenido real:\n$joined",
            joined.contains("<lorebook") && joined.contains("El Gremio de Magos es una antigua organización secreta."),
        )
        coVerify { lorebookRepository.getByIdsEnabled(listOf("lb1")) }
    }

    @Test
    fun `Cuando el libro mundial no está encuadernado, las entradas no se consultarán ni se inyectarán`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "Aviso del sistema"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "solo di algo")

        val joined = result.joinToString("\n") { it.content }
        assertTrue("La etiqueta del libro de historia no debería aparecer cuando el libro mundial no está encuadernado.", !joined.contains("<lorebook"))
        coVerify(exactly = 0) { lorebookRepository.getByIdsEnabled(any()) }
    }

    @Test
    fun `También se inyectarán entradas de inyección de palabras rápidas`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "Aviso del sistema"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()
        coEvery { lorebookRepository.getByIdsEnabled(any()) } returns emptyList()
        val injection = PromptInjectionEntity(
            id = "inj1",
            name = "restricciones de estilo",
            content = "Las respuestas deben ser breves.",
            mode = "default",
            enabled = true,
        )
        coEvery { promptInjectionRepository.getByIdsEnabled(listOf("inj1")) } returns listOf(injection)

        val result = builder().buildMessages(
            assistant(lorebookIds = "[]", injectionIds = """["inj1"]"""),
            "escribe algo",
        )

        val joined = result.joinToString("\n") { it.content }
        assertTrue("La inyección rápida de palabras debe surtir efecto\ncontenido real:\n$joined", joined.contains("Las respuestas deben ser breves."))
    }

    @Test
    fun `El comando del sistema de la ruta del mensaje privado lleva la convención de marca de voz`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "Aviso completo del sistema"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "¿Está ahí?", voiceMessageEnabled = true)

        val systemText = result.filter { it.role == MessageRole.SYSTEM }.joinToString("\n") { it.content }
        assertTrue("El mensaje privado debe enviar un acuerdo de voz.\ncontenido real:\n$systemText", systemText.contains(voiceMessageInstruction()))
        assertTrue("Las marcas deben escribirse claramente.", systemText.contains("[Voz]") && systemText.contains("[/Voz]"))
        assertEquals("El acuerdo se clasifica antes que el material del usuario y el material sigue siendo el último.", MessageRole.USER, result.last().role)
    }

    @Test
    fun `Ruta de momentos sin convención de etiquetas de voz`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } returns listOf(UIMessage(role = MessageRole.SYSTEM, content = "Aviso completo del sistema"))
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "Por favor envía un mensaje a Momentos")

        val systemText = result.filter { it.role == MessageRole.SYSTEM }.joinToString("\n") { it.content }
        assertFalse("No hay exportación de voz en Moments, por lo que no debería haber ninguna marca.\ncontenido real:\n$systemText", systemText.contains("[Voz]"))
        assertFalse(systemText.contains(voiceMessageInstruction()))
    }

    @Test
    fun `Assembler Cuando se produce una excepción, se degrada a un indicador del sistema vacío sin fallar`() = runTest {
        coEvery {
            assembler.build(any(), any(), any(), any(), any())
        } throws IllegalStateException("boom")
        coEvery { settingsRepository.getUserProfile() } returns UserProfile()

        val result = builder().buildMessages(assistant(lorebookIds = "[]"), "contenido")

        // Al menos conservar los mensajes de los usuarios y no fallar en su conjunto.
        assertEquals(MessageRole.USER, result.last().role)
        assertTrue(result.last().content.contains("contenido"))
    }
}
