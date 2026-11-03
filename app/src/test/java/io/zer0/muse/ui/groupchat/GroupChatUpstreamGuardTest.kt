package io.zer0.muse.ui.groupchat

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.MultiAgentConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatEntity
import io.zer0.muse.data.groupchat.GroupChatMessageEntity
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.schedule.GroupChatScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * El flujo de estado de la interfaz de usuario del ViewModel del chat grupal converge y regresa de manera anormal.
 *
 * Antecedentes:init adentro `viewModelScope.launch { flow.collect { } }` Antes no había habido líneas de defensa inusuales.
 * aguas arriba (Room/DataStore) arroja un error, o el propio bloque de recopilación arroja un error, se convertirá en una excepción de rutina no detectada:
 * La máquina real puede bloquear el proceso; se filtrará al siguiente caso de uso en la prueba `UncaughtExceptionsBeforeTest`
 * (WorkspaceBoundaryTest Por lo tanto escamoso).
 *
 * Aquí, StandardTestDispatcher se utiliza para ejecutar el viewModelScope real: si la excepción no converge,
 * El programador de pruebas lo generará como una excepción no detectada y el caso de uso se mostrará en rojo directamente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GroupChatUpstreamGuardTest {

    private lateinit var testScheduler: TestCoroutineScheduler
    private lateinit var repository: GroupChatRepository
    private lateinit var scheduler: GroupChatScheduler
    private lateinit var assistantRepository: AssistantRepository
    private lateinit var settings: SettingsRepository
    private lateinit var activityHub: GroupChatActivityHub

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun chat(id: String, name: String) = GroupChatEntity(
        id = id,
        name = name,
        memberIdsJson = "[]",
        updatedAt = 1L,
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

    @Before
    fun setup() {
        unmockkAll()
        clearAllMocks()
        testScheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        repository = mockk(relaxed = true)
        scheduler = mockk(relaxed = true)
        assistantRepository = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        every { settings.multiAgentConfigFlow } returns MutableStateFlow(MultiAgentConfig())
        every { settings.chatPreferencesFlow } returns MutableStateFlow(ChatPreferences())
        activityHub = GroupChatActivityHub()

        // De forma predeterminada, todos los flujos son normales y los casos de uso específicos se pueden anular en flujos que generarán errores.
        every { repository.observeChats() } returns MutableStateFlow(emptyList())
        every { assistantRepository.observeAll } returns MutableStateFlow(emptyList())
        every { repository.observeChat(any()) } returns MutableStateFlow<GroupChatEntity?>(null)
        every { repository.getPagedMessages(any(), any()) } returns MutableStateFlow(emptyList())
        // relaxed mock El valor de StateFlow arrojará KotlinNothingValueException, aquí está el verdadero StateFlow.
        every { scheduler.activeGroupGeneration } returns MutableStateFlow(null)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun buildViewModel(): GroupChatViewModel {
        val vm = GroupChatViewModel(
            groupChatRepository = repository,
            scheduler = scheduler,
            assistantRepository = assistantRepository,
            settings = settings,
            activityHub = activityHub,
            appContext = context,
            groupChatMemoryRepository = null,
        )
        testScheduler.advanceUntilIdle()
        return vm
    }

    /** El flujo ascendente arroja un error directamente: todo el flujo de recolección converge y el proceso/Ninguna prueba debería recibir excepciones no detectadas. */
    @Test
    fun `throwing upstream flow does not leak an uncaught exception`() {
        every { repository.observeChats() } returns flow { throw IllegalStateException("room exploded") }

        val vm = buildViewModel()

        // El coleccionista ha terminado,state Mantenga el valor predeterminado en lugar de fallar.
        assertTrue(vm.state.value.chats.isEmpty())
    }

    /** Si una secuencia falla, otras secuencias no se verán afectadas: la lista de asistentes aún puede volver al estado normal. */
    @Test
    fun `one failing collector does not stop the others`() {
        every { repository.observeChats() } returns flow { throw IllegalStateException("room exploded") }
        every { assistantRepository.observeAll } returns MutableStateFlow(
            listOf(AssistantEntity(id = "assistant-a", name = "asistenteA")),
        )

        val vm = buildViewModel()

        assertEquals("La falla del flujo de la lista de chat grupal no afecta el flujo de la lista del asistente", 1, vm.state.value.assistants.size)
        // La lista se bloquea y la marca de carga de la primera pantalla solo puede permanecer en su valor original (ya nada puede desactivarla).
        assertTrue("isChatsLoading conserva su valor original cuando falla el flujo de la lista", vm.state.value.isChatsLoading)
    }

    /** Después de que la secuencia de la ventana de mensajes se genera incorrectamente, el chat grupal aún puede recibir el mensaje a través de loadInitialPage, una ruta conectada directamente a la base de datos. */
    @Test
    fun `failing message window flow still allows initial page load`() {
        val chatId = "chat1"
        every { repository.observeChats() } returns MutableStateFlow(listOf(chat(chatId, "grupo uno")))
        every { repository.getPagedMessages(chatId, any()) } returns flow {
            throw IllegalStateException("paged query failed")
        }
        coEvery { repository.countMessages(chatId) } returns 1
        coEvery { repository.getRecentMessagesPaged(chatId, any()) } returns
            listOf(msg("m1", chatId, "hello", 1L))

        val vm = buildViewModel()
        vm.selectChat(chatId)
        testScheduler.advanceUntilIdle()

        assertEquals(1, vm.state.value.currentMessages.size)
    }

    /**
     * El bloque de recopilación posterior arroja un error (la verdadera forma de escama histórica: al tomar el campo de activeGroupGeneration
     * lanza KotlinNothingValueException) también debe converger.
     *
     * Tenga en cuenta que el punto de lanzamiento debe estar en el cuerpo de recogida: si en `scheduler.activeGroupGeneration` Esta propiedad se lanza cuando se lee,
     * Esta es una excepción normal en el bloque de inicio, que no pasa por la protección de flujo (es un error de la persona que llama y no está dentro del alcance de esta solución).
     */
    @Test
    fun `throwing collect body does not leak an uncaught exception`() {
        val brokenGeneration = mockk<GroupChatScheduler.ActiveGroupGeneration>()
        every { brokenGeneration.currentSpeakerId } throws IllegalStateException("speaker unavailable")
        every { scheduler.activeGroupGeneration } returns MutableStateFlow(brokenGeneration)
        every { assistantRepository.observeAll } returns MutableStateFlow(
            listOf(AssistantEntity(id = "assistant-a", name = "asistenteA")),
        )

        val vm = buildViewModel()

        // El flujo de la lista de ayuda se interrumpe dentro del cuerpo de recopilación, pero la máquina virtual no falla y otros flujos no se ven afectados.
        assertEquals(0, vm.state.value.assistants.size)
        assertEquals(false, vm.state.value.isAgentResponding)
    }
}
