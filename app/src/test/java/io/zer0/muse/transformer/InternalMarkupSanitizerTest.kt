package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InternalMarkupSanitizerTest {
    @Test
    fun stripsInternalBlocksFromDisplayButKeepsTexto() {
        assertEquals(
            "Hola\neste es el texto",
            InternalMarkupSanitizer.stripForDisplay("<think>pensamiento interno</think>Hola\n<mod>Manuscrito interno</mod>este es el texto"),
        )
    }

    @Test
    fun stripsUnclosedInternalBlockToAvoidLeakingThoughts() {
        assertEquals("Texto", InternalMarkupSanitizer.stripForDisplay("Texto<thinking>Pensamientos que aún no han terminado"))
    }

    @Test
    fun extractsMoodFromMoodAndModTags() {
        assertEquals("de buen humor\nPrepárate para responder seriamente.", InternalMarkupSanitizer.extractMood("<mood>de buen humor</mood><mod>Prepárate para responder seriamente.</mod>Texto"))
        assertNull(InternalMarkupSanitizer.extractMood("Sólo texto"))
    }

    @Test
    fun extractsMoodFromBracketTags() {
        assertEquals(
            "Estable primero\nResponde en serio",
            InternalMarkupSanitizer.extractMood("[mood]Estable primero[/mood][mod]Responde en serio[/mod]Texto"),
        )
    }

    // ── v2.6.7 Cambios de rendimiento: equivalencia de comportamiento para rutas rápidas ─────────────────────────

    /** Texto sin formato (excluyendo '<' con '[') toman el camino rápido y el contenido no debe cambiarse. */
    @Test
    fun plainTextFastPathKeepsContent() {
        assertEquals("una frase común", InternalMarkupSanitizer.stripForDisplay("  una frase común  "))
        assertEquals("múltiples líneas\ntexto", InternalMarkupSanitizer.stripForDisplay("múltiples líneas\ntexto"))
    }

    /** La ruta rápida todavía tiene que converger en más de 3 líneas vacías consecutivas (la implementación anterior también hacía esto). */
    @Test
    fun plainTextFastPathStillCollapsesBlankRuns() {
        assertEquals("en\n\nabajo", InternalMarkupSanitizer.stripForDisplay("en\n\n\n\nabajo"))
    }

    /** Contiene '[' pero sin marca de identificación (markdown enlace) no se puede romper. */
    @Test
    fun bracketWithoutMarkerIsPreserved() {
        assertEquals("[enlace](https://a.b) Texto", InternalMarkupSanitizer.stripForDisplay("[enlace](https://a.b) Texto"))
    }

    /** Los que contienen corchetes angulares pero no etiquetas internas (fragmentos de código) no se pueden eliminar accidentalmente. */
    @Test
    fun angleBracketWithoutMarkerIsPreserved() {
        assertEquals("List<String>", InternalMarkupSanitizer.stripForDisplay("List<String>"))
    }

    /** El texto en blanco devuelve una cadena vacía y no debe generar un error. */
    @Test
    fun blankTextReturnsEmpty() {
        assertEquals("", InternalMarkupSanitizer.stripForDisplay("   \n  "))
        assertEquals("", InternalMarkupSanitizer.stripForDisplay(""))
    }
}
