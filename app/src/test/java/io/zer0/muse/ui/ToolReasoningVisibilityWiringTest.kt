package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ToolReasoningVisibilityWiringTest {

    private fun read(vararg paths: String): String = paths
        .map(Path::of)
        .firstOrNull(Files::exists)
        ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
        ?: error("source file not found")

    @Test
    fun `tool messages keep reasoning visible when reasoning display is enabled`() {
        val source =
            read(
                "src/main/java/io/zer0/muse/ui/MessageBubble.kt",
                "app/src/main/java/io/zer0/muse/ui/MessageBubble.kt",
            )
        val reasoningBlock = source.substringAfter("// Phase 8.3: Tarjetas plegables de proceso de razonamiento")
            .substringBefore("// Isomórfico al estado de ánimo/bloqueo de razonamiento")
        assertTrue(reasoningBlock.contains("if (chatPrefs.showReasoning"))
        assertFalse(reasoningBlock.contains("!isToolRoundMessage"))
    }

    @Test
    fun `expanded grouped reasoning is not silently capped at six lines`() {
        val source =
            read(
                "src/main/java/io/zer0/muse/ui/ToolRunCard.kt",
                "app/src/main/java/io/zer0/muse/ui/ToolRunCard.kt",
            )
        // v2.6.7: El área de expansión se ha extraído de ToolRunCard a ToolRunExpandedRows (LongMethod rectificación),
        // La antigua forma de escribir "texto completo después de los comentarios" saltará a la siguiente función y provocará errores de juicio.
        // En su lugar, limite el alcance a la función extraída y la fuerza de la afirmación permanecerá sin cambios.
        // El área expandida ahora llama ProcessStepItem / ProcessStepState (definido debajo),
        // Entonces, el límite se cambia a "antes de la siguiente función de nivel superior" y el límite ya no está fijado a ToolRunHeaderRow
        // ——Esta última ya no es la función que le sigue.
        val reasoningBlock =
            source.substringAfter("private fun ToolRunExpandedRows")
                .substringBefore("\ninternal data class ProcessTally")
        // Los elementos Think ahora siguen el caparazón plegable de ProcessStepItem y los puntos de anclaje siguen la nueva implementación;
        // Lo que realmente hay que tener en cuenta es "no truncamiento": no debería haber un límite superior en el número de líneas o elipses en el texto reflexivo.
        assertTrue(reasoningBlock.contains("chat_process_thinking_done"))
        assertFalse(reasoningBlock.contains("maxLines = 6"))
        assertFalse(
            reasoningBlock.substringAfter("text = reasoningText").contains("TextOverflow.Ellipsis"),
        )
    }

    @Test
    fun `grouping predicate accepts reasoning-only messages as process steps`() {
        val source =
            read(
                "src/main/java/io/zer0/muse/ui/ChatScreen.kt",
                "app/src/main/java/io/zer0/muse/ui/ChatScreen.kt",
            )
        val groupingBlock = source.substringAfter("ChatDisplayGrouper.group(visibleMessages)")
            .substringBefore(".filterIsInstance")
        // v2.6.10: Se flexibiliza el criterio a "cuenta como un proceso sin el texto final"——
        // Los mensajes de pensamiento puro también deben incorporarse al flujo del proceso (el mismo nivel que el pensamiento/grupo_herramientas en el lado de la plataforma).
        // Tenga en cuenta dos puntos aquí:① Aún se requiere "Sin texto";② La fuente del proceso son las herramientas o el pensamiento.
        assertTrue(groupingBlock.contains("displayContent"))
        assertTrue(groupingBlock.contains("isBlank()"))
        assertTrue(groupingBlock.contains("msg.toolCallInfo != null"))
        assertTrue(groupingBlock.contains("reasoning.isNullOrBlank()"))
    }
}
