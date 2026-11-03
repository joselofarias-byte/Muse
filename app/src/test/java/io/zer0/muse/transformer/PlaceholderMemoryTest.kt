package io.zer0.muse.transformer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueba de juicio de shell vacío de memoria de compilación.
 *
 * Ítem 19 de la autoevaluación del asistente: “Anclas de la coherencia del carácter en todas las sesiones —— Déjame, que soy nuevo en la conversación, no actuar como un extraño cuando te conozco por primera vez”.
 *
 * El mecanismo de verificación y descubrimiento realmente existe (`buildLongTermMemorySection` inyectado en todas las sesiones por AssistantId),
 * Pero hay un defecto real:`MemoryCompiler.assembleCompiledMarkdown` Complete las cuatro secciones en blanco con "(Ninguno todavía)".
 * **Siempre devuelve una cadena no vacía**. Entonces las nuevas sesiones se inyectarán de manera estable:
 *
 *   Datos importantes: (ninguno) / Hoy: (ninguno) / A principios de esta semana: (ninguno) / Situación a largo plazo: (ninguno)
 *
 * Eso no es memoria, es ruido. —— Al ocupar tokens, también hace que el modelo piense "Recuerdo algo" pero no puede decir nada.
 */
class PlaceholderMemoryTest {

    private fun isPlaceholder(md: String) = SystemPromptAssembler.isPlaceholderOnly(md)

    /** Un shell vacío idéntico a la salida de MemoryCompiler.assembleCompiledMarkdown. */
    private val emptyShell =
        """
        ## hechos importantes

        (Ninguno todavía)

        ## hoy

        (Ninguno todavía)

        ## a principios de esta semana

        (Ninguno todavía)

        ## situación a largo plazo

        (Ninguno todavía)
        """.trimIndent() + "\n"

    @Test
    fun `all-placeholder markdown is detected as empty shell`() {
        assertTrue("Los cuatro párrafos \"(Ninguno)\" son sólo cáscaras vacías", isPlaceholder(emptyShell))
    }

    @Test
    fun `english placeholders are detected too`() {
        val en =
            """
            ## Key facts

            (none)

            ## Today

            (none)

            ## Earlier this week

            (none)

            ## Long-term context

            (none)
            """.trimIndent()

        assertTrue(isPlaceholder(en))
    }

    @Test
    fun `real facts are not treated as placeholder`() {
        val real =
            """
            ## hechos importantes

            - El usuario está trabajando en el proyecto de Android Muse y prefiere respuestas concisas.
            - El usuario mencionó que quería subcontratar en 2026-10

            ## hoy

            (Ninguno todavía)

            ## a principios de esta semana

            (Ninguno todavía)

            ## situación a largo plazo

            (Ninguno todavía)
            """.trimIndent()

        assertFalse("Si hay contenido real, no se puede tratar como un caparazón vacío.", isPlaceholder(real))
    }

    @Test
    fun `a single real line among placeholders is enough to keep it`() {
        val mostlyEmpty = emptyShell.replaceFirst("(Ninguno todavía)", "- El usuario está escribiendo una tarjeta de reproductor de música.")

        assertFalse("Es mejor perderse el veredicto que juzgar mal y perder el verdadero recuerdo.", isPlaceholder(mostlyEmpty))
    }

    @Test
    fun `blank markdown is handled by the earlier isBlank check not this one`() {
        // La cadena vacía ha sido reemplazada en el pulsador. md.isBlank() Bloquear; aquí solo confirmamos que no se lanzará ninguna excepción.
        assertTrue(isPlaceholder(""))
    }

    @Test
    fun `headings alone do not count as content`() {
        val onlyHeadings =
            """
            ## hechos importantes
            ## hoy
            ## a principios de esta semana
            ## situación a largo plazo
            """.trimIndent()

        assertTrue("Sólo el título y ningún texto siguen siendo conchas vacías.", isPlaceholder(onlyHeadings))
    }

    @Test
    fun `markdown bullets and emphasis do not count as content`() {
        val symbolsOnly =
            """
            ## hechos importantes
            - ** **
            - __ __
            """.trimIndent()

        assertTrue(isPlaceholder(symbolsOnly))
    }
}
