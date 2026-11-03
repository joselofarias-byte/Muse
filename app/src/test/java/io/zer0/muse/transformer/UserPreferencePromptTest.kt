package io.zer0.muse.transformer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una prueba de preferencia del usuario indica la representación de palabras.
 *
 * Que las "restricciones estrictas" puedan surtir efecto depende en parte del almacenamiento y en parte de la redacción. Si el modelo está escrito como un modelo de "mejor esfuerzo", debe considerarse como una sugerencia.
 * Así que aquí está el punto clave: no se inyectan listas vacías, se enumeran todos los elementos y la redacción es obligatoria.
 */
class UserPreferencePromptTest {

    private fun render(rules: List<String>) = SystemPromptAssembler.renderUserPreferences(rules)

    @Test
    fun `no preferences means no section at all`() {
        assertEquals("", render(emptyList()))
    }

    @Test
    fun `blank rules do not produce an empty section`() {
        assertEquals("", render(listOf("", "   ", "\n")))
    }

    @Test
    fun `each rule is listed`() {
        val out = render(listOf("No respondas con estímulos vacíos.", "No hagas un resumen de tres puntos"))

        assertTrue(out.contains("No respondas con estímulos vacíos."))
        assertTrue(out.contains("No hagas un resumen de tres puntos"))
    }

    @Test
    fun `wording marks it as a hard constraint not a suggestion`() {
        val out = render(listOf("No uses estímulos vacíos"))

        assertTrue("Hay que decir claramente que es una limitación dura.", out.contains("Debe cumplir con"))
        assertTrue("Para aclarar prioridades", out.contains("Prioridad superior a"))
        assertFalse("No se pueden escribir como sugerencias suaves.", out.contains("haz tu mejor esfuerzo"))
    }

    @Test
    fun `conflict handling is spelled out`() {
        val out = render(listOf("No uses estímulos vacíos"))

        assertTrue("En caso de conflicto, prevalecerá la preferencia. Sea claro.", out.contains("Sujeto a esto"))
        assertTrue("Explique cuándo no puede satisfacer sus necesidades en lugar de ignorarlas en silencio.", out.contains("explicar razones"))
    }

    @Test
    fun `rules are trimmed`() {
        val out = render(listOf("  No uses estímulos vacíos  "))

        assertTrue(out.contains("- No uses estímulos vacíos"))
    }
}
