package io.zer0.muse.miniphone.ui.shell

import io.zer0.muse.miniphone.api.MiniPhoneHost
import io.zer0.muse.miniphone.api.MpCharacter
import io.zer0.muse.miniphone.api.MpChatTurn
import io.zer0.muse.miniphone.api.MpProfile
import io.zer0.muse.miniphone.api.MpReplyEvent
import io.zer0.muse.miniphone.data.chat.MpConversationActions
import io.zer0.muse.miniphone.data.chat.MpGroupActions
import io.zer0.muse.miniphone.data.db.dao.MpThreadDao
import io.zer0.muse.miniphone.data.db.entity.MpThreadEntity
import io.zer0.muse.miniphone.data.settings.MpMomentAutoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Síntesis del estado de la cáscara:cuatro corrientes(Conversación/Rol/Perfil/Enviar automáticamente al interruptor Momentos)entrar,una porción [ShellUiState] afuera.
 *
 * Usar DAO falsos, hosts falsos y configuraciones falsas,Fije la cuestión de "quién verifica la base de datos" en ViewModel —— La interfaz que obtenga ya debe ser un producto terminado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiniPhoneShellViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiStateMergesThreadsWithCharacterNamesAndProfile() = runTest(dispatcher) {
        val viewModel = MiniPhoneShellViewModel(
            threadDao = FakeThreadDao(
                listOf(
                    thread(id = "t2", characterId = "c2", at = 3_000L, unread = 1),
                    thread(id = "t1", characterId = "c1", at = 1_000L),
                ),
            ),
            host = FakeHost(
                characters = listOf(MpCharacter(id = "c1", name = "ali"), MpCharacter(id = "c2", name = "Bob")),
                profile = MpProfile(nickname = "Ziqi", signature = "Escribir en un teléfono celular pequeño"),
            ),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = FakeGroupActions(),
        )

        val state = viewModel.uiState.first { it.threads.isNotEmpty() }

        assertEquals(listOf("t2", "t1"), state.threads.map { it.id })
        assertEquals(listOf("Bob", "ali"), state.threads.map { it.name })
        assertEquals(listOf(1, 0), state.threads.map { it.unreadCount })
        assertEquals(listOf("B", "#"), state.contactSections.map { it.key })
        assertEquals("Ziqi", state.profile.nickname)
    }

    @Test
    fun threadWithoutCharacterFallsBackToPlaceholderName() = runTest(dispatcher) {
        val viewModel = MiniPhoneShellViewModel(
            threadDao = FakeThreadDao(listOf(thread(id = "t9", characterId = "gone", at = 5_000L))),
            host = FakeHost(characters = emptyList(), profile = MpProfile(nickname = "")),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = FakeGroupActions(),
        )

        val state = viewModel.uiState.first { it.threads.isNotEmpty() }

        assertEquals(listOf(UNKNOWN_CHARACTER_NAME), state.threads.map { it.name })
        assertEquals(emptyList<MiniPhoneContactSection>(), state.contactSections)
    }

    @Test
    fun autoMomentSwitchReflectsStoreAndWritesBack() = runTest(dispatcher) {
        val store = FakeAutoStore(enabled = false)
        val viewModel = MiniPhoneShellViewModel(
            threadDao = FakeThreadDao(emptyList()),
            host = FakeHost(characters = emptyList(), profile = MpProfile(nickname = "")),
            settings = store,
            store = FakeConversationActions(),
            groupStore = FakeGroupActions(),
        )

        // Cuando el interruptor está apagado, el estado debe estar apagado.(El valor predeterminado es true,Aquí podemos ver que el flujo ascendente está realmente conectado.)
        val off = viewModel.uiState.first { !it.autoMomentEnabled }
        assertFalse(off.autoMomentEnabled)

        viewModel.setAutoMomentEnabled(true)

        assertTrue(store.isEnabled())
        assertTrue(viewModel.uiState.first { it.autoMomentEnabled }.autoMomentEnabled)
    }

    @Test
    fun openOrCreateThreadReturnsTheSameThreadForTheSameCharacter() = runTest(dispatcher) {
        val threads = FakeThreadDao(emptyList())
        val viewModel = MiniPhoneShellViewModel(
            threadDao = threads,
            host = FakeHost(characters = emptyList(), profile = MpProfile(nickname = "")),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = FakeGroupActions(),
        )

        val first = viewModel.openOrCreateThread("c1")
        val second = viewModel.openOrCreateThread("c1")

        assertEquals("Los puntos duplicados para la misma persona sólo pueden recaer en la misma conversación.", first, second)
        assertEquals(1, threads.stored.size)
        assertEquals("c1", threads.stored.single().characterId)
    }

    @Test
    fun openOrCreateThreadKeepsDifferentCharactersApart() = runTest(dispatcher) {
        val threads = FakeThreadDao(listOf(thread(id = "t-old", characterId = "c2", at = 1_000L)))
        val viewModel = MiniPhoneShellViewModel(
            threadDao = threads,
            host = FakeHost(characters = emptyList(), profile = MpProfile(nickname = "")),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = FakeGroupActions(),
        )

        val existing = viewModel.openOrCreateThread("c2")
        val created = viewModel.openOrCreateThread("c3")

        assertEquals("c2 Ya tengo una sesión,Solo usa el viejo", "t-old", existing)
        assertNotEquals(existing, created)
        assertEquals(2, threads.stored.size)
        assertEquals(listOf("c2", "c3"), threads.stored.map { it.characterId }.sorted())
    }

    @Test
    fun openOrCreateThreadIgnoresBlankCharacterId() = runTest(dispatcher) {
        val threads = FakeThreadDao(emptyList())
        val viewModel = MiniPhoneShellViewModel(
            threadDao = threads,
            host = FakeHost(characters = emptyList(), profile = MpProfile(nickname = "")),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = FakeGroupActions(),
        )

        assertNull(viewModel.openOrCreateThread("  "))
        assertTrue("El rol vacío no abre una sesión", threads.stored.isEmpty())
    }

    @Test
    fun createdGroupCarriesTheJoinedNamesAndReturnsItsThreadId() = runTest(dispatcher) {
        val groups = FakeGroupActions(newThreadId = "g1")
        val viewModel = MiniPhoneShellViewModel(
            threadDao = FakeThreadDao(emptyList()),
            host = FakeHost(
                characters = listOf(
                    MpCharacter(id = "c1", name = "Ayuan"),
                    MpCharacter(id = "c2", name = "Xiaoman"),
                    MpCharacter(id = "c3", name = "Awu"),
                ),
                profile = MpProfile(nickname = "Ziqi"),
            ),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = groups,
        )
        // El nombre sólo se puede deletrear después de que la lista esté en el estado
        viewModel.uiState.first { it.contactSections.isNotEmpty() }

        val threadId = viewModel.createGroup(listOf("c3", "c1", "c2"))

        assertEquals("g1", threadId)
        assertEquals("El orden es el orden de selección.", "Awu, Ayuan, Xiaoman", groups.createdTitle)
        assertEquals(listOf("c3", "c1", "c2"), groups.createdMembers)
    }

    @Test
    fun creatingAGroupNeedsTwoPeopleAtLeast() = runTest(dispatcher) {
        val groups = FakeGroupActions(newThreadId = "g1")
        val viewModel = MiniPhoneShellViewModel(
            threadDao = FakeThreadDao(emptyList()),
            host = FakeHost(characters = listOf(MpCharacter(id = "c1", name = "Ayuan")), profile = MpProfile(nickname = "")),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = groups,
        )
        viewModel.uiState.first { it.contactSections.isNotEmpty() }

        assertNull("Una persona no forma un grupo.", viewModel.createGroup(listOf("c1")))
        assertNull("La persona que se repite no cuenta como dos.", viewModel.createGroup(listOf("c1", " c1 ")))
        assertTrue("No entró en la capa de escritura.", groups.createdMembers.isEmpty())
    }

    @Test
    fun threadActionsAreForwardedToTheConversationStore() = runTest(dispatcher) {
        val actions = FakeConversationActions()
        val viewModel = MiniPhoneShellViewModel(
            threadDao = FakeThreadDao(emptyList()),
            host = FakeHost(characters = emptyList(), profile = MpProfile(nickname = "")),
            settings = FakeAutoStore(),
            store = actions,
            groupStore = FakeGroupActions(),
        )

        viewModel.setThreadPinned("t1", pinned = true)
        viewModel.markThreadRead("t1")
        viewModel.deleteThread("t1")
        viewModel.clearConversations()

        assertEquals("Orientación en la parte superior", listOf("t1" to true), actions.pinned)
        assertEquals(listOf("t1"), actions.read)
        assertEquals(listOf("t1"), actions.deleted)
        assertEquals("Borrar y enviar solo una vez", 1, actions.cleared)
    }

    @Test
    fun uiStateCarriesTheHostVersionName() = runTest(dispatcher) {
        val viewModel = MiniPhoneShellViewModel(
            threadDao = FakeThreadDao(emptyList()),
            host = FakeHost(characters = emptyList(), profile = MpProfile(nickname = ""), appVersion = "2.6.12"),
            settings = FakeAutoStore(),
            store = FakeConversationActions(),
            groupStore = FakeGroupActions(),
        )

        assertEquals("La información de la versión de mi página depende de ello.", "2.6.12", viewModel.uiState.first().appVersionName)
    }

    private fun thread(id: String, characterId: String, at: Long, unread: Int = 0): MpThreadEntity = MpThreadEntity(
        id = id,
        characterId = characterId,
        unreadCount = unread,
        lastMessagePreview = "¿Está ahí?",
        lastMessageAt = at,
    )
}

