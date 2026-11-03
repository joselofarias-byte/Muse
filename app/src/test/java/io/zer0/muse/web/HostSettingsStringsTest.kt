package io.zer0.muse.web

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Configure un análisis de redacción publicitaria para verificar los recursos reales de la aplicación.
 *
 * `HostSettingsCatalog` En cada grupo/entrada, solo se escribe el nombre del recurso y la copia la escribe
 * [AndroidHostSettingsStrings] Analizado en el idioma actual en el momento de la instantánea. Aquí analizamos los recursos reales uno por uno:
 * Cualquier cambio de nombre, eliminación de clave u omisión (volver al nombre del recurso) se dividirá aquí en lugar de convertirse en una línea en blanco en la página web.
 */
@RunWith(RobolectricTestRunner::class)
class HostSettingsStringsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val strings = AndroidHostSettingsStrings(context)

    /** Nombres de todos los recursos de redacción en el directorio que se mostrarán en la interfaz (título del grupo + título de la entrada + descripción de la entrada). */
    private val catalogResourceNames: List<String> = buildList {
        HostSettingsCatalog.groups.forEach { group ->
            add(group.key)
            group.entries.forEach { entry ->
                add(entry.titleKey)
                entry.descKey?.let { add(it) }
            }
        }
    }

    @Test
    fun `every catalog resource name resolves to non blank text`() {
        // Se deben analizar tanto el directorio de recursos predeterminado (chino) como el directorio en inglés para obtener la copia.
        listOf("system", "zh", "en").forEach { language ->
            val missing = catalogResourceNames.filter { strings.resolve(it, language).isNullOrBlank() }
            assertTrue("language=$language Estos recursos de redacción de configuración no se pueden analizar: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `resolved text follows the requested language`() {
        val name = "settings_screen_group_assistants"

        // Lenguaje explícito:values/ Es el directorio predeterminado chino.values-en Es un catálogo en inglés.
        val zh = strings.resolve(name, "zh")
        val en = strings.resolve(name, "en")

        assertTrue("Los catálogos chinos deben analizar la copia", !zh.isNullOrBlank())
        assertTrue("El catálogo en inglés debe analizar la copia.", !en.isNullOrBlank())
        assertNotEquals("Después de cambiar al inglés, la copia debería cambiar en consecuencia; de lo contrario, significa que no se analiza según el idioma.", zh, en)
    }

    @Test
    fun `unknown resource names resolve to null instead of a placeholder`() {
        assertEquals(null, strings.resolve("settings_screen_definitely_not_a_resource", "zh"))
        assertEquals(null, strings.resolve("  ", "zh"))
    }
}
