package io.zer0.muse.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Configure la estructura del índice de búsqueda y presione bloquear.
 *
 * El índice consta de 48 elementos en la lista de categorías más la configuración en la página de "solo búsqueda directa". Todos los registros deben colgarse
 * 14 En una de las categorías de primer nivel: los resultados de la búsqueda se basan en el nombre de esta categoría para crear rutas de navegación. Sin él, los usuarios no sabrán adónde los llevarán.
 * Aquí, utilizamos recursos reales para ejecutar filtrado y rutas de navegación. Si se cambia el nombre de la clave o se mueve el grupo, se romperá.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsSearchIndexTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val groupTitles: Map<SettingsGroupKind, String> =
        SettingsCatalog.groups.associate { group -> group.kind to text(group.titleRes) }
    private val records: List<SettingsSearchRecord> = SettingsSearchCatalog.records

    @Test
    fun `index covers the settings catalog and extra deep links`() {
        assertTrue("El índice debe cubrir al menos 48 elementos de la lista de clasificación.", records.size >= SettingsCatalog.entries.size)

        val catalogTitles = SettingsCatalog.entries.map { it.titleRes }
        catalogTitles.forEach { titleRes ->
            assertTrue(
                "Artículos en la lista de categorías \"${text(titleRes)}\"No incluido en el índice de búsqueda",
                records.any { it.titleRes == titleRes },
            )
        }
    }

    @Test
    fun `every indexed entry is reachable by searching its own title`() {
        records.forEach { record ->
            val title = text(record.titleRes)
            val hits = search(title)
            assertTrue(
                "Buscar \"$title\"No se puede encontrar este registro (agrupado ${record.group})",
                hits.contains(record),
            )
        }
    }

    @Test
    fun `every search result carries a breadcrumb that starts with one of the fourteen categories`() {
        val allowed = SettingsCatalog.groups.map { text(it.titleRes) }.toSet()

        records.forEach { record ->
            val breadcrumb = settingsBreadcrumb(
                groupTitle = groupTitles.getValue(record.group),
                entryTitle = text(record.titleRes),
            )
            val firstSegment = breadcrumb.substringBefore(SETTINGS_BREADCRUMB_SEPARATOR)
            assertTrue("Las rutas de navegación deben comenzar con el nombre de la categoría.: $breadcrumb", allowed.contains(firstSegment))
            assertTrue(
                "El segundo párrafo de la ruta de navegación debe ser el nombre del elemento.: $breadcrumb",
                breadcrumb.substringAfter(SETTINGS_BREADCRUMB_SEPARATOR) == text(record.titleRes),
            )
        }
    }

    /** grupo de renderizado == Grupo de índice: para cada elemento visto en la página de categoría, la atribución en el índice debe ser coherente con la lista. */
    @Test
    fun `catalog entries are indexed under their declared group`() {
        SettingsCatalog.entries.forEach { entry ->
            val declared = SettingsCatalog.groups.single { group ->
                group.entries.any { it.id == entry.id }
            }
            assertTrue(
                "Entrada \"${text(entry.titleRes)}\"no figura en el índice\"${text(declared.titleRes)}\"abajo",
                records.any { it.titleRes == entry.titleRes && it.group == declared.kind },
            )
        }
    }

    @Test
    fun `moved entries are indexed in their new categories`() {
        assertEquals(
            "El agente debe estar colgado en \"Conexiones\".",
            SettingsGroupKind.CONNECTION,
            records.single { it.titleRes == R.string.proxy_title }.group,
        )
        assertEquals(
            "El uso compartido y la seguridad deben aparecer en \"Privacidad y seguridad\".",
            SettingsGroupKind.PRIVACY_SECURITY,
            records.single { it.titleRes == R.string.settings_screen_share }.group,
        )
        assertEquals(
            "PII El guardia debe aparecer en \"Privacidad y seguridad\".",
            SettingsGroupKind.PRIVACY_SECURITY,
            records.single { it.titleRes == R.string.settings_screen_pii_guard }.group,
        )
        assertEquals(
            "La gestión de datos debe aparecer en \"Datos y copia de seguridad\".",
            SettingsGroupKind.DATA_STORAGE,
            records.single { it.titleRes == R.string.data_management_entry }.group,
        )
        assertEquals(
            "Las estadísticas deben colgarse en \"Desarrollador\"",
            SettingsGroupKind.DEVELOPER,
            records.single { it.titleRes == R.string.settings_screen_stats }.group,
        )
    }

    @Test
    fun `web access entry is indexed in the connection group`() {
        val record = records.single { it.titleRes == R.string.settings_screen_web_access }
        assertEquals("El acceso a la versión web debe ser en la categoría \"Conexión\"", SettingsGroupKind.CONNECTION, record.group)
        assertTrue("Para acceder a la versión web, debe poder hacer clic en la página.", record.action is SettingsEntryAction.Navigate)

        listOf("Versión web", "código qr", "LAN").forEach { keyword ->
            assertTrue("Palabras clave \"$keyword\"Falta, la búsqueda no puede acertar", record.keywords.contains(keyword))
        }
        assertTrue("Faltan palabras clave pinyin", record.keywords.contains("wangyeban"))
    }

    @Test
    fun `searching a category name lists that category's entries`() {
        val chatGroup = SettingsGroupKind.CHAT
        val hits = search(groupTitles.getValue(chatGroup))
        val chatRecords = records.filter { it.group == chatGroup }

        assertTrue("Presione \"${groupTitles.getValue(chatGroup)}\"La búsqueda debe enumerar todas las entradas de esta categoría.", hits.containsAll(chatRecords))
    }

    private fun search(query: String): List<SettingsSearchRecord> = filterSettingsSearch(
        records = records,
        query = query,
        titleOf = { text(it) },
        groupTitleOf = { groupTitles.getValue(it) },
    )

    private fun text(resId: Int): String = context.getString(resId)
}
