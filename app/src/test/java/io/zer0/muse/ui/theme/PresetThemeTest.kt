package io.zer0.muse.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-09: Verifique el color de la luz de cada conjunto de temas preestablecidos/Todas las muestras oscuras anulan explícitamente los campos de superficie clave,
 * Evite que un conjunto de temas vuelva al color púrpura predeterminado del material después de dividirlo, lo que dará como resultado bloques de color blanco oscuro.
 */
class PresetThemeTest {

    private val defaultLight = lightColorScheme()
    private val defaultDark = darkColorScheme()

    @Test
    fun `preset themes are unique and complete`() {
        assertEquals(12, PresetThemes.size)
        assertEquals(12, PresetThemes.map { it.id }.toSet().size)
    }

    @Test
    fun `every theme overrides key surface fields in both schemes`() {
        PresetThemes.forEach { theme ->
            listOf(
                theme.lightScheme to defaultLight,
                theme.darkScheme to defaultDark,
            ).forEach { (scheme, defaultScheme) ->
                assertNotEquals("${theme.id} surfaceContainer", defaultScheme.surfaceContainer, scheme.surfaceContainer)
                assertNotEquals("${theme.id} surface", defaultScheme.surface, scheme.surface)
                assertNotEquals("${theme.id} surfaceVariant", defaultScheme.surfaceVariant, scheme.surfaceVariant)
                assertNotEquals("${theme.id} background", defaultScheme.background, scheme.background)
                assertTrue("${theme.id} onBackground", scheme.onBackground != defaultScheme.onBackground)
            }
        }
    }

    @Test
    fun `findPresetTheme falls back to warm paper`() {
        assertEquals("warm_paper", findPresetTheme("missing").id)
        assertEquals("mono", findPresetTheme("mono").id)
    }
}
