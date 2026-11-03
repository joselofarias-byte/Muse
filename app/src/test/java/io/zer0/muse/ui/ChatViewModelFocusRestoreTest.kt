package io.zer0.muse.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.ai.ChatService
import io.zer0.ai.image.ImageService
import io.zer0.ai.video.VideoGenerationService
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.FolderRepository
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.doc.OcrManager
import io.zer0.muse.network.NetworkMonitor
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.rag.RagService
import io.zer0.muse.schedule.ChatGenerationManager
import io.zer0.muse.schedule.UserActivityProfile
import io.zer0.muse.session.ConversationSessionManager
import io.zer0.muse.tools.AgentRouter
import io.zer0.muse.tools.DeferredResultStore
import io.zer0.muse.tools.DelegationChainTracker
import io.zer0.muse.tools.DelegationPauseManager
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.transformer.SystemPromptAssembler
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.vision.VisionBridge
import io.zer0.muse.vision.VisionProgress
import io.zer0.muse.web.WebSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-TEST-02: El enfoque de la sesión reanuda las pruebas de regresión.
 *
 * Anular la corrección del backend R-UI-02:
 *  - Una vez reconstruido el proceso, se restaurará primero la sesión vista cuando el usuario abandonó.,en lugar de sesiones activas recientes
 *  - outbox La reproducción solo restaura mensajes,No sobrescriba el foco de visualización actual
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatViewModelFocusRestoreTest {

    private val testDispatcher = StandardTestDispatcher()

    private val chatService: ChatService = mockk(relaxed = true)
    private val settings: SettingsRepository = mockk(relaxed = true)
    private val memoryTicker: MemoryTicker = mockk(relaxed = true)
    private val sessionRepository: SessionRepository = mockk(relaxed = true)
    private val checkpointReader: io.zer0.memory.summary.ContextCheckpointReader = mockk(relaxed = true)
    private val imageService: ImageService = mockk(relaxed = true)
    private val videoGenerationService: VideoGenerationService = mockk(relaxed = true)
    private val documentParser: DocumentParser = mockk(relaxed = true)
    private val toolRegistry: ToolRegistry = mockk(relaxed = true)
    private val assistantRepository: AssistantRepository = mockk(relaxed = true)
    private val webSearchService: WebSearchService = mockk(relaxed = true)
    private val lorebookRepository: LorebookRepository = mockk(relaxed = true)
    private val quickMessageRepository: QuickMessageRepository = mockk(relaxed = true)
    private val promptInjectionRepository: PromptInjectionRepository = mockk(relaxed = true)
    private val ocrManager: OcrManager = mockk(relaxed = true)
    private val ttsManager: TtsManager = mockk(relaxed = true)
    private val skillRepository: SkillRepository = mockk(relaxed = true)
    private val skillExecutor: SkillExecutor = mockk(relaxed = true)
    private val delegationPauseManager: DelegationPauseManager = mockk(relaxed = true)
    private val delegationChainTracker: DelegationChainTracker = mockk(relaxed = true)
    private val agentRouter: AgentRouter = mockk(relaxed = true)
    private val folderRepository: FolderRepository = mockk(relaxed = true)
    private val notificationManager: MuseNotificationManager = mockk(relaxed = true)
    private val systemPromptAssembler: SystemPromptAssembler = mockk(relaxed = true)
    private val chatGenerationManager: ChatGenerationManager = mockk(relaxed = true)
    private val artifactRepository: ArtifactRepository = mockk(relaxed = true)
    private val ragService: RagService = mockk(relaxed = true)
    private val visionBridge: VisionBridge = mockk(relaxed = true)
    private val auditLogger: AuditLogger = mockk(relaxed = true)
    private val sessionPermissionStore: SessionPermissionStore = mockk(relaxed = true)
    private val networkMonitor: NetworkMonitor = mockk(relaxed = true)
    private val deferredResultStore: DeferredResultStore = mockk(relaxed = true)
    private val subagentThreadStore: SubagentThreadStore = mockk(relaxed = true)
    private val sessionManager: ConversationSessionManager = mockk(relaxed = true)
    private val activityProfile: UserActivityProfile = mockk(relaxed = true)
    private lateinit var appContext: Context

    private lateinit var viewModel: ChatViewModel

    private val oldSession = SessionEntity(id = "session-old", title = "old", createdAt = 1L, updatedAt = 2L)
    private val newSession = SessionEntity(id = "session-new", title = "new", createdAt = 3L, updatedAt = 4L)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()

        every { sessionRepository.observeSessions() } returns flowOf(listOf(newSession, oldSession))
        every { settings.providerConfigFlow } returns emptyFlow()
        every { settings.providersFlow } returns emptyFlow()
        every { settings.activeProviderIdFlow } returns emptyFlow()
        every { settings.selectedModelIdFlow } returns emptyFlow()
        every { settings.toolModelIdFlow } returns emptyFlow()
        every { settings.mediaConfigFlow } returns emptyFlow()
        every { settings.multiAgentConfigFlow } returns emptyFlow()
        every { delegationChainTracker.chains } returns MutableStateFlow<Map<String, DelegationChainTracker.ChainNode>>(emptyMap())
        every { delegationPauseManager.activePauses } returns MutableStateFlow<Map<String, DelegationPauseManager.PauseRequest>>(emptyMap())
        every { chatGenerationManager.activeGeneration } returns MutableStateFlow<ChatGenerationManager.ActiveGeneration?>(null)
        // fcc18f6: ChatViewModel observar en su lugar activeGenerations(Generación concurrente multisesión),
        // Los resguardos de prueba deben proporcionar versiones en plural simultáneamente, De lo contrario, se lanzará un simulacro relajado al recoger KotlinNothingValueException
        every { chatGenerationManager.activeGenerations } returns MutableStateFlow(emptyMap())
        every { visionBridge.progressFlow } returns MutableStateFlow(VisionProgress(idle = true, index = 0, total = 0))
        every { deferredResultStore.completedTasks } returns MutableStateFlow(emptyMap())
        every { deferredResultStore.tasks } returns MutableStateFlow(emptyMap())
        coEvery { subagentThreadStore.listActiveThreads() } returns emptyList()
        coEvery { sessionRepository.getPendingOutbox() } returns emptyList()
        coEvery { sessionRepository.getMessageCount(any()) } returns 0
        coEvery { sessionRepository.getRecentMessages(any(), any()) } returns emptyList()
        coEvery { sessionRepository.getAssistantId(any()) } returns "default"
        // El cambio de sesión primero verificará si la sesión de destino existe (verificación de cadena profunda); Si este código auxiliar no está configurado, el simulacro relajado devolverá nulo.
        // switchSession Regresará temprano antes de escribir el enfoque, lo que hará que el "enfoque inicial" siempre sea nulo.
        coEvery { sessionRepository.getSessionById(any()) } answers {
            val id = firstArg<String>()
            listOf(newSession, oldSession).firstOrNull { it.id == id }
        }
        coEvery { assistantRepository.getById(any()) } returns null
        coEvery { settings.loadChatDraft(any()) } returns ""
        coEvery { settings.getViewedSessionId() } returns "session-old"
        every { settings.defaultSessionPermissionModeFlow } returns flowOf(io.zer0.muse.tools.SessionPermissionMode.ASK)

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        // Primero cancele las corrutinas dejadas por el caso de prueba anterior: continuarán ejecutándose en el programador de pruebas compartido.
        // que puede incluir nuevos cambios de sesión —— Eso aumentará el número de secuencia de SessionSwitchGuard,
        // El cambio propio del siguiente caso de uso se considerará "caducado" y se descartará directamente.
        if (::viewModel.isInitialized) {
            viewModel.viewModelScope.cancel()
        }
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ChatViewModel = ChatViewModel(
        chatService = chatService,
        settings = settings,
        memoryTicker = memoryTicker,
        checkpointReader = checkpointReader,
        sessionRepository = sessionRepository,
        imageService = imageService,
        videoGenerationService = videoGenerationService,
        documentParser = documentParser,
        toolRegistry = toolRegistry,
        assistantRepository = assistantRepository,
        webSearchService = webSearchService,
        lorebookRepository = lorebookRepository,
        quickMessageRepository = quickMessageRepository,
        promptInjectionRepository = promptInjectionRepository,
        ocrManager = ocrManager,
        ttsManager = ttsManager,
        skillRepository = skillRepository,
        skillExecutor = skillExecutor,
        delegationPauseManager = delegationPauseManager,
        delegationChainTracker = delegationChainTracker,
        agentRouter = agentRouter,
        folderRepository = folderRepository,
        notificationManager = notificationManager,
        systemPromptAssembler = systemPromptAssembler,
        chatGenerationManager = chatGenerationManager,
        artifactRepository = artifactRepository,
        appContext = appContext,
        ragService = ragService,
        visionBridge = visionBridge,
        auditLogger = auditLogger,
        sessionPermissionStore = sessionPermissionStore,
        networkMonitor = networkMonitor,
        activityProfile = activityProfile,
        deferredResultStore = deferredResultStore,
        subagentThreadStore = subagentThreadStore,
        sessionManager = sessionManager,
        toolOrchestrator = mockk(relaxed = true),
        toolApprovalRouter = io.zer0.muse.tools.ToolApprovalRouter(),
    )

    @Test
    fun `La recuperación del proceso prioriza las sesiones de visualización de restauración sobre las sesiones activas recientemente`() = runTest(testDispatcher) {
        val focus = awaitFocusSettled()

        assertEquals("Se debe restaurar la vista que el usuario vio mientras estaba fuera. session-old", "session-old", focus)
        coVerify(atLeast = 1) { settings.getViewedSessionId() }
        coVerify(atLeast = 1) { settings.saveViewedSessionId("session-old") }
    }

    @Test
    fun `outbox Reproducir sin cambiar el enfoque de la vista`() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceUntilIdle()
        // Invariantes:outbox La reproducción solo puede agregar mensajes y no puede mover el foco a la sesión generada.
        // "A qué sesión se restablece el foco después del inicio" es responsabilidad de otro caso de uso del mismo tipo; aquí no se afirma si se ha completado la restauración.
        // De lo contrario, las dos invariantes se entrelazarán y el problema de sincronización de una cubrirá el problema real de la otra cuando se ejecute a todo volumen.
        val focusBefore = viewModel.state.value.currentSessionId

        // Una vez iniciada la simulación, aparece un mensaje perteneciente a la sesión generada. outbox;Restaurar solo escribir mensajes,El foco no debe cambiarse a la sesión generada.
        coEvery { sessionRepository.getPendingOutbox() } returns listOf(
            io.zer0.muse.data.session.MessageOutboxEntity(
                id = "outbox-1",
                sessionId = "session-generating",
                userMessageId = "user-1",
                text = "hello",
                imageBase64Json = "[]",
                createdAt = System.currentTimeMillis(),
                assistantMessageId = "assistant-1",
            ),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        val focusAfter = viewModel.state.value.currentSessionId
        assertNotEquals("El foco no se debe mover a la sesión generada", "session-generating", focusAfter)
        // Si el foco cambia repentinamente a otra conversación durante la repetición, solo puede ser el resultado de restaurar su propio acuerdo.
        // todavía pertenece a“No cambiado por repetición”;Aquí sólo bloques“Cambiado por repetición para generar sesión.”Esta única cosa.
        if (focusBefore != null) {
            assertEquals("Las repeticiones no deben reescribir el foco que se ha resuelto.", focusBefore, focusAfter)
        }
        coVerify(exactly = 0) { settings.saveViewedSessionId("session-generating") }
    }

    /**
     * Bombee hasta que se complete la recuperación.
     *
     * La recuperación se bloquea en el primer inicio de la secuencia de la sesión. Un solo avance hasta Idle no garantiza que el lanzamiento haya sido programado.
     * Así que bombea unas cuantas veces más; cuando se alcanza el límite superior, se devuelve el valor actual y se entrega a la persona que llama para su afirmación.
     */
    private suspend fun awaitFocusSettled(): String? {
        repeat(20) {
            testDispatcher.scheduler.advanceUntilIdle()
            viewModel.state.value.currentSessionId?.let { return it }
            testDispatcher.scheduler.advanceTimeBy(50)
        }
        return viewModel.state.value.currentSessionId
    }
}
