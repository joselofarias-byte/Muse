package io.zer0.muse.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.zer0.ai.ChatService
import io.zer0.ai.core.ChatStreamEvent
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * ChatViewModel session mismatch Revertir pruebas de unidades lógicas(H1 Puntos de reparación).
 *
 * escenario de prueba:Después de que el usuario envía el mensaje,Channel El consumidor cambió de sesión antes de procesar la solicitud.,
 * En este momento, req.sessionId no coincide con currentSessionId(mismatch),
 * Los consumidores deberían:
 *  1. Eliminar mensajes optimistas de usuario/asistente agregados por enqueueSend
 *  2. restablecer isStreaming / isWaitingFirstToken
 *  3. No llamar a sesión de error sessionRepository.appendMessage
 *  4. no comienza launchStream(No enviar solicitud HTTP a la sesión de error)
 *
 * Puntos de implementación:
 *  - ChatViewModel Construir dependencias hasta 30.,Todo inyectado con MockK relajado.
 *  - appContext Realista con Robolectric Context(ToolConfigStore necesitar SharedPreferences)
 *  - Utilice StandardTestDispatcher para controlar el envío de rutinas de viewModelScope,asegurar
 *    "enviar → Cambiar de sesión → procesamiento del consumidor"El tiempo es controlable.
 *  - Pase ChatStateAccessor.update{} Establecer directamente currentSessionId,Simular efecto switchSession
 *  - chatGenerationManager.launchGeneration es burlado como no-op,Saltar la lógica de transmisión
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatViewModelSessionMismatchTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── Todo depende de mock(relaxed = true Permitir que los métodos no modificados devuelvan valores predeterminados) ──
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
    private lateinit var outboxPersisted: CompletableDeferred<Unit>

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()
        outboxPersisted = CompletableDeferred()

        // stub Retorno de flujo clave emptyFlow,Evite que la recopilación en init desencadene efectos secundarios
        // (observeSessions Devolver flujo vacío → no se creará automáticamente/Cambiar de sesión)
        every { sessionRepository.observeSessions() } returns emptyFlow()
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

        // appendMessage El valor predeterminado regresa exitosamente
        coEvery { sessionRepository.appendMessage(any(), any()) } returns "msg-id"
        coEvery { sessionRepository.insertOutbox(any()) } coAnswers {
            outboxPersisted.complete(Unit)
        }

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** estructura ChatViewModel,Inyecte todas las dependencias simuladas. */
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

    /**
     * Deje que la rutina de inicio en init termine de ejecutarse(emptyFlow La recogida se completa inmediatamente.,
     * channel El consumidor suspende esperas en un canal vacío).
     */
    private fun runInitCoroutines() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // ── caso de prueba ────────────────────────────────────────────────────────

    @Test
    fun `Cambiar de sesión al enviar un mensaje,Se eliminan las noticias optimistas`() = runTest(testDispatcher) {
        runInitCoroutines()

        // Establecer sesión actual A + texto de entrada
        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        // enviar mensaje(enqueueSend Ejecute sincrónicamente la actualización optimista + trySend en cola)
        viewModel.send()

        // Validar actualizaciones optimistas:user + assistant El mensaje de marcador de posición se ha agregado a la lista.
        val stateAfterSend = viewModel.state.value
        assertEquals("Debería haber 2 mensajes después de la actualización optimista.", 2, viewModel.messages.value.size)
        assertEquals(MessageRole.USER, viewModel.messages.value[0].role)
        assertEquals(MessageRole.ASSISTANT, viewModel.messages.value[1].role)

        // Simular cambio de sesión B(Antes de que el consumidor atienda la solicitud)
        viewModel.update { it.copy(currentSessionId = "session-B") }

        // Deje que el consumidor del canal maneje la solicitud.(En este momento currentSessionId=B ≠ req.sessionId=A)
        advanceUntilIdle()

        // Verifique que el mensaje optimista haya sido eliminado
        val stateAfterRollback = viewModel.state.value
        assertEquals("session mismatch La lista de mensajes debe estar vacía después de la reversión", 0, viewModel.messages.value.size)
    }

    @Test
    fun `session mismatch después de restablecer isStreaming y isWaitingFirstToken`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // Ingrese al estado de transmisión después de enviar
        assertTrue("Después de enviarlo debería estar en isStreaming", viewModel.state.value.isStreaming)
        assertTrue("Después de enviarlo debería estar en isWaitingFirstToken", viewModel.state.value.isWaitingFirstToken)

        // Activador de cambio de sesión mismatch
        viewModel.update { it.copy(currentSessionId = "session-B") }
        advanceUntilIdle()

        // Verificar que la bandera de transmisión esté restablecida
        assertFalse("mismatch Después de la reversión, isStreaming debería ser false", viewModel.state.value.isStreaming)
        assertFalse("mismatch Después de la reversión, isWaitingFirstToken debería ser false", viewModel.state.value.isWaitingFirstToken)
    }

    @Test
    fun `session mismatch no será llamado a la sesión de error después appendMessage`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // cambiar a sesión B,hacer req.sessionId=A con currentSessionId=B no hay coincidencia
        viewModel.update { it.copy(currentSessionId = "session-B") }
        advanceUntilIdle()

        // Verifique que nunca se llame a appendMessage(No escriba mensajes en la sesión de error.)
        coVerify(exactly = 0) { sessionRepository.appendMessage(any(), any()) }
    }

    @Test
    fun `session mismatch no comenzará después launchStream`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        viewModel.update { it.copy(currentSessionId = "session-B") }
        advanceUntilIdle()

        // launchGeneration Es la entrada a launchStream.;mismatch no debe ser llamado cuando
        // Nota:launchGeneration no suspend,Utilice verificar en lugar de coVerify
        verify(exactly = 0) {
            chatGenerationManager.launchGeneration(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `session Cuando coincida, llame a appendMessage normalmente y comience a transmitir`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // No cambiar de sesión,Deje que los consumidores lo manejen normalmente(sessionId partido)
        advanceUntilIdle()
        withTimeout(5_000) { outboxPersisted.await() }
        // outboxReady Después de que se complete Dispatchers.IO,La continuación del consumidor devuelve el despachador de prueba.
        advanceUntilIdle()

        // Verifique que appendMessage se llame una vez,Y el ID de sesión es correcto.
        coVerify(exactly = 1) { sessionRepository.appendMessage("session-A", any()) }

        // Verifique que se llame a launchGeneration(Ingresar a la transmisión)
        // Nota:launchGeneration no suspend,Utilice verificar en lugar de coVerify
        verify(atLeast = 1) {
            chatGenerationManager.launchGeneration(
                sessionId = "session-A",
                assistantId = any(),
                sessionTitle = any(),
                generationId = any(),
                block = any(),
            )
        }
    }

    @Test
    fun `La actualización optimista no se activa cuando se envía texto vacío y sin imagen`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "   ") }
        viewModel.send()

        // La entrada vacía debe ser directamente return,No se agregó ningún mensaje
        assertEquals(0, viewModel.messages.value.size)
        assertFalse(viewModel.state.value.isStreaming)
    }

    @Test
    fun `isStreaming Cuando sea cierto, el envío nuevamente no se repetirá en la cola`() = runTest(testDispatcher) {
        runInitCoroutines()

        viewModel.update { it.copy(currentSessionId = "session-A", input = "hello") }
        viewModel.send()

        // Después del primer envío isStreaming=true
        assertTrue(viewModel.state.value.isStreaming)
        val firstMsgCount = viewModel.messages.value.size

        // Configure la entrada nuevamente y envíe(Debería ser interceptado por el guardia de isStreaming.)
        viewModel.update { it.copy(input = "second message") }
        viewModel.send()

        // El número de mensajes no debe aumentar.
        assertEquals(firstMsgCount, viewModel.messages.value.size)
    }

    @Test
    fun `Agent En el modo de discrepancia, agentSessionId se utiliza para determinar`() = runTest(testDispatcher) {
        runInitCoroutines()

        // Agent modo:isAgentMode=true, agentSessionId=session-A
        viewModel.update {
            it.copy(isAgentMode = true, agentSessionId = "agent-session-A", input = "hello")
        }
        viewModel.send()

        // Validar actualizaciones optimistas
        assertEquals(2, viewModel.messages.value.size)

        // Cambiar agentSessionId Simular cambio de pestaña Agente
        viewModel.update { it.copy(agentSessionId = "agent-session-B") }
        advanceUntilIdle()

        // Verificar que se eliminen los mensajes optimistas
        assertEquals(0, viewModel.messages.value.size)
        assertFalse(viewModel.state.value.isStreaming)

        // Validar no llamar a una sesión incorrecta appendMessage
        coVerify(exactly = 0) { sessionRepository.appendMessage(any(), any()) }
    }

    @Test
    fun `interrupted streamed translation keeps partial text but does not persist as complete`() = runBlocking {
        runInitCoroutines()
        val source = UIMessage(role = MessageRole.USER, content = "source text")
        viewModel.update { it.copy(currentSessionId = "session-A", errors = emptyList()) }
        viewModel.updateMessages { listOf(source) }
        coEvery {
            chatService.streamChat(
                messages = any(),
                model = any(),
                temperature = any(),
                maxTokens = any(),
                tools = any(),
                toolChoice = any(),
                nativeWebSearch = any(),
                reasoningLevel = any(),
                providerConfig = any(),
                mode = any(),
                resumeFromText = any(),
                topP = any(),
            )
        } returns flowOf(
            ChatStreamEvent.ContentDelta("partial translation"),
            ChatStreamEvent.StreamInterrupted("network interrupted", java.io.IOException("socket closed")),
        )

        viewModel.translateMessage(source.id)

        val completedState = withTimeout(5_000) {
            viewModel.state.first { !it.isTranslating && it.translatingMessageId == null }
        }

        assertTrue("an interrupted SSE translation must surface a failure", completedState.errors.isNotEmpty())
        // v2.4.6: La traducción se cuelga de las traducciones del mensaje fuente.(No más mensajes de apertura del asistente)
        val partialSource = viewModel.messages.value.first { it.id == source.id }
        assertTrue(partialSource.translations.values.firstOrNull()?.endsWith("partial translation") == true)
        coVerify(exactly = 0) { sessionRepository.appendMessage("session-A", any()) }
    }

    @Test
    fun `stopping streamed translation keeps deltas already shown in the chat`() = runBlocking {
        runInitCoroutines()
        val source = UIMessage(role = MessageRole.USER, content = "source text")
        val firstDeltaApplied = CompletableDeferred<Unit>()
        viewModel.update { it.copy(currentSessionId = "session-A", errors = emptyList()) }
        viewModel.updateMessages { listOf(source) }
        coEvery {
            chatService.streamChat(
                messages = any(),
                model = any(),
                temperature = any(),
                maxTokens = any(),
                tools = any(),
                toolChoice = any(),
                nativeWebSearch = any(),
                reasoningLevel = any(),
                providerConfig = any(),
                mode = any(),
                resumeFromText = any(),
                topP = any(),
            )
        } returns flow {
            emit(ChatStreamEvent.ContentDelta("partial translation"))
            firstDeltaApplied.complete(Unit)
            awaitCancellation()
        }

        viewModel.translateMessage(source.id)
        withTimeout(5_000) { firstDeltaApplied.await() }

        viewModel.stop()

        withTimeout(5_000) {
            viewModel.state.first { !it.isTranslating && it.translatingMessageId == null }
        }
        // v2.4.6: La traducción se cuelga de las traducciones del mensaje fuente.(No más mensajes de apertura del asistente)
        val partialSource = viewModel.messages.value.first { it.id == source.id }
        assertTrue(partialSource.translations.values.firstOrNull()?.endsWith("partial translation") == true)
        coVerify(exactly = 0) { sessionRepository.appendMessage("session-A", any()) }
    }
}
