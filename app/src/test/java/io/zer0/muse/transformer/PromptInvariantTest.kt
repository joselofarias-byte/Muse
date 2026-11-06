package io.zer0.muse.transformer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Una prueba de anclajes de regresión de palabras clave.
 *
 * Cubre dos cuestiones en el autoexamen del asistente que "se basan en un juicio sobre el terreno y tienen resultados inconsistentes en diferentes rondas":
 * - Artículo 11:artifact El borde con show_card siempre está oscilando y diferentes rondas del mismo tipo de contenido dan diferentes formas.
 * - Artículo 12: La búsqueda es una prioridad de tercer nivel pero no existe un umbral “suficiente”. A menudo raspamos demasiado las páginas web o respondemos con resúmenes.
 *
 * La única forma de solucionar este tipo de problema es utilizar palabras rápidas. —— dado**orden decidible**En lugar de "ir por sentimiento".
 * Esta prueba garantiza que estos criterios no serán eliminados por editores posteriores: no son redacción publicitaria, sino limitaciones de comportamiento.
 */
class PromptInvariantTest {

    private val invariants = SystemPromptAssembler.PROMPT_INVARIANTS

    @Test
    fun `invariant list is not empty`() {
        assertTrue("La tabla de puntos de anclaje se borra y las restricciones de palabras rápidas pierden su protección.", invariants.isNotEmpty())
    }

    @Test
    fun `artifact vs show_card decision is based on what the user does with it`() {
        val joined = invariants.joinToString("\n")

        assertTrue("Juzgar por \"quitarlo\" artifact", joined.contains("¿El usuario lo copiará o lo guardará?"))
        assertTrue("A juzgar por la \"operación\" show_card", joined.contains("¿Los usuarios harán clic en él, lo arrastrarán y lo cambiarán?"))
    }

    @Test
    fun `duplicating the same content in both forms is forbidden`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "Dos copias del mismo contenido es un fenómeno del que los usuarios se quejan y debe prohibirse explícitamente.",
            joined.contains("no lo hagas**Emitir tarjetas y artifact"),
        )
    }

    @Test
    fun `search has both stop and continue criteria`() {
        val joined = invariants.joinToString("\n")

        assertTrue("Debe haber un criterio para el \"debería parar\"", joined.contains("Ya puedo responder las preguntas de los usuarios directamente"))
        assertTrue("Si varias fuentes están de acuerdo, debería detenerse.", joined.contains("Varias fuentes coinciden en el mismo hecho"))
        assertTrue("Debe haber un criterio para \"debería continuar\"", joined.contains("cifras clave/Fecha/aún falta el nombre"))
    }

    @Test
    fun `summary alone is not treated as verified`() {
        val joined = invariants.joinToString("\n")

        assertTrue(
            "Tratar los fragmentos de búsqueda como verificados es la otra mitad del punto 12",
            joined.contains("El resumen no puede considerarse verificado."),
        )
    }

    @Test
    fun `every invariant actually appears in the assembled prompt sources`() {
        // Evitar "El punto de anclaje está escrito en la tabla pero el texto de la palabra solicitada no está escrito"——De esa manera la prueba se vuelve verde pero el comportamiento permanece sin cambios.
        val source =
            java.io.File("src/main/java/io/zer0/muse/transformer/SystemPromptAssembler.kt")
                .takeIf { it.exists() }
                ?.readText()
        if (source == null) return // Saltar (CI entrará corriendo)

        invariants.forEach { phrase ->
            // La lista de anclaje aparecerá una vez por sí sola; debe aparecer al menos una vez en el texto.
            val occurrences = Regex(Regex.escape(phrase)).findAll(source).count()
            assertTrue(
                "punto de anclaje$phrase”solo aparece en la lista de anclaje y el texto de la palabra emergente no está escrito. —— El comportamiento no cambiará",
                occurrences >= 2,
            )
        }
    }
}
