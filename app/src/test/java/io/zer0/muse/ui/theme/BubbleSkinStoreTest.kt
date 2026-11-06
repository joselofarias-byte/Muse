package io.zer0.muse.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.common.AppJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * almacenamiento de piel de burbujas(DataStore)prueba.
 *
 * Siga las convenciones de prueba establecidas en la Tienda:dentro de un único método de prueba"Lea el valor predeterminado primero y luego escriba",
 * Evite la contaminación de archivos del almacén de datos entre métodos de prueba.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BubbleSkinStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF1B2A38L,
        contentArgb = 0xFFF2F7FBL,
        radiusDp = 16f,
    )

    private fun skin(id: String, style: BubbleRoleStyle = validStyle): BubbleSkin = BubbleSkin(
        id = id,
        name = "Skin $id",
        light = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
        dark = mapOf(BubbleRole.USER to style, BubbleRole.ASSISTANT to style),
    )

    @Test
    fun bubbleSkinStore_defaultsThenPersistsSelectionImportAndDelete() = runBlocking {
        val store = BubbleSkinStore(context)

        // Predeterminado:Sin máscara personalizada, seleccione integrada default → selectedSkinFlow para null,
        // Si la persona que llama pasa nulo, se mantendrá la ruta de representación antes de la transformación.
        assertEquals(BubbleSkinCatalog.DEFAULT_SKIN_ID, store.selectedSkinIdFlow.first())
        assertTrue(store.customSkinsFlow.first().isEmpty())
        assertNull(store.selectedSkinFlow.first())

        // Guarde el skin legal y selecciónelo
        assertTrue(store.upsertSkin(skin("store-skin")))
        store.saveSelectedSkinId("store-skin")
        assertEquals("store-skin", store.selectedSkinFlow.first()?.id)
        assertEquals(1, store.customSkinsFlow.first().size)

        // piel ilegal(No hay suficiente contraste)Escribir rechazado,lista existente/Mantener seleccionado
        assertFalse(store.upsertSkin(skin("bad-skin", validStyle.copy(contentArgb = 0xFFDDDDDDL, surfaceArgb = 0xFFEEEEEE))))
        assertNull(store.selectedSkinFlow.first()?.takeIf { it.id == "bad-skin" })
        assertEquals(1, store.customSkinsFlow.first().size)

        // Importar contenido ilegal no cambia ningún estado
        assertNull(store.importSkin("{ not json"))
        assertEquals(1, store.customSkinsFlow.first().size)

        // Después de importar JSON legal, puede seleccionarlo por ID
        val imported = store.importSkin(
            AppJson.encodeToString(BubbleSkin.serializer(), skin("imported-skin")),
        )
        assertEquals("imported-skin", imported?.id)
        store.saveSelectedSkinId("imported-skin")
        assertEquals("imported-skin", store.selectedSkinFlow.first()?.id)
        assertEquals(2, store.customSkinsFlow.first().size)

        // Eliminar la máscara actualmente seleccionada → Seleccione la identificación para borrar y revertir la función incorporada default(null piel)
        store.deleteSkin("imported-skin")
        assertEquals(BubbleSkinCatalog.DEFAULT_SKIN_ID, store.selectedSkinIdFlow.first())
        assertNull(store.selectedSkinFlow.first())
        assertEquals(1, store.customSkinsFlow.first().size)

        // Cuando no se selecciona la máscara personalizada, escribir nuevamente la identificación incorporada sigue siendo el comportamiento predeterminado.
        store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID)
        assertNull(store.selectedSkinFlow.first())
    }
}
