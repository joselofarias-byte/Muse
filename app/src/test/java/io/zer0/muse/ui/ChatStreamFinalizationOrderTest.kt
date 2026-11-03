package io.zer0.muse.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ChatStreamFinalizationOrderTest {

    @Test
    fun `supersede replacement resets every content throttle and persists immediately`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatToolLoopRunner.kt not found")

        val supersedeBlock =
            source.substringAfter("// v2.0: Reanudar la transferencia y reescribir el resultado final")
                .substringBefore("// Final content mutations")
        listOf("lastUiUpdateChars", "lastPersistChars", "lastNotifChars", "lastTokenUpdateChars")
            .forEach { counter -> assertTrue(supersedeBlock.contains("$counter = params.builder.length")) }
        assertTrue(supersedeBlock.contains("throttledPersist()"))
    }

    @Test
    fun `done event records terminal reason without mutating content before pending flush joins`() {
        // v2.x Refactor S1: cuerpo del bucle de herramienta(streamRound)ha sido extraído en su totalidad ChatToolLoopRunner,
        // La afirmación del cableado del código fuente de la secuencia de estado final se cambia para escanear archivos nuevos.(Las dos rutas son compatibles con diferentes directorios de ejecución.).
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
                Path.of("app/src/main/java/io/zer0/muse/ui/chat/ChatToolLoopRunner.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("ChatToolLoopRunner.kt not found")

        val doneBody = source.substringAfter("is ChatStreamEvent.Done ->")
            .substringBefore("is ChatStreamEvent.Error ->")
        assertTrue(doneBody.contains("doneFinishReason = event.finishReason"))
        assertFalse(doneBody.contains("params.builder.append"))
        assertFalse(doneBody.contains("params.reasoningBuilder.setLength"))

        val finalizationBody = source.substringAfter("// v1.0.4: final de la corriente flush pendingBuilder")
            .substringBefore("if (streamError != null)")
        val flushIndex = finalizationBody.indexOf("params.builder.append(pendingBuilder)")
        val reasoningFallbackIndex = finalizationBody.indexOf("params.builder.isEmpty()")
        val truncationIndex = finalizationBody.indexOf("ChatStopReason.isLengthLimited")
        assertTrue(flushIndex >= 0)
        assertTrue(reasoningFallbackIndex > flushIndex)
        assertTrue(truncationIndex > reasoningFallbackIndex)
    }
}
