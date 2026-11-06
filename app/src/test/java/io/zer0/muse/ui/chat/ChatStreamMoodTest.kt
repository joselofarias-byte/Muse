package io.zer0.muse.ui.chat

import io.mockk.mockk
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.hook.HookRegistry
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.TransformerPipeline
import io.zer0.muse.vision.VisionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Mood Análisis de pruebas de regresión: verifique que la misma entrada de actualización utilizada al final de la transmisión cambie el estado de ánimo del texto principal./canal de razonamiento
 * Sepárelo para garantizar que MessageBubble posterior tenga datos para representar, en lugar de simplemente requerir la salida del modelo en el mensaje.
 */
class ChatStreamMoodTest {

    @Test
    fun `mood is persisted separately and removed from visible content`() {
        val messageId = Uuid.random()
        val accessor = MutableMessagesAccessor(
            listOf(UIMessage(id = messageId, role = MessageRole.ASSISTANT, content = "")),
        )
        val coordinator = coordinator(accessor)

        coordinator.updateAssistant(
            id = messageId,
            content = """
                <mood>
                Vibe: calm
                Sparks: a small idea
                Reflections: keep the answer clear
                Will: help the user
                </mood>
                este es el texto
            """.trimIndent(),
            reasoning = "<think>internal reasoning</think>",
            isStreaming = false,
        )

        val updated = accessor.messages.single()
        assertEquals("este es el texto", updated.content.trim())
        assertNotNull(updated.mood)
        assertTrue(updated.mood!!.contains("Vibe: calm"))
        assertFalse(updated.content.contains("<mood>", ignoreCase = true))
        assertFalse(updated.content.contains("</mood>", ignoreCase = true))
        assertEquals("internal reasoning", updated.reasoning?.trim())
    }

    @Test
    fun `mood in reasoning channel is recovered when content has no mood`() {
        val messageId = Uuid.random()
        val accessor = MutableMessagesAccessor(
            listOf(UIMessage(id = messageId, role = MessageRole.ASSISTANT, content = "")),
        )
        val coordinator = coordinator(accessor)

        coordinator.updateAssistant(
            id = messageId,
            content = "respuesta final",
            reasoning = "<mood>Vibe: thoughtful</mood><think>proceso de análisis</think>",
            isStreaming = false,
        )

        val updated = accessor.messages.single()
        assertEquals("respuesta final", updated.content)
        assertEquals("Vibe: thoughtful", updated.mood?.trim())
        assertEquals("proceso de análisis", updated.reasoning?.trim())
    }

    private fun coordinator(accessor: MutableMessagesAccessor): ChatStreamCoordinator = ChatStreamCoordinator(
        accessor = accessor,
        sessionRepository = mockk(relaxed = true),
        memoryTicker = mockk(relaxed = true),
        checkpointReader = mockk(relaxed = true),
        settings = mockk<SettingsRepository>(relaxed = true),
        appContext = mockk(relaxed = true),
        notificationManager = mockk<MuseNotificationManager>(relaxed = true),
        assistantRepository = mockk<AssistantRepository>(relaxed = true),
        visionBridge = mockk<VisionBridge>(relaxed = true),
        toolRegistry = mockk<ToolRegistry>(relaxed = true),
        skillRepository = mockk<SkillRepository>(relaxed = true),
        idListJson = Json,
        lorebookRepository = mockk<LorebookRepository>(relaxed = true),
        promptInjectionRepository = mockk<PromptInjectionRepository>(relaxed = true),
        transformerPipeline = mockk<TransformerPipeline>(relaxed = true),
        hookRegistry = mockk<HookRegistry>(relaxed = true),
    )

    private class MutableMessagesAccessor(
        initial: List<UIMessage>,
    ) : ChatStateAccessor {
        var messages: List<UIMessage> = initial

        override val snapshot: io.zer0.muse.ui.ChatUiState = io.zer0.muse.ui.ChatUiState()
        override val messagesSnapshot: List<UIMessage> get() = messages
        override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

        override fun update(transform: (io.zer0.muse.ui.ChatUiState) -> io.zer0.muse.ui.ChatUiState) = Unit

        override fun updateMessages(transform: (List<UIMessage>) -> List<UIMessage>) {
            messages = transform(messages)
        }
    }
}