/** Tabla de sesiones en memoria:Escribir y consultar por rol trabajan sobre los mismos datos.,Sólo se puede medir mediante \"una sola conversación con la misma persona\". */
private class FakeThreadDao(
    initial: List<MpThreadEntity>,
) : MpThreadDao {

    private val state = MutableStateFlow(initial)

    val stored: List<MpThreadEntity> get() = state.value

    override fun observeThreads(): Flow<List<MpThreadEntity>> = state

    override suspend fun getThread(threadId: String): MpThreadEntity? = stored.firstOrNull { it.id == threadId }

    override suspend fun findThreadByCharacter(characterId: String): MpThreadEntity? =
        stored.filter { it.characterId == characterId }.minByOrNull { it.id }

    override suspend fun upsert(thread: MpThreadEntity) {
        state.value = stored.filterNot { it.id == thread.id } + thread
    }

    override suspend fun updateLastMessage(threadId: String, preview: String?, at: Long) {
        state.value = stored.map { if (it.id == threadId) it.copy(lastMessagePreview = preview, lastMessageAt = at) else it }
    }

    override suspend fun updateTitle(threadId: String, title: String, customized: Boolean) {
        state.value = stored.map {
            if (it.id == threadId) it.copy(title = title, titleCustomized = customized) else it
        }
    }

    override suspend fun updateCharacter(threadId: String, characterId: String) {
        state.value = stored.map { if (it.id == threadId) it.copy(characterId = characterId) else it }
    }

    override suspend fun unreadCount(threadId: String): Int? = stored.firstOrNull { it.id == threadId }?.unreadCount

    override suspend fun increaseUnread(threadId: String) = Unit

    override suspend fun setUnread(threadId: String, count: Int) = Unit

    override suspend fun clearUnread(threadId: String) = Unit

    override suspend fun setPinned(threadId: String, pinned: Boolean) {
        state.value = stored.map { if (it.id == threadId) it.copy(pinned = pinned) else it }
    }

    override suspend fun delete(threadId: String) {
        state.value = stored.filterNot { it.id == threadId }
    }

    override suspend fun deleteAll() {
        state.value = emptyList()
    }
}

