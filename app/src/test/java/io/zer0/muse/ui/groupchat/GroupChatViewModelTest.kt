package io.zer0.muse.ui.groupchat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.schedule.GroupChatScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEST-03: chat grupal ViewModel — state Ruta crítica de distribución de compilación y renderizado(Carga de página/cambiar chat grupal/envío optimista)Prueba única de JVM.
 *
 * Toda la inyección de dependencia MockK mock;GroupChatViewModel no tocar Koin/GlobalContext,Puedes burlarte y ejecutar directamente.
 * Robolectric proporcionar Context;StandardTestDispatcher Controla el envío principal de viewModelScope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GroupChatViewModelTest {

    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var repository: GroupChatRepository
    private lateinit var scheduler: GroupChatScheduler
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var settings: SettingsRepository
    private lateinit var activityHub: GroupChatActivityHub
    private lateinit var viewModel: GroupChatViewModel

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun chat(id: String, name: String, updatedAt: Long = 0L) = GroupChatEntity(
        id = id,
        name = name,
        memberIdsJson = "[]",
        updatedAt = updatedAt,
    )

    private fun msg(id: String, chatId: String, body: String, ts: Long) = GroupChatMessageEntity(
        id = id,
        chatId = chatId,
        senderType = "assistant",
        senderId = "assistant-a",
        senderName = "asistenteA",
        body = body,
        timestamp = ts,
    )

    private fun buildViewModel() {
        viewModel = GroupChatViewModel(
            groupChatRepository = repository,
            scheduler = scheduler,
            assistantRepository = assistantRepository,
            settings = settings,
            activityHub = activityHub,
            appContext = context,
            groupChatMemoryRepository = null,
        )
        testScheduler.advanceUntilIdle()
    }

    @Before
    fun setup() {
        unmockkAll()
        clearAllMocks()
        testScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        repository = mockk<GroupChatRepository>(relaxed = true)
        scheduler = mockk<GroupChatScheduler>(relaxed = true)
        assistantRepository = mockk<AssistantRepository>(relaxed = true)
        settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.multiAgentConfigFlow } returns MutableStateFlow(MultiAgentConfig())
        every { settings.chatPreferencesFlow } returns MutableStateFlow(ChatPreferences())

        // GroupChatActivityHub Es una capa de transmisión de memoria pura.(Sin dependencias de Android),Utilice simplemente ejemplos reales.
        activityHub = GroupChatActivityHub()

        // init Cada flujo de observación en el valor predeterminado está vacío.,Evite el bloqueo de recopilación a nulo;Se cubrirán casos de uso específicos según sea necesario.
        every { repository.observeChats() } returns MutableStateFlow(emptyList())
        every { assistantRepository.observeAll } returns MutableStateFlow(emptyList())
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)
        every { repository.getPagedMessages(any(), any()) } returns MutableStateFlow(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** selectChat Cambiar chat grupal → currentChat Actualizado para seleccionar chat grupal(Núcleo de distribución de renderizado). */
    @Test
    fun `selectChat switches current chat and clears old messages`() {
        val chat1 = chat("chat1", "grupo uno")
        val chat2 = chat("chat2", "grupo 2")
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat1, chat2))
        every { repository.observeChat("chat2") } returns MutableStateFlow(chat2)
        buildViewModel()

        viewModel.selectChat("chat2")
        testScheduler.advanceUntilIdle()

        assertEquals(2, viewModel.state.value.chats.size)
        assertEquals("chat2", viewModel.state.value.currentChat?.id)
        assertTrue(viewModel.state.value.currentMessages.isEmpty())
    }

    /** Cargando lista de chat grupal:observeChats Los datos devueltos están correctamente asignados a state.chats(Cantidad/preservar el orden). */
    @Test
    fun `observeChats maps repository list into state`() {
        val chatNew = chat("chat2", "nuevo grupo", updatedAt = 200L)
        val chatOld = chat("chat1", "viejo grupo", updatedAt = 100L)
        // El almacén ha sido devuelto en orden descendente por actualizadoEn,VM Debe pasarse tal cual.
        every { repository.observeChats() } returns MutableStateFlow(listOf(chatNew, chatOld))
        buildViewModel()

        assertEquals(listOf(chatNew, chatOld), viewModel.state.value.chats)
        assertFalse(viewModel.state.value.isChatsLoading)
    }

    /** loadMoreHistory Paginación:Primera pantalla N elementos → Desliza hacia arriba para cargar el historial anterior,El número de elementos aumenta sin necesidad de insertarlos repetidamente. */
    @Test
    fun `loadMoreHistory prepends older messages without duplicates`() {
        val chatId = "chat2"
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat("chat2", "grupo 2")))
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)

        coEvery { repository.countMessages(chatId) } returns 5
        // En la mitad superior de la página: últimos 4 elementos(orden ascendente del tiempo ts=1..4),total=5 Por lo tanto hasMoreHistory=true
        val recent = (1L..4L).map { msg("m$it", chatId, "body$it", it) }
        coEvery { repository.getRecentMessagesPaged(chatId, any()) } returns recent
        // 2 antes(ts 0,1),Mucho menos que el tamaño de la página. → Después de cargar hasMoreHistory regresa false
        val older = listOf(
            msg("m-1", chatId, "older-1", 0L),
            msg("m0", chatId, "older0", 1L),
        )
        coEvery { repository.getOlderMessages(chatId, any(), any(), any()) } returns older

        buildViewModel()
        viewModel.selectChat(chatId)
        testScheduler.advanceUntilIdle()
        assertEquals(4, viewModel.state.value.currentMessages.size)
        assertTrue(viewModel.state.value.hasMoreHistory)

        viewModel.loadMoreHistory()
        testScheduler.advanceUntilIdle()

        val messages = viewModel.state.value.currentMessages
        assertEquals(6, messages.size)
        assertEquals(messages.size, messages.map { it.id }.toSet().size) // Sin inserción duplicada
        assertEquals(2, viewModel.state.value.lastHistoryLoadCount)
        assertFalse(viewModel.state.value.hasMoreHistory)
    }

    /** reinicio de estado:Los mensajes antiguos del chat grupal se borran después de cambiar de chat grupal(Evite las conversaciones cruzadas en los chats grupales). */
    @Test
    fun `switching chat resets previous chat messages`() {
        val chat2 = chat("chat2", "grupo 2")
        val chat3 = chat("chat3", "grupo tres")
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat2, chat3))
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)

        coEvery { repository.countMessages("chat2") } returns 2
        coEvery { repository.getRecentMessagesPaged("chat2", any()) } returns
            listOf(msg("a", "chat2", "hi", 100L), msg("b", "chat2", "yo", 200L))
        coEvery { repository.countMessages("chat3") } returns 0

        buildViewModel()
        viewModel.selectChat("chat2")
        testScheduler.advanceUntilIdle()
        assertEquals(2, viewModel.state.value.currentMessages.size)

        viewModel.selectChat("chat3")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.currentMessages.isEmpty())
        assertFalse(viewModel.state.value.hasMoreHistory)
    }

    /** enviar mensaje:Borre el cuadro de entrada + agregue un mensaje optimista + delegue el programador para activar la rotación(sendMessage Comportamiento comprobable de JVM). */
    @Test
    fun `sendMessage clears input and appends optimistic message`() {
        val chatId = "chat2"
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat("chat2", "grupo 2")))
        coEvery { repository.countMessages(chatId) } returns 0

        buildViewModel()
        viewModel.selectChat(chatId)
        testScheduler.advanceUntilIdle()

        viewModel.updateInput("  hola  ")
        viewModel.sendMessage("  hola  ")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("", state.inputText)
        assertEquals(1, state.currentMessages.size)
        assertTrue(state.currentMessages.first().id.startsWith("optimistic-"))
        // El programador delegado activa la rotación de agentes(Renderizar la ruta crítica distribuida a los trabajadores.).
        verify { scheduler.launchRoundRobin(chatId, "  hola  ", emptyList(), emptyList()) }
    }
}
