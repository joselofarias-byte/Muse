package io.zer0.muse.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entradas que no están recomendadas actualmente: las páginas de categorías no se muestran, pero las búsquedas aún pueden llegar a ellas.
 *
 * Estos dos módulos funcionales y el enrutamiento todavía están en el paquete, pero esta versión no proporciona la entrada principal.
 */
class SettingsEntryVisibilityTest {

    private val hiddenIds = listOf(SettingsEntryId.MINIPHONE, SettingsEntryId.WEB_ACCESS)

    @Test
    fun `La entrada que aún no se muestra está marcada browseVisible=false`() {
        val all = SettingsCatalog.entries.associateBy { it.id }
        hiddenIds.forEach { id ->
            val entry = requireNotNull(all[id]) { "$id La declaración debe mantenerse en la lista." }
            assertFalse("$id No debe mostrarse en las páginas de categorías.", entry.browseVisible)
        }
    }

    @Test
    fun `Las entradas que no se muestran actualmente todavía están en la lista y pueden recuperarse mediante el índice de búsqueda`() {
        val ids = SettingsCatalog.entries.map { it.id }.toSet()
        hiddenIds.forEach { id ->
            assertTrue("$id Una vez que desaparece de la lista, no se puede encontrar en las búsquedas.", id in ids)
        }
    }

    @Test
    fun `Otras entradas se muestran por defecto`() {
        val hidden = hiddenIds.toSet()
        val wronglyHidden = SettingsCatalog.entries.filter { it.id !in hidden && !it.browseVisible }
        assertEquals(emptyList<SettingsEntryId>(), wronglyHidden.map { it.id })
    }

    @Test
    fun `No mostrarlo temporalmente no afectará la estructura de clasificación ni el número total de entradas`() {
        // oculto es“mostrar”, no“Declaración”; de lo contrario, el número de grupos y pruebas de diseño variarán en consecuencia.
        assertEquals(14, SettingsCatalog.groups.size)
        assertEquals(SettingsCatalog.groups.sumOf { it.entries.size }, SettingsCatalog.entries.size)
    }
}
