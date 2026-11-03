package io.zer0.muse.data.miniphone

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatCompletion
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.ToolCall
import io.zer0.ai.core.ToolDefinition
import io.zer0.ai.core.UIMessage
import io.zer0.muse.miniphone.api.MpActivityKind
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.tools.ToolCategories
import io.zer0.muse.tools.ToolCategory
import io.zer0.muse.tools.ToolPermissionResolver
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las barreras de comportamiento generadas por la ronda de herramientas en mensajes privados en pequeños teléfonos celulares.
 *
 * La entrada generada y el registro de herramientas se implementan con falsos:Sin modelos, sin herramientas para correr,Pruebe únicamente "qué herramientas se pueden proporcionar",
 * "Cómo rellenar los resultados de la herramienta" y "Cómo cerrar rondas y tiempos de espera" son tres cosas.
 */
class MiniPhoneToolTurnRunnerTest {

    @Test
    fun `La rueda de herramientas envía la actividad primero y luego el texto y completa el resultado en un mensaje de HERRAMIENTA.`() = runBlocking {
        val chatService = mockk<ChatService>()
        val registry = registryWith(def("quick_note_list"))
        coEvery { registry.executeFromJson("quick_note_list", "{}") } returns "hay una nota"
        val sent = stubCompletions(chatService) { index, _ ->
            if (index == 1) {
                ChatCompletion(
                    text = "",
                    toolCalls = listOf(ToolCall(id = "call-1", name = "quick_note_list", arguments = "{}")),
                )
            } else {
                ChatCompletion(text = " Hay una nota hoy ")
            }
        }

        val events = runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals(listOf(MpReplyEvent.Activity(MpActivityKind.NOTES), MpReplyEvent.Text("Hay una nota hoy")), events)
        coVerify(exactly = 1) { registry.executeFromJson("quick_note_list", "{}") }

        val backfilled = sent.messages[1].last()
        assertEquals(MessageRole.TOOL, backfilled.role)
        assertEquals("hay una nota", backfilled.content)
        assertEquals("call-1", backfilled.toolCallId)
    }

    @Test
    fun `Las herramientas de categorías prohibidas no aparecerán en las solicitudes`() = runBlocking {
        val chatService = mockk<ChatService>()
        val registry = registryWith(def("execute_python_script"), def("get_weather"))
        val sent = stubCompletions(chatService) { _, _ -> ChatCompletion(text = "esta soleado hoy") }

        runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals(listOf("get_weather"), sent.tools.first().orEmpty().map { it.name })
    }

    @Test
    fun `Las herramientas de alto riesgo no aparecerán incluso si la categoría está en la lista blanca`() = runBlocking {
        // afirmación de premisa:De hecho, estos dos nombres están en la categoría permitida y se juzgan por la fuente real de riesgo. HIGH
        assertEquals(ToolCategory.STANDARD, ToolCategories.categoryOf(SAVE_MEMORY))
        assertEquals(ToolRiskLevel.HIGH, ToolPermissionResolver.riskLevelFor(SAVE_MEMORY))

        val chatService = mockk<ChatService>()
        val registry = registryWith(def(SAVE_MEMORY), def("get_weather"))
        val sent = stubCompletions(chatService) { _, _ -> ChatCompletion(text = "esta soleado hoy") }

        runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals(listOf("get_weather"), sent.tools.first().orEmpty().map { it.name })
    }

    @Test
    fun `Una vez agotada la herramienta de tres ruedas, se enviará nuevamente una solicitud sin herramienta.`() = runBlocking {
        val chatService = mockk<ChatService>()
        val registry = registryWith(def("quick_note_list"))
        val sent = stubCompletions(chatService) { index, tools ->
            if (tools == null) {
                ChatCompletion(text = "OK")
            } else {
                ChatCompletion(
                    text = "",
                    toolCalls = listOf(ToolCall(id = "call-$index", name = "quick_note_list", arguments = "{}")),
                )
            }
        }

        val events = runner(chatService, registry).reply(messages(), null, null).toList()

        assertEquals("Tres rondas de herramientas seguidas de una solicitud de acabado.", 4, sent.tools.size)
        assertTrue("Herramientas para las tres primeras ruedas.", sent.tools.take(MAX_TOOL_ROUNDS).all { it != null })
        assertNull("Sin herramientas para la ronda final", sent.tools.last())
        assertEquals(MAX_TOOL_ROUNDS, events.count { it is MpReplyEvent.Activity })
        assertEquals(MpReplyEvent.Text("OK"), events.last())
    }

