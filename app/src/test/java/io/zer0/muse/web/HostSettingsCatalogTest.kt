package io.zer0.muse.web

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.feature.FeatureFlags
import io.zer0.muse.ui.settings.SettingsCatalog
import io.zer0.muse.ui.settings.SettingsEntryAction
import io.zer0.muse.ui.settings.SettingsToggleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * settings.snapshot La coherencia de la lista de grupos y la lista de configuraciones está bloqueada.
 *
 * App El orden, título y propiedad del cambio de los terminales están únicamente en
 * [io.zer0.muse.ui.settings.SettingsCatalog] una declaración aquí;[HostSettingsCatalog] ¿está dando?
 * Web La proyección del protocolo utilizada por el cliente (la clave en el protocolo es el nombre del recurso de cadena). Aquí, el ID del recurso en la lista se restaura al nombre del recurso.
 * Comparación grupo por elemento: si alguna de las partes cambia el grupo, el orden, el título o agrega o elimina elementos, fallará.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HostSettingsCatalogTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resources: Resources = context.resources

    @Test
    fun `catalog groups match the settings groups and order`() {
        val appGroups = SettingsCatalog.groups.map { resourceName(it.titleRes) }
        assertEquals(
            "La agrupación no es coherente con la lista de configuraciones:\nApp = $appGroups\nAcuerdo = ${HostSettingsCatalog.groups.map { it.key }}",
            appGroups,
            HostSettingsCatalog.groups.map { it.key },
        )
    }

    @Test
    fun `catalog entries match each group's declared entries`() {
        SettingsCatalog.groups.forEachIndexed { index, group ->
            val hostGroup = HostSettingsCatalog.groups[index]
            val appEntries = group.entries
            assertEquals(
                "「${hostGroup.key}\"El número de entradas es inconsistente",
                appEntries.size,
                hostGroup.entries.size,
            )
            appEntries.forEachIndexed { entryIndex, entry ->
                val hostEntry = hostGroup.entries[entryIndex]
                assertEquals(
                    "「${hostGroup.key}\"No. ${entryIndex + 1} Los identificadores de elementos son inconsistentes",
                    entry.id.name.lowercase(),
                    hostEntry.id,
                )
                assertEquals(
                    "entrada ${hostEntry.id} Los recursos del título son inconsistentes.",
                    resourceName(entry.titleRes),
                    hostEntry.titleKey,
                )
                assertEquals(
                    "entrada ${hostEntry.id} Los recursos de descripción son inconsistentes.",
                    entry.descRes?.let { resourceName(it) },
                    hostEntry.descKey,
                )
                assertEquals(
                    "entrada ${hostEntry.id} El tipo de es incompatible con la acción declarada.",
                    expectedKind(entry.action),
                    hostEntry.kind,
                )
            }
        }
    }

    @Test
    fun `every entry carries the fields the web client renders`() {
        val ids = HostSettingsCatalog.entries.map { it.id }
        assertEquals("El ID de entrada debe ser único: $ids", ids.size, ids.toSet().size)

        HostSettingsCatalog.entries.forEach { entry ->
            assertTrue("entrada ${entry.id} desaparecido id", entry.id.isNotBlank())
            assertTrue("entrada ${entry.id} desaparecido titleKey", entry.titleKey.isNotBlank())
            assertTrue("entrada ${entry.id} desaparecido iconKey", entry.iconKey.isNotBlank())
            assertTrue("entrada ${entry.id} La clave de grupo está vacía", entry.groupKey.isNotBlank())
            assertTrue(
                "entrada ${entry.id} El tipo no está dentro del valor acordado.: ${entry.kind}",
                entry.kind in setOf(
                    HostSettingsCatalog.KIND_LINK,
                    HostSettingsCatalog.KIND_SWITCH,
                    HostSettingsCatalog.KIND_ACTION,
                ),
            )
        }

        // La clave de grupo de la entrada en el grupo debe ser el grupo al que pertenece.,Evite que los cambios manuales posteriores pierdan la sincronización.
        HostSettingsCatalog.groups.forEach { group ->
            group.entries.forEach { entry ->
                assertEquals("entrada ${entry.id} La clave de grupo no coincide con el grupo al que pertenece.", group.key, entry.groupKey)
            }
        }
    }

    @Test
    fun `switch entries point at writable setting keys`() {
        val appToggles = SettingsCatalog.entries
            .filter { it.action is SettingsEntryAction.Toggle }
            .map { (it.action as SettingsEntryAction.Toggle).key }
        val switches = HostSettingsCatalog.entries.filter { it.kind == HostSettingsCatalog.KIND_SWITCH }
        assertTrue("La lista de configuraciones debe tener al menos una entrada de interruptor.", switches.isNotEmpty())
        assertEquals("El número de entradas de interruptor no coincide con la lista", appToggles.size, switches.size)

        appToggles.zip(switches).forEach { (toggleKey, entry) ->
            assertEquals(
                "cambiar de entrada ${entry.id} La clave no coincide con la lista.",
                appSettingKey(toggleKey),
                entry.toggleKey,
            )
            assertTrue(
                "cambiar de entrada ${entry.id} de toggleKey=${entry.toggleKey} No en configuración. Actualizar lista blanca",
                entry.toggleKey in HostSettingDefinitions.writableKeys,
            )
        }
    }

    @Test
    fun `compile time gated entries keep their flag state`() {
        val hostMiniPhone = HostSettingsCatalog.entries.single { it.id == "miniphone" }
        assertEquals(
            "El estado disponible de la entrada del teléfono celular pequeño debe ser coherente con la puerta en tiempo de compilación.",
            FeatureFlags.MINI_PHONE,
            hostMiniPhone.enabled,
        )
    }

    private fun expectedKind(action: SettingsEntryAction): String = when (action) {
        is SettingsEntryAction.Navigate -> HostSettingsCatalog.KIND_LINK
        is SettingsEntryAction.Toggle -> HostSettingsCatalog.KIND_SWITCH
        is SettingsEntryAction.Action -> HostSettingsCatalog.KIND_ACTION
    }

    /** La entrada del interruptor es la clave en settings.update; el nombre de la clave solo está en [HostSettingKeys] Declararlo una vez. */
    private fun appSettingKey(key: SettingsToggleKey): String = when (key) {
        SettingsToggleKey.PII_GUARD -> HostSettingKeys.PII_GUARD
    }

    private fun resourceName(resId: Int): String = resources.getResourceEntryName(resId)
}
