package io.zer0.muse.automation.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * UI Prueba automatizada de recursos de cadenas de páginas de permisos.
 *
 * 1. Todas las claves de copia de página en el idioma predeterminado se pueden analizar y no están vacías.(Contiene copia formateada con parámetros posicionales.);
 * 2. Cada directorio de idiomas se declara completo. key —— Android volverá silenciosamente al chino predeterminado cuando falte la traducción,
 *    Solo afirme que getString no es nulo y no se puede encontrar,Así que revisa cada uno directamente values-&#42; XML para el directorio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationSettingsStringsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Todas las claves de recursos de cadena a las que hace referencia directamente la página. */
    private val pageKeys = listOf(
        R.string.automation_settings_title,
        R.string.automation_settings_intro,
        R.string.automation_settings_unavailable,
        R.string.automation_tier_level,
        R.string.automation_tier_accessibility,
        R.string.automation_tier_accessibility_desc,
        R.string.automation_tier_shell,
        R.string.automation_tier_shell_desc,
        R.string.automation_tier_root,
        R.string.automation_tier_root_desc,
        R.string.automation_action_enable,
        R.string.automation_status_enabled,
        R.string.automation_root_action_request,
        R.string.automation_root_requesting,
        R.string.automation_root_fallback_action,
        R.string.automation_root_granted,
        R.string.automation_root_no_su,
        R.string.automation_root_timeout,
        R.string.automation_root_denied,
        R.string.automation_root_failed,
        R.string.automation_test_action,
        R.string.automation_test_running,
        R.string.automation_test_no_channel,
        R.string.automation_test_current_app,
        R.string.automation_test_node_count,
        R.string.automation_test_resolution,
        R.string.automation_test_source,
        R.string.automation_test_unknown,
        R.string.automation_test_failed,
        R.string.automation_orchestration_title,
        R.string.automation_orchestration_hint,
        R.string.automation_orchestration_action,
    )

    /** Nombre clave de redacción requerida(Nombre del recurso),Se utiliza para comprobar la existencia de directorios por idioma. */
    private val pageKeyNames = listOf(
        "automation_settings_title",
        "automation_settings_intro",
        "automation_settings_unavailable",
        "automation_tier_level",
        "automation_tier_accessibility",
        "automation_tier_accessibility_desc",
        "automation_tier_shell",
        "automation_tier_shell_desc",
        "automation_tier_root",
        "automation_tier_root_desc",
        "automation_action_enable",
        "automation_status_enabled",
        "automation_root_action_request",
        "automation_root_requesting",
        "automation_root_fallback_action",
        "automation_root_granted",
        "automation_root_no_su",
        "automation_root_timeout",
        "automation_root_denied",
        "automation_root_failed",
        "automation_test_action",
        "automation_test_running",
        "automation_test_no_channel",
        "automation_test_current_app",
        "automation_test_node_count",
        "automation_test_resolution",
        "automation_test_source",
        "automation_test_unknown",
        "automation_test_failed",
        "automation_orchestration_title",
        "automation_orchestration_hint",
        "automation_orchestration_action",
    )

    private val localeDirs = listOf(
        "values",
        "values-en",
        "values-es",
        "values-ja",
        "values-ko",
        "values-pt-rBR",
        "values-ru",
    )

    @Test
    fun `every page string resolves to non blank text`() {
        pageKeys.forEach { id ->
            val name = context.resources.getResourceName(id)
            assertTrue("cuerda $name resuelve vaciar", context.getString(id).isNotBlank())
        }
    }

    @Test
    fun `formatted page strings keep their positional arguments`() {
        assertTrue(context.getString(R.string.automation_tier_level, 3).contains("3"))
        assertTrue(context.getString(R.string.automation_test_node_count, 5).contains("5"))
        val resolution = context.getString(R.string.automation_test_resolution, 1080, 2400)
        assertTrue(resolution.contains("1080"))
        assertTrue(resolution.contains("2400"))
        assertTrue(context.getString(R.string.automation_test_failed, "boom").contains("boom"))
        assertTrue(context.getString(R.string.automation_test_current_app, "com.example").contains("com.example"))
    }

    @Test
    fun `every locale directory declares all page keys`() {
        val resRoot = listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: error("directorio res no encontrado,directorio de trabajo actual: ${File(".").absolutePath}")
        localeDirs.forEach { locale ->
            val dir = File(resRoot, locale)
            assertTrue("Falta directorio de idiomas: ${dir.path}", dir.isDirectory)
            val declared = dir.listFiles { file -> file.extension == "xml" }
                .orEmpty()
                .flatMap { file ->
                    Regex("""name="(automation_[A-Za-z0-9_]+)"""")
                        .findAll(file.readText())
                        .map { it.groupValues[1] }
                        .toList()
                }
                .toSet()
            val missing = pageKeyNames.filterNot { it in declared }
            assertTrue("$locale Cadena faltante: $missing", missing.isEmpty())
        }
    }
}