    @Test
    fun `Cuando la generación se cuelgue, presione tiempo de espera. Failed`() = runBlocking {
        val chatService = mockk<ChatService>()
        coEvery {
            chatService.completeText(
                messages = any(),
                temperature = any(),
                maxTokens = any(),
                tools = any(),
                toolChoice = any(),
            )
        } coAnswers {
            delay(SLOW_REPLY_MS)
            ChatCompletion(text = "demasiado tarde")
        }

        val startedAt = System.currentTimeMillis()
        val events = runner(chatService, registryWith(), timeoutMs = SHORT_TIMEOUT_MS)
            .reply(messages(), null, null)
            .toList()
        val elapsed = System.currentTimeMillis() - startedAt

        assertEquals(listOf(MpReplyEvent.Failed), events)
        assertTrue("El tiempo de espera debe cerrarse inmediatamente, el tiempo real ${elapsed}ms", elapsed < TIMEOUT_UPPER_BOUND_MS)
    }

    // ── accesorio ────────────────────────────────────────────────────────────

    /** Solicitudes capturadas en una sola ejecución:Definiciones de herramientas recibidas por ronda de solicitud,Y una lista de mensajes para esa ronda. */
    private class Captured {
        val tools: MutableList<List<ToolDefinition>?> = mutableListOf()
        val messages: MutableList<List<UIMessage>> = mutableListOf()
    }

    /**
     * Responde llamando al número:No. [index] veces(de 1)Obtienes lo que pides.
     *
     * La misma pila también registra el conjunto de herramientas y la lista de mensajes solicitados en cada ronda.,La afirmación procede en consecuencia.
     */
    private fun stubCompletions(
        chatService: ChatService,
        answer: (index: Int, tools: List<ToolDefinition>?) -> ChatCompletion,
    ): Captured {
        val captured = Captured()
        val toolsSlot = slot<List<ToolDefinition>?>()
        val messagesSlot = slot<List<UIMessage>>()
        coEvery {
            chatService.completeText(
                messages = capture(messagesSlot),
                temperature = any(),
                maxTokens = any(),
                tools = captureNullable(toolsSlot),
                toolChoice = any(),
            )
        } coAnswers {
            captured.tools += toolsSlot.captured
            captured.messages += messagesSlot.captured
            answer(captured.tools.size, toolsSlot.captured)
        }
        return captured
    }

    private fun runner(
        chatService: ChatService,
        registry: ToolRegistry,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): MiniPhoneToolTurnRunner = MiniPhoneToolTurnRunner(
        chatService = chatService,
        toolRegistry = registry,
        timeoutMs = timeoutMs,
    )

    private fun registryWith(vararg definitions: ToolDefinition): ToolRegistry {
        val registry = mockk<ToolRegistry>(relaxed = true)
        every { registry.listToolsAsToolDefinitions(any()) } returns definitions.toList()
        return registry
    }

    private fun def(name: String): ToolDefinition = ToolDefinition(
        name = name,
        description = "Herramientas para probar",
        parametersJsonSchema = "{\"type\":\"object\",\"properties\":{}}",
    )

    private fun messages(): List<UIMessage> = listOf(
        UIMessage(role = MessageRole.SYSTEM, content = "eres un ling"),
        UIMessage(role = MessageRole.USER, content = "¿Está ahí?"),
    )

    private companion object {
        const val SAVE_MEMORY = "save_memory"
        const val MAX_TOOL_ROUNDS = 3

        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val SHORT_TIMEOUT_MS = 150L
        const val SLOW_REPLY_MS = 5_000L

        /** El límite superior del tiempo de cierre del tiempo muerto.:Deje suficiente margen de arranque en frío de JVM,Pero mucho menos que los 5 segundos que tarda en colgarse. */
        const val TIMEOUT_UPPER_BOUND_MS = 2_000L
    }
}