/** Tenga en cuenta la acción del Shell ViewModel girando:Sólo comprueba \"si se ha corregido\",El verdadero comportamiento es ejecutar SQL real en las pruebas de la biblioteca. */
private class FakeConversationActions : MpConversationActions {

    val pinned: MutableList<Pair<String, Boolean>> = mutableListOf()
    val read: MutableList<String> = mutableListOf()
    val deleted: MutableList<String> = mutableListOf()
    var cleared: Int = 0
        private set

    override suspend fun setPinned(threadId: String, pinned: Boolean) {
        this.pinned += threadId to pinned
    }

    override suspend fun markRead(threadId: String) {
        read += threadId
    }

    override suspend fun deleteThread(threadId: String) {
        deleted += threadId
    }

    override suspend fun clearAll() {
        cleared += 1
    }
}

/** Asumir la configuración:Dobles variables para cambios y últimos tiempos de generación,El valor escrito se deja en el campo para la afirmación. */
private class FakeAutoStore(
    enabled: Boolean = true,
    lastGeneratedAt: Long? = null,
) : MpMomentAutoStore {

    private val enabledState = MutableStateFlow(enabled)

    var lastGeneratedAtMillis: Long? = lastGeneratedAt
        private set

    override val enabledFlow: Flow<Boolean> = enabledState

    override suspend fun isEnabled(): Boolean = enabledState.value

    override suspend fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    override suspend fun lastGeneratedAt(): Long? = lastGeneratedAtMillis

    override suspend fun markGenerated(atMillis: Long) {
        lastGeneratedAtMillis = atMillis
    }

    override val chatProactiveEnabledFlow: Flow<Boolean> = flowOf(false)

    override suspend fun isChatProactiveEnabled(): Boolean = false

    override suspend fun setChatProactiveEnabled(enabled: Boolean) = Unit

    override suspend fun lastProactiveChatAt(): Long? = null

    override suspend fun markProactiveChat(atMillis: Long) = Unit
}

