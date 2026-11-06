package io.zer0.muse.transformer

import io.zer0.muse.data.assistant.AssistantEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testing del perfil de asistente delegable.
 *
 * Punto 13 de la autoevaluación del asistente: "Los asistentes a los que se les puede confiar en la delegación de múltiples agentes tienen casi los mismos perfiles y me resulta difícil juzgar a quién buscar".
 * Causa raíz: breve introducción `systemPrompt.take(80)`, y los personajes de la mayoría de los asistentes comienzan con el mismo conjunto de habilidades generales para hablar.
 * ("Eres un…"/「You are a…”), naturalmente todos son iguales cuando se cortan.
 *
 * Fijado aquí: utilice primero los campos estructurados (definiciones/etiquetas de personalidad) y elimine los abridores genéricos cuando recurra.
 */
class DelegateBriefTest {

    private fun assistant(name: String = "a", systemPrompt: String = "", personaDefinition: String = "", tagsJson: String = "[]") =
        AssistantEntity(
            id = name,
            name = name,
            systemPrompt = systemPrompt,
            personaDefinition = personaDefinition,
            tagsJson = tagsJson,
        )

    private fun brief(a: AssistantEntity) = SystemPromptAssembler.delegateBriefOf(a)

    @Test
    fun `persona definition wins over system prompt`() {
        val a = assistant(
            systemPrompt = "Eres un asistente útil y universal que destaca en una variedad de tareas.",
            personaDefinition = "Se especializa en visualización de datos y es bueno para dibujar indicadores complejos en gráficos fáciles de entender.",
        )

        assertTrue("Se deben preferir las definiciones de personalidad.", brief(a).contains("visualización de datos"))
    }

    @Test
    fun `tags are used when persona is empty`() {
        val a = assistant(
            systemPrompt = "Eres un útil asistente universal.",
            tagsJson = """["escribiendo","polaco","redacción"]""",
        )

        assertEquals("escribiendo,polaco,redacción", brief(a))
    }

    @Test
    fun `generic opening is stripped from system prompt fallback`() {
        val a = assistant(systemPrompt = "Usted es un consultor jurídico senior que se especializa en revisión de contratos y advertencia de riesgos.")

        val out = brief(a)

        assertTrue("Los abridores genéricos deberían eliminarse, en realidad: $out", !out.startsWith("Usted es un"))
        assertTrue("Debe conservarse la parte distintiva", out.contains("consultor jurídico"))
    }

    @Test
    fun `english generic opening is stripped too`() {
        val a = assistant(systemPrompt = "You are a senior backend engineer who reviews Go services.")

        val out = brief(a)

        assertTrue("Las primeras líneas comunes en inglés también deberían eliminarse, de hecho: $out", !out.startsWith("You are"))
        assertTrue(out.contains("backend engineer"))
    }

    @Test
    fun `two assistants with identical generic openings still get different briefs`() {
        // Este es el escenario que reportan los usuarios que “los perfiles son casi iguales”
        val a = assistant("x", systemPrompt = "eres un asistente de IA. Bueno en análisis de datos e informes.")
        val b = assistant("y", systemPrompt = "eres un asistente de IA. Bueno en la creación de novelas y la configuración de la visión del mundo.")

        assertNotEquals("La introducción debe diferenciar entre los dos asistentes.", brief(a), brief(b))
        assertTrue(brief(a).contains("análisis de datos"))
        assertTrue(brief(b).contains("creación de novelas"))
    }

    @Test
    fun `brief is truncated to the configured budget`() {
        val a = assistant(personaDefinition = "largo".repeat(200))

        assertTrue(brief(a).length <= SystemPromptAssembler.BRIEF_MAX_CHARS)
    }

    @Test
    fun `newlines and extra whitespace collapse to one line`() {
        val a = assistant(personaDefinition = "primera linea\n\n  segunda línea tercera línea")

        val out = brief(a)

        assertTrue("Sin saltos de línea", !out.contains("\n"))
        assertTrue("Los espacios en blanco continuos deben converger", !out.contains("  "))
    }

    @Test
    fun `empty everything yields an empty brief`() {
        assertEquals("", brief(assistant()))
    }

    @Test
    fun `malformed tags json does not crash`() {
        val a = assistant(systemPrompt = "Eres un asistente universal.", tagsJson = "not-json")

        assertTrue(brief(a).isNotEmpty())
    }

    @Test
    fun `tags are capped so the line does not grow unbounded`() {
        val a = assistant(tagsJson = """["a","b","c","d","e","f","g"]""")

        val out = brief(a)

        assertEquals("a,b,c,d", out)
    }
}
