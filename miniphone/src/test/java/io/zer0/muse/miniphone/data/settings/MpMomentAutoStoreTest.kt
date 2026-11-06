package io.zer0.muse.miniphone.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Ubicación de la configuración del módulo:El mensaje privado activo del personaje cambia y la última vez.
 *
 * Corre verdadero DataStore(archivos temporales),Sin sustituto —— Lo que hay que probar es "las claves siguen ahí cuando se escriben y se vuelven a leer".:
 * Los valores predeterminados, la lectura tras escritura y las marcas de tiempo son comportamientos verdaderos en esta ruta.
 * Un documento nuevo por caso de uso,No hay residuos entre sí.
 */
class MpMomentAutoStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeScope: CoroutineScope
    private lateinit var store: MpMomentAutoStore

    @Before
    fun setUp() {
        storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // El nombre del archivo es consistente con la producción.(DataStore Agregue el sufijo .preferences_pb al nombre del archivo)
        val file = File(tempFolder.newFolder(), "miniphone_settings.preferences_pb")
        store = DataStoreMpMomentAutoStore(
            PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }),
        )
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    @Test
    fun chatProactiveIsOffByDefault() = runTest {
        // Desactivado predeterminado:Si el usuario no lo ha abierto, no será molestado.
        assertFalse(store.isChatProactiveEnabled())
        assertFalse(store.chatProactiveEnabledFlow.first())
        // Nunca escribí nada la última vez.
        assertNull(store.lastProactiveChatAt())
    }

    @Test
    fun enablingChatProactiveIsReadBack() = runTest {
        store.setChatProactiveEnabled(true)

        assertTrue(store.isChatProactiveEnabled())
        assertTrue(store.chatProactiveEnabledFlow.first())
        // El cambio para publicar automáticamente en Momentos no se ve afectado(Las dos llaves hacen cada una lo suyo.)
        assertTrue(store.isEnabled())
    }

    @Test
    fun markingProactiveChatKeepsTheTimestamp() = runTest {
        store.markProactiveChat(PROACTIVE_AT)

        assertEquals(PROACTIVE_AT, store.lastProactiveChatAt())
    }

    private companion object {
        const val PROACTIVE_AT: Long = 1_779_000_000_000L
    }
}
