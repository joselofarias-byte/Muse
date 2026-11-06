package io.zer0.muse.ui.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceso a la máscara de complemento [BubbleSkinStore] elección/Pruebas semánticas alternativas.
 *
 * Siga las convenciones de prueba establecidas en la Tienda:Cubra múltiples estados secuencialmente dentro de un solo método de prueba,Evite los archivos DataStore en
 * Contaminación entre métodos de prueba.;La fuente del complemento del robot está implementada en la memoria.,No toca el directorio de complementos real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BubbleSkinPluginSourceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val validStyle = BubbleRoleStyle(
        surfaceArgb = 0xFF1B2A38L,
        contentArgb = 0xFFF2F7FBL,
        radiusDp = 16f,
    )

    @Test
    fun pluginSkinsParticipateInSelectionAndFallBackSafely() = runBlocking {
        val source = FakePluginSkinSource()
        val store = BubbleSkinStore(context, source)
        try {
            selectAndFallBack(store, source)
        } finally {
            // muse_settings DataStore Los singleton son compartidos por múltiples clases de prueba dentro del mismo entorno limitado de Robolectric:
            // Limpiar esta escritura de prueba,Evite contaminar las afirmaciones de valores predeterminados de BubbleSkinStoreTest existentes.
            store.deleteSkin("shared-id")
            store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID)
        }
    }

    private suspend fun selectAndFallBack(store: BubbleSkinStore, source: FakePluginSkinSource) {
        // Mantenga el comportamiento existente cuando no haya un diseño de complemento:Verificar incorporado id → null.
        assertNull(store.selectedSkinFlow.first())

        source.entries = listOf(installedSkin("aurora", enabled = true))
        store.saveSelectedSkinId("aurora")
        assertEquals("aurora", store.selectedSkinFlow.first()?.id)
        assertEquals(listOf("aurora"), store.pluginSkinsFlow.first().map { it.skin.id })

        // El complemento está deshabilitado(Procedente de enabled=false Regresar)→ Respaldo estable incorporado default;La página de configuración todavía parece no estar disponible.
        source.entries = listOf(installedSkin("aurora", enabled = false))
        source.revision.value += 1
        assertNull(store.selectedSkinFlow.first())
        assertEquals(listOf(false), store.pluginSkinsFlow.first().map { it.enabled })

        // Volver a habilitar → Restaura la máscara del complemento.
        source.entries = listOf(installedSkin("aurora", enabled = true))
        source.revision.value += 1
        assertEquals("aurora", store.selectedSkinFlow.first()?.id)

        // El complemento está desinstalado → Respaldo incorporado default,La entrada desaparece.
        source.entries = emptyList()
        source.revision.value += 1
        assertNull(store.selectedSkinFlow.first())
        assertTrue(store.pluginSkinsFlow.first().isEmpty())

        // Al mismo tiempo, la máscara personalizada importada por el usuario mantiene su prioridad existente.,La instalación de complementos no cambia la apariencia existente del usuario.
        assertTrue(store.upsertSkin(skin("shared-id", name = "Custom shared")))
        source.entries = listOf(
            InstalledSkin(
                pluginId = "skin-pack",
                pluginName = "Aurora",
                version = "1.0.0",
                enabled = true,
                skin = skin("shared-id", name = "Plugin shared"),
            ),
        )
        source.revision.value += 1
        store.saveSelectedSkinId("shared-id")
        assertEquals("Custom shared", store.selectedSkinFlow.first()?.name)

        // Máscaras de complementos ilegales incluso si la fuente los marca como disponibles,No entrará en la ruta de renderizado.(La última verificación por parte del anfitrión.).
        source.entries = listOf(
            InstalledSkin(
                pluginId = "skin-pack",
                pluginName = "Aurora",
                version = "1.0.0",
                enabled = true,
                skin = skin("broken").copy(
                    light = mapOf(
                        BubbleRole.USER to validStyle.copy(
                            surfaceArgb = 0xFFEEEEEE,
                            contentArgb = 0xFFDDDDDD,
                        ),
                    ),
                ),
            ),
        )
        source.revision.value += 1
        store.saveSelectedSkinId("broken")
        assertNull(store.selectedSkinFlow.first())

        // La identificación predeterminada incorporada nunca puede ser ocupada por un diseño de complemento.
        source.entries = listOf(
            InstalledSkin(
                pluginId = "skin-pack",
                pluginName = "Aurora",
                version = "1.0.0",
                enabled = true,
                skin = skin(BubbleSkinCatalog.DEFAULT_SKIN_ID),
            ),
        )
        source.revision.value += 1
        store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID)
        assertNull(store.selectedSkinFlow.first())
    }

    private fun skin(id: String, name: String = "Skin $id"): BubbleSkin = BubbleSkin(
        id = id,
        name = name,
        light = mapOf(BubbleRole.USER to validStyle, BubbleRole.ASSISTANT to validStyle),
        dark = mapOf(BubbleRole.USER to validStyle, BubbleRole.ASSISTANT to validStyle),
    )

    private fun installedSkin(id: String, enabled: Boolean): InstalledSkin = InstalledSkin(
        pluginId = "skin-pack",
        pluginName = "Aurora",
        version = "1.0.0",
        enabled = enabled,
        skin = skin(id),
    )

    /** Fuente de máscara del complemento de versión de memoria:Prueba aprobada modificación [entries] y aumentar por si solo [revision] Simule cambios de estado de complementos. */
    private class FakePluginSkinSource : PluginSkinSource {
        val revision = MutableStateFlow(0)
        var entries: List<InstalledSkin> = emptyList()

        override fun listInstalledSkins(includeDisabled: Boolean): List<InstalledSkin> = entries.filter { includeDisabled || it.enabled }

        override val revisionFlow: Flow<Int> = revision
    }
}
