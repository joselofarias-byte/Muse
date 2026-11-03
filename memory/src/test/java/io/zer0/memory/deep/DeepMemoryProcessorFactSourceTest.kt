package io.zer0.memory.deep

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepMemoryProcessorFactSourceTest {

    @Test
    fun assistantAttributedTimelineItemsAreExcludedFromLongTermExtraction() {
        val summary = """
            ### hechos importantes
            - El usuario se está preparando actualmente para moverse.
            ### que paso
            - [Usuario] Planes explicados para un nuevo hogar.
            - [asistente] Comprométete a hacer un seguimiento
            - [Léame de Vibe del Asistente] Algunos se preocupan por la falta de detalles
        """.trimIndent()

        val source = filterAssistantAttributedMemoryEntries(summary)

        assertTrue(source.contains("El usuario se está preparando actualmente para moverse."))
        assertTrue(source.contains("[Usuario]"))
        assertFalse(source.contains("[asistente]"))
        assertFalse(source.contains("[Léame de Vibe del Asistente]"))
    }

    @Test
    fun sameUserFactsWithOnlyAssistantTimelineChangesDoNotRequireExtraction() {
        val previous = """
            ### hechos importantes
            - El usuario se está preparando actualmente para moverse.
            ### que paso
            - [Léame de Vibe del Asistente] Algunos se preocupan por la falta de detalles
        """.trimIndent()
        val current = """
            ### hechos importantes
            - El usuario se está preparando actualmente para moverse.
            ### que paso
            - [Léame de Vibe del Asistente] me siento mas tranquilo
        """.trimIndent()

        assertTrue(
            filterAssistantAttributedMemoryEntries(previous) ==
                filterAssistantAttributedMemoryEntries(current),
        )
    }
}