/** anfitrión falso:Los roles y la información son valores fijos.,Las habilidades generativas siempre \"no tienen resultados\". */
private class FakeHost(
    private val characters: List<MpCharacter>,
    private val profile: MpProfile,
    private val appVersion: String = "",
) : MiniPhoneHost {

    override fun observeCharacters(): Flow<List<MpCharacter>> = flowOf(characters)

    override fun observeProfile(): Flow<MpProfile> = flowOf(profile)

    override fun appVersionName(): String = appVersion

    override fun respond(characterId: String, history: List<MpChatTurn>): Flow<MpReplyEvent> = flowOf(MpReplyEvent.Failed)

    override suspend fun composeMoment(characterId: String, hint: String?): String? = null

    override suspend fun composeProactiveMessage(characterId: String, hint: String?): String? = null

    override suspend fun synthesizeVoice(text: String, emotion: String?): Pair<String, Long>? = null

    override suspend fun transcribeVoice(wavBytes: ByteArray): String? = null

    override suspend fun supportsVoiceTranscription(): Boolean = false
}

/** Tome nota de la solicitud del grupo.:¿Está escrito correctamente el nombre del grupo? ¿Se conserva el orden de los miembros?,Léelos todos desde aquí. */
private class FakeGroupActions(
    private val newThreadId: String = "g-new",
) : MpGroupActions {

    var createdMembers: List<String> = emptyList()
        private set

    var createdTitle: String = ""
        private set

    override suspend fun createGroup(memberIds: List<String>, title: String): String {
        createdMembers = memberIds
        createdTitle = title
        return newThreadId
    }

    override suspend fun renameGroup(threadId: String, title: String): Boolean = false

    override suspend fun addMembers(threadId: String, characterIds: List<String>, autoTitle: String?): Boolean = false

    override suspend fun removeMember(threadId: String, characterId: String, autoTitle: String?): Boolean = false

    override suspend fun disbandGroup(threadId: String): Boolean = false
}
