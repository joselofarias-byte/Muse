package io.zer0.muse.ui

import io.zer0.muse.data.skill.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Criterios para el alcance de "inicio y parada con un solo botón".
 *
 * Esta función determina lo que sucede cuando el usuario hace clic en "Desactivar todo". Las consecuencias de hacer un juicio equivocado son graves:
 * Desactivar las habilidades integradas equivale a quitar todas las herramientas del modelo, pero la interfaz simplemente dice "deshabilitar todo".
 */
class SkillBulkToggleTargetsTest {

    @Test
    fun `Las habilidades integradas no participan en el inicio y la parada con un solo clic`() {
        val skills = listOf(
            skill(id = "read_file", impl = "read_file"),
            skill(id = "mine", impl = "read_file"),
        )

        val targets = bulkToggleTargets(skills, builtInIds = setOf("read_file"))

        assertEquals(listOf("mine"), targets.map { it.id })
    }

    @Test
    fun `Las habilidades de complemento no participan en el inicio y la parada con un solo clic`() {
        // Las habilidades del complemento comienzan y finalizan con el complemento, y los cambios independientes entrarán en conflicto con el estado del complemento.
        val skills = listOf(
            skill(id = "plugin_skill", impl = "plugin:my-plugin:hello"),
            skill(id = "mine", impl = "read_file"),
        )

        val targets = bulkToggleTargets(skills, builtInIds = emptySet())

        assertEquals(listOf("mine"), targets.map { it.id })
    }

    @Test
    fun `Solo las habilidades del usuario se cambiarán en lotes.`() {
        val skills = listOf(
            skill(id = "read_file", impl = "read_file"),
            skill(id = "plugin_skill", impl = "plugin:p:fn"),
            skill(id = "a", impl = "read_file"),
            skill(id = "b", impl = "http_get"),
        )

        val targets = bulkToggleTargets(skills, builtInIds = setOf("read_file"))

        assertEquals(listOf("a", "b"), targets.map { it.id })
    }

    @Test
    fun `Devuelve una lista vacía cuando no hay objetos intercambiables`() {
        val targets = bulkToggleTargets(
            listOf(skill(id = "read_file", impl = "read_file")),
            builtInIds = setOf("read_file"),
        )

        assertTrue(targets.isEmpty())
    }

    private fun skill(id: String, impl: String) = SkillEntity(
        id = id,
        name = id,
        description = "",
        implementationKotlin = impl,
    )
}
