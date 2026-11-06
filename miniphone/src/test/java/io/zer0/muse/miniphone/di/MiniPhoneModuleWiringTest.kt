package io.zer0.muse.miniphone.di

import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpConversationStore
import io.zer0.muse.miniphone.data.db.MiniPhoneDb
import io.zer0.muse.miniphone.data.db.MpMomentSource
import io.zer0.muse.miniphone.data.db.entity.MpMomentEntity
import io.zer0.muse.miniphone.data.moment.MP_LOCAL_ACTOR_ID
import io.zer0.muse.miniphone.ui.moments.MiniPhoneMomentsViewModel
import io.zer0.muse.miniphone.ui.shell.MiniPhoneShellViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Montaje de módulos Koin.:Se obtienen ambos ViewModels de interfaz.,Y el de "Mis Momentos" realmente obtuvo los parámetros del modo.
 *
 * Los errores de ensamblaje en esta capa no se expondrán en el momento de la compilación. —— Al shell le falta una dependencia y los parámetros de Moments ViewModel tienen subíndices incorrectos.,
 * Sólo explotan cuando se hace clic en ellos durante el tiempo de ejecución. Aquí lo analizamos directamente según el método de Koin.,Y de hecho publicó dos actualizaciones de Curry.,
 * Fíjate en el ejemplo de “Mírate sólo a ti mismo” para ver si es el que sólo lee sobre ti.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MiniPhoneModuleWiringTest {

    private lateinit var koin: Koin

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        koin = startKoin {
            androidContext(ApplicationProvider.getApplicationContext())
            modules(miniPhoneModule)
        }.koin
    }

    @After
    fun tearDown() {
        stopKoin()
        Dispatchers.resetMain()
    }

    @Test
    fun shellViewModelResolvesWithTheConversationStore() {
        val viewModel = koin.get<MiniPhoneShellViewModel>()

        assertNotNull(viewModel)
        assertTrue(
            "Las operaciones de escritura en líneas de sesión están conectadas a la implementación real.",
            koin.get<MpConversationActions>() is MpConversationStore,
        )
        assertNotNull("La capacidad de anfitrión se realiza ocupando lugares.", koin.get<MiniPhoneHost>())
    }

    @Test
    fun myMomentsViewModelReadsTheOnlyMineFlagFromItsParameters() = runBlocking {
        val db = koin.get<MiniPhoneDb>()
        db.momentDao().insert(moment(id = "mine", authorId = MP_LOCAL_ACTOR_ID, content = "propio", at = 20L))
        db.momentDao().insert(moment(id = "other", authorId = "c1", content = "el de otra persona", at = 30L))

        val mine = koin.get<MiniPhoneMomentsViewModel> { parametersOf(true) }

        val state = withTimeout(TIMEOUT_MS) { mine.uiState.first { it.isLoaded } }
        assertEquals("Solo el que te enviaste", listOf("mine"), state.moments.map { it.id })
    }

    @Test
    fun momentsViewModelWithoutTheFlagReadsTheWholeTimeline() = runBlocking {
        val db = koin.get<MiniPhoneDb>()
        db.momentDao().insert(moment(id = "mine", authorId = MP_LOCAL_ACTOR_ID, content = "propio", at = 20L))
        db.momentDao().insert(moment(id = "other", authorId = "c1", content = "el de otra persona", at = 30L))

        val all = koin.get<MiniPhoneMomentsViewModel> { parametersOf(false) }

        val state = withTimeout(TIMEOUT_MS) { all.uiState.first { it.isLoaded } }
        assertEquals("Todas las actualizaciones en orden cronológico inverso.", listOf("other", "mine"), state.moments.map { it.id })
    }

    private fun moment(id: String, authorId: String, content: String, at: Long): MpMomentEntity = MpMomentEntity(
        id = id,
        authorId = authorId,
        authorName = authorId,
        content = content,
        createdAt = at,
        source = if (authorId == MP_LOCAL_ACTOR_ID) MpMomentSource.USER else MpMomentSource.AI,
    )

    private companion object {
        const val TIMEOUT_MS: Long = 5_000L
    }
}
