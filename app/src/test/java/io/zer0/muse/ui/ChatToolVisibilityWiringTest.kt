package io.zer0.muse.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * v2.4.5 fix Regresar: El interruptor "Mostrar proceso de llamada de herramienta" debe controlar ambas rutas de renderizado al mismo tiempo.
 *
 * fondo:Cuando el interruptor está apagado, ChatScreen configura la tarjeta de tarea en null,Si el que está dentro de MessageBubble
 * `toolInfo != null` La sucursal no está controlada,El renderizado caerá sobre él.,La tarjeta de herramientas aparece como de costumbre.
 * (Comentarios reales de la máquina del usuario:Apague el interruptor y aún verá la tarjeta "Herramienta llamada").
 */
class ChatToolVisibilityWiringTest {

    @Test
    fun `tool detail preference is consumed by chat rendering`() {
        val source = readChatSource("ChatScreen.kt")
        assertTrue(source.contains("state.chatPreferences.showToolCallDetails"))
        assertTrue(source.contains("if (showToolCallDetails)"))
        // ChatScreen El interruptor debe pasarse de forma transparente a MessageBubble,De lo contrario, el camino dentro de la burbuja queda incontrolado.
        assertTrue(source.contains("showToolCallDetails = showToolCallDetails"))
    }

    @Test
    fun `message bubble gates its own tool call card behind the preference`() {
        val bubble = readChatSource("MessageBubble.kt")
        assertTrue(
            "MessageBubble Necesita recibir el parámetro showToolCallDetails",
            bubble.contains("showToolCallDetails: Boolean"),
        )
        assertTrue(
            "Las tarjetas de herramientas huérfanas dentro de las burbujas deben estar controladas por interruptores.",
            bubble.contains("toolPayloadShowing(toolInfo.toolName, showToolCallDetails)"),
        )
        // v2.6.0: show_card es una tarjeta de contenido,interruptor de exención;La función de activación en sí aún necesita conservar el juicio del interruptor.
        assertTrue(
            "La función de activación de la tarjeta de herramientas debe contener el juicio del interruptor",
            bubble.contains("showToolCallDetails || toolName == \"show_card\""),
        )
    }

    @Test
    fun `plan card is hidden when an execution task card already owns the same turn`() {
        val source = readChatSource("MessageBubble.kt")
        val planBlock = source.substringAfter("if (agentPlan != null)")
            .substringBefore("} // closes AI bubble Surface Column")
        assertTrue(planBlock.contains("taskCard == null"))
        assertTrue(planBlock.contains("msg.toolCallInfo == null"))
        assertTrue(planBlock.contains("msg.toolCalls.isNullOrEmpty()"))
    }

    private fun readChatSource(fileName: String): String {
        val candidates =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/$fileName"),
                Path.of("app/src/main/java/io/zer0/muse/ui/$fileName"),
            )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("Unable to locate $fileName from ${System.getProperty("user.dir")}")
        return Files.newBufferedReader(path).use { it.readText() }
    }
}
